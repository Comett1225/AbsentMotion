#include "platform_log.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <string_view>
#include <unordered_map>

#include "include/core/SkBBHFactory.h"
#include "include/core/SkBlender.h"
#include "include/core/SkBlurTypes.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkImage.h"
#include "include/core/SkMaskFilter.h"
#include "include/core/SkPaint.h"
#include "include/core/SkRRect.h"
#include "include/core/SkRSXform.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPathMeasure.h"
#include "include/core/SkM44.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "include/core/SkSurface.h"
#include "include/effects/SkGradient.h"
#include "include/effects/SkImageFilters.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/pathops/SkPathOps.h"
#include "depth.h"
#include "drawing.h"
#include "particles.h"
#include "pieces3d.h"
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
        SkPoint followScale;
        float followRotation;
        ParentTerms(l, timeMs, p, &followScale, &followRotation);
        own.scale = {own.scale.fX * followScale.fX, own.scale.fY * followScale.fY};
        if (p.tilt != SkM44()) own.tilt = Turn(-own.rotation) * p.tilt * Turn(own.rotation) * own.tilt;
        own.rotation += followRotation;
        own.z = p.z + offset.z;
        ParentRoll(l, timeMs, &own);
    }
    if (!FollowsPath(l)) return own;
    const Layer* source = PathSource(siblings, l);
    if (!source) return own;
    const Layer& below = *source;
    const float t = below.localTime(timeMs);
    const SkPath path = below.text ? TextGlyphPath(below, t, Fps()) : Cropped(below, t, ShapePath(below, t));
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
    const SkPath tapered = l.kind == "shape" && !l.text ? TaperedStroke(l, path, width, t) : SkPath();
    SkPath area = !outline ? SkPath() : tapered.isEmpty() ? skpathutils::FillPathWithPaint(path, paint) : tapered;
    const auto add = [&](const SkPath& end) { if (outline) area = Union(area, end); };
    const bool layered = canvas && ends && color.fA < 1;
    if (layered) {
        SkPaint opacity;
        opacity.setAlphaf(color.fA);
        const float room = width * std::max(l.stroke.endSize, 1.0f) * 3;
        canvas->saveLayer(path.getBounds().makeOutset(room, room), &opacity);
        paint.setAlphaf(1);
    }
    if (canvas && tapered.isEmpty()) canvas->drawPath(path, paint);
    if (canvas && !tapered.isEmpty()) {
        SkPaint fill = paint;
        fill.setStyle(SkPaint::kFill_Style);
        canvas->drawPath(tapered, fill);
    }
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
    bool feedback = false;
    bool shuttered = false;
    float alpha = 1;
};

void DrawOne(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs,
             const MediaPictures& pictures, const Override& ov);
void DrawList(SkCanvas* canvas, const std::vector<Layer>& layers, int width, int height, bool top, double timeMs,
              const MediaPictures& pictures);

