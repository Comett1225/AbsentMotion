#include "platform_log.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string_view>

#include "include/core/SkBlender.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPathMeasure.h"
#include "include/core/SkM44.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkSurface.h"
#include "include/effects/SkImageFilters.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/pathops/SkPathOps.h"
#include "drawing.h"
#include "scene.h"
#include "text.h"

namespace am {
namespace {

std::string AttrOf(const XmlNode& n, const char* key) {
    const char* v = n.attr(key);
    return v ? v : "";
}

SkColor4f ColorAt(const Anim& a, float t, SkColor4f fallback, float alpha = 1) {
    if (a.empty()) return {fallback.fR, fallback.fG, fallback.fB, std::clamp(fallback.fA * alpha, 0.0f, 1.0f)};
    return {a.get(1, t, fallback.fR), a.get(2, t, fallback.fG), a.get(3, t, fallback.fB),
            std::clamp(a.get(0, t, fallback.fA) * alpha, 0.0f, 1.0f)};
}

const SkColor4f kWhite = {1, 1, 1, 1}, kBlack = {0, 0, 0, 1};

const char* kBlendHelpers = R"(
float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
float hiOf(float3 c) { return max(c.r, max(c.g, c.b)); }
float loOf(float3 c) { return min(c.r, min(c.g, c.b)); }
float3 hueShape(float3 c) {
    float hi = hiOf(c), lo = loOf(c);
    return hi > lo ? (c - lo) / (hi - lo) : float3(1.0);
}
float hardChannel(float light, float base) {
    return light <= 0.5 ? base * 2.0 * light : 1.0 - (1.0 - base) * (2.0 - 2.0 * light);
}
)";

struct ShaderMode {
    const char* id;
    bool separable;
    const char* code;
};

const ShaderMode kShaderModes[] = {
    {"multiply", true, "return s * b;"},
    {"diff", true, "return abs(b - s);"},
    {"exclusion", true, "return s + b - 2.0 * s * b;"},
    {"lighten", true, "return max(s, b);"},
    {"darken", true, "return min(s, b);"},
    {"lighter-color", false, "return luma(s) > luma(b) ? s : b;"},
    {"darker-color", false, "return luma(s) < luma(b) ? s : b;"},
    {"color-burn", true, "return b >= 1.0 ? 1.0 : 1.0 - min(1.0, (1.0 - b) / max(s, 1e-6));"},
    {"linear-burn", true, "return s + b - 1.0;"},
    {"color-dodge", true, "return b <= 0.0 ? 0.0 : min(1.0, b / max(1.0 - s, 1e-6));"},
    {"linear-dodge", true, "return s + b;"},
    {"overlay", true, "return hardChannel(b, s);"},
    {"soft-light", true, "return 0.5 * (b + hardChannel(s, b));"},
    {"hard-light", true, "return hardChannel(s, b);"},
    {"soft-overlay", true, "return 0.5 * (b + hardChannel(b, s));"},
    {"vivid-light", true, "return s <= 0.5 ? (b >= 1.0 ? 1.0 : 1.0 - min(1.0, (1.0 - b) / max(2.0 * s, 1e-6))) : (b <= 0.0 ? 0.0 : min(1.0, b / max(2.0 - 2.0 * s, 1e-6)));"},
    {"linear-light", true, "return b + 2.0 * s - 1.0;"},
    {"pin-light", true, "return s <= 0.5 ? min(b, 2.0 * s) : max(b, 2.0 * s - 1.0);"},
    {"hue", false, "float hi = hiOf(b), lo = loOf(b); return lo + (hi - lo) * hueShape(s);"},
    {"saturation", false,
     "float hi = hiOf(s); float sat = hi > 0.0 ? (hi - loOf(s)) / hi : 0.0;"
     " return hiOf(b) * (1.0 - sat * (1.0 - hueShape(b)));"},
    {"color", false, "return s + (luma(b) - luma(s));"},
    {"luminance", false, "return b + (luma(s) - luma(b));"},
    {"subtract", true, "return b - s;"},
    {"divide", true, "return b / max(s, 1e-6);"},
};

sk_sp<SkBlender> ShaderBlender(const std::string& id) {
    static std::map<std::string, sk_sp<SkBlender>> cache;
    if (auto hit = cache.find(id); hit != cache.end()) return hit->second;
    sk_sp<SkBlender> blender;
    for (const ShaderMode& mode : kShaderModes) {
        if (id != mode.id) continue;
        std::string sksl = kBlendHelpers;
        if (mode.separable) {
            sksl += std::string("float mixChannel(float s, float b) { ") + mode.code + " }\n";
            sksl += "float3 mixColor(float3 s, float3 b) {\n"
                    "    return float3(mixChannel(s.r, b.r), mixChannel(s.g, b.g), mixChannel(s.b, b.b));\n}\n";
        } else {
            sksl += std::string("float3 mixColor(float3 s, float3 b) { ") + mode.code + " }\n";
        }
        sksl += R"(
half4 main(half4 src, half4 dst) {
    float sa = float(src.a), da = float(dst.a);
    if (sa <= 0.0) return dst;
    float3 s = clamp(float3(src.rgb) / sa, 0.0, 1.0);
    float3 b = da > 0.0 ? clamp(float3(dst.rgb) / da, 0.0, 1.0) : float3(0.0);
    float3 mixed = clamp(mixColor(s, b), 0.0, 1.0);
    float alpha = sa + da * (1.0 - sa);
    float3 rgb = (1.0 - da) * float3(src.rgb) + (1.0 - sa) * float3(dst.rgb) + sa * da * mixed;
    return half4(half3(min(rgb, float3(alpha))), half(alpha));
})";
        auto result = SkRuntimeEffect::MakeForBlender(SkString(sksl.c_str()));
        if (result.effect) blender = result.effect->makeBlender(nullptr);
        else __android_log_print(ANDROID_LOG_ERROR, "AMStyles", "blend %s: %s", id.c_str(), result.errorText.c_str());
        break;
    }
    cache[id] = blender;
    return blender;
}

