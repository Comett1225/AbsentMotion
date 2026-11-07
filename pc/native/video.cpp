#include "video.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkYUVAPixmaps.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}

namespace {

constexpr int kAhead = 3;

struct FrameFree { void operator()(AVFrame* f) const { av_frame_free(&f); } };
using FramePtr = std::unique_ptr<AVFrame, FrameFree>;

struct Decoded {
    FramePtr frame;
    int64_t us;
};

struct Video;
std::unique_ptr<Video> Open(const std::string& path);

bool UnspecifiedHd(const AVFrame* f) { return f->colorspace == AVCOL_SPC_UNSPECIFIED && f->height >= 720; }

SkYUVColorSpace YuvSpace(const AVFrame* f) {
    const bool full = f->color_range == AVCOL_RANGE_JPEG;
    switch (f->colorspace) {
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:
            return full ? kBT2020_8bit_Full_SkYUVColorSpace : kBT2020_8bit_Limited_SkYUVColorSpace;
        case AVCOL_SPC_BT709:
            return full ? kRec709_Full_SkYUVColorSpace : kRec709_Limited_SkYUVColorSpace;
        default:
            if (UnspecifiedHd(f)) return full ? kRec709_Full_SkYUVColorSpace : kRec709_Limited_SkYUVColorSpace;
            return full ? kJPEG_Full_SkYUVColorSpace : kRec601_Limited_SkYUVColorSpace;
    }
}

AVPixelFormat PickFormat(AVCodecContext* c, const AVPixelFormat* list) {
    const auto hw = (AVPixelFormat)(intptr_t)c->opaque;
    for (auto p = list; *p != AV_PIX_FMT_NONE; ++p) if (*p == hw) return *p;
    for (auto p = list; *p != AV_PIX_FMT_NONE; ++p) if (!(av_pix_fmt_desc_get(*p)->flags & AV_PIX_FMT_FLAG_HWACCEL)) return *p;
    return list[0];
}

struct Video {
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    AVStream* stream = nullptr;
    AVPacket* packet = av_packet_alloc();
    SwsContext* scaler = nullptr;
    int64_t origin = 0;
    double info[7] = {};
    std::mutex lock;
    std::condition_variable wake, ready;
    std::deque<Decoded> frames;
    int64_t want = 0, cover = INT64_MAX, last = INT64_MIN;
    bool asked = false, ended = false, quit = false;
    std::thread worker;
    bool hardware = false;
    int64_t shownUs = INT64_MIN;
    sk_sp<SkImage> shown;
    std::string path;
    std::vector<std::unique_ptr<Video>> lanes;
    uint64_t used = 0, turns = 0;
    int64_t owner = INT64_MIN;

    ~Video() {
        {
            std::lock_guard<std::mutex> hold(lock);
            quit = true;
        }
        wake.notify_all();
        if (worker.joinable()) worker.join();
        frames.clear();
        shown.reset();
        sws_free_context(&scaler);
        avcodec_free_context(&codec);
        avformat_close_input(&format);
        av_packet_free(&packet);
    }

    bool configure(const AVCodec* decoder, AVBufferRef* device) {
        codec = avcodec_alloc_context3(decoder);
        if (!codec || avcodec_parameters_to_context(codec, stream->codecpar) < 0) return false;
        codec->pkt_timebase = stream->time_base;
        if (device) {
            const auto type = ((AVHWDeviceContext*)device->data)->type;
            for (int i = 0; const AVCodecHWConfig* config = avcodec_get_hw_config(decoder, i); ++i) {
                if ((config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) && config->device_type == type) {
                    codec->hw_device_ctx = av_buffer_ref(device);
                    codec->opaque = (void*)(intptr_t)config->pix_fmt;
                    codec->get_format = PickFormat;
                    codec->thread_count = 1;
                    hardware = true;
                    break;
                }
            }
        }
        if (!hardware) codec->thread_count = 0;
        return avcodec_open2(codec, decoder, nullptr) >= 0;
    }

    bool probe() {
        if (std::getenv("AM_VIDEO_AV1_REFUSE")) return false;
        FramePtr frame(av_frame_alloc());
        bool got = false;
        for (int sent = 0; !got && sent < 64;) {
            if (av_read_frame(format, packet) < 0) break;
            if (packet->stream_index == stream->index) {
                ++sent;
                if (avcodec_send_packet(codec, packet) < 0) { av_packet_unref(packet); break; }
                got = avcodec_receive_frame(codec, frame.get()) >= 0 && frame->hw_frames_ctx;
            }
            av_packet_unref(packet);
        }
        if (got) seek(0);
        return got;
    }

    int64_t toUs(int64_t pts) const { return av_rescale_q(pts - origin, stream->time_base, AV_TIME_BASE_Q); }
    int64_t fromUs(int64_t us) const { return av_rescale_q(us, AV_TIME_BASE_Q, stream->time_base) + origin; }

