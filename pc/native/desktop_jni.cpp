#include <jni.h>
#include <algorithm>
#include <atomic>
#include <clocale>
#include <cmath>
#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <vector>
#include "effects.h"
#include "interpolation.h"
#include "scene.h"
#include "easing.h"
#include "textanim.h"
#include "gpu.h"
#include "trails.h"
#include "drawing.h"
#include "text.h"
#include "preview.h"
#include "video.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkImage.h"
#include "include/core/SkImageGenerator.h"
#include "include/core/SkSurface.h"
#include "include/codec/SkCodec.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkStream.h"
#include "include/encode/SkWebpEncoder.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/GrDirectContext.h"

struct DesktopMosh {
    am::MoshParams params;
    std::vector<int64_t> done;
    std::vector<int64_t> pts;
    sk_sp<SkImage> state, previous, motion;
};

static std::atomic<int> DesktopGpuState{-1};

struct ExportRead {
    int width = 0, height = 0;
    std::unique_ptr<const SkImage::AsyncReadResult> pixels;
    bool done = false;
};

struct Desktop {
    am::Scene scene;
    am::EffectRuntime runtime;
    std::map<std::string, sk_sp<SkImage>> pictures;
    std::map<std::string, sk_sp<SkImage>> masks;
    std::map<std::pair<std::string, int64_t>, sk_sp<SkImage>> layerPictures;
    std::map<std::pair<std::string, int64_t>, DesktopMosh> mosh;
    am::Trails trails;
    std::unique_ptr<DesktopGpu> gpu;
    bool cameraView = true;
    bool accurateBlur = true;
    std::vector<uint8_t> frameBytes;
    std::shared_ptr<ExportRead> exportPending;
    sk_sp<SkSurface> exportSurface;
    Desktop() : gpu(std::getenv("AM_SOFTWARE_RENDER") ? nullptr : CreateDesktopGpu()) {
        DesktopGpuState = gpu && gpu->context() ? 1 : 0;
        if (gpu && gpu->context()) gpu->context()->setResourceCacheLimit(size_t(512) << 20);
        trails.layerBudget = size_t(128) << 20;
        trails.budget = size_t(384) << 20;
    }
    ~Desktop() { exportPending.reset(); exportSurface.reset(); trails.clear(); mosh.clear(); layerPictures.clear(); masks.clear(); pictures.clear(); }
    am::MediaPictures maskLookup() {
        return [this](const std::string& uri, int64_t) -> sk_sp<SkImage> {
            auto it = masks.find(uri);
            return it == masks.end() ? nullptr : it->second;
        };
    }
    am::MediaPictures media() {
        return [this](const std::string& uri, int64_t layer) -> sk_sp<SkImage> {
            auto found = layerPictures.find({uri, layer});
            if (found != layerPictures.end()) return found->second;
            auto it = pictures.find(uri);
            return it == pictures.end() ? nullptr : it->second;
        };
    }
    am::LayerEffects effects() {
        am::LayerEffects fx;
        fx.active = [this](const am::Layer& l) { return runtime.active(l); };
        fx.lifted = [this](const am::Layer& l) { return runtime.lifted(l); };
        fx.spills = [this](const am::Layer& l) { return runtime.spills(l); };
        fx.behind = [this](const am::Layer& l) { return runtime.behind(l); };
        fx.wraps = [this](const am::Layer& l) { return runtime.wraps(l); };
        fx.steady = [this](const am::Layer& l, float a, float b) { return runtime.steady(l, a, b); };
        fx.past = [this](const std::string& uri, int64_t layer, int copy, float gain) { return trails.at(uri, layer, copy, gain); };
        fx.draw = [this](SkCanvas* c, const am::Scene& s, const am::Layer& l, double t, const SkRect& r, const SkMatrix& m, const std::function<void(SkCanvas*)>& src, const std::function<void(SkCanvas*)>& below) {
            return runtime.draw(c, s, l, t, r, m, src, below, [this](int w, int h, const std::function<void(SkCanvas*)>& paint, std::vector<uint8_t>* rgba) {
                return offscreen(w, h, false, paint, rgba);
            });
        };
        return fx;
    }
    sk_sp<SkSurface> surface(const SkImageInfo& info) {
        auto made = gpu && gpu->context() ? SkSurfaces::RenderTarget(gpu->context(), skgpu::Budgeted::kNo, info) : nullptr;
        return made ? made : SkSurfaces::Raster(info);
    }
    am::Trails::Shrink shrink() {
        return [this](const sk_sp<SkImage>& image, int width, int height) -> sk_sp<SkImage> {
            auto s = surface(SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB()));
            if (!s) return nullptr;
            s->getCanvas()->clear(SK_ColorTRANSPARENT);
            s->getCanvas()->drawImageRect(image, SkRect::MakeIWH(width, height), SkSamplingOptions(SkCubicResampler::CatmullRom()));
            return s->makeImageSnapshot();
        };
    }
    bool take(JNIEnv* e, jbyteArray out) {
        const std::shared_ptr<ExportRead> read = std::move(exportPending);
        if (!read) return false;
        GrDirectContext* context = gpu ? gpu->context() : nullptr;
        if (!read->done && context) {
            context->submit(GrSyncCpu::kYes);
            context->checkAsyncWorkCompletion();
        }
        const size_t row = (size_t)read->width * 4;
        if (!out || !read->pixels || read->pixels->count() != 1 || read->pixels->rowBytes(0) < row ||
            (int64_t)e->GetArrayLength(out) < (int64_t)row * read->height) return false;
        const auto* from = (const jbyte*)read->pixels->data(0);
        const size_t stride = read->pixels->rowBytes(0);
        if (stride == row) e->SetByteArrayRegion(out, 0, (jsize)(row * read->height), from);
        else for (int y = 0; y < read->height; ++y) e->SetByteArrayRegion(out, (jsize)(y * row), (jsize)row, from + y * stride);
        return true;
    }
    bool offscreen(int width, int height, bool premultiplied, const std::function<void(SkCanvas*)>& paint, std::vector<uint8_t>* rgba) {
        if (width <= 0 || height <= 0) return false;
        auto info = SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
        auto s = surface(info);
        if (!s) return false;
        s->getCanvas()->clear(SK_ColorTRANSPARENT);
        paint(s->getCanvas());
        void* probe = std::malloc((size_t)width * height * 4);
        if (!probe) return false;
        std::free(probe);
        rgba->resize((size_t)width * height * 4);
        return s->readPixels(premultiplied ? info : info.makeAlphaType(kUnpremul_SkAlphaType), rgba->data(), (size_t)width * 4, 0, 0);
    }
};

