#include "video.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkYUVAInfo.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrYUVABackendTextures.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendSurface.h"
#include "include/gpu/ganesh/mtl/GrMtlTypes.h"
#import <CoreVideo/CoreVideo.h>
#import <Metal/Metal.h>
extern "C" {
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
}

AVBufferRef* VideoHwDevice() {
    static AVBufferRef* device = [] {
        AVBufferRef* made = nullptr;
        return av_hwdevice_ctx_create(&made, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, nullptr, nullptr, 0) < 0 ? nullptr : made;
    }();
    return device;
}

static CVMetalTextureCacheRef Cache() {
    static CVMetalTextureCacheRef cache = [] {
        CVMetalTextureCacheRef made = nullptr;
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device) CVMetalTextureCacheCreate(kCFAllocatorDefault, nullptr, device, nullptr, &made);
        return made;
    }();
    return cache;
}

struct HeldFrame {
    CVMetalTextureRef planes[2] = {};
    AVFrame* frame = nullptr;
};

static void ReleaseFrame(void* context) {
    auto* held = static_cast<HeldFrame*>(context);
    for (auto plane : held->planes) if (plane) CFRelease(plane);
    av_frame_free(&held->frame);
    delete held;
}

static bool Deep(OSType type) {
    switch (type) {
        case kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange:
        case kCVPixelFormatType_420YpCbCr10BiPlanarFullRange:
        case kCVPixelFormatType_422YpCbCr10BiPlanarVideoRange:
        case kCVPixelFormatType_422YpCbCr10BiPlanarFullRange:
        case kCVPixelFormatType_444YpCbCr10BiPlanarVideoRange:
        case kCVPixelFormatType_444YpCbCr10BiPlanarFullRange:
        case kCVPixelFormatType_422YpCbCr16BiPlanarVideoRange:
        case kCVPixelFormatType_444YpCbCr16BiPlanarVideoRange:
            return true;
        default:
            return false;
    }
}

sk_sp<SkImage> VideoHwImage(GrDirectContext* context, const AVFrame* frame, SkYUVColorSpace space) {
    if (!context || context->backend() != GrBackendApi::kMetal || frame->format != AV_PIX_FMT_VIDEOTOOLBOX || !Cache()) return nullptr;
    auto pixels = (CVPixelBufferRef)frame->data[3];
    if (!pixels || CVPixelBufferGetPlaneCount(pixels) != 2) return nullptr;
    const bool deep = Deep(CVPixelBufferGetPixelFormatType(pixels));
    const int width = (int)CVPixelBufferGetWidth(pixels), height = (int)CVPixelBufferGetHeight(pixels);
    const int chromaWidth = (int)CVPixelBufferGetWidthOfPlane(pixels, 1), chromaHeight = (int)CVPixelBufferGetHeightOfPlane(pixels, 1);
    SkYUVAInfo::Subsampling subsampling;
    if (chromaWidth == width && chromaHeight == height) subsampling = SkYUVAInfo::Subsampling::k444;
    else if (chromaWidth == (width + 1) / 2 && chromaHeight == height) subsampling = SkYUVAInfo::Subsampling::k422;
    else if (chromaWidth == (width + 1) / 2 && chromaHeight == (height + 1) / 2) subsampling = SkYUVAInfo::Subsampling::k420;
    else return nullptr;
    auto* held = new HeldFrame;
    held->frame = av_frame_clone(frame);
    GrBackendTexture textures[SkYUVAInfo::kMaxPlanes];
    for (int i = 0; i < 2; ++i) {
        const MTLPixelFormat format = i == 0 ? (deep ? MTLPixelFormatR16Unorm : MTLPixelFormatR8Unorm) : (deep ? MTLPixelFormatRG16Unorm : MTLPixelFormatRG8Unorm);
        const size_t w = CVPixelBufferGetWidthOfPlane(pixels, i), h = CVPixelBufferGetHeightOfPlane(pixels, i);
        if (CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, Cache(), pixels, nullptr, format, w, h, i, &held->planes[i]) != kCVReturnSuccess) {
            ReleaseFrame(held);
            return nullptr;
        }
        GrMtlTextureInfo info;
        info.fTexture.retain((__bridge GrMTLHandle)CVMetalTextureGetTexture(held->planes[i]));
        textures[i] = GrBackendTextures::MakeMtl((int)w, (int)h, skgpu::Mipmapped::kNo, info);
    }
    const SkYUVAInfo yuva({width, height}, SkYUVAInfo::PlaneConfig::kY_UV, subsampling, space);
    const GrYUVABackendTextures planes(yuva, textures, kTopLeft_GrSurfaceOrigin);
    auto image = SkImages::TextureFromYUVATextures(context, planes, SkColorSpace::MakeSRGB(), ReleaseFrame, held);
    CVMetalTextureCacheFlush(Cache(), 0);
    return image;
}
