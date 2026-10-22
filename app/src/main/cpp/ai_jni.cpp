#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "cpu.h"
#include "datareader.h"
#include "gpu.h"
#include "net.h"
#include "rife_ops.h"

DEFINE_LAYER_CREATOR(Warp)

namespace {

constexpr uint32_t Fp16Tag = 0x01306B47;

std::once_flag gpuOnce;
int gpuIndex = -1;

int gpu() {
    std::call_once(gpuOnce, [] {
        if (ncnn::create_gpu_instance() != 0 || ncnn::get_gpu_count() <= 0) return;
        gpuIndex = ncnn::get_default_gpu_index();
    });
    return gpuIndex;
}

struct Model {
    ncnn::Net net;
    std::atomic<bool> cancel{false};
    bool vulkan = false;
    int flow = -1;
    int mask = -1;
    ncnn::VkAllocator *blobVk = nullptr;
    ncnn::VkAllocator *stagingVk = nullptr;

    ~Model() {
        net.clear();
        delete blobVk;
        delete stagingVk;
    }

    ncnn::Extractor extractor() {
        ncnn::Extractor ex = net.create_extractor();
        if (blobVk) {
            ex.set_blob_vkallocator(blobVk);
            ex.set_workspace_vkallocator(blobVk);
            ex.set_staging_vkallocator(stagingVk);
        }
        return ex;
    }
};

Model *model(jlong handle) { return reinterpret_cast<Model *>(handle); }

std::vector<unsigned char> readFile(const char *path) {
    std::vector<unsigned char> data;
    FILE *file = fopen(path, "rb");
    if (!file) return data;
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);
    if (size > 0) {
        data.resize(static_cast<size_t>(size));
        if (fread(data.data(), 1, data.size(), file) != data.size()) data.clear();
    }
    fclose(file);
    return data;
}

class BlendReader : public ncnn::DataReader {
public:
    BlendReader(std::vector<unsigned char> a, std::vector<unsigned char> b, float weight) : a_(std::move(a)), b_(std::move(b)), weight_(weight) {}

    size_t read(void *buf, size_t size) const override {
        if (at_ + size > a_.size() || a_.size() != b_.size()) return 0;
        const unsigned char *x = a_.data() + at_;
        const unsigned char *y = b_.data() + at_;
        at_ += size;
        auto *out = static_cast<unsigned char *>(buf);
        bool half = halfNext_;
        halfNext_ = false;
        if (std::memcmp(x, y, size) == 0) {
            std::memcpy(out, x, size);
            if (size == 4) {
                uint32_t tag;
                std::memcpy(&tag, x, 4);
                halfNext_ = tag == Fp16Tag;
            }
            return size;
        }
        if (half) {
            for (size_t i = 0; i + 1 < size; i += 2) {
                uint16_t p, q;
                std::memcpy(&p, x + i, 2);
                std::memcpy(&q, y + i, 2);
                uint16_t v = ncnn::float32_to_float16(ncnn::float16_to_float32(p) * weight_ + ncnn::float16_to_float32(q) * (1 - weight_));
                std::memcpy(out + i, &v, 2);
            }
            if (size % 2) out[size - 1] = x[size - 1];
            return size;
        }
        for (size_t i = 0; i + 3 < size; i += 4) {
            float p, q;
            std::memcpy(&p, x + i, 4);
            std::memcpy(&q, y + i, 4);
            float v = p * weight_ + q * (1 - weight_);
            std::memcpy(out + i, &v, 4);
        }
        for (size_t i = size / 4 * 4; i < size; i++) out[i] = x[i];
        return size;
    }

private:
    std::vector<unsigned char> a_;
    std::vector<unsigned char> b_;
    float weight_;
    mutable size_t at_ = 0;
    mutable bool halfNext_ = false;
};

std::string text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

inline float channel(uint32_t argb, int shift) { return static_cast<float>((argb >> shift) & 0xFF) * (1 / 255.f); }

inline uint32_t byte(float v) {
    int i = static_cast<int>(std::lround(v * 255.f));
    return static_cast<uint32_t>(std::clamp(i, 0, 255));
}

struct Pixels {
    JNIEnv *env;
    jintArray array;
    uint32_t *data;
    Pixels(JNIEnv *e, jintArray a) : env(e), array(a), data(a ? static_cast<uint32_t *>(e->GetPrimitiveArrayCritical(a, nullptr)) : nullptr) {}
    ~Pixels() { if (data) env->ReleasePrimitiveArrayCritical(array, data, 0); }
};

