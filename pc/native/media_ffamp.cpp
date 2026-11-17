#include "video.h"
#include "ffamp_clock.h"
#include "absent/capture.h"
#include "absent/encoders.h"
#include "absent/tools.h"
#include "absent/probe.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace absent {
FILE* openFile(const std::string& path, const char* mode);
int64_t fileBytes(const std::string& path);
}

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

template <class R, class F> R Guarded(R fallback, F&& work) {
    try {
        return work();
    } catch (...) {
        return fallback;
    }
}

struct Stepper {
    JNIEnv* env;
    jobject target;
    jmethodID step = nullptr;
    std::thread::id home = std::this_thread::get_id();
    bool alive = true;

    Stepper(JNIEnv* e, jobject progress) : env(e), target(progress) {
        if (target) step = env->GetMethodID(env->GetObjectClass(target), "step", "(D)Z");
    }

    absent::Progress progress() {
        return [this](double done) {
            if (!step || std::this_thread::get_id() != home) return alive;
            alive = env->CallBooleanMethod(target, step, (jdouble)done) == JNI_TRUE && !env->ExceptionCheck();
            return alive;
        };
    }
};

jstring Told(JNIEnv* e, bool ok, const std::string& error) {
    if (ok) return nullptr;
    return e->NewStringUTF(error.empty() ? "failed" : error.c_str());
}

uint32_t CodecOf(const std::string& name) {
    if (name == "h264") return absent::fourcc("avc1");
    if (name == "hevc") return absent::fourcc("hvc1");
    if (name == "av1") return absent::fourcc("av01");
    if (name == "vp9") return absent::fourcc("vp09");
    if (name == "apv") return absent::fourcc("apv1");
    return 0;
}

absent::EncodeSpec SpecOf(const std::string& codec, int width, int height, int fps, int bitrate, int gop) {
    absent::EncodeSpec spec;
    spec.width = width;
    spec.height = height;
    spec.timescale = (uint32_t)std::max(fps, 1);
    spec.fps = std::max(fps, 1);
    spec.bitrate = bitrate;
    spec.keyInterval = std::max(gop, 1);
    spec.codec = CodecOf(codec);
    spec.reorder = spec.codec != absent::fourcc("avc1");
    return spec;
}

struct Export {
    std::string path;
    std::unique_ptr<absent::VideoEncoder> encoder;
    std::unique_ptr<absent::Writer> writer;
    std::unique_ptr<absent::AacEncoder> aac;
    std::unique_ptr<absent::OpusEncoder> opus;
    FILE* sound = nullptr;
    int videoTrack = -1, audioTrack = -1;
    int fps = 30, rate = 48000, channels = 2, step = 1024;
    int64_t frames = 0, soundFrames = 0, soundDone = 0, soundPackets = 0;
    bool configured = false, done = false;
    std::string error;
    std::vector<int16_t> pcm, mono;

    ~Export() {
        if (sound) fclose(sound);
        writer.reset();
        if (!done) remove(path.c_str());
    }

    bool fail(const std::string& text) {
        if (error.empty()) error = text;
        return false;
    }

    void drain() {
        absent::Packet packet;
        while (encoder->take(packet)) {
            if (!configured) {
                writer->configure(videoTrack, encoder->configType(), encoder->config());
                configured = true;
            }
            writer->write(videoTrack, packet.data.data(), packet.data.size(), packet.pts, packet.dts, packet.duration ? packet.duration : 1, packet.sync);
        }
    }

    bool feed(int64_t until, bool last) {
        if (!sound) return true;
        const absent::AudioSink sink = [this](const uint8_t* data, size_t size) {
            writer->write(audioTrack, data, size, soundPackets * step, soundPackets * step, (uint32_t)step, true);
            ++soundPackets;
        };
        while (soundDone < until) {
            const size_t want = (size_t)std::min<int64_t>(until - soundDone, rate);
            pcm.resize(want * 2);
            const size_t got = fread(pcm.data(), 4, want, sound);
            if (!got) break;
            const int16_t* samples = pcm.data();
            if (channels == 1) {
                mono.resize(got);
                for (size_t i = 0; i < got; ++i) mono[i] = (int16_t)((pcm[2 * i] + pcm[2 * i + 1]) / 2);
                samples = mono.data();
            }
            if (!(opus ? opus->encode(samples, got, sink) : aac->encode(samples, got, sink))) return fail("the audio encoder failed");
            soundDone += (int64_t)got;
        }
        if (last && !(opus ? opus->finish(sink) : aac->finish(sink))) return fail("the audio encoder failed");
        return true;
    }

