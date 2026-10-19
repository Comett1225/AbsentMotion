#include <android/hardware_buffer_jni.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "easing.h"
#include "engine.h"

#define JNI_FN(name) Java_app_absentmotion_engine_NativeEngine_##name

namespace {
am::Engine* engineOf(jlong handle) { return reinterpret_cast<am::Engine*>(handle); }
am::Target* targetOf(jlong handle) { return reinterpret_cast<am::Target*>(handle); }
am::InterpolationParams interpolationParams(JNIEnv* env, jfloatArray params) {
    jfloat p[6] = {4, 5, 6, 1, 0.15f, 4};
    env->GetFloatArrayRegion(params, 0, std::min<jsize>(6, env->GetArrayLength(params)), p);
    return {(int)p[0], (int)p[1], (int)p[2], (int)p[3], p[4], (int)p[5]};
}
}

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv* env, jclass, jint backend) {
    std::string error;
    auto engine = am::Engine::Make(static_cast<am::Backend>(backend), &error);
    if (!engine) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv*, jclass, jlong engine) {
    delete engineOf(engine);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeInfo)(JNIEnv* env, jclass, jlong engine) {
    return env->NewStringUTF(engineOf(engine)->info().c_str());
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeFailed)(JNIEnv*, jclass, jlong engine) {
    return !engine || engineOf(engine)->failed();
}

JNIEXPORT jlong JNICALL JNI_FN(nativeAttach)(JNIEnv* env, jclass, jlong engine, jobject surface,
                                            jboolean forEncoder) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    am::Target* target = engineOf(engine)->attach(window, forEncoder);
    ANativeWindow_release(window);
    return reinterpret_cast<jlong>(target);
}

JNIEXPORT void JNICALL JNI_FN(nativeDetach)(JNIEnv*, jclass, jlong engine, jlong target) {
    engineOf(engine)->detach(targetOf(target));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeTiming)(JNIEnv* env, jclass, jlong engine, jlong target) {
    return env->NewStringUTF(engineOf(engine)->timing(targetOf(target)).c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeSetScene)(JNIEnv* env, jclass, jlong engine, jbyteArray xml) {
    std::string text((size_t)env->GetArrayLength(xml), '\0');
    env->GetByteArrayRegion(xml, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    std::string error;
    return engineOf(engine)->setScene(text, &error) ? nullptr : env->NewStringUTF(error.c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeLoadEffect)(JNIEnv* env, jclass, jlong engine, jbyteArray xml) {
    std::string text((size_t)env->GetArrayLength(xml), '\0');
    env->GetByteArrayRegion(xml, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    return env->NewStringUTF(engineOf(engine)->loadEffect(text).c_str());
}

JNIEXPORT jint JNICALL JNI_FN(nativeMaxPictureSize)(JNIEnv*, jclass, jlong engine) {
    return engineOf(engine)->maxPictureSize();
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPicture)(JNIEnv* env, jclass, jlong engine, jbyteArray uri,
                                                    jlong layerId, jobject bitmap, jobject hardwareBuffer,
                                                    jboolean opaque) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    if (!bitmap) {
        auto* buffer = hardwareBuffer ? AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer) : nullptr;
        return engineOf(engine)->setPicture(text, layerId, buffer, nullptr, 0, 0, 0, opaque);
    }
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const bool ok = engineOf(engine)->setPicture(text, layerId, nullptr, pixels, info.width, info.height,
                                                info.stride, opaque);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetVideoFrame)(JNIEnv* env, jclass, jlong engine, jbyteArray uri, jlong layerId,
                                                       jlong pts, jobject hardwareBuffer) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    return engineOf(engine)->setVideoFrame(text, layerId, pts, AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer));
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeInterpolateVideo)(JNIEnv* env, jclass, jlong engine, jbyteArray uri, jlong layerId,
                                                          jlong a, jlong b, jfloat t, jboolean flow, jfloatArray params) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    return engineOf(engine)->interpolateVideo(text, layerId, a, b, t, flow, interpolationParams(env, params));
}

JNIEXPORT jint JNICALL JNI_FN(nativeRenderFrame)(JNIEnv*, jclass, jlong engine, jlong target, jdouble timeMs,
                                                 jint background, jlong presentTimeNs) {
    return engineOf(engine)->renderFrame(targetOf(target), timeMs, (uint32_t)background, presentTimeNs);
}

