#include "platform_log.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string_view>

#include "include/core/SkBlender.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
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

thread_local const std::function<void()>* gBreak = nullptr;

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
constexpr float kRadians = 3.14159265f / 180;

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
    thread_local std::map<std::string, sk_sp<SkBlender>> cache;
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

struct Frozen {
    int64_t id = 0;
    double at = 0;
    bool position = true, scale = true, rotation = true;
};

thread_local const Frozen* gFrozen = nullptr;

Placement OwnAt(const Layer& l, double timeMs) {
    if (Placement seen; SeenPlacement(l, timeMs, &seen)) return seen;
    const float t = l.localTime(timeMs);
    Placement p{{l.location.get(0, t, 0), l.location.get(1, t, 0)},
                {l.pivot.get(0, t, 0), l.pivot.get(1, t, 0)},
                {l.scale.get(0, t, 1), l.scale.get(1, t, 1)},
                l.rotation.get(0, t, 0),
                l.location.get(2, t, 0)};
    const float tx = l.tilt.get(0, t, 0), ty = l.tilt.get(1, t, 0);
    if (tx != 0 || ty != 0) p.tilt = SkM44::Rotate({0, 1, 0}, -ty * kRadians) * SkM44::Rotate({1, 0, 0}, -tx * kRadians);
    ApplyMotion(l, timeMs, &p);
    return p;
}

Placement Own(const Layer& l, double timeMs) {
    Placement p = OwnAt(l, timeMs);
    if (gFrozen && gFrozen->id == l.id) {
        const Placement still = OwnAt(l, gFrozen->at);
        if (!gFrozen->position) {
            p.location = still.location;
            p.z = still.z;
        }
        if (!gFrozen->scale) p.scale = still.scale;
        if (!gFrozen->rotation) p.rotation = still.rotation;
    }
    return p;
}

SkM44 Turn(float degrees) { return SkM44::Rotate({0, 0, 1}, degrees * kRadians); }

constexpr int kMaxParentDepth = 16;

SkMatrix MatrixOf(const Placement& p, const Layer& l, float t);
int Fps();

const Layer* PathSource(const std::vector<Layer>& siblings, const Layer& l) {
    if (l.pathLayer != 0) {
        for (const Layer& s : siblings) if (s.id == l.pathLayer && &s != &l) return &s;
    }
    for (size_t i = 1; i < siblings.size(); i++) {
        if (&siblings[i] == &l || siblings[i].id == l.id) return &siblings[i - 1];
    }
    return nullptr;
}

Placement Effective(const std::vector<Layer>& siblings, const Layer& l, double timeMs, int depth) {
    Placement own = Own(l, timeMs);
    if (depth >= kMaxParentDepth) return own;
    const Layer* parent = nullptr;
    for (const Layer& s : siblings) {
        if (l.parent != 0 && s.id == l.parent && &s != &l) { parent = &s; break; }
    }
    Placement p{};
    if (parent) {
        p = Effective(siblings, *parent, timeMs, depth + 1);
        const SkV4 offset = Turn(p.rotation) * p.tilt * SkV4{(own.location.fX + own.pivot.fX - p.pivot.fX) * p.scale.fX,
                                                             (own.location.fY + own.pivot.fY - p.pivot.fY) * p.scale.fY, own.z, 0};
        own.location = p.location + SkVector{offset.x, offset.y} - own.pivot + p.pivot;
        own.scale = {own.scale.fX * p.scale.fX, own.scale.fY * p.scale.fY};
        if (p.tilt != SkM44()) own.tilt = Turn(-own.rotation) * p.tilt * Turn(own.rotation) * own.tilt;
        own.rotation += p.rotation;
        own.z = p.z + offset.z;
    }
    if (!FollowsPath(l)) return own;
    const Layer* source = PathSource(siblings, l);
    if (!source) return own;
    const Layer& below = *source;
    const float t = below.localTime(timeMs);
    const SkPath path = below.text ? TextGlyphPath(below, t, Fps()) : ShapePath(below, t);
    FollowPath(l, timeMs, path.makeTransform(MatrixOf(parent == &below ? p : Effective(siblings, below, timeMs, depth + 1), below, t)), &own);
    return own;
}

SkMatrix MatrixOf(const Placement& p, const Layer& l, float t) {
    SkMatrix m = l.projected ? p.space.asM33() : SkMatrix::Translate(p.location.fX, p.location.fY);
    if (!l.projected) m.preRotate(p.rotation);
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

SkPath DrawStroke(SkCanvas* canvas, const Layer& l, const SkPath& path, float t, float alpha, bool outline = true) {
    const float width = l.stroke.size.get(0, t, 0);
    if (!(width > 0) || path.isEmpty()) return {};
    const SkColor4f color = ColorAt(l.stroke.color, t, kBlack, alpha);
    SkPaint paint = StrokePaint(l, width, color);
    const bool ends = (l.stroke.start != "none" || l.stroke.end != "none") && l.stroke.endSize > 0;
    SkPath area = outline ? skpathutils::FillPathWithPaint(path, paint) : SkPath();
    const auto add = [&](const SkPath& end) { if (outline) area = Union(area, end); };
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
            if (contours.getPosTan(0, &p, &tangent)) add(DrawStrokeEnd(canvas, paint, p, -tangent, r, l.stroke.start));
            if (contours.getPosTan(length, &p, &tangent)) add(DrawStrokeEnd(canvas, paint, p, tangent, r, l.stroke.end));
        } while (contours.nextContour());
    }
    if (layered) canvas->restore();
    return area;
}

