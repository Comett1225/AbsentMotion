#include <jni.h>
#include <cstdint>
#include <exception>
#include <new>
#include "tracker/tracker.h"

namespace {

void throwJava(JNIEnv* env, const char* type, const char* message) {
    env->ThrowNew(env->FindClass(type), message);
}

struct Handle {
    motion::Tracker tracker;
    std::size_t count;
};

Handle* handleOf(jlong value) { return reinterpret_cast<Handle*>(static_cast<intptr_t>(value)); }

}

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_media_MotionTracker_nativeCreate(JNIEnv* env, jclass, jdoubleArray points, jint patchRadius, jint searchRadius) {
    const jsize length = points ? env->GetArrayLength(points) : 0;
    if (length < 2 || length > 8 || length % 2 != 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "The tracker needs one to four points");
        return 0;
    }
    jdouble xy[8];
    env->GetDoubleArrayRegion(points, 0, length, xy);
    if (env->ExceptionCheck()) return 0;
    motion::Point start[4];
    for (jsize i = 0; i < length / 2; i++) start[i] = {xy[2 * i], xy[2 * i + 1]};
    motion::Options options;
    options.patch_radius = patchRadius;
    options.search_radius = searchRadius;
    try {
        return reinterpret_cast<intptr_t>(new Handle{motion::Tracker(start, static_cast<std::size_t>(length / 2), options), static_cast<std::size_t>(length / 2)});
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to start tracking");
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_absentmotion_media_MotionTracker_nativeTrack(JNIEnv* env, jclass, jlong value, jbyteArray luma, jint width, jint height, jdoubleArray out) {
    Handle* handle = handleOf(value);
    if (!handle || !luma || !out || width < 1 || height < 1 || width > 16384 || height > 16384 ||
        env->GetArrayLength(luma) < static_cast<int64_t>(width) * height ||
        env->GetArrayLength(out) < static_cast<jsize>(handle->count * 3)) {
        throwJava(env, "java/lang/IllegalArgumentException", "The tracker needs width x height luma bytes and a result array");
        return JNI_FALSE;
    }
    jbyte* pixels = env->GetByteArrayElements(luma, nullptr);
    if (!pixels) return JNI_FALSE;
    motion::Result result;
    try {
        result = handle->tracker.track({reinterpret_cast<const std::uint8_t*>(pixels), width, height, width});
    } catch (const std::bad_alloc&) {
        env->ReleaseByteArrayElements(luma, pixels, JNI_ABORT);
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to track this video");
        return JNI_FALSE;
    } catch (const std::exception& e) {
        env->ReleaseByteArrayElements(luma, pixels, JNI_ABORT);
        throwJava(env, "java/lang/IllegalStateException", e.what());
        return JNI_FALSE;
    }
    env->ReleaseByteArrayElements(luma, pixels, JNI_ABORT);
    jdouble values[12];
    for (std::size_t i = 0; i < handle->count; i++) {
        values[3 * i] = result.points[i].position.x;
        values[3 * i + 1] = result.points[i].position.y;
        values[3 * i + 2] = result.points[i].confidence;
    }
    env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(handle->count * 3), values);
    return result.lost ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_media_MotionTracker_nativeRelease(JNIEnv*, jclass, jlong value) {
    delete handleOf(value);
}
