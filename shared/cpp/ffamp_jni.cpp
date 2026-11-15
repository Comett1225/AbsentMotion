#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include "absent/android.h"
#include "absent/audio.h"

using namespace absent;

namespace {

thread_local std::string lastError;

struct Opened {
    std::unique_ptr<Reader> reader;
};

struct Video {
    Opened* file;
    const Track* track;
    std::unique_ptr<android::SoftVideo> soft;
    std::vector<uint8_t> packet;
    std::deque<android::SoftPicture> ready;
    std::vector<int64_t> later;
};

struct Audio {
    std::unique_ptr<AudioDecoder> decoder;
};

Opened* opened(jlong h) { return reinterpret_cast<Opened*>(h); }

const Track* trackOf(jlong h, jint t) {
    auto* f = opened(h);
    if (!f || t < 0 || size_t(t) >= f->reader->tracks().size()) return nullptr;
    return &f->reader->tracks()[size_t(t)];
}

int64_t micros(const Track& t, int64_t ticks) { return t.timescale ? int64_t(std::llround(double(ticks - t.mediaStart) * 1e6 / t.timescale)) : 0; }

}

extern "C" {

JNIEXPORT jlong JNICALL Java_app_absentmotion_ffamp_FfampNative_open(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    std::string error;
    auto reader = Reader::open(chars, &error);
    env->ReleaseStringUTFChars(path, chars);
    if (!reader) { lastError = error.empty() ? "cannot open" : error; return 0; }
    return reinterpret_cast<jlong>(new Opened{std::move(reader)});
}

JNIEXPORT jstring JNICALL Java_app_absentmotion_ffamp_FfampNative_error(JNIEnv* env, jobject) { return env->NewStringUTF(lastError.c_str()); }

JNIEXPORT void JNICALL Java_app_absentmotion_ffamp_FfampNative_close(JNIEnv*, jobject, jlong h) { delete opened(h); }

JNIEXPORT jstring JNICALL Java_app_absentmotion_ffamp_FfampNative_container(JNIEnv* env, jobject, jlong h) { return env->NewStringUTF(opened(h)->reader->format()); }

JNIEXPORT jint JNICALL Java_app_absentmotion_ffamp_FfampNative_tracks(JNIEnv*, jobject, jlong h) { return jint(opened(h)->reader->tracks().size()); }

JNIEXPORT jstring JNICALL Java_app_absentmotion_ffamp_FfampNative_mime(JNIEnv* env, jobject, jlong h, jint t) {
    const Track* track = trackOf(h, t);
    return env->NewStringUTF(track ? android::mime(*track).c_str() : "");
}

JNIEXPORT jlongArray JNICALL Java_app_absentmotion_ffamp_FfampNative_track(JNIEnv* env, jobject, jlong h, jint t) {
    const Track* k = trackOf(h, t);
    if (!k) return nullptr;
    uint32_t largest = 0;
    for (const Sample& s : k->samples) largest = std::max(largest, s.size);
    const jlong values[] = {jlong(k->kind == Kind::Video ? 1 : k->kind == Kind::Audio ? 2 : 0), jlong(k->width), jlong(k->height), jlong(k->rotation),
        jlong(k->sampleRate), jlong(k->channels), jlong(std::llround(k->seconds() * 1e6)), jlong(k->samples.size()), jlong(largest),
        jlong(k->color.known), jlong(k->color.primaries), jlong(k->color.transfer), jlong(k->color.matrix), jlong(k->color.full), jlong(k->alpha),
        jlong(k->codec), jlong(k->bits), jlong(android::codecProfile(*k)), jlong(android::codecLevel(*k))};
    const jsize n = jsize(sizeof(values) / sizeof(values[0]));
    jlongArray out = env->NewLongArray(n);
    env->SetLongArrayRegion(out, 0, n, values);
    return out;
}

JNIEXPORT jobjectArray JNICALL Java_app_absentmotion_ffamp_FfampNative_csd(JNIEnv* env, jobject, jlong h, jint t) {
    const Track* k = trackOf(h, t);
    auto parts = k ? android::codecConfig(*k) : std::vector<std::vector<uint8_t>>();
    jobjectArray out = env->NewObjectArray(jsize(parts.size()), env->FindClass("[B"), nullptr);
    for (size_t i = 0; i < parts.size(); ++i) {
        jbyteArray bytes = env->NewByteArray(jsize(parts[i].size()));
        env->SetByteArrayRegion(bytes, 0, jsize(parts[i].size()), reinterpret_cast<const jbyte*>(parts[i].data()));
        env->SetObjectArrayElement(out, jsize(i), bytes);
        env->DeleteLocalRef(bytes);
    }
    return out;
}

JNIEXPORT void JNICALL Java_app_absentmotion_ffamp_FfampNative_table(JNIEnv* env, jobject, jlong h, jint t, jlongArray pts, jintArray sizes, jbooleanArray syncs) {
    const Track* k = trackOf(h, t);
    if (!k) return;
    const size_t n = k->samples.size();
    std::vector<jlong> times(n);
    std::vector<jint> bytes(n);
    std::vector<jboolean> keys(n);
    for (size_t i = 0; i < n; ++i) {
        times[i] = micros(*k, k->samples[i].pts);
        bytes[i] = jint(k->samples[i].size);
        keys[i] = k->samples[i].sync;
    }
    env->SetLongArrayRegion(pts, 0, jsize(n), times.data());
    env->SetIntArrayRegion(sizes, 0, jsize(n), bytes.data());
    env->SetBooleanArrayRegion(syncs, 0, jsize(n), keys.data());
}

JNIEXPORT jint JNICALL Java_app_absentmotion_ffamp_FfampNative_read(JNIEnv* env, jobject, jlong h, jint t, jint index, jobject buffer, jint offset) {
    const Track* k = trackOf(h, t);
    if (!k || index < 0 || size_t(index) >= k->samples.size()) return -1;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer) - offset;
    const uint32_t size = k->samples[size_t(index)].size;
    if (!base || capacity < jlong(size)) { lastError = "buffer too small"; return -1; }
    uint8_t* out = base + offset;
    if (!opened(h)->reader->read(*k, uint32_t(index), out)) { lastError = "cannot read the sample"; return -1; }
    const int lengthSize = android::nalLengthSize(*k);
    if (lengthSize == 4) return jint(android::annexB(out, size, 4, out, size_t(capacity)) ? size : 0);
    if (lengthSize > 0) {
        std::vector<uint8_t> copy(out, out + size);
        return jint(android::annexB(copy.data(), copy.size(), lengthSize, out, size_t(capacity)));
    }
    return jint(size);
}