    bool mustSeek(int64_t t) const {
        if (t < cover) return true;
        if (t <= last || ended) return false;
        const int key = av_index_search_timestamp(stream, fromUs(t), AVSEEK_FLAG_BACKWARD);
        if (key < 0) return t - last > 2000000;
        const AVIndexEntry* entry = avformat_index_get_entry(stream, key);
        return entry && toUs(entry->timestamp) > last;
    }

    bool farFrom(int64_t t) const { return t < cover || (t > last && !ended && t - last > 2000000); }

    bool servable(int64_t t) const { return t >= cover && !frames.empty() && (ended || frames.back().us > t); }
    bool hopeless(int64_t t) const { return t >= cover && frames.empty() && ended; }

    const Decoded& chosen(int64_t t) const {
        auto it = std::upper_bound(frames.begin(), frames.end(), t, [](int64_t v, const Decoded& d) { return v < d.us; });
        return it == frames.begin() ? frames.front() : *(it - 1);
    }

    void prune(int64_t t) {
        bool popped = false;
        while (frames.size() >= 2 && frames[1].us <= t) { frames.pop_front(); popped = true; }
        if (popped) cover = frames.front().us;
    }

    int ahead(int64_t t) const {
        int n = 0;
        for (auto it = frames.rbegin(); it != frames.rend() && it->us > t; ++it) ++n;
        return n;
    }

    void seek(int64_t t) {
        avcodec_flush_buffers(codec);
        if (av_seek_frame(format, stream->index, fromUs(std::max<int64_t>(t, 0)), AVSEEK_FLAG_BACKWARD) < 0)
            av_seek_frame(format, stream->index, origin, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY);
    }

    int decode(AVFrame* out, int64_t target) {
        for (int failed = 0;;) {
            int r = avcodec_receive_frame(codec, out);
            if (r >= 0 || r == AVERROR_EOF) return r;
            if (r != AVERROR(EAGAIN)) {
                if (++failed > 64) return r;
                continue;
            }
            r = av_read_frame(format, packet);
            if (r < 0) {
                if (avcodec_send_packet(codec, nullptr) < 0) return AVERROR_EOF;
                continue;
            }
            if (packet->stream_index == stream->index) {
                const bool early = packet->pts != AV_NOPTS_VALUE && packet->duration > 0 && toUs(packet->pts + packet->duration) <= target;
                codec->skip_frame = early ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
                avcodec_send_packet(codec, packet);
            }
            av_packet_unref(packet);
        }
    }

    void run() {
        FramePtr frame(av_frame_alloc());
        std::unique_lock<std::mutex> hold(lock);
        while (!quit) {
            if (!asked) { wake.wait(hold); continue; }
            const int64_t t = want;
            if (mustSeek(t)) {
                frames.clear();
                ended = false;
                last = t;
                cover = t <= 0 ? INT64_MIN : t;
                hold.unlock();
                seek(t);
                hold.lock();
                continue;
            }
            prune(t);
            if (ended || ahead(t) >= kAhead) {
                ready.notify_all();
                wake.wait(hold);
                continue;
            }
            hold.unlock();
            const int r = decode(frame.get(), t);
            hold.lock();
            if (r < 0) ended = true;
            else {
                int64_t pts = frame->best_effort_timestamp != AV_NOPTS_VALUE ? frame->best_effort_timestamp : frame->pts;
                const int64_t us = pts != AV_NOPTS_VALUE ? toUs(pts) : last + 1;
                auto at = std::upper_bound(frames.begin(), frames.end(), us, [](int64_t v, const Decoded& d) { return v < d.us; });
                frames.insert(at, Decoded{std::move(frame), us});
                last = std::max(last, us);
                frame.reset(av_frame_alloc());
            }
            ready.notify_all();
        }
    }

    sk_sp<SkImage> planar(GrDirectContext* context, const AVFrame* f) {
        auto config = SkYUVAInfo::PlaneConfig::kY_U_V;
        auto subsampling = SkYUVAInfo::Subsampling::k420;
        switch (f->format) {
            case AV_PIX_FMT_YUV420P: case AV_PIX_FMT_YUVJ420P: break;
            case AV_PIX_FMT_YUV422P: case AV_PIX_FMT_YUVJ422P: subsampling = SkYUVAInfo::Subsampling::k422; break;
            case AV_PIX_FMT_YUV444P: case AV_PIX_FMT_YUVJ444P: subsampling = SkYUVAInfo::Subsampling::k444; break;
            case AV_PIX_FMT_NV12: config = SkYUVAInfo::PlaneConfig::kY_UV; break;
            default: return nullptr;
        }
        if (!context) return nullptr;
        const SkYUVAInfo yuva({f->width, f->height}, config, subsampling, YuvSpace(f));
        size_t rowBytes[SkYUVAInfo::kMaxPlanes] = {};
        for (int i = 0; i < yuva.numPlanes(); ++i) {
            if (f->linesize[i] <= 0) return nullptr;
            rowBytes[i] = (size_t)f->linesize[i];
        }
        const SkYUVAPixmapInfo info(yuva, SkYUVAPixmapInfo::DataType::kUnorm8, rowBytes);
        SkPixmap planes[SkYUVAInfo::kMaxPlanes];
        for (int i = 0; i < yuva.numPlanes(); ++i) planes[i].reset(info.planeInfo(i), f->data[i], rowBytes[i]);
        return SkImages::TextureFromYUVAPixmaps(context, SkYUVAPixmaps::FromExternalPixmaps(yuva, planes), skgpu::Mipmapped::kNo, false, SkColorSpace::MakeSRGB());
    }