void DrawGroup(SkCanvas* canvas, const Layer& l, double timeMs, const MediaPictures& pictures) {
    canvas->translate(-l.innerWidth / 2.0f, -l.innerHeight / 2.0f);
    const double inner = NestedTime(l, timeMs);
    if (inner < 0) return;
    struct Context {
        const Scene* previous;
        Scene scene;
        explicit Context(const Layer& group) : previous(gScene) {
            scene.width = group.innerWidth;
            scene.height = group.innerHeight;
            scene.fps = std::max(1, (int)group.retiming.fps);
            scene.depthOrder = previous && previous->depthOrder;
            scene.pieceOrder = previous && previous->pieceOrder;
            if (previous) {
                for (const double beat : previous->beats) {
                    const double inner = NestedTime(group, beat * 1000);
                    if (inner >= 0) scene.beats.push_back(inner / 1000);
                }
                std::sort(scene.beats.begin(), scene.beats.end());
                scene.beats.erase(std::unique(scene.beats.begin(), scene.beats.end()), scene.beats.end());
            }
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
    if (l.innerBackground >> 24) {
        SkPaint background;
        background.setColor((SkColor)l.innerBackground);
        canvas->drawRect(SkRect::MakeWH((float)l.innerWidth, (float)l.innerHeight), background);
    }
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
        if (below && below->kind != "nullobj" && below->kind != "camera" && below->kind != "light") {
            const float tb = below->localTime(timeMs);
            space.below = (below->text ? TextGlyphPath(*below, tb, Fps()) : Cropped(*below, tb, ShapePath(*below, tb))).makeTransform(SiblingMatrix(siblings, *below, timeMs));
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

SkPath EdgePath(const Layer& l, float t) { return Cropped(l, t, BodyPath(l, t)); }

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

const char* const kParticles = "absent.particles";

bool Emits(const LayerEffect& e) { return !e.hidden && e.id == kParticles; }

float Setting(const LayerEffect& e, const char* name, float t, float fallback, int i = 0) {
    const Property* p = e.prop(name);
    const float v = p ? p->anim.get(i, t, fallback) : fallback;
    return std::isfinite(v) ? v : fallback;
}

float Highest(const LayerEffect& e, const char* name, float fallback) {
    const Property* p = e.prop(name);
    if (!p) return fallback;
    const Anim& a = p->anim;
    if (a.keys.empty()) return !a.value.empty() && std::isfinite(a.value[0]) ? a.value[0] : fallback;
    float most = -1e30f;
    for (const Keyframe& k : a.keys) {
        if (!k.v.empty() && std::isfinite(k.v[0])) most = std::max(most, k.v[0]);
    }
    if (a.hasLead && !a.lead.v.empty() && std::isfinite(a.lead.v[0])) most = std::max(most, a.lead.v[0]);
    return most > -1e30f ? most : fallback;
}

particles::Settings ParticleSettings(const LayerEffect& e, float t) {
    particles::Settings s;
    const auto v = [&](const char* name, float fallback) { return Setting(e, name, t, fallback); };
    const auto pick = [&](const char* name, int most) { return std::clamp((int)std::lround(v(name, 0)), 0, most); };
    s.kind = pick("kind", 8);
    s.emission = pick("emission", 2);
    s.colouring = pick("colorMode", 2);
    s.beatEvery = 1 << pick("beats", 2);
    s.follow = pick("orientation", 1) == 1;
    s.prewarm = v("prewarm", 1) >= 0.5f;
    s.depth = v("depth", 0) >= 0.5f;
    s.zSpeed = v("zSpeed", 0);
    s.zSpread = v("zSpread", 0);
    s.perspective = v("perspective", 1);
    s.rate = v("rate", 40);
    s.count = v("count", 100);
    s.start = v("start", 0);
    s.every = v("every", 0);
    s.bpm = v("bpm", 120);
    s.lifetime = v("lifetime", 10);
    s.lifeVariation = v("lifeVariation", 0.2f);
    s.speed = v("speed", 30);
    s.speedVariation = v("speedVariation", 0.5f);
    s.direction = v("direction", 90);
    s.spread = v("spread", 40);
    s.gravity = v("gravity", 300);
    s.wind = v("wind", 0);
    s.drag = v("drag", 1.2f);
    s.swirl = v("swirl", 18);
    s.swirlSpeed = v("swirlSpeed", 0.25f);
    s.size = v("size", 1);
    s.sizeVariation = v("sizeVariation", 0.6f);
    s.endSize = v("endSize", 1);
    s.spin = v("spin", 0);
    s.spinVariation = v("spinVariation", 0.5f);
    s.randomRotation = v("randomRotation", 1);
    s.fadeIn = v("fadeIn", 0.05f);
    s.fadeOut = v("fadeOut", 0.15f);
    s.twinkle = v("twinkle", 0);
    s.colorVariation = v("colorVariation", 0);
    const float second[3] = {0x8f / 255.0f, 0xd3 / 255.0f, 1};
    for (int i = 0; i < 3; i++) {
        s.color[i] = Setting(e, "color", t, 1, i + 1);
        s.color2[i] = Setting(e, "color2", t, second[i], i + 1);
    }
    return s;
}

constexpr int kCell = 96, kSheetColumns = 4, kSheetRows = 3;
constexpr float kSpriteSize[] = {18, 36, 28, 44, 16, 34, 30, 30};

int SpriteCell(int kind, int frame) {
    static const int cells[] = {0, 1, 2, 6, 7, 8, 9, 10};
    return kind == particles::kConfetti ? 2 + std::clamp(frame, 0, 3) : cells[std::clamp(kind, 0, 7)];
}

SkRect CellRect(int cell) {
    return SkRect::MakeXYWH((float)(cell % kSheetColumns * kCell), (float)(cell / kSheetColumns * kCell), (float)kCell, (float)kCell);
}

sk_sp<SkShader> Fade(float radius, const std::vector<std::pair<float, float>>& stops) {
    std::vector<SkColor4f> colors;
    std::vector<float> at;
    for (const auto& [where, alpha] : stops) {
        at.push_back(where);
        colors.push_back({1, 1, 1, alpha});
    }
    return SkShaders::RadialGradient({0, 0}, radius, SkGradient(SkGradient::Colors(colors, at, SkTileMode::kClamp), {}));
}

sk_sp<SkImage> SpriteSheet() {
    static const sk_sp<SkImage> sheet = [] {
        SkPictureRecorder recorder;
        SkCanvas* c = recorder.beginRecording(SkRect::MakeWH((float)(kSheetColumns * kCell), (float)(kSheetRows * kCell)));
        const float r = kCell / 2.0f - 6;
        const auto cell = [&](int i, const std::function<void(SkPaint&)>& draw) {
            SkPaint paint;
            paint.setAntiAlias(true);
            paint.setColor(SK_ColorWHITE);
            c->save();
            c->translate((i % kSheetColumns + 0.5f) * kCell, (i / kSheetColumns + 0.5f) * kCell);
            draw(paint);
            c->restore();
        };
        cell(0, [&](SkPaint& p) {
            p.setShader(Fade(r, {{0, 1}, {0.35f, 0.92f}, {0.7f, 0.42f}, {1, 0}}));
            c->drawCircle(0, 0, r, p);
        });
        cell(1, [&](SkPaint& p) {
            const SkPoint ends[2] = {{-r, 0}, {r, 0}};
            const SkColor4f colors[] = {{1, 1, 1, 0}, {1, 1, 1, 0.5f}, {1, 1, 1, 1}};
            const float at[] = {0, 0.55f, 1};
            p.setShader(SkShaders::LinearGradient(ends, SkGradient(SkGradient::Colors(colors, at, SkTileMode::kClamp), {})));
            p.setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, r * 0.05f));
            c->drawRRect(SkRRect::MakeRectXY(SkRect::MakeLTRB(-r, -r * 0.14f, r, r * 0.14f), r * 0.14f, r * 0.14f), p);
        });
        for (int f = 0; f < 4; f++) {
            cell(2 + f, [&](SkPaint& p) {
                const float wide[] = {1, 0.72f, 0.42f, 0.14f}, grey[] = {1, 0.86f, 0.74f, 0.62f};
                p.setColor4f({grey[f], grey[f], grey[f], 1});
                const float w = r * 0.72f * wide[f], h = r * 0.42f;
                c->drawRRect(SkRRect::MakeRectXY(SkRect::MakeLTRB(-w, -h, w, h), 3, 3), p);
            });
        }
        cell(6, [&](SkPaint& p) {
            p.setShader(Fade(r, {{0, 0.05f}, {0.6f, 0.1f}, {0.84f, 0.45f}, {0.93f, 0.95f}, {1, 0}}));
            c->drawCircle(0, 0, r, p);
            SkPaint shine;
            shine.setAntiAlias(true);
            shine.setColor4f({1, 1, 1, 0.9f});
            shine.setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, r * 0.04f));
            c->save();
            c->translate(-r * 0.42f, -r * 0.42f);
            c->rotate(-45);
            c->drawOval(SkRect::MakeLTRB(-r * 0.22f, -r * 0.1f, r * 0.22f, r * 0.1f), shine);
            c->restore();
        });
        cell(7, [&](SkPaint& p) {
            p.setShader(Fade(r, {{0, 1}, {0.15f, 0.7f}, {0.45f, 0.2f}, {1, 0}}));
            c->drawCircle(0, 0, r, p);
        });
        cell(8, [&](SkPaint& p) {
            SkPathBuilder b;
            b.moveTo(0, r * 0.85f);
            b.cubicTo(-r * 0.2f, r * 0.62f, -r * 0.95f, r * 0.25f, -r * 0.95f, -r * 0.25f);
            b.cubicTo(-r * 0.95f, -r * 0.75f, -r * 0.3f, -r * 0.95f, 0, -r * 0.45f);
            b.cubicTo(r * 0.3f, -r * 0.95f, r * 0.95f, -r * 0.75f, r * 0.95f, -r * 0.25f);
            b.cubicTo(r * 0.95f, r * 0.25f, r * 0.2f, r * 0.62f, 0, r * 0.85f);
            b.close();
            c->drawPath(b.detach(), p);
        });
        cell(9, [&](SkPaint& p) {
            SkPathBuilder b;
            for (int i = 0; i < 10; i++) {
                const float a = (-90 + 36 * i) * kRadians, k = i % 2 ? r * 0.45f : r * 0.92f;
                if (i == 0) b.moveTo(std::cos(a) * k, std::sin(a) * k);
                else b.lineTo(std::cos(a) * k, std::sin(a) * k);
            }
            b.close();
            p.setStrokeJoin(SkPaint::kRound_Join);
            p.setStrokeWidth(r * 0.08f);
            p.setStyle(SkPaint::kStrokeAndFill_Style);
            c->drawPath(b.detach(), p);
        });
        cell(10, [&](SkPaint& p) {
            SkPathBuilder b;
            b.moveTo(0, r * 0.95f);
            b.cubicTo(r * 0.78f, r * 0.42f, r * 0.72f, -r * 0.55f, r * 0.3f, -r * 0.93f);
            b.lineTo(0, -r * 0.68f);
            b.lineTo(-r * 0.3f, -r * 0.93f);
            b.cubicTo(-r * 0.72f, -r * 0.55f, -r * 0.78f, r * 0.42f, 0, r * 0.95f);
            b.close();
            const SkPoint ends[2] = {{0, -r}, {0, r}};
            const SkColor4f colors[] = {{1, 1, 1, 1}, {0.8f, 0.8f, 0.8f, 1}};
            p.setShader(SkShaders::LinearGradient(ends, SkGradient(SkGradient::Colors(colors, SkTileMode::kClamp), {})));
            c->drawPath(b.detach(), p);
        });
        return SkImages::DeferredFromPicture(recorder.finishRecordingAsPicture(), {kSheetColumns * kCell, kSheetRows * kCell}, nullptr, nullptr,
                                             SkImages::BitDepth::kU8, SkColorSpace::MakeSRGB());
    }();
    return sheet;
}

