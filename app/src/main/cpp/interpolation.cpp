#include "interpolation.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <vector>

#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkM44.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/Recorder.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AMInterp", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AMInterp", __VA_ARGS__)

namespace am {
namespace {

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
uniform float dir;
uniform float2 size;
float first(float2 q) { half2 v = pyramid.eval(q).rg; return mix(v.r, v.g, dir); }
float second(float2 q) { half2 v = pyramid.eval(q).rg; return mix(v.g, v.r, dir); }
half4 main(float2 p) {
    float2 w = flow.eval(p * scale).rg * gain;
    float2 q = p + w;
    float2 dx = float2(1, 0);
    float2 dy = float2(0, 1);
    float ix = 0.25 * (second(q + dx) - second(q - dx) + first(p + dx) - first(p - dx));
    float iy = 0.25 * (second(q + dy) - second(q - dy) + first(p + dy) - first(p - dy));
    float it = second(q) - first(p);
    float seen = step(0, q.x) * step(0, q.y) * step(q.x, size.x) * step(q.y, size.y);
    return half4(half3(ix, iy, it - ix * w.x - iy * w.y) * half(seen), 1);
}
)";

const char* const kJacobi = R"(
uniform shader flow;
uniform shader derivatives;
uniform float scale;
uniform float gain;
uniform float alpha2;
half4 main(float2 p) {
    float2 m = (flow.eval((p + float2(1, 0)) * scale).rg + flow.eval((p - float2(1, 0)) * scale).rg +
                flow.eval((p + float2(0, 1)) * scale).rg + flow.eval((p - float2(0, 1)) * scale).rg) * (0.25 * gain);
    float3 g = derivatives.eval(p).rgb;
    float k = (g.x * m.x + g.y * m.y + g.z) / (alpha2 + g.x * g.x + g.y * g.y);
    return half4(half2(m - g.xy * k), 0, 1);
}
)";

const char* const kWarp = R"(
uniform shader a;
uniform shader b;
uniform shader forward;
uniform shader backward;
uniform float t;
uniform float d;
uniform float2 size;
float2 fwd(float2 x) { return forward.eval(x / d).rg * d; }
float2 bwd(float2 x) { return backward.eval(x / d).rg * d; }
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

struct Effects {
    sk_sp<SkRuntimeEffect> grey, down, derivatives, jacobi, warp, blend;
    bool ok() const { return grey && down && derivatives && jacobi && warp && blend; }
};

sk_sp<SkRuntimeEffect> Compile(const char* sksl) {
    auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
    if (!result.effect) LOGE("%s", result.errorText.c_str());
    return result.effect;
}

const Effects& Shaders() {
    static const Effects effects{Compile(kGrey), Compile(kDown), Compile(kDerivatives), Compile(kJacobi),
                                 Compile(kWarp), Compile(kBlend)};
    return effects;
}

sk_sp<SkShader> Sample(const sk_sp<SkImage>& image) {
    return image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kLinear));
}

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
        auto recording = fRecorder->snap();
        if (!recording || !fContext->insertRecording({recording.get()})) fFailed = true;
        fPasses++;
        return SkSurfaces::AsImage(target);
    }

    double finish() {
        if (!fContext->submit(skgpu::graphite::SyncToCpu::kYes)) fFailed = true;
        const auto now = std::chrono::steady_clock::now();
        const double ms = std::chrono::duration<double, std::milli>(now - fStart).count();
        fStart = now;
        return ms;
    }

    bool failed() const { return fFailed; }
    int passes() const { return fPasses; }

private:
    skgpu::graphite::Context* fContext;
    skgpu::graphite::Recorder* fRecorder;
    std::chrono::steady_clock::time_point fStart = std::chrono::steady_clock::now();
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