    sk_sp<SkImage> software(GrDirectContext* context, AVFrame* f) {
        if (UnspecifiedHd(f)) f->colorspace = AVCOL_SPC_BT709;
        if (auto image = planar(context, f)) return image;
        if (!scaler && (scaler = sws_alloc_context())) scaler->flags = SWS_BILINEAR | SWS_ACCURATE_RND | SWS_FULL_CHR_H_INT;
        AVFrame* rgba = av_frame_alloc();
        rgba->format = AV_PIX_FMT_RGBA;
        rgba->width = f->width;
        rgba->height = f->height;
        if (!scaler || sws_scale_frame(scaler, rgba, f) < 0) {
            av_frame_free(&rgba);
            return nullptr;
        }
        const auto* desc = av_pix_fmt_desc_get((AVPixelFormat)f->format);
        const bool alpha = desc && (desc->flags & AV_PIX_FMT_FLAG_ALPHA);
        const auto info = SkImageInfo::Make(f->width, f->height, kRGBA_8888_SkColorType, alpha ? kUnpremul_SkAlphaType : kOpaque_SkAlphaType, SkColorSpace::MakeSRGB());
        const size_t rowBytes = (size_t)rgba->linesize[0];
        auto data = SkData::MakeWithProc(rgba->data[0], rowBytes * f->height, [](const void*, void* c) {
            auto* held = (AVFrame*)c;
            av_frame_free(&held);
        }, rgba);
        auto image = SkImages::RasterFromData(info, std::move(data), rowBytes);
        if (image && context) {
            auto uploaded = SkImages::TextureFromImage(context, image.get());
            if (uploaded) image = uploaded;
        }
        return image;
    }

    sk_sp<SkImage> image(GrDirectContext* context, FramePtr f) {
        if (f->hw_frames_ctx) {
            if (auto image = VideoHwImage(context, f.get(), YuvSpace(f.get()))) return image;
            FramePtr copy(av_frame_alloc());
            if (av_hwframe_transfer_data(copy.get(), f.get(), 0) < 0) return nullptr;
            av_frame_copy_props(copy.get(), f.get());
            f = std::move(copy);
        }
        return software(context, f.get());
    }

    Video* route(int64_t t, int64_t layer) {
        Video* best = nullptr;
        int64_t gap = INT64_MAX;
        for (size_t i = 0; i <= lanes.size(); ++i) {
            Video* v = i ? lanes[i - 1].get() : this;
            std::lock_guard<std::mutex> hold(v->lock);
            if (v->asked && v->farFrom(t)) continue;
            const int64_t d = v->asked ? std::max<int64_t>(t - v->last, 0) : INT64_MAX - 1;
            if (d < gap) gap = d, best = v;
        }
        for (size_t i = 0; !best && i <= lanes.size(); ++i) {
            Video* v = i ? lanes[i - 1].get() : this;
            if (v->owner == layer) best = v;
        }
        if (!best && lanes.size() < 2) {
            if (auto made = Open(path)) best = lanes.emplace_back(std::move(made)).get();
        }
        if (!best) {
            best = this;
            for (auto& v : lanes) if (v->used < best->used) best = v.get();
        }
        best->used = ++turns;
        best->owner = layer;
        return best;
    }