sk_sp<SkImage> Uploaded(const sk_sp<SkImage>& image, bool mipmapped) {
    return image && gEffects && gEffects->upload ? gEffects->upload(image, mipmapped) : image;
}

struct Sprite {
    sk_sp<SkImage> image;
    SkPoint anchor = {0, 0};
    float scale = 1;
};

Sprite LayerSprite(SkCanvas* canvas, const Layer& l, double timeMs, float t, const SkMatrix& m, const SkPath& path, const MediaPictures& pictures,
                   float largest) {
    Sprite out;
    const SkRect box = path.getBounds();
    const SkPoint centre = m.mapPoint(box.center());
    const SkMatrix shape = SkMatrix::Translate(-centre.fX, -centre.fY) * m;
    const float room = l.stroke.present && l.stroke.enabled ? l.stroke.size.get(0, t, 0) * std::max(l.stroke.endSize, 1.0f) * 3 + 2 : 2;
    const SkRect drawn = shape.mapRect(box.makeOutset(room, room));
    if (drawn.isEmpty() || !drawn.isFinite()) return out;
    float scale = std::clamp(canvas->getTotalMatrix().getMaxScale(), 1.0f, 4.0f) * std::clamp(largest, 0.05f, 2.0f);
    const float side = std::max(drawn.width(), drawn.height()) * scale;
    if (side > 1024) scale *= 1024 / side;
    SkPictureRecorder recorder;
    DrawBody(recorder.beginRecording(drawn), l, timeMs, t, shape, path, 1, pictures, false);
    const SkMatrix place = SkMatrix::Translate(1, 1) * SkMatrix::Scale(scale, scale) * SkMatrix::Translate(-drawn.left(), -drawn.top());
    const SkISize size = {(int)std::ceil(drawn.width() * scale) + 2, (int)std::ceil(drawn.height() * scale) + 2};
    out.image = SkImages::DeferredFromPicture(recorder.finishRecordingAsPicture(), size, &place, nullptr, SkImages::BitDepth::kU8,
                                              SkColorSpace::MakeSRGB());
    out.anchor = place.mapPoint({0, 0});
    out.scale = scale;
    return out;
}

