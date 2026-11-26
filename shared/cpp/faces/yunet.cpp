#include "yunet.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>
#include <utility>

namespace faces {
namespace {

constexpr std::int64_t kMaxElements = 1 << 24;
constexpr int kMaxSide = 4096;
constexpr int kMaxFaces = 32;

struct Reader {
    const std::uint8_t* p;
    const std::uint8_t* end;

    bool more() const { return p < end; }

    std::uint64_t varint() {
        std::uint64_t value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (p >= end) break;
            const std::uint8_t b = *p++;
            value |= std::uint64_t(b & 0x7f) << shift;
            if (!(b & 0x80)) return value;
        }
        throw std::runtime_error("The face model is damaged");
    }

    void advance(std::uint64_t n) {
        if (n > std::uint64_t(end - p)) throw std::runtime_error("The face model is damaged");
        p += n;
    }

    Reader bytes() {
        const std::uint64_t n = varint();
        Reader inner{p, p};
        advance(n);
        inner.end = p;
        return inner;
    }

    std::string text() {
        Reader r = bytes();
        return std::string(reinterpret_cast<const char*>(r.p), r.end - r.p);
    }

    void skip(int wire) {
        if (wire == 0) varint();
        else if (wire == 1) advance(8);
        else if (wire == 2) bytes();
        else if (wire == 5) advance(4);
        else throw std::runtime_error("The face model is damaged");
    }
};

struct Tensor {
    int c = 0, h = 0, w = 0;
    std::vector<float> data;
    Tensor() = default;
    Tensor(int channels, int height, int width) : c(channels), h(height), w(width) {
        if (c < 1 || h < 1 || w < 1 || std::int64_t(c) * h * w > kMaxElements) throw std::runtime_error("Face model tensor out of range");
        data.assign(std::size_t(c) * h * w, 0.0f);
    }
    float* plane(int k) { return data.data() + std::size_t(k) * h * w; }
    const float* plane(int k) const { return data.data() + std::size_t(k) * h * w; }
};

struct Weights {
    std::vector<std::int64_t> dims;
    std::vector<float> values;
};

struct Node {
    std::string op;
    std::vector<std::string> in, out;
    std::map<std::string, std::vector<std::int64_t>> ints;

