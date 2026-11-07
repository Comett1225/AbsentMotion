#include "effects.h"
#include "depth.h"

#include "particles.h"
#include "pieces3d.h"
#include "text.h"
#include "textanim.h"
#include "warp.h"
#include "xml.h"
#include "include/core/SkBBHFactory.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkImage.h"
#include "include/core/SkM44.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "include/core/SkSurface.h"
#include "include/core/SkTypeface.h"
#include "include/effects/SkImageFilters.h"
#include "src/core/SkCanvasPriv.h"
#include "src/core/SkDevice.h"
#include "src/core/SkSpecialImage.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <climits>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <optional>
#include <string_view>
#include <vector>

namespace am {
namespace {

const char* const kLift = "absent.backdrop";

const char* const kPuppet = "absent.puppet";

bool BuiltIn(const std::string& id) {
    return id == "absent.blur" || id == "absent.glow" || id == "absent.smear" || id == kLift || id == kPuppet;
}

const char* const kPuppetShader = R"(
uniform shader image;
uniform float3x3 toLocal;
uniform float3x3 fromLocal;
uniform float4 pins[32];
uniform float4 similar;
uniform float count;
uniform float power;
half4 main(float2 xy) {
    float3 h = toLocal * float3(xy, 1.0);
    if (abs(h.z) < 0.000001) return half4(0);
    float2 v = h.xy / h.z;
    float2 s = float2(similar.x * v.x - similar.y * v.y, similar.y * v.x + similar.x * v.y) + similar.zw;
    for (int i = 0; i < 32; i++) {
        if (float(i) >= count) break;
        float2 d = pins[i].xy - v;
        float r = dot(d, d);
        s += pins[i].zw * (power == 0.75 ? sqrt(r * sqrt(r)) : pow(r, power));
    }
    float3 o = fromLocal * float3(s, 1.0);
    if (abs(o.z) < 0.000001) return half4(0);
    return image.eval(o.xy / o.z);
}
)";

float Value(const LayerEffect& e, const std::map<std::string, Anim>* defaults, const std::string& name, int i, float t, float fallback);

float PuppetNumber(const LayerEffect& e, const std::string& name, float t, float fallback, float low, float high) {
    const Property* p = e.prop(name.c_str());
    const float v = p ? p->anim.get(0, t, fallback) : fallback;
    return std::isfinite(v) ? std::clamp(v, low, high) : fallback;
}

std::vector<warp::Pin> PuppetPins(const LayerEffect& e, float t, double timeMs) {
    const bool sway = PuppetNumber(e, "sway", t, 0, 0, 1) >= 0.5f;
    const float amount = PuppetNumber(e, "swayAmount", t, 15, 0, 500);
    const float speed = PuppetNumber(e, "swaySpeed", t, 1, 0, 10);
    const float angle = PuppetNumber(e, "swayAngle", t, 0, -3600, 3600) * 0.01745329252f;
    const float seed = PuppetNumber(e, "swaySeed", t, 0, 0, 1000);
    const bool random = PuppetNumber(e, "swayDirection", t, 0, 0, 1) >= 0.5f;
    const bool selected = PuppetNumber(e, "swayPins", t, 0, 0, 1) >= 0.5f;
    std::vector<warp::Pin> pins;
    for (const Property& p : e.props) {
        if (p.name.size() < 4 || p.name.size() > 12 || p.name.compare(0, 3, "pin") != 0 || p.name[3] == '0' ||
            !std::all_of(p.name.begin() + 3, p.name.end(), [](char c) { return c >= '0' && c <= '9'; })) continue;
        const Property* r = e.prop(("rest" + p.name.substr(3)).c_str());
        if (!r) continue;
        warp::Pin pin{p.anim.get(0, t, NAN), p.anim.get(1, t, NAN), r->anim.get(0, t, NAN), r->anim.get(1, t, NAN)};
        if (!std::isfinite(pin.x) || !std::isfinite(pin.y) || !std::isfinite(pin.restX) || !std::isfinite(pin.restY)) continue;
        const std::string number = p.name.substr(3);
        pin.mode = (int)std::lround(PuppetNumber(e, "pinMode" + number, t, 0, 0, 2));
        pin.rotation = PuppetNumber(e, "pinRotation" + number, t, 0, -3600, 3600);
        pin.scale = PuppetNumber(e, "pinScale" + number, t, 1, 0.05f, 10);
        if (pin.mode == 2) { pin.x = pin.restX; pin.y = pin.restY; }
        if (pin.mode != 2 && sway && amount > 0 && (!selected || PuppetNumber(e, "swayPin" + number, t, 0, 0, 1) >= 0.5f)) {
            const unsigned id = (unsigned)std::strtoul(number.c_str(), nullptr, 10);
            const double phase = std::fmod((std::isfinite(timeMs) ? timeMs : 0.0) * 0.001 * speed, 100000.0) * 6.283185307179586 + id * 2.3999632297 + seed;
            const double direction = random ? id * 2.3999632297 + seed * 0.6180339887 : angle;
            const float d = amount * (float)std::sin(phase);
            pin.x += d * (float)std::cos(direction);
            pin.y += d * (float)std::sin(direction);
        }
        pins.push_back(pin);
        if ((int)pins.size() == warp::kMaxPins) break;
    }
    return pins;
}

bool Bent(const Layer& layer, float t, double timeMs) {
    return std::any_of(layer.effects.begin(), layer.effects.end(), [&](const LayerEffect& e) {
        if (e.hidden || e.id != kPuppet) return false;
        const std::vector<warp::Pin> pins = PuppetPins(e, t, timeMs);
        return !pins.empty() && (!warp::AtRest(pins) || std::any_of(pins.begin(), pins.end(), [](const warp::Pin& p) {
            return p.mode == 1 && (std::fabs(p.rotation) > 1e-4f || std::fabs(p.scale - 1) > 1e-4f);
        }));
    });
}

float PuppetReach(const warp::Bend& bend, const SkMatrix& fromLocal, const SkMatrix& toLocal, const SkRect& seen, const SkRect& inside,
                  float fallback) {
    constexpr int kSteps = 24;
    std::vector<SkPoint> moved((kSteps + 1) * (kSteps + 1)), source(moved.size());
    for (int j = 0; j <= kSteps; j++) {
        for (int i = 0; i <= kSteps; i++) {
            const SkPoint xy = {seen.left() + seen.width() * i / kSteps, seen.top() + seen.height() * j / kSteps};
            const SkPoint v = toLocal.mapPoint(xy);
            double sx, sy;
            warp::Source(bend, v.x(), v.y(), &sx, &sy);
            const SkPoint o = fromLocal.mapPoint({(float)sx, (float)sy});
            if (!o.isFinite()) return fallback;
            source[j * (kSteps + 1) + i] = o;
            moved[j * (kSteps + 1) + i] = o - xy;
        }
    }
    float spread = 0;
    for (int j = 0; j <= kSteps; j++) {
        for (int i = 0; i <= kSteps; i++) {
            const SkPoint d = moved[j * (kSteps + 1) + i];
            if (i < kSteps) spread = std::max(spread, (d - moved[j * (kSteps + 1) + i + 1]).length());
            if (j < kSteps) spread = std::max(spread, (d - moved[(j + 1) * (kSteps + 1) + i]).length());
        }
    }
    const SkRect near = inside.makeOutset(2 * spread, 2 * spread);
    float reach = 0;
    for (size_t k = 0; k < moved.size(); k++) {
        if (near.contains(source[k].x(), source[k].y())) reach = std::max(reach, moved[k].length());
    }
    return std::min(fallback, reach + 2 * spread + 2);
}

sk_sp<SkImage> OnGpu(const sk_sp<SkImage>& image);

const char* const kPuppetMeshShader = R"(
uniform shader image;
uniform shader mesh;
uniform float3x3 toLocal;
uniform float3x3 fromLocal;
uniform float4 bounds;
uniform float4 sourceBounds;
uniform float2 grid;
float2 readPoint(float2 at) {
    float3 rgb = floor(float3(mesh.eval(at + 0.5).rgb) * 255.0 + 0.5);
    float b = rgb.b;
    float2 v = float2(rgb.r * 16.0 + floor(b / 16.0), rgb.g * 16.0 + mod(b, 16.0)) / 4095.0;
    return sourceBounds.xy + v * sourceBounds.zw;
}
half4 main(float2 xy) {
    float3 h = toLocal * float3(xy, 1.0);
    if (abs(h.z) < 0.000001) return half4(0);
    float2 v = h.xy / h.z;
    float2 g = (v - bounds.xy) / bounds.zw * grid;
    if (g.x < 0.0 || g.y < 0.0 || g.x > grid.x || g.y > grid.y) return half4(0);
    float2 cell = min(floor(g), grid - 1.0), f = g - cell;
    float2 a = readPoint(cell), b, c;
    float2 src;
    if (f.x + f.y <= 1.0) {
        b = readPoint(cell + float2(1, 0));
        c = readPoint(cell + float2(0, 1));
        src = a + f.x * (b - a) + f.y * (c - a);
    } else {
        b = readPoint(cell + float2(1, 0));
        c = readPoint(cell + float2(0, 1));
        float2 d = readPoint(cell + float2(1, 1));
        src = d + (1.0 - f.y) * (b - d) + (1.0 - f.x) * (c - d);
    }
    float3 o = fromLocal * float3(src, 1.0);
    if (abs(o.z) < 0.000001) return half4(0);
    return image.eval(o.xy / o.z);
}
)";