void SetBlend(SkPaint* paint, const std::string& id) {
    if (id.empty() || id == "normal") return;
    if (id == "screen") paint->setBlendMode(SkBlendMode::kScreen);
    else if (id == "mask") paint->setBlendMode(SkBlendMode::kDstIn);
    else if (id == "exclude") paint->setBlendMode(SkBlendMode::kDstOut);
    else if (id == "clipping-mask") paint->setBlendMode(SkBlendMode::kSrcATop);
    else if (auto blender = ShaderBlender(id)) paint->setBlender(blender);
}

bool IsNormal(const std::string& id) { return id.empty() || id == "normal"; }

sk_sp<SkShader> GradientShader(const Gradient& g, const SkRect& bounds, float alpha) {
    static sk_sp<SkRuntimeEffect> effect = [] {
        auto result = SkRuntimeEffect::MakeForShader(SkString(R"(
uniform float4 c0;
uniform float4 c1;
uniform float2 p0;
uniform float2 p1;
uniform float4 box;
uniform float kind;
half4 main(float2 xy) {
    float2 st = (xy - box.xy) / box.zw;
    float f;
    if (kind < 0.5) {
        float2 b = p1 - p0;
        float u = clamp(dot(st - p0, b) / max(dot(b, b), 1.0e-9), 0.0, 1.0);
        f = smoothstep(0.0, 1.0, u);
    } else if (kind < 1.5) {
        f = smoothstep(0.0, 1.0, distance(st, p0) / max(distance(p1, p0), 1.0e-6));
    } else {
        float2 xy2 = st - p0;
        float2 zw = p1 - p0;
        float a = atan(xy2.x, xy2.y);
        float b = atan(zw.x, zw.y);
        f = mod((a - b) + 6.28, 6.28) / 6.28;
    }
    return half4(mix(c0, c1, f));
})"));
        if (!result.effect) __android_log_print(ANDROID_LOG_ERROR, "AMStyles", "gradient: %s", result.errorText.c_str());
        return result.effect;
    }();
    if (!effect) return nullptr;
    auto premul = [alpha](const float* c) {
        const float a = c[0] * alpha;
        return SkV4{c[1] * a, c[2] * a, c[3] * a, a};
    };
    SkRuntimeEffectBuilder builder(effect);
    builder.uniform("c0") = premul(g.startColor);
    builder.uniform("c1") = premul(g.endColor);
    builder.uniform("p0") = SkV2{g.start[0], g.start[1]};
    builder.uniform("p1") = SkV2{g.end[0], g.end[1]};
    builder.uniform("box") = SkV4{bounds.left(), bounds.top(), std::max(bounds.width(), 1.0e-6f), std::max(bounds.height(), 1.0e-6f)};
    builder.uniform("kind") = g.type == "radial" ? 1.0f : g.type == "sweep" ? 2.0f : 0.0f;
    return builder.makeShader();
}

Placement Own(const Layer& l, double timeMs) {
    const float t = l.localTime(timeMs);
    Placement p{{l.location.get(0, t, 0), l.location.get(1, t, 0)},
                {l.pivot.get(0, t, 0), l.pivot.get(1, t, 0)},
                {l.scale.get(0, t, 1), l.scale.get(1, t, 1)},
                l.rotation.get(0, t, 0),
                l.location.get(2, t, 0)};
    ApplyMotion(l, timeMs, &p);
    return p;
}

constexpr int kMaxParentDepth = 16;

Placement Effective(const std::vector<Layer>& siblings, const Layer& l, double timeMs, int depth) {
    Placement own = Own(l, timeMs);
    if (l.parent == 0 || depth >= kMaxParentDepth) return own;
    const Layer* parent = nullptr;
    for (const Layer& s : siblings) {
        if (s.id == l.parent && &s != &l) { parent = &s; break; }
    }
    if (!parent) return own;
    const Placement p = Effective(siblings, *parent, timeMs, depth + 1);
    const SkVector offset = SkMatrix::RotateDeg(p.rotation).mapVector(
        (own.location.fX + own.pivot.fX - p.pivot.fX) * p.scale.fX, (own.location.fY + own.pivot.fY - p.pivot.fY) * p.scale.fY);
    own.location = p.location + offset - own.pivot + p.pivot;
    own.scale = {own.scale.fX * p.scale.fX, own.scale.fY * p.scale.fY};
    own.rotation += p.rotation;
    own.z += p.z;
    if (!own.stretch.isIdentity()) own.stretch = SkMatrix::RotateDeg(p.rotation) * own.stretch * SkMatrix::RotateDeg(-p.rotation);
    return own;
}

SkMatrix MatrixOf(const Placement& p, const Layer& l, float t) {
    SkMatrix m = SkMatrix::Translate(p.location.fX, p.location.fY);
    m.preConcat(p.stretch);
    m.preRotate(p.rotation);
    m.preSkew(tanf(l.skew.get(0, t, 0)), tanf(l.skew.get(1, t, 0)));
    m.preScale(p.scale.fX, p.scale.fY);
    m.preTranslate(-p.pivot.fX, -p.pivot.fY);
    return m;
}

SkMatrix SiblingMatrix(const std::vector<Layer>& siblings, const Layer& l, double timeMs) {
    return MatrixOf(Effective(siblings, l, timeMs, 0), l, l.localTime(timeMs));
}

SkPaint StrokePaint(const Layer& l, float width, SkColor4f color) {
    SkPaint p;
    p.setAntiAlias(true);
    p.setColor4f(color);
    p.setStyle(SkPaint::kStroke_Style);
    p.setStrokeWidth(width);
    p.setStrokeMiter(12);
    const std::string& cap = l.stroke.cap;
    p.setStrokeCap(cap == "butt" ? SkPaint::kButt_Cap : cap == "square" ? SkPaint::kSquare_Cap : SkPaint::kRound_Cap);
    const std::string& join = l.stroke.join;
    p.setStrokeJoin(join == "miter" ? SkPaint::kMiter_Join : join == "round" ? SkPaint::kRound_Join : SkPaint::kBevel_Join);
    return p;
}

SkPath Union(const SkPath& a, const SkPath& b);

SkPath DrawStrokeEnd(SkCanvas* canvas, SkPaint paint, SkPoint p, SkVector d, float r, const std::string& style) {
    const SkVector v = {-d.fY, d.fX};
    auto at = [&](float along, float across) { return p + d * (along * r) + v * (across * r); };
    SkPathBuilder b;
    if (style == "arrow") {
        b.moveTo(at(-1, -1)).lineTo(at(1, 0)).lineTo(at(-1, 1));
    } else if (style == "t-line") {
        b.moveTo(at(0, -1)).lineTo(at(0, 1));
    } else if (style == "square" || style == "square-filled") {
        b.moveTo(at(1, 1)).lineTo(at(1, -1)).lineTo(at(-1, -1)).lineTo(at(-1, 1)).close();
    } else if (style == "diamond" || style == "diamond-filled") {
        b.moveTo(at(1, 0)).lineTo(at(0, -1)).lineTo(at(-1, 0)).lineTo(at(0, 1)).close();
    } else if (style == "arrow-hollow" || style == "arrow-filled") {
        b.moveTo(at(1, 0)).lineTo(at(-1, -1)).lineTo(at(-1, 1)).close();
    } else if (style == "circle-hollow" || style == "circle-filled") {
        b.addCircle(p.fX, p.fY, r);
    } else if (style == "teardrop-hollow" || style == "teardrop-filled") {
        const float facing = atan2f(d.fY, d.fX) * 180 / 3.14159265f;
        b.moveTo(at(2, 0));
        b.arcTo(SkRect::MakeLTRB(p.fX - r, p.fY - r, p.fX + r, p.fY + r), facing + 60, 240, false);
        b.close();
    } else {
        return {};
    }
    const bool filled = style.size() > 7 && style.compare(style.size() - 7, 7, "-filled") == 0;
    paint.setStyle(filled ? SkPaint::kFill_Style : SkPaint::kStroke_Style);
    const SkPath shape = b.detach();
    if (canvas) canvas->drawPath(shape, paint);
    return filled ? shape : skpathutils::FillPathWithPaint(shape, paint);
}

SkPath DrawStroke(SkCanvas* canvas, const Layer& l, const SkPath& path, float t, float alpha) {
    const float width = l.stroke.size.get(0, t, 0);
    if (!(width > 0) || path.isEmpty()) return {};
    const SkColor4f color = ColorAt(l.stroke.color, t, kBlack, alpha);
    SkPaint paint = StrokePaint(l, width, color);
    const bool ends = (l.stroke.start != "none" || l.stroke.end != "none") && l.stroke.endSize > 0;
    SkPath area = skpathutils::FillPathWithPaint(path, paint);
    const bool layered = canvas && ends && color.fA < 1;
    if (layered) {
        SkPaint opacity;
        opacity.setAlphaf(color.fA);
        const float room = width * std::max(l.stroke.endSize, 1.0f) * 3;
        canvas->saveLayer(path.getBounds().makeOutset(room, room), &opacity);
        paint.setAlphaf(1);
    }
    if (canvas) canvas->drawPath(path, paint);
    if (ends) {
        const float r = width * l.stroke.endSize;
        SkPathMeasure contours(path, false);
        do {
            const float length = contours.getLength();
            SkPoint p;
            SkVector tangent;
            if (contours.isClosed() || !(length > 0)) continue;
            if (contours.getPosTan(0, &p, &tangent))
                area = Union(area, DrawStrokeEnd(canvas, paint, p, -tangent, r, l.stroke.start));
            if (contours.getPosTan(length, &p, &tangent))
                area = Union(area, DrawStrokeEnd(canvas, paint, p, tangent, r, l.stroke.end));
        } while (contours.nextContour());
    }
    if (layered) canvas->restore();
    return area;
}

bool ShapeKind(const Layer& l) { return l.kind == "shape"; }

bool ShaderBorder(const Layer& l, const Edge& e, float t) {
    return e.enabled && e.size.get(0, t, e.direction == "centered" ? 4 : 6) > 0.003f &&
           !(ShapeKind(l) && e.direction == "centered");
}

bool ShadowOn(const Layer& l) { return !l.shadow.direction.empty() && l.shadow.enabled; }

SkPath Union(const SkPath& a, const SkPath& b) {
    if (a.isEmpty()) return b;
    if (b.isEmpty()) return a;
    auto both = Op(a, b, kUnion_SkPathOp);
    return both ? *both : a;
}

void DrawBorder(SkCanvas* canvas, const SkPath& region, const Edge& e, float t) {
    const bool centered = e.direction == "centered";
    const float size = e.size.get(0, t, centered ? 4 : 6);
    const float radius = std::max(size / 2, 0.0f);
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setColor4f(ColorAt(e.color, t, kWhite));
    paint.setStrokeWidth(2 * radius);
    paint.setStrokeJoin(SkPaint::kRound_Join);
    if (e.direction == "outside") {
        paint.setStyle(SkPaint::kStrokeAndFill_Style);
        paint.setBlendMode(SkBlendMode::kDstOver);
        canvas->drawPath(region, paint);
    } else if (centered) {
        paint.setStyle(SkPaint::kStroke_Style);
        canvas->drawPath(region, paint);
    } else {
        paint.setStyle(SkPaint::kStroke_Style);
        paint.setBlendMode(SkBlendMode::kSrcATop);
        canvas->save();
        canvas->clipPath(region, true);
        canvas->drawPath(region, paint);
        canvas->restore();
    }
}

sk_sp<SkImageFilter> Morph(float radius, bool grow) {
    static const sk_sp<SkRuntimeEffect> effect = [] {
        auto result = SkRuntimeEffect::MakeForShader(SkString(R"(
uniform shader image;
uniform float2 stride;
uniform float count;
uniform float grow;
half4 main(float2 xy) {
    half a = image.eval(xy).a;
    for (int i = 1; i <= 64; i++) {
        if (float(i) > count) break;
        half p = image.eval(xy + stride * float(i)).a, q = image.eval(xy - stride * float(i)).a;
        a = grow > 0.5 ? max(a, max(p, q)) : min(a, min(p, q));
    }
    return half4(a);
})"));
        if (!result.effect) __android_log_print(ANDROID_LOG_ERROR, "AMStyles", "morph: %s", result.errorText.c_str());
        return result.effect;
    }();
    if (!effect) return nullptr;
    const float reach = radius * 3.14159265f / 16;
    const int count = std::clamp((int)std::ceil(reach * 2), 1, 64);
    sk_sp<SkImageFilter> f;
    for (int k = 0; k < 8; k++) {
        const float angle = k * 3.14159265f / 8;
        SkRuntimeShaderBuilder b(effect);
        b.uniform("stride") = SkV2{cosf(angle) * reach / count, sinf(angle) * reach / count};
        b.uniform("count") = (float)count;
        b.uniform("grow") = grow ? 1.0f : 0.0f;
        f = SkImageFilters::RuntimeShader(b, reach, "image", f);
    }
    return f;
}

void DrawGroupBorders(SkCanvas* canvas, const Layer& l, float t) {
    static const sk_sp<SkRuntimeEffect> effect = [] {
        auto result = SkRuntimeEffect::MakeForShader(SkString(R"(
uniform shader image;
uniform shader grown;
uniform shader shrunk;
layout(color) uniform half4 colour;
uniform float mode;
uniform float unit;
half4 main(float2 xy) {
    half4 c = image.eval(xy);
    float d = grown.eval(xy).a, e = shrunk.eval(xy).a, dm = d, em = e;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            float2 o = float2(float(i), float(j)) * 2.0 * unit;
            dm = max(dm, grown.eval(xy + o).a);
            em = max(em, shrunk.eval(xy + o).a);
        }
    }
    float outer = clamp((d - 0.5 * dm) * 3.0 + 0.5 * dm, 0.0, dm);
    float inner = em > 0.0 ? clamp((e / em - 0.5) * 3.0 + 0.5, 0.0, 1.0) : 0.0;
    half4 paint = half4(colour.rgb * colour.a, colour.a);
    if (mode < 0.5) {
        half4 b = paint * half(1.0 - inner);
        return b * c.a + c * (1.0 - b.a);
    }
    if (mode < 1.5) return c + paint * half(outer) * (1.0 - c.a);
    half4 b = paint * half(outer * (1.0 - inner));
    return b + c * (1.0 - b.a);
})"));
        if (!result.effect) __android_log_print(ANDROID_LOG_ERROR, "AMStyles", "border: %s", result.errorText.c_str());
        return result.effect;
    }();
    if (!effect) return;
    const SkMatrix& ctm = canvas->getTotalMatrix();
    const float unit = 1 / std::max(std::sqrt(std::fabs(ctm.getScaleX() * ctm.getScaleY() - ctm.getSkewX() * ctm.getSkewY())), 1e-3f);
    sk_sp<SkImageFilter> result;
    for (const Edge& e : l.borders) {
        if (!ShaderBorder(l, e, t)) continue;
        const bool outside = e.direction == "outside", centered = e.direction == "centered";
        const float radius = std::max(e.size.get(0, t, centered ? 4 : 6) / 2, 0.0f);
        const SkColor4f colour = ColorAt(e.color, t, kWhite);
        SkRuntimeShaderBuilder b(effect);
        b.uniform("colour") = SkV4{colour.fR, colour.fG, colour.fB, colour.fA};
        b.uniform("mode") = outside ? 1.0f : centered ? 2.0f : 0.0f;
        b.uniform("unit") = unit;
        std::string_view names[] = {"image", "grown", "shrunk"};
        const sk_sp<SkImageFilter> inputs[] = {result, outside || centered ? Morph(radius, true) : nullptr, outside ? nullptr : Morph(radius, false)};
        result = SkImageFilters::RuntimeShader(b, 3 * unit, names, inputs, 3);
    }
    if (!result) return;
    SkPaint replace;
    replace.setBlendMode(SkBlendMode::kSrc);
    canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &replace, result.get(), 0));
    canvas->restore();
}