    std::int64_t get(const char* name, std::int64_t fallback, std::size_t i = 0) const {
        auto found = ints.find(name);
        return found == ints.end() || found->second.size() <= i ? fallback : found->second[i];
    }
};

void Ints(Reader& r, int wire, std::vector<std::int64_t>* out) {
    if (wire == 0) {
        out->push_back(std::int64_t(r.varint()));
        return;
    }
    Reader inner = r.bytes();
    while (inner.more()) out->push_back(std::int64_t(inner.varint()));
}

std::pair<std::string, Weights> ParseTensor(Reader r) {
    std::string name;
    Weights w;
    std::int64_t type = 1;
    const std::uint8_t* raw = nullptr;
    std::size_t rawSize = 0;
    std::vector<float> floats;
    while (r.more()) {
        const std::uint64_t key = r.varint();
        const int field = int(key >> 3), wire = int(key & 7);
        if (field == 1) {
            Ints(r, wire, &w.dims);
        } else if (field == 2 && wire == 0) {
            type = std::int64_t(r.varint());
        } else if (field == 8 && wire == 2) {
            name = r.text();
        } else if (field == 9 && wire == 2) {
            Reader inner = r.bytes();
            raw = inner.p;
            rawSize = std::size_t(inner.end - inner.p);
        } else if (field == 4 && wire == 2) {
            Reader inner = r.bytes();
            const std::size_t n = std::size_t(inner.end - inner.p) / 4;
            floats.resize(n);
            std::memcpy(floats.data(), inner.p, n * 4);
        } else {
            r.skip(wire);
        }
    }
    std::int64_t count = 1;
    for (std::int64_t d : w.dims) {
        if (d < 0 || d > kMaxElements) throw std::runtime_error("Face model tensor out of range");
        count *= d;
        if (count > kMaxElements) throw std::runtime_error("Face model tensor out of range");
    }
    if (type == 1) {
        if (raw) {
            if (rawSize != std::size_t(count) * 4) throw std::runtime_error("The face model is damaged");
            w.values.resize(std::size_t(count));
            std::memcpy(w.values.data(), raw, rawSize);
        } else if (std::int64_t(floats.size()) == count) {
            w.values = std::move(floats);
        } else {
            throw std::runtime_error("The face model is damaged");
        }
        for (float v : w.values) if (!std::isfinite(v)) throw std::runtime_error("The face model is damaged");
    }
    return {name, std::move(w)};
}

Node ParseNode(Reader r) {
    Node n;
    while (r.more()) {
        const std::uint64_t key = r.varint();
        const int field = int(key >> 3), wire = int(key & 7);
        if (field == 1 && wire == 2) n.in.push_back(r.text());
        else if (field == 2 && wire == 2) n.out.push_back(r.text());
        else if (field == 4 && wire == 2) n.op = r.text();
        else if (field == 5 && wire == 2) {
            Reader a = r.bytes();
            std::string name;
            std::vector<std::int64_t> values;
            while (a.more()) {
                const std::uint64_t k = a.varint();
                const int f = int(k >> 3), wa = int(k & 7);
                if (f == 1 && wa == 2) name = a.text();
                else if (f == 3 && wa == 0) values.push_back(std::int64_t(a.varint()));
                else if (f == 8) Ints(a, wa, &values);
                else a.skip(wa);
            }
            n.ints[name] = std::move(values);
        } else r.skip(wire);
    }
    return n;
}

void Convolve(const Tensor& x, const Weights& w, const Weights* bias, int stride, int pad, int group, Tensor* out) {
    if (w.dims.size() != 4 || w.dims[2] != w.dims[3]) throw std::runtime_error("Unsupported face model convolution");
    const int co = int(w.dims[0]), cig = int(w.dims[1]), k = int(w.dims[2]);
    if (group < 1 || stride < 1 || pad < 0 || co % group || x.c != cig * group || (bias && int(bias->values.size()) != co))
        throw std::runtime_error("Unsupported face model convolution");
    const int oh = (x.h + 2 * pad - k) / stride + 1, ow = (x.w + 2 * pad - k) / stride + 1;
    *out = Tensor(co, oh, ow);
    const int cog = co / group;
    const bool pointwise = k == 1 && stride == 1 && pad == 0;
    for (int o = 0; o < co; o++) {
        float* dst = out->plane(o);
        std::fill(dst, dst + std::size_t(oh) * ow, bias ? bias->values[o] : 0.0f);
        const int g = o / cog;
        for (int ic = 0; ic < cig; ic++) {
            const float* src = x.plane(g * cig + ic);
            const float* wk = w.values.data() + (std::size_t(o) * cig + ic) * k * k;
            if (pointwise) {
                const float wt = wk[0];
                const std::size_t n = std::size_t(oh) * ow;
                for (std::size_t j = 0; j < n; j++) dst[j] += src[j] * wt;
                continue;
            }
            for (int ky = 0; ky < k; ky++) {
                for (int kx = 0; kx < k; kx++) {
                    const float wt = wk[ky * k + kx];
                    const int first = std::max(0, (pad - kx + stride - 1) / stride);
                    const int last = std::min(ow, (x.w - 1 - kx + pad) / stride + 1);
                    for (int y = 0; y < oh; y++) {
                        const int iy = y * stride + ky - pad;
                        if (iy < 0 || iy >= x.h) continue;
                        float* row = dst + std::size_t(y) * ow;
                        const float* in = src + std::size_t(iy) * x.w + kx - pad;
                        if (stride == 1) {
                            for (int j = first; j < last; j++) row[j] += in[j] * wt;
                        } else {
                            for (int j = first; j < last; j++) row[j] += in[j * stride] * wt;
                        }
                    }
                }
            }
        }
    }
}

float Overlap(const Face& a, const Face& b) {
    const float x0 = std::max(a.x, b.x), y0 = std::max(a.y, b.y);
    const float x1 = std::min(a.x + a.width, b.x + b.width), y1 = std::min(a.y + a.height, b.y + b.height);
    const float common = std::max(0.0f, x1 - x0) * std::max(0.0f, y1 - y0);
    const float both = a.width * a.height + b.width * b.height - common;
    return both > 0 ? common / both : 0.0f;
}

}

