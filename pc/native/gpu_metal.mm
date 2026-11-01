#include "gpu.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendContext.h"
#include "include/gpu/ganesh/mtl/GrMtlDirectContext.h"
#import <Metal/Metal.h>

sk_sp<GrDirectContext> CreateDesktopGpu() {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    id<MTLCommandQueue> queue = [device newCommandQueue];
    if (!device || !queue) return nullptr;
    GrMtlBackendContext backend;
    backend.fDevice.retain((__bridge GrMTLHandle)device);
    backend.fQueue.retain((__bridge GrMTLHandle)queue);
    return GrDirectContexts::MakeMetal(backend);
}