static jbyteArray bytes(JNIEnv* e, const std::vector<uint8_t>& v) {
    auto r = e->NewByteArray((jsize)v.size());
    if (!r) return nullptr;
    e->SetByteArrayRegion(r, 0, (jsize)v.size(), (const jbyte*)v.data());
    return r;
}

static jfloatArray floats(JNIEnv* e, const float* v, int n) {
    auto r = e->NewFloatArray(n);
    if (!r) return nullptr;
    e->SetFloatArrayRegion(r, 0, n, v);
    return r;
}

static std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize length = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    std::string result;
    result.reserve((size_t)length);
    for (jsize i = 0; i < length; i++) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) result.push_back((char)c);
        else if (c < 0x800) {
            result.push_back((char)(0xc0 | (c >> 6)));
            result.push_back((char)(0x80 | (c & 0x3f)));
        } else if (c < 0x10000) {
            result.push_back((char)(0xe0 | (c >> 12)));
            result.push_back((char)(0x80 | ((c >> 6) & 0x3f)));
            result.push_back((char)(0x80 | (c & 0x3f)));
        } else {
            result.push_back((char)(0xf0 | (c >> 18)));
            result.push_back((char)(0x80 | ((c >> 12) & 0x3f)));
            result.push_back((char)(0x80 | ((c >> 6) & 0x3f)));
            result.push_back((char)(0x80 | (c & 0x3f)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return result;
}
static Desktop* ptr(jlong h) { return reinterpret_cast<Desktop*>(h); }
GrDirectContext* DesktopContext(jlong h) { return ptr(h)->gpu ? ptr(h)->gpu->context() : nullptr; }
std::string DesktopString(JNIEnv* env, jstring s) { return str(env, s); }
void DesktopInstall(jlong h, const std::string& uri, sk_sp<SkImage> image) { ptr(h)->pictures[uri] = std::move(image); }
void DesktopInstallLayer(jlong h, const std::string& uri, int64_t layer, sk_sp<SkImage> image) { ptr(h)->layerPictures[{uri, layer}] = std::move(image); }
namespace {
class RawPicture : public SkImageGenerator {
public:
    RawPicture(std::unique_ptr<SkCodec> codec, bool mirror, bool raw) : SkImageGenerator(Info(*codec, raw)), fCodec(std::move(codec)), fMirror(mirror) {}

protected:
    bool onGetPixels(const SkImageInfo& info, void* pixels, size_t rowBytes, const Options&) override {
        const SkCodec::Result result = fCodec->getPixels(info, pixels, rowBytes);
        if (result != SkCodec::kSuccess && result != SkCodec::kIncompleteInput && result != SkCodec::kErrorInInput) return false;
        const size_t pixel = info.bytesPerPixel();
        if (!fMirror) return true;
        for (int y = 0; y < info.height(); ++y) {
            auto* row = (uint8_t*)pixels + (size_t)y * rowBytes;
            for (int a = 0, b = info.width() - 1; a < b; ++a, --b) std::swap_ranges(row + a * pixel, row + (a + 1) * pixel, row + b * pixel);
        }
        return true;
    }

private:
    static SkImageInfo Info(const SkCodec& codec, bool raw) {
        const SkImageInfo info = raw ? codec.getInfo().makeColorSpace(nullptr) : codec.getInfo();
        return info.alphaType() == kUnpremul_SkAlphaType ? info.makeAlphaType(kPremul_SkAlphaType) : info;
    }
    std::unique_ptr<SkCodec> fCodec;
    bool fMirror;
};

sk_sp<SkImage> PictureAsStored(sk_sp<SkData> data, bool raw) {
    auto codec = SkCodec::MakeFromData(data);
    const SkEncodedOrigin origin = codec ? codec->getOrigin() : kTopLeft_SkEncodedOrigin;
    if (origin == kTopLeft_SkEncodedOrigin && !(raw && codec)) return SkImages::DeferredFromEncodedData(data);
    const bool mirror = origin == kTopRight_SkEncodedOrigin || origin == kBottomLeft_SkEncodedOrigin ||
                        origin == kLeftTop_SkEncodedOrigin || origin == kRightBottom_SkEncodedOrigin;
    return SkImages::DeferredFromGenerator(std::make_unique<RawPicture>(std::move(codec), mirror, raw));
}
}

#define JNI(name) Java_app_absentmotion_pc_engine_DesktopNative_##name
extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    setlocale(LC_NUMERIC, "C");
    return JNI_VERSION_1_8;
}
JNIEXPORT jlong JNICALL JNI(create)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Desktop()); }
JNIEXPORT jint JNICALL JNI(gpuState)(JNIEnv*, jobject) { return DesktopGpuState.load(); }
JNIEXPORT void JNICALL JNI(destroy)(JNIEnv*, jobject, jlong h) { delete ptr(h); }
JNIEXPORT jstring JNICALL JNI(loadEffect)(JNIEnv* e, jobject, jlong h, jstring xml) {
    return e->NewStringUTF(ptr(h)->runtime.load(str(e, xml)).c_str());
}
JNIEXPORT jstring JNICALL JNI(setScene)(JNIEnv* e, jobject, jlong h, jstring xml) {
    am::Scene next;
    std::string error;
    if (!am::ParseScene(str(e, xml), &next, &error)) return e->NewStringUTF(error.c_str());
    ptr(h)->scene = std::move(next);
    return nullptr;
}
JNIEXPORT jboolean JNICALL JNI(setPicture)(JNIEnv* e, jobject, jlong h, jstring uri, jstring path, jboolean raw) {
    SkFILEStream stream(str(e, path).c_str());
    auto data = stream.isValid() ? SkData::MakeFromStream(&stream, stream.getLength()) : nullptr;
    auto image = data ? PictureAsStored(data, raw) : nullptr;
    if (!image) return false;
    auto* d = ptr(h);
    const auto key = str(e, uri);
    d->masks.erase(key);
    if (!image->isOpaque() && image->width() > 0 && image->height() > 0) {
        const float scale = std::min(1.0f, 256.0f / std::max(image->width(), image->height()));
        auto surface = SkSurfaces::Raster(SkImageInfo::MakeA8(std::max(1, (int)std::lround(image->width() * scale)), std::max(1, (int)std::lround(image->height() * scale))));
        if (surface) {
            surface->getCanvas()->clear(SK_ColorTRANSPARENT);
            surface->getCanvas()->drawImageRect(image, SkRect::MakeWH((float)surface->width(), (float)surface->height()), SkSamplingOptions(SkFilterMode::kLinear));
            if (auto mask = surface->makeImageSnapshot()) d->masks[key] = std::move(mask);
        }
    }
    d->pictures[key] = image;
    return true;
}
JNIEXPORT jboolean JNICALL JNI(setFrame)(JNIEnv* e, jobject, jlong h, jstring uri, jint w, jint height, jbyteArray bytes) {
    if (w <= 0 || height <= 0 || (int64_t)w * height * 4 != e->GetArrayLength(bytes)) return false;
    auto data = SkData::MakeUninitialized((size_t)w * height * 4);
    e->GetByteArrayRegion(bytes, 0, e->GetArrayLength(bytes), (jbyte*)data->writable_data());
    auto info = SkImageInfo::Make(w, height, kRGBA_8888_SkColorType, kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB());
    ptr(h)->masks.erase(str(e, uri));
    ptr(h)->pictures[str(e, uri)] = SkImages::RasterFromData(info, data, (size_t)w * 4);
    return true;
}
static sk_sp<SkImage> frameImage(JNIEnv* e, jint w, jint height, jbyteArray bytes) {
    if (!bytes || w <= 0 || height <= 0 || (int64_t)w * height * 4 != e->GetArrayLength(bytes)) return nullptr;
    auto data = SkData::MakeUninitialized((size_t)w * height * 4);
    e->GetByteArrayRegion(bytes, 0, e->GetArrayLength(bytes), (jbyte*)data->writable_data());
    auto info = SkImageInfo::Make(w, height, kRGBA_8888_SkColorType, kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB());
    return SkImages::RasterFromData(info, data, (size_t)w * 4);
}
JNIEXPORT void JNICALL JNI(clearLayerFrames)(JNIEnv*, jobject, jlong h) { ptr(h)->layerPictures.clear(); }
JNIEXPORT void JNICALL JNI(dropVideo)(JNIEnv* e, jobject, jlong h, jstring uri) {
    auto* d = ptr(h);
    const auto key = str(e, uri);
    std::erase_if(d->mosh, [&](const auto& item) { return item.first.first == key; });
    std::erase_if(d->layerPictures, [&](const auto& item) { return item.first.first == key; });
    d->trails.dropUri(key);
    d->masks.erase(key);
    d->pictures.erase(key);
}
JNIEXPORT jboolean JNICALL JNI(setLayerFrame)(JNIEnv* e, jobject, jlong h, jstring uri, jlong layer, jint w, jint height, jbyteArray data) {
    auto image = frameImage(e, w, height, data);
    if (!image) return false;
    ptr(h)->layerPictures[{str(e, uri), layer}] = std::move(image);
    return true;
}
JNIEXPORT jboolean JNICALL JNI(interpolate)(JNIEnv* e, jobject, jlong h, jstring uri, jlong layer, jint w, jint height,
                                           jbyteArray first, jbyteArray second, jbyteArray before, jbyteArray after, jbyteArray made,
                                           jfloat t, jint mode, jfloat blur, jfloatArray parameters) {
    auto* d = ptr(h);
    auto a = frameImage(e, w, height, first), b = frameImage(e, w, height, second);
    if (!a || !b) return false;
    am::InterpolationParams params;
    if (parameters && e->GetArrayLength(parameters) >= 6) {
        float p[6];
        e->GetFloatArrayRegion(parameters, 0, 6, p);
        params = {(int)p[0], (int)p[1], (int)p[2], (int)p[3], p[4], (int)p[5]};
    }
    am::GpuContext context{nullptr, nullptr, DesktopContext(h)};
    sk_sp<SkImage> field;
    if (mode == 2 || blur > 0) {
        am::FlowStats stats;
        field = am::EstimateFlow(context, a, b, params, &stats);
        if (!field) return false;
    }
    double ms = 0;
    auto prior = before ? frameImage(e, w, height, before) : nullptr;
    auto next = after ? frameImage(e, w, height, after) : nullptr;
    auto image = mode == 0 ? a : mode == 3 ? frameImage(e, w, height, made) : am::SynthesizeFrame(context, a, b, mode == 2 ? field : nullptr, t, params.divisor, &ms, prior, next);
    if (image && blur > 0) image = am::MotionBlur(context, image, field, t, blur, params.divisor);
    if (!image) return false;
    d->layerPictures[{str(e, uri), layer}] = std::move(image);
    return true;
}
JNIEXPORT jlongArray JNICALL JNI(videoTrail)(JNIEnv* e, jobject, jlong h, jstring uri, jlong layer, jlong shown, jlongArray tags, jfloat step) {
    auto* d = ptr(h);
    const auto key = std::make_pair(str(e, uri), (int64_t)layer);
    std::vector<int64_t> wanted((size_t)e->GetArrayLength(tags));
    e->GetLongArrayRegion(tags, 0, (jsize)wanted.size(), reinterpret_cast<jlong*>(wanted.data()));
    const auto found = d->layerPictures.find(key);
    const std::vector<int64_t> missing = d->trails.plan(key, shown, wanted, step, found == d->layerPictures.end() ? nullptr : found->second, d->shrink());
    jlongArray out = e->NewLongArray((jsize)missing.size());
    if (out) e->SetLongArrayRegion(out, 0, (jsize)missing.size(), reinterpret_cast<const jlong*>(missing.data()));
    return out;
}
JNIEXPORT jboolean JNICALL JNI(videoTrailKeep)(JNIEnv* e, jobject, jlong h, jstring uri, jlong layer, jlong tag) {
    auto* d = ptr(h);
    const auto key = std::make_pair(str(e, uri), (int64_t)layer);
    const auto found = d->layerPictures.find(key);
    return found != d->layerPictures.end() && d->trails.keep(key, tag, found->second, d->shrink());
}
JNIEXPORT void JNICALL JNI(trailBudget)(JNIEnv*, jobject, jlong h, jint layerMb, jint totalMb) {
    ptr(h)->trails.layerBudget = size_t(layerMb) << 20;
    ptr(h)->trails.budget = size_t(totalMb) << 20;
}
JNIEXPORT jint JNICALL JNI(moshStart)(JNIEnv* e, jobject, jlong h, jstring uri, jlong layer, jlongArray stamps, jfloatArray values) {
    auto* d = ptr(h);
    if (!stamps || !values || e->GetArrayLength(values) < 5) return -1;
    const int count = e->GetArrayLength(stamps);
    if (count <= 0 || count > 61) return -1;
    std::vector<jlong> input((size_t)count);
    e->GetLongArrayRegion(stamps, 0, count, input.data());
    float p[5];
    e->GetFloatArrayRegion(values, 0, 5, p);
    am::MoshParams params{p[0], p[1], p[2], p[3], p[4]};
    const auto key = std::make_pair(str(e, uri), (int64_t)layer);
    auto& m = d->mosh[key];
    if (!(m.params == params) || m.done.size() > input.size() || !std::equal(m.done.begin(), m.done.end(), input.begin())) m = DesktopMosh{};
    m.params = params;
    m.pts.assign(input.begin(), input.end());
    if (m.done.size() == m.pts.size() && m.state) d->layerPictures[key] = m.state;
    return (int)m.done.size();
}
JNIEXPORT jboolean JNICALL JNI(moshFrame)(JNIEnv* e, jobject, jlong h, jstring uri, jlong layer, jint step, jint w, jint height, jbyteArray data) {
    auto* d = ptr(h);
    const auto key = std::make_pair(str(e, uri), (int64_t)layer);
    auto found = d->mosh.find(key);
    if (found == d->mosh.end()) return false;
    auto& m = found->second;
    if (step < 0 || (size_t)step != m.done.size() || (size_t)step >= m.pts.size()) return false;
    auto frame = frameImage(e, w, height, data);
    if (!frame) return false;
    if (step == 0) {
        m.state = m.previous = frame;
        m.motion = nullptr;
    } else {
        if (!m.previous || !m.state || frame->dimensions() != m.previous->dimensions()) return false;
        am::GpuContext context{nullptr, nullptr, DesktopContext(h)};
        sk_sp<SkImage> flow;
        if (m.pts[step] != m.pts[step - 1]) {
            am::FlowStats stats;
            flow = am::EstimateFlow(context, m.previous, frame, m.params.flow, &stats);
            if (!flow) return false;
        }
        sk_sp<SkImage> motion;
        const float salt = (float)(((uint64_t)layer * 131 + (uint64_t)step * 7919) % 997) + 0.5f;
        auto state = am::MoshStep(context, m.state, m.motion, frame, flow, m.params, salt, &motion);
        if (!state) return false;
        m.state = std::move(state);
        m.motion = std::move(motion);
        m.previous = std::move(frame);
    }
    m.done.push_back(m.pts[step]);
    if (m.done.size() == m.pts.size()) d->layerPictures[key] = m.state;
    return true;
}
JNIEXPORT jboolean JNICALL JNI(render)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jobject buffer) {
    Desktop* d = ptr(h);
    if (!d || w <= 0 || height <= 0 || d->scene.width <= 0 || d->scene.height <= 0 || e->GetDirectBufferCapacity(buffer) < (jlong)w * height * 4) return false;
    auto info = SkImageInfo::Make(w, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
    auto surface = d->surface(info);
    if (!surface) return false;
    const am::LayerEffects fx = d->effects();
    auto canvas = surface->getCanvas();
    canvas->clear(SK_ColorTRANSPARENT);
    canvas->scale((float)w / d->scene.width, (float)height / d->scene.height);
    am::SetCameraView(d->cameraView);
    am::SetFastShutterBlur(!d->accurateBlur);
    am::DrawScene(canvas, d->scene, time, d->media(), &fx);
    am::SetFastShutterBlur(false);
    return surface->readPixels(info, e->GetDirectBufferAddress(buffer), (size_t)w * 4, 0, 0);
}
JNIEXPORT jboolean JNICALL JNI(renderPreview)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jlong address, jlong rowBytes,
        jfloat scale, jfloat dx, jfloat dy, jint background, jlong selectedId, jint handles, jboolean pixels, jint pixelDivisor,
        jfloat gridSpacing, jint solo, jlongArray soloIds, jfloatArray guides, jfloat refPx) {
    Desktop* d = ptr(h);
    if (!d || w <= 0 || height <= 0 || address == 0 || rowBytes < (jlong)w * 4) return false;
    am::PreviewOptions view;
    view.pixels = pixels;
    view.pixelDivisor = pixelDivisor;
    view.refPx = refPx;
    view.gridSpacing = gridSpacing;
    view.solo = solo;
    if (soloIds) {
        std::vector<jlong> ids((size_t)e->GetArrayLength(soloIds));
        e->GetLongArrayRegion(soloIds, 0, (jsize)ids.size(), ids.data());
        view.ids.assign(ids.begin(), ids.end());
    }
    std::vector<jfloat> g((size_t)e->GetArrayLength(guides));
    e->GetFloatArrayRegion(guides, 0, (jsize)g.size(), g.data());
    for (size_t i = 0; i + 2 < g.size(); i += 3) view.guides.push_back({g[i] != 0, g[i + 1], g[i + 2] != 0});
    auto info = SkImageInfo::Make(w, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
    auto surface = d->surface(info);
    if (!surface) return false;
    const am::LayerEffects fx = d->effects();
    am::SetFastShutterBlur(!d->accurateBlur);
    am::RenderPreview(surface->getCanvas(), w, height, d->scene, time, scale, dx, dy, (uint32_t)background, selectedId, handles, view, d->cameraView, d->media(), fx);
    am::SetFastShutterBlur(false);
    return surface->readPixels(info, reinterpret_cast<void*>(address), (size_t)rowBytes, 0, 0);
}
JNIEXPORT jbyteArray JNICALL JNI(still)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jboolean accurate) {
    Desktop* d = ptr(h);
    if (d->scene.width <= 0) return nullptr;
    std::vector<uint8_t> rgba;
    am::SetCameraView(true);
    const am::LayerEffects fx = d->effects();
    const bool ok = d->offscreen(w, height, false, [&](SkCanvas* c) {
        c->scale((float)w / d->scene.width, (float)height / d->scene.height);
        am::SetFastShutterBlur(accurate != JNI_TRUE);
        am::DrawScene(c, d->scene, time, d->media(), &fx);
        am::SetFastShutterBlur(false);
    }, &rgba);
    return ok ? bytes(e, rgba) : nullptr;
}
JNIEXPORT jboolean JNICALL JNI(stillInto)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jbyteArray out) {
    Desktop* d = ptr(h);
    if (d->scene.width <= 0 || d->scene.height <= 0 || w <= 0 || height <= 0 || (int64_t)e->GetArrayLength(out) < (int64_t)w * height * 4) return false;
    am::SetCameraView(true);
    const am::LayerEffects fx = d->effects();
    const bool ok = d->offscreen(w, height, true, [&](SkCanvas* c) {
        c->scale((float)w / d->scene.width, (float)height / d->scene.height);
        am::DrawScene(c, d->scene, time, d->media(), &fx);
    }, &d->frameBytes);
    if (ok) e->SetByteArrayRegion(out, 0, (jsize)d->frameBytes.size(), (const jbyte*)d->frameBytes.data());
    return ok;
}
JNIEXPORT jint JNICALL JNI(exportFrame)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jbyteArray out) {
    Desktop* d = ptr(h);
    if (d->scene.width <= 0 || d->scene.height <= 0 || w <= 0 || height <= 0) return -1;
    const auto info = SkImageInfo::Make(w, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
    if (!d->exportSurface || d->exportSurface->width() != w || d->exportSurface->height() != height) d->exportSurface = d->surface(info);
    const sk_sp<SkSurface> surface = d->exportSurface;
    if (!surface) return -1;
    {
        SkCanvas* c = surface->getCanvas();
        SkAutoCanvasRestore restore(c, true);
        c->clear(SK_ColorTRANSPARENT);
        c->scale((float)w / d->scene.width, (float)height / d->scene.height);
        am::SetCameraView(true);
        const am::LayerEffects fx = d->effects();
        am::DrawScene(c, d->scene, time, d->media(), &fx);
    }
    const bool had = d->exportPending != nullptr;
    const bool took = had && d->take(e, out);
    auto read = std::make_shared<ExportRead>();
    read->width = w;
    read->height = height;
    d->exportPending = read;
    surface->asyncRescaleAndReadPixels(info, SkIRect::MakeWH(w, height), SkImage::RescaleGamma::kSrc, SkImage::RescaleMode::kNearest,
        [](void* context, std::unique_ptr<const SkImage::AsyncReadResult> pixels) {
            const std::unique_ptr<std::shared_ptr<ExportRead>> held(static_cast<std::shared_ptr<ExportRead>*>(context));
            (*held)->pixels = std::move(pixels);
            (*held)->done = true;
        }, new std::shared_ptr<ExportRead>(read));
    if (d->gpu && d->gpu->context()) d->gpu->context()->submit(GrSyncCpu::kNo);
    return !had ? 0 : took ? 1 : -1;
}
JNIEXPORT jint JNICALL JNI(exportFlush)(JNIEnv* e, jobject, jlong h, jbyteArray out) {
    Desktop* d = ptr(h);
    const bool had = d->exportPending != nullptr;
    const bool took = had && d->take(e, out);
    d->exportPending.reset();
    d->exportSurface.reset();
    return !had || !out ? 0 : took ? 1 : -1;
}
JNIEXPORT jboolean JNICALL JNI(setPixels)(JNIEnv* e, jobject, jlong h, jstring uri, jint w, jint height, jintArray argb) {
    if (w <= 0 || height <= 0 || (int64_t)w * height != e->GetArrayLength(argb)) return false;
    auto data = SkData::MakeUninitialized((size_t)w * height * 4);
    e->GetIntArrayRegion(argb, 0, w * height, (jint*)data->writable_data());
    auto info = SkImageInfo::Make(w, height, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB());
    ptr(h)->pictures[str(e, uri)] = SkImages::RasterFromData(info, data, (size_t)w * 4);
    return true;
}
JNIEXPORT jboolean JNICALL JNI(setModel)(JNIEnv* e, jobject, jstring uri, jfloatArray positions, jfloatArray normals,
                                       jfloatArray uvs, jintArray colors, jintArray triangles, jintArray slots, jboolean twoSided) {
    const auto key = str(e, uri);
    if (!positions) { am::SetModel(key, nullptr); return true; }
    if (!normals || !uvs || !colors || !triangles || !slots) return false;
    auto floats = [&](jfloatArray a) {
        std::vector<float> v((size_t)e->GetArrayLength(a));
        e->GetFloatArrayRegion(a, 0, (jsize)v.size(), v.data());
        return v;
    };
    auto ints = [&](jintArray a) {
        std::vector<int> v((size_t)e->GetArrayLength(a));
        e->GetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
        return v;
    };
    auto model = std::make_shared<am::Model>();
    model->positions = floats(positions);
    model->normals = floats(normals);
    model->uvs = floats(uvs);
    const auto argb = ints(colors);
    model->colors.assign(argb.begin(), argb.end());
    model->triangles = ints(triangles);
    model->slots = ints(slots);
    model->twoSided = twoSided;
    const size_t count = model->positions.size() / 3;
    const bool ok = model->positions.size() == count * 3 && model->normals.size() == count * 3 && model->uvs.size() == count * 2 &&
        model->colors.size() == count && model->triangles.size() % 3 == 0 && model->slots.size() == model->triangles.size() / 3 &&
        std::all_of(model->triangles.begin(), model->triangles.end(), [&](int i) { return i >= 0 && (size_t)i < count; });
    if (ok) am::SetModel(key, std::move(model));
    return ok;
}
JNIEXPORT void JNICALL JNI(dropPicture)(JNIEnv* e, jobject, jlong h, jstring uri) { ptr(h)->pictures.erase(str(e, uri)); }
JNIEXPORT void JNICALL JNI(setCameraView)(JNIEnv*, jobject, jlong h, jboolean on) { ptr(h)->cameraView = on == JNI_TRUE; }
JNIEXPORT void JNICALL JNI(setAccurateBlur)(JNIEnv*, jobject, jlong h, jboolean on) { ptr(h)->accurateBlur = on == JNI_TRUE; }
JNIEXPORT jfloatArray JNICALL JNI(propertyNumbers)(JNIEnv* e, jobject, jlong h, jlong id, jint effect, jstring name, jdouble t) {
    const auto* layer = ptr(h)->scene.layer(id);
    float values[3];
    if (!layer || !am::PropertyNumbers(*layer, effect, str(e, name), t, values)) return nullptr;
    return floats(e, values, 3);
}
JNIEXPORT jfloatArray JNICALL JNI(layerMatrix)(JNIEnv* e, jobject, jlong h, jlong id, jdouble t) {
    Desktop* d = ptr(h);
    am::SetCameraView(d->cameraView);
    const auto* layer = d->scene.layer(id);
    if (!layer) return nullptr;
    float m[18];
    am::WorldMatrix(d->scene, *layer, t).get9(m);
    am::PlaneMatrix(d->scene.layers, *layer, d->scene.width, d->scene.height, t, d->scene.depthOrder).get9(m + 9);
    return floats(e, m, 18);
}
JNIEXPORT jfloatArray JNICALL JNI(layerBounds)(JNIEnv* e, jobject, jlong h, jlong id, jdouble t) {
    Desktop* d = ptr(h);
    const am::SceneScope scope(d->scene);
    am::SetCameraView(d->cameraView);
    const auto* layer = d->scene.layer(id);
    if (!layer) return nullptr;
    const SkRect b = am::SelectionBounds(*layer, t, am::WorldMatrix(d->scene, *layer, t));
    if (b.isEmpty()) return nullptr;
    float v[4] = {b.left(), b.top(), b.right(), b.bottom()};
    return floats(e, v, 4);
}
JNIEXPORT jstring JNICALL JNI(layerOutline)(JNIEnv* e, jobject, jlong h, jlong id, jdouble t) {
    Desktop* d = ptr(h);
    const am::SceneScope scope(d->scene);
    const auto* layer = d->scene.layer(id);
    if (!layer) return nullptr;
    const SkPath path = layer->text ? am::TextGlyphPath(*layer, layer->localTime(t), d->scene.fps) : am::ShapePath(*layer, layer->localTime(t));
    if (path.isEmpty()) return nullptr;
    return e->NewStringUTF(am::OutlineSvg(path).c_str());
}
JNIEXPORT jbyteArray JNICALL JNI(shapeIcon)(JNIEnv* e, jobject, jlong h, jstring xml, jint size, jint color) {
    Desktop* d = ptr(h);
    am::Scene scene;
    std::string error;
    if (!am::ParseScene(str(e, xml), &scene, &error) || scene.layers.empty()) return nullptr;
    scene.layers.front().effects.clear();
    const am::Layer& layer = scene.layers.front();
    std::vector<uint8_t> rgba;
    SkRect content;
    if (am::ContentIconBounds(layer, &content)) {
        if (content.isEmpty()) return nullptr;
        const bool drawn = d->offscreen(size, size, false, [&](SkCanvas* canvas) {
            const float scale = size * 0.9f / std::max(content.width(), content.height());
            canvas->translate(size / 2.0f, size / 2.0f);
            canvas->scale(scale, scale);
            canvas->translate(-content.centerX(), -content.centerY());
            am::DrawContentIcon(canvas, layer, d->media());
        }, &rgba);
        return drawn ? bytes(e, rgba) : nullptr;
    }
    const SkPath path = am::ShapePath(layer, 0);
    const SkPath stroke = am::StrokePath(layer, path, 0);
    SkMatrix orientation = SkMatrix::I();
    if (layer.fillType == "media") {
        auto info = d->scene.mediaOrientation.find(layer.mediaUri);
        if (info != d->scene.mediaOrientation.end()) orientation.setRotate(info->second);
    }
    SkRect bounds = layer.fillType == "none" ? stroke.getBounds() : path.getBounds();
    if (layer.fillType != "none" && !stroke.isEmpty()) bounds.join(stroke.getBounds());
    bounds = orientation.mapRect(bounds);
    if (bounds.isEmpty()) return nullptr;
    const bool ok = d->offscreen(size, size, false, [&](SkCanvas* canvas) {
        const float scale = size * 0.9f / std::max(bounds.width(), bounds.height());
        canvas->translate(size / 2.0f, size / 2.0f);
        canvas->scale(scale, scale);
        canvas->translate(-bounds.centerX(), -bounds.centerY());
        canvas->concat(orientation);
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor((SkColor)color);
        if (layer.fillType == "media" && !d->media()(layer.mediaUri, layer.id)) return;
        if (layer.fillType == "media") am::DrawMedia(canvas, layer, path, 1, d->media());
        else if (layer.text && layer.fillType != "none") am::DrawText(canvas, layer, 0, paint);
        else if (layer.fillType != "none") canvas->drawPath(path, paint);
        canvas->drawPath(stroke, paint);
    }, &rgba);
    return ok ? bytes(e, rgba) : nullptr;
}
JNIEXPORT jlong JNICALL JNI(hitTest)(JNIEnv*, jobject, jlong h, jfloat x, jfloat y, jdouble t) {
    Desktop* d = ptr(h);
    const am::SceneScope scope(d->scene);
    am::SetCameraView(d->cameraView);
    return am::TopLayerAt(d->scene, t, x, y, d->maskLookup());
}
JNIEXPORT jfloatArray JNICALL JNI(layerQuad)(JNIEnv* e, jobject, jlong h, jlong id, jdouble t) {
    Desktop* d = ptr(h);
    const am::SceneScope scope(d->scene);
    am::SetCameraView(d->cameraView);
    const auto* layer = d->scene.layer(id);
    if (!layer || !layer->activeAt(t)) return nullptr;
    SkPoint p[4];
    if (!am::SelectionQuad(d->scene, *layer, t, p)) return nullptr;
    float values[8];
    for (int i = 0; i < 4; ++i) { values[2*i] = p[i].x(); values[2*i+1] = p[i].y(); }
    auto result = e->NewFloatArray(8);
    if (!result) return nullptr;
    e->SetFloatArrayRegion(result, 0, 8, values);
    return result;
}
JNIEXPORT jstring JNICALL JNI(shapeOutline)(JNIEnv* e, jobject, jstring scene, jfloat t, jboolean rounded) {
    std::string svg;
    if (!am::ShapeOutlineSvg(str(e, scene), t, rounded, &svg)) return nullptr;
    return e->NewStringUTF(svg.c_str());
}
JNIEXPORT jfloat JNICALL JNI(ease)(JNIEnv* e, jobject, jstring easing, jfloat u) { return am::Ease(am::ParseEasing(str(e, easing).c_str()), u); }
JNIEXPORT jfloatArray JNICALL JNI(blipTimes)(JNIEnv* e, jobject, jstring text, jfloat speed, jfloat pause, jint every) {
    auto times = am::textanim::BlipTimes(str(e, text), speed, pause, every);
    auto result = e->NewFloatArray((jsize)times.size());
    if (!result) return nullptr;
    e->SetFloatArrayRegion(result, 0, (jsize)times.size(), times.data());
    return result;
}
JNIEXPORT jbyteArray JNICALL JNI(encodeWebp)(JNIEnv* e, jobject, jbyteArray rgba, jint w, jint h, jint quality, jboolean lossless) {
    std::vector<uint8_t> px((size_t)e->GetArrayLength(rgba));
    if (w <= 0 || h <= 0 || px.size() < (size_t)w * h * 4) return nullptr;
    e->GetByteArrayRegion(rgba, 0, (jsize)px.size(), reinterpret_cast<jbyte*>(px.data()));
    SkPixmap pixmap(SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kUnpremul_SkAlphaType), px.data(), (size_t)w * 4);
    SkWebpEncoder::Options options;
    options.fCompression = lossless ? SkWebpEncoder::Compression::kLossless : SkWebpEncoder::Compression::kLossy;
    options.fQuality = (float)quality;
    SkDynamicMemoryWStream out;
    if (!SkWebpEncoder::Encode(&out, pixmap, options)) return nullptr;
    sk_sp<SkData> data = out.detachAsData();
    const uint8_t* start = data->bytes();
    return bytes(e, std::vector<uint8_t>(start, start + data->size()));
}
}