JNIEXPORT jlong JNICALL Java_app_absentmotion_ffamp_FfampNative_videoOpen(JNIEnv*, jobject, jlong h, jint t) {
    const Track* k = trackOf(h, t);
    if (!k) return 0;
    std::string error;
    auto soft = android::SoftVideo::open(*k, &error);
    if (!soft) { lastError = error; return 0; }
    std::vector<int64_t> later(k->samples.size() + 1, INT64_MAX);
    for (size_t i = k->samples.size(); i-- > 0;) later[i] = std::min(later[i + 1], micros(*k, k->samples[i].pts));
    return reinterpret_cast<jlong>(new Video{opened(h), k, std::move(soft), {}, {}, std::move(later)});
}

JNIEXPORT jstring JNICALL Java_app_absentmotion_ffamp_FfampNative_videoName(JNIEnv* env, jobject, jlong v) { return env->NewStringUTF(reinterpret_cast<Video*>(v)->soft->name()); }

JNIEXPORT jint JNICALL Java_app_absentmotion_ffamp_FfampNative_videoFeed(JNIEnv*, jobject, jlong v, jint index) {
    auto* d = reinterpret_cast<Video*>(v);
    std::vector<android::SoftPicture> out;
    if (index < 0) d->soft->drain(out);
    else {
        if (size_t(index) >= d->track->samples.size()) return -1;
        const Sample& s = d->track->samples[size_t(index)];
        d->packet.resize(s.size);
        if (!d->file->reader->read(*d->track, uint32_t(index), d->packet.data())) return -1;
        d->soft->decode(d->packet.data(), d->packet.size(), micros(*d->track, s.pts), out, d->later[size_t(index) + 1]);
    }
    for (auto& p : out) d->ready.push_back(std::move(p));
    return jint(d->ready.size());
}

JNIEXPORT jlong JNICALL Java_app_absentmotion_ffamp_FfampNative_videoTake(JNIEnv* env, jobject, jlong v, jobject buffer, jintArray shape) {
    auto* d = reinterpret_cast<Video*>(v);
    if (d->ready.empty()) return INT64_MIN;
    android::SoftPicture p = std::move(d->ready.front());
    d->ready.pop_front();
    const jint dims[2] = {p.width, p.height};
    env->SetIntArrayRegion(shape, 0, 2, dims);
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base || env->GetDirectBufferCapacity(buffer) < jlong(p.i420.size())) return INT64_MIN + 1;
    memcpy(base, p.i420.data(), p.i420.size());
    return p.stamp;
}