const LayerEffects* gEffects = nullptr;
const Scene* gScene = nullptr;

const std::vector<Layer>* gEffectList = nullptr;

bool EffectsApply(const std::vector<Layer>& siblings, const Layer& l) {
    return gEffects && gScene && &siblings == gEffectList && gEffects->active(l);
}

struct Override {
    bool blend = false;
    SkBlendMode mode = SkBlendMode::kSrcOver;
    bool unitOpacity = false;
    bool echoed = false;
    float alpha = 1;
};

void DrawOne(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs,
             const MediaPictures& pictures, const Override& ov);
void DrawList(SkCanvas* canvas, const std::vector<Layer>& layers, int width, int height, bool top, double timeMs,
              const MediaPictures& pictures);

void DrawGroup(SkCanvas* canvas, const Layer& l, double timeMs, const MediaPictures& pictures) {
    canvas->translate(-l.innerWidth / 2.0f, -l.innerHeight / 2.0f);
    canvas->clipRect(SkRect::MakeWH((float)l.innerWidth, (float)l.innerHeight), true);
    const double inner = NestedTime(l, timeMs);
    if (inner < 0) return;
    struct Context {
        const Scene* previous;
        Scene scene;
        explicit Context(const Layer& group) : previous(gScene) {
            scene.width = group.innerWidth;
            scene.height = group.innerHeight;
            scene.fps = std::max(1, (int)group.retiming.fps);
            gScene = &scene;
        }
        ~Context() { gScene = previous; }
    } context(l);
    if (l.fixedPrecompose && l.innerWidth > 0 && l.innerHeight > 0) {
        if (auto surface = canvas->makeSurface(SkImageInfo::MakeN32Premul(l.innerWidth, l.innerHeight))) {
            surface->getCanvas()->drawColor((SkColor)l.innerBackground);
            DrawList(surface->getCanvas(), l.children, l.innerWidth, l.innerHeight, false, inner, pictures);
            canvas->drawImage(surface->makeImageSnapshot(), 0, 0, SkSamplingOptions(SkFilterMode::kLinear));
            return;
        }
    }
    if (l.innerBackground >> 24) canvas->drawColor((SkColor)l.innerBackground);
    DrawList(canvas, l.children, l.innerWidth, l.innerHeight, false, inner, pictures);
}