sk_sp<SkImage> Estimate(Gpu& gpu, const Effects& fx, const std::vector<sk_sp<SkImage>>& pyramid, float dir,
                        const InterpolationParams& params) {
    sk_sp<SkImage> flow;
    for (int level = (int)pyramid.size() - 1; level >= 0 && !gpu.failed(); level--) {
        const sk_sp<SkImage>& grey = pyramid[level];
        const int w = grey->width(), h = grey->height();
        sk_sp<SkSurface> derivatives = gpu.field(w, h), ping = gpu.field(w, h), pong = gpu.field(w, h);
        float scale = flow ? 0.5f : 1.0f, gain = flow ? 2.0f : 0.0f;
        sk_sp<SkImage> current = flow ? flow : grey;
        const int iterations = std::clamp(params.iterations << level, 1, 64);
        for (int warp = 0; warp < std::max(params.warps, 1); warp++) {
            SkRuntimeShaderBuilder d(fx.derivatives);
            d.child("pyramid") = Sample(grey);
            d.child("flow") = Sample(current);
            d.uniform("scale") = scale;
            d.uniform("gain") = gain;
            d.uniform("dir") = dir;
            d.uniform("size") = SkV2{(float)w, (float)h};
            sk_sp<SkImage> terms = gpu.draw(derivatives, d);
            for (int i = 0; i < iterations && terms; i++) {
                SkRuntimeShaderBuilder j(fx.jacobi);
                j.child("flow") = Sample(current);
                j.child("derivatives") = Sample(terms);
                j.uniform("scale") = scale;
                j.uniform("gain") = gain;
                j.uniform("alpha2") = params.alpha * params.alpha;
                current = gpu.draw(ping, j);
                std::swap(ping, pong);
                scale = 1.0f;
                gain = 1.0f;
                if (!current) return nullptr;
            }
        }
        flow = current;
    }
    return gpu.failed() ? nullptr : flow;
}

}

sk_sp<SkImage> FrameInterpolator::run(skgpu::graphite::Context* context, skgpu::graphite::Recorder* recorder,
                                      const sk_sp<SkImage>& a, const sk_sp<SkImage>& b, float t,
                                      const InterpolationParams& params) {
    const Effects& fx = Shaders();
    if (!fx.ok() || !a || !b) return nullptr;
    Gpu gpu(context, recorder);
    gpu.finish();
    const bool flow = params.mode == 2;
    const int divisor = std::max(params.divisor, 1);
    double estimate = -1;
    int levels = 0;
    if (flow) {
        const Key key{a->uniqueID(), b->uniqueID(), divisor, params.levels, params.iterations, params.warps, params.alpha};
        if (!fForward || !fBackward || key != fKey) {
            fForward = fBackward = nullptr;
            auto pyramid = Pyramid(gpu, fx, a, b, divisor, std::max(params.levels, 1));
            levels = (int)pyramid.size();
            auto forward = Estimate(gpu, fx, pyramid, 0, params);
            auto backward = Estimate(gpu, fx, pyramid, 1, params);
            estimate = gpu.finish();
            if (gpu.failed() || !forward || !backward) {
                LOGE("flow estimation failed");
                return nullptr;
            }
            fKey = key;
            fForward = std::move(forward);
            fBackward = std::move(backward);
        }
    }
    const int estimatePasses = gpu.passes();
    sk_sp<SkImage> out;
    if (flow) {
        SkRuntimeShaderBuilder w(fx.warp);
        w.child("a") = Sample(a);
        w.child("b") = Sample(b);
        w.child("forward") = Sample(fForward);
        w.child("backward") = Sample(fBackward);
        w.uniform("t") = t;
        w.uniform("d") = (float)divisor;
        w.uniform("size") = SkV2{(float)a->width(), (float)a->height()};
        out = gpu.draw(gpu.picture(a->width(), a->height()), w);
    } else {
        SkRuntimeShaderBuilder m(fx.blend);
        m.child("a") = Sample(a);
        m.child("b") = Sample(b);
        m.uniform("t") = t;
        out = gpu.draw(gpu.picture(a->width(), a->height()), m);
    }
    const double synthesis = gpu.finish();
    if (gpu.failed() || !out) {
        LOGE("frame synthesis failed");
        return nullptr;
    }
    LOGI("mode=%d size=%dx%d divisor=%d levels=%d iterations=%d warps=%d alpha=%.4f t=%.4f passes=%d estimate=%.2f synthesis=%.2f",
         params.mode, a->width(), a->height(), divisor, levels, params.iterations, params.warps, params.alpha, t,
         estimatePasses, estimate, synthesis);
    return out;
}

}
