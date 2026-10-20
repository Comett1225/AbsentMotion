#include "interpolation.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <string>
#include <vector>

#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkM44.h"
#include "include/core/SkPaint.h"
#include "include/core/SkShader.h"
#include "include/core/SkSurface.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/Recorder.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AMInterp", __VA_ARGS__)

namespace am {
namespace {

using Clock = std::chrono::steady_clock;

const char* const kGrey = R"(
uniform shader a;
uniform shader b;
uniform float d;
float ga(float2 q) { return dot(a.eval(q).rgb, half3(0.2126, 0.7152, 0.0722)); }
float gb(float2 q) { return dot(b.eval(q).rgb, half3(0.2126, 0.7152, 0.0722)); }
half4 main(float2 p) {
    float2 c = p * d;
    float o = d * 0.25;
    float x = ga(c + float2(-o, -o)) + ga(c + float2(o, -o)) + ga(c + float2(-o, o)) + ga(c + float2(o, o));
    float y = gb(c + float2(-o, -o)) + gb(c + float2(o, -o)) + gb(c + float2(-o, o)) + gb(c + float2(o, o));
    return half4(half(x * 0.25), half(y * 0.25), 0, 1);
}
)";

const char* const kDown = R"(
uniform shader src;
half4 main(float2 p) { return src.eval(p * 2); }
)";

const char* const kDerivatives = R"(
uniform shader pyramid;
uniform shader flow;
uniform float scale;
uniform float gain;
uniform float2 size;
float first(float2 q, float dir) { half2 v = pyramid.eval(q).rg; return mix(v.r, v.g, dir); }
float second(float2 q, float dir) { half2 v = pyramid.eval(q).rg; return mix(v.g, v.r, dir); }
half4 main(float2 c) {
    float dir = step(size.x, c.x);
    float2 p = float2(c.x - size.x * dir, c.y);
    float4 v = flow.eval(p * scale) * gain;
    float2 w = mix(v.xy, v.zw, dir);
    float2 q = p + w;
    float2 dx = float2(1, 0);
    float2 dy = float2(0, 1);
    float ix = 0.25 * (second(q + dx, dir) - second(q - dx, dir) + first(p + dx, dir) - first(p - dx, dir));
    float iy = 0.25 * (second(q + dy, dir) - second(q - dy, dir) + first(p + dy, dir) - first(p - dy, dir));
    float it = second(q, dir) - first(p, dir);
    float seen = step(0, q.x) * step(0, q.y) * step(q.x, size.x) * step(q.y, size.y);
    return half4(half3(ix, iy, it - ix * w.x - iy * w.y) * half(seen), 1);
}
)";

const char* const kJacobiHead = R"(
uniform shader flow;
uniform shader terms;
uniform float scale;
uniform float gain;
uniform float alpha2;
uniform float2 size;
float4 source(float2 q) { return flow.eval(q * scale) * gain; }
float4 relax(float2 q, float4 m) {
    float2 c = clamp(q, float2(0.5), size - float2(0.5));
    float3 f = terms.eval(c).rgb;
    float3 g = terms.eval(c + float2(size.x, 0)).rgb;
    float kf = (f.x * m.x + f.y * m.y + f.z) / (alpha2 + f.x * f.x + f.y * f.y);
    float kb = (g.x * m.z + g.y * m.w + g.z) / (alpha2 + g.x * g.x + g.y * g.y);
    return float4(m.xy - f.xy * kf, m.zw - g.xy * kb);
}
half4 main(float2 p) {
)";

