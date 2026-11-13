#include "video.h"
#include "absent/media.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <thread>
#include <vector>
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkYUVAInfo.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrYUVABackendTextures.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendSurface.h"
#include "include/gpu/ganesh/mtl/GrMtlTypes.h"
#import <CoreVideo/CoreVideo.h>
#import <dispatch/dispatch.h>
#import <Metal/Metal.h>

namespace {

constexpr int64_t kAhead = 3;
constexpr int64_t kConceal = 8;
constexpr int64_t kHoldMs = 250;

CVMetalTextureCacheRef Cache() {
    static CVMetalTextureCacheRef cache = [] {
        CVMetalTextureCacheRef made = nullptr;
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device) CVMetalTextureCacheCreate(kCFAllocatorDefault, nullptr, device, nullptr, &made);
        return made;
    }();
    return cache;
}

struct Held {
    CVMetalTextureRef planes[2] = {};
    std::shared_ptr<void> pixels;
};

void Free(Held* held) {
    for (auto plane : held->planes) if (plane) CFRelease(plane);
    delete held;
}

void Release(void* context) {
    auto* held = static_cast<Held*>(context);
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, kHoldMs * NSEC_PER_MSEC), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{ Free(held); });
}

SkYUVColorSpace Space(const absent::Frame& f) {
    const bool full = f.color.full;
    const int matrix = f.color.matrix;
    if (matrix == 9 || matrix == 10) return full ? kBT2020_8bit_Full_SkYUVColorSpace : kBT2020_8bit_Limited_SkYUVColorSpace;
    if (matrix == 1 || (matrix == 2 && f.height >= 720)) return full ? kRec709_Full_SkYUVColorSpace : kRec709_Limited_SkYUVColorSpace;
    return full ? kJPEG_Full_SkYUVColorSpace : kRec601_Limited_SkYUVColorSpace;
}

bool Hdr(const absent::Frame& f) { return f.color.transfer == 16 || f.color.transfer == 18; }

sk_sp<SkImage> Direct(GrDirectContext* context, const absent::Frame& frame) {
    auto pixels = static_cast<CVPixelBufferRef>(frame.native.get());
    if (!context || context->backend() != GrBackendApi::kMetal || !Cache() || CVPixelBufferGetPlaneCount(pixels) != 2) return nullptr;
    const bool deep = frame.bits > 8;
    const int width = (int)CVPixelBufferGetWidth(pixels), height = (int)CVPixelBufferGetHeight(pixels);
    const int chromaWidth = (int)CVPixelBufferGetWidthOfPlane(pixels, 1), chromaHeight = (int)CVPixelBufferGetHeightOfPlane(pixels, 1);
    SkYUVAInfo::Subsampling subsampling;
    if (chromaWidth == width && chromaHeight == height) subsampling = SkYUVAInfo::Subsampling::k444;
    else if (chromaWidth == (width + 1) / 2 && chromaHeight == height) subsampling = SkYUVAInfo::Subsampling::k422;
    else if (chromaWidth == (width + 1) / 2 && chromaHeight == (height + 1) / 2) subsampling = SkYUVAInfo::Subsampling::k420;
    else return nullptr;
    auto* held = new Held;
    held->pixels = frame.native;
    GrBackendTexture textures[SkYUVAInfo::kMaxPlanes];
    for (int i = 0; i < 2; ++i) {
        const MTLPixelFormat format = i == 0 ? (deep ? MTLPixelFormatR16Unorm : MTLPixelFormatR8Unorm) : (deep ? MTLPixelFormatRG16Unorm : MTLPixelFormatRG8Unorm);
        const size_t w = CVPixelBufferGetWidthOfPlane(pixels, i), h = CVPixelBufferGetHeightOfPlane(pixels, i);
        if (CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, Cache(), pixels, nullptr, format, w, h, i, &held->planes[i]) != kCVReturnSuccess) {
            Free(held);
            return nullptr;
        }
        GrMtlTextureInfo info;
        info.fTexture.retain((__bridge GrMTLHandle)CVMetalTextureGetTexture(held->planes[i]));
        textures[i] = GrBackendTextures::MakeMtl((int)w, (int)h, skgpu::Mipmapped::kNo, info);
    }
    const SkYUVAInfo yuva({width, height}, SkYUVAInfo::PlaneConfig::kY_UV, subsampling, Space(frame));
    const GrYUVABackendTextures planes(yuva, textures, kTopLeft_GrSurfaceOrigin);
    auto image = SkImages::TextureFromYUVATextures(context, planes, SkColorSpace::MakeSRGB(), Release, held);
    CVMetalTextureCacheFlush(Cache(), 0);
    return image;
}