    int show(jlong desktop, const std::string& uri, int64_t t, int waitMs, int64_t layer = 0) {
        FramePtr pick;
        int64_t pickUs = INT64_MIN;
        bool gone = false;
        {
            std::unique_lock<std::mutex> hold(lock);
            want = t;
            asked = true;
            wake.notify_one();
            const auto wait = std::chrono::milliseconds(std::max(waitMs, 0));
            ready.wait_for(hold, wait, [&] { return servable(t) || hopeless(t); });
            if (servable(t)) {
                const Decoded& d = chosen(t);
                pickUs = d.us;
                if (d.us != shownUs || !shown) pick.reset(av_frame_clone(d.frame.get()));
            } else gone = hopeless(t);
        }
        if (pick) {
            auto made = image(DesktopContext(desktop), std::move(pick));
            if (made) {
                shown = std::move(made);
                shownUs = pickUs;
            } else gone = true;
        }
        if (shown) { if (layer) DesktopInstallLayer(desktop, uri, layer, shown); else DesktopInstall(desktop, uri, shown); }
        if (pickUs != INT64_MIN && shownUs == pickUs) return 1;
        return gone ? -1 : 0;
    }
};

std::unique_ptr<Video> Open(const std::string& path) {
    auto v = std::make_unique<Video>();
    if (avformat_open_input(&v->format, path.c_str(), nullptr, nullptr) < 0) return nullptr;
    if (avformat_find_stream_info(v->format, nullptr) < 0) return nullptr;
    const AVCodec* decoder = nullptr;
    const int index = av_find_best_stream(v->format, AVMEDIA_TYPE_VIDEO, -1, -1, &decoder, 0);
    if (index < 0 || !decoder) return nullptr;
    v->stream = v->format->streams[index];
    const bool audio = av_find_best_stream(v->format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0) >= 0;
    for (unsigned i = 0; i < v->format->nb_streams; ++i) if ((int)i != index) v->format->streams[i]->discard = AVDISCARD_ALL;
    v->origin = v->stream->start_time != AV_NOPTS_VALUE ? v->stream->start_time : 0;
    AVBufferRef* device = std::getenv("AM_VIDEO_SOFTWARE") ? nullptr : VideoHwDevice();
    const AVCodec* native = device && decoder->id == AV_CODEC_ID_AV1 && std::string(decoder->name) != "av1" ? avcodec_find_decoder_by_name("av1") : nullptr;
    if (native && !(v->configure(native, device) && v->hardware && v->probe())) {
        avcodec_free_context(&v->codec);
        v->hardware = false;
        device = nullptr;
    }
    if (!v->codec && !(v->configure(decoder, device) && (!v->hardware || v->probe()))) {
        avcodec_free_context(&v->codec);
        v->hardware = false;
        if (!v->configure(decoder, nullptr)) return nullptr;
    }
    const AVCodecParameters* par = v->stream->codecpar;
    int orientation = 0;
    if (const AVPacketSideData* side = av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX)) {
        const double rotation = av_display_rotation_get((const int32_t*)side->data);
        if (std::isfinite(rotation)) orientation = (((int)std::lround(-rotation / 90) * 90) % 360 + 360) % 360;
    }
    const int64_t duration = v->stream->duration != AV_NOPTS_VALUE ? av_rescale_q(v->stream->duration, v->stream->time_base, AV_TIME_BASE_Q)
                                                                   : std::max<int64_t>(v->format->duration, 0);
    AVRational rate = v->stream->avg_frame_rate.num > 0 && v->stream->avg_frame_rate.den > 0 ? v->stream->avg_frame_rate : v->stream->r_frame_rate;
    const double fps = rate.num > 0 && rate.den > 0 ? av_q2d(rate) : 30.0;
    const double values[7] = {(double)par->width, (double)par->height, (double)duration, fps, (double)orientation, audio ? 1.0 : 0.0, v->hardware ? 1.0 : 0.0};
    std::copy(values, values + 7, v->info);
    v->path = path;
    Video* raw = v.get();
    v->worker = std::thread([raw] { raw->run(); });
    return v;
}

Video* video(jlong v) { return reinterpret_cast<Video*>(v); }

}

#define JNI(name) Java_app_absentmotion_pc_engine_DesktopNative_##name
extern "C" {
JNIEXPORT jlong JNICALL JNI(videoOpen)(JNIEnv* e, jobject, jstring path) { return reinterpret_cast<jlong>(Open(DesktopString(e, path)).release()); }
JNIEXPORT jdoubleArray JNICALL JNI(videoInfo)(JNIEnv* e, jobject, jlong v) {
    auto result = e->NewDoubleArray(7);
    e->SetDoubleArrayRegion(result, 0, 7, video(v)->info);
    return result;
}
JNIEXPORT jint JNICALL JNI(videoFrame)(JNIEnv* e, jobject, jlong h, jlong v, jstring uri, jlong timeUs, jboolean exact) {
    return video(v)->route(timeUs, 0)->show(h, DesktopString(e, uri), timeUs, exact == JNI_TRUE ? 15000 : 30);
}
JNIEXPORT jint JNICALL JNI(videoFrameLayer)(JNIEnv* e, jobject, jlong h, jlong v, jstring uri, jlong layer, jlong timeUs, jint waitMs) {
    return video(v)->route(timeUs, layer)->show(h, DesktopString(e, uri), timeUs, waitMs, layer);
}
JNIEXPORT void JNICALL JNI(videoClose)(JNIEnv*, jobject, jlong v) { delete video(v); }
}