const char* const kWarp = R"(
uniform shader a;
uniform shader b;
uniform shader flow;
uniform float t;
uniform float d;
uniform float2 size;
float2 fwd(float2 x) { return flow.eval(x / d).rg * d; }
float2 bwd(float2 x) { return flow.eval(x / d).ba * d; }
float inside(float2 x) { return step(0, x.x) * step(0, x.y) * step(x.x, size.x) * step(x.y, size.y); }
half4 main(float2 p) {
    float2 pa = p - t * fwd(p);
    pa = p - t * fwd(pa);
    pa = p - t * fwd(pa);
    float2 pb = p - (1 - t) * bwd(p);
    pb = p - (1 - t) * bwd(pb);
    pb = p - (1 - t) * bwd(pb);
    float2 fa = fwd(pa);
    float2 gb = bwd(pb);
    float2 ga = bwd(pa + fa);
    float2 fb = fwd(pb + gb);
    float2 ra = pa + t * fa - p;
    float2 rb = pb + (1 - t) * gb - p;
    float la = dot(fa + ga, fa + ga) * inside(pa + fa) + dot(ra, ra);
    float lb = dot(gb + fb, gb + fb) * inside(pb + gb) + dot(rb, rb);
    float ka = 0.01 * (dot(fa, fa) + dot(ga, ga)) + 0.5 * d * d;
    float kb = 0.01 * (dot(gb, gb) + dot(fb, fb)) + 0.5 * d * d;
    float ca = (1 - smoothstep(ka, 4 * ka, la)) * inside(pa);
    float cb = (1 - smoothstep(kb, 4 * kb, lb)) * inside(pb);
    half4 blend = mix(a.eval(p), b.eval(p), half(t));
    float wa = max(1 - t, 0.01) * ca;
    float wb = max(t, 0.01) * cb;
    if (wa + wb <= 0) { return blend; }
    half4 warped = (a.eval(pa) * half(wa) + b.eval(pb) * half(wb)) / half(wa + wb);
    return mix(blend, warped, half(max(ca, cb)));
}
)";

const char* const kBlend = R"(
uniform shader a;
uniform shader b;
uniform float t;
half4 main(float2 p) { return mix(a.eval(p), b.eval(p), half(t)); }
)";

const char* const kMoshMotion = R"(
uniform shader flow;
uniform shader prior;
uniform float intensity;
uniform float bloom;
uniform float glitch;
uniform float cell;
uniform float salt;
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }
half4 main(float2 p) {
    float2 id = floor(p / cell);
    float2 c = (id + 0.5) * cell;
    float2 m = float2(flow.eval(c).ba) * intensity + float2(prior.eval(c).rg) * bloom;
    float h = hash(id + salt);
    if (h < glitch * 0.15) { m += (float2(hash(id + salt + 17.0), hash(id + salt + 41.0)) - 0.5) * cell * 6.0; }
    return half4(half2(m), 0, 1);
}
)";

const char* const kMoshImage = R"(
uniform shader state;
uniform shader frame;
uniform shader motion;
uniform float d;
uniform float decay;
half4 main(float2 p) {
    float2 m = floor(float2(motion.eval(p / d).rg) * d + 0.5);
    return mix(state.eval(p + m), frame.eval(p), half(decay));
}
)";

constexpr int kFused[] = {1, 2, 4};

std::string Name(int step, int x, int y) {
    auto number = [](int v) { return v < 0 ? "m" + std::to_string(-v) : std::to_string(v); };
    return "v" + std::to_string(step) + "_" + number(x) + "_" + number(y);
}

std::string JacobiSource(int steps) {
    std::string s = kJacobiHead;
    for (int step = 0; step <= steps; step++) {
        const int reach = steps - step;
        for (int y = -reach; y <= reach; y++) {
            for (int x = -reach; x <= reach; x++) {
                const int distance = std::abs(x) + std::abs(y);
                if (distance > reach || (reach - distance) % 2) continue;
                const std::string at = "p + float2(" + std::to_string(x) + ", " + std::to_string(y) + ")";
                s += "    float4 " + Name(step, x, y) + " = ";
                if (step == 0) {
                    s += "source(" + at + ");\n";
                } else {
                    s += "relax(" + at + ", 0.25 * (" + Name(step - 1, x + 1, y) + " + " + Name(step - 1, x - 1, y) + " + " +
                         Name(step - 1, x, y + 1) + " + " + Name(step - 1, x, y - 1) + "));\n";
                }
            }
        }
    }
    return s + "    return half4(" + Name(steps, 0, 0) + ");\n}\n";
}

struct Effects {
    sk_sp<SkRuntimeEffect> grey, down, derivatives, warp, blend;
    sk_sp<SkRuntimeEffect> jacobi[3];
    bool ok() const { return grey && down && derivatives && warp && blend && jacobi[0] && jacobi[1] && jacobi[2]; }
};

sk_sp<SkRuntimeEffect> Compile(const std::string& sksl) {
    auto result = SkRuntimeEffect::MakeForShader(SkString(sksl.c_str()));
    if (!result.effect) LOGE("%s", result.errorText.c_str());
    return result.effect;
}

const Effects& Shaders() {
    static const Effects effects{Compile(kGrey), Compile(kDown), Compile(kDerivatives), Compile(kWarp), Compile(kBlend),
                                 {Compile(JacobiSource(kFused[0])), Compile(JacobiSource(kFused[1])), Compile(JacobiSource(kFused[2]))}};
    return effects;
}

