#include <jni.h>

#include <cmath>
#include <functional>
#include <string>
#include <vector>

#include "drawing.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkSurface.h"
#include "scene.h"

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativePlaces(JNIEnv* env, jclass, jstring xml, jlongArray ids, jdoubleArray times) {
    const char* text = env->GetStringUTFChars(xml, nullptr);
    am::Scene scene;
    std::string error;
    const bool parsed = am::ParseScene(text, &scene, &error);
    env->ReleaseStringUTFChars(xml, text);
    if (!parsed) return nullptr;
    const jsize n = env->GetArrayLength(ids);
    std::vector<jlong> id((size_t)n);
    std::vector<jdouble> at((size_t)n);
    env->GetLongArrayRegion(ids, 0, n, id.data());
    env->GetDoubleArrayRegion(times, 0, n, at.data());
    constexpr int kEach = 33;
    std::vector<float> out((size_t)n * kEach, NAN);
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    for (jsize i = 0; i < n; i++) {
        const am::Layer* layer = scene.layer(id[(size_t)i]);
        if (!layer) continue;
        float* o = out.data() + (size_t)i * kEach;
        am::WorldMatrix(scene, *layer, at[(size_t)i]).get9(o);
        const am::Placement p = am::EffectivePlacement(scene.layers, *layer, at[(size_t)i]);
        const float place[] = {p.location.fX, p.location.fY, p.z, p.pivot.fX, p.pivot.fY, p.scale.fX, p.scale.fY, p.rotation};
        for (int k = 0; k < 8; k++) o[9 + k] = place[k];
        p.tilt.getRowMajor(o + 17);
    }
    jfloatArray result = env->NewFloatArray((jsize)out.size());
    if (result) env->SetFloatArrayRegion(result, 0, (jsize)out.size(), out.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativeSelectionQuad(JNIEnv* env, jclass, jstring xml, jlong id, jdouble t) {
    const char* text = env->GetStringUTFChars(xml, nullptr);
    am::Scene scene;
    std::string error;
    const bool parsed = am::ParseScene(text, &scene, &error);
    env->ReleaseStringUTFChars(xml, text);
    if (!parsed) return nullptr;
    const am::Layer* layer = scene.layer(id);
    if (!layer) return nullptr;
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    SkPoint corners[4];
    if (!am::SelectionQuad(scene, *layer, t, corners)) return nullptr;
    float out[8];
    for (int i = 0; i < 4; i++) {
        out[2 * i] = corners[i].fX;
        out[2 * i + 1] = corners[i].fY;
    }
    jfloatArray result = env->NewFloatArray(8);
    if (result) env->SetFloatArrayRegion(result, 0, 8, out);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_absentmotion_editor_PivotEngine_nativeShapeOutline(JNIEnv* env, jclass, jstring xml, jfloat t, jboolean rounded) {
    const char* text = env->GetStringUTFChars(xml, nullptr);
    std::string svg;
    const bool made = am::ShapeOutlineSvg(text, t, rounded, &svg);
    env->ReleaseStringUTFChars(xml, text);
    return made ? env->NewStringUTF(svg.c_str()) : nullptr;
}

extern "C" JNIEXPORT jbooleanArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativeShapeHits(JNIEnv* env, jclass, jstring xml, jfloat t, jfloatArray points) {
    const char* text = env->GetStringUTFChars(xml, nullptr);
    am::Scene scene;
    std::string error;
    const bool parsed = am::ParseScene(text, &scene, &error);
    env->ReleaseStringUTFChars(xml, text);
    if (!parsed || scene.layers.empty()) return nullptr;
    const am::SceneScope scope(scene);
    const SkPath path = am::ShapePath(scene.layers.front(), t);
    const jsize n = env->GetArrayLength(points) / 2;
    std::vector<float> xy((size_t)n * 2);
    env->GetFloatArrayRegion(points, 0, n * 2, xy.data());
    std::vector<jboolean> hits((size_t)n);
    for (jsize i = 0; i < n; i++) hits[(size_t)i] = path.contains(xy[(size_t)i * 2], xy[(size_t)i * 2 + 1]);
    jbooleanArray result = env->NewBooleanArray(n);
    if (result) env->SetBooleanArrayRegion(result, 0, n, hits.data());
    return result;
}

static bool ParsedScene(JNIEnv* env, jstring xml, am::Scene* scene) {
    const char* text = env->GetStringUTFChars(xml, nullptr);
    std::string error;
    const bool parsed = am::ParseScene(text, scene, &error);
    env->ReleaseStringUTFChars(xml, text);
    return parsed;
}

static jbyteArray Alphas(JNIEnv* env, int width, int height, const std::function<void(SkCanvas*)>& draw) {
    sk_sp<SkSurface> surface = SkSurfaces::Raster(SkImageInfo::MakeA8(width, height));
    if (!surface) return nullptr;
    surface->getCanvas()->clear(SK_ColorTRANSPARENT);
    draw(surface->getCanvas());
    std::vector<jbyte> alpha((size_t)width * height);
    if (!surface->readPixels(SkImageInfo::MakeA8(width, height), alpha.data(), (size_t)width, 0, 0)) return nullptr;
    jbyteArray result = env->NewByteArray((jsize)alpha.size());
    if (result) env->SetByteArrayRegion(result, 0, (jsize)alpha.size(), alpha.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativeLayerBounds(JNIEnv* env, jclass, jstring xml, jlong id, jdouble t) {
    am::Scene scene;
    if (!ParsedScene(env, xml, &scene)) return nullptr;
    const am::Layer* layer = scene.layer(id);
    if (!layer) return nullptr;
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    const SkRect b = am::SelectionBounds(*layer, t, am::WorldMatrix(scene, *layer, t));
    if (b.isEmpty()) return nullptr;
    const float out[] = {b.left(), b.top(), b.right(), b.bottom()};
    jfloatArray result = env->NewFloatArray(4);
    if (result) env->SetFloatArrayRegion(result, 0, 4, out);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativeHits(JNIEnv* env, jclass, jstring xml, jdouble t, jfloatArray points) {
    am::Scene scene;
    if (!ParsedScene(env, xml, &scene)) return nullptr;
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    const jsize n = env->GetArrayLength(points) / 2;
    std::vector<float> xy((size_t)n * 2);
    env->GetFloatArrayRegion(points, 0, n * 2, xy.data());
    std::vector<jlong> ids((size_t)n);
    const am::MediaPictures none = [](const std::string&, int64_t) { return sk_sp<SkImage>(); };
    for (jsize i = 0; i < n; i++) ids[(size_t)i] = am::TopLayerAt(scene, t, xy[(size_t)i * 2], xy[(size_t)i * 2 + 1], none);
    jlongArray result = env->NewLongArray(n);
    if (result) env->SetLongArrayRegion(result, 0, n, ids.data());
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativeDrawn(JNIEnv* env, jclass, jstring xml, jdouble t) {
    am::Scene scene;
    if (!ParsedScene(env, xml, &scene) || scene.width <= 0 || scene.height <= 0) return nullptr;
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    const am::MediaPictures none = [](const std::string&, int64_t) { return sk_sp<SkImage>(); };
    return Alphas(env, scene.width, scene.height, [&](SkCanvas* canvas) { am::DrawScene(canvas, scene, t, none); });
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativeDrawnPreview(JNIEnv* env, jclass, jstring xml, jdouble t, jint width, jint height, jfloat scale, jfloat dx, jfloat dy) {
    am::Scene scene;
    if (!ParsedScene(env, xml, &scene) || scene.width <= 0 || scene.height <= 0 || width <= 0 || height <= 0) return nullptr;
    const am::SceneScope scope(scene);
    am::SetCameraView(false);
    const am::MediaPictures none = [](const std::string&, int64_t) { return sk_sp<SkImage>(); };
    return Alphas(env, width, height, [&](SkCanvas* canvas) {
        canvas->translate(dx, dy);
        canvas->scale(scale, scale);
        am::DrawScene(canvas, scene, t, none);
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_absentmotion_editor_PivotEngine_nativePointMarks(JNIEnv* env, jclass, jstring xml, jlong id, jdouble t) {
    am::Scene scene;
    if (!ParsedScene(env, xml, &scene) || scene.width <= 0 || scene.height <= 0) return nullptr;
    const am::Layer* layer = scene.layer(id);
    if (!layer) return nullptr;
    const am::SceneScope scope(scene);
    am::SetCameraView(true);
    const SkMatrix world = am::WorldMatrix(scene, *layer, t);
    return Alphas(env, scene.width, scene.height, [&](SkCanvas* canvas) {
        am::DrawPointEdit(canvas, *layer, world, 1, am::kPointEdit | (0x7f << 20), layer->localTime(t));
    });
}