void fillRgb(ncnn::Mat &mat, const uint32_t *argb, int stride, int x0, int y0, int w, int h, int width, int height) {
    for (int c = 0; c < 3; c++) {
        float *out = mat.channel(c);
        int shift = 16 - 8 * c;
        for (int y = 0; y < h; y++) {
            int sy = std::clamp(y0 + y, 0, height - 1);
            const uint32_t *row = argb + static_cast<size_t>(sy) * stride;
            for (int x = 0; x < w; x++) *out++ = channel(row[std::clamp(x0 + x, 0, width - 1)], shift);
        }
    }
}


float sampleBilinear(const ncnn::Mat &plane, float x, float y, int w, int h) {
    x = std::clamp(x, 0.f, static_cast<float>(w - 1));
    y = std::clamp(y, 0.f, static_cast<float>(h - 1));
    int x0 = static_cast<int>(x), y0 = static_cast<int>(y);
    int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    float a = x - x0, b = y - y0;
    const float *r0 = plane.row(y0), *r1 = plane.row(y1);
    return (r0[x0] * (1 - a) + r0[x1] * a) * (1 - b) + (r1[x0] * (1 - a) + r1[x1] * a) * b;
}

void warpPixel(const uint32_t *argb, int w, int h, float sx, float sy, float rgb[3]) {
    int x0 = static_cast<int>(std::floor(sx)), y0 = static_cast<int>(std::floor(sy));
    float a = sx - x0, b = sy - y0;
    int xa = std::clamp(x0, 0, w - 1), xb = std::clamp(x0 + 1, 0, w - 1);
    int ya = std::clamp(y0, 0, h - 1), yb = std::clamp(y0 + 1, 0, h - 1);
    uint32_t p00 = argb[static_cast<size_t>(ya) * w + xa], p01 = argb[static_cast<size_t>(ya) * w + xb];
    uint32_t p10 = argb[static_cast<size_t>(yb) * w + xa], p11 = argb[static_cast<size_t>(yb) * w + xb];
    for (int c = 0; c < 3; c++) {
        int shift = 16 - 8 * c;
        float top = ((p00 >> shift) & 0xFF) * (1 - a) + ((p01 >> shift) & 0xFF) * a;
        float bottom = ((p10 >> shift) & 0xFF) * (1 - a) + ((p11 >> shift) & 0xFF) * a;
        rgb[c] = (top * (1 - b) + bottom * b) * (1 / 255.f);
    }
}

void boxDown(ncnn::Mat &mat, const uint32_t *argb, int w, int h, int nw, int nh) {
    std::vector<int> xs(nw + 1), ys(nh + 1);
    for (int i = 0; i <= nw; i++) xs[i] = static_cast<int>(static_cast<int64_t>(i) * w / nw);
    for (int i = 0; i <= nh; i++) ys[i] = static_cast<int>(static_cast<int64_t>(i) * h / nh);
    for (int y = 0; y < nh; y++) {
        int y0 = ys[y], y1 = std::max(ys[y + 1], y0 + 1);
        float *r = mat.channel(0).row(y), *g = mat.channel(1).row(y), *bl = mat.channel(2).row(y);
        for (int x = 0; x < nw; x++) {
            int x0 = xs[x], x1 = std::max(xs[x + 1], x0 + 1);
            uint32_t sr = 0, sg = 0, sb = 0;
            for (int sy = y0; sy < y1; sy++) {
                const uint32_t *row = argb + static_cast<size_t>(sy) * w;
                for (int sx = x0; sx < x1; sx++) {
                    uint32_t p = row[sx];
                    sr += (p >> 16) & 0xFF;
                    sg += (p >> 8) & 0xFF;
                    sb += p & 0xFF;
                }
            }
            float k = 1.f / (255.f * static_cast<float>((x1 - x0) * (y1 - y0)));
            r[x] = sr * k;
            g[x] = sg * k;
            bl[x] = sb * k;
        }
    }
}

