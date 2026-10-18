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
    bool draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs,
              const std::function<void(SkCanvas*)>& source) const;

private:
    struct Definition {
        sk_sp<SkRuntimeEffect> shader;
        std::map<std::string, Anim> defaults;
    };
    const Definition* find(const std::string& id) const;
    sk_sp<SkImageFilter> chain(const Scene& scene, const Layer& layer, double timeMs) const;
    std::map<std::string, Definition> fDefinitions;
};

}