JNIEXPORT jint JNICALL JNI_FN(nativeRenderScene)(JNIEnv* env, jclass, jlong engine, jlong target, jdouble timeMs,
                                                 jfloat scale, jfloat dx, jfloat dy, jint background,
                                                 jlong selectedId, jint handles, jboolean pixels, jint pixelDivisor,
                                                 jfloat gridSpacing, jint solo, jlongArray soloIds,
                                                 jfloatArray guides) {
    am::PreviewOptions view;
    view.pixels = pixels;
    view.pixelDivisor = pixelDivisor;
    view.gridSpacing = gridSpacing;
    view.solo = solo;
    if (soloIds) {
        std::vector<jlong> ids((size_t)env->GetArrayLength(soloIds));
        env->GetLongArrayRegion(soloIds, 0, (jsize)ids.size(), ids.data());
        view.ids.assign(ids.begin(), ids.end());
    }
    std::vector<jfloat> g((size_t)env->GetArrayLength(guides));
    env->GetFloatArrayRegion(guides, 0, (jsize)g.size(), g.data());
    for (size_t i = 0; i + 2 < g.size(); i += 3) view.guides.push_back({g[i] != 0, g[i + 1], g[i + 2] != 0});
    return engineOf(engine)->renderScene(targetOf(target), timeMs, scale, dx, dy, (uint32_t)background,
                                         selectedId, handles, view);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetCameraView)(JNIEnv*, jclass, jlong engine, jboolean on) {
    engineOf(engine)->setCameraView(on);
}

JNIEXPORT jfloatArray JNICALL JNI_FN(nativeEase)(JNIEnv* env, jclass, jstring easing, jfloatArray us) {
    const char* text = env->GetStringUTFChars(easing, nullptr);
    const am::Easing e = am::ParseEasing(text);
    env->ReleaseStringUTFChars(easing, text);
    const jsize n = env->GetArrayLength(us);
    std::vector<float> values((size_t)n);
    env->GetFloatArrayRegion(us, 0, n, values.data());
    for (float& v : values) v = am::Ease(e, v);
    jfloatArray out = env->NewFloatArray(n);
    if (out) env->SetFloatArrayRegion(out, 0, n, values.data());
    return out;
}

JNIEXPORT jfloatArray JNICALL JNI_FN(nativeLayerQuad)(JNIEnv* env, jclass, jlong engine, jlong layerId,
                                                      jdouble timeMs) {
    float xy[8];
    if (!engineOf(engine)->layerQuad(layerId, timeMs, xy)) return nullptr;
    jfloatArray out = env->NewFloatArray(8);
    if (!out) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetFloatArrayRegion(out, 0, 8, xy);
    return out;
}

JNIEXPORT jfloatArray JNICALL JNI_FN(nativeLayerBounds)(JNIEnv* env, jclass, jlong engine, jlong layerId, jdouble timeMs) {
    float bounds[4];
    if (!engineOf(engine)->layerBounds(layerId, timeMs, bounds)) return nullptr;
    jfloatArray out = env->NewFloatArray(4);
    if (!out) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetFloatArrayRegion(out, 0, 4, bounds);
    return out;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeLayerOutline)(JNIEnv* env, jclass, jlong engine, jlong layerId, jdouble timeMs) {
    std::string svg;
    if (!engineOf(engine)->layerOutline(layerId, timeMs, &svg)) return nullptr;
    return env->NewStringUTF(svg.c_str());
}

JNIEXPORT jlong JNICALL JNI_FN(nativeHitTest)(JNIEnv*, jclass, jlong engine, jfloat x, jfloat y, jdouble timeMs) {
    return engineOf(engine)->hitTest(x, y, timeMs);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeStill)(JNIEnv* env, jclass, jlong engine, jdouble timeMs, jint width,
                                                 jint height) {
    std::vector<uint8_t> rgba;
    if (!engineOf(engine)->still(timeMs, width, height, &rgba)) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)rgba.size());
    if (!out) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, (jsize)rgba.size(), reinterpret_cast<const jbyte*>(rgba.data()));
    return out;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeShapeIcon)(JNIEnv* env, jclass, jlong engine, jbyteArray xml, jint size,
                                                     jint color) {
    std::string text((size_t)env->GetArrayLength(xml), '\0');
    env->GetByteArrayRegion(xml, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    std::vector<uint8_t> rgba;
    if (!engineOf(engine)->shapeIcon(text, size, (uint32_t)color, &rgba)) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)rgba.size());
    if (!out) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, (jsize)rgba.size(), reinterpret_cast<const jbyte*>(rgba.data()));
    return out;
}

}