sk_sp<SkImage> Copied(GrDirectContext* context, const absent::Frame& frame) {
    auto* pixels = new std::vector<uint8_t>;
    if (!absent::copyBGRA(frame, *pixels)) {
        delete pixels;
        return nullptr;
    }
    const size_t rowBytes = (size_t)frame.width * 4;
    auto data = SkData::MakeWithProc(pixels->data(), pixels->size(), [](const void*, void* c) { delete static_cast<std::vector<uint8_t>*>(c); }, pixels);
    const auto info = SkImageInfo::Make(frame.width, frame.height, kBGRA_8888_SkColorType, kOpaque_SkAlphaType, SkColorSpace::MakeSRGB());
    auto image = SkImages::RasterFromData(info, std::move(data), rowBytes);
    if (image && context) {
        auto uploaded = SkImages::TextureFromImage(context, image.get());
        if (uploaded) image = uploaded;
    }
    return image;
}

sk_sp<SkImage> Image(GrDirectContext* context, const absent::Frame& frame) {
    if (!Hdr(frame)) if (auto image = Direct(context, frame)) return image;
    return Copied(context, frame);
}

struct Lane {
    std::unique_ptr<absent::Reader> file;
    std::unique_ptr<absent::VideoDecoder> decoder;
    const absent::Track* track = nullptr;
    int64_t total = 0;
    std::mutex lock;
    std::condition_variable wake, ready;
    std::map<int64_t, absent::Frame> frames;
    std::set<int64_t> failed;
    int64_t want = -1;
    bool quit = false;
    std::thread worker;
    int64_t at = -1, shownIndex = -1, owner = INT64_MIN;
    uint64_t used = 0;
    sk_sp<SkImage> shown;

    ~Lane() {
        {
            std::lock_guard<std::mutex> hold(lock);
            quit = true;
        }
        wake.notify_all();
        if (worker.joinable()) worker.join();
    }

    static std::unique_ptr<Lane> open(const std::string& path) {
        auto lane = std::make_unique<Lane>();
        try {
            lane->file = absent::Reader::open(path);
            if (!lane->file) return nullptr;
            lane->track = lane->file->first(absent::Kind::Video);
            if (!lane->track) return nullptr;
            const bool software = std::getenv("AM_VIDEO_SOFTWARE") || std::getenv("AM_VIDEO_AV1_REFUSE");
            lane->decoder = absent::VideoDecoder::open(*lane->file, *lane->track, nullptr, software);
        } catch (...) {
            return nullptr;
        }
        if (!lane->decoder || !lane->decoder->frames()) return nullptr;
        lane->total = lane->decoder->frames();
        Lane* raw = lane.get();
        lane->worker = std::thread([raw] { raw->run(); });
        return lane;
    }

    absent::Frame decode(int64_t index) {
        try {
            return decoder->frame((uint32_t)index);
        } catch (...) {
            return {};
        }
    }

    void run() {
        std::unique_lock<std::mutex> hold(lock);
        while (!quit) {
            int64_t next = -1;
            if (want >= 0) {
                const int64_t end = std::min(want + kAhead, total - 1);
                for (int64_t i = want; i <= end && next < 0; ++i) if (!frames.count(i) && !failed.count(i)) next = i;
            }
            if (next < 0) {
                wake.wait(hold);
                continue;
            }
            hold.unlock();
            absent::Frame frame = decode(next);
            if (!frame) frame = decode(next);
            for (int64_t back = next - 1; !frame && back >= 0 && back >= next - kConceal; --back) frame = decode(back);
            hold.lock();
            if (frame) frames[next] = std::move(frame); else failed.insert(next);
            std::erase_if(frames, [&](const auto& e) { return e.first < want || e.first > want + kAhead; });
            std::erase_if(failed, [&](int64_t i) { return i < want || i > want + kAhead; });
            ready.notify_all();
        }
    }

