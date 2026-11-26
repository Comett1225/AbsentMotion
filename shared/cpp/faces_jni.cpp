#include <jni.h>
#include <cstdint>
#include <exception>
#include <new>
#include <vector>
#include "faces/yunet.h"

namespace {

void throwJava(JNIEnv* env, const char* type, const char* message) {
    env->ThrowNew(env->FindClass(type), message);
}

faces::Detector* detectorOf(jlong value) { return reinterpret_cast<faces::Detector*>(static_cast<intptr_t>(value)); }

}

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_media_FaceDetector_nativeCreate(JNIEnv* env, jclass, jbyteArray model) {
    const jsize size = model ? env->GetArrayLength(model) : 0;
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    if (size > 0) env->GetByteArrayRegion(model, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return 0;
    try {
        return reinterpret_cast<intptr_t>(new faces::Detector(bytes.data(), bytes.size()));
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to load the face model");
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", e.what());
    }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_absentmotion_media_FaceDetector_nativeDetect(JNIEnv* env, jclass, jlong handle, jbyteArray rgb, jint width, jint height, jfloat threshold) {
    faces::Detector* detector = detectorOf(handle);
    if (!detector || !rgb || width < 1 || height < 1 || env->GetArrayLength(rgb) < static_cast<int64_t>(width) * height * 3) {
        throwJava(env, "java/lang/IllegalArgumentException", "Face detection needs width x height RGB pixels");
        return nullptr;
    }
    jbyte* pixels = env->GetByteArrayElements(rgb, nullptr);
    if (!pixels) return nullptr;
    std::vector<faces::Face> found;
    try {
        found = detector->detect(reinterpret_cast<const std::uint8_t*>(pixels), width, height, threshold);
    } catch (const std::bad_alloc&) {
        env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to look for faces");
        return nullptr;
    } catch (const std::exception& e) {
        env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);
        throwJava(env, "java/lang/IllegalStateException", e.what());
        return nullptr;
    }
    env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);
    std::vector<jfloat> values;
    for (const faces::Face& f : found) values.insert(values.end(), {f.x, f.y, f.width, f.height, f.score});
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (out && !values.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_media_FaceDetector_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete detectorOf(handle);
}
