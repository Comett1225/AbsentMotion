#include "cutout.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>

namespace cutout {
namespace {

constexpr int Band = 256;
constexpr int Fine = 8;
constexpr int Precision = 22;
constexpr int Channels = 7;

template <typename T>
struct Buffer {
    T *data;
    explicit Buffer(size_t count) : data(static_cast<T *>(std::malloc(std::max<size_t>(count, 1) * sizeof(T)))) {}
    ~Buffer() { std::free(data); }
    Buffer(const Buffer &) = delete;
    Buffer &operator=(const Buffer &) = delete;
    T &operator[](size_t i) { return data[i]; }
    const T &operator[](size_t i) const { return data[i]; }
};

struct Taps {
    Buffer<int> i0, i1;
    Buffer<float> f;

    Taps(int nOut, int nIn, int lo, int hi) : i0(hi - lo), i1(hi - lo), f(hi - lo) {
        if (!ok()) return;
        for (int i = lo; i < hi; i++) {
            float s = std::max((static_cast<float>(i) + .5f) * static_cast<float>(nIn) / static_cast<float>(nOut) - .5f, 0.f);
            int a = std::min(static_cast<int>(s), nIn - 1);
            i0[i - lo] = a;
            i1[i - lo] = std::min(a + 1, nIn - 1);
            f[i - lo] = s - static_cast<float>(a);
        }
    }

    bool ok() const { return i0.data && i1.data && f.data; }
};

inline float mix(float a00, float a01, float a10, float a11, float fx, float fy) {
    return (a00 * (1 - fx) + a01 * fx) * (1 - fy) + (a10 * (1 - fx) + a11 * fx) * fy;
}

inline uint8_t byte(float v) { return static_cast<uint8_t>(std::clamp(std::nearbyint(v * 255.f), 0.f, 255.f)); }

struct Weights {
    Buffer<int> start, count, k;
    int span;
    bool ready = false;

    Weights(int in, int out) : start(out), count(out), k(static_cast<size_t>(out) * (static_cast<int>(std::ceil(0.5 * std::max(static_cast<double>(in) / out, 1.0))) * 2 + 1)),
                               span(static_cast<int>(std::ceil(0.5 * std::max(static_cast<double>(in) / out, 1.0))) * 2 + 1) {
        if (!start.data || !count.data || !k.data) return;
        double scale = static_cast<double>(in) / out, filter = std::max(scale, 1.0), support = 0.5 * filter;
        Buffer<double> w(span);
        if (!w.data) return;
        for (int o = 0; o < out; o++) {
            double center = (o + 0.5) * scale, sum = 0;
            int lo = std::max(static_cast<int>(center - support + 0.5), 0);
            int n = std::min(static_cast<int>(center + support + 0.5), in) - lo;
            n = std::clamp(n, 0, span);
            for (int x = 0; x < n; x++) {
                double t = (x + lo - center + 0.5) / filter;
                w[x] = t > -0.5 && t <= 0.5 ? 1.0 : 0.0;
                sum += w[x];
            }
            start[o] = lo;
            count[o] = n;
            for (int x = 0; x < n; x++) k[static_cast<size_t>(o) * span + x] = static_cast<int>(0.5 + (sum != 0 ? w[x] / sum : w[x]) * (1 << Precision));
        }
        ready = true;
    }

    bool ok() const { return ready; }