void DrawParticles(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, const LayerEffect& e, double timeMs, float t,
                   const SkMatrix& m, const SkPath& path, float alpha, const MediaPictures& pictures) {
    const double length = std::max(1, l.endTime - l.startTime);
    particles::Field field;
    field.seed = particles::Mix(particles::Mix((uint64_t)(l.noiseId ? l.noiseId : l.id)) ^
                                particles::Mix((uint64_t)std::llround((double)Setting(e, "seed", t, 0) * 1000) + 0x51ed27ull));
    field.unit = gScene && gScene->width > 0 && gScene->height > 0 ? std::min(gScene->width, gScene->height) / 1080.0f : 1.0f;
    field.rateMost = Highest(e, "rate", 40);
    field.lifeMost = Highest(e, "lifetime", 10) * (1 + std::clamp(Highest(e, "lifeVariation", 0.2f), 0.0f, 1.0f));
    field.animated = std::any_of(e.props.begin(), e.props.end(), [](const Property& p) { return !p.anim.keys.empty(); });
    field.fps = Fps();
    field.origin = l.startTime / 1000.0;
    if (gScene) field.beats = gScene->beats;
    field.at = [&](double s) { return ParticleSettings(e, (float)(s * 1000 / length)); };
    const SkRect box = path.getBounds();
    const int emitter = std::clamp((int)std::lround(Setting(e, "emitter", t, 1)), 0, 3);
    const float reach = std::max(0.0f, Setting(e, "emitterSize", t, 1));
    const SkPoint centre = box.center() + SkVector{Setting(e, "position", t, 0, 0), Setting(e, "position", t, 0, 1)};
    std::vector<SkPoint> rim;
    if (emitter == particles::kOutline) {
        const SkPath outline = l.text ? TextGlyphPath(l, t, Fps()) : path;
        SkPathMeasure measure(outline, false);
        float total = 0;
        do total += measure.getLength();
        while (measure.nextContour());
        SkPathMeasure walk(outline, false);
        float done = 0, span = walk.getLength();
        for (int i = 0; i < 512 && total > 0; i++) {
            const float d = (i + 0.5f) * total / 512;
            while (done + span < d && walk.nextContour()) {
                done += span;
                span = walk.getLength();
            }
            SkPoint p;
            if (walk.getPosTan(std::clamp(d - done, 0.0f, span), &p, nullptr)) rim.push_back(p);
        }
    }
    const double step = std::max(1.0 / std::max(field.fps, 1.0), field.lifeMost / 240.0);
    std::unordered_map<int64_t, SkMatrix> placed;
    const auto placedAt = [&](int64_t k) -> const SkMatrix& {
        auto found = placed.find(k);
        if (found == placed.end()) found = placed.emplace(k, SiblingMatrix(siblings, l, l.startTime + k * step * 1000)).first;
        return found->second;
    };
    field.emit = [&](double born, float u, float v, float direction, float* x, float* y) {
        SkPoint p = centre;
        if (emitter == particles::kArea) {
            p += SkVector{(u - 0.5f) * box.width() * reach, (v - 0.5f) * box.height() * reach};
        } else if (emitter == particles::kOutline && !rim.empty()) {
            p = rim[std::min(rim.size() - 1, (size_t)(u * rim.size()))];
        } else if (emitter == particles::kLine) {
            const float a = direction * kRadians;
            SkMatrix inverse;
            const bool turned = placedAt((int64_t)std::floor(born / step)).invert(&inverse);
            SkVector d = turned ? inverse.mapVector(std::cos(a), std::sin(a)) : SkVector{std::cos(a), std::sin(a)};
            if (!d.normalize()) d = {0, 1};
            const SkVector across = {-d.fY, d.fX};
            const float back = (std::fabs(d.fX) * box.width() + std::fabs(d.fY) * box.height()) / 2;
            const float half = (std::fabs(across.fX) * box.width() + std::fabs(across.fY) * box.height()) / 2 * reach;
            p += across * ((2 * u - 1) * half) - d * back;
        }
        const double at = born / step;
        const int64_t k = (int64_t)std::floor(at);
        const float w = (float)(at - k);
        const SkPoint a = placedAt(k).mapPoint(p), b = w > 0 ? placedAt(k + 1).mapPoint(p) : a;
        *x = a.fX + (b.fX - a.fX) * w;
        *y = a.fY + (b.fY - a.fY) * w;
    };
    std::unordered_map<int64_t, SkM44> orientations;
    if (Setting(e, "depth", t, 0) >= 0.5f) field.orient = [&](double born, float* out) {
        const int64_t k = (int64_t)std::floor(born / step);
        auto found = orientations.find(k);
        if (found == orientations.end()) {
            const Placement p = Effective(siblings, l, l.startTime + k * step * 1000, 0);
            found = orientations.emplace(k, Turn(p.rotation) * p.tilt * Turn(-p.rotation)).first;
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) out[row * 3 + col] = found->second.rc(row, col);
    };
    std::vector<particles::Particle> list;
    particles::Simulate(field, std::max(0.0, (l.steppedMs(timeMs) - l.startTime) / 1000.0), &list);
    if (list.empty()) return;
    const particles::Settings look = ParticleSettings(e, t);
    const bool layered = look.kind == particles::kLayer;
    Sprite sprite;
    if (layered) {
        sprite = LayerSprite(canvas, l, timeMs, t, m, path, pictures,
                             Highest(e, "size", 1) * (1 + std::clamp(look.sizeVariation, 0.0f, 1.0f)) * std::max(1.0f, look.endSize));
        sprite.image = Uploaded(sprite.image, false);
    } else {
        sprite.image = Uploaded(SpriteSheet(), true);
        sprite.anchor = {kCell / 2.0f, kCell / 2.0f};
        sprite.scale = kCell / (kSpriteSize[std::clamp(look.kind, 0, 7)] * field.unit);
    }
    if (!sprite.image) return;
    const float tint = layered ? std::clamp(Setting(e, "tint", t, 0), 0.0f, 1.0f) : 1.0f;
    const SkRect whole = SkRect::MakeIWH(sprite.image->width(), sprite.image->height());
    const SkRect seen = canvas->getLocalClipBounds();
    std::vector<SkRSXform> xforms;
    std::vector<SkRect> cells;
    std::vector<SkColor> colors;
    std::vector<float> depths;
    xforms.reserve(list.size());
    cells.reserve(list.size());
    colors.reserve(list.size());
    SkRect cull = SkRect::MakeEmpty();
    for (const particles::Particle& p : list) {
        const SkRect cell = layered ? whole : CellRect(SpriteCell(look.kind, p.frame));
        const float scale = p.size / sprite.scale, r = scale * std::max(cell.width(), cell.height());
        const SkRect around = SkRect::MakeLTRB(p.x - r, p.y - r, p.x + r, p.y + r);
        if (!SkRect::Intersects(around, seen)) continue;
        cull.join(around);
        xforms.push_back(SkRSXform::MakeFromRadians(scale, p.angle, p.x, p.y, sprite.anchor.fX, sprite.anchor.fY));
        cells.push_back(cell);
        colors.push_back(SkColor4f{1 + (p.r - 1) * tint, 1 + (p.g - 1) * tint, 1 + (p.b - 1) * tint, std::clamp(p.alpha, 0.0f, 1.0f)}.toSkColor());
        depths.push_back(p.z);
    }
    if (xforms.empty()) return;
    const int blend = std::clamp((int)std::lround(Setting(e, "blend", t, 0)), 0, 2);
    SkPaint paint;
    paint.setAlphaf(std::clamp(alpha, 0.0f, 1.0f));
    paint.setBlendMode(blend == 1 ? SkBlendMode::kPlus : blend == 2 ? SkBlendMode::kScreen : SkBlendMode::kSrcOver);
    const SkSamplingOptions sampling = layered ? SkSamplingOptions(SkFilterMode::kLinear) : SkSamplingOptions(SkFilterMode::kLinear, SkMipmapMode::kLinear);
    if (depth::Pass* pass = depth::Active(); pass && pass->target == &l) {
        const int source = pass->add(canvas->getTotalMatrix(), blend == 0 ? pass->styled(paint) : paint);
        pass->sources[source].sprite = sprite.image;
        pass->sources[source].sampling = sampling;
        for (size_t i = 0; i < xforms.size(); i++) pass->sprite(source, xforms[i], cells[i], colors[i], pass->at(depths[i]));
        return;
    }
    canvas->drawAtlas(sprite.image.get(), xforms, cells, colors, SkBlendMode::kModulate, sampling, &cull, &paint);
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
    const auto order = [&](const LayerEffect& e) { return std::clamp((int)std::lround(Setting(e, "layer", t, 0)), 0, 2); };
    const bool emits = std::any_of(l.effects.begin(), l.effects.end(), Emits);
    const bool alone = emits && std::any_of(l.effects.begin(), l.effects.end(), [&](const LayerEffect& e) { return Emits(e) && order(e) == 2; });
    auto own = [&](SkCanvas* canvas) {
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
            const SkMatrix& mc = c.layer;
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
    auto body = [&](SkCanvas* canvas) {
        if (!emits) return own(canvas);
        depth::Pass* const collector = depth::Active() && depth::Active()->target == &l ? depth::Active() : nullptr;
        const auto mine = [&](SkCanvas* canvas) {
            if (!collector) return own(canvas);
            SkPictureRecorder recorder;
            SkCanvas* c = recorder.beginRecording(SkRect::MakeLTRB(-1e6f, -1e6f, 1e6f, 1e6f));
            c->setMatrix(canvas->getLocalToDevice());
            own(c);
            collector->picture(recorder.finishRecordingAsPicture(), SkMatrix::I(), collector->depth);
        };
        const auto pass = [&](bool behind) {
            for (const LayerEffect& e : l.effects) {
                if (Emits(e) && (order(e) == 1) == behind) DrawParticles(canvas, siblings, l, e, timeMs, t, m, path, alpha, pictures);
            }
        };
        pass(true);
        if (!alone) mine(canvas);
        pass(false);
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
    if (effects && gEffects->lifted(l) && !(gEffects->spills && gEffects->spills(l)) && !scenePath.isEmpty()) {
        SkPaint mask;
        mask.setAntiAlias(true);
        if (copies || pixelEdged) {
            SkPaint keep;
            keep.setBlendMode(SkBlendMode::kDstIn);
            canvas->saveLayer(nullptr, &keep);
            if (pixelEdged) body(canvas);
            else for (const auto& copy : CopyOutlines(l, *copies, [&](float at, const SkMatrix& placed) { return EdgePath(l, at).makeTransform(placed); }))
                canvas->drawPath(copy.first, mask);
            canvas->restore();
        } else {
            SkPath outside = Cropped(l, t, path).makeTransform(m);
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
        ? CopyOutlines(l, *copies, [&](float at, const SkMatrix& placed) { return regionAt(at, placed, EdgePath(l, at), drawn); })
        : std::vector<std::pair<SkPath, float>>{{regionAt(t, m, Cropped(l, t, path), drawn), t}};
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

bool Changing(const LayerEffect& e) {
    if (e.hidden) return false;
    if (e.id == "absent.shutterblur" || e.id == "absent.motionblur" || e.id == "absent.echo" || e.id == "absent.videofeedback") return false;
    return MotionEffect(e.id) || RepeatEffect(e.id) || TextEffect(e.id) || DrawingEffect(e.id) || e.id == kParticles;
}

bool SteadyPlacement(const std::vector<Layer>& siblings, const Layer& l, double from, double to, int depth) {
    if (l.placed) {
        const Sight& s = l.sight;
        if (!s.layers || !s.layer || s.layer->placed) return false;
        if (s.camera && (!s.camera->camera.fov.steady(s.camera->localTime(from), s.camera->localTime(to)) ||
                         !SteadyPlacement(*s.layers, *s.camera, from, to, 0))) return false;
        return SteadyPlacement(*s.layers, *s.layer, from, to, 0);
    }
    if (!l.steps.empty() || std::any_of(l.effects.begin(), l.effects.end(), Changing)) return false;
    const float a = l.localTime(from), b = l.localTime(to);
    for (const Anim* anim : {&l.location, &l.pivot, &l.scale, &l.rotation, &l.tilt}) {
        if (!anim->steady(a, b)) return false;
    }
    if (l.parent == 0 || depth >= kMaxParentDepth) return true;
    for (const Layer& s : siblings) {
        if (s.id == l.parent && &s != &l) return SteadyPlacement(siblings, s, from, to, depth + 1);
    }
    return true;
}

bool Steady(const std::vector<Layer>& siblings, const Layer& l, double from, double to) {
    if ((l.kind != "shape" && l.kind != "text") || l.keyedPoints || TextAnimated(l)) return false;
    if (!SteadyPlacement(siblings, l, from, to, 0)) return false;
    const float a = l.localTime(from), b = l.localTime(to);
    const auto still = [&](const Anim& x) { return x.steady(a, b); };
    const auto edge = [&](const Edge& e) { return still(e.color) && still(e.size) && still(e.opacity) && still(e.hardness) && still(e.offset); };
    const auto props = [&](const std::vector<Property>& list) {
        return std::all_of(list.begin(), list.end(), [&](const Property& p) { return still(p.anim); });
    };
    if (!still(l.skew) || !still(l.opacity) || !still(l.fillColor) || !still(l.stroke.color) || !still(l.stroke.size) ||
        !edge(l.shadow) || !std::all_of(l.borders.begin(), l.borders.end(), edge) || !props(l.props) ||
        !std::all_of(l.effects.begin(), l.effects.end(), [&](const LayerEffect& e) { return e.hidden || props(e.props); })) return false;
    return !gEffects || (gEffects->steady && gEffects->steady(l, a, b));
}

struct Recorded {
    sk_sp<SkPicture> picture;
    SkRect bounds = SkRect::MakeEmpty();
    bool bounded = false;
};

Recorded RecordInDevice(SkCanvas* canvas, const SkRect& area, const std::function<void(SkCanvas*)>& draw) {
    SkPictureRecorder recorder;
    SkRTreeFactory tight;
    SkCanvas* c = recorder.beginRecording(area, &tight);
    c->setMatrix(canvas->getLocalToDevice());
    draw(c);
    Recorded out;
    out.picture = recorder.finishRecordingAsPicture();
    const SkMatrix m = canvas->getTotalMatrix();
    SkMatrix inverse;
    out.bounded = !m.hasPerspective() && m.invert(&inverse);
    if (out.bounded) out.bounds = inverse.mapRect(out.picture->cullRect().makeOutset(2, 2));
    return out;
}

void PlayInDevice(SkCanvas* canvas, const SkPicture* picture) {
    canvas->save();
    canvas->resetMatrix();
    canvas->drawPicture(picture);
    canvas->restore();
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
    const auto at = [&](double u) { return std::clamp(timeMs + open * (u - 0.5), (double)l.startTime, (double)l.endTime); };
    const bool steady = Steady(siblings, l, at(0), at(1));
    int samples = shutter.samples;
    if (shutter.adaptive && !steady) samples = std::clamp((int)std::ceil(Reach(siblings, l, timeMs, open) / 2), samples, 48);
    if (l.kind == "embedScene") {
        canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &composite, SkCanvas::kF16ColorType));
        SkPaint add;
        add.setBlendMode(SkBlendMode::kPlus);
        for (int i = 0; i < samples;) {
            const double when = at((i + 0.5) / samples);
            int same = 1;
            while (i + same < samples && (steady || at((i + same + 0.5) / samples) == when)) same++;
            const float weight = (float)same / samples;
            const float scale[20] = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, weight, 0};
            add.setColorFilter(SkColorFilters::Matrix(scale));
            canvas->saveLayer(nullptr, &add);
            DrawOne(canvas, siblings, l, when, pictures, sample);
            canvas->restore();
            if (gBreak) (*gBreak)();
            i += same;
        }
        canvas->restore();
        return;
    }
    const SkRect area = SkRect::MakeLTRB(-1e6f, -1e6f, 1e6f, 1e6f);
    std::vector<std::pair<Recorded, float>> taken;
    SkRect all = SkRect::MakeEmpty();
    bool bounded = true;
    for (int i = 0; i < samples;) {
        const double when = at((i + 0.5) / samples);
        int same = 1;
        while (i + same < samples && (steady || at((i + same + 0.5) / samples) == when)) same++;
        Recorded r = RecordInDevice(canvas, area, [&](SkCanvas* c) { DrawOne(c, siblings, l, when, pictures, sample); });
        bounded = bounded && r.bounded;
        all.join(r.bounds);
        taken.emplace_back(std::move(r), (float)same / samples);
        i += same;
    }
    canvas->saveLayer(SkCanvas::SaveLayerRec(bounded ? &all : nullptr, &composite, SkCanvas::kF16ColorType));
    SkPaint add;
    add.setBlendMode(SkBlendMode::kPlus);
    for (const auto& [r, share] : taken) {
        const float scale[20] = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, share, 0};
        add.setColorFilter(SkColorFilters::Matrix(scale));
        canvas->saveLayer(bounded ? &r.bounds : nullptr, &add);
        PlayInDevice(canvas, r.picture.get());
        canvas->restore();
        if (gBreak) (*gBreak)();
    }
    canvas->restore();
}

void DrawOne(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& l, double timeMs,
             const MediaPictures& pictures, const Override& ov) {
    if (!l.activeAt(timeMs) || l.kind == "nullobj" || l.kind == "camera" || l.kind == "light") return;
    if (l.fillType == "media" && !pictures(l.mediaUri, l.id)) return;
    if (!ov.feedback) for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.videofeedback") continue;
        const float t = l.localTime(timeMs);
        const int count = std::clamp((int)std::lround(Setting(e, "copies", t, 24)), 1, 64);
        const float zoom = std::clamp(Setting(e, "zoom", t, 0.85f), 0.1f, 1.5f);
        const float rotation = Setting(e, "rotation", t, 0);
        const float fade = std::clamp(Setting(e, "fade", t, 0.9f), 0.0f, 1.0f);
        const float hue = Setting(e, "hueShift", t, 0), delay = std::clamp(Setting(e, "delay", t, 0), 0.0f, 1.0f);
        const int blend = std::clamp((int)std::lround(Setting(e, "blend", t, 0)), 0, 2);
        const SkMatrix placed = SiblingMatrix(siblings, l, timeMs);
        const SkPoint centre = placed.mapPoint({Setting(e, "center", t, 0, 0), Setting(e, "center", t, 0, 1)});
        float sx = zoom, sy = zoom, turn = rotation;
        SkVector offset = {Setting(e, "offset", t, 0, 0), Setting(e, "offset", t, 0, 1)};
        if (Setting(e, "useLayerTransform", t, 1) >= 0.5f) {
            const Placement p = Effective(siblings, l, timeMs, 0);
            sx *= p.scale.fX;
            sy *= p.scale.fY;
            turn += p.rotation;
            offset += p.location - SkPoint{gScene ? gScene->width / 2.0f : 0, gScene ? gScene->height / 2.0f : 0};
        }
        SkMatrix step = SkMatrix::Translate(centre.fX + offset.fX, centre.fY + offset.fY);
        step.preRotate(turn);
        step.preScale(sx, sy);
        step.preTranslate(-centre.fX, -centre.fY);
        std::vector<SkMatrix> matrices;
        matrices.reserve(count);
        SkMatrix current = SkMatrix::I();
        float alpha = 1;
        const SkRect bounds = BodyPath(l, t).getBounds();
        const float extent = std::max(bounds.width(), bounds.height());
        SkRect reach = placed.mapRect(bounds);
        reach.outset(std::max(reach.width(), reach.height()) * 0.5f + 64, std::max(reach.width(), reach.height()) * 0.5f + 64);
        const float shrink = std::max(std::fabs(sx), std::fabs(sy));
        for (int i = 1; i <= count; i++) {
            alpha *= fade;
            if (alpha < 1.0f / 255 || (shrink < 1 && extent * std::pow(shrink, i) < 1)) break;
            current = SkMatrix::Concat(step, current);
            matrices.push_back(current);
        }
        Override plain = ov;
        plain.feedback = true;
        const bool inward = shrink < 1;
        if (inward) DrawOne(canvas, siblings, l, timeMs, pictures, plain);
        const int n = (int)matrices.size();
        for (int k = 0; k < n; k++) {
            const int i = inward ? k + 1 : n - k;
            const float a = hue * i * kRadians, c = std::cos(a), s = std::sin(a) / std::sqrt(3.0f), q = (1 - c) / 3;
            const float colors[20] = {c+q, q-s, q+s, 0, 0, q+s, c+q, q-s, 0, 0, q-s, q+s, c+q, 0, 0, 0, 0, 0, 1, 0};
            SkPaint paint;
            paint.setAlphaf(std::pow(fade, i));
            if (hue != 0) paint.setColorFilter(SkColorFilters::Matrix(colors));
            paint.setBlendMode(blend == 1 ? SkBlendMode::kPlus : blend == 2 ? SkBlendMode::kScreen : SkBlendMode::kSrcOver);
            const SkRect area = matrices[i - 1].mapRect(reach);
            canvas->saveLayer(&area, &paint);
            canvas->concat(matrices[i - 1]);
            const double at = std::clamp(timeMs - 1000.0 * delay * i, (double)l.startTime, (double)l.endTime);
            DrawOne(canvas, siblings, l, at, pictures, plain);
            canvas->restore();
            if (gBreak) (*gBreak)();
        }
        if (!inward) DrawOne(canvas, siblings, l, timeMs, pictures, plain);
        return;
    }
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
    if (!(opacity > 0)) return;
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
    auto centredBorders = [&](SkCanvas* canvas, float alpha) {
        if (!centred) return;
        const std::vector<std::pair<SkPath, float>> outlines = repeated
            ? CopyOutlines(l, copies, [&](float at, const SkMatrix& placed) { return Cropped(l, at, ShapePath(l, at)).makeTransform(placed); })
            : std::vector<std::pair<SkPath, float>>{{Cropped(l, t, ShapePath(l, t)).makeTransform(SiblingMatrix(siblings, l, timeMs)), t}};
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
    const bool spilled = EffectsApply(siblings, l) && gEffects->spills && gEffects->spills(l);
    if (!group && !decorated && !blended && !spilled && (opacity >= 1 || !overlaps)) {
        DrawContent(canvas, siblings, l, timeMs, opacity, false, pictures, repeated ? &copies : nullptr);
        centredBorders(canvas, opacity);
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
        const bool sees = SeesPrevious(siblings, l);
        if (!group && !sees && !spilled && !repeated && !EffectsApply(siblings, l)) {
            const SkRect area = SkRect::MakeLTRB(-1e6f, -1e6f, 1e6f, 1e6f);
            const Recorded r = RecordInDevice(canvas, area, [&](SkCanvas* c) {
                DrawContent(c, siblings, l, timeMs, 1, decorated, pictures, repeated ? &copies : nullptr);
                centredBorders(c, 1);
            });
            canvas->saveLayer(r.bounded ? &r.bounds : nullptr, &layerPaint);
            PlayInDevice(canvas, r.picture.get());
            canvas->restore();
            return;
        }
        canvas->saveLayer(SkCanvas::SaveLayerRec(nullptr, &layerPaint, sees ? SkCanvas::kInitWithPrevious_SaveLayerFlag : 0));
        DrawContent(canvas, siblings, l, timeMs, 1, decorated, pictures, repeated ? &copies : nullptr);
        centredBorders(canvas, 1);
        canvas->restore();
    }
}

}