    int show(jlong desktop, const std::string& uri, int64_t index, int waitMs, int64_t layer) {
        absent::Frame pick;
        bool have = false, gone = false;
        {
            std::unique_lock<std::mutex> hold(lock);
            want = index;
            wake.notify_one();
            ready.wait_for(hold, std::chrono::milliseconds(std::max(waitMs, 0)), [&] { return frames.count(index) || failed.count(index); });
            auto found = frames.find(index);
            if (found != frames.end()) {
                have = true;
                if (shownIndex != index || !shown) pick = found->second;
            } else gone = failed.count(index) != 0;
        }
        if (pick) {
            auto made = Image(DesktopContext(desktop), pick);
            if (made) {
                shown = std::move(made);
                shownIndex = index;
            } else gone = true;
        }
        at = index;
        if (shown) { if (layer) DesktopInstallLayer(desktop, uri, layer, shown); else DesktopInstall(desktop, uri, shown); }
        if (have && shownIndex == index) return 1;
        return gone ? -1 : 0;
    }
};

struct Video {
    std::string path;
    std::vector<std::unique_ptr<Lane>> lanes;
    std::vector<int64_t> times;
    uint64_t turns = 0;
    double info[7] = {};

    Lane* route(int64_t index, int64_t layer) {
        Lane* best = nullptr;
        int64_t gap = INT64_MAX;
        for (auto& lane : lanes) {
            const int64_t d = index - lane->at;
            if (lane->at >= 0 && d >= 0 && d <= 60 && d < gap) gap = d, best = lane.get();
        }
        for (auto& lane : lanes) if (!best && lane->owner == layer) best = lane.get();
        for (auto& lane : lanes) if (!best && lane->at < 0) best = lane.get();
        if (!best && lanes.size() < 3) {
            if (auto made = Lane::open(path)) best = lanes.emplace_back(std::move(made)).get();
        }
        if (!best) {
            best = lanes.front().get();
            for (auto& lane : lanes) if (lane->used < best->used) best = lane.get();
        }
        best->used = ++turns;
        best->owner = layer;
        return best;
    }

    int show(jlong desktop, const std::string& uri, int64_t t, int waitMs, int64_t layer) {
        auto it = std::upper_bound(times.begin(), times.end(), t);
        const int64_t index = it == times.begin() ? 0 : (it - times.begin()) - 1;
        return route(index, layer)->show(desktop, uri, index, waitMs, layer);
    }
};

Video* Open(const std::string& path) {
    auto first = Lane::open(path);
    if (!first) return nullptr;
    auto v = std::make_unique<Video>();
    v->path = path;
    const absent::Track& t = *first->track;
    v->times.reserve((size_t)first->total);
    for (int64_t i = 0; i < first->total; ++i) v->times.push_back((int64_t)std::llround((double)(first->decoder->pts((uint32_t)i) - t.mediaStart) * 1e6 / t.timescale));
    const double rate = t.rate();
    const double values[7] = {(double)t.width, (double)t.height, t.seconds() * 1e6, rate > 0 ? rate : 30.0, (double)(((t.rotation % 360) + 360) % 360),
        first->file->first(absent::Kind::Audio) ? 1.0 : 0.0, std::strcmp(first->decoder->backend(), "videotoolbox") == 0 ? 1.0 : 0.0};
    std::copy(values, values + 7, v->info);
    v->lanes.push_back(std::move(first));
    return v.release();
}

Video* video(jlong v) { return reinterpret_cast<Video*>(v); }

}

#define JNI(name) Java_app_absentmotion_pc_engine_DesktopNative_##name
extern "C" {
JNIEXPORT jlong JNICALL JNI(videoOpen)(JNIEnv* e, jobject, jstring path) { return reinterpret_cast<jlong>(Open(DesktopString(e, path))); }
JNIEXPORT jdoubleArray JNICALL JNI(videoInfo)(JNIEnv* e, jobject, jlong v) {
    auto result = e->NewDoubleArray(7);
    if (!result) return nullptr;
    e->SetDoubleArrayRegion(result, 0, 7, video(v)->info);
    return result;
}
JNIEXPORT jint JNICALL JNI(videoFrame)(JNIEnv* e, jobject, jlong h, jlong v, jstring uri, jlong timeUs, jboolean exact) {
    return video(v)->show(h, DesktopString(e, uri), timeUs, exact == JNI_TRUE ? 15000 : 30, 0);
}
JNIEXPORT jint JNICALL JNI(videoFrameLayer)(JNIEnv* e, jobject, jlong h, jlong v, jstring uri, jlong layer, jlong timeUs, jint waitMs) {
    return video(v)->show(h, DesktopString(e, uri), timeUs, waitMs, layer);
}
JNIEXPORT void JNICALL JNI(videoClose)(JNIEnv*, jobject, jlong v) { delete video(v); }
}