    uint8_t apply(int o, const uint8_t *src, size_t step) const {
        int64_t s = 1 << (Precision - 1);
        const int *weights = k.data + static_cast<size_t>(o) * span;
        const uint8_t *p = src + start[o] * step;
        for (int x = 0; x < count[o]; x++) s += static_cast<int64_t>(p[x * step]) * weights[x];
        return static_cast<uint8_t>(std::clamp<int64_t>(s >> Precision, 0, 255));
    }
};

bool shrink(const uint8_t *rgba, size_t stride, int width, int height, int w, int h, float *out) {
    Weights across(width, w), down(height, h);
    Buffer<uint8_t> rows(static_cast<size_t>(height) * w * 3);
    if (!across.ok() || !down.ok() || !rows.data) return false;
    for (int y = 0; y < height; y++) {
        const uint8_t *row = rgba + y * stride;
        uint8_t *dst = rows.data + static_cast<size_t>(y) * w * 3;
        for (int x = 0; x < w; x++)
            for (int c = 0; c < 3; c++) dst[x * 3 + c] = across.apply(x, row + c, 4);
    }
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w * 3; x++) out[static_cast<size_t>(y) * w * 3 + x] = static_cast<float>(down.apply(y, rows.data + x, static_cast<size_t>(w) * 3)) / 255.f;
    return true;
}

bool box(const float *src, float *dst, int h, int w, int ch, int r) {
    const size_t line = static_cast<size_t>(w) * ch;
    Buffer<float> tmp(static_cast<size_t>(h) * line);
    Buffer<double> acc(std::max(line, static_cast<size_t>(ch)));
    if (!tmp.data || !acc.data) return false;
    const double size = 2 * r + 1;
    for (size_t j = 0; j < line; j++) acc[j] = 0;
    for (int k = -r; k <= r; k++) {
        const float *row = src + std::clamp(k, 0, h - 1) * line;
        for (size_t j = 0; j < line; j++) acc[j] += row[j];
    }
    for (int y = 0; y < h; y++) {
        float *out = tmp.data + y * line;
        const float *in = src + std::clamp(y + r + 1, 0, h - 1) * line;
        const float *gone = src + std::clamp(y - r, 0, h - 1) * line;
        for (size_t j = 0; j < line; j++) {
            out[j] = static_cast<float>(acc[j] / size);
            acc[j] += static_cast<double>(in[j]) - gone[j];
        }
    }
    for (int y = 0; y < h; y++) {
        const float *row = tmp.data + y * line;
        float *out = dst + y * line;
        for (int c = 0; c < ch; c++) {
            double s = 0;
            for (int k = -r; k <= r; k++) s += row[std::clamp(k, 0, w - 1) * ch + c];
            for (int x = 0; x < w; x++) {
                out[x * ch + c] = static_cast<float>(s / size);
                s += static_cast<double>(row[std::clamp(x + r + 1, 0, w - 1) * ch + c]) - row[std::clamp(x - r, 0, w - 1) * ch + c];
            }
        }
    }
    return true;
}

bool lowFuse(const float *image, const float *alpha, int w, int h, float *fore, float *back) {
    const size_t count = static_cast<size_t>(w) * h;
    Buffer<float> packed(count * Channels), boxed(count * Channels);
    if (!packed.data || !boxed.data) return false;
    for (size_t i = 0; i < count; i++) {
        float a = alpha[i];
        float *p = packed.data + i * Channels;
        p[0] = a;
        for (int c = 0; c < 3; c++) {
            p[1 + c] = image[i * 3 + c] * a;
            p[4 + c] = image[i * 3 + c] * (1 - a);
        }
    }
    if (!box(packed.data, boxed.data, h, w, Channels, std::max(2, static_cast<int>(90.0 * w / 1024)))) return false;
    for (size_t i = 0; i < count; i++) {
        const float *s = boxed.data + i * Channels;
        float a = alpha[i];
        for (int c = 0; c < 3; c++) {
            float f = s[1 + c] / (s[0] + 1e-5f);
            float b = s[4 + c] / ((1 - s[0]) + 1e-5f);
            float e = image[i * 3 + c] - a * f - (1 - a) * b;
            fore[i * 3 + c] = std::clamp(f + a * e, 0.f, 1.f);
            back[i * 3 + c] = std::clamp(b + (1 - a) * e, 0.f, 1.f);
        }
    }
    return true;
}

inline bool edge(float a) { return a > .004f && a < .996f; }

}

Letterbox letterbox(int width, int height, int size) {
    double scale = std::min(static_cast<double>(size) / width, static_cast<double>(size) / height);
    int w = std::max(1, static_cast<int>(width * scale + .5));
    int h = std::max(1, static_cast<int>(height * scale + .5));
    return {(size - w) / 2, (size - h) / 2, w, h};
}

void unpremultiply(uint8_t *rgba, size_t stride, int width, int height) {
    for (int y = 0; y < height; y++) {
        uint8_t *p = rgba + y * stride;
        for (int x = 0; x < width; x++, p += 4) {
            int a = p[3];
            if (a == 255) continue;
            for (int c = 0; c < 3; c++) p[c] = a == 0 ? 0 : static_cast<uint8_t>(std::min(255, (p[c] * 255 + a / 2) / a));
        }
    }
}

bool netInput(const uint8_t *rgba, size_t stride, int width, int height, int size, const Letterbox &box, float *planes) {
    const size_t plane = static_cast<size_t>(size) * size;
    std::fill(planes, planes + 3 * plane, .5f);
    Taps ys(box.height, height, 0, box.height), xs(box.width, width, 0, box.width);
    if (!ys.ok() || !xs.ok()) return false;
    for (int y = 0; y < box.height; y++) {
        const uint8_t *r0 = rgba + ys.i0[y] * stride, *r1 = rgba + ys.i1[y] * stride;
        float fy = ys.f[y];
        size_t at = static_cast<size_t>(box.top + y) * size + box.left;
        for (int x = 0; x < box.width; x++) {
            int c0 = xs.i0[x] * 4, c1 = xs.i1[x] * 4;
            float fx = xs.f[x];
            for (int c = 0; c < 3; c++)
                planes[c * plane + at + x] = mix(r0[c0 + c] / 255.f, r0[c1 + c] / 255.f, r1[c0 + c] / 255.f, r1[c1 + c] / 255.f, fx, fy);
        }
    }
    return true;
}

