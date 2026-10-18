#include "effects.h"

#include "xml.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkPaint.h"
#include "include/effects/SkImageFilters.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace am {
namespace {

bool BuiltIn(const std::string& id) {
    return id == "absent.blur" || id == "absent.glow" || id == "absent.solid" || id == "absent.motionblur";
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

sk_sp<SkImageFilter> BuiltInFilter(const std::string& id, const std::function<float(const char*, int, float)>& v,
                                   sk_sp<SkImageFilter> input) {
    if (id == "absent.blur") {
        const float sigma = std::max(v("strength", 0, 10), 0.0f) / 3;
        return sigma > 0 ? SkImageFilters::Blur(sigma, sigma, SkTileMode::kDecal, input) : input;
    }
    if (id == "absent.glow") {
        const float sigma = std::max(v("strength", 0, 20), 0.0f) / 3, intensity = std::max(v("intensity", 0, 1), 0.0f);
        if (!(sigma > 0) || !(intensity > 0)) return input;
        return SkImageFilters::Arithmetic(0, intensity, 1, 0, true, input,
                                          SkImageFilters::Blur(sigma, sigma, SkTileMode::kDecal, input));
    }
    if (id == "absent.solid") {
        const float amount = std::clamp(v("amount", 0, 1), 0.0f, 1.0f) * std::clamp(v("color", 0, 1), 0.0f, 1.0f);
        if (!(amount > 0)) return input;
        const SkColor4f c = {v("color", 1, 1), v("color", 2, 1), v("color", 3, 1), amount};
        return SkImageFilters::ColorFilter(SkColorFilters::Blend(c, nullptr, SkBlendMode::kSrcATop), input);
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
        if (!BuiltIn(id)) return "shader error: no shader and no built-in called " + id;
    } else {
        auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
        if (!result.effect) {
            const std::string text = result.errorText.c_str();
            return "shader error: " + text.substr(0, text.find('\n')) + "\n" + text;
        }
        if (!result.effect->findChild("image")) return "shader error: no 'uniform shader image'";
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

sk_sp<SkImageFilter> EffectRuntime::chain(const Scene& scene, const Layer& layer, double timeMs) const {
    const float t = layer.localTime(timeMs), seconds = (float)((timeMs - layer.startTime) / 1000.0);
    const SkRect bounds = SkRect::MakeIWH(scene.width, scene.height);
    const float reach = (float)std::max(scene.width, scene.height);
    sk_sp<SkImageFilter> f;
    bool any = false;
    for (const LayerEffect& e : layer.effects) {
        if (e.hidden) continue;
        const Definition* d = find(e.id);
        const auto* defaults = d ? &d->defaults : nullptr;
        if (BuiltIn(e.id)) {
            f = BuiltInFilter(e.id, [&](const char* name, int i, float fallback) {
                return Value(e, defaults, name, i, t, fallback);
            }, f);
        } else if (d && d->shader) {
            SkRuntimeShaderBuilder b(d->shader);
            for (const SkRuntimeEffect::Uniform& u : d->shader->uniforms()) {
                const std::string name(u.name);
                const int n = (int)(u.sizeInBytes() / 4);
                std::vector<float> v(n);
                if (name == "size") v = {(float)scene.width, (float)scene.height};
                else if (name == "time") v = {seconds};
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
            f = SkImageFilters::RuntimeShader(b, reach, "image", f);
        } else {
            continue;
        }
        f = SkImageFilters::Crop(bounds, f);
        any = true;
    }
    return any ? f : nullptr;
}

bool EffectRuntime::draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs,
                         const std::function<void(SkCanvas*)>& source) const {
    sk_sp<SkImageFilter> filter = this->chain(scene, layer, timeMs);
    if (!filter) return false;
    SkPaint paint;
    paint.setImageFilter(std::move(filter));
    const SkRect bounds = SkRect::MakeIWH(scene.width, scene.height);
    canvas->saveLayer(&bounds, &paint);
    source(canvas);
    canvas->restore();
    return true;
}

}
