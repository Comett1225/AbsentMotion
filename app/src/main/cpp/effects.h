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
    bool behind(const Layer& layer) const;
    bool draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkMatrix& placed,
              const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below) const;

private:
    struct Definition {
        sk_sp<SkRuntimeEffect> shader;
        bool blurred = false;
        bool brightPass = false;
        bool backdrop = false;
        bool below = false;
        std::map<std::string, Anim> defaults;
    };
    const Definition* find(const std::string& id) const;
    int liftAt(const Layer& layer) const;
    bool needs(const Layer& layer, bool Definition::*input) const;
    sk_sp<SkImageFilter> chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkMatrix& placed,
                               sk_sp<SkImageFilter> content, sk_sp<SkImageFilter> below) const;
    std::map<std::string, Definition> fDefinitions;
};

}
