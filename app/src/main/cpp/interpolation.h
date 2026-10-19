#pragma once

#include "engine.h"
#include "include/core/SkImage.h"

#include <cstdint>
#include <tuple>

namespace skgpu::graphite {
class Context;
class Recorder;
}

namespace am {

class FrameInterpolator {
public:
    sk_sp<SkImage> run(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& a,
                       const sk_sp<SkImage>& b, float t, const InterpolationParams& params);

private:
    using Key = std::tuple<uint32_t, uint32_t, int, int, int, int, float>;
    Key fKey{};
    sk_sp<SkImage> fForward, fBackward;
};

}
