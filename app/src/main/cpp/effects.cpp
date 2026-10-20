#include "effects.h"

#include "xml.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "include/effects/SkImageFilters.h"

#include <algorithm>
#include <cmath>
#include <string_view>
#include <vector>

namespace am {
namespace {

const char* const kLift = "absent.backdrop";

bool BuiltIn(const std::string& id) {
    return id == "absent.blur" || id == "absent.glow" || id == "absent.motionblur" || id == kLift;
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
        const float sigma = std::max(v("strength", 0, 20), 0.0f) / 3;
        const float intensity = std::max(v("intensity", 0, 1), 0.0f) * std::min(sigma / 3, 1.0f);
        if (!(sigma > 0) || !(intensity > 0)) return input;
        return SkImageFilters::Arithmetic(0, intensity, 1, 0, true, input,
                                          SkImageFilters::Blur(sigma, sigma, SkTileMode::kDecal, input));
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
    } else {
        auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
        if (!result.effect) {
            const std::string text = result.errorText.c_str();
            return "shader error: " + text.substr(0, text.find('\n')) + "\n" + text;
        }
        if (!result.effect->findChild("image")) return "shader error: no 'uniform shader image'";
        d.blurred = result.effect->findChild("blurred") != nullptr;
        d.brightPass = d.blurred && result.effect->findUniform("threshold") != nullptr;
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
        return d && d->shader && d->*input;
    });
}

bool EffectRuntime::behind(const Layer& layer) const { return needs(layer, &Definition::backdrop); }

sk_sp<SkImageFilter> EffectRuntime::chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area,
                                          sk_sp<SkImageFilter> content, sk_sp<SkImageFilter> below) const {
    const float t = layer.localTime(timeMs), seconds = (float)((layer.steppedMs(timeMs) - layer.startTime) / 1000.0);
    const SkRect bounds = SkRect::MakeIWH(scene.width, scene.height).makeOffset(-area.left(), -area.top());
    const float reach = (float)std::max(scene.width, scene.height);
    sk_sp<SkImageFilter> f = std::move(content);
    bool any = false;
    for (size_t k = (size_t)(liftAt(layer) + 1); k < layer.effects.size(); k++) {
        const LayerEffect& e = layer.effects[k];
        if (e.hidden || e.id == kLift || (layer.video && e.id == "absent.datamosh")) continue;
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
                const bool automatic = !(defaults && defaults->count(name));
                if (automatic && name == "size") v = {area.width(), area.height()};
                else if (automatic && name == "time") v = {seconds};
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
                names.push_back("blurred");
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

bool EffectRuntime::draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs, const SkRect& area,
                         const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below) const {
    const SkRect bounds = SkRect::MakeIWH(scene.width, scene.height);
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
    sk_sp<SkImageFilter> filter = this->chain(scene, layer, timeMs, at, std::move(content), std::move(under));
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
    paint.setBlendMode(SkBlendMode::kSrc);
    canvas->saveLayer(SkCanvas::SaveLayerRec(&bounds, &paint, SkCanvas::kInitWithPrevious_SaveLayerFlag));
    if (own > 0) {
        canvas->saveLayerAlphaf(&bounds, own);
        source(canvas);
        canvas->restore();
    }
    canvas->restore();
    return true;
}

}