void SetLayerBreak(const std::function<void()>* each) { gBreak = each; }

bool ParticleEffect(const std::string& id) { return id == kParticles; }

void SetLayerEffects(const LayerEffects* effects, const Scene* scene, bool world) {
    gEffects = effects;
    gScene = scene;
    gWorld = world;
}

bool WorldView() { return gWorld; }

bool EffectOwnerMatrix(int64_t owner, double timeMs, SkMatrix* matrix) {
    if (!gEffectList) return false;
    for (const Layer& l : *gEffectList) {
        if (l.id == owner) {
            *matrix = SiblingMatrix(*gEffectList, l, timeMs);
            return true;
        }
    }
    return false;
}

int ProjectFps() { return Fps(); }

const std::vector<Layer>* EffectSiblings() { return gEffectList; }

SceneScope::SceneScope(const Scene& scene) : fPrevious(gScene) {
    if (!gScene) gScene = &scene;
}

SceneScope::~SceneScope() { gScene = fPrevious; }

static const std::vector<Layer>* ListWith(const std::vector<Layer>& layers, int64_t id, const Layer** found) {
    for (const Layer& l : layers) {
        if (l.id == id) {
            *found = &l;
            return &layers;
        }
    }
    for (const Layer& l : layers) {
        if (const std::vector<Layer>* inside = ListWith(l.children, id, found)) return inside;
    }
    return nullptr;
}

