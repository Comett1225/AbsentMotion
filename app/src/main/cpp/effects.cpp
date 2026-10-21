#include "effects.h"

#include "xml.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkM44.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "include/effects/SkImageFilters.h"

#include <algorithm>
#include <cmath>
#include <optional>
#include <string_view>
#include <vector>

namespace am {
namespace {

const char* const kLift = "absent.backdrop";

bool BuiltIn(const std::string& id) {
    return id == "absent.blur" || id == "absent.glow" || id == "absent.smear" || id == kLift;
}

float Value(const LayerEffect& e, const std::map<std::string, Anim>* defaults, const std::string& name, int i, float t,
            float fallback) {
    if (defaults) {
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
            float h = float(image.eval(p + float2(0.0, float(j))).r) * n;
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
    float r = float(field.eval(p).r);
    float d = max(r * reach - shift, 0.0);
    float g = r > 0.995 ? 0.0 : soft > 0.0 ? smoothstep(grow + soft, grow - soft, d) : (d <= grow ? 1.0 : 0.0);
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

sk_sp<SkRuntimeEffect> Compiled(const char* sksl) {
    auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
    return result.effect;
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

sk_sp<SkImageFilter> EffectRuntime::chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkRect& seen,
                                          const SkMatrix& placed, sk_sp<SkImageFilter> content, sk_sp<SkImageFilter> below) const {
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
        if (BuiltIn(e.id)) {
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
            f = inputs.size() == 1 ? SkImageFilters::RuntimeShader(b, reach, "image", f)
                                   : SkImageFilters::RuntimeShader(b, reach, names.data(), inputs.data(), (int)inputs.size());
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
    auto record = [&](const std::function<void(SkCanvas*)>& draw) {
        SkPictureRecorder recorder;
        SkCanvas* c = recorder.beginRecording(local);
        c->translate(-at.left(), -at.top());
        draw(c);
        return SkImageFilters::Picture(recorder.finishRecordingAsPicture(), local);
    };
    const bool seesBehind = lift < 0 && behind(layer);
    sk_sp<SkImageFilter> content = seesBehind ? record(source) : nullptr;
    sk_sp<SkImageFilter> under = below && needs(layer, &Definition::below) ? record(below) : nullptr;
    sk_sp<SkImageFilter> filter = this->chain(scene, layer, timeMs, at, bounds, placed, std::move(content), std::move(under));
    if (!filter && lift < 0) return false;
    if (seesBehind) {
        SkPaint replace;
        replace.setBlendMode(SkBlendMode::kSrc);
        canvas->saveLayer(SkCanvas::SaveLayerRec(&bounds, &replace, filter.get(), 0));
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
