#include <jni.h>
#include <map>
#include <string>
#include <vector>
#include "effects.h"
#include "scene.h"
#include "easing.h"
#include "textanim.h"
#include "gpu.h"
#include "drawing.h"
#include "text.h"
#include "preview.h"
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
    std::unique_ptr<DesktopGpu> gpu;
    bool cameraView = true;
    bool accurateBlur = true;
    Desktop() : gpu(CreateDesktopGpu()) {}
    am::MediaPictures media() {
        return [this](const std::string& uri, int64_t) -> sk_sp<SkImage> {
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
        fx.steady = [this](const am::Layer& l, float a, float b) { return runtime.steady(l, a, b); };
        fx.draw = [this](SkCanvas* c, const am::Scene& s, const am::Layer& l, double t, const SkRect& r, const SkMatrix& m, const std::function<void(SkCanvas*)>& src, const std::function<void(SkCanvas*)>& below) { return runtime.draw(c, s, l, t, r, m, src, below); };
        return fx;
    }
    sk_sp<SkSurface> surface(const SkImageInfo& info) {
        auto made = gpu && gpu->context() ? SkSurfaces::RenderTarget(gpu->context(), skgpu::Budgeted::kNo, info) : nullptr;
        return made ? made : SkSurfaces::Raster(info);
    }
    bool offscreen(int width, int height, bool premultiplied, const std::function<void(SkCanvas*)>& paint, std::vector<uint8_t>* rgba) {
        if (width <= 0 || height <= 0) return false;
        auto info = SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
        auto s = surface(info);
        if (!s) return false;
        s->getCanvas()->clear(SK_ColorTRANSPARENT);
        paint(s->getCanvas());
        rgba->resize((size_t)width * height * 4);
        return s->readPixels(premultiplied ? info : info.makeAlphaType(kUnpremul_SkAlphaType), rgba->data(), (size_t)width * 4, 0, 0);
    }
};

static jbyteArray bytes(JNIEnv* e, const std::vector<uint8_t>& v) {
    auto r = e->NewByteArray((jsize)v.size());
    e->SetByteArrayRegion(r, 0, (jsize)v.size(), (const jbyte*)v.data());
    return r;
}

static jfloatArray floats(JNIEnv* e, const float* v, int n) {
    auto r = e->NewFloatArray(n);
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
JNIEXPORT jboolean JNICALL JNI(renderPreview)(JNIEnv* e, jobject, jlong h, jdouble time, jint w, jint height, jobject buffer,
        jfloat scale, jfloat dx, jfloat dy, jint background, jlong selectedId, jint handles, jboolean pixels, jint pixelDivisor,
        jfloat gridSpacing, jint solo, jlongArray soloIds, jfloatArray guides, jfloat refPx) {
    Desktop* d = ptr(h);
    if (!d || w <= 0 || height <= 0 || e->GetDirectBufferCapacity(buffer) < (jlong)w * height * 4) return false;
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
    return surface->readPixels(info, e->GetDirectBufferAddress(buffer), (size_t)w * 4, 0, 0);
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
JNIEXPORT jboolean JNICALL JNI(setPixels)(JNIEnv* e, jobject, jlong h, jstring uri, jint w, jint height, jintArray argb) {
    if (w <= 0 || height <= 0 || (int64_t)w * height != e->GetArrayLength(argb)) return false;
    auto data = SkData::MakeUninitialized((size_t)w * height * 4);
    e->GetIntArrayRegion(argb, 0, w * height, (jint*)data->writable_data());
    auto info = SkImageInfo::Make(w, height, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB());
    ptr(h)->pictures[str(e, uri)] = SkImages::RasterFromData(info, data, (size_t)w * 4);
    return true;
}
JNIEXPORT void JNICALL JNI(dropPicture)(JNIEnv* e, jobject, jlong h, jstring uri) { ptr(h)->pictures.erase(str(e, uri)); }
JNIEXPORT void JNICALL JNI(setCameraView)(JNIEnv*, jobject, jlong h, jboolean on) { ptr(h)->cameraView = on == JNI_TRUE; }
JNIEXPORT void JNICALL JNI(setAccurateBlur)(JNIEnv*, jobject, jlong h, jboolean on) { ptr(h)->accurateBlur = on == JNI_TRUE; }
JNIEXPORT jfloatArray JNICALL JNI(layerMatrix)(JNIEnv* e, jobject, jlong h, jlong id, jdouble t) {
    Desktop* d = ptr(h);
    am::SetCameraView(d->cameraView);
    const auto* layer = d->scene.layer(id);
    if (!layer) return nullptr;
    float m[18];
    am::WorldMatrix(d->scene, *layer, t).get9(m);
    am::PlaneMatrix(d->scene.layers, *layer, d->scene.width, d->scene.height, t).get9(m + 9);
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
    const SkPath path = am::ShapePath(layer, 0);
    const SkPath stroke = am::StrokePath(layer, path, 0);
    SkRect bounds = layer.fillType == "none" ? stroke.getBounds() : path.getBounds();
    if (layer.fillType != "none" && !stroke.isEmpty()) bounds.join(stroke.getBounds());
    if (bounds.isEmpty()) return nullptr;
    std::vector<uint8_t> rgba;
    const bool ok = d->offscreen(size, size, true, [&](SkCanvas* canvas) {
        const float scale = size * 0.9f / std::max(bounds.width(), bounds.height());
        canvas->translate(size / 2.0f, size / 2.0f);
        canvas->scale(scale, scale);
        canvas->translate(-bounds.centerX(), -bounds.centerY());
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor((SkColor)color);
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
