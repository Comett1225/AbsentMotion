#include "effects.h"

#include "warp.h"
#include "xml.h"
#include "include/core/SkBBHFactory.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkM44.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "include/effects/SkImageFilters.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
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

std::vector<warp::Pin> PuppetPins(const LayerEffect& e, float t) {
    std::vector<warp::Pin> pins;
    for (const Property& p : e.props) {
        if (p.name.size() < 4 || p.name.size() > 12 || p.name.compare(0, 3, "pin") != 0 || p.name[3] == '0' ||
            !std::all_of(p.name.begin() + 3, p.name.end(), [](char c) { return c >= '0' && c <= '9'; })) continue;
        const Property* r = e.prop(("rest" + p.name.substr(3)).c_str());
        if (!r) continue;
        warp::Pin pin{p.anim.get(0, t, NAN), p.anim.get(1, t, NAN), r->anim.get(0, t, NAN), r->anim.get(1, t, NAN)};
        if (!std::isfinite(pin.x) || !std::isfinite(pin.y) || !std::isfinite(pin.restX) || !std::isfinite(pin.restY)) continue;
        pins.push_back(pin);
        if ((int)pins.size() == warp::kMaxPins) break;
    }
    return pins;
}

bool Bent(const Layer& layer, float t) {
    return std::any_of(layer.effects.begin(), layer.effects.end(), [&](const LayerEffect& e) {
        if (e.hidden || e.id != kPuppet) return false;
        const std::vector<warp::Pin> pins = PuppetPins(e, t);
        return !pins.empty() && !warp::AtRest(pins);
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

sk_sp<SkImageFilter> PuppetFilter(const LayerEffect& e, const std::map<std::string, Anim>* defaults, float t, double timeMs, const SkRect& area,
                                  SkMatrix placed, const SkRect& seen, const SkRect& inside, float reach, sk_sp<SkImageFilter> input) {
    static const sk_sp<SkRuntimeEffect> effect = SkRuntimeEffect::MakeForShader(SkString(kPuppetShader)).effect;
    const std::vector<warp::Pin> pins = PuppetPins(e, t);
    if (!effect || pins.empty() || warp::AtRest(pins)) return input;
    if (e.owner && !EffectOwnerMatrix(e.owner, timeMs, &placed)) return input;
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

}

std::string EffectRuntime::load(const std::string& xml) {
    std::string error;
    auto root = ParseXml(xml, &error);
    if (!root || root->name != "effect" || !root->attr("id")) return "shader error: bad definition " + error;
    Definition d;
    for (const auto& c : root->children) {
        if (c->name == "param" && c->attr("name")) d.defaults[c->attr("name")] = ParseAnimNode(c.get());
    }
    const std::string id = root->attr("id");
    const char* sksl = root->attr("sksl");
    if (!sksl || !*sksl) {
        if (!BuiltIn(id) && !MotionEffect(id) && !RepeatEffect(id) && !TextEffect(id) && !DrawingEffect(id)) return "shader error: no shader and no built-in called " + id;
        d.backdrop = id == "absent.glow";
    } else {
        auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
        if (!result.effect) {
            const std::string text = result.errorText.c_str();
            return "shader error: " + text.substr(0, text.find('\n')) + "\n" + text;
        }
        if (!result.effect->findChild("image")) return "shader error: no 'uniform shader image'";
        d.brightPass = result.effect->findChild("bright") != nullptr;
        d.blurred = d.brightPass || result.effect->findChild("blurred") != nullptr;
        d.backdrop = result.effect->findChild("backdrop") != nullptr;
        d.below = result.effect->findChild("below") != nullptr;
        d.pointwise = Pointwise(sksl);
        d.timed = result.effect->findUniform("time") && Mentions(sksl, "time") > 1;
        d.shader = std::move(result.effect);
    }
    fDefinitions[id] = std::move(d);
    return "ok";
}

const EffectRuntime::Definition* EffectRuntime::find(const std::string& id) const {
    auto d = fDefinitions.find(id);
    return d == fDefinitions.end() ? nullptr : &d->second;
}

bool EffectRuntime::active(const Layer& layer) const {
    return std::any_of(layer.effects.begin(), layer.effects.end(), [&](const LayerEffect& e) {
        if (e.hidden) return false;
        const Definition* d = find(e.id);
        return BuiltIn(e.id) || (d && d->shader);
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
        const Definition* d = find(e.id);
        if (d && (d->timed || d->below)) return false;
        if (d && !std::all_of(d->defaults.begin(), d->defaults.end(), [&](const auto& k) { return still(k.second); })) return false;
        return std::all_of(e.props.begin(), e.props.end(), [&](const Property& p) { return still(p.anim); });
    });
}

sk_sp<SkImageFilter> EffectRuntime::chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkRect& seen,
                                          const SkRect& drawn, const SkMatrix& placed, sk_sp<SkImageFilter> content,
                                          sk_sp<SkImageFilter> below) const {
    const float t = layer.localTime(timeMs), seconds = (float)((layer.steppedMs(timeMs) - layer.startTime) / 1000.0);
    const SkRect bounds = seen.makeOffset(-area.left(), -area.top());
    const float reach = std::max({(float)scene.width, (float)scene.height, seen.width(), seen.height()});
    std::optional<SkRect> shape;
    const auto own = [&]() -> const SkRect& {
        if (!shape) shape = ShapePath(layer, t).getBounds();
        return *shape;
    };
    const auto fromLayer = [&] { return SkMatrix::Translate(-area.left(), -area.top()) * placed * SkMatrix::Translate(own().left(), own().top()); };
    const auto columns = [](const SkMatrix& m) { return std::vector<float>{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]}; };
    const bool withBackdrop = content != nullptr;
    sk_sp<SkImageFilter> f = std::move(content);
    bool any = false;
    for (size_t k = (size_t)(liftAt(layer) + 1); k < layer.effects.size(); k++) {
        const LayerEffect& e = layer.effects[k];
        if (e.hidden || e.id == kLift || (layer.video && e.id == "absent.datamosh")) continue;
        const Definition* d = find(e.id);
        const auto* defaults = d ? &d->defaults : nullptr;
        if (e.id == kPuppet) {
            f = PuppetFilter(e, defaults, t, timeMs, area, placed, bounds, f ? f->computeFastBounds(drawn) : drawn, reach, f);
            if (!f) continue;
        } else if (BuiltIn(e.id)) {
            const auto value = [&](const char* name, int i, float fallback) { return Value(e, defaults, name, i, t, fallback); };
            f = e.id == "absent.glow" ? GlowFilter(value, f, withBackdrop) : BuiltInFilter(e.id, value, f);
        } else if (d && d->shader) {
            SkRuntimeShaderBuilder b(d->shader);
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
                else if (automatic && name == "screen") v = {-area.left(), -area.top(), (float)scene.width, (float)scene.height};
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
                const float sigma = std::max(Value(e, defaults, "blur", 0, t, 0), 0.0f) / 3;
                sk_sp<SkImageFilter> source = f;
                if (d->brightPass) {
                    const float cut = Value(e, defaults, "threshold", 0, t, 0);
                    const float luma[5] = {0.2126f, 0.7152f, 0.0722f, 0.0f, -cut};
                    const float rows[20] = {luma[0], luma[1], luma[2], luma[3], luma[4], luma[0], luma[1], luma[2], luma[3], luma[4],
                                            luma[0], luma[1], luma[2], luma[3], luma[4], 0, 0, 0, 1, 0};
                    source = SkImageFilters::ColorFilter(SkColorFilters::Matrix(rows), f);
                }
                names.push_back(d->brightPass ? "bright" : "blurred");
                inputs.push_back(sigma > 0 ? SkImageFilters::Blur(sigma, sigma, SkTileMode::kDecal, source) : source);
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
                         const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below) const {
    const SkRect bounds = WorldView() ? canvas->getLocalClipBounds() : SkRect::MakeIWH(scene.width, scene.height);
    const int lift = liftAt(layer);
    const SkRect at = area.isEmpty() ? bounds : area;
    const SkRect local = bounds.makeOffset(-at.left(), -at.top());
    auto record = [&](const std::function<void(SkCanvas*)>& draw, SkRect* bounded = nullptr) {
        SkPictureRecorder recorder;
        SkRTreeFactory tight;
        SkCanvas* c = recorder.beginRecording(local, &tight);
        c->translate(-at.left(), -at.top());
        draw(c);
        sk_sp<SkPicture> picture = recorder.finishRecordingAsPicture();
        const SkRect drawn = picture->cullRect().makeOutset(2, 2);
        if (bounded) *bounded = drawn.makeOffset(at.left(), at.top());
        return SkImageFilters::Picture(std::move(picture), drawn);
    };
    const bool seesBehind = lift < 0 && behind(layer);
    SkRect drawn = SkRect::MakeEmpty();
    sk_sp<SkImageFilter> content = seesBehind ? record(source, &drawn) : nullptr;
    sk_sp<SkImageFilter> under = below && needs(layer, &Definition::below) ? record(below) : nullptr;
    SkRect inside = local;
    if (lift < 0 && !seesBehind && Bent(layer, layer.localTime(timeMs))) {
        SkRect measured;
        record(source, &measured);
        const float scale = canvas->getTotalMatrix().getMinScale(), pad = scale > 0 ? 4 / scale : 4;
        inside = measured.makeOffset(-at.left(), -at.top()).makeOutset(pad, pad);
    }
    sk_sp<SkImageFilter> filter = this->chain(scene, layer, timeMs, at, bounds, inside, placed, std::move(content), std::move(under));
    if (!filter && lift < 0) return false;
    if (seesBehind) {
        float reach = 0;
        bool glowOnly = true;
        const float t = layer.localTime(timeMs);
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
        canvas->saveLayer(&bounds, &paint);
        source(canvas);
        canvas->restore();
        return true;
    }
    const LayerEffect& e = layer.effects[lift];
    const Definition* d = find(e.id);
    const float own = std::clamp(Value(e, d ? &d->defaults : nullptr, "mix", 0, layer.localTime(timeMs), 0), 0.0f, 1.0f);
    const bool masked = Value(e, d ? &d->defaults : nullptr, "mask", 0, layer.localTime(timeMs), 0) > 0.5f;
    paint.setBlendMode(masked ? SkBlendMode::kSrcOver : SkBlendMode::kSrc);
    canvas->saveLayer(SkCanvas::SaveLayerRec(&bounds, &paint, SkCanvas::kInitWithPrevious_SaveLayerFlag));
    if (own > 0) {
        canvas->saveLayerAlphaf(&bounds, own);
        source(canvas);
        canvas->restore();
    }
    if (masked) {
        SkPaint mask;
        mask.setBlendMode(SkBlendMode::kDstIn);
        canvas->saveLayer(&bounds, &mask);
        source(canvas);
        canvas->restore();
    }
    canvas->restore();
    return true;
}

}
