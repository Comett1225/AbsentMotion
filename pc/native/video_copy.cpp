#include "video.h"
extern "C" {
#include <libavutil/hwcontext.h>
}
#ifndef _WIN32
#include <dlfcn.h>
#endif

AVBufferRef* VideoHwDevice() {
    static AVBufferRef* device = []() -> AVBufferRef* {
        AVBufferRef* made = nullptr;
#ifdef _WIN32
        const AVHWDeviceType type = AV_HWDEVICE_TYPE_D3D11VA;
#else
        for (const char* library : {"libva.so.2", "libva-drm.so.2", "libva-x11.so.2", "libX11.so.6", "libdrm.so.2"})
            if (!dlopen(library, RTLD_NOW | RTLD_GLOBAL)) return nullptr;
        const AVHWDeviceType type = AV_HWDEVICE_TYPE_VAAPI;
#endif
        return av_hwdevice_ctx_create(&made, type, nullptr, nullptr, 0) < 0 ? nullptr : made;
    }();
    return device;
}

sk_sp<SkImage> VideoHwImage(GrDirectContext*, const AVFrame*, SkYUVColorSpace) { return nullptr; }