sk_sp<SkImageFilter> PuppetMesh(const std::vector<warp::Pin>& pins, float falloff, int density, float expansion,
                               const SkRect& area, const SkRect& inside, float reach, const SkMatrix& placed, sk_sp<SkImageFilter> input) {
    static const sk_sp<SkRuntimeEffect> effect = SkRuntimeEffect::MakeForShader(SkString(kPuppetMeshShader)).effect;
    if (!effect) return input;
    const SkMatrix fromLocal = SkMatrix::Translate(-area.left(), -area.top()) * placed;
    SkMatrix toLocal;
    if (!fromLocal.invert(&toLocal)) return input;
    SkRect src = toLocal.mapRect(inside);
    if (!src.isFinite() || !std::isfinite(src.width()) || !std::isfinite(src.height()) || src.isEmpty()) return input;
    src.outset(expansion + 4, expansion + 4);
    SkRect bounds = src;
    float move = 0;
    for (const auto& p : pins) {
        bounds.fLeft = std::min(bounds.fLeft, p.x); bounds.fRight = std::max(bounds.fRight, p.x);
        bounds.fTop = std::min(bounds.fTop, p.y); bounds.fBottom = std::max(bounds.fBottom, p.y);
        if (p.mode == 1 && (std::fabs(p.rotation) > 1e-4f || std::fabs(p.scale - 1) > 1e-4f)) {
            const float angle = p.rotation * 0.01745329252f, c = std::cos(angle), s = std::sin(angle);
            for (float x : {src.left(), src.right()}) for (float y : {src.top(), src.bottom()}) {
                const float dx = (x - p.restX) * p.scale, dy = (y - p.restY) * p.scale;
                const float px = p.x + c * dx - s * dy, py = p.y + s * dx + c * dy;
                bounds.fLeft = std::min(bounds.fLeft, px); bounds.fRight = std::max(bounds.fRight, px);
                bounds.fTop = std::min(bounds.fTop, py); bounds.fBottom = std::max(bounds.fBottom, py);
            }
        }
        move = std::max(move, std::hypot(p.x - p.restX, p.y - p.restY));
    }
    bounds.outset(move + 4, move + 4);
    if (!bounds.isFinite() || !std::isfinite(bounds.width()) || !std::isfinite(bounds.height()) || bounds.width() <= 0 || bounds.height() <= 0) return input;
    const int longest = std::clamp(density, 8, 48);
    const int nx = std::clamp((int)std::ceil(longest * (bounds.width() / std::max(bounds.width(), bounds.height()))), 4, 48);
    const int ny = std::clamp((int)std::ceil(longest * (bounds.height() / std::max(bounds.width(), bounds.height()))), 4, 48);
    const auto angles = warp::MeshAngles(pins);
    std::array<uint8_t, 49 * 49 * 4> data{};
    float distance = 0;
    for (int j = 0; j <= ny; j++) for (int i = 0; i <= nx; i++) {
        const double x = bounds.left() + bounds.width() * ((double)i / nx), y = bounds.top() + bounds.height() * ((double)j / ny);
        double sx, sy;
        warp::MeshSource(pins, angles, falloff, x, y, &sx, &sy);
        if (!std::isfinite(sx) || !std::isfinite(sy)) return input;
        const SkPoint a = fromLocal.mapPoint({(float)x, (float)y}), b = fromLocal.mapPoint({(float)sx, (float)sy});
        if (!a.isFinite() || !b.isFinite()) return input;
        distance = std::max(distance, (a - b).length());
        const int u = (int)std::lround(std::clamp((sx - src.left()) / src.width(), 0.0, 1.0) * 4095);
        const int v = (int)std::lround(std::clamp((sy - src.top()) / src.height(), 0.0, 1.0) * 4095);
        const int at = (j * (nx + 1) + i) * 4;
        data[at] = u >> 4; data[at + 1] = v >> 4; data[at + 2] = ((u & 15) << 4) | (v & 15); data[at + 3] = 255;
    }
    const SkImageInfo info = SkImageInfo::Make(nx + 1, ny + 1, kRGBA_8888_SkColorType, kOpaque_SkAlphaType);
    const sk_sp<SkImage> map = OnGpu(SkImages::RasterFromData(info, SkData::MakeWithCopy(data.data(), (size_t)(nx + 1) * (ny + 1) * 4), (nx + 1) * 4));
    if (!map) return input;
    const auto columns = [](const SkMatrix& m) { return std::vector<float>{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]}; };
    SkRuntimeShaderBuilder b(effect);
    const auto to = columns(toLocal), from = columns(fromLocal);
    b.uniform("toLocal").set(to.data(), 9); b.uniform("fromLocal").set(from.data(), 9);
    b.uniform("bounds") = SkV4{bounds.left(), bounds.top(), bounds.width(), bounds.height()};
    b.uniform("sourceBounds") = SkV4{src.left(), src.top(), src.width(), src.height()};
    b.uniform("grid") = SkV2{(float)nx, (float)ny};
    b.child("mesh") = map->makeShader(SkSamplingOptions(SkFilterMode::kNearest));
    const SkPoint origin = fromLocal.mapPoint({bounds.left(), bounds.top()});
    const SkPoint step = fromLocal.mapPoint({bounds.left() + bounds.width() / nx, bounds.top() + bounds.height() / ny});
    const float pad = origin.isFinite() && step.isFinite() ? 2 * (step - origin).length() + 2 : reach;
    return SkImageFilters::RuntimeShader(b, std::min(reach, distance + pad), "image", SkImageFilters::Crop(inside, std::move(input)), true);
}

sk_sp<SkImageFilter> PuppetFilter(const LayerEffect& e, const std::map<std::string, Anim>* defaults, float t, double timeMs, const SkRect& area,
                                  SkMatrix placed, const SkRect& seen, const SkRect& inside, float reach, sk_sp<SkImageFilter> input) {
    static const sk_sp<SkRuntimeEffect> effect = SkRuntimeEffect::MakeForShader(SkString(kPuppetShader)).effect;
    const std::vector<warp::Pin> pins = PuppetPins(e, t, timeMs);
    if (!effect || pins.empty()) return input;
    const bool special = std::any_of(pins.begin(), pins.end(), [](const warp::Pin& p) { return p.mode != 0; });
    const bool changed = !warp::AtRest(pins) || std::any_of(pins.begin(), pins.end(), [](const warp::Pin& p) {
        return p.mode == 1 && (std::fabs(p.rotation) > 1e-4f || std::fabs(p.scale - 1) > 1e-4f);
    });
    if (!changed) return input;
    if (e.owner && !EffectOwnerMatrix(e.owner, timeMs, &placed)) return input;
    const int density = (int)std::lround(PuppetNumber(e, "density", t, 0, 0, 48));
    const float expansion = PuppetNumber(e, "expansion", t, 0, 0, 500);
    if (density > 0 || expansion > 0 || special)
        return PuppetMesh(pins, Value(e, defaults, "falloff", 0, t, 1), density > 0 ? density : 24, expansion, area, inside, reach, placed, std::move(input));
    const warp::Bend bend = warp::Solve(pins, Value(e, defaults, "falloff", 0, t, 1));
    const SkMatrix fromLocal = SkMatrix::Translate(-area.left(), -area.top()) * placed;
    SkMatrix toLocal;
    if (!fromLocal.invert(&toLocal)) return input;
    const SkMatrix centered = fromLocal * SkMatrix::Translate((float)bend.centerX, (float)bend.centerY);
    SkMatrix toCentered;
    if (!centered.invert(&toCentered)) return input;
    const auto columns = [](const SkMatrix& m) { return std::vector<float>{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]}; };
    std::vector<float> packed(4 * warp::kMaxPins, 0.0f);
    for (size_t i = 0; i < bend.cx.size(); i++) {
        packed[4 * i] = (float)bend.x[i];
        packed[4 * i + 1] = (float)bend.y[i];
        packed[4 * i + 2] = (float)bend.cx[i];
        packed[4 * i + 3] = (float)bend.cy[i];
    }
    SkRuntimeShaderBuilder b(effect);
    const std::vector<float> to = columns(toCentered), from = columns(centered);
    b.uniform("toLocal").set(to.data(), 9);
    b.uniform("fromLocal").set(from.data(), 9);
    b.uniform("pins").set(packed.data(), (int)packed.size());
    b.uniform("similar") = SkV4{(float)bend.ar, (float)bend.ai, (float)bend.tx, (float)bend.ty};
    b.uniform("count") = (float)bend.cx.size();
    b.uniform("power") = (float)bend.power;
    return SkImageFilters::RuntimeShader(b, PuppetReach(bend, fromLocal, toLocal, seen, inside, reach), "image",
                                         SkImageFilters::Crop(inside, std::move(input)), true);
}

float Value(const LayerEffect& e, const std::map<std::string, Anim>* defaults, const std::string& name, int i, float t,
            float fallback) {
    if (e.id == "absent.stretch" && name == "stretch") fallback = 200;
    else if (e.id == "absent.ominoglass" && name == "color") fallback = 1;
    else if (e.id == "absent.polardisplacement" && name == "angle") fallback = 45;
    else if (defaults) {
        if (auto d = defaults->find(name); d != defaults->end()) fallback = d->second.get(i, t, fallback);
    }
    for (const Property& p : e.props) {
        if (p.name == name) return p.anim.get(i, t, fallback);
    }
    return fallback;
}

const int kGlowCells = 24;
const float kGlowReach = 0.345f;
const float kEdgeSpread = 0.042f;

const char* const kGlowRows = R"(
uniform shader image;
uniform float n;
half4 main(float2 p) {
    float best = n;
    for (int i = -24; i <= 24; i++) {
        float d = abs(float(i));
        if (d < best && image.eval(p + float2(float(i), 0.0)).a > 0.5) best = d;
    }
    return half4(half(best / n), 0.0, 0.0, 1.0);
}
)";

const char* const kGlowColumns = R"(
uniform shader image;
uniform float n;
half4 main(float2 p) {
    float best = n;
    for (int j = -24; j <= 24; j++) {
        float dy = abs(float(j));
        if (dy < best) {
            half4 s = image.eval(p + float2(0.0, float(j)));
            float h = (float(s.r) + 1.0 - float(s.a)) * n;
            best = min(best, sqrt(h * h + dy * dy));
        }
    }
    return half4(half(best / n), 0.0, 0.0, 1.0);
}
)";