void clean(float *alpha, int w, int h) {
    const size_t n = static_cast<size_t>(w) * h;
    const int reach = std::max(2, static_cast<int>(std::lround(8.0 * std::max(w, h) / 1024.0)));
    const size_t small = std::max<size_t>(16, static_cast<size_t>(n * 0.0003));
    constexpr float Floor = .04f, Ceil = .12f, Core = .6f, Faint = .3f;
    Buffer<int> label(n), queue(n);
    Buffer<uint8_t> alive(n), near(n), grown(n);
    if (!label.data || !queue.data || !alive.data || !near.data || !grown.data) return;
    std::fill(label.data, label.data + n, -1);
    std::fill(alive.data, alive.data + n, 0);
    int found = 0;
    for (size_t seed = 0; seed < n; seed++) {
        if (alpha[seed] < Ceil || label[seed] >= 0) continue;
        size_t head = 0, tail = 0, area = 0, core = 0;
        float peak = 0;
        queue[tail++] = static_cast<int>(seed);
        label[seed] = 0;
        while (head < tail) {
            const int at = queue[head++];
            area++;
            if (alpha[at] >= Core) core++;
            peak = std::max(peak, alpha[at]);
            const int x = at % w, y = at / w;
            const int next[4] = {x > 0 ? at - 1 : -1, x + 1 < w ? at + 1 : -1, y > 0 ? at - w : -1, y + 1 < h ? at + w : -1};
            for (int to : next)
                if (to >= 0 && label[to] < 0 && alpha[to] >= Ceil) {
                    label[to] = 0;
                    queue[tail++] = to;
                }
        }
        const bool keep = core >= small || (peak >= Faint && area >= small * 8);
        if (keep) found++;
        for (size_t i = 0; i < tail; i++) alive[queue[i]] = keep ? 1 : 2;
    }
    if (found == 0) return;
    for (size_t i = 0; i < n; i++) near[i] = alive[i] == 1;
    for (int pass = 0; pass < 2; pass++) {
        const bool rows = pass == 0;
        const int length = rows ? w : h, lines = rows ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                const size_t at = rows ? static_cast<size_t>(line) * w + i : static_cast<size_t>(i) * w + line;
                uint8_t hit = 0;
                const int lo = std::max(0, i - reach), hi = std::min(length - 1, i + reach);
                for (int k = lo; k <= hi && !hit; k++) hit = near[rows ? static_cast<size_t>(line) * w + k : static_cast<size_t>(k) * w + line];
                grown[at] = hit;
            }
        }
        std::copy(grown.data, grown.data + n, near.data);
    }
    for (size_t i = 0; i < n; i++) {
        if (alive[i] == 2) {
            alpha[i] = 0;
        } else if (alpha[i] < Ceil && !near[i]) {
            if (alpha[i] <= Floor) alpha[i] = 0;
            else {
                const float t = (alpha[i] - Floor) / (Ceil - Floor);
                alpha[i] *= t * t * (3 - 2 * t);
            }
        }
    }
}