SkPaint FillPaint(SkColor4f color) {
    SkPaint p;
    p.setAntiAlias(true);
    p.setColor4f(color);
    return p;
}

void DrawContent(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs, float alpha,
                 bool decorate, const MediaPictures& pictures) {
    const float t = l.localTime(timeMs);
    const SkMatrix m = SiblingMatrix(siblings, l, timeMs);
    const SkPath path = ShapePath(l, t);
    const SkColor4f fill = ColorAt(l.fillColor, t, kWhite, alpha);
    bool hasFill = false;
    SkPath stroked;
    const SkPath scenePath = path.makeTransform(m);
    auto picture = [&](SkCanvas* canvas) {
        canvas->save();
        canvas->concat(m);
        if (l.kind == "embedScene") {
            DrawGroup(canvas, l, timeMs, pictures);
            canvas->restore();
            return;
        }
        if (l.kind == "drawing") {
            hasFill = !l.strokes.empty();
            DrawDrawing(canvas, l, alpha);
        } else if (l.fillType == "color") {
            hasFill = fill.fA > 0;
            if (l.text) DrawText(canvas, l, t, FillPaint(fill));
            else canvas->drawPath(path, FillPaint(fill));
        } else if (l.fillType == "media" || l.fillType == "gradient") {
            hasFill = true;
            canvas->save();
            if (l.text) canvas->clipPath(TextGlyphPath(l), true);
            if (l.fillType == "media") {
                DrawMedia(canvas, l, path, alpha, pictures);
            } else {
                SkPaint paint;
                paint.setAntiAlias(true);
                paint.setShader(GradientShader(l.gradient, path.getBounds(), alpha));
                if (paint.getShader()) canvas->drawPath(path, paint);
            }
            canvas->restore();
        }
        canvas->restore();
        if (l.stroke.present && l.stroke.enabled) stroked = DrawStroke(canvas, l, scenePath, t, alpha);
    };
    const bool effects = EffectsApply(siblings, l);
    if (!effects || !gEffects->draw(canvas, *gScene, l, timeMs, scenePath.getBounds(), picture)) picture(canvas);
    if (effects && gEffects->lifted(l) && !scenePath.isEmpty()) {
        SkPath outside = scenePath;
        outside.toggleInverseFillType();
        SkPaint clear;
        clear.setAntiAlias(true);
        clear.setBlendMode(SkBlendMode::kClear);
        canvas->drawPath(outside, clear);
    }
    if (!decorate) return;
    if (l.kind == "embedScene") return DrawGroupBorders(canvas, l, t);
    SkPath region = hasFill ? (l.text ? TextGlyphPath(l).makeTransform(m) : l.kind == "drawing" ? DrawingOutline(l).makeTransform(m) : scenePath) : SkPath();
    region = Union(region, stroked);
    if (region.isEmpty()) return;
    for (const Edge& e : l.borders) {
        if (ShaderBorder(l, e, t)) DrawBorder(canvas, region, e, t);
    }
}

