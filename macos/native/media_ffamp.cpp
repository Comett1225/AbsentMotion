#include "video.h"
#include "absent/capture.h"
#include "absent/probe.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

namespace {

struct Pcm {
    std::unique_ptr<absent::PcmReader> reader;
    int64_t at = 0;
    std::vector<float> samples;
};

struct Frames {
    std::unique_ptr<absent::FrameReader> reader;
    std::vector<uint8_t> pixels;
};

double OnFileClock(const absent::Track& t, int64_t stamp) {
    return double(int64_t(uint64_t(stamp) - uint64_t(t.mediaStart) + uint64_t(t.delay))) / t.timescale;
}

double TrackStart(const absent::Track& t) {
    if (t.order.empty()) return double(t.delay) / t.timescale;
    return OnFileClock(t, std::max(t.samples[t.order.front()].pts, t.mediaStart));
}

double FileStart(const absent::Reader& reader) {
    double begin = 1e300;
    for (const absent::Track& t : reader.tracks()) {
        if (t.timescale && !t.samples.empty() && t.kind != absent::Kind::Other) begin = std::min(begin, TrackStart(t));
    }
    return begin == 1e300 ? 0 : begin;
}

template <class R, class F> R Guarded(R fallback, F&& work) {
    try {
        return work();
    } catch (...) {
        return fallback;
    }
}

Pcm* pcm(jlong h) { return reinterpret_cast<Pcm*>(h); }
Frames* frames(jlong h) { return reinterpret_cast<Frames*>(h); }

}