bool TextGuideBelow(const Layer& layer, float t, SkPath* path) {
    const Layer* self = nullptr;
    const std::vector<Layer>* list = nullptr;
    if (gEffectList) {
        for (const Layer& s : *gEffectList) {
            if (s.id == layer.id) self = &s;
        }
        if (self) list = gEffectList;
    }
    if (!list && gScene) list = ListWith(gScene->layers, layer.id, &self);
    if (!list || !self) return false;
    if (self->placed && self->sight.layers && self->sight.layer) {
        list = self->sight.layers;
        self = self->sight.layer;
    }
    const Layer* below = PathSource(*list, *self);
    if (!below || below == self || below->kind == "nullobj" || below->kind == "camera" || below->kind == "audio" || below->kind == "light") return false;
    const double timeMs = layer.startTime + (double)t * std::max(0, layer.endTime - layer.startTime);
    const float tb = below->localTime(timeMs);
    SkMatrix inverse;
    if (!SiblingMatrix(*list, *self, timeMs).invert(&inverse)) return false;
    const SkPath outline = below->text ? TextGlyphPath(*below, tb, Fps()) : below->kind == "drawing" ? DrawingStrokes(*below) : Cropped(*below, tb, ShapePath(*below, tb));
    *path = outline.makeTransform(SkMatrix::Concat(inverse, SiblingMatrix(*list, *below, timeMs)));
    return !path->isEmpty();
}

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
    if (!l.activeAt(timeMs) || l.kind == "nullobj" || l.kind == "camera" || l.kind == "light") return false;
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
    if (matrix.hasPerspective() && !(inverse.mapPointToHomogeneous(p).fZ > 0)) return false;
    const SkPoint local = inverse.mapPoint(p);
    if (l.kind == "drawing") return DrawingContains(l, local);
    if (l.kind != "embedScene") {
        const float t = l.localTime(timeMs);
        const SkPath path = l.text ? TextHitPath(l, t) : ShapePath(l, t);
        const sk_sp<SkImage> mask = ShapeKind(l) && !l.text && !l.video && l.fillType == "media" ? masks(l.mediaUri, l.id) : nullptr;
        if (mask) return CutoutContains(l, t, path, *mask, local, std::sqrt(std::fabs(matrix.getScaleX() * matrix.getScaleY() - matrix.getSkewX() * matrix.getSkewY())));
        if (l.fillType != "none" && path.contains(local.fX, local.fY)) return true;
        if (!(l.stroke.present && l.stroke.enabled)) return false;
        return DrawStroke(nullptr, l, StrokeGeometry(l, path, t).makeTransform(matrix), t, 1).contains(p.fX, p.fY);
    }
    const SkPoint inner = {local.fX + l.innerWidth / 2.0f, local.fY + l.innerHeight / 2.0f};
    const double innerTime = NestedTime(l, timeMs);
    if (innerTime >= 0 && (l.innerBackground >> 24) != 0 && SkRect::MakeWH(l.innerWidth, l.innerHeight).contains(inner.fX, inner.fY)) return true;
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
        if (innerTime < 0 || !c.activeAt(innerTime) || c.kind == "nullobj" || c.kind == "camera" || c.kind == "light") continue;
        if (!ContentRect(c, innerTime, SkMatrix::Concat(origin, SiblingMatrix(l.children, c, innerTime)), &child)) continue;
        left = std::min(left, child.fLeft);
        top = std::min(top, child.fTop);
        right = std::max(right, child.fRight);
        bottom = std::max(bottom, child.fBottom);
    }
    if (left <= right && top <= bottom) *out = SkRect::MakeLTRB(left, top, right, bottom);
    return true;
}