const char* const kGlowMix = R"(
uniform shader image;
uniform shader field;
uniform shader backdrop;
uniform float reach;
uniform float shift;
uniform float grow;
uniform float soft;
uniform float amount;
uniform float mode;
uniform half4 tint;
half4 main(float2 p) {
    half4 src = image.eval(p);
    half4 f = field.eval(p);
    float r = float(f.r) + 1.0 - float(f.a);
    float d = max(r * reach - shift, 0.0);
    float g = r > 0.995 ? 0.0 : soft > 0.0 ? 1.0 - smoothstep(grow - soft, grow + soft, d) : (d <= grow ? 1.0 : 0.0);
    half k = half(g * amount) * tint.a;
    half3 c = tint.rgb;
    if (mode > 0.5) {
        half4 b = backdrop.eval(p);
        half3 under = b.a > 0.0 ? b.rgb / b.a : half3(0.0);
        half3 mixed = mode < 1.5 ? under * c : under + c - under * c;
        c = mix(c, mixed, b.a);
    }
    return src + half4(c * k, k) * (1.0 - src.a);
}
)";

bool Pointwise(const std::string& sksl) {
    if (sksl.find("inout") != std::string::npos || sksl.find(" out ") != std::string::npos) return false;
    const size_t main = sksl.find("main(");
    if (main == std::string::npos) return false;
    size_t at = sksl.find_first_not_of(' ', main + 5);
    if (at == std::string::npos || (sksl.compare(at, 7, "float2 ") != 0 && sksl.compare(at, 6, "half2 ") != 0)) return false;
    at = sksl.find_first_not_of(' ', sksl.find(' ', at));
    const size_t end = sksl.find_first_of(" )", at);
    if (at == std::string::npos || end == std::string::npos) return false;
    const std::string p = sksl.substr(at, end - at);
    const auto word = [&](size_t i) { return i < sksl.size() && (std::isalnum((unsigned char)sksl[i]) || sksl[i] == '_'); };
    for (size_t i = sksl.find(p, end); i != std::string::npos; i = sksl.find(p, i + 1)) {
        if (word(i - 1) || word(i + p.size())) continue;
        size_t k = i + p.size();
        if (k < sksl.size() && sksl[k] == '.') while (word(++k)) {}
        k = sksl.find_first_not_of(' ', k);
        if (k == std::string::npos) break;
        if (sksl[k] == '=' && (k + 1 >= sksl.size() || sksl[k + 1] != '=')) return false;
        if (std::strchr("+-*/", sksl[k]) && k + 1 < sksl.size() && sksl[k + 1] == '=') return false;
        if ((sksl[k] == '+' || sksl[k] == '-') && k + 1 < sksl.size() && sksl[k + 1] == sksl[k]) return false;
    }
    bool sampled = false;
    for (size_t i = sksl.find(".eval("); i != std::string::npos; i = sksl.find(".eval(", i + 1)) {
        size_t name = i;
        while (name > 0 && word(name - 1)) name--;
        const std::string child = sksl.substr(name, i - name);
        if (child == "palette" || child == "thresholds" || child == "glyphs") continue;
        int depth = 0;
        size_t close = std::string::npos;
        for (size_t k = i + 5; k < sksl.size(); k++) {
            if (sksl[k] == '(') depth++;
            else if (sksl[k] == ')' && --depth == 0) { close = k; break; }
        }
        if (close == std::string::npos) return false;
        const size_t first = sksl.find_first_not_of(' ', i + 6), last = sksl.find_last_not_of(' ', close - 1);
        if (first > last || sksl.compare(first, last - first + 1, p) != 0 || last - first + 1 != p.size()) return false;
        sampled = true;
    }
    return sampled;
}

int Mentions(const std::string& sksl, const std::string& word) {
    const auto part = [&](size_t i) { return i < sksl.size() && (std::isalnum((unsigned char)sksl[i]) || sksl[i] == '_'); };
    int n = 0;
    for (size_t i = sksl.find(word); i != std::string::npos; i = sksl.find(word, i + 1)) {
        if (!part(i - 1) && !part(i + word.size())) n++;
    }
    return n;
}

sk_sp<SkRuntimeEffect> Compiled(const char* sksl) {
    auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
    return result.effect;
}

float GlowExtent(const std::function<float(const char*, int, float)>& v) {
    const float reach = std::max(v("radius", 0, 100), 0.0f) * kGlowReach;
    const float hard = std::clamp(v("hardness", 0, 0.5f), 0.0f, 1.0f);
    const float edge = std::min(reach / 4, 1.0f), grow = hard * reach - 0.5f * edge, soft = (1 - hard) * reach + edge;
    const float step = std::max(1.0f, (grow + soft + 1) / kGlowCells);
    return grow + soft + 1 + 2 * step + 4;
}

sk_sp<SkImageFilter> GlowFilter(const std::function<float(const char*, int, float)>& v, sk_sp<SkImageFilter> input, bool behind) {
    static const sk_sp<SkRuntimeEffect> rows = Compiled(kGlowRows), columns = Compiled(kGlowColumns), mix = Compiled(kGlowMix);
    const float reach = std::max(v("radius", 0, 100), 0.0f) * kGlowReach;
    const float amount = std::clamp(v("alpha", 0, 0.75f), 0.0f, 1.0f);
    const float hard = std::clamp(v("hardness", 0, 0.5f), 0.0f, 1.0f);
    const float a = std::clamp(v("color", 0, 1), 0.0f, 1.0f);
    if (!rows || !columns || !mix || !(reach > 0) || !(amount * a > 0)) return input;
    const float edge = std::min(reach / 4, 1.0f), grow = hard * reach - 0.5f * edge, soft = (1 - hard) * reach + edge;
    const float step = std::max(1.0f, (grow + soft + 1) / kGlowCells), cells = (grow + soft + 1) / step;
    const SkSamplingOptions linear(SkFilterMode::kLinear);
    sk_sp<SkImageFilter> mask = input;
    float grown = 0;
    if (step > 1) {
        grown = std::ceil(step / 2);
        mask = SkImageFilters::MatrixTransform(SkMatrix::Scale(1 / step, 1 / step), linear, SkImageFilters::Dilate(grown, grown, input));
    }
    SkRuntimeShaderBuilder across(rows);
    across.uniform("n") = cells;
    SkRuntimeShaderBuilder down(columns);
    down.uniform("n") = cells;
    sk_sp<SkImageFilter> field = SkImageFilters::RuntimeShader(down, cells + 1, "image",
                                                               SkImageFilters::RuntimeShader(across, cells + 1, "image", mask));
    if (step > 1) field = SkImageFilters::MatrixTransform(SkMatrix::Scale(step, step), linear, field);
    SkRuntimeShaderBuilder b(mix);
    b.uniform("reach") = cells * step;
    b.uniform("shift") = 0.5f - grown;
    b.uniform("grow") = grow;
    b.uniform("soft") = soft;
    b.uniform("amount") = amount;
    b.uniform("mode") = behind ? std::clamp(std::round(v("blendMode", 0, 2)), 0.0f, 2.0f) : 0.0f;
    b.uniform("tint") = SkV4{std::clamp(v("color", 1, 1), 0.0f, 1.0f), std::clamp(v("color", 2, 1), 0.0f, 1.0f),
                             std::clamp(v("color", 3, 0.667f), 0.0f, 1.0f), a};
    std::string_view names[] = {"image", "field", "backdrop"};
    sk_sp<SkImageFilter> inputs[] = {input, field, behind ? nullptr : SkImageFilters::Empty()};
    return SkImageFilters::RuntimeShader(b, 0, names, inputs, 3);
}

SkM44 Orientation(const Layer& layer, double timeMs) {
    Placement p;
    if (!layer.sight.layers || !SeenPlacement(layer, timeMs, &p)) return SkM44();
    const SkM44 camera = layer.projected ? SkM44() : p.turn;
    if (p.tilt == SkM44()) return camera;
    const float r = p.rotation * 3.14159265f / 180;
    const SkM44 own = SkM44::Rotate({0, 0, 1}, r) * p.tilt * SkM44::Rotate({0, 0, 1}, -r);
    return camera == SkM44() ? own : camera * own;
}

std::vector<float> columns3(const SkM44& m) {
    return {m.rc(0, 0), m.rc(1, 0), m.rc(2, 0), m.rc(0, 1), m.rc(1, 1), m.rc(2, 1), m.rc(0, 2), m.rc(1, 2), m.rc(2, 2)};
}

sk_sp<SkImageFilter> BuiltInFilter(const std::string& id, const std::function<float(const char*, int, float)>& v,
                                   sk_sp<SkImageFilter> input) {
    if (id == "absent.blur") {
        const float sigma = std::max(v("strength", 0, 10), 0.0f) / 3;
        return sigma > 0 ? SkImageFilters::Blur(sigma, sigma, SkTileMode::kDecal, input) : input;
    }
    const float length = std::max(v("length", 0, 40), 0.0f), angle = v("angle", 0, 0);
    if (!(length > 0) || !std::isfinite(angle)) return input;
    const SkSamplingOptions linear(SkFilterMode::kLinear);
    auto turned = SkImageFilters::MatrixTransform(SkMatrix::RotateDeg(-angle), linear, input);
    auto blurred = SkImageFilters::Blur(length * 0.3f, 0, SkTileMode::kDecal, turned);
    return SkImageFilters::MatrixTransform(SkMatrix::RotateDeg(angle), linear, blurred);
}

const int kDiffuse = 12, kMethods = 23, kExact = 4, kMaxColors = 512;

thread_local Upload tUpload;

sk_sp<SkImage> OnGpu(const sk_sp<SkImage>& image) {
    return image && tUpload ? tUpload(image) : image;
}

sk_sp<SkImage> Rgba(int w, int h, const void* pixels, SkAlphaType alpha, sk_sp<SkColorSpace> space = nullptr) {
    const SkImageInfo info = SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, alpha, std::move(space));
    return SkImages::RasterFromData(info, SkData::MakeWithCopy(pixels, (size_t)w * h * 4), (size_t)w * 4);
}

sk_sp<SkImage> Owned(std::vector<uint8_t>&& pixels, int w, int h) {
    auto* held = new std::vector<uint8_t>(std::move(pixels));
    sk_sp<SkData> data = SkData::MakeWithProc(held->data(), held->size(), [](const void*, void* context) { delete static_cast<std::vector<uint8_t>*>(context); }, held);
    const SkImageInfo info = SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB());
    return SkImages::RasterFromData(info, std::move(data), (size_t)w * 4);
}