sk_sp<SkShader> Sample(const sk_sp<SkImage>& image, SkFilterMode filter = SkFilterMode::kLinear) {
    return image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(filter));
}

double Since(Clock::time_point start) { return std::chrono::duration<double, std::milli>(Clock::now() - start).count(); }

class Gpu {
public:
    Gpu(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder) : fContext(context), fRecorder(recorder) {}

    sk_sp<SkSurface> field(int w, int h) {
        return SkSurfaces::RenderTarget(fRecorder, SkImageInfo::Make(w, h, kRGBA_F16_SkColorType, kPremul_SkAlphaType, nullptr));
    }

    sk_sp<SkSurface> picture(int w, int h) {
        return SkSurfaces::RenderTarget(fRecorder, SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kPremul_SkAlphaType,
                                                                     SkColorSpace::MakeSRGB()));
    }

    sk_sp<SkImage> draw(const sk_sp<SkSurface>& target, const SkRuntimeShaderBuilder& builder) {
        if (!target || fFailed) { fFailed = true; return nullptr; }
        SkPaint paint;
        paint.setShader(builder.makeShader());
        paint.setBlendMode(SkBlendMode::kSrc);
        target->getCanvas()->drawPaint(paint);
        fPasses++;
        return SkSurfaces::AsImage(target);
    }

    bool submit() {
        auto recording = fRecorder->snap();
        if (!fFailed && (!recording || !fContext->insertRecording({recording.get()}) ||
                         !fContext->submit(skgpu::graphite::SyncToCpu::kYes))) {
            fFailed = true;
        }
        return !fFailed;
    }

    bool failed() const { return fFailed; }
    int passes() const { return fPasses; }

private:
    skgpu::graphite::Context* fContext;
    skgpu::graphite::Recorder* fRecorder;
    bool fFailed = false;
    int fPasses = 0;
};

std::vector<sk_sp<SkImage>> Pyramid(Gpu& gpu, const Effects& fx, const sk_sp<SkImage>& a, const sk_sp<SkImage>& b,
                                    int divisor, int levels) {
    int w = (a->width() + divisor - 1) / divisor, h = (a->height() + divisor - 1) / divisor;
    SkRuntimeShaderBuilder grey(fx.grey);
    grey.child("a") = Sample(a);
    grey.child("b") = Sample(b);
    grey.uniform("d") = (float)divisor;
    std::vector<sk_sp<SkImage>> pyramid{gpu.draw(gpu.field(w, h), grey)};
    while ((int)pyramid.size() < levels && std::min(w, h) >= 32 && pyramid.back()) {
        w = (w + 1) / 2;
        h = (h + 1) / 2;
        SkRuntimeShaderBuilder down(fx.down);
        down.child("src") = Sample(pyramid.back());
        pyramid.push_back(gpu.draw(gpu.field(w, h), down));
    }
    return pyramid;
}

int FusedIndex(int remaining, int most) {
    for (int i = 2; i > 0; i--) {
        if (kFused[i] <= std::min(remaining, most)) return i;
    }
    return 0;
}

}

sk_sp<SkImage> EstimateFlow(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& a,
                            const sk_sp<SkImage>& b, const InterpolationParams& params, FlowStats* stats) {
    const Effects& fx = Shaders();
    if (!fx.ok() || !a || !b) return nullptr;
    context->submit(skgpu::graphite::SyncToCpu::kYes);
    const auto start = Clock::now();
    Gpu gpu(context, recorder);
    const auto pyramid = Pyramid(gpu, fx, a, b, std::max(params.divisor, 1), std::max(params.levels, 1));
    sk_sp<SkImage> flow;
    for (int level = (int)pyramid.size() - 1; level >= 0 && !gpu.failed() && pyramid[level]; level--) {
        const sk_sp<SkImage>& grey = pyramid[level];
        const int w = grey->width(), h = grey->height();
        const SkV2 size{(float)w, (float)h};
        sk_sp<SkSurface> terms = gpu.field(2 * w, h), ping = gpu.field(w, h), pong = gpu.field(w, h);
        float scale = flow ? 0.5f : 1.0f, gain = flow ? 2.0f : 0.0f;
        sk_sp<SkImage> current = flow ? flow : grey;
        const int iterations = std::clamp(params.iterations << level, 1, 64);
        for (int warp = 0; warp < std::max(params.warps, 1) && current; warp++) {
            SkRuntimeShaderBuilder d(fx.derivatives);
            d.child("pyramid") = Sample(grey);
            d.child("flow") = Sample(current);
            d.uniform("scale") = scale;
            d.uniform("gain") = gain;
            d.uniform("size") = size;
            const sk_sp<SkImage> derivatives = gpu.draw(terms, d);
            for (int done = 0; done < iterations && derivatives && current;) {
                const int fused = FusedIndex(iterations - done, params.fuse);
                SkRuntimeShaderBuilder j(fx.jacobi[fused]);
                j.child("flow") = Sample(current, scale == 1.0f ? SkFilterMode::kNearest : SkFilterMode::kLinear);
                j.child("terms") = Sample(derivatives, SkFilterMode::kNearest);
                j.uniform("scale") = scale;
                j.uniform("gain") = gain;
                j.uniform("alpha2") = params.alpha * params.alpha;
                j.uniform("size") = size;
                current = gpu.draw(ping, j);
                std::swap(ping, pong);
                scale = 1.0f;
                gain = 1.0f;
                done += kFused[fused];
            }
        }
        flow = current;
    }
    const bool ok = gpu.submit() && flow;
    if (stats) *stats = {gpu.passes(), (int)pyramid.size(), Since(start)};
    if (!ok) LOGE("flow estimation failed");
    return ok ? flow : nullptr;
}

