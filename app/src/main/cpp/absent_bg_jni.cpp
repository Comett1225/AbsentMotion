#include <jni.h>
#include <cstdint>
#include <exception>
#include <new>
#include <string>
#include "absent_bg/absent_bg.h"

static void throwJava(JNIEnv* env, const char* type, const char* message) {
    env->ThrowNew(env->FindClass(type), message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_ai_BackgroundRemoval_nativeLoad(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string file(chars);
    env->ReleaseStringUTFChars(path, chars);
    try {
        return reinterpret_cast<intptr_t>(new absent::Model(file));
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to load the background removal model");
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", (std::string("Cannot load the background removal model: ") + e.what()).c_str());
    }
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_absentmotion_ai_BackgroundRemoval_nativeMatte(JNIEnv* env, jobject, jlong handle, jbyteArray rgb, jint width, jint height) {
    if (!handle || !rgb || width < 1 || height < 1 || env->GetArrayLength(rgb) != int64_t(width) * height * 3) {
        throwJava(env, "java/lang/IllegalArgumentException", "Background removal needs width x height RGB pixels");
        return nullptr;
    }
    jbyte* pixels = env->GetByteArrayElements(rgb, nullptr);
    if (!pixels) return nullptr;
    std::vector<uint8_t> alpha;
    try {
        alpha = reinterpret_cast<absent::Model*>(static_cast<intptr_t>(handle))->matte(reinterpret_cast<const uint8_t*>(pixels), width, height);
    } catch (const std::bad_alloc&) {
        env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);
        throwJava(env, "java/lang/OutOfMemoryError", "Not enough memory to process this image");
        return nullptr;
    } catch (const std::exception& e) {
        env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);
        throwJava(env, "java/lang/IllegalStateException", e.what());
        return nullptr;
    }
    env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);
    auto result = env->NewByteArray(static_cast<jsize>(alpha.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(alpha.size()), reinterpret_cast<const jbyte*>(alpha.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_ai_BackgroundRemoval_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<absent::Model*>(static_cast<intptr_t>(handle));
}