SkRect SelectionBounds(const Layer& layer, double timeMs, const SkMatrix& matrix) {
    if (matrix.hasPerspective()) {
        SkPoint corners[4];
        SelectionBounds(layer, timeMs, SkMatrix::I()).toQuad(corners);
        for (int i = 0; i < 4; i++) {
            const SkPoint3 h = matrix.mapPointToHomogeneous(corners[i]);
            if (!(h.fZ >= 1)) return SkRect::MakeEmpty();
            corners[i] = {h.fX / h.fZ, h.fY / h.fZ};
        }
        SkRect bounds;
        bounds.setBounds({corners, 4});
        return bounds.isFinite() ? bounds : SkRect::MakeEmpty();
    }
    if (layer.kind != "embedScene") return ShapePath(layer, layer.localTime(timeMs)).makeTransform(matrix).getBounds();
    SkRect bounds = SkRect::MakeEmpty();
    ContentRect(layer, timeMs, matrix, &bounds);
    return bounds;
}

bool ContentIconBounds(const Layer& layer, SkRect* bounds) {
    if (layer.kind == "drawing") *bounds = DrawingBounds(layer).getBounds();
    else if (layer.kind == "embedScene") *bounds = SelectionBounds(layer, 0, SkMatrix::I());
    else return false;
    return true;
}

void DrawContentIcon(SkCanvas* canvas, const Layer& layer, const MediaPictures& pictures) {
    canvas->saveLayer(nullptr, nullptr);
    if (layer.kind == "drawing") DrawDrawing(canvas, layer, 1, 0);
    else DrawGroup(canvas, layer, 0, pictures);
    canvas->restore();
}


static std::mutex gOrientedLock;
static std::set<std::string> gOrientedIds;

void NoteOrientedEffect(const std::string& id) {
    std::lock_guard<std::mutex> hold(gOrientedLock);
    gOrientedIds.insert(id);
}

static void FaceForward(std::vector<Layer>* seen) {
    std::lock_guard<std::mutex> hold(gOrientedLock);
    if (gOrientedIds.empty()) return;
    for (Layer& l : *seen) {
        if (l.projected && std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && gOrientedIds.count(e.id); })) {
            l.projected = false;
        }
    }
}

static const std::vector<Layer>& SeenProject(const Scene& scene, double timeMs, std::vector<Layer>* storage) {
    if (!SeeLayers(scene.layers, scene.width, scene.height, true, scene.fps, timeMs, scene.depthOrder, storage, scene.depthOrder && scene.pieceOrder)) return scene.layers;
    FaceForward(storage);
    SplitSeen(storage, timeMs);
    return *storage;
}