#define JNI(name) Java_app_absentmotion_media_FfampNative_##name
extern "C" {

JNIEXPORT jstring JNICALL JNI(probe)(JNIEnv* e, jobject, jstring path) {
    const std::string file = DesktopString(e, path);
    const std::string json = Guarded(std::string(), [&] { return absent::probeJson(file, false); });
    return json.empty() ? nullptr : e->NewStringUTF(json.c_str());
}

JNIEXPORT jdoubleArray JNICALL JNI(packets)(JNIEnv* e, jobject, jstring path, jint stream) {
    const std::string file = DesktopString(e, path);
    std::vector<double> values;
    const bool read = Guarded(false, [&] {
        auto reader = absent::Reader::open(file);
        if (!reader || stream < 0 || (size_t)stream >= reader->tracks().size()) return false;
        const absent::Track& t = reader->tracks()[(size_t)stream];
        if (!t.timescale) return true;
        values.reserve(t.samples.size() * 3);
        for (const absent::Sample& s : t.samples) {
            values.push_back(OnFileClock(t, s.pts));
            values.push_back((double)s.size);
            values.push_back(s.sync ? 1.0 : 0.0);
        }
        return true;
    });
    if (!read) return nullptr;
    auto result = e->NewDoubleArray((jsize)values.size());
    if (result) e->SetDoubleArrayRegion(result, 0, (jsize)values.size(), values.data());
    return result;
}

JNIEXPORT jlong JNICALL JNI(pcmOpen)(JNIEnv* e, jobject, jstring path, jint rate, jint channels, jdouble seconds) {
    const std::string file = DesktopString(e, path);
    return Guarded<jlong>(0, [&]() -> jlong {
        auto source = absent::Reader::open(file);
        const absent::Track* track = source ? source->first(absent::Kind::Audio) : nullptr;
        if (!track || !track->timescale) return 0;
        const double lead = TrackStart(*track) - FileStart(*source);
        source.reset();
        auto made = std::make_unique<Pcm>();
        made->reader = absent::PcmReader::open(file, rate, channels);
        if (!made->reader) return 0;
        made->at = (int64_t)std::llround(seconds * double(rate)) - (int64_t)std::llround(lead * double(rate));
        return reinterpret_cast<jlong>(made.release());
    });
}

JNIEXPORT jint JNICALL JNI(pcmRead)(JNIEnv* e, jobject, jlong h, jbyteArray out, jint count, jboolean whole) {
    Pcm& p = *pcm(h);
    const int channels = p.reader->channels();
    const int64_t left = p.reader->length() - p.at;
    if (left <= 0 || count <= 0) return 0;
    const size_t got = (size_t)std::min<int64_t>(count, left);
    p.samples.resize(got * (size_t)channels);
    if (!Guarded(false, [&] { return p.reader->read(p.at, got, p.samples.data()); })) return -1;
    p.at += (int64_t)got;
    if (whole == JNI_TRUE) {
        std::vector<int16_t> shorts(p.samples.size());
        for (size_t i = 0; i < shorts.size(); ++i) shorts[i] = (int16_t)std::clamp(std::lrintf(p.samples[i] * 32768.0f), -32768L, 32767L);
        e->SetByteArrayRegion(out, 0, (jsize)(shorts.size() * 2), reinterpret_cast<const jbyte*>(shorts.data()));
    } else {
        e->SetByteArrayRegion(out, 0, (jsize)(p.samples.size() * 4), reinterpret_cast<const jbyte*>(p.samples.data()));
    }
    return (jint)got;
}

JNIEXPORT void JNICALL JNI(pcmClose)(JNIEnv*, jobject, jlong h) { delete pcm(h); }

JNIEXPORT jlong JNICALL JNI(framesOpen)(JNIEnv* e, jobject, jstring path, jint width, jint height, jint layout, jboolean rotate) {
    const std::string file = DesktopString(e, path);
    return Guarded<jlong>(0, [&]() -> jlong {
        absent::FrameReaderSpec spec;
        spec.width = width;
        spec.height = height;
        spec.layout = layout == 1 ? absent::PixelLayout::Rgb24 : layout == 2 ? absent::PixelLayout::I420 : layout == 3 ? absent::PixelLayout::Bgra : absent::PixelLayout::Rgba;
        spec.rotate = rotate == JNI_TRUE;
        auto made = std::make_unique<Frames>();
        made->reader = absent::FrameReader::open(file, spec);
        return made->reader ? reinterpret_cast<jlong>(made.release()) : 0;
    });
}

JNIEXPORT jdoubleArray JNICALL JNI(framesInfo)(JNIEnv* e, jobject, jlong h) {
    const absent::FrameReader& r = *frames(h)->reader;
    const double values[8] = {(double)r.width(), (double)r.height(), (double)r.sourceWidth(), (double)r.sourceHeight(), (double)r.rotation(), (double)r.frames(), r.rate(), r.length()};
    auto result = e->NewDoubleArray(8);
    if (result) e->SetDoubleArrayRegion(result, 0, 8, values);
    return result;
}

JNIEXPORT jint JNICALL JNI(framesIndexAt)(JNIEnv*, jobject, jlong h, jdouble seconds) { return (jint)frames(h)->reader->indexAt(seconds); }

JNIEXPORT jdouble JNICALL JNI(framesSeconds)(JNIEnv*, jobject, jlong h, jint index) { return frames(h)->reader->seconds((uint32_t)index); }

JNIEXPORT jboolean JNICALL JNI(framesRead)(JNIEnv* e, jobject, jlong h, jint index, jbyteArray out) {
    Frames& f = *frames(h);
    if (index < 0 || (uint32_t)index >= f.reader->frames() || (size_t)e->GetArrayLength(out) < f.reader->frameBytes()) return JNI_FALSE;
    if (!Guarded(false, [&] { return f.reader->read((uint32_t)index, f.pixels); }) || f.pixels.size() != f.reader->frameBytes()) return JNI_FALSE;
    e->SetByteArrayRegion(out, 0, (jsize)f.pixels.size(), reinterpret_cast<const jbyte*>(f.pixels.data()));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(framesClose)(JNIEnv*, jobject, jlong h) { delete frames(h); }

}
