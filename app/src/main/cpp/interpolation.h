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

sk_sp<SkImage> MoshStep(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& state,
                        const sk_sp<SkImage>& prior, const sk_sp<SkImage>& frame, const sk_sp<SkImage>& flow, const MoshParams& params,
                        float salt, sk_sp<SkImage>* motion);

sk_sp<SkImage> SynthesizeFrame(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& a,
                               const sk_sp<SkImage>& b, const sk_sp<SkImage>& flow, float t, int divisor, double* ms,
                               const sk_sp<SkImage>& before = nullptr, const sk_sp<SkImage>& after = nullptr);

sk_sp<SkImage> MotionBlur(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& image,
                          const sk_sp<SkImage>& flow, float t, float length, int divisor);

}