struct Detector::Graph {
    std::string input;
    std::vector<Node> nodes;
    std::map<std::string, Weights> weights;
};

Detector::Detector(const std::uint8_t* onnx, std::size_t size) : graph_(new Graph) {
    if (!onnx || size < 16) throw std::runtime_error("The face model is missing");
    Reader model{onnx, onnx + size};
    bool found = false;
    while (model.more()) {
        const std::uint64_t key = model.varint();
        const int field = int(key >> 3), wire = int(key & 7);
        if (field != 7 || wire != 2) {
            model.skip(wire);
            continue;
        }
        found = true;
        Reader graph = model.bytes();
        std::vector<std::string> inputs;
        while (graph.more()) {
            const std::uint64_t k = graph.varint();
            const int f = int(k >> 3), w = int(k & 7);
            if (f == 1 && w == 2) graph_->nodes.push_back(ParseNode(graph.bytes()));
            else if (f == 5 && w == 2) graph_->weights.insert(ParseTensor(graph.bytes()));
            else if (f == 11 && w == 2) {
                Reader info = graph.bytes();
                while (info.more()) {
                    const std::uint64_t ik = info.varint();
                    if (int(ik >> 3) == 1 && int(ik & 7) == 2) inputs.push_back(info.text());
                    else info.skip(int(ik & 7));
                }
            } else graph.skip(w);
        }
        for (const std::string& name : inputs) {
            if (!graph_->weights.count(name)) {
                graph_->input = name;
                break;
            }
        }
    }
    static const char* const kOps[] = {"Conv", "Relu", "MaxPool", "Resize", "Add", "Sigmoid", "Transpose", "Reshape"};
    for (const Node& n : graph_->nodes) {
        if (std::none_of(std::begin(kOps), std::end(kOps), [&](const char* op) { return n.op == op; }) || n.in.empty() || n.out.size() != 1)
            throw std::runtime_error("The face model uses an unsupported operation");
    }
    if (!found || graph_->input.empty() || graph_->nodes.empty()) throw std::runtime_error("The face model is damaged");
}

Detector::~Detector() = default;