int refine(uint8_t *rgba, size_t stride, int width, int height, const float *alpha, int w, int h, const std::atomic<bool> &cancel) {
    const size_t low = static_cast<size_t>(w) * h;
    Buffer<float> fore(low * 3), back(low * 3), al(static_cast<size_t>(Band + 2 * Fine) * width);
    Taps cols(width, w, 0, width);
    if (!fore.data || !back.data || !al.data || !cols.ok()) return -2;
    {
        Buffer<float> image(low * 3);
        if (!image.data || !shrink(rgba, stride, width, height, w, h, image.data) || !lowFuse(image.data, alpha, w, h, fore.data, back.data)) return -2;
    }
    if (cancel) return 1;
    for (int y0 = 0; y0 < height; y0 += Band) {
        if (cancel) return 1;
        int y1 = std::min(height, y0 + Band), ya = std::max(0, y0 - Fine), yb = std::min(height, y1 + Fine), n = yb - ya;
        Taps rows(height, h, ya, yb);
        if (!rows.ok()) return -2;
        for (int r = 0; r < n; r++) {
            const float *a0 = alpha + static_cast<size_t>(rows.i0[r]) * w, *a1 = alpha + static_cast<size_t>(rows.i1[r]) * w;
            float fy = rows.f[r];
            float *out = al.data + static_cast<size_t>(r) * width;
            for (int x = 0; x < width; x++) {
                float v = mix(a0[cols.i0[x]], a0[cols.i1[x]], a1[cols.i0[x]], a1[cols.i1[x]], cols.f[x], fy);
                out[x] = v > .999f ? 1.f : v < .001f ? 0.f : v;
            }
        }
        int ex0 = width, ex1 = -1;
        for (int y = y0; y < y1; y++) {
            const float *row = al.data + static_cast<size_t>(y - ya) * width;
            for (int x = 0; x < width; x++)
                if (edge(row[x])) {
                    ex0 = std::min(ex0, x);
                    ex1 = std::max(ex1, x);
                }
        }
        if (ex1 >= 0) {
            int c0 = std::max(0, ex0 - Fine), c1 = std::min(width - 1, ex1 + Fine), span = ex1 - ex0 + 1, wide = c1 - c0 + 1;
            Buffer<float> sums(static_cast<size_t>(Channels) * n * span), prod(static_cast<size_t>(Channels) * wide);
            if (!sums.data || !prod.data) return -2;
            for (int r = 0; r < n; r++) {
                const float *f0 = fore.data + static_cast<size_t>(rows.i0[r]) * w * 3, *f1 = fore.data + static_cast<size_t>(rows.i1[r]) * w * 3;
                const float *b0 = back.data + static_cast<size_t>(rows.i0[r]) * w * 3, *b1 = back.data + static_cast<size_t>(rows.i1[r]) * w * 3;
                const float *arow = al.data + static_cast<size_t>(r) * width;
                float fy = rows.f[r];
                for (int x = c0; x <= c1; x++) {
                    float a = arow[x];
                    int i0 = cols.i0[x] * 3, i1 = cols.i1[x] * 3;
                    float fx = cols.f[x];
                    prod[x - c0] = a;
                    for (int c = 0; c < 3; c++) {
                        prod[static_cast<size_t>(1 + c) * wide + x - c0] = mix(f0[i0 + c], f0[i1 + c], f1[i0 + c], f1[i1 + c], fx, fy) * a;
                        prod[static_cast<size_t>(4 + c) * wide + x - c0] = mix(b0[i0 + c], b0[i1 + c], b1[i0 + c], b1[i1 + c], fx, fy) * (1 - a);
                    }
                }
                for (int ch = 0; ch < Channels; ch++) {
                    const float *p = prod.data + static_cast<size_t>(ch) * wide;
                    float *out = sums.data + (static_cast<size_t>(ch) * n + r) * span;
                    double s = 0;
                    for (int k = -Fine; k <= Fine; k++) s += p[std::clamp(ex0 + k, 0, width - 1) - c0];
                    for (int x = ex0; x <= ex1; x++) {
                        out[x - ex0] = static_cast<float>(s / (2 * Fine + 1));
                        s += static_cast<double>(p[std::clamp(x + Fine + 1, 0, c1) - c0]) - p[std::clamp(x - Fine, 0, width - 1) - c0];
                    }
                }
            }
            for (int y = y0; y < y1; y++) {
                const float *arow = al.data + static_cast<size_t>(y - ya) * width;
                uint8_t *px = rgba + y * stride;
                for (int x = ex0; x <= ex1; x++) {
                    float a = arow[x];
                    if (!edge(a)) continue;
                    float s[Channels];
                    for (int ch = 0; ch < Channels; ch++) {
                        double t = 0;
                        for (int k = -Fine; k <= Fine; k++) t += sums[(static_cast<size_t>(ch) * n + std::clamp(y + k, 0, height - 1) - ya) * span + x - ex0];
                        s[ch] = static_cast<float>(t / (2 * Fine + 1));
                    }
                    uint8_t *p = px + x * 4;
                    for (int c = 0; c < 3; c++) {
                        float f = s[1 + c] / (s[0] + 1e-5f);
                        float b = s[4 + c] / ((1 - s[0]) + 1e-5f);
                        float e = static_cast<float>(p[c]) / 255.f - a * f - (1 - a) * b;
                        p[c] = byte(std::clamp(f + a * e, 0.f, 1.f));
                    }
                }
            }
        }
        for (int y = y0; y < y1; y++) {
            const float *arow = al.data + static_cast<size_t>(y - ya) * width;
            uint8_t *p = rgba + y * stride + 3;
            for (int x = 0; x < width; x++, p += 4) {
                int a = byte(arow[x]);
                *p = static_cast<uint8_t>(*p == 255 ? a : (a * *p + 127) / 255);
            }
        }
    }
    return 0;
}

}
