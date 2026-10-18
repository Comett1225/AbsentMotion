#include <jni.h>
#include <cstdlib>
#include <cstdint>
#include <mutex>
#include "rnnoise.h"

static std::mutex noiseMutex;

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_ai_NoiseReduction_nativeCreate(JNIEnv* env, jobject) {
    auto* state = static_cast<DenoiseState*>(std::malloc(rnnoise_get_size()));
    if (!state) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Cannot allocate RNNoise");
        return 0;
    }
    rnnoise_init(state, nullptr);
    return reinterpret_cast<intptr_t>(state);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_absentmotion_ai_NoiseReduction_nativeProcess(JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
    if (!handle || !pcm || env->GetArrayLength(pcm) != 480) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "RNNoise needs 480 mono samples");
        return nullptr;
    }
    float input[480], output[480];
    env->GetFloatArrayRegion(pcm, 0, 480, input);
    if (env->ExceptionCheck()) return nullptr;
    for (float& sample : input) sample *= 32768.0f;
    {
        std::lock_guard<std::mutex> lock(noiseMutex);
        rnnoise_process_frame(reinterpret_cast<DenoiseState*>(static_cast<intptr_t>(handle)), output, input);
    }
    for (float& sample : output) sample /= 32768.0f;
    auto result = env->NewFloatArray(480);
    if (result) env->SetFloatArrayRegion(result, 0, 480, output);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_ai_NoiseReduction_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    if (handle) rnnoise_destroy(reinterpret_cast<DenoiseState*>(static_cast<intptr_t>(handle)));
}
