#pragma once

#include "engine.h"
#include "include/core/SkImage.h"

namespace skgpu::graphite {
class Context;
class Recorder;
}

namespace am {

struct FlowStats {
    int passes = 0;
    int levels = 0;
    double ms = 0;
};

sk_sp<SkImage> EstimateFlow(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& a,
                            const sk_sp<SkImage>& b, const InterpolationParams& params, FlowStats* stats);

sk_sp<SkImage> SynthesizeFrame(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& a,
                               const sk_sp<SkImage>& b, const sk_sp<SkImage>& flow, float t, int divisor, double* ms);

}
