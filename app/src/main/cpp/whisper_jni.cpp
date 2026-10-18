#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"

namespace {

struct Job {
    whisper_context *ctx;
    std::atomic<bool> cancel{false};
};

struct Listener {
    JNIEnv *env;
    jobject target;
    jmethodID accept;
};

Job *job(jlong handle) { return reinterpret_cast<Job *>(handle); }

}

extern "C" JNIEXPORT jlong JNICALL
Java_app_absentmotion_ai_Whisper_nativeOpen(JNIEnv *env, jobject, jstring path) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
#ifdef __APPLE__
    params.use_gpu = true;
#else
    params.use_gpu = false;
#endif
    whisper_context *ctx = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    return ctx ? reinterpret_cast<jlong>(new Job{ctx}) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_ai_Whisper_nativeCancel(JNIEnv *, jobject, jlong handle) {
    job(handle)->cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_app_absentmotion_ai_Whisper_nativeClose(JNIEnv *, jobject, jlong handle) {
    whisper_free(job(handle)->ctx);
    delete job(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_absentmotion_ai_Whisper_nativeRun(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jboolean english,
                                              jobject listener) {
    Job *j = job(handle);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::clamp(static_cast<int>(std::thread::hardware_concurrency()), 1, 8);
    p.language = english ? "en" : "auto";
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    p.suppress_nst = true;
    p.n_max_text_ctx = 0;
    Listener progress{env, listener, env->GetMethodID(env->GetObjectClass(listener), "accept", "(I)V")};
    p.progress_callback = [](whisper_context *, whisper_state *, int percent, void *data) {
        auto *l = static_cast<Listener *>(data);
        l->env->CallVoidMethod(l->target, l->accept, percent);
    };
    p.progress_callback_user_data = &progress;
    p.abort_callback = [](void *data) { return static_cast<Job *>(data)->cancel.load(); };
    p.abort_callback_user_data = j;

    std::vector<float> samples(env->GetArrayLength(pcm));
    env->GetFloatArrayRegion(pcm, 0, static_cast<jsize>(samples.size()), samples.data());
    if (whisper_full(j->ctx, p, samples.data(), static_cast<int>(samples.size())) != 0 || j->cancel) return nullptr;

    std::string out;
    for (int i = 0, n = whisper_full_n_segments(j->ctx); i < n; i++) {
        out += std::to_string(whisper_full_get_segment_t0(j->ctx, i) * 10) + '\t' +
               std::to_string(whisper_full_get_segment_t1(j->ctx, i) * 10) + '\t' +
               whisper_full_get_segment_text(j->ctx, i) + '\n';
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}