void DrawOne(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs,
             const MediaPictures& pictures, const Override& ov) {
    if (!l.activeAt(timeMs) || l.kind == "nullobj" || l.kind == "camera") return;
    if (l.fillType == "media" && !pictures(l.mediaUri, l.id)) return;
    Echo echo;
    if (!ov.echoed && EchoOf(l, timeMs, &echo)) {
        Override plain = ov;
        plain.echoed = true;
        auto past = [&](int i) {
            Override faded = plain;
            faded.alpha = ov.alpha * (1 + (echo.alpha - 1) * i / echo.count);
            const double at = std::clamp(timeMs - 1000.0 * echo.seconds * i / echo.count, (double)l.startTime, (double)l.endTime);
            DrawOne(canvas, siblings, l, at, pictures, faded);
        };
        if (!echo.atop) for (int i = echo.count; i >= 1; i--) past(i);
        DrawOne(canvas, siblings, l, timeMs, pictures, plain);
        if (echo.atop) for (int i = 1; i <= echo.count; i++) past(i);
        return;
    }
    const float t = l.localTime(timeMs);
    const float opacity = std::clamp(ov.unitOpacity ? ov.alpha : l.opacity.get(0, t, 1) * MotionOpacity(l, timeMs) * ov.alpha, 0.0f, 1.0f);
    const bool group = l.kind == "embedScene";
    bool decorated = false;
    for (const Edge& e : l.borders) decorated |= ShaderBorder(l, e, t);
    const bool shadow = ShadowOn(l);
    decorated = decorated || shadow || EffectsApply(siblings, l);
    const bool blended = ov.blend || !IsNormal(l.blending);
    const bool centred = ShapeKind(l) && std::any_of(l.borders.begin(), l.borders.end(), [](const Edge& e) { return e.enabled && e.direction == "centered"; });
    const bool overlaps = l.kind == "drawing" || (l.stroke.present && l.stroke.enabled) || centred;
    auto centredBorders = [&](float alpha) {
        if (!centred) return;
        const SkPath scenePath = ShapePath(l, t).makeTransform(SiblingMatrix(siblings, l, timeMs));
        for (const Edge& e : l.borders) {
            if (!e.enabled || e.direction != "centered") continue;
            const float size = e.size.get(0, t, 4);
            if (!(size > 0)) continue;
            SkPaint paint = FillPaint(ColorAt(e.color, t, kWhite, alpha));
            paint.setStyle(SkPaint::kStroke_Style);
            paint.setStrokeWidth(size);
            paint.setStrokeCap(SkPaint::kRound_Cap);
            paint.setStrokeJoin(SkPaint::kBevel_Join);
            canvas->drawPath(scenePath, paint);
        }
    };
    if (!group && !decorated && !blended && (opacity >= 1 || !overlaps)) {
        DrawContent(canvas, siblings, l, timeMs, opacity, false, pictures);
        centredBorders(opacity);
    } else {
        SkPaint layerPaint;
        layerPaint.setAlphaf(opacity);
        if (ov.blend) layerPaint.setBlendMode(ov.mode);
        else SetBlend(&layerPaint, l.blending);
        if (shadow) {
            const Edge& s = l.shadow;
            const float sigma = std::max(s.size.get(0, t, 4), 0.0f) * 0.5f;
            const SkColor4f color = ColorAt(s.color, t, kBlack, std::clamp(s.opacity.get(0, t, 1), 0.0f, 1.0f));
            layerPaint.setImageFilter(SkImageFilters::DropShadow(s.offset.get(0, t, 3), s.offset.get(1, t, 3), sigma, sigma,
                                                                 color, nullptr, nullptr));
        }
        const bool lifted = EffectsApply(siblings, l) && gEffects->lifted(l);
        canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &layerPaint, lifted ? SkCanvas::kInitWithPrevious_SaveLayerFlag : 0));
        DrawContent(canvas, siblings, l, timeMs, 1, decorated, pictures);
        centredBorders(1);
        canvas->restore();
    }
}

}

