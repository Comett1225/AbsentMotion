#include "gpu.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendContext.h"
#include "include/gpu/ganesh/mtl/GrMtlDirectContext.h"
#import <Metal/Metal.h>

struct MetalGpu : DesktopGpu {
    sk_sp<GrDirectContext> gpu;
    GrDirectContext* context() override { return gpu.get(); }
};

std::unique_ptr<DesktopGpu> CreateDesktopGpu() {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    id<MTLCommandQueue> queue = [device newCommandQueue];
    if (!device || !queue) return nullptr;
    GrMtlBackendContext backend;
    backend.fDevice.retain((__bridge GrMTLHandle)device);
    backend.fQueue.retain((__bridge GrMTLHandle)queue);
    auto result = std::make_unique<MetalGpu>();
    result->gpu = GrDirectContexts::MakeMetal(backend);
    return result;
}