int Gap(uint32_t c, int r, int g, int b) {
    const int dr = (int)(c >> 16 & 255) - r, dg = (int)(c >> 8 & 255) - g, db = (int)(c & 255) - b;
    return dr * dr + dg * dg + db * db;
}

uint32_t Closest(const std::vector<uint32_t>& colors, int r, int g, int b, uint32_t* second = nullptr) {
    int best = INT_MAX, next = INT_MAX;
    uint32_t out = 0, after = 0;
    for (uint32_t c : colors) {
        const int d = Gap(c, r, g, b);
        if (d < best) next = best, after = out, best = d, out = c;
        else if (d < next) next = d, after = c;
    }
    if (second) *second = next == INT_MAX ? out : after;
    return out;
}

}

struct Palette {
    std::vector<uint32_t> colors;
    float spread = 1;
    mutable std::once_flag built;
    mutable std::vector<uint32_t> table;
    mutable sk_sp<SkImage> lut;

    void build() const;
    sk_sp<SkImage> image() const {
        build();
        return lut;
    }
    uint32_t nearest(int r, int g, int b) const {
        if (colors.size() <= (size_t)kExact) return Closest(colors, r, g, b);
        build();
        const auto level = [](int v) { return (v * 31 + 127) / 255; };
        const size_t at = 2 * (size_t)((level(b) * 32 + level(g)) * 32 + level(r));
        return Gap(table[at], r, g, b) <= Gap(table[at + 1], r, g, b) ? table[at] : table[at + 1];
    }
};

void Palette::build() const {
    std::call_once(built, [this] {
        table.resize(2 * 32 * 32 * 32);
        std::vector<uint32_t> pixels(256 * 128);
        for (int b = 0; b < 32; b++) {
            for (int g = 0; g < 32; g++) {
                for (int r = 0; r < 32; r++) {
                    uint32_t second = 0;
                    const uint32_t c = Closest(colors, (r * 255 + 15) / 31, (g * 255 + 15) / 31, (b * 255 + 15) / 31, &second);
                    table[2 * ((b * 32 + g) * 32 + r)] = c;
                    table[2 * ((b * 32 + g) * 32 + r) + 1] = second;
                    pixels[(b / 8 * 32 + g) * 256 + b % 8 * 32 + r] = 0xff000000u | (c >> 16 & 0xff) | (c & 0xff00) | (c & 0xff) << 16;
                }
            }
        }
        lut = Rgba(256, 128, pixels.data(), kOpaque_SkAlphaType);
    });
}

namespace {

std::vector<uint32_t> ParseColors(const std::string& text) {
    std::vector<uint32_t> out;
    uint32_t v = 0;
    int digits = 0;
    const auto end = [&] {
        if (digits == 6 && out.size() < (size_t)kMaxColors && std::find(out.begin(), out.end(), v) == out.end()) out.push_back(v);
        v = 0, digits = 0;
    };
    for (char c : text) {
        const int h = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        if (h < 0) end();
        else if (++digits <= 6) v = v * 16 + (uint32_t)h;
    }
    end();
    return out;
}

std::shared_ptr<const Palette> MakePalette(std::vector<uint32_t> colors) {
    auto p = std::make_shared<Palette>();
    p->colors = std::move(colors);
    std::vector<int> gaps;
    for (size_t i = 0; i < p->colors.size(); i++) {
        int gap = 255;
        for (size_t j = 0; j < p->colors.size(); j++) {
            if (i == j) continue;
            const uint32_t a = p->colors[i], b = p->colors[j];
            gap = std::min(gap, std::max({std::abs((int)(a >> 16 & 255) - (int)(b >> 16 & 255)), std::abs((int)(a >> 8 & 255) - (int)(b >> 8 & 255)),
                                          std::abs((int)(a & 255) - (int)(b & 255))}));
        }
        gaps.push_back(gap);
    }
    std::nth_element(gaps.begin(), gaps.begin() + gaps.size() / 2, gaps.end());
    p->spread = std::clamp(gaps[gaps.size() / 2] / 255.0f, 1 / 255.0f, 1.0f);
    return p;
}

std::shared_ptr<const Palette> PaletteFor(std::vector<uint32_t> colors) {
    static std::mutex lock;
    static std::map<std::vector<uint32_t>, std::shared_ptr<const Palette>> made;
    if (colors.empty()) colors = {0x000000, 0xffffff};
    std::lock_guard<std::mutex> hold(lock);
    if (auto found = made.find(colors); found != made.end()) return found->second;
    if (made.size() >= 24) made.clear();
    auto p = MakePalette(colors);
    made[std::move(colors)] = p;
    return p;
}

struct GlyphAtlas {
    sk_sp<SkImage> image;
    int count = 0, columns = 1;
    float width = 1, height = 1;
};

std::shared_ptr<const GlyphAtlas> AtlasFor(const std::string& characters, float cell) {
    static const int ladder[] = {4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48, 56, 64, 72, 80, 96, 112, 128};
    const int wanted = std::clamp((int)std::lround(std::isfinite(cell) ? cell : 12.0f), 4, 128);
    const int w = *std::lower_bound(std::begin(ladder), std::end(ladder), wanted), h = (int)std::lround(w * 1.6f);
    const std::string key = std::to_string(w) + "\n" + characters;
    static std::mutex lock;
    static std::map<std::string, std::shared_ptr<const GlyphAtlas>> made;
    {
        std::lock_guard<std::mutex> hold(lock);
        if (auto found = made.find(key); found != made.end()) return found->second;
    }
    std::vector<uint32_t> ramp;
    for (uint32_t c : textanim::CodePoints(characters)) {
        if (c >= 32 && c != 0x7F && ramp.size() < 256 && std::find(ramp.begin(), ramp.end(), c) == ramp.end()) ramp.push_back(c);
    }
    if (ramp.empty()) ramp = {' ', '.', ':', '-', '=', '+', '*', '#', '%', '@'};
    SkFont base(MonoFace('M'), 100);
    base.setEdging(SkFont::Edging::kAntiAlias);
    SkFontMetrics m;
    base.getMetrics(&m);
    const SkGlyphID em = base.unicharToGlyph('M');
    SkScalar advance = 0;
    base.getWidths({&em, 1}, {&advance, 1});
    base.setSize(98 * std::min(w / std::max(advance, 1.0f), h / std::max(m.fDescent - m.fAscent, 1.0f)));
    base.getMetrics(&m);
    const float baseline = (h - (m.fDescent - m.fAscent)) / 2 - m.fAscent;
    const auto draw = [&](SkCanvas* c, uint32_t ch, float x, float y) {
        SkFont font = base;
        font.setTypeface(MonoFace((int32_t)ch));
        const SkGlyphID g = font.unicharToGlyph((SkUnichar)ch);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        paint.setAntiAlias(true);
        c->save();
        c->translate(x, y);
        if (ch >= 0x2580 && ch <= 0x259F) {
            SkRect box;
            font.getBounds({&g, 1}, {&box, 1}, nullptr);
            if (!box.isEmpty()) c->concat(SkMatrix::RectToRect(box, SkRect::MakeIWH(w, h), SkMatrix::kFill_ScaleToFit));
            const SkPoint at = {0, 0};
            c->drawGlyphs({&g, 1}, {&at, 1}, {0, 0}, font, paint);
        } else {
            SkScalar a = 0;
            font.getWidths({&g, 1}, {&a, 1});
            const SkPoint at = {(w - a) / 2, baseline};
            c->drawGlyphs({&g, 1}, {&at, 1}, {0, 0}, font, paint);
        }
        c->restore();
    };
    std::vector<std::pair<double, uint32_t>> order;
    sk_sp<SkSurface> one = SkSurfaces::Raster(SkImageInfo::MakeA8(w, h));
    if (!one) return nullptr;
    SkPixmap pixels;
    for (uint32_t ch : ramp) {
        one->getCanvas()->clear(SK_ColorTRANSPARENT);
        draw(one->getCanvas(), ch, 0, 0);
        double sum = 0;
        if (one->peekPixels(&pixels)) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) sum += *pixels.addr8(x, y);
        }
        order.push_back({sum, ch});
    }
    std::stable_sort(order.begin(), order.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    for (uint32_t ch : {(uint32_t)'|', (uint32_t)'-', (uint32_t)'/', (uint32_t)'\\'}) order.push_back({0, ch});
    auto atlas = std::make_shared<GlyphAtlas>();
    atlas->count = (int)ramp.size();
    atlas->columns = std::min(16, (int)order.size());
    atlas->width = (float)w;
    atlas->height = (float)h;
    const int rows = ((int)order.size() + atlas->columns - 1) / atlas->columns;
    sk_sp<SkSurface> sheet = SkSurfaces::Raster(SkImageInfo::MakeN32Premul(atlas->columns * w, rows * h));
    if (!sheet) return nullptr;
    sheet->getCanvas()->clear(SK_ColorTRANSPARENT);
    for (size_t i = 0; i < order.size(); i++) draw(sheet->getCanvas(), order[i].second, (float)(i % atlas->columns * w), (float)(i / atlas->columns * h));
    atlas->image = sheet->makeImageSnapshot();
    std::lock_guard<std::mutex> hold(lock);
    if (made.size() >= 48) made.clear();
    made[key] = atlas;
    return atlas;
}

std::string Characters(const LayerEffect& e, const std::map<std::string, Anim>* defaults, const std::map<std::string, std::string>& texts, float t) {
    static const char* const sets[] = {
        " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~",
        " .:-=+*#%@", " \u2591\u2592\u2593\u2588", " 01"};
    const int set = std::clamp((int)std::lround(Value(e, defaults, "charset", 0, t, 0)), 0, 4);
    if (set < 4) return sets[set];
    const Property* p = e.prop("characters");
    const auto fallback = texts.find("characters");
    return p ? p->text : fallback == texts.end() ? std::string() : fallback->second;
}

