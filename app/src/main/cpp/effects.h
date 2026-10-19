#pragma once

#include "scene.h"
#include "include/effects/SkRuntimeEffect.h"
#include <functional>
#include <map>
#include <string>

class SkImageFilter;

namespace am {

class EffectRuntime {
public:
    std::string load(const std::string& xml);
    bool active(const Layer& layer) const;
    bool lifted(const Layer& layer) const;
    bool draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs, const SkRect& area,
              const std::function<void(SkCanvas*)>& source) const;

private:
    struct Definition {
        sk_sp<SkRuntimeEffect> shader;
        bool blurred = false;
        bool brightPass = false;
        std::map<std::string, Anim> defaults;
    };
    const Definition* find(const std::string& id) const;
    int liftAt(const Layer& layer) const;
    sk_sp<SkImageFilter> chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area) const;
    std::map<std::string, Definition> fDefinitions;
};

}