    bool frame(const uint8_t* pixels) {
        if (!encoder->encodeBGRA(pixels, frames, 1, true)) return fail("the video encoder refused a frame");
        ++frames;
        if (!feed(std::min(soundFrames, frames * rate / fps), false)) return false;
        drain();
        return true;
    }

    bool finish() {
        if (!encoder->finish()) return fail("the video encoder failed");
        drain();
        if (!feed(soundFrames, true)) return false;
        std::string problem;
        if (!writer->finish(&problem)) return fail(problem);
        done = true;
        return true;
    }
};

struct Sound {
    std::unique_ptr<absent::AudioFileWriter> writer;
    std::string path;
    int channels = 2;
    bool done = false;
    std::vector<float> samples;

    ~Sound() {
        writer.reset();
        if (!done) remove(path.c_str());
    }
};

struct Sink {
    std::unique_ptr<absent::FrameWriter> writer;
    std::string path;
    bool done = false;

    ~Sink() {
        writer.reset();
        if (!done) remove(path.c_str());
    }
};

struct Camera {
    std::unique_ptr<absent::CaptureSession> session;
    std::mutex lock;
    std::vector<uint8_t> pixels;
    int width = 0, height = 0;
    int serial = 0;

    ~Camera() { session.reset(); }
};

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
        const double peak = p.reader->mixPeak();
        const float gain = peak > 1 ? float(1 / peak) : 1.0f;
        for (size_t i = 0; i < shorts.size(); ++i) shorts[i] = (int16_t)std::clamp(std::lrintf(p.samples[i] * gain * 32768.0f), -32768L, 32767L);
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

JNIEXPORT jstring JNICALL JNI(openProblem)(JNIEnv* e, jobject, jstring path) {
    const std::string file = DesktopString(e, path);
    std::string error;
    Guarded(false, [&] {
        auto reader = absent::Reader::open(file, &error);
        if (!reader) return false;
        const absent::Track* track = reader->first(absent::Kind::Video);
        if (track) absent::VideoDecoder::open(*reader, *track, &error);
        const absent::Track* sound = reader->first(absent::Kind::Audio);
        if (sound && error.empty()) absent::AudioDecoder::open(*reader, *sound, &error);
        return true;
    });
    return error.empty() ? nullptr : e->NewStringUTF(error.c_str());
}