bool ShapeKind(const Layer& l) { return l.kind == "shape"; }

bool PixelEdged(const Layer& l, const MediaPictures& pictures) {
    if (l.kind == "drawing") return true;
    if (!ShapeKind(l) || l.text || l.video || l.fillType != "media") return false;
    auto image = pictures(l.mediaUri, l.id);
    return image && !image->isOpaque();
}

bool ShaderBorder(const Layer& l, const Edge& e, float t, bool pixelEdged = false) {
    return e.enabled && e.size.get(0, t, e.direction == "centered" ? 4 : 6) > 0.003f &&
           !(ShapeKind(l) && !pixelEdged && e.direction == "centered");
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

float PixelUnit(const SkCanvas* canvas) {
    const SkMatrix& ctm = canvas->getTotalMatrix();
    return 1 / std::max(std::sqrt(std::fabs(ctm.getScaleX() * ctm.getScaleY() - ctm.getSkewX() * ctm.getSkewY())), 1e-3f);
}

sk_sp<SkImageFilter> EdgeFilter(sk_sp<SkImageFilter> image, const std::string& direction, float radius, SkColor4f colour, float unit) {
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
    if (!effect) return image;
    const bool outside = direction == "outside", centered = direction == "centered";
    SkRuntimeShaderBuilder b(effect);
    b.uniform("colour") = SkV4{colour.fR, colour.fG, colour.fB, colour.fA};
    b.uniform("mode") = outside ? 1.0f : centered ? 2.0f : 0.0f;
    b.uniform("unit") = unit;
    std::string_view names[] = {"image", "grown", "shrunk"};
    const sk_sp<SkImageFilter> inputs[] = {image, outside || centered ? Morph(radius, true) : nullptr, outside ? nullptr : Morph(radius, false)};
    return SkImageFilters::RuntimeShader(b, 3 * unit, names, inputs, 3);
}

sk_sp<SkImageFilter> BorderFilter(const Layer& l, float t, bool pixelEdged, float unit) {
    sk_sp<SkImageFilter> result;
    for (const Edge& e : l.borders) {
        if (!ShaderBorder(l, e, t, pixelEdged)) continue;
        const float radius = std::max(e.size.get(0, t, e.direction == "centered" ? 4 : 6) / 2, 0.0f);
        result = EdgeFilter(result, e.direction, radius, ColorAt(e.color, t, kWhite), unit);
    }
    return result;
}

void DrawGroupBorders(SkCanvas* canvas, const Layer& l, float t, bool pixelEdged = false) {
    const sk_sp<SkImageFilter> result = BorderFilter(l, t, pixelEdged, PixelUnit(canvas));
    if (!result) return;
    SkPaint replace;
    replace.setBlendMode(SkBlendMode::kSrc);
    canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &replace, result.get(), 0));
    canvas->restore();
}

thread_local const LayerEffects* gEffects = nullptr;
thread_local const Scene* gScene = nullptr;
thread_local bool gWorld = false;

int Fps() { return gScene ? gScene->fps : 30; }

thread_local const std::vector<Layer>* gEffectList = nullptr;

bool EffectsApply(const std::vector<Layer>& siblings, const Layer& l) {
    return gEffects && gScene && &siblings == gEffectList && gEffects->active(l);
}

bool SeesPrevious(const std::vector<Layer>& siblings, const Layer& l) {
    return EffectsApply(siblings, l) && (gEffects->lifted(l) || gEffects->behind(l));
}

