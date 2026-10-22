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
    opt.use_vulkan_compute = index >= 0;
    if (index >= 0) {
        const ncnn::GpuInfo &info = ncnn::get_gpu_info(index);
        opt.use_fp16_packed = info.support_fp16_storage();
        opt.use_fp16_storage = info.support_fp16_storage();
        m->net.set_vulkan_device(index);
        m->vulkan = true;
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
                ncnn::Extractor ex = m->net.create_extractor();
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
                                                    jfloat t, jint padding, jintArray output) {
    Model *m = model(handle);
    m->cancel = false;
    if (width <= 0 || height <= 0 || padding <= 0) return -1;
    jsize count = width * height;
    if (env->GetArrayLength(first) < count || env->GetArrayLength(second) < count || env->GetArrayLength(output) < count) return -1;
    int pw = (width + padding - 1) / padding * padding;
    int ph = (height + padding - 1) / padding * padding;
    ncnn::Mat in0(pw, ph, 3), in1(pw, ph, 3), step(pw, ph, 1);
    if (in0.empty() || in1.empty() || step.empty()) return -2;
    in0.fill(0.f);
    in1.fill(0.f);
    step.fill(t);
    for (int k = 0; k < 2; k++) {
        Pixels pixels(env, k == 0 ? first : second);
        if (!pixels.data) return -2;
        ncnn::Mat &mat = k == 0 ? in0 : in1;
        for (int c = 0; c < 3; c++) {
            int shift = 16 - 8 * c;
            ncnn::Mat plane = mat.channel(c);
            for (int y = 0; y < height; y++) {
                float *row = plane.row(y);
                const uint32_t *src = pixels.data + static_cast<size_t>(y) * width;
                for (int x = 0; x < width; x++) row[x] = channel(src[x], shift);
            }
        }
    }
    if (m->cancel) return 1;
    ncnn::Mat result;
    {
        ncnn::Extractor ex = m->net.create_extractor();
        if (ex.input("in0", in0) != 0 || ex.input("in1", in1) != 0 || ex.input("in2", step) != 0 || ex.extract("out0", result) != 0) return -3;
    }
    if (result.w != pw || result.h != ph || result.c < 3) return -3;
    Pixels a(env, first);
    Pixels b(env, second);
    Pixels out(env, output);
    if (!a.data || !b.data || !out.data) return -2;
    const ncnn::Mat r = result.channel(0), g = result.channel(1), bl = result.channel(2);
    for (int y = 0; y < height; y++) {
        const float *rr = r.row(y), *gg = g.row(y), *bb = bl.row(y);
        size_t base = static_cast<size_t>(y) * width;
        for (int x = 0; x < width; x++) {
            float alpha = static_cast<float>(a.data[base + x] >> 24) * (1 - t) + static_cast<float>(b.data[base + x] >> 24) * t;
            uint32_t al = static_cast<uint32_t>(std::clamp(static_cast<int>(std::lround(alpha)), 0, 255));
            out.data[base + x] = (al << 24) | (byte(rr[x]) << 16) | (byte(gg[x]) << 8) | byte(bb[x]);
        }
    }
    return 0;
}
