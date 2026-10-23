#include <android/hardware_buffer_jni.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <asm/hwcap.h>
#include <jni.h>
#include <sys/auxv.h>

#include <algorithm>
#include <string>
#include <vector>

#include "easing.h"
#include "engine.h"
#include "scene.h"

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
                                                    jboolean opaque, jint standard, jint range, jint left, jint top, jint right, jint bottom) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    if (!bitmap) {
        auto* buffer = hardwareBuffer ? AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer) : nullptr;
        return engineOf(engine)->setPicture(text, layerId, buffer, nullptr, 0, 0, 0, opaque, standard, range, {left, top, right, bottom});
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
                                                       jlong pts, jobject hardwareBuffer, jint standard, jint range, jint left, jint top, jint right, jint bottom) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    return engineOf(engine)->setVideoFrame(text, layerId, pts, AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer), standard, range, {left, top, right, bottom});
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeInterpolateVideo)(JNIEnv* env, jclass, jlong engine, jbyteArray uri, jlong layerId,
                                                          jlongArray frames, jfloat t, jint mode, jboolean soft, jfloat blur, jfloatArray params,
                                                          jobject made) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    jlong f[4] = {0, 0, 0, 0};
    env->GetLongArrayRegion(frames, 0, std::min<jsize>(4, env->GetArrayLength(frames)), f);
    am::SmoothRequest request{f[0], f[1], f[2], f[3], t, static_cast<am::SmoothMode>(std::clamp<jint>(mode, 0, 3)), soft == JNI_TRUE, blur};
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (made) {
        if (AndroidBitmap_getInfo(env, made, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            AndroidBitmap_lockPixels(env, made, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
        request.pixels = pixels;
        request.width = (int)info.width;
        request.height = (int)info.height;
        request.rowBytes = info.stride;
    }
    const bool ok = engineOf(engine)->interpolateVideo(text, layerId, request, interpolationParams(env, params));
    if (made) AndroidBitmap_unlockPixels(env, made);
    return ok;
}

JNIEXPORT jint JNICALL JNI_FN(nativeMoshStart)(JNIEnv* env, jclass, jlong engine, jbyteArray uri, jlong layerId, jlongArray pts,
                                                jfloatArray mosh, jfloatArray flow) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    std::vector<int64_t> stamps((size_t)env->GetArrayLength(pts));
    env->GetLongArrayRegion(pts, 0, (jsize)stamps.size(), reinterpret_cast<jlong*>(stamps.data()));
    am::MoshParams params;
    jfloat p[6] = {params.intensity, params.block, params.decay, params.glitch, params.bloom, params.source};
    env->GetFloatArrayRegion(mosh, 0, std::min<jsize>(6, env->GetArrayLength(mosh)), p);
    params = {p[0], p[1], p[2], p[3], p[4], p[5], interpolationParams(env, flow)};
    return engineOf(engine)->moshStart(text, layerId, stamps, params);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeMoshFrame)(JNIEnv* env, jclass, jlong engine, jbyteArray uri, jlong layerId, jint step,
                                                   jobject hardwareBuffer, jint standard, jint range, jint left, jint top, jint right, jint bottom) {
    std::string text((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)text.size(), reinterpret_cast<jbyte*>(text.data()));
    return engineOf(engine)->moshFrame(text, layerId, step, AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer), standard, range,
                                       {left, top, right, bottom});
}

JNIEXPORT jint JNICALL JNI_FN(nativeRenderFrame)(JNIEnv*, jclass, jlong engine, jlong target, jdouble timeMs,
                                                 jint background, jlong presentTimeNs) {
    return engineOf(engine)->renderFrame(targetOf(target), timeMs, (uint32_t)background, presentTimeNs);
}

JNIEXPORT jint JNICALL JNI_FN(nativeRenderBuffer)(JNIEnv* env, jclass, jlong engine, jobject buffer, jdouble timeMs,
                                                  jint background) {
    return engineOf(engine)->renderBuffer(AHardwareBuffer_fromHardwareBuffer(env, buffer), timeMs, (uint32_t)background);
}

JNIEXPORT void JNICALL JNI_FN(nativeReleaseBuffers)(JNIEnv*, jclass, jlong engine) {
    engineOf(engine)->releaseBuffers();
}

JNIEXPORT jint JNICALL JNI_FN(nativeRenderScene)(JNIEnv* env, jclass, jlong engine, jlong target, jdouble timeMs,
                                                 jfloat scale, jfloat dx, jfloat dy, jint background,
                                                 jlong selectedId, jint handles, jboolean pixels, jint pixelDivisor,
                                                 jfloat gridSpacing, jint solo, jlongArray soloIds,
                                                 jfloatArray guides, jfloat refPx) {
    am::PreviewOptions view;
    view.pixels = pixels;
    view.pixelDivisor = pixelDivisor;
    view.refPx = refPx;
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

JNIEXPORT void JNICALL JNI_FN(nativeSetAccurateBlur)(JNIEnv*, jclass, jlong engine, jboolean on) {
    engineOf(engine)->setAccurateBlur(on);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeFastCaptionCpu)(JNIEnv*, jclass) {
    const unsigned long need = HWCAP_ASIMDDP | HWCAP_ASIMDHP | HWCAP_FPHP | HWCAP_ATOMICS | HWCAP_ASIMDRDM | HWCAP_CRC32;
    return (getauxval(AT_HWCAP) & need) == need;
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

JNIEXPORT jfloatArray JNICALL JNI_FN(nativeLayerMatrix)(JNIEnv* env, jclass, jlong engine, jlong layerId, jdouble timeMs) {
    float m[18];
    if (!engineOf(engine)->layerMatrix(layerId, timeMs, m)) return nullptr;
    jfloatArray out = env->NewFloatArray(18);
    if (!out) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetFloatArrayRegion(out, 0, 18, m);
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

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetModel)(JNIEnv* env, jclass, jbyteArray uri, jfloatArray positions, jfloatArray normals,
                                                 jfloatArray uvs, jintArray colors, jintArray triangles, jintArray slots, jboolean twoSided) {
    std::string key((size_t)env->GetArrayLength(uri), '\0');
    env->GetByteArrayRegion(uri, 0, (jsize)key.size(), reinterpret_cast<jbyte*>(key.data()));
    if (!positions) {
        am::SetModel(key, nullptr);
        return true;
    }
    auto floats = [&](jfloatArray a) {
        std::vector<float> v((size_t)env->GetArrayLength(a));
        env->GetFloatArrayRegion(a, 0, (jsize)v.size(), v.data());
        return v;
    };
    auto ints = [&](jintArray a) {
        std::vector<int> v((size_t)env->GetArrayLength(a));
        env->GetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
        return v;
    };
    auto model = std::make_shared<am::Model>();
    model->positions = floats(positions);
    model->normals = floats(normals);
    model->uvs = floats(uvs);
    const std::vector<int> argb = ints(colors);
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

}
