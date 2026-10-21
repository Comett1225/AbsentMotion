#include <jni.h>

#include <vector>

#include "lame.h"

#define JNI_FN(name) Java_app_absentmotion_export_Mp3Encoder_##name

namespace {
lame_global_flags* lameOf(jlong handle) { return reinterpret_cast<lame_global_flags*>(handle); }

jbyteArray bytes(JNIEnv* env, const std::vector<unsigned char>& data, int size) {
    if (size < 0) return nullptr;
    jbyteArray out = env->NewByteArray(size);
    if (out && size > 0) env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte*>(data.data()));
    return out;
}
}

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv*, jclass, jint rate, jint channels, jint kbps) {
    lame_global_flags* gf = lame_init();
    if (!gf) return 0;
    lame_set_in_samplerate(gf, rate);
    lame_set_out_samplerate(gf, rate);
    lame_set_num_channels(gf, channels);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(gf, vbr_off);
    lame_set_brate(gf, kbps);
    lame_set_quality(gf, 2);
    lame_set_bWriteVbrTag(gf, 1);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        return 0;
    }
    return reinterpret_cast<jlong>(gf);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncode)(JNIEnv* env, jclass, jlong handle, jshortArray pcm, jint frames) {
    lame_global_flags* gf = lameOf(handle);
    const int channels = lame_get_num_channels(gf);
    if (!pcm || frames < 0 || (jlong)frames * channels > env->GetArrayLength(pcm)) return nullptr;
    std::vector<unsigned char> out((size_t)frames * 5 / 4 + 7200);
    jshort* samples = env->GetShortArrayElements(pcm, nullptr);
    if (!samples) return nullptr;
    const int n = channels == 1
            ? lame_encode_buffer(gf, samples, samples, frames, out.data(), (int)out.size())
            : lame_encode_buffer_interleaved(gf, samples, frames, out.data(), (int)out.size());
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);
    return bytes(env, out, n);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeFlush)(JNIEnv* env, jclass, jlong handle) {
    std::vector<unsigned char> out(7200);
    return bytes(env, out, lame_encode_flush(lameOf(handle), out.data(), (int)out.size()));
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeTag)(JNIEnv* env, jclass, jlong handle) {
    std::vector<unsigned char> out(2880);
    const size_t n = lame_get_lametag_frame(lameOf(handle), out.data(), out.size());
    return bytes(env, out, n > out.size() ? -1 : (int)n);
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*, jclass, jlong handle) {
    lame_close(lameOf(handle));
}

}