bool findFlowMask(const ncnn::Net &net, int &flow, int &mask) {
    const auto &layers = net.layers();
    const auto &blobs = net.blobs();
    int warp = -1, sigmoid = -1;
    for (size_t i = 0; i < layers.size(); i++) {
        if (layers[i]->type == "rife.Warp") warp = static_cast<int>(i);
        if (layers[i]->type == "Sigmoid") sigmoid = static_cast<int>(i);
    }
    if (warp < 0 || sigmoid < 0 || layers[warp]->bottoms.size() < 2) return false;
    int crop = blobs[layers[warp]->bottoms[1]].producer;
    if (crop < 0 || layers[crop]->type != "Crop") return false;
    int blob = layers[crop]->bottoms[0];
    int split = blobs[blob].producer;
    if (split >= 0 && layers[split]->type == "Split") blob = layers[split]->bottoms[0];
    flow = blob;
    mask = layers[sigmoid]->tops[0];
    return true;
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_app_absentmotion_ai_AiNative_nativeGpu(JNIEnv *env, jobject) {
    int index = gpu();
    if (index < 0) return nullptr;
    const ncnn::GpuInfo &info = ncnn::get_gpu_info(index);
    char line[512];
    snprintf(line, sizeof line, "%u\t%d\t%d\t%d\t%d\t%s", info.api_version(), info.type(), info.support_fp16_storage() ? 1 : 0,
             info.support_fp16_arithmetic() ? 1 : 0, info.support_VK_KHR_push_descriptor() ? 1 : 0, info.device_name());
    return env->NewStringUTF(line);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_absentmotion_ai_AiNative_nativeCpuCores(JNIEnv *, jobject) {
    return std::max(1, ncnn::get_big_cpu_count());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_ai_AiNative_nativeOpen(JNIEnv *env, jobject, jstring param, jstring bin, jstring blendBin, jfloat weight, jboolean useGpu) {
    auto *m = new (std::nothrow) Model;
    if (!m) return 0;
    int index = useGpu ? gpu() : -1;
    ncnn::Option &opt = m->net.opt;
    opt.num_threads = std::max(1, ncnn::get_big_cpu_count());
    opt.use_fp16_arithmetic = false;
    opt.use_local_pool_allocator = false;
    opt.use_vulkan_compute = index >= 0;
    if (index >= 0) {
        const ncnn::GpuInfo &info = ncnn::get_gpu_info(index);
        opt.use_fp16_packed = info.support_fp16_storage();
        opt.use_fp16_storage = info.support_fp16_storage();
        m->net.set_vulkan_device(index);
        m->blobVk = new (std::nothrow) ncnn::VkBlobAllocator(m->net.vulkan_device());
        m->stagingVk = new (std::nothrow) ncnn::VkStagingAllocator(m->net.vulkan_device());
        m->vulkan = true;
        if (!m->blobVk || !m->stagingVk) {
            delete m;
            return 0;
        }
    }
    m->net.register_custom_layer("rife.Warp", Warp_layer_creator);
    std::string paramPath = text(env, param);
    std::string binPath = text(env, bin);
    std::string otherPath = text(env, blendBin);
    bool loaded = m->net.load_param(paramPath.c_str()) == 0;
    if (loaded && !otherPath.empty()) {
        BlendReader reader(readFile(binPath.c_str()), readFile(otherPath.c_str()), weight);
        loaded = m->net.load_model(reader) == 0;
    } else if (loaded) {
        loaded = m->net.load_model(binPath.c_str()) == 0;
    }
    if (!loaded || m->net.input_names().empty() || m->net.output_names().empty()) {
        delete m;
        return 0;
    }
    findFlowMask(m->net, m->flow, m->mask);
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_absentmotion_ai_AiNative_nativeOnGpu(JNIEnv *, jobject, jlong handle) {
    return model(handle)->vulkan;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_ai_AiNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    model(handle)->cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_ai_AiNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete model(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_absentmotion_ai_AiNative_nativeUpscale(JNIEnv *env, jobject, jlong handle, jintArray input, jint width, jint height, jint netScale,
                                                jint outScale, jint tile, jint overlap, jintArray output) {
    Model *m = model(handle);
    m->cancel = false;
    if (width <= 0 || height <= 0 || netScale <= 0 || outScale <= 0 || netScale % outScale || tile <= 0 || overlap < 0) return -1;
    if (env->GetArrayLength(input) < width * height || env->GetArrayLength(output) < width * outScale * height * outScale) return -1;
    const int factor = netScale / outScale;
    const int outWidth = width * outScale;
    const char *in = m->net.input_names()[0];
    const char *out = m->net.output_names()[0];
    for (int ty = 0; ty < height; ty += tile) {
        for (int tx = 0; tx < width; tx += tile) {
            if (m->cancel) return 1;
            int tw = std::min(tile, width - tx);
            int th = std::min(tile, height - ty);
            int pw = tw + 2 * overlap;
            int ph = th + 2 * overlap;
            ncnn::Mat src(pw, ph, 3);
            if (src.empty()) return -2;
            {
                Pixels pixels(env, input);
                if (!pixels.data) return -2;
                fillRgb(src, pixels.data, width, tx - overlap, ty - overlap, pw, ph, width, height);
            }
            ncnn::Mat result;
            {
                ncnn::Extractor ex = m->extractor();
                if (ex.input(in, src) != 0 || ex.extract(out, result) != 0) return -3;
            }
            if (result.w != pw * netScale || result.h != ph * netScale || result.c < 3) return -3;
            Pixels pixels(env, output);
            if (!pixels.data) return -2;
            const float area = 1.f / static_cast<float>(factor * factor);
            for (int y = 0; y < th * outScale; y++) {
                uint32_t *row = pixels.data + static_cast<size_t>(ty * outScale + y) * outWidth + tx * outScale;
                int sy = (overlap + 0) * netScale + y * factor;
                for (int x = 0; x < tw * outScale; x++) {
                    int sx = overlap * netScale + x * factor;
                    float rgb[3];
                    for (int c = 0; c < 3; c++) {
                        const ncnn::Mat plane = result.channel(c);
                        float sum = 0;
                        for (int dy = 0; dy < factor; dy++) {
                            const float *line = plane.row(sy + dy) + sx;
                            for (int dx = 0; dx < factor; dx++) sum += line[dx];
                        }
                        rgb[c] = sum * area;
                    }
                    row[x] = 0xFF000000u | (byte(rgb[0]) << 16) | (byte(rgb[1]) << 8) | byte(rgb[2]);
                }
            }
        }
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_absentmotion_ai_AiNative_nativeInterpolate(JNIEnv *env, jobject, jlong handle, jintArray first, jintArray second, jint width, jint height,
                                                    jfloat t, jint padding, jint maxPixels, jintArray output) {
    Model *m = model(handle);
    m->cancel = false;
    if (width <= 0 || height <= 0 || padding <= 0 || maxPixels <= 0 || m->flow < 0 || m->mask < 0) return -1;
    jsize count = width * height;
    if (env->GetArrayLength(first) < count || env->GetArrayLength(second) < count || env->GetArrayLength(output) < count) return -1;
    double k = std::min(1.0, std::sqrt(static_cast<double>(maxPixels) / (static_cast<double>(width) * height)));
    int nw = std::max(1, static_cast<int>(std::lround(width * k)));
    int nh = std::max(1, static_cast<int>(std::lround(height * k)));
    int pw = (nw + padding - 1) / padding * padding;
    int ph = (nh + padding - 1) / padding * padding;
    ncnn::Mat flow, mask;
    {
        ncnn::Mat in0(pw, ph, 3), in1(pw, ph, 3), step(pw, ph, 1);
        if (in0.empty() || in1.empty() || step.empty()) return -2;
        in0.fill(0.f);
        in1.fill(0.f);
        step.fill(t);
        for (int i = 0; i < 2; i++) {
            Pixels pixels(env, i == 0 ? first : second);
            if (!pixels.data) return -2;
            boxDown(i == 0 ? in0 : in1, pixels.data, width, height, nw, nh);
        }
        if (m->cancel) return 1;
        ncnn::Extractor ex = m->extractor();
        if (ex.input("in0", in0) != 0 || ex.input("in1", in1) != 0 || ex.input("in2", step) != 0) return -3;
        if (ex.extract(m->mask, mask) != 0 || ex.extract(m->flow, flow) != 0) return -3;
    }
    if (flow.w != pw || flow.h != ph || flow.c < 4 || mask.w != pw || mask.h != ph || mask.c < 1) return -3;
    if (m->cancel) return 1;
    Pixels a(env, first);
    Pixels b(env, second);
    Pixels out(env, output);
    if (!a.data || !b.data || !out.data) return -2;
    const float fx = static_cast<float>(nw) / width, fy = static_cast<float>(nh) / height;
    const float sx = static_cast<float>(width) / nw, sy = static_cast<float>(height) / nh;
    const ncnn::Mat f0 = flow.channel(0), f1 = flow.channel(1), f2 = flow.channel(2), f3 = flow.channel(3), weight = mask.channel(0);
    for (int y = 0; y < height; y++) {
        float ny = (y + 0.5f) * fy - 0.5f;
        for (int x = 0; x < width; x++) {
            float nx = (x + 0.5f) * fx - 0.5f;
            float w0 = sampleBilinear(weight, nx, ny, nw, nh);
            float ca[3], cb[3];
            warpPixel(a.data, width, height, x + sampleBilinear(f0, nx, ny, nw, nh) * sx, y + sampleBilinear(f1, nx, ny, nw, nh) * sy, ca);
            warpPixel(b.data, width, height, x + sampleBilinear(f2, nx, ny, nw, nh) * sx, y + sampleBilinear(f3, nx, ny, nw, nh) * sy, cb);
            size_t at = static_cast<size_t>(y) * width + x;
            float alpha = static_cast<float>(a.data[at] >> 24) * (1 - t) + static_cast<float>(b.data[at] >> 24) * t;
            uint32_t value = static_cast<uint32_t>(std::clamp(static_cast<int>(std::lround(alpha)), 0, 255)) << 24;
            for (int c = 0; c < 3; c++) value |= byte(ca[c] * w0 + cb[c] * (1 - w0)) << (16 - 8 * c);
            out.data[at] = value;
        }
    }
    return 0;
}