struct Override {
    bool blend = false;
    SkBlendMode mode = SkBlendMode::kSrcOver;
    bool unitOpacity = false;
    bool echoed = false;
    bool shuttered = false;
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

struct LayerCopy {
    SkMatrix scene;
    double timeMs;
    RepeatCopy look;
    SkMatrix layer;
};

std::vector<LayerCopy> CopiesOf(const std::vector<Layer>& siblings, const Layer& l, double timeMs, int width, int height) {
    const float t = l.localTime(timeMs);
    const SkMatrix m = SiblingMatrix(siblings, l, timeMs);
    RepeatSpace space;
    space.pivot = {l.pivot.get(0, t, 0), l.pivot.get(1, t, 0)};
    space.layer = m;
    space.anchor = m.mapPoint(space.pivot);
    space.width = (float)width;
    space.height = (float)height;
    const SkRect box = ShapePath(l, t).makeTransform(m).getBounds();
    space.radius = std::max({std::fabs(box.left() - space.anchor.fX), std::fabs(box.right() - space.anchor.fX),
                             std::fabs(box.top() - space.anchor.fY), std::fabs(box.bottom() - space.anchor.fY)}) * 1.415f;
    if (RepeatsAlongPath(l)) {
        const Layer* below = PathSource(siblings, l);
        if (below && below->kind != "nullobj" && below->kind != "camera") {
            const float tb = below->localTime(timeMs);
            space.below = (below->text ? TextGlyphPath(*below, tb, Fps()) : ShapePath(*below, tb)).makeTransform(SiblingMatrix(siblings, *below, timeMs));
        }
    }
    std::vector<RepeatCopy> copies;
    RepeatCopies(l, timeMs, space, &copies);
    std::vector<LayerCopy> out;
    out.reserve(copies.size());
    double at = timeMs;
    SkMatrix matrix = m, inverse;
    bool invertible = m.invert(&inverse);
    for (const RepeatCopy& c : copies) {
        const double when = std::clamp(timeMs + c.shiftMs, (double)l.startTime, (double)l.endTime);
        if (when != at) {
            at = when;
            matrix = SiblingMatrix(siblings, l, when);
            invertible = matrix.invert(&inverse);
        }
        if (!invertible) continue;
        out.push_back({c.outer * matrix * c.local * inverse, when, c, matrix});
    }
    return out;
}

std::vector<std::pair<SkPath, float>> CopyOutlines(const Layer& l, const std::vector<LayerCopy>& copies,
                                                  const std::function<SkPath(float, const SkMatrix&)>& outline) {
    std::vector<std::pair<SkPath, float>> out;
    double shown = NAN;
    SkPath own;
    for (const LayerCopy& c : copies) {
        const float at = l.localTime(c.timeMs);
        if (c.timeMs != shown) own = outline(at, c.layer);
        shown = c.timeMs;
        out.emplace_back(own.makeTransform(c.scene), at);
    }
    return out;
}

SkPath BodyPath(const Layer& l, float t) { return ShapeKind(l) ? DrawingProgressPath(l, ShapePath(l, t), t) : ShapePath(l, t); }

SkPath StrokeGeometry(const Layer& l, const SkPath& body, float t) { return l.text ? TextGlyphPath(l, t, Fps()) : body; }

struct Drawn {
    bool fill = false;
    SkPath stroked;
};

Drawn DrawBody(SkCanvas* canvas, const Layer& l, double timeMs, float t, const SkMatrix& m, const SkPath& path, float alpha,
               const MediaPictures& pictures, bool outline) {
    Drawn drawn;
    if (l.kind == "model") {
        DrawModel(canvas, l, timeMs, alpha, pictures);
        return drawn;
    }
    const SkColor4f fill = ColorAt(l.fillColor, t, kWhite, alpha);
    const bool stroked = l.stroke.present && l.stroke.enabled, edged = stroked && (l.kind == "embedScene" || PixelEdged(l, pictures));
    if (edged) {
        const float width = l.stroke.size.get(0, t, 0);
        SkPaint edge;
        if (width > 0) edge.setImageFilter(EdgeFilter(nullptr, "centered", width / 2, ColorAt(l.stroke.color, t, kBlack, alpha), PixelUnit(canvas)));
        canvas->saveLayer(nullptr, &edge);
    }
    canvas->save();
    canvas->concat(m);
    if (l.kind == "embedScene") {
        DrawGroup(canvas, l, timeMs, pictures);
        canvas->restore();
        if (edged) canvas->restore();
        return drawn;
    }
    if (l.kind == "drawing") {
        DrawDrawing(canvas, l, alpha, t);
    } else if (l.fillType == "color") {
        drawn.fill = fill.fA > 0;
        if (l.text) DrawText(canvas, l, t, FillPaint(fill), Fps());
        else canvas->drawPath(path, FillPaint(fill));
    } else if (l.fillType == "media" || l.fillType == "gradient") {
        drawn.fill = true;
        const bool masked = l.text && TextAnimated(l);
        canvas->save();
        if (masked) {
            SkPaint inside;
            inside.setBlendMode(SkBlendMode::kSrcIn);
            canvas->saveLayer(nullptr, nullptr);
            DrawText(canvas, l, t, FillPaint(kWhite), Fps(), TextPart::kPlain);
            canvas->saveLayer(nullptr, &inside);
        } else if (l.text) {
            canvas->clipPath(TextGlyphPath(l), true);
        }
        if (l.fillType == "media") {
            DrawMedia(canvas, l, path, alpha, pictures);
        } else {
            SkPaint paint;
            paint.setAntiAlias(true);
            paint.setShader(GradientShader(l.gradient, path.getBounds(), alpha));
            if (paint.getShader() && masked) canvas->drawPaint(paint);
            else if (paint.getShader()) canvas->drawPath(path, paint);
        }
        if (masked) {
            canvas->restore();
            canvas->restore();
        }
        canvas->restore();
        if (l.text) DrawText(canvas, l, t, FillPaint({1, 1, 1, std::clamp(alpha, 0.0f, 1.0f)}), Fps(), TextPart::kColor);
    }
    canvas->restore();
    if (edged) canvas->restore();
    else if (stroked) drawn.stroked = DrawStroke(canvas, l, StrokeGeometry(l, path, t).makeTransform(m), t, alpha, outline);
    return drawn;
}

bool SelfOverlaps(const Layer& l) {
    return l.kind == "drawing" || l.kind == "embedScene" || l.kind == "model" || (l.stroke.present && l.stroke.enabled);
}

sk_sp<SkColorFilter> CopyTint(const RepeatCopy& look) {
    static const sk_sp<SkRuntimeEffect> effect = SkRuntimeEffect::MakeForColorFilter(SkString(R"(
layout(color) uniform half4 tint;
uniform float amount;
uniform float mode;
float lum(float3 c) { return dot(c, float3(0.3, 0.59, 0.11)); }
float3 withLum(float3 c, float l) {
    c += l - lum(c);
    float k = lum(c), n = min(min(c.r, c.g), c.b), x = max(max(c.r, c.g), c.b);
    if (n < 0) c = k + (c - k) * k / (k - n);
    if (x > 1) c = k + (c - k) * (1 - k) / (x - k);
    return c;
}
half4 main(half4 color) {
    float a = color.a;
    if (a <= 0) return half4(0);
    float3 u = float3(color.rgb) / a, s = float3(tint.rgb);
    float4 blended = float4(s * tint.a, tint.a);
    if (mode > 0.5) {
        if (mode > 1.5) {
            float n = min(min(s.r, s.g), s.b), x = max(max(s.r, s.g), s.b);
            s = x > n ? (s - n) * (max(max(u.r, u.g), u.b) - min(min(u.r, u.g), u.b)) / (x - n) : float3(0);
        }
        blended = float4(mix(u, withLum(s, lum(u)), float(tint.a)), 1);
    }
    return half4(mix(float4(u, 1), blended, amount) * a);
}
)")).effect;
    const bool known = look.tintMode == SkBlendMode::kSrcIn || look.tintMode == SkBlendMode::kColor || look.tintMode == SkBlendMode::kHue;
    if (!effect || !known) return SkColorFilters::Lerp(look.tinted, nullptr, SkColorFilters::Blend(look.tint, nullptr, look.tintMode));
    SkRuntimeColorFilterBuilder b(effect);
    b.uniform("tint") = SkV4{look.tint.fR, look.tint.fG, look.tint.fB, look.tint.fA};
    b.uniform("amount") = std::clamp(look.tinted, 0.0f, 1.0f);
    b.uniform("mode") = look.tintMode == SkBlendMode::kSrcIn ? 0.0f : look.tintMode == SkBlendMode::kColor ? 1.0f : 2.0f;
    return b.makeColorFilter();
}

void ReleaseLayers(SkCanvas* canvas) {
    const SkIRect seen = canvas->getDeviceClipBounds();
    if (seen.isEmpty()) return;
    SkPaint same;
    same.setBlendMode(SkBlendMode::kSrc);
    const SkRect pixel = SkRect::Make(SkIRect::MakeXYWH(seen.left(), seen.top(), 1, 1));
    canvas->save();
    canvas->resetMatrix();
    canvas->saveLayer(SkCanvas::SaveLayerRec(&pixel, &same, SkCanvas::kInitWithPrevious_SaveLayerFlag));
    canvas->restore();
    canvas->restore();
}

void DrawContent(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs, float alpha,
                 bool decorate, const MediaPictures& pictures, const std::vector<LayerCopy>* copies) {
    const float t = l.localTime(timeMs);
    const SkMatrix m = SiblingMatrix(siblings, l, timeMs);
    const SkPath path = BodyPath(l, t);
    Drawn drawn;
    const SkPath scenePath = path.makeTransform(m);
    const bool effects = EffectsApply(siblings, l);
    const bool pixelEdged = decorate && PixelEdged(l, pictures);
    const bool bordered = decorate && std::any_of(l.borders.begin(), l.borders.end(), [&](const Edge& e) { return ShaderBorder(l, e, t, pixelEdged); });
    const bool early = pixelEdged && bordered && SeesPrevious(siblings, l) && !gEffects->lifted(l);
    auto body = [&](SkCanvas* canvas) {
        if (!copies) {
            drawn = DrawBody(canvas, l, timeMs, t, m, path, alpha, pictures, bordered);
            return;
        }
        const float room = l.stroke.present && l.stroke.enabled ? l.stroke.size.get(0, t, 0) * std::max(l.stroke.endSize, 1.0f) * 3 + 2 : 2;
        const bool single = !l.text && l.kind != "drawing" && l.kind != "embedScene" && l.kind != "model" && l.fillType == "color" &&
                            !(l.stroke.present && l.stroke.enabled);
        const SkIRect seen = canvas->getDeviceClipBounds();
        const float frame = std::max(1.0f, (float)seen.width() * (float)seen.height());
        const bool plainText = l.text && !TextAnimated(l);
        float spent = 0;
        for (const LayerCopy& c : *copies) {
            const bool now = c.timeMs == timeMs;
            const float tc = now ? t : l.localTime(c.timeMs);
            const SkMatrix mc = now ? m : SiblingMatrix(siblings, l, c.timeMs);
            const SkPath pc = now ? path : BodyPath(l, tc);
            const bool tinted = c.look.tinted > 0;
            const bool styled = tinted || c.look.mode != SkBlendMode::kSrcOver;
            if (styled && single) {
                const SkColor4f fill = ColorAt(l.fillColor, tc, kWhite, alpha);
                SkPaint paint = FillPaint({fill.fR, fill.fG, fill.fB, fill.fA * c.look.alpha});
                paint.setBlendMode(c.look.mode);
                if (tinted) paint.setColorFilter(CopyTint(c.look));
                canvas->save();
                canvas->concat(c.scene);
                canvas->concat(mc);
                canvas->drawPath(pc, paint);
                canvas->restore();
                if (now) {
                    drawn = Drawn();
                    drawn.fill = fill.fA > 0;
                }
                continue;
            }
            const bool layered = styled || (c.look.alpha < 1 && SelfOverlaps(l));
            canvas->save();
            canvas->concat(c.scene);
            if (layered) {
                SkPaint paint;
                paint.setAlphaf(c.look.alpha);
                paint.setBlendMode(c.look.mode);
                if (tinted) paint.setColorFilter(CopyTint(c.look));
                SkRect bounds = SkRect::MakeEmpty();
                if (plainText) {
                    const float scale = canvas->getTotalMatrix().getMinScale(), edge = room + (scale > 0 ? 2 / scale : 2);
                    bounds = mc.mapRect(TextInkBounds(l)).makeOutset(edge, edge);
                } else if (!l.text && l.kind != "embedScene" && l.kind != "model") {
                    bounds = pc.makeTransform(mc).getBounds().makeOutset(room, room);
                }
                SkRect device = SkRect::Make(seen);
                if (!bounds.isEmpty() && !device.intersect(canvas->getTotalMatrix().mapRect(bounds))) device.setEmpty();
                spent += device.width() * device.height();
                canvas->saveLayer(bounds.isEmpty() ? nullptr : &bounds, &paint);
            }
            const Drawn d = DrawBody(canvas, l, c.timeMs, tc, mc, pc, layered ? alpha : alpha * c.look.alpha, pictures, false);
            if (layered) canvas->restore();
            canvas->restore();
            if (spent >= 8 * frame) {
                ReleaseLayers(canvas);
                spent = 0;
            }
            if (now) drawn = d;
        }
    };
    auto picture = [&](SkCanvas* canvas) {
        if (!early) return body(canvas);
        SkPaint edges;
        edges.setImageFilter(BorderFilter(l, t, true, PixelUnit(canvas)));
        canvas->saveLayer(nullptr, &edges);
        body(canvas);
        canvas->restore();
    };
    const Layer* under = nullptr;
    for (size_t i = 1; effects && i < siblings.size(); i++) {
        if (&siblings[i] == &l) under = &siblings[i - 1];
    }
    std::function<void(SkCanvas*)> below;
    if (under) below = [&](SkCanvas* canvas) {
        Override plain;
        plain.unitOpacity = true;
        DrawOne(canvas, siblings, *under, timeMs, pictures, plain);
    };
    if (!effects || !gEffects->draw(canvas, *gScene, l, timeMs, scenePath.getBounds(), m, picture, below)) picture(canvas);
    if (effects && gEffects->lifted(l) && !scenePath.isEmpty()) {
        SkPaint mask;
        mask.setAntiAlias(true);
        if (copies || pixelEdged) {
            SkPaint keep;
            keep.setBlendMode(SkBlendMode::kDstIn);
            canvas->saveLayer(nullptr, &keep);
            if (pixelEdged) body(canvas);
            else for (const auto& copy : CopyOutlines(l, *copies, [&](float at, const SkMatrix& placed) { return BodyPath(l, at).makeTransform(placed); }))
                canvas->drawPath(copy.first, mask);
            canvas->restore();
        } else {
            SkPath outside = scenePath;
            outside.toggleInverseFillType();
            mask.setBlendMode(SkBlendMode::kClear);
            canvas->drawPath(outside, mask);
        }
    }
    if (!decorate) return;
    if (l.kind == "embedScene") return DrawGroupBorders(canvas, l, t);
    if (!bordered) return;
    if (pixelEdged) {
        if (!early) DrawGroupBorders(canvas, l, t, true);
        return;
    }
    const auto regionAt = [&](float at, const SkMatrix& placed, const SkPath& shape, Drawn d) {
        if (copies) {
            d.fill = l.fillType == "color" ? ColorAt(l.fillColor, at, kWhite, alpha).fA > 0 : l.fillType == "media" || l.fillType == "gradient";
            if (l.stroke.present && l.stroke.enabled) d.stroked = DrawStroke(nullptr, l, StrokeGeometry(l, shape, at).makeTransform(placed), at, alpha);
        }
        return Union(d.fill ? (l.text ? TextGlyphPath(l, at, Fps()) : shape).makeTransform(placed) : SkPath(), d.stroked);
    };
    const std::vector<std::pair<SkPath, float>> regions = copies
        ? CopyOutlines(l, *copies, [&](float at, const SkMatrix& placed) { return regionAt(at, placed, BodyPath(l, at), drawn); })
        : std::vector<std::pair<SkPath, float>>{{regionAt(t, m, path, drawn), t}};
    const auto seeThrough = [&](float at) {
        const bool fill = l.fillType == "color" ? ColorAt(l.fillColor, at, kWhite, alpha).fA < 1
                        : l.fillType == "gradient" && std::min(l.gradient.startColor[0], l.gradient.endColor[0]) * alpha < 1;
        return fill || (l.stroke.present && l.stroke.enabled && ColorAt(l.stroke.color, at, kBlack, alpha).fA < 1);
    };
    const bool hollow = std::any_of(regions.begin(), regions.end(), [&](const auto& r) { return seeThrough(r.second); }) ||
                        (copies && std::any_of(copies->begin(), copies->end(), [](const LayerCopy& c) { return c.look.alpha < 1; }));
    SkPaint clear, seam, beneath;
    clear.setAntiAlias(true);
    clear.setBlendMode(SkBlendMode::kClear);
    seam.setStyle(SkPaint::kStroke_Style);
    seam.setStrokeJoin(SkPaint::kRound_Join);
    seam.setStrokeWidth((l.text ? 5 : 2) * PixelUnit(canvas));
    beneath.setBlendMode(SkBlendMode::kDstOver);
    std::vector<SkPath> seams;
    for (const Edge& e : l.borders) {
        SkRect band = SkRect::MakeEmpty();
        for (const auto& [region, at] : regions) {
            if (!ShaderBorder(l, e, at) || region.isEmpty()) continue;
            const float reach = e.size.get(0, at, 6) / 2 + 1;
            if (hollow && e.direction == "outside") band.join(region.getBounds().makeOutset(reach, reach));
            else DrawBorder(canvas, region, e, at);
        }
        if (band.isEmpty()) continue;
        if (seams.empty()) for (const auto& [region, at] : regions) seams.push_back(skpathutils::FillPathWithPaint(region, seam));
        canvas->saveLayer(&band, &beneath);
        for (const auto& [region, at] : regions) {
            if (ShaderBorder(l, e, at) && !region.isEmpty()) DrawBorder(canvas, region, e, at);
        }
        for (size_t i = 0; i < regions.size(); i++) {
            canvas->save();
            canvas->clipPath(seams[i], SkClipOp::kDifference, true);
            canvas->drawPath(regions[i].first, clear);
            canvas->restore();
        }
        canvas->restore();
    }
}

struct FrozenScope {
    const Frozen* saved;
    explicit FrozenScope(const Frozen* now) : saved(gFrozen) { gFrozen = now; }
    ~FrozenScope() { gFrozen = saved; }
};

float Reach(const std::vector<Layer>& siblings, const Layer& l, double timeMs, double open) {
    const SkRect box = ShapePath(l, l.localTime(timeMs)).getBounds();
    const SkMatrix from = SiblingMatrix(siblings, l, timeMs - open / 2), to = SiblingMatrix(siblings, l, timeMs + open / 2);
    SkPoint points[5];
    box.toQuad(points);
    points[4] = box.center();
    float most = 0;
    for (const SkPoint& p : points) most = std::max(most, (to.mapPoint(p) - from.mapPoint(p)).length());
    return std::isfinite(most) ? most : 0;
}

void DrawShuttered(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs,
                   const MediaPictures& pictures, const Override& ov, const ShutterBlur& shutter) {
    const double open = 1000.0 / std::max(gScene ? gScene->fps : 30, 1) * shutter.angle / 360;
    Override sample = ov;
    sample.shuttered = true;
    const Frozen frozen{l.id, timeMs, shutter.position, shutter.scale, shutter.rotation};
    const bool restricted = !(shutter.position && shutter.scale && shutter.rotation);
    FrozenScope scope(restricted ? &frozen : gFrozen);
    SkPaint composite;
    if (shutter.fast) {
        const SkPoint middle = ShapePath(l, l.localTime(timeMs)).getBounds().center();
        const SkVector moved = SiblingMatrix(siblings, l, timeMs + open / 2).mapPoint(middle) -
                               SiblingMatrix(siblings, l, timeMs - open / 2).mapPoint(middle);
        composite.setImageFilter(SmearFilter(moved));
        if (!composite.getImageFilter()) return DrawOne(canvas, siblings, l, timeMs, pictures, sample);
    }
    if (ov.blend || !IsNormal(l.blending)) {
        if (ov.blend) composite.setBlendMode(ov.mode);
        else SetBlend(&composite, l.blending);
        sample.blend = true;
        sample.mode = SkBlendMode::kSrcOver;
    }
    if (shutter.fast) {
        canvas->saveLayer(nullptr, &composite);
        DrawOne(canvas, siblings, l, timeMs, pictures, sample);
        canvas->restore();
        return;
    }
    canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &composite, SkCanvas::kF16ColorType));
    int samples = shutter.samples;
    if (shutter.adaptive) samples = std::clamp((int)std::ceil(Reach(siblings, l, timeMs, open) / 2), samples, 48);
    SkPaint add;
    add.setBlendMode(SkBlendMode::kPlus);
    add.setAlphaf(1.0f / samples);
    for (int i = 0; i < samples; i++) {
        canvas->saveLayer(nullptr, &add);
        DrawOne(canvas, siblings, l, std::clamp(timeMs + open * ((i + 0.5) / samples - 0.5), (double)l.startTime, (double)l.endTime), pictures, sample);
        canvas->restore();
        if (gBreak) (*gBreak)();
    }
    canvas->restore();
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
            if (gBreak) (*gBreak)();
        };
        if (!echo.atop) for (int i = echo.count; i >= 1; i--) past(i);
        DrawOne(canvas, siblings, l, timeMs, pictures, plain);
        if (echo.atop) for (int i = 1; i <= echo.count; i++) past(i);
        return;
    }
    ShutterBlur shutter;
    if (!ov.shuttered && ShutterBlurOf(l, timeMs, &shutter) && !SeesPrevious(siblings, l)) {
        DrawShuttered(canvas, siblings, l, timeMs, pictures, ov, shutter);
        return;
    }
    const float t = l.localTime(timeMs);
    const float opacity = std::clamp(ov.unitOpacity ? ov.alpha : l.opacity.get(0, t, 1) * MotionOpacity(l, timeMs) * ov.alpha, 0.0f, 1.0f);
    const bool group = l.kind == "embedScene";
    bool decorated = false;
    const bool pixelEdged = PixelEdged(l, pictures);
    for (const Edge& e : l.borders) decorated |= ShaderBorder(l, e, t, pixelEdged);
    const bool shadow = ShadowOn(l);
    decorated = decorated || shadow || EffectsApply(siblings, l);
    const bool blended = ov.blend || !IsNormal(l.blending);
    const bool centred = ShapeKind(l) && !pixelEdged && std::any_of(l.borders.begin(), l.borders.end(), [](const Edge& e) { return e.enabled && e.direction == "centered"; });
    const bool repeated = HasRepeat(l);
    const std::vector<LayerCopy> copies = repeated ? CopiesOf(siblings, l, timeMs, gScene ? gScene->width : 0, gScene ? gScene->height : 0) : std::vector<LayerCopy>();
    const bool overlaps = l.kind == "drawing" || l.kind == "model" || (l.stroke.present && l.stroke.enabled) || centred || repeated;
    auto centredBorders = [&](float alpha) {
        if (!centred) return;
        const std::vector<std::pair<SkPath, float>> outlines = repeated
            ? CopyOutlines(l, copies, [&](float at, const SkMatrix& placed) { return ShapePath(l, at).makeTransform(placed); })
            : std::vector<std::pair<SkPath, float>>{{ShapePath(l, t).makeTransform(SiblingMatrix(siblings, l, timeMs)), t}};
        for (const Edge& e : l.borders) {
            if (!e.enabled || e.direction != "centered") continue;
            for (const auto& [outline, at] : outlines) {
                const float size = e.size.get(0, at, 4);
                if (!(size > 0)) continue;
                SkPaint paint = FillPaint(ColorAt(e.color, at, kWhite, alpha));
                paint.setStyle(SkPaint::kStroke_Style);
                paint.setStrokeWidth(size);
                paint.setStrokeCap(SkPaint::kRound_Cap);
                paint.setStrokeJoin(SkPaint::kBevel_Join);
                canvas->drawPath(outline, paint);
            }
        }
    };
    if (!group && !decorated && !blended && (opacity >= 1 || !overlaps)) {
        DrawContent(canvas, siblings, l, timeMs, opacity, false, pictures, repeated ? &copies : nullptr);
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
        canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &layerPaint, SeesPrevious(siblings, l) ? SkCanvas::kInitWithPrevious_SaveLayerFlag : 0));
        DrawContent(canvas, siblings, l, timeMs, 1, decorated, pictures, repeated ? &copies : nullptr);
        centredBorders(1);
        canvas->restore();
    }
}

}