JNIEXPORT jboolean JNICALL JNI(divxDecoder)(JNIEnv*, jobject) {
#ifdef ABSENT_XVID
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL JNI(encoders)(JNIEnv* e, jobject) {
    std::string text;
    for (const absent::EncoderInfo& info : absent::encoders()) text += info.name + "\n";
    return e->NewStringUTF(text.c_str());
}

JNIEXPORT jboolean JNICALL JNI(encoderWorks)(JNIEnv* e, jobject, jstring codec, jint width, jint height, jint fps, jint bitrate) {
    const absent::EncodeSpec spec = SpecOf(DesktopString(e, codec), width, height, fps, bitrate, fps);
    return spec.codec && Guarded(false, [&] { return absent::encoderWorks(spec); }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI(exportOpen)(JNIEnv* e, jobject, jstring path, jstring codec, jint width, jint height, jint fps, jint bitrate, jint sound, jint soundBitrate, jint rate, jint channels, jstring pcmPath) {
    const std::string file = DesktopString(e, path), name = DesktopString(e, codec), samples = pcmPath ? DesktopString(e, pcmPath) : std::string();
    return Guarded<jlong>(0, [&]() -> jlong {
        auto made = std::make_unique<Export>();
        Export& x = *made;
        x.path = file;
        x.fps = std::max(fps, 1);
        absent::EncodeSpec spec = SpecOf(name, width, height, fps, bitrate, fps);
        spec.bgra = true;
        if (!spec.codec) return 0;
        x.encoder = absent::VideoEncoder::open(spec);
        x.writer = absent::Writer::open(file);
        if (!x.encoder || !x.writer) return 0;
        absent::TrackSpec picture;
        picture.kind = absent::Kind::Video;
        picture.codec = spec.codec;
        picture.timescale = spec.timescale;
        picture.width = width;
        picture.height = height;
        picture.color.known = true;
        x.videoTrack = x.writer->add(picture);
        if (sound && !samples.empty()) {
            x.rate = rate;
            x.channels = channels == 1 ? 1 : 2;
            x.sound = absent::openFile(samples, "rb");
            if (!x.sound) return 0;
            x.soundFrames = absent::fileBytes(samples) / 4;
            absent::TrackSpec track;
            track.kind = absent::Kind::Audio;
            track.timescale = (uint32_t)rate;
            track.sampleRate = rate;
            track.channels = x.channels;
            track.length = x.soundFrames;
            if (sound == 2) {
                if (rate != 48000) return 0;
                x.opus = absent::OpusEncoder::open(x.channels, soundBitrate);
                if (!x.opus) return 0;
                x.step = 960;
                track.codec = absent::fourcc("Opus");
                track.configType = absent::fourcc("OpHd");
                track.config = x.opus->config();
                track.skip = x.opus->priming();
            } else {
                x.aac = absent::AacEncoder::open(rate, x.channels, soundBitrate);
                if (!x.aac) return 0;
                track.codec = absent::fourcc("mp4a");
                track.configType = absent::fourcc("esds");
                track.config = x.aac->config();
                track.skip = x.aac->priming();
            }
            x.audioTrack = x.writer->add(track);
        }
        return reinterpret_cast<jlong>(made.release());
    });
}

JNIEXPORT jboolean JNICALL JNI(exportFrame)(JNIEnv* e, jobject, jlong h, jbyteArray pixels) {
    Export& x = *reinterpret_cast<Export*>(h);
    jbyte* bytes = e->GetByteArrayElements(pixels, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = Guarded(false, [&] { return x.frame(reinterpret_cast<const uint8_t*>(bytes)); });
    e->ReleaseByteArrayElements(pixels, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI(exportFinish)(JNIEnv* e, jobject, jlong h) {
    Export& x = *reinterpret_cast<Export*>(h);
    return Told(e, Guarded(false, [&] { return x.finish(); }), x.error);
}

JNIEXPORT jstring JNICALL JNI(exportProblem)(JNIEnv* e, jobject, jlong h) { return e->NewStringUTF(reinterpret_cast<Export*>(h)->error.c_str()); }

JNIEXPORT void JNICALL JNI(exportClose)(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Export*>(h); }

JNIEXPORT jlong JNICALL JNI(soundOpen)(JNIEnv* e, jobject, jstring path, jint format, jint rate, jint channels, jint bitrate) {
    const std::string file = DesktopString(e, path);
    return Guarded<jlong>(0, [&]() -> jlong {
        static const absent::AudioFormat formats[] = {absent::AudioFormat::Wav16, absent::AudioFormat::Wav24, absent::AudioFormat::Flac, absent::AudioFormat::Aac, absent::AudioFormat::Opus, absent::AudioFormat::Mp3};
        if (format < 0 || format > 5) return 0;
        auto made = std::make_unique<Sound>();
        made->path = file;
        made->channels = channels == 1 ? 1 : 2;
        made->writer = absent::AudioFileWriter::open(file, formats[format], rate, made->channels, bitrate);
        return made->writer ? reinterpret_cast<jlong>(made.release()) : 0;
    });
}

JNIEXPORT jboolean JNICALL JNI(soundWrite)(JNIEnv* e, jobject, jlong h, jbyteArray data, jint frames, jboolean floats) {
    Sound& s = *reinterpret_cast<Sound*>(h);
    jbyte* bytes = e->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;
    const size_t count = (size_t)frames;
    s.samples.resize(count * (size_t)s.channels);
    for (size_t i = 0; i < count; ++i) {
        float left, right;
        if (floats == JNI_TRUE) {
            memcpy(&left, bytes + i * 8, 4);
            memcpy(&right, bytes + i * 8 + 4, 4);
        } else {
            int16_t pair[2];
            memcpy(pair, bytes + i * 4, 4);
            left = pair[0] / 32768.0f;
            right = pair[1] / 32768.0f;
        }
        if (s.channels == 1) s.samples[i] = (left + right) * 0.5f;
        else {
            s.samples[2 * i] = left;
            s.samples[2 * i + 1] = right;
        }
    }
    e->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return Guarded(false, [&] { return s.writer->write(s.samples.data(), count); }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI(soundFinish)(JNIEnv* e, jobject, jlong h) {
    Sound& s = *reinterpret_cast<Sound*>(h);
    std::string error;
    s.done = Guarded(false, [&] { return s.writer->finish(&error); });
    return Told(e, s.done, error);
}

JNIEXPORT void JNICALL JNI(soundClose)(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Sound*>(h); }

JNIEXPORT jdoubleArray JNICALL JNI(times)(JNIEnv* e, jobject, jstring path, jboolean decoded, jdouble from) {
    const std::string file = DesktopString(e, path);
    std::vector<double> values;
    const bool read = Guarded(false, [&] {
        auto reader = absent::Reader::open(file);
        if (!reader) return false;
        const absent::Track* track = reader->first(absent::Kind::Video);
        if (!track || track->order.empty()) return true;
        const bool firstOnly = from < 0;
        const double start = std::max(from, 0.0) + FileStart(*reader);
        uint32_t key = track->syncs.empty() ? 0 : track->syncs[0];
        for (uint32_t index : track->syncs) if (index < track->samples.size() && OnFileClock(*track, track->samples[index].pts) <= start + 1e-9) key = index;
        if (decoded != JNI_TRUE) {
            for (uint32_t n = key; n < track->samples.size(); ++n) values.push_back(OnFileClock(*track, track->samples[n].pts));
            return true;
        }
        auto decoder = absent::VideoDecoder::open(*reader, *track);
        if (!decoder) return true;
        uint32_t first = 0;
        if (!firstOnly) while (first < track->order.size() && track->order[first] != key) ++first;
        const uint32_t end = firstOnly ? std::min<uint32_t>(1, decoder->frames()) : decoder->frames();
        for (uint32_t n = first; n < end; ++n) {
            if (!decoder->frame(n)) break;
            values.push_back(OnFileClock(*track, decoder->pts(n)));
        }
        return true;
    });
    if (!read) return nullptr;
    auto result = e->NewDoubleArray((jsize)values.size());
    if (result) e->SetDoubleArrayRegion(result, 0, (jsize)values.size(), values.data());
    return result;
}

JNIEXPORT jstring JNICALL JNI(compress)(JNIEnv* e, jobject, jstring input, jstring output, jlong startUs, jlong endUs, jint width, jint height, jint fps, jint bitrate, jint sound, jint soundBitrate, jobject progress) {
    absent::CompressJob job;
    job.input = DesktopString(e, input);
    job.output = DesktopString(e, output);
    job.startUs = startUs;
    job.endUs = endUs;
    job.width = width;
    job.height = height;
    job.fpsNumerator = fps;
    job.bitrate = bitrate;
    job.sound = sound == 0 ? absent::SoundChoice::None : sound == 1 ? absent::SoundChoice::Copy : absent::SoundChoice::Aac;
    job.audioBitrate = soundBitrate;
    Stepper stepper(e, progress);
    std::string error;
    return Told(e, Guarded(false, [&] { return absent::compress(job, stepper.progress(), &error); }), error);
}

JNIEXPORT jstring JNICALL JNI(trim)(JNIEnv* e, jobject, jstring input, jstring output, jlong startUs, jlong endUs, jobject progress) {
    const std::string in = DesktopString(e, input), out = DesktopString(e, output);
    Stepper stepper(e, progress);
    std::string error;
    return Told(e, Guarded(false, [&] { return absent::trimCopy(in, out, startUs, endUs, stepper.progress(), &error); }), error);
}

JNIEXPORT jstring JNICALL JNI(extractAudio)(JNIEnv* e, jobject, jstring input, jstring output, jint how, jint bitrate, jobject progress) {
    const std::string in = DesktopString(e, input), out = DesktopString(e, output);
    const absent::SoundChoice choice = how == 1 ? absent::SoundChoice::Copy : how == 3 ? absent::SoundChoice::Mp3 : absent::SoundChoice::Aac;
    Stepper stepper(e, progress);
    std::string error;
    return Told(e, Guarded(false, [&] { return absent::extractAudio(in, out, choice, bitrate, stepper.progress(), &error); }), error);
}

JNIEXPORT jdoubleArray JNICALL JNI(reverse)(JNIEnv* e, jobject, jstring input, jstring output, jlong startUs, jlong endUs, jobject progress) {
    absent::ReverseJob job;
    job.input = DesktopString(e, input);
    job.output = DesktopString(e, output);
    job.startUs = startUs;
    job.endUs = endUs;
    Stepper stepper(e, progress);
    absent::ReverseResult made;
    if (!Guarded(false, [&] { return absent::reverse(job, stepper.progress(), &made); })) return nullptr;
    const double values[4] = {(double)made.width, (double)made.height, (double)made.frames, (double)made.durationUs};
    auto result = e->NewDoubleArray(4);
    if (result) e->SetDoubleArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jdoubleArray JNICALL JNI(gifVideo)(JNIEnv* e, jobject, jstring input, jstring colour, jstring matte, jobject progress) {
    const std::string in = DesktopString(e, input), one = DesktopString(e, colour), two = DesktopString(e, matte);
    Stepper stepper(e, progress);
    absent::GifInfo info;
    if (!Guarded(false, [&] { return absent::gifToVideo(in, one, two, &info, stepper.progress()); })) return nullptr;
    const double values[6] = {(double)info.videoWidth, (double)info.videoHeight, (double)info.fps, (double)info.durationMs, info.transparent ? 1.0 : 0.0, (double)info.delaysMs.size()};
    auto result = e->NewDoubleArray(6);
    if (result) e->SetDoubleArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT jstring JNICALL JNI(merge)(JNIEnv* e, jobject, jstring video, jstring audio, jstring output) {
    const std::string one = DesktopString(e, video), two = DesktopString(e, audio), out = DesktopString(e, output);
    std::string error;
    return Told(e, Guarded(false, [&] { return absent::merge(one, two, out, nullptr, &error); }), error);
}

JNIEXPORT jstring JNICALL JNI(stripAudio)(JNIEnv* e, jobject, jstring input, jstring output) {
    const std::string in = DesktopString(e, input), out = DesktopString(e, output);
    std::string error;
    return Told(e, Guarded(false, [&] { return absent::stripAudio(in, out, nullptr, &error); }), error);
}

JNIEXPORT jstring JNICALL JNI(rewrap)(JNIEnv* e, jobject, jstring input, jstring output) {
    const std::string in = DesktopString(e, input), out = DesktopString(e, output);
    std::string error;
    return Told(e, Guarded(false, [&] { return absent::rewrap(in, out, nullptr, &error); }), error);
}

JNIEXPORT jintArray JNICALL JNI(framesSteady)(JNIEnv* e, jobject, jlong h, jdouble start, jdouble fps, jdouble length) {
    const std::vector<uint32_t> picked = Guarded(std::vector<uint32_t>(), [&] { return frames(h)->reader->steady(start, fps, length, -1); });
    auto result = e->NewIntArray((jsize)picked.size());
    if (result) e->SetIntArrayRegion(result, 0, (jsize)picked.size(), reinterpret_cast<const jint*>(picked.data()));
    return result;
}

JNIEXPORT jlong JNICALL JNI(sinkOpen)(JNIEnv* e, jobject, jstring path, jint width, jint height, jint layout, jstring rate, jdouble start, jstring audioFrom, jdouble audioStart, jdouble audioLength, jdouble limit) {
    const std::string file = DesktopString(e, path), fps = DesktopString(e, rate), sound = audioFrom ? DesktopString(e, audioFrom) : std::string();
    return Guarded<jlong>(0, [&]() -> jlong {
        absent::FrameWriterSpec spec;
        spec.width = width;
        spec.height = height;
        spec.layout = layout == 1 ? absent::PixelLayout::Rgb24 : layout == 4 ? absent::PixelLayout::Gray : absent::PixelLayout::Rgba;
        if (!absent::frameRateFrom(fps, spec.rateTop, spec.rateBottom)) return 0;
        spec.start = start;
        spec.audioFrom = sound;
        spec.audioStart = audioStart;
        spec.audioLength = audioLength;
        spec.limit = limit;
        auto made = std::make_unique<Sink>();
        made->path = file;
        made->writer = absent::FrameWriter::open(file, spec);
        return made->writer ? reinterpret_cast<jlong>(made.release()) : 0;
    });
}

JNIEXPORT jboolean JNICALL JNI(sinkWrite)(JNIEnv* e, jobject, jlong h, jbyteArray pixels) {
    Sink& s = *reinterpret_cast<Sink*>(h);
    if ((size_t)e->GetArrayLength(pixels) < s.writer->frameBytes()) return JNI_FALSE;
    jbyte* bytes = e->GetByteArrayElements(pixels, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = Guarded(false, [&] { return s.writer->write(reinterpret_cast<const uint8_t*>(bytes)); });
    e->ReleaseByteArrayElements(pixels, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI(sinkFinish)(JNIEnv*, jobject, jlong h) {
    Sink& s = *reinterpret_cast<Sink*>(h);
    s.done = Guarded(false, [&] { return s.writer->finish(); });
    return s.done ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI(sinkClose)(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Sink*>(h); }

JNIEXPORT jstring JNICALL JNI(speech)(JNIEnv* e, jobject, jstring input, jstring output, jint rate, jdouble speed, jdouble pitch) {
    const std::string in = DesktopString(e, input), out = DesktopString(e, output);
    std::string error;
    const bool ok = Guarded(false, [&] { return absent::convertSpeech(in, out, rate, speed, pitch, &error); });
    if (!ok) remove(out.c_str());
    return Told(e, ok, error);
}

JNIEXPORT jstring JNICALL JNI(devices)(JNIEnv* e, jobject) {
    std::string text;
    Guarded(false, [&] {
        for (const absent::CaptureDevice& device : absent::captureDevices()) text += std::string(device.video ? "video" : "audio") + "\t" + device.id + "\t" + device.name + "\n";
        return true;
    });
    return e->NewStringUTF(text.c_str());
}

JNIEXPORT jlong JNICALL JNI(cameraOpen)(JNIEnv* e, jobject, jstring video, jstring audio, jstring record, jint maxWidth) {
    const std::string picture = DesktopString(e, video), sound = audio ? DesktopString(e, audio) : std::string(), file = record ? DesktopString(e, record) : std::string();
    return Guarded<jlong>(0, [&]() -> jlong {
        auto made = std::make_unique<Camera>();
        Camera* raw = made.get();
        absent::CaptureSpec spec;
        spec.video = picture;
        spec.audio = sound;
        spec.record = file;
        spec.ask = true;
        spec.preview = [raw](const uint8_t* bgra, int width, int height, double) {
            std::lock_guard<std::mutex> hold(raw->lock);
            raw->pixels.assign(bgra, bgra + (size_t)width * (size_t)height * 4);
            raw->width = width;
            raw->height = height;
            ++raw->serial;
        };
        (void)maxWidth;
        made->session = absent::CaptureSession::open(spec);
        return made->session ? reinterpret_cast<jlong>(made.release()) : 0;
    });
}

JNIEXPORT jintArray JNICALL JNI(cameraState)(JNIEnv* e, jobject, jlong h) {
    Camera& c = *reinterpret_cast<Camera*>(h);
    std::lock_guard<std::mutex> hold(c.lock);
    const jint values[3] = {c.width, c.height, c.serial};
    auto result = e->NewIntArray(3);
    if (result) e->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL JNI(cameraCopy)(JNIEnv* e, jobject, jlong h, jbyteArray out) {
    Camera& c = *reinterpret_cast<Camera*>(h);
    std::lock_guard<std::mutex> hold(c.lock);
    if (c.pixels.empty() || (size_t)e->GetArrayLength(out) != c.pixels.size()) return -1;
    e->SetByteArrayRegion(out, 0, (jsize)c.pixels.size(), reinterpret_cast<const jbyte*>(c.pixels.data()));
    return c.serial;
}

JNIEXPORT jstring JNICALL JNI(cameraFailed)(JNIEnv* e, jobject, jlong h) {
    std::string error;
    return reinterpret_cast<Camera*>(h)->session->failed(&error) ? e->NewStringUTF(error.empty() ? "failed" : error.c_str()) : nullptr;
}

JNIEXPORT jstring JNICALL JNI(cameraFinish)(JNIEnv* e, jobject, jlong h) {
    Camera& c = *reinterpret_cast<Camera*>(h);
    std::string error;
    return Told(e, Guarded(false, [&] { return c.session->finish(&error); }), error);
}

JNIEXPORT void JNICALL JNI(cameraClose)(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Camera*>(h); }

}