int BayerRank(int x, int y, int n) {
    if (n == 1) return 0;
    const int h = n / 2, base[4] = {0, 2, 3, 1};
    return 4 * BayerRank(x % h, y % h, h) + base[(y / h) % 2 * 2 + (x / h) % 2];
}

std::vector<uint8_t> Ranked(int w, int h, const std::vector<float>& key) {
    std::vector<int> order(w * h);
    for (int i = 0; i < w * h; i++) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return key[a] < key[b]; });
    std::vector<uint8_t> out(w * h);
    for (int r = 0; r < w * h; r++) out[order[r]] = (uint8_t)std::lround((r + 0.5f) / (w * h) * 255);
    return out;
}

sk_sp<SkImage> MatrixFor(int method) {
    static std::mutex lock;
    static std::array<sk_sp<SkImage>, kMethods> made;
    method = std::clamp(method, 0, kMethods - 1);
    std::lock_guard<std::mutex> hold(lock);
    if (made[method]) return made[method];
    int w = 1, h = 1;
    std::vector<float> key = {0};
    const auto grid = [&](int gw, int gh, const std::function<float(int, int)>& f) {
        w = gw, h = gh, key.assign(gw * gh, 0);
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) key[y * gw + x] = f(x, y);
    };
    const int lines[4] = {2, 0, 1, 3}, spread[4] = {0, 2, 1, 3};
    switch (method) {
        case 3: grid(2, 2, [](int x, int y) { return (float)BayerRank(x, y, 2); }); break;
        case 4: grid(3, 3, [](int x, int y) { const int m[9] = {2, 6, 3, 5, 0, 8, 1, 7, 4}; return (float)m[y * 3 + x]; }); break;
        case 5: grid(4, 4, [](int x, int y) { return (float)BayerRank(x, y, 4); }); break;
        case 7: grid(4, 4, [](int x, int y) { return std::hypot(x - 1.5f, y - 1.5f) + std::atan2(y - 1.5f, x - 1.5f) * 0.001f; }); break;
        case 8: grid(8, 8, [](int x, int y) { return std::hypot(x - 3.5f, y - 3.5f) + std::atan2(y - 3.5f, x - 3.5f) * 0.001f; }); break;
        case 9: grid(4, 4, [&](int x, int y) { return (float)(lines[y] * 4 + spread[x]); }); break;
        case 10: grid(4, 4, [&](int x, int y) { return (float)(lines[x] * 4 + spread[y]); }); break;
        case 11: grid(4, 4, [&](int x, int y) { return (float)(lines[(x + y) % 4] * 4 + spread[x]); }); break;
        default: if (method >= 5) grid(8, 8, [](int x, int y) { return (float)BayerRank(x, y, 8); }); break;
    }
    const std::vector<uint8_t> ranks = method < 2 ? std::vector<uint8_t>{128} : method == 2 ? std::vector<uint8_t>{64, 191, 191, 64} : Ranked(w, h, key);
    if (method == 2) w = h = 2;
    std::vector<uint32_t> pixels(w * h);
    for (int i = 0; i < w * h; i++) pixels[i] = 0xff000000u | ranks[i] * 0x010101u;
    made[method] = Rgba(w, h, pixels.data(), kOpaque_SkAlphaType);
    return made[method];
}

struct Tap { int dx, dy, weight; };
struct Kernel { int divisor; std::vector<Tap> taps; };

const Kernel& KernelFor(int method) {
    static const std::vector<Kernel> kernels = {
        {16, {{1, 0, 7}, {-1, 1, 3}, {0, 1, 5}, {1, 1, 1}}},
        {8, {{1, 0, 3}, {0, 1, 3}, {1, 1, 2}}},
        {4, {{1, 0, 2}, {-1, 1, 1}, {0, 1, 1}}},
        {16, {{1, 0, 7}, {-2, 1, 1}, {-1, 1, 3}, {0, 1, 5}}},
        {16, {{1, 0, 8}, {-3, 1, 1}, {-2, 1, 1}, {-1, 1, 2}, {0, 1, 4}}},
        {48, {{1, 0, 7}, {2, 0, 5}, {-2, 1, 3}, {-1, 1, 5}, {0, 1, 7}, {1, 1, 5}, {2, 1, 3}, {-2, 2, 1}, {-1, 2, 3}, {0, 2, 5}, {1, 2, 3}, {2, 2, 1}}},
        {42, {{1, 0, 8}, {2, 0, 4}, {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2}, {-2, 2, 1}, {-1, 2, 2}, {0, 2, 4}, {1, 2, 2}, {2, 2, 1}}},
        {32, {{1, 0, 8}, {2, 0, 4}, {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2}}},
        {32, {{1, 0, 5}, {2, 0, 3}, {-2, 1, 2}, {-1, 1, 4}, {0, 1, 5}, {1, 1, 4}, {2, 1, 2}, {-1, 2, 2}, {0, 2, 3}, {1, 2, 2}}},
        {16, {{1, 0, 4}, {2, 0, 3}, {-2, 1, 1}, {-1, 1, 2}, {0, 1, 3}, {1, 1, 2}, {2, 1, 1}}},
        {8, {{1, 0, 1}, {2, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1}, {0, 2, 1}}},
    };
    return kernels[std::clamp(method - kDiffuse, 0, (int)kernels.size() - 1)];
}

void Diffuse(std::vector<uint8_t>* rgba, int w, int h, const Palette& palette, int method, float strength, float amount) {
    const Kernel& kernel = KernelFor(method);
    struct Share { int dx, dy; float k; };
    std::vector<Share> shares;
    for (const Tap& tap : kernel.taps) shares.push_back({tap.dx, tap.dy, strength * tap.weight / kernel.divisor});
    const bool whole = amount >= 1;
    std::vector<float> error((size_t)3 * w * 3, 0.0f);
    for (int y = 0; y < h; y++) {
        float* rows[3] = {&error[(size_t)(y % 3) * w * 3], &error[(size_t)((y + 1) % 3) * w * 3], &error[(size_t)((y + 2) % 3) * w * 3]};
        std::fill(rows[2], rows[2] + (size_t)w * 3, 0.0f);
        for (int x = 0; x < w; x++) {
            uint8_t* px = rgba->data() + ((size_t)y * w + x) * 4;
            if (px[3] == 0) continue;
            float v[3], e[3];
            for (int c = 0; c < 3; c++) v[c] = std::clamp(px[c] + rows[0][x * 3 + c], 0.0f, 255.0f);
            const uint32_t q = palette.nearest((int)(v[0] + 0.5f), (int)(v[1] + 0.5f), (int)(v[2] + 0.5f));
            const int out[3] = {(int)(q >> 16 & 255), (int)(q >> 8 & 255), (int)(q & 255)};
            for (int c = 0; c < 3; c++) {
                e[c] = v[c] - out[c];
                px[c] = whole ? (uint8_t)out[c] : (uint8_t)(px[c] + (out[c] - px[c]) * amount + 0.5f);
            }
            for (const Share& share : shares) {
                const int nx = x + share.dx;
                if (nx < 0 || nx >= w || y + share.dy >= h) continue;
                float* to = rows[share.dy] + nx * 3;
                to[0] += e[0] * share.k, to[1] += e[1] * share.k, to[2] += e[2] * share.k;
            }
        }
    }
}

}

std::string EffectRuntime::load(const std::string& xml) {
    std::string error;
    auto root = ParseXml(xml, &error);
    if (!root || root->name != "effect" || !root->attr("id")) return "shader error: bad definition " + error;
    Definition d;
    for (const auto& c : root->children) {
        if (c->name != "param" || !c->attr("name")) continue;
        d.defaults[c->attr("name")] = ParseAnimNode(c.get());
        if (c->attr("value")) d.texts[c->attr("name")] = c->attr("value");
    }
    const std::string id = root->attr("id");
    const char* sksl = root->attr("sksl");
    if (!sksl || !*sksl) {
        if (!BuiltIn(id) && !pieces3d::IsEffect(id) && !MotionEffect(id) && !RepeatEffect(id) && !TextEffect(id) && !DrawingEffect(id) && !ParticleEffect(id)) return "shader error: no shader and no built-in called " + id;
        d.backdrop = id == "absent.glow";
        if (pieces3d::IsEffect(id)) NoteOrientedEffect(id);
    } else {
        auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
        if (!result.effect) {
            const std::string text = result.errorText.c_str();
            return "shader error: " + text.substr(0, text.find('\n')) + "\n" + text;
        }
        if (!result.effect->findChild("image")) return "shader error: no 'uniform shader image'";
        d.brightPass = result.effect->findChild("bright") != nullptr;
        d.plainBlur = result.effect->findChild("blurred") != nullptr;
        d.blurred = d.brightPass || d.plainBlur;
        d.backdrop = result.effect->findChild("backdrop") != nullptr;
        d.below = result.effect->findChild("below") != nullptr;
        d.pointwise = Pointwise(sksl);
        d.timed = (result.effect->findUniform("time") && Mentions(sksl, "time") > 1) || result.effect->findUniform("beat") ||
                  result.effect->findUniform("beatTime") || result.effect->findUniform("beatIndex");
        if (result.effect->findUniform("orient")) NoteOrientedEffect(id);
        d.palette = result.effect->findChild("palette") != nullptr;
        d.matrix = result.effect->findChild("thresholds") != nullptr;
        d.glyphs = result.effect->findChild("glyphs") != nullptr;
        d.shader = std::move(result.effect);
    }
    fDefinitions[id] = std::move(d);
    return "ok";
}

const EffectRuntime::Definition* EffectRuntime::find(const std::string& id) const {
    auto d = fDefinitions.find(id);
    return d == fDefinitions.end() ? nullptr : &d->second;
}

int EffectRuntime::method(const LayerEffect& e, const Definition* d, float t) const {
    return std::clamp((int)std::lround(Value(e, &d->defaults, "pattern", 0, t, 0)), 0, kMethods - 1);
}

