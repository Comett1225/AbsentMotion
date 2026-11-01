#include <jni.h>
#include <map>
#include <string>
#include <vector>
#include "effects.h"
#include "scene.h"
#include "easing.h"
#include "textanim.h"
#include "gpu.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkImage.h"
#include "include/core/SkSurface.h"
#include "include/codec/SkCodec.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/GrDirectContext.h"

struct Desktop {
    am::Scene scene;
    am::EffectRuntime runtime;
    std::map<std::string, sk_sp<SkImage>> pictures;
    sk_sp<GrDirectContext> gpu;
    Desktop() : gpu(CreateDesktopGpu()) {}
    am::MediaPictures media() {
        return [this](const std::string& uri, int64_t) -> sk_sp<SkImage> {
            auto it = pictures.find(uri);
            return it == pictures.end() ? nullptr : it->second;
        };
    }
};

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
#define JNI(name) Java_app_absentmotion_pc_engine_DesktopNative_##name
extern "C" {
JNIEXPORT jlong JNICALL JNI(create)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Desktop()); }
JNIEXPORT void JNICALL JNI(destroy)(JNIEnv*, jobject, jlong h) { delete ptr(h); }
JNIEXPORT jstring JNICALL JNI(loadEffect)(JNIEnv* e, jobject, jlong h, jstring xml) {
    return e->NewStringUTF(ptr(h)->runtime.load(str(e, xml)).c_str());
}
JNIEXPORT jstring JNICALL JNI(setScene)(JNIEnv* e, jobject, jlong h, jstring xml) {
    am::Scene next;
    std::string error;
    if (!am::ParseScene(str(e, xml), &next, &error)) return e->NewStringUTF(error.c_str());
    ptr(h)->scene = std::move(next);
    ptr(h)->pictures.clear();
    return nullptr;
}
JNIEXPORT jboolean JNICALL JNI(setPicture)(JNIEnv* e, jobject, jlong h, jstring uri, jstring path) {
    auto data = SkData::MakeFromFileName(str(e, path).c_str());
    auto image = data ? SkImages::DeferredFromEncodedData(data) : nullptr;
    if (!image) return false;
    ptr(h)->pictures[str(e, uri)] = image;
    return true;
}
JNIEXPORT jboolean JNICALL JNI(setFrame)(JNIEnv* e, jobject, jlong h, jstring uri, jint w, jint height, jbyteArray bytes) {
    if (w <= 0 || height <= 0 || (int64_t)w * height * 4 != e->GetArrayLength(bytes)) return false;
    auto data = SkData::MakeUninitialized((size_t)w * height * 4);
    e->GetByteArrayRegion(bytes, 0, e->GetArrayLength(bytes), (jbyte*)data->writable_data());
    auto info = SkImageInfo::Make(w, height, kRGBA_8888_SkColorType, kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB());
    ptr(h)->pictures[str(e, uri)] = SkImages::RasterFromData(info, data, (size_t)w * 4);
    return true;
}
JNIEXPORT jboolean JNICALL JNI(render)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jobject buffer) {
    Desktop* d = ptr(h);
    if (!d || w <= 0 || height <= 0 || d->scene.width <= 0 || d->scene.height <= 0 || e->GetDirectBufferCapacity(buffer) < (jlong)w * height * 4) return false;
    auto info = SkImageInfo::Make(w, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
    auto surface = d->gpu ? SkSurfaces::RenderTarget(d->gpu.get(), skgpu::Budgeted::kNo, info) : nullptr;
    if (!surface) surface = SkSurfaces::Raster(info);
    if (!surface) return false;
    am::LayerEffects fx;
    fx.active = [&](const am::Layer& l) { return d->runtime.active(l); };
    fx.lifted = [&](const am::Layer& l) { return d->runtime.lifted(l); };
    fx.spills = [&](const am::Layer& l) { return d->runtime.spills(l); };
    fx.behind = [&](const am::Layer& l) { return d->runtime.behind(l); };
    fx.steady = [&](const am::Layer& l, float a, float b) { return d->runtime.steady(l, a, b); };
    fx.draw = [&](SkCanvas* c, const am::Scene& s, const am::Layer& l, double t, const SkRect& r, const SkMatrix& m, const std::function<void(SkCanvas*)>& src, const std::function<void(SkCanvas*)>& below) { return d->runtime.draw(c, s, l, t, r, m, src, below); };
    auto canvas = surface->getCanvas();
    canvas->clear(SK_ColorTRANSPARENT);
    canvas->scale((float)w / d->scene.width, (float)height / d->scene.height);
    am::SetCameraView(true);
    am::DrawScene(canvas, d->scene, time, d->media(), &fx);
    return surface->readPixels(info, e->GetDirectBufferAddress(buffer), (size_t)w * 4, 0, 0);
}
JNIEXPORT jlong JNICALL JNI(hitTest)(JNIEnv*, jobject, jlong h, jfloat x, jfloat y, jdouble t) {
    Desktop* d = ptr(h);
    const am::SceneScope scope(d->scene);
    am::SetCameraView(true);
    return am::TopLayerAt(d->scene, t, x, y, d->media());
}
JNIEXPORT jfloatArray JNICALL JNI(layerQuad)(JNIEnv* e, jobject, jlong h, jlong id, jdouble t) {
    Desktop* d = ptr(h);
    const am::SceneScope scope(d->scene);
    const auto* layer = d->scene.layer(id);
    if (!layer || !layer->activeAt(t)) return nullptr;
    SkPoint p[4];
    am::SelectionBounds(*layer, t, SkMatrix::I()).toQuad(p);
    am::WorldMatrix(d->scene, *layer, t).mapPoints(p);
    float values[8];
    for (int i = 0; i < 4; ++i) { values[2*i] = p[i].x(); values[2*i+1] = p[i].y(); }
    auto result = e->NewFloatArray(8);
    e->SetFloatArrayRegion(result, 0, 8, values);
    return result;
}
JNIEXPORT jfloat JNICALL JNI(ease)(JNIEnv* e, jobject, jstring easing, jfloat u) { return am::Ease(am::ParseEasing(str(e, easing).c_str()), u); }
JNIEXPORT jfloatArray JNICALL JNI(blipTimes)(JNIEnv* e, jobject, jstring text, jfloat speed, jfloat pause, jint every) {
    auto times = am::textanim::BlipTimes(str(e, text), speed, pause, every);
    auto result = e->NewFloatArray((jsize)times.size());
    e->SetFloatArrayRegion(result, 0, (jsize)times.size(), times.data());
    return result;
}
}