JNIEXPORT void JNICALL Java_app_absentmotion_ffamp_FfampNative_videoReset(JNIEnv*, jobject, jlong v) {
    auto* d = reinterpret_cast<Video*>(v);
    d->soft->reset();
    d->ready.clear();
}

JNIEXPORT void JNICALL Java_app_absentmotion_ffamp_FfampNative_videoClose(JNIEnv*, jobject, jlong v) { delete reinterpret_cast<Video*>(v); }

JNIEXPORT jlong JNICALL Java_app_absentmotion_ffamp_FfampNative_audioOpen(JNIEnv*, jobject, jlong h, jint t) {
    const Track* k = trackOf(h, t);
    if (!k) return 0;
    std::string error;
    auto decoder = AudioDecoder::open(*opened(h)->reader, *k, &error);
    if (!decoder) { lastError = error; return 0; }
    return reinterpret_cast<jlong>(new Audio{std::move(decoder)});
}

JNIEXPORT jlongArray JNICALL Java_app_absentmotion_ffamp_FfampNative_audioShape(JNIEnv* env, jobject, jlong a) {
    auto& d = *reinterpret_cast<Audio*>(a)->decoder;
    const jlong values[3] = {d.sampleRate(), d.channels(), d.length()};
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, values);
    return out;
}

JNIEXPORT jboolean JNICALL Java_app_absentmotion_ffamp_FfampNative_audioRead(JNIEnv* env, jobject, jlong a, jlong start, jint frames, jfloatArray out) {
    auto& d = *reinterpret_cast<Audio*>(a)->decoder;
    std::vector<float> pcm(size_t(frames) * size_t(d.channels()));
    if (!d.read(start, size_t(frames), pcm.data())) return JNI_FALSE;
    env->SetFloatArrayRegion(out, 0, jsize(pcm.size()), pcm.data());
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_app_absentmotion_ffamp_FfampNative_picture(JNIEnv* env, jobject, jobject yPlane, jobject uPlane, jobject vPlane, jint yRow, jint uvRow, jint width, jint height, jboolean sd) {
    auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yPlane));
    auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uPlane));
    auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vPlane));
    if (!y || !u || !v || width <= 0 || height <= 0) return 0;
    AHardwareBuffer_Desc desc{};
    desc.width = uint32_t(width);
    desc.height = uint32_t(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    AHardwareBuffer* buffer = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0) return 0;
    AHardwareBuffer_describe(buffer, &desc);
    void* pixels = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pixels) != 0) {
        AHardwareBuffer_release(buffer);
        return 0;
    }
    const int rv = sd ? 409 : 459, gu = sd ? 100 : 55, gv = sd ? 208 : 136, bu = sd ? 516 : 541;
    for (int row = 0; row < height; ++row) {
        auto* out = static_cast<uint8_t*>(pixels) + size_t(row) * desc.stride * 4;
        const uint8_t* ys = y + size_t(row) * size_t(yRow);
        const uint8_t* us = u + size_t(row / 2) * size_t(uvRow);
        const uint8_t* vs = v + size_t(row / 2) * size_t(uvRow);
        for (int x = 0; x < width; ++x) {
            const int l = 298 * (ys[x] - 16), cb = us[x / 2] - 128, cr = vs[x / 2] - 128;
            out[4 * x] = uint8_t(std::clamp((l + rv * cr + 128) >> 8, 0, 255));
            out[4 * x + 1] = uint8_t(std::clamp((l - gu * cb - gv * cr + 128) >> 8, 0, 255));
            out[4 * x + 2] = uint8_t(std::clamp((l + bu * cb + 128) >> 8, 0, 255));
            out[4 * x + 3] = 255;
        }
    }
    AHardwareBuffer_unlock(buffer, nullptr);
    return reinterpret_cast<jlong>(buffer);
}

JNIEXPORT jobject JNICALL Java_app_absentmotion_ffamp_FfampNative_pictureBuffer(JNIEnv* env, jobject, jlong picture) {
    return AHardwareBuffer_toHardwareBuffer(env, reinterpret_cast<AHardwareBuffer*>(picture));
}

JNIEXPORT void JNICALL Java_app_absentmotion_ffamp_FfampNative_pictureRelease(JNIEnv*, jobject, jlong picture) { AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(picture)); }

JNIEXPORT void JNICALL Java_app_absentmotion_ffamp_FfampNative_audioClose(JNIEnv*, jobject, jlong a) { delete reinterpret_cast<Audio*>(a); }

}
