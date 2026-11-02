#include "video.h"
extern "C" {
#include <libavutil/hwcontext.h>
}

AVBufferRef* VideoHwDevice() {
    static AVBufferRef* device = [] {
        AVBufferRef* made = nullptr;
#ifdef _WIN32
        const AVHWDeviceType type = AV_HWDEVICE_TYPE_D3D11VA;
#else
        const AVHWDeviceType type = AV_HWDEVICE_TYPE_VAAPI;
#endif
        return av_hwdevice_ctx_create(&made, type, nullptr, nullptr, 0) < 0 ? nullptr : made;
    }();
    return device;
}

sk_sp<SkImage> VideoHwImage(GrDirectContext*, const AVFrame*, SkYUVColorSpace) { return nullptr; }