void SetLayerBreak(const std::function<void()>* each) { gBreak = each; }

void SetLayerEffects(const LayerEffects* effects, const Scene* scene, bool world) {
    gEffects = effects;
    gScene = scene;
    gWorld = world;
}

bool WorldView() { return gWorld; }

void ParseStyle(const XmlNode& n, Layer* l) {
    l->blending = AttrOf(n, "blending");
    l->clippingMask = AttrOf(n, "clippingMask") == "true";
    l->parent = strtoll(AttrOf(n, "parent").c_str(), nullptr, 10);
    l->pathLayer = strtoll(AttrOf(n, "pathLayer").c_str(), nullptr, 10);
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

static bool ContainsOne(const std::vector<Layer>& siblings, const Layer& l, double timeMs, SkPoint p, const MediaPictures& masks);

static bool Contains(const std::vector<Layer>& siblings, const Layer& l, double timeMs, SkPoint p, int width, int height,
                     const MediaPictures& masks) {
    if (!l.activeAt(timeMs) || l.kind == "nullobj" || l.kind == "camera") return false;
    if (!HasRepeat(l)) return ContainsOne(siblings, l, timeMs, p, masks);
    for (const LayerCopy& c : CopiesOf(siblings, l, timeMs, width, height)) {
        SkMatrix inverse;
        if (c.scene.invert(&inverse) && ContainsOne(siblings, l, c.timeMs, inverse.mapPoint(p), masks)) return true;
    }
    return false;
}

static bool CutoutContains(const Layer& l, float t, const SkPath& path, const SkImage& mask, SkPoint local, float scale) {
    SkPixmap pixels;
    const SkRect dst = MediaRect(l, path.getBounds(), mask.width(), mask.height());
    if (dst.isEmpty() || !mask.peekPixels(&pixels)) return path.contains(local.fX, local.fY);
    auto seen = [&](SkPoint q) {
        const int x = (int)std::floor((q.fX - dst.left()) / dst.width() * pixels.width());
        const int y = (int)std::floor((q.fY - dst.top()) / dst.height() * pixels.height());
        return x >= 0 && y >= 0 && x < pixels.width() && y < pixels.height() && path.contains(q.fX, q.fY) && pixels.getAlphaf(x, y) >= 0.5f;
    };
    if (seen(local)) return true;
    const float width = l.stroke.present && l.stroke.enabled ? l.stroke.size.get(0, t, 0) : 0;
    const float reach = width / 2 / std::max(scale, 1e-6f);
    for (int k = 0; reach > 0 && k < 16; k++) {
        const float angle = k * 3.14159265f / 4, r = k < 8 ? reach : reach / 2;
        if (seen({local.fX + r * cosf(angle), local.fY + r * sinf(angle)})) return true;
    }
    return false;
}

static bool ContainsOne(const std::vector<Layer>& siblings, const Layer& l, double timeMs, SkPoint p, const MediaPictures& masks) {
    const SkMatrix matrix = SiblingMatrix(siblings, l, timeMs);
    SkMatrix inverse;
    if (!matrix.invert(&inverse)) return false;
    const SkPoint local = inverse.mapPoint(p);
    if (l.kind == "drawing") return DrawingContains(l, local);
    if (l.kind != "embedScene") {
        const float t = l.localTime(timeMs);
        const SkPath path = ShapePath(l, t);
        const sk_sp<SkImage> mask = ShapeKind(l) && !l.text && !l.video && l.fillType == "media" ? masks(l.mediaUri, l.id) : nullptr;
        if (mask) return CutoutContains(l, t, path, *mask, local, std::sqrt(std::fabs(matrix.getScaleX() * matrix.getScaleY() - matrix.getSkewX() * matrix.getSkewY())));
        if (l.fillType != "none" && path.contains(local.fX, local.fY)) return true;
        if (!(l.stroke.present && l.stroke.enabled)) return false;
        return DrawStroke(nullptr, l, StrokeGeometry(l, path, t).makeTransform(matrix), t, 1).contains(p.fX, p.fY);
    }
    const SkPoint inner = {local.fX + l.innerWidth / 2.0f, local.fY + l.innerHeight / 2.0f};
    if (!SkRect::MakeWH(l.innerWidth, l.innerHeight).contains(inner.fX, inner.fY)) return false;
    const double innerTime = NestedTime(l, timeMs);
    if (innerTime >= 0 && (l.innerBackground >> 24) != 0) return true;
    for (auto it = l.children.rbegin(); innerTime >= 0 && it != l.children.rend(); ++it) {
        if (Contains(l.children, *it, innerTime, inner, l.innerWidth, l.innerHeight, masks)) return true;
    }
    return false;
}

static bool ContentRect(const Layer& l, double timeMs, const SkMatrix& matrix, SkRect* out) {
    const SkPath shape = ShapePath(l, l.localTime(timeMs));
    if (shape.isEmpty()) return false;
    *out = matrix.mapRect(shape.getBounds());
    if (l.kind != "embedScene") return true;
    const double innerTime = NestedTime(l, timeMs);
    if (innerTime >= 0 && (l.innerBackground >> 24) != 0) return true;
    const SkMatrix origin = SkMatrix::Concat(matrix, SkMatrix::Translate(-l.innerWidth / 2.0f, -l.innerHeight / 2.0f));
    float left = INFINITY, top = INFINITY, right = -INFINITY, bottom = -INFINITY;
    SkRect child;
    for (const Layer& c : l.children) {
        if (innerTime < 0 || !c.activeAt(innerTime) || c.kind == "nullobj" || c.kind == "camera") continue;
        if (!ContentRect(c, innerTime, SkMatrix::Concat(origin, SiblingMatrix(l.children, c, innerTime)), &child)) continue;
        left = std::min(left, child.fLeft);
        top = std::min(top, child.fTop);
        right = std::max(right, child.fRight);
        bottom = std::max(bottom, child.fBottom);
    }
    const SkRect seen = SkRect::MakeLTRB(std::max(left, out->fLeft), std::max(top, out->fTop), std::min(right, out->fRight), std::min(bottom, out->fBottom));
    if (seen.fLeft <= seen.fRight && seen.fTop <= seen.fBottom) *out = seen;
    return true;
}

SkRect SelectionBounds(const Layer& layer, double timeMs, const SkMatrix& matrix) {
    if (layer.kind != "embedScene") return ShapePath(layer, layer.localTime(timeMs)).makeTransform(matrix).getBounds();
    SkRect bounds = SkRect::MakeEmpty();
    ContentRect(layer, timeMs, matrix, &bounds);
    return bounds;
}

static const std::vector<Layer>& SeenProject(const Scene& scene, double timeMs, std::vector<Layer>* storage) {
    return SeeLayers(scene.layers, scene.width, scene.height, true, scene.fps, timeMs, storage) ? *storage : scene.layers;
}

int64_t TopLayerAt(const Scene& scene, double timeMs, float x, float y, const MediaPictures& masks) {
    std::vector<Layer> storage;
    const std::vector<Layer>& list = SeenProject(scene, timeMs, &storage);
    for (auto it = list.rbegin(); it != list.rend(); ++it) {
        if (Contains(list, *it, timeMs, {x, y}, scene.width, scene.height, masks)) return it->id;
    }
    if (CameraViewOn()) return 0;
    for (auto it = scene.layers.rbegin(); it != scene.layers.rend(); ++it) {
        SkPoint frame[4];
        if (it->kind == "camera" && it->activeAt(timeMs) && CameraFrame(scene.layers, *it, scene.width, scene.height, timeMs, frame) &&
            SkPath::Polygon({frame, 4}, true).contains(x, y)) return it->id;
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

bool Shared(const LayerEffect& e) { return e.toChildren && !e.hidden && !MotionEffect(e.id) && !RepeatEffect(e.id); }

bool InheritEffects(const std::vector<Layer>& layers, std::vector<Layer>* out) {
    if (std::none_of(layers.begin(), layers.end(), [](const Layer& l) { return std::any_of(l.effects.begin(), l.effects.end(), Shared); }))
        return false;
    *out = layers;
    for (Layer& l : *out) {
        int64_t up = l.parent;
        for (int depth = 0; up != 0 && depth < kMaxParentDepth; depth++) {
            auto parent = std::find_if(layers.begin(), layers.end(), [&](const Layer& p) { return p.id == up && p.id != l.id; });
            if (parent == layers.end()) break;
            for (const LayerEffect& e : parent->effects) {
                if (Shared(e)) l.effects.push_back(e);
            }
            up = parent->parent;
        }
    }
    return true;
}

void DrawList(SkCanvas* canvas, const std::vector<Layer>& listed, int width, int height, bool top, double timeMs,
              const MediaPictures& pictures) {
    std::vector<Layer> inherited, storage;
    const std::vector<Layer>& source = InheritEffects(listed, &inherited) ? inherited : listed;
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
            if (top && gBreak && EffectsApply(layers, base)) (*gBreak)();
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
        SkPaint atop;
        atop.setBlendMode(SkBlendMode::kSrcATop);
        for (size_t k = i + 1; k < end; k++) {
            if (IsNormal(layers[k].blending)) {
                DrawOne(canvas, layers, layers[k], timeMs, pictures, clipped);
                continue;
            }
            canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &atop, SkCanvas::kInitWithPrevious_SaveLayerFlag));
            DrawOne(canvas, layers, layers[k], timeMs, pictures, Override());
            canvas->restore();
        }
        canvas->restore();
    }
    gEffectList = outer;
}

}

void DrawLayerList(SkCanvas* canvas, const Scene& scene, const std::vector<Layer>& layers, double timeMs, const MediaPictures& pictures) {
    DrawList(canvas, layers, scene.width, scene.height, true, timeMs, pictures);
}

}
