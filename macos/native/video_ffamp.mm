#include "video.h"
#include "absent/media.h"
#include <memory>
#include "include/core/SkColorSpace.h"
#include "include/core/SkYUVAInfo.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrYUVABackendTextures.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendSurface.h"
#include "include/gpu/ganesh/mtl/GrMtlTypes.h"
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#import <objc/runtime.h>
#import <Metal/Metal.h>

@interface AMFrameKeeper : NSObject {
@public
    std::shared_ptr<void> pixels;
}
@end

@implementation AMFrameKeeper
@end

namespace {

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
};

void Release(void* context) {
    auto* held = static_cast<Held*>(context);
    for (auto plane : held->planes) if (plane) CFRelease(plane);
    delete held;
    if (Cache()) CVMetalTextureCacheFlush(Cache(), 0);
}

const char kKeeperKey = 0;

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
    AMFrameKeeper* keeper = [AMFrameKeeper new];
    keeper->pixels = frame.native;
    GrBackendTexture textures[SkYUVAInfo::kMaxPlanes];
    for (int i = 0; i < 2; ++i) {
        const MTLPixelFormat format = i == 0 ? (deep ? MTLPixelFormatR16Unorm : MTLPixelFormatR8Unorm) : (deep ? MTLPixelFormatRG16Unorm : MTLPixelFormatRG8Unorm);
        const size_t w = CVPixelBufferGetWidthOfPlane(pixels, i), h = CVPixelBufferGetHeightOfPlane(pixels, i);
        if (CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, Cache(), pixels, nullptr, format, w, h, i, &held->planes[i]) != kCVReturnSuccess) {
            Release(held);
            return nullptr;
        }
        objc_setAssociatedObject(CVMetalTextureGetTexture(held->planes[i]), &kKeeperKey, keeper, OBJC_ASSOCIATION_RETAIN);
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

bool Alpha(const absent::Frame& frame) {
    CFTypeRef veil = CVBufferCopyAttachment(static_cast<CVPixelBufferRef>(frame.native.get()), CFSTR("absent.alpha"), nullptr);
    if (veil) CFRelease(veil);
    return veil != nullptr;
}

}

sk_sp<SkImage> FfampImage(GrDirectContext* context, const absent::Frame& frame) {
    const bool alpha = Alpha(frame);
    if (!Hdr(frame) && !alpha) if (auto image = Direct(context, frame)) return image;
    return FfampCopy(context, frame, alpha);
}
