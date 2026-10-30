#pragma once

#include "scene.h"
#include "include/effects/SkRuntimeEffect.h"
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

class SkImage;
class SkImageFilter;

namespace am {

struct Palette;

using Grab = std::function<bool(int, int, const std::function<void(SkCanvas*)>&, std::vector<uint8_t>*)>;
using Upload = std::function<sk_sp<SkImage>(const sk_sp<SkImage>&)>;

class EffectRuntime {
public:
    std::string load(const std::string& xml);
    bool active(const Layer& layer) const;
    bool lifted(const Layer& layer) const;
    bool spills(const Layer& layer) const;
    bool behind(const Layer& layer) const;
    bool steady(const Layer& layer, float from, float to) const;
    bool grabs(const std::vector<Layer>& layers) const;
    bool draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkMatrix& placed,
              const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below, const Grab& grab = nullptr,
              const Upload& upload = nullptr) const;

private:
    struct Definition {
        sk_sp<SkRuntimeEffect> shader;
        bool blurred = false;
        bool brightPass = false;
        bool backdrop = false;
        bool below = false;
        bool pointwise = false;
        bool timed = false;
        bool palette = false;
        bool matrix = false;
        std::map<std::string, Anim> defaults;
        std::map<std::string, std::string> texts;
    };
    const Definition* find(const std::string& id) const;
    int liftAt(const Layer& layer) const;
    bool needs(const Layer& layer, bool Definition::*input) const;
    int method(const LayerEffect& e, const Definition* d, float t) const;
    std::shared_ptr<const Palette> palette(const LayerEffect& e, const Definition* d, float t) const;
    int diffusing(const Layer& layer, int lift, float t) const;
    bool mayDiffuse(const LayerEffect& e, const Definition* d) const;
    sk_sp<SkImageFilter> chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkRect& seen,
                               const SkRect& drawn, const SkMatrix& placed, sk_sp<SkImageFilter> content, sk_sp<SkImageFilter> below,
                               int before = -1, int from = -1) const;
    std::map<std::string, Definition> fDefinitions;
};

}