void SetLayerEffects(const LayerEffects* effects, const Scene* scene) {
    gEffects = effects;
    gScene = scene;
}

void ParseStyle(const XmlNode& n, Layer* l) {
    l->blending = AttrOf(n, "blending");
    l->clippingMask = AttrOf(n, "clippingMask") == "true";
    l->parent = strtoll(AttrOf(n, "parent").c_str(), nullptr, 10);
    for (const auto& c : n.children) {
        if (c->name == "border" || c->name == "shadow") {
            Edge e;
            e.enabled = AttrOf(*c, "enabled") != "false";
            const std::string direction = AttrOf(*c, "direction");
            if (c->name == "border") {
                e.direction = direction.empty() ? "inside" : direction;
            } else if (direction.empty() || direction == "outside") {
                e.direction = "outside";
            } else {
                continue;
            }
            e.color = ParseAnimNode(c->child("color"));
            e.size = ParseAnimNode(c->child("size"));
            e.opacity = ParseAnimNode(c->child("opacity"));
            e.hardness = ParseAnimNode(c->child("hardness"));
            e.offset = ParseAnimNode(c->child("offset"));
            if (c->name == "border") l->borders.push_back(std::move(e));
            else l->shadow = std::move(e);
        } else if (c->name == "gradient") {
            Gradient& g = l->gradient;
            g.present = true;
            if (!AttrOf(*c, "type").empty()) g.type = AttrOf(*c, "type");
            auto colour = [&](const char* key, float* out) {
                const std::string v = AttrOf(*c, key);
                if (v.size() != 9 || v[0] != '#') return;
                const uint32_t argb = (uint32_t)strtoul(v.c_str() + 1, nullptr, 16);
                for (int i = 0; i < 4; i++) out[i] = ((argb >> (24 - 8 * i)) & 0xff) / 255.0f;
            };
            auto point = [&](const char* key, float* out) {
                const std::string v = AttrOf(*c, key);
                char* rest = nullptr;
                const float x = strtof(v.c_str(), &rest);
                if (rest == v.c_str() || *rest != ',') return;
                out[0] = x;
                out[1] = strtof(rest + 1, nullptr);
            };
            colour("startColor", g.startColor);
            colour("endColor", g.endColor);
            point("start", g.start);
            point("end", g.end);
        } else if (c->name == "path-stroke") {
            l->stroke.enabled = AttrOf(*c, "enabled") != "false";
            if (!AttrOf(*c, "cap").empty()) l->stroke.cap = AttrOf(*c, "cap");
            if (!AttrOf(*c, "join").empty()) l->stroke.join = AttrOf(*c, "join");
            if (!AttrOf(*c, "start").empty()) l->stroke.start = AttrOf(*c, "start");
            if (!AttrOf(*c, "end").empty()) l->stroke.end = AttrOf(*c, "end");
        }
    }
}