int64_t TopLayerAt(const Scene& scene, double timeMs, float x, float y, const MediaPictures& masks) {
    std::vector<Layer> storage;
    const std::vector<Layer>& list = SeenProject(scene, timeMs, &storage);
    for (auto it = list.rbegin(); it != list.rend(); ++it) {
        if (depth::Inside(it->sight.cuts, {x, y}) && Contains(list, *it, timeMs, {x, y}, scene.width, scene.height, masks)) return it->id;
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

bool SelectionQuad(const Scene& scene, const Layer& layer, double timeMs, SkPoint corners[4]) {
    SelectionBounds(layer, timeMs, SkMatrix::I()).toQuad(corners);
    const SkMatrix m = WorldMatrix(scene, layer, timeMs);
    if (!m.hasPerspective()) {
        m.mapPoints({corners, 4});
        return true;
    }
    constexpr float kNear = 1;
    SkPoint3 h[4], out[4];
    bool placed[4] = {};
    for (int i = 0; i < 4; i++) h[i] = m.mapPointToHomogeneous(corners[i]);
    for (int i = 0; i < 4; i++) {
        if (h[i].fZ >= kNear) {
            out[i] = h[i];
            placed[i] = true;
            continue;
        }
        SkPoint3 sum{0, 0, 0};
        int n = 0;
        for (const int j : {(i + 3) % 4, (i + 1) % 4}) {
            if (h[j].fZ < kNear) continue;
            sum += h[i] + ((kNear - h[i].fZ) / (h[j].fZ - h[i].fZ)) * (h[j] - h[i]);
            n++;
        }
        if (n > 0) {
            out[i] = (1.0f / n) * sum;
            placed[i] = true;
        }
    }
    if (std::none_of(placed, placed + 4, [](bool b) { return b; })) return false;
    for (int i = 0; i < 4; i++) {
        if (!placed[i]) out[i] = 0.5f * (out[(i + 3) % 4] + out[(i + 1) % 4]);
        corners[i] = {out[i].fX / out[i].fZ, out[i].fY / out[i].fZ};
    }
    return std::all_of(corners, corners + 4, [](const SkPoint& c) { return c.isFinite(); });
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
                if (!Shared(e)) continue;
                l.effects.push_back(e);
                l.effects.back().owner = parent->id;
            }
            up = parent->parent;
        }
    }
    return true;
}

bool Mixable(const std::vector<Layer>& layers, const Layer& l, double timeMs) {
    if (!l.mixPieces || l.kind == "embedScene" || ShadowOn(l) || HasRepeat(l)) return false;
    if (std::any_of(l.borders.begin(), l.borders.end(), [](const Edge& e) { return e.enabled; })) return false;
    Echo echo;
    ShutterBlur shutter;
    if (EchoOf(l, timeMs, &echo) || ShutterBlurOf(l, timeMs, &shutter)) return false;
    const bool effects = EffectsApply(layers, l);
    if (effects && (gEffects->lifted(l) || gEffects->behind(l) || (gEffects->spills && gEffects->spills(l)))) return false;
    const float t = l.localTime(timeMs);
    int last = -1;
    bool emits = false, deep = false, tinted = false;
    for (size_t k = 0; k < l.effects.size(); k++) {
        const LayerEffect& e = l.effects[k];
        if (e.hidden) continue;
        if (e.id == "absent.videofeedback") return false;
        if (pieces3d::IsEffect(e.id)) last = (int)k;
        if (Emits(e)) {
            emits = true;
            deep = deep || Setting(e, "depth", t, 0) >= 0.5f;
            tinted = tinted || std::lround(Setting(e, "blend", t, 0)) != 0;
        }
    }
    if (last >= 0) {
        for (size_t k = (size_t)last + 1; k < l.effects.size(); k++) {
            const LayerEffect& e = l.effects[k];
            if (!e.hidden && !MotionEffect(e.id) && !TextEffect(e.id) && !DrawingEffect(e.id)) return false;
        }
        return !emits;
    }
    if (effects) return false;
    if (emits) return deep && (IsNormal(l.blending) || !tinted);
    return l.kind == "model";
}

void Collect(SkCanvas* canvas, const std::vector<Layer>& layers, const Layer& l, double timeMs, const MediaPictures& pictures, depth::Pass* pass) {
    if (!l.activeAt(timeMs)) return;
    const float t = l.localTime(timeMs);
    const float opacity = std::clamp(l.opacity.get(0, t, 1) * MotionOpacity(l, timeMs), 0.0f, 1.0f);
    if (!(opacity > 0) || (l.fillType == "media" && !pictures(l.mediaUri, l.id))) return;
    pass->target = &l;
    pass->depth = l.sight.depth;
    pass->perPixel = DepthPerPixel(l, timeMs);
    pass->look = SkPaint();
    SetBlend(&pass->look, l.blending);
    SeenFlat(l, timeMs, &pass->flat);
    {
        depth::Scope scope(pass);
        DrawContent(canvas, layers, l, timeMs, opacity, false, pictures, nullptr);
    }
    pass->target = nullptr;
}

void DrawList(SkCanvas* canvas, const std::vector<Layer>& listed, int width, int height, bool top, double timeMs,
              const MediaPictures& pictures) {
    std::vector<Layer> inherited, storage;
    const std::vector<Layer>& source = InheritEffects(listed, &inherited) ? inherited : listed;
    const bool pieces = gScene && gScene->depthOrder && gScene->pieceOrder;
    const bool seen = SeeLayers(source, width, height, top, gScene ? gScene->fps : 30, timeMs, gScene && gScene->depthOrder, &storage, pieces);
    if (seen) FaceForward(&storage);
    if (seen && !pieces) SplitSeen(&storage, timeMs);
    const std::vector<Layer>& layers = seen ? storage : source;
    const std::vector<Layer>* const outer = gEffectList;
    gEffectList = &layers;
    const auto drawStackOf = [&](size_t i, size_t end) {
        const Layer& base = layers[i];
        if (end == i + 1) {
            DrawOne(canvas, layers, base, timeMs, pictures, Override());
            if (top && gBreak && EffectsApply(layers, base)) (*gBreak)();
            return;
        }
        if (!base.activeAt(timeMs)) return;
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
    };
    const auto drawStack = [&](size_t i, size_t end) {
        const std::vector<depth::Cut>& cuts = layers[i].sight.cuts;
        if (cuts.empty()) return drawStackOf(i, end);
        canvas->save();
        depth::Clip(canvas, cuts.data(), (int)cuts.size());
        drawStackOf(i, end);
        canvas->restore();
    };
    const auto after = [&](size_t i) {
        size_t end = i + 1;
        while (end < layers.size() && layers[end].clippingMask) end++;
        return end;
    };
    const bool mixing = seen && pieces;
    for (size_t i = 0; i < layers.size();) {
        if (!mixing || layers[i].sight.run == 0) {
            const size_t end = after(i);
            drawStack(i, end);
            i = end;
            continue;
        }
        depth::Pass pass;
        const int run = layers[i].sight.run;
        for (size_t end; i < layers.size(); i = end) {
            const Layer& l = layers[i];
            const bool idle = l.kind == "camera" || l.kind == "nullobj" || l.kind == "light";
            if (!idle && l.sight.run != run) break;
            end = after(i);
            if (idle) continue;
            if (end == i + 1 && Mixable(layers, l, timeMs)) {
                Collect(canvas, layers, l, timeMs, pictures, &pass);
                if (gBreak && EffectsApply(layers, l)) (*gBreak)();
            } else {
                depth::Flat flat;
                SeenFlat(l, timeMs, &flat);
                pass.whole([&drawStack, i, end](SkCanvas*) { drawStack(i, end); }, l.sight.depth, flat);
            }
        }
        pass.draw(canvas);
    }
    gEffectList = outer;
}

}

void DrawLayerList(SkCanvas* canvas, const Scene& scene, const std::vector<Layer>& layers, double timeMs, const MediaPictures& pictures) {
    DrawList(canvas, layers, scene.width, scene.height, true, timeMs, pictures);
}

}