std::shared_ptr<const Palette> EffectRuntime::palette(const LayerEffect& e, const Definition* d, float t) const {
    std::vector<uint32_t> colors;
    if (d->defaults.count("source") && Value(e, &d->defaults, "source", 0, t, 0) < 0.5f) {
        const int n = std::clamp((int)std::lround(Value(e, &d->defaults, "colors", 0, t, 8)), 2, 8);
        for (int i = 1; i <= n; i++) {
            const std::string name = "color" + std::to_string(i);
            uint32_t c = 0;
            for (int k = 1; k <= 3; k++) c = c << 8 | (uint32_t)std::clamp((int)std::lround(Value(e, &d->defaults, name, k, t, 0) * 255), 0, 255);
            colors.push_back(c);
        }
    } else {
        const Property* p = e.prop("palette");
        const auto fallback = d->texts.find("palette");
        colors = ParseColors(p ? p->text : fallback == d->texts.end() ? std::string() : fallback->second);
    }
    return PaletteFor(std::move(colors));
}

int EffectRuntime::diffusing(const Layer& layer, int lift, float t) const {
    for (size_t k = (size_t)(lift + 1); k < layer.effects.size(); k++) {
        const LayerEffect& e = layer.effects[k];
        const Definition* d = e.hidden ? nullptr : find(e.id);
        if (d && d->shader && d->palette && d->matrix && method(e, d, t) >= kDiffuse) return (int)k;
    }
    return -1;
}

bool EffectRuntime::mayDiffuse(const LayerEffect& e, const Definition* d) const {
    const auto high = [](const std::vector<float>& v) { return !v.empty() && std::lround(v[0]) >= kDiffuse; };
    const auto any = [&](const Anim& a) {
        return high(a.value) || (a.hasLead && high(a.lead.v)) || std::any_of(a.keys.begin(), a.keys.end(), [&](const Keyframe& k) { return high(k.v); });
    };
    if (const Property* p = e.prop("pattern")) return any(p->anim);
    const auto fallback = d->defaults.find("pattern");
    return fallback != d->defaults.end() && any(fallback->second);
}

bool EffectRuntime::grabs(const std::vector<Layer>& layers) const {
    return std::any_of(layers.begin(), layers.end(), [&](const Layer& l) {
        return std::any_of(l.effects.begin(), l.effects.end(), [&](const LayerEffect& e) {
            const Definition* d = e.hidden ? nullptr : find(e.id);
            return !e.hidden && (pieces3d::IsEffect(e.id) || (d && d->palette && d->matrix && mayDiffuse(e, d)));
        }) || grabs(l.children);
    });
}

bool EffectRuntime::active(const Layer& layer) const {
    return std::any_of(layer.effects.begin(), layer.effects.end(), [&](const LayerEffect& e) {
        if (e.hidden) return false;
        const Definition* d = find(e.id);
        return BuiltIn(e.id) || pieces3d::IsEffect(e.id) || (d && d->shader);
    });
}

int EffectRuntime::liftAt(const Layer& layer) const {
    int at = -1;
    for (size_t i = 0; i < layer.effects.size(); i++) {
        if (!layer.effects[i].hidden && layer.effects[i].id == kLift) at = (int)i;
    }
    return at;
}

bool EffectRuntime::lifted(const Layer& layer) const { return liftAt(layer) >= 0; }

bool EffectRuntime::spills(const Layer& layer) const {
    const int lift = liftAt(layer);
    if (lift < 0) return false;
    const LayerEffect& e = layer.effects[lift];
    const Definition* d = find(e.id);
    return Value(e, d ? &d->defaults : nullptr, "spill", 0, 0, 0) > 0.5f;
}

bool EffectRuntime::needs(const Layer& layer, bool Definition::*input) const {
    return std::any_of(layer.effects.begin(), layer.effects.end(), [&](const LayerEffect& e) {
        const Definition* d = e.hidden ? nullptr : find(e.id);
        return d && (d->shader || BuiltIn(e.id)) && d->*input;
    });
}

bool EffectRuntime::behind(const Layer& layer) const { return needs(layer, &Definition::backdrop); }

bool EffectRuntime::steady(const Layer& layer, float from, float to) const {
    const auto still = [&](const Anim& a) { return a.steady(from, to); };
    return std::all_of(layer.effects.begin(), layer.effects.end(), [&](const LayerEffect& e) {
        if (e.hidden) return true;
        if (e.id == kPuppet) {
            const Property* sway = e.prop("sway");
            if (sway && (!still(sway->anim) || sway->anim.get(0, from, 0) >= 0.5f)) return false;
        }
        const Definition* d = find(e.id);
        if (d && (d->timed || d->below)) return false;
        if (d && !std::all_of(d->defaults.begin(), d->defaults.end(), [&](const auto& k) { return still(k.second); })) return false;
        return std::all_of(e.props.begin(), e.props.end(), [&](const Property& p) { return still(p.anim); });
    });
}

sk_sp<SkImageFilter> EffectRuntime::chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkRect& seen,
                                          const SkRect& drawn, const SkMatrix& placed, sk_sp<SkImageFilter> content,
                                          sk_sp<SkImageFilter> below, int before, int from) const {
    const float t = layer.localTime(timeMs), seconds = (float)((layer.steppedMs(timeMs) - layer.startTime) / 1000.0);
    const float reach = std::max({(float)scene.width, (float)scene.height, seen.width(), seen.height()});
    const SkRect bounds = seen.makeOffset(-area.left(), -area.top()).makeOutset(before >= 0 ? reach : 0, before >= 0 ? reach : 0);
    std::optional<SkRect> shape;
    size_t at = 0, shapeAt = 0;
    const auto own = [&]() -> const SkRect& {
        if (!shape || shapeAt != at) shape = CroppedBox(layer, t, at), shapeAt = at;
        return *shape;
    };
    const auto fromLayer = [&] { return SkMatrix::Translate(-area.left(), -area.top()) * placed * SkMatrix::Translate(own().left(), own().top()); };
    const auto columns = [](const SkMatrix& m) { return std::vector<float>{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]}; };
    const bool withBackdrop = content != nullptr;
    sk_sp<SkImageFilter> f = std::move(content);
    bool any = false;
    const size_t end = before >= 0 ? std::min((size_t)before, layer.effects.size()) : layer.effects.size();
    const auto pointwiseAfter = [&](size_t from) {
        for (size_t j = from; j < end; j++) {
            const LayerEffect& next = layer.effects[j];
            if (next.hidden || next.id == kLift || (layer.video && next.id == "absent.datamosh")) continue;
            if (next.id == kPuppet || BuiltIn(next.id)) return false;
            const Definition* d = find(next.id);
            if (d && d->shader && (!d->pointwise || d->blurred)) return false;
        }
        return true;
    };
    for (size_t k = from >= 0 ? (size_t)from : before >= 0 ? 0 : (size_t)(liftAt(layer) + 1); k < end; k++) {
        const LayerEffect& e = layer.effects[k];
        at = k;
        if (e.hidden || e.id == kLift || (layer.video && e.id == "absent.datamosh")) continue;
        const Definition* d = find(e.id);
        const auto* defaults = d ? &d->defaults : nullptr;
        if (e.id == kPuppet) {
            f = PuppetFilter(e, defaults, t, timeMs, area, placed, bounds, f ? f->computeFastBounds(drawn) : drawn, reach, f);
            if (!f) continue;
        } else if (BuiltIn(e.id)) {
            const auto value = [&](const char* name, int i, float fallback) { return Value(e, defaults, name, i, t, fallback); };
            if (e.id == "absent.glow") {
                const bool first = withBackdrop && !any;
                const float extent = GlowExtent(value);
                const SkRect lit = (first ? f->computeFastBounds(bounds) : bounds).makeOutset(extent, extent);
                f = GlowFilter(value, f, withBackdrop);
                if (first && !lit.contains(bounds) && pointwiseAfter(k + 1)) f = SkImageFilters::Crop(lit, f);
            } else {
                f = BuiltInFilter(e.id, value, f);
            }
        } else if (d && d->shader) {
            SkRuntimeShaderBuilder b(d->shader);
            const std::shared_ptr<const Palette> colors = d->palette ? this->palette(e, d, t) : nullptr;
            if (colors) {
                static const uint32_t black = 0xff000000u;
                static const sk_sp<SkImage> blank = Rgba(1, 1, &black, kOpaque_SkAlphaType);
                if (sk_sp<SkImage> lut = OnGpu(colors->colors.size() > 16 ? colors->image() : blank)) b.child("palette") = lut->makeShader(SkSamplingOptions());
            }
            if (sk_sp<SkImage> tile = d->matrix ? OnGpu(MatrixFor(method(e, d, t))) : nullptr) {
                b.child("thresholds") = tile->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, SkSamplingOptions());
            }
            float zoom = 1;
            if (d->glyphs) {
                const SkMatrix m = fromLayer();
                zoom = std::sqrt(std::fabs(m.getScaleX() * m.getScaleY() - m.getSkewX() * m.getSkewY()));
                if (!std::isfinite(zoom) || zoom < 0.01f) zoom = 1;
            }
            const std::shared_ptr<const GlyphAtlas> letters = d->glyphs ? AtlasFor(Characters(e, defaults, d->texts, t), Value(e, defaults, "cell", 0, t, 12) * zoom) : nullptr;
            if (sk_sp<SkImage> sheet = letters ? OnGpu(letters->image) : nullptr) b.child("glyphs") = sheet->makeShader(SkSamplingOptions(SkFilterMode::kLinear));
            std::optional<particles::Beat> beat;
            const auto beatNow = [&]() -> const particles::Beat& {
                if (!beat) beat = particles::LastBeat(scene.beats, timeMs / 1000.0, 1 << std::clamp((int)std::lround(Value(e, defaults, "beats", 0, t, 0)), 0, 2),
                                                      Value(e, defaults, "offset", 0, t, 0), Value(e, defaults, "bpm", 0, t, 120));
                return *beat;
            };
            for (const SkRuntimeEffect::Uniform& u : d->shader->uniforms()) {
                const std::string name(u.name);
                const int n = (int)(u.sizeInBytes() / 4);
                std::vector<float> v(n);
                const bool automatic = !(defaults && defaults->count(name));
                if (automatic && name == "size") v = {area.width(), area.height()};
                else if (automatic && name == "time") v = {seconds};
                else if (automatic && name == "layer") v = {std::max(own().width(), 1.0f), std::max(own().height(), 1.0f)};
                else if (automatic && name == "fromLayer") v = columns(fromLayer());
                else if (automatic && name == "toLayer") {
                    SkMatrix inverse;
                    v = columns(fromLayer().invert(&inverse) ? inverse : SkMatrix::I());
                }
                else if (automatic && name == "duration") v = {(float)((layer.endTime - layer.startTime) / 1000.0)};
                else if (automatic && name == "orient") v = columns3(Orientation(layer, timeMs));
                else if (automatic && name == "paletteSize") v = {colors ? (float)colors->colors.size() : 0.0f};
                else if (automatic && name == "paletteSpread") v = {colors ? colors->spread : 1.0f};
                else if (automatic && name == "swatches" && colors) {
                    for (size_t i = 0; i < colors->colors.size() && 4 * i + 3 < v.size(); i++) {
                        const uint32_t c = colors->colors[i];
                        v[4 * i] = (c >> 16 & 255) / 255.0f, v[4 * i + 1] = (c >> 8 & 255) / 255.0f, v[4 * i + 2] = (c & 255) / 255.0f, v[4 * i + 3] = 1;
                    }
                }
                else if (automatic && name == "glyphCount") v = {letters ? (float)letters->count : 1.0f};
                else if (automatic && name == "glyphColumns") v = {letters ? (float)letters->columns : 1.0f};
                else if (automatic && name == "glyphCell") v = {letters ? letters->width : 1.0f, letters ? letters->height : 1.0f};
                else if (automatic && name == "visible") {
                    const SkRect r = seen.makeOffset(-area.left(), -area.top());
                    v = {r.left(), r.top(), r.right(), r.bottom()};
                }
                else if (automatic && name == "screen") v = {-area.left(), -area.top(), (float)scene.width, (float)scene.height};
                else if (automatic && name == "beat") v = {particles::Envelope(beatNow().since, Value(e, defaults, "decay", 0, t, 0.4f))};
                else if (automatic && name == "beatTime") v = {(float)beatNow().since};
                else if (automatic && name == "beatIndex") v = {(float)beatNow().index};
                else if (u.isColor() && n == 4) v = {Value(e, defaults, name, 1, t, 0), Value(e, defaults, name, 2, t, 0),
                                                     Value(e, defaults, name, 3, t, 0), Value(e, defaults, name, 0, t, 1)};
                else for (int i = 0; i < n; i++) v[i] = Value(e, defaults, name, i, t, 0);
                v.resize(n);
                if (u.type >= SkRuntimeEffect::Uniform::Type::kInt) {
                    std::vector<int> ints(v.begin(), v.end());
                    b.uniform(u.name).set(ints.data(), n);
                } else {
                    b.uniform(u.name).set(v.data(), n);
                }
            }
            std::vector<std::string_view> names = {"image"};
            std::vector<sk_sp<SkImageFilter>> inputs = {f};
            if (d->blurred) {
                const float spread = e.id == "absent.electricedges" ? Value(e, defaults, "strength", 0, t, -1) : -1;
                const float sigma = spread >= 0 ? std::max(spread, 0.02f) * kEdgeSpread * std::hypot((float)scene.width, (float)scene.height)
                                                : std::max(Value(e, defaults, "blur", 0, t, 0), 0.0f) / 3;
                auto blurOf = [&](sk_sp<SkImageFilter> source) {
                    return sigma > 0 ? SkImageFilters::Blur(sigma, sigma, SkTileMode::kDecal, source) : source;
                };
                if (d->brightPass) {
                    const float cut = Value(e, defaults, "threshold", 0, t, 0);
                    const float luma[5] = {0.2126f, 0.7152f, 0.0722f, 0.0f, -cut};
                    const float rows[20] = {luma[0], luma[1], luma[2], luma[3], luma[4], luma[0], luma[1], luma[2], luma[3], luma[4],
                                            luma[0], luma[1], luma[2], luma[3], luma[4], 0, 0, 0, 1, 0};
                    names.push_back("bright");
                    inputs.push_back(blurOf(SkImageFilters::ColorFilter(SkColorFilters::Matrix(rows), f)));
                }
                if (d->plainBlur) {
                    names.push_back("blurred");
                    inputs.push_back(blurOf(f));
                }
            }
            if (d->backdrop) {
                names.push_back("backdrop");
                inputs.push_back(nullptr);
            }
            if (d->below) {
                names.push_back("below");
                inputs.push_back(below ? below : SkImageFilters::Empty());
            }
            const float radius = d->pointwise ? 0.0f : reach;
            f = inputs.size() == 1 ? SkImageFilters::RuntimeShader(b, radius, "image", f)
                                   : SkImageFilters::RuntimeShader(b, radius, names.data(), inputs.data(), (int)inputs.size());
        } else {
            continue;
        }
        f = SkImageFilters::Crop(bounds, f);
        any = true;
    }
    return any ? f->makeWithLocalMatrix(SkMatrix::Translate(area.left(), area.top())) : nullptr;
}