static bool Contains(const std::vector<Layer>& siblings, const Layer& l, double timeMs, SkPoint p) {
    if (!l.activeAt(timeMs) || l.kind == "nullobj" || l.kind == "camera") return false;
    const SkMatrix matrix = SiblingMatrix(siblings, l, timeMs);
    SkMatrix inverse;
    if (!matrix.invert(&inverse)) return false;
    const SkPoint local = inverse.mapPoint(p);
    if (l.kind == "drawing") return DrawingContains(l, local);
    if (l.kind != "embedScene") {
        const float t = l.localTime(timeMs);
        const SkPath path = ShapePath(l, t);
        if (l.fillType != "none" && path.contains(local.fX, local.fY)) return true;
        if (!(l.stroke.present && l.stroke.enabled)) return false;
        return DrawStroke(nullptr, l, path.makeTransform(matrix), t, 1).contains(p.fX, p.fY);
    }
    const SkPoint inner = {local.fX + l.innerWidth / 2.0f, local.fY + l.innerHeight / 2.0f};
    if (!SkRect::MakeWH(l.innerWidth, l.innerHeight).contains(inner.fX, inner.fY)) return false;
    const double innerTime = NestedTime(l, timeMs);
    for (auto it = l.children.rbegin(); innerTime >= 0 && it != l.children.rend(); ++it) {
        if (Contains(l.children, *it, innerTime, inner)) return true;
    }
    return false;
}