sk_sp<SkImage> SynthesizeFrame(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& a,
                               const sk_sp<SkImage>& b, const sk_sp<SkImage>& flow, float t, int divisor, double* ms) {
    const Effects& fx = Shaders();
    if (!fx.ok() || !a || !b) return nullptr;
    const auto start = Clock::now();
    Gpu gpu(context, recorder);
    SkRuntimeShaderBuilder builder(flow ? fx.warp : fx.blend);
    builder.child("a") = Sample(a);
    builder.child("b") = Sample(b);
    builder.uniform("t") = t;
    if (flow) {
        builder.child("flow") = Sample(flow);
        builder.uniform("d") = (float)std::max(divisor, 1);
        builder.uniform("size") = SkV2{(float)a->width(), (float)a->height()};
    }
    sk_sp<SkImage> out = gpu.draw(gpu.picture(a->width(), a->height()), builder);
    if (!gpu.submit() || !out) {
        LOGE("frame synthesis failed");
        return nullptr;
    }
    if (ms) *ms = Since(start);
    return out;
}

sk_sp<SkImage> MoshStep(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder, const sk_sp<SkImage>& state,
                        const sk_sp<SkImage>& prior, const sk_sp<SkImage>& frame, const sk_sp<SkImage>& flow, const MoshParams& params,
                        float salt, sk_sp<SkImage>* motion) {
    static const sk_sp<SkRuntimeEffect> moshMotion = Compile(kMoshMotion), moshImage = Compile(kMoshImage);
    if (!moshMotion || !moshImage || !state || !frame) return nullptr;
    const int divisor = std::max(params.flow.divisor, 1);
    const int fw = flow ? flow->width() : (frame->width() + divisor - 1) / divisor;
    const int fh = flow ? flow->height() : (frame->height() + divisor - 1) / divisor;
    const sk_sp<SkShader> none = SkShaders::Color(SK_ColorTRANSPARENT);
    Gpu gpu(context, recorder);
    SkRuntimeShaderBuilder field(moshMotion);
    field.child("flow") = flow ? Sample(flow) : none;
    field.child("prior") = prior ? Sample(prior, SkFilterMode::kNearest) : none;
    field.uniform("intensity") = params.intensity;
    field.uniform("bloom") = params.bloom;
    field.uniform("glitch") = params.glitch;
    field.uniform("cell") = std::max(params.block / divisor, 1.0f);
    field.uniform("salt") = salt;
    sk_sp<SkImage> moved = gpu.draw(gpu.field(fw, fh), field);
    SkRuntimeShaderBuilder image(moshImage);
    image.child("state") = Sample(state);
    image.child("frame") = Sample(frame);
    image.child("motion") = moved ? Sample(moved, params.block >= 2 * divisor ? SkFilterMode::kNearest : SkFilterMode::kLinear) : none;
    image.uniform("d") = (float)divisor;
    image.uniform("decay") = std::clamp(params.decay, 0.0f, 1.0f);
    sk_sp<SkImage> out = gpu.draw(gpu.picture(state->width(), state->height()), image);
    if (!gpu.submit() || !out || !moved) {
        LOGE("datamosh step failed");
        return nullptr;
    }
    *motion = moved;
    return out;
}

}