bool EffectRuntime::draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkMatrix& placed,
                         const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below, const Grab& grab,
                         const Upload& upload) const {
    tUpload = upload;
    const SkRect bounds = WorldView() ? canvas->getLocalClipBounds() : SkRect::MakeIWH(scene.width, scene.height);
    const int lift = liftAt(layer);
    const float t = layer.localTime(timeMs);
    const SkRect at = area.isEmpty() ? bounds : area;
    const SkRect local = bounds.makeOffset(-at.left(), -at.top());
    auto record = [&](const std::function<void(SkCanvas*)>& draw, SkRect* bounded = nullptr, float outset = 0) {
        SkPictureRecorder recorder;
        SkRTreeFactory tight;
        SkCanvas* c = recorder.beginRecording(local.makeOutset(outset, outset), &tight);
        c->translate(-at.left(), -at.top());
        draw(c);
        sk_sp<SkPicture> picture = recorder.finishRecordingAsPicture();
        const SkRect drawn = picture->cullRect().makeOutset(2, 2);
        if (bounded) *bounded = drawn.makeOffset(at.left(), at.top());
        return SkImageFilters::Picture(std::move(picture), drawn);
    };
    if (lift < 0 && std::any_of(layer.effects.begin(), layer.effects.end(), [](const LayerEffect& e) { return !e.hidden && pieces3d::IsEffect(e.id); })) {
        std::function<void(SkCanvas*)> current = source;
        int from = 0, last = -1;
        depth::Pass* const pass = depth::Active() && depth::Active()->target == &layer ? depth::Active() : nullptr;
        for (int k = 0; k < (int)layer.effects.size(); k++) {
            if (!layer.effects[k].hidden && pieces3d::IsEffect(layer.effects[k].id)) last = k;
        }
        for (int k = 0; k < (int)layer.effects.size(); k++) {
            const LayerEffect& e = layer.effects[k];
            if (e.hidden || !pieces3d::IsEffect(e.id)) continue;
            const Definition* d = find(e.id);
            if (e.id == "absent.fracture3d" && Value(e, d ? &d->defaults : nullptr, "progress", 0, t, 0) <= 0 && Orientation(layer, timeMs) == SkM44()) continue;
            float step = std::max(1.0f, std::max(at.width(), at.height()) / 2048.0f);
            if (e.id == "absent.scatter3d") {
                const int depth = std::clamp((int)std::round(Value(e, d ? &d->defaults : nullptr, "depth", 0, t, 1)), 1, 16);
                step = std::max({step, std::clamp(Value(e, d ? &d->defaults : nullptr, "spacing", 0, t, 24), 1.0f, 10000.0f),
                                 std::sqrt(at.width() * at.height() * depth / (20000 * 0.85f))});
                while ((int64_t)std::max(1, (int)std::ceil(at.width() / step)) * std::max(1, (int)std::ceil(at.height() / step)) * depth > 20000) step *= 1.05f;
            }
            const int width = std::max(1, (int)std::ceil(at.width() / step)), height = std::max(1, (int)std::ceil(at.height() / step));
            sk_sp<SkImageFilter> before = chain(scene, layer, timeMs, at, bounds, local, placed, nullptr, nullptr, k, from);
            std::vector<uint8_t> pixels;
            const auto capture = [&](SkCanvas* c) {
                c->scale((float)width / at.width(), (float)height / at.height());
                c->translate(-at.left(), -at.top());
                if (before) {
                    SkPaint p;
                    p.setImageFilter(before);
                    c->saveLayer(&bounds, &p);
                    current(c);
                    c->restore();
                } else current(c);
            };
            bool ok = grab && grab(width, height, capture, &pixels);
            if (!grab) {
                const SkImageInfo info = SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
                sk_sp<SkSurface> surface = SkSurfaces::Raster(info);
                if (surface) {
                    surface->getCanvas()->clear(SK_ColorTRANSPARENT);
                    capture(surface->getCanvas());
                    pixels.resize((size_t)width * height * 4);
                    ok = surface->readPixels(info.makeAlphaType(kUnpremul_SkAlphaType), pixels.data(), (size_t)width * 4, 0, 0);
                }
            }
            if (!ok || pixels.size() != (size_t)width * height * 4) return false;
            sk_sp<SkImage> image;
            if (e.id == "absent.fracture3d") {
                image = OnGpu(Rgba(width, height, pixels.data(), kUnpremul_SkAlphaType, SkColorSpace::MakeSRGB()));
                if (!image) return false;
            }
            const auto value = [&](const char* name, int i, float fallback) { return Value(e, d ? &d->defaults : nullptr, name, i, t, fallback); };
            const bool handing = pass && k == last;
            SkPictureRecorder recorder;
            SkCanvas* out = handing ? canvas : recorder.beginRecording(bounds);
            const auto& sourceLayers = layer.sight.layers ? *layer.sight.layers : EffectSiblings() ? *EffectSiblings() : scene.layers;
            const Layer& original = layer.sight.layer ? *layer.sight.layer : layer;
            const Placement world = EffectivePlacement(sourceLayers, original, timeMs);
            const LightSet lights = SceneLights(sourceLayers, timeMs);
            const SkM44 orientation = Orientation(layer, timeMs);
            PieceView view;
            view.origin = {world.location.x(), world.location.y(), world.z};
            SeenPieces(layer, timeMs, at.center(), orientation, &view);
            pieces3d::Draw(out, e.id, at, orientation, value, pixels, width, height, image.get(), lights, view, handing ? pass : nullptr);
            if (handing) return true;
            sk_sp<SkPicture> result = recorder.finishRecordingAsPicture();
            current = [result](SkCanvas* c) { c->drawPicture(result); };
            from = k + 1;
        }
        SkPictureRecorder recorder;
        SkCanvas* out = pass ? recorder.beginRecording(bounds) : canvas;
        SkPaint paint;
        paint.setImageFilter(chain(scene, layer, timeMs, at, bounds, local, placed, nullptr, nullptr, -1, from));
        out->saveLayer(&bounds, &paint);
        current(out);
        out->restore();
        if (pass) pass->picture(recorder.finishRecordingAsPicture(), canvas->getTotalMatrix(), pass->depth);
        return true;
    }
    const bool seesBehind = lift < 0 && behind(layer), bent = lift < 0 && Bent(layer, t, timeMs);
    const float margin = bent ? std::max({(float)scene.width, (float)scene.height, bounds.width(), bounds.height()}) : 0;
    SkRect drawn = SkRect::MakeEmpty();
    sk_sp<SkImageFilter> content = seesBehind ? record(source, &drawn, margin) : nullptr;
    sk_sp<SkImageFilter> under = below && needs(layer, &Definition::below) ? record(below) : nullptr;
    const LayerEffect* adjustment = lift >= 0 ? &layer.effects[lift] : nullptr;
    const Definition* adjustmentType = adjustment ? find(adjustment->id) : nullptr;
    const auto setting = [&](const char* name) {
        return adjustment ? Value(*adjustment, adjustmentType ? &adjustmentType->defaults : nullptr, name, 0, t, 0) : 0.0f;
    };
    const float own = std::clamp(setting("mix"), 0.0f, 1.0f);
    const bool masked = setting("mask") > 0.5f, spill = setting("spill") > 0.5f;
    sk_sp<SkImageFilter> early;
    if (lift > 0) {
        const float margin = std::max({(float)scene.width, (float)scene.height, bounds.width(), bounds.height()});
        early = this->chain(scene, layer, timeMs, at, bounds, local, placed, record(source, nullptr, margin), nullptr, lift);
    }
    const auto shaped = [&](SkCanvas* c) {
        if (!early) return source(c);
        SkPaint p;
        p.setImageFilter(early);
        c->saveLayer(&bounds, &p);
        c->restore();
    };
    const auto adjust = [&](SkCanvas* c) {
        if (own > 0) {
            c->saveLayerAlphaf(&bounds, own);
            shaped(c);
            c->restore();
        }
        if (masked) {
            SkPaint mask;
            mask.setBlendMode(SkBlendMode::kDstIn);
            c->saveLayer(&bounds, &mask);
            shaped(c);
            c->restore();
        }
    };
    if (!seesBehind && grab) {
        sk_sp<SkImage> image;
        sk_sp<SkSpecialImage> backdrop;
        SkRect imageGrid = SkRect::MakeEmpty(), region = bounds;
        SkMatrix fromDevice;
        SkIRect taken = SkIRect::MakeEmpty();
        int done = lift;
        bool failed = false;
        const auto bare = [&](int from, int to) {
            for (int j = std::max(from, 0); j < to; j++) {
                const Definition* dx = layer.effects[j].hidden ? nullptr : find(layer.effects[j].id);
                if (dx && (dx->shader || BuiltIn(layer.effects[j].id))) return false;
            }
            return true;
        };
        const int first = diffusing(layer, lift, t);
        if (first >= 0 && lift < 0 && bare(0, first)) {
            SkRect measured;
            record(source, &measured);
            if (!region.intersect(measured)) region.setEmpty();
        }
        for (int k = first; k >= 0 && !failed; k = diffusing(layer, done, t)) {
            if (image) region = bare(done + 1, k) ? imageGrid : bounds;
            const LayerEffect& e = layer.effects[k];
            const Definition* d = find(e.id);
            const float cell = std::clamp(std::round(Value(e, &d->defaults, "pixel", 0, t, 1)), 1.0f, 256.0f);
            const auto snap = [&](float v, float origin, bool up) { return origin + (up ? std::ceil((v - origin) / cell) : std::floor((v - origin) / cell)) * cell; };
            const SkRect grid = SkRect::MakeLTRB(snap(region.left(), at.left(), false), snap(region.top(), at.top(), false),
                                                 snap(region.right(), at.left(), true), snap(region.bottom(), at.top(), true));
            const int gw = (int)std::lround(grid.width() / cell), gh = (int)std::lround(grid.height() / cell);
            if (lift >= 0 && !image) {
                SkDevice* device = SkCanvasPriv::TopDevice(canvas);
                if (device && device->localToDevice().invert(&fromDevice)) {
                    taken = device->localToDevice().mapRect(grid).roundOut();
                    if (taken.intersect(SkIRect::MakeWH(device->width(), device->height()))) backdrop = device->snapSpecial(taken, true);
                }
            }
            std::vector<uint8_t> pixels;
            failed = gw <= 0 || gh <= 0 || (int64_t)gw * gh > 4096 * 4096 || (lift >= 0 && !image && !backdrop);
            if (failed) break;
            sk_sp<SkImageFilter> before = this->chain(scene, layer, timeMs, at, bounds, local, placed, nullptr, under, k, done + 1);
            const bool read = grab(gw, gh, [&](SkCanvas* c) {
                c->scale(1 / cell, 1 / cell);
                c->translate(-grid.left(), -grid.top());
                SkPaint p;
                p.setImageFilter(before);
                c->saveLayer(&grid, &p);
                if (image) {
                    c->drawImageRect(image, imageGrid, SkSamplingOptions(SkFilterMode::kLinear));
                } else if (lift < 0) {
                    source(c);
                } else {
                    c->save();
                    c->concat(fromDevice);
                    c->drawImageRect(backdrop->asImage(), SkRect::Make(backdrop->subset()), SkRect::Make(taken), SkSamplingOptions(SkFilterMode::kLinear),
                                     nullptr, SkCanvas::kStrict_SrcRectConstraint);
                    c->restore();
                    adjust(c);
                }
                c->restore();
            }, &pixels);
            backdrop.reset();
            failed = !read || pixels.size() != (size_t)gw * gh * 4;
            if (failed) break;
            const float strength = Value(e, &d->defaults, "dither", 0, t, 1);
            Diffuse(&pixels, gw, gh, *palette(e, d, t), method(e, d, t), std::isfinite(strength) ? std::max(strength, 0.0f) : 0.0f,
                    std::clamp(Value(e, &d->defaults, "alpha", 0, t, 1), 0.0f, 1.0f));
            image = OnGpu(Owned(std::move(pixels), gw, gh));
            imageGrid = grid;
            done = k;
            failed = !image;
        }
        if (image && !failed) {
            SkPaint paint;
            paint.setImageFilter(this->chain(scene, layer, timeMs, at, bounds, local, placed, nullptr, under, -1, done + 1));
            if (lift >= 0) paint.setBlendMode(masked && !spill ? SkBlendMode::kSrcOver : SkBlendMode::kSrc);
            canvas->saveLayer(&bounds, &paint);
            canvas->drawImageRect(image, imageGrid, SkSamplingOptions(SkFilterMode::kNearest));
            canvas->restore();
            return true;
        }
    }
    SkRect inside = local, layerBounds = bounds;
    if (bent) {
        SkRect measured = drawn;
        if (!seesBehind) record(source, &measured, margin);
        const float scale = canvas->getTotalMatrix().getMinScale(), pad = scale > 0 ? 4 / scale : 4;
        inside = measured.makeOffset(-at.left(), -at.top()).makeOutset(pad, pad);
        layerBounds.join(inside.makeOffset(at.left(), at.top()));
    }
    sk_sp<SkImageFilter> filter = this->chain(scene, layer, timeMs, at, layerBounds, inside, placed, std::move(content), std::move(under));
    if (!filter && lift < 0) return false;
    if (seesBehind) {
        float reach = 0;
        bool glowOnly = true;
        for (const LayerEffect& e : layer.effects) {
            const Definition* d = find(e.id);
            if (e.hidden || !(BuiltIn(e.id) || (d && d->shader))) continue;
            if (e.id != "absent.glow") { glowOnly = false; break; }
            reach += GlowExtent([&](const char* name, int i, float fallback) { return Value(e, d ? &d->defaults : nullptr, name, i, t, fallback); });
        }
        SkPaint replace;
        replace.setBlendMode(SkBlendMode::kSrc);
        canvas->save();
        if (glowOnly && !drawn.isEmpty()) {
            const SkRect region = drawn.makeOutset(reach, reach);
            canvas->save();
            canvas->clipRect(bounds);
            canvas->clipRect(region, SkClipOp::kDifference);
            canvas->drawColor(SK_ColorTRANSPARENT, SkBlendMode::kSrc);
            canvas->restore();
            canvas->clipRect(region);
        }
        canvas->saveLayer(SkCanvas::SaveLayerRec(&bounds, &replace, filter.get(), 0));
        canvas->restore();
        canvas->restore();
        return true;
    }
    SkPaint paint;
    paint.setImageFilter(std::move(filter));
    if (lift < 0) {
        canvas->saveLayer(&layerBounds, &paint);
        source(canvas);
        canvas->restore();
        return true;
    }
    paint.setBlendMode(masked && !spill ? SkBlendMode::kSrcOver : SkBlendMode::kSrc);
    canvas->saveLayer(SkCanvas::SaveLayerRec(&bounds, &paint, SkCanvas::kInitWithPrevious_SaveLayerFlag));
    adjust(canvas);
    canvas->restore();
    return true;
}

}