static const std::vector<Layer>& SeenProject(const Scene& scene, double timeMs, std::vector<Layer>* storage) {
    return SeeLayers(scene.layers, scene.width, scene.height, true, scene.fps, timeMs, storage) ? *storage : scene.layers;
}

bool HitLayer(const Scene& scene, const Layer& layer, double timeMs, float x, float y) {
    std::vector<Layer> storage;
    const std::vector<Layer>& list = SeenProject(scene, timeMs, &storage);
    for (const Layer& l : list) if (l.id == layer.id) return Contains(list, l, timeMs, {x, y});
    return false;
}

int64_t TopLayerAt(const Scene& scene, double timeMs, float x, float y) {
    std::vector<Layer> storage;
    const std::vector<Layer>& list = SeenProject(scene, timeMs, &storage);
    for (auto it = list.rbegin(); it != list.rend(); ++it) {
        if (Contains(list, *it, timeMs, {x, y})) return it->id;
    }
    return 0;
}

SkMatrix WorldMatrix(const Scene& scene, const Layer& layer, double timeMs) {
    std::vector<Layer> storage;
    const std::vector<Layer>& list = SeenProject(scene, timeMs, &storage);
    for (const Layer& l : list) if (l.id == layer.id) return SiblingMatrix(list, l, timeMs);
    return SiblingMatrix(scene.layers, layer, timeMs);
}

Placement EffectivePlacement(const std::vector<Layer>& siblings, const Layer& layer, double timeMs) {
    return Effective(siblings, layer, timeMs, 0);
}

namespace {

void DrawList(SkCanvas* canvas, const std::vector<Layer>& source, int width, int height, bool top, double timeMs,
              const MediaPictures& pictures) {
    std::vector<Layer> storage;
    const std::vector<Layer>& layers =
        SeeLayers(source, width, height, top, gScene ? gScene->fps : 30, timeMs, &storage) ? storage : source;
    const std::vector<Layer>* const outer = gEffectList;
    gEffectList = &layers;
    for (size_t i = 0; i < layers.size(); i++) {
        const Layer& base = layers[i];
        if (base.clippingMask && i > 0) continue;
        size_t end = i + 1;
        while (end < layers.size() && layers[end].clippingMask) end++;
        if (end == i + 1) {
            DrawOne(canvas, layers, base, timeMs, pictures, Override());
            continue;
        }
        if (!base.activeAt(timeMs)) continue;
        const float t = base.localTime(timeMs);
        SkPaint stack;
        stack.setAlphaf(std::clamp(base.opacity.get(0, t, 1) * MotionOpacity(base, timeMs), 0.0f, 1.0f));
        SetBlend(&stack, base.blending);
        canvas->saveLayer(nullptr, &stack);
        Override plain;
        plain.blend = true;
        plain.unitOpacity = true;
        DrawOne(canvas, layers, base, timeMs, pictures, plain);
        Override clipped;
        clipped.blend = true;
        clipped.mode = SkBlendMode::kSrcATop;
        for (size_t k = i + 1; k < end; k++) DrawOne(canvas, layers, layers[k], timeMs, pictures, clipped);
        canvas->restore();
    }
    gEffectList = outer;
}

}

void DrawLayerList(SkCanvas* canvas, const Scene& scene, const std::vector<Layer>& layers, double timeMs, const MediaPictures& pictures) {
    DrawList(canvas, layers, scene.width, scene.height, true, timeMs, pictures);
}

}