std::vector<Face> Detector::detect(const std::uint8_t* rgb, int width, int height, float threshold) const {
    if (!rgb || width < 1 || height < 1 || width > kMaxSide || height > kMaxSide) throw std::invalid_argument("Face detection needs an RGB picture");
    const int padW = (width + 31) / 32 * 32, padH = (height + 31) / 32 * 32;
    std::map<std::string, Tensor> values;
    std::map<std::string, int> uses;
    for (const Node& n : graph_->nodes) for (const std::string& name : n.in) uses[name]++;
    Tensor& input = values[graph_->input] = Tensor(3, padH, padW);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const std::uint8_t* px = rgb + (std::size_t(y) * width + x) * 3;
            for (int ch = 0; ch < 3; ch++) input.plane(ch)[std::size_t(y) * padW + x] = px[2 - ch];
        }
    }
    static const char* const kKept[] = {"cls_8", "cls_16", "cls_32", "obj_8", "obj_16", "obj_32", "bbox_8", "bbox_16", "bbox_32"};
    for (const char* name : kKept) uses[name]++;
    for (const Node& n : graph_->nodes) {
        auto in = values.find(n.in[0]);
        if (in == values.end()) throw std::runtime_error("The face model is damaged");
        Tensor out;
        if (n.op == "Conv") {
            auto w = graph_->weights.find(n.in.size() > 1 ? n.in[1] : "");
            auto b = graph_->weights.find(n.in.size() > 2 ? n.in[2] : "");
            if (w == graph_->weights.end() || n.get("dilations", 1) != 1) throw std::runtime_error("Unsupported face model convolution");
            if (n.get("pads", 0, 0) != n.get("pads", 0, 2) || n.get("pads", 0, 0) != n.get("pads", 0, 1) || n.get("strides", 1, 0) != n.get("strides", 1, 1))
                throw std::runtime_error("Unsupported face model convolution");
            Convolve(in->second, w->second, b == graph_->weights.end() ? nullptr : &b->second, int(n.get("strides", 1)), int(n.get("pads", 0)),
                     int(n.get("group", 1)), &out);
        } else if (n.op == "Relu" || n.op == "Sigmoid") {
            out = in->second;
            if (n.op == "Relu") for (float& v : out.data) v = std::max(v, 0.0f);
            else for (float& v : out.data) v = 1.0f / (1.0f + std::exp(-v));
        } else if (n.op == "MaxPool") {
            if (n.get("kernel_shape", 0) != 2 || n.get("strides", 0) != 2 || n.get("pads", 0) != 0) throw std::runtime_error("Unsupported face model pooling");
            const Tensor& x = in->second;
            out = Tensor(x.c, x.h / 2, x.w / 2);
            for (int c = 0; c < x.c; c++) {
                for (int y = 0; y < out.h; y++) {
                    const float* a = x.plane(c) + std::size_t(2 * y) * x.w;
                    const float* b = a + x.w;
                    float* dst = out.plane(c) + std::size_t(y) * out.w;
                    for (int j = 0; j < out.w; j++) dst[j] = std::max(std::max(a[2 * j], a[2 * j + 1]), std::max(b[2 * j], b[2 * j + 1]));
                }
            }
        } else if (n.op == "Resize") {
            auto scales = graph_->weights.find(n.in.size() > 2 ? n.in[2] : "");
            if (scales == graph_->weights.end() || scales->second.values.size() != 4) throw std::runtime_error("Unsupported face model resize");
            const float sy = scales->second.values[2], sx = scales->second.values[3];
            if (!(sy >= 1 && sy <= 8 && sx >= 1 && sx <= 8)) throw std::runtime_error("Unsupported face model resize");
            const Tensor& x = in->second;
            out = Tensor(x.c, int(std::floor(x.h * sy)), int(std::floor(x.w * sx)));
            for (int c = 0; c < x.c; c++) {
                for (int y = 0; y < out.h; y++) {
                    const float* src = x.plane(c) + std::size_t(std::min(x.h - 1, int(std::floor(y / sy)))) * x.w;
                    float* dst = out.plane(c) + std::size_t(y) * out.w;
                    for (int j = 0; j < out.w; j++) dst[j] = src[std::min(x.w - 1, int(std::floor(j / sx)))];
                }
            }
        } else if (n.op == "Add") {
            auto other = values.find(n.in.size() > 1 ? n.in[1] : "");
            if (other == values.end() || other->second.c != in->second.c || other->second.h != in->second.h || other->second.w != in->second.w)
                throw std::runtime_error("The face model is damaged");
            out = in->second;
            for (std::size_t j = 0; j < out.data.size(); j++) out.data[j] += other->second.data[j];
        } else {
            out = in->second;
        }
        for (const std::string& name : n.in) {
            if (--uses[name] <= 0) values.erase(name);
        }
        values[n.out[0]] = std::move(out);
    }
    std::vector<Face> found;
    for (int stride : {8, 16, 32}) {
        const std::string s = std::to_string(stride);
        auto cls = values.find("cls_" + s), obj = values.find("obj_" + s), box = values.find("bbox_" + s);
        if (cls == values.end() || obj == values.end() || box == values.end() || box->second.c != 4) throw std::runtime_error("The face model is damaged");
        const Tensor& c = cls->second;
        for (int r = 0; r < c.h; r++) {
            for (int col = 0; col < c.w; col++) {
                const std::size_t at = std::size_t(r) * c.w + col;
                const float score = std::sqrt(std::clamp(c.data[at], 0.0f, 1.0f) * std::clamp(obj->second.data[at], 0.0f, 1.0f));
                if (!(score >= threshold)) continue;
                const float cx = (col + box->second.plane(0)[at]) * stride, cy = (r + box->second.plane(1)[at]) * stride;
                const float w = std::exp(std::min(box->second.plane(2)[at], 8.0f)) * stride, h = std::exp(std::min(box->second.plane(3)[at], 8.0f)) * stride;
                found.push_back({cx - w / 2, cy - h / 2, w, h, score});
            }
        }
    }
    std::sort(found.begin(), found.end(), [](const Face& a, const Face& b) { return a.score > b.score; });
    std::vector<Face> kept;
    for (const Face& f : found) {
        if (int(kept.size()) >= kMaxFaces) break;
        if (std::all_of(kept.begin(), kept.end(), [&](const Face& k) { return Overlap(f, k) < 0.3f; })) kept.push_back(f);
    }
    return kept;
}

}
