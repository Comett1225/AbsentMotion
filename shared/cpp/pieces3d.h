#pragma once

#include "include/core/SkM44.h"
#include "include/core/SkRect.h"
#include <functional>
#include <string>
#include <vector>

class SkCanvas;
class SkImage;

namespace am { struct LightSet; }
namespace am::depth { struct Pass; }

namespace am::pieces3d {

using Setting = std::function<float(const char*, int, float)>;

bool IsEffect(const std::string& id);
void Draw(SkCanvas* canvas, const std::string& id, const SkRect& area, const SkM44& orientation, const Setting& value,
          const std::vector<unsigned char>& pixels, int width, int height, SkImage* image, const LightSet& lights, SkV3 origin,
          depth::Pass* pass = nullptr);

}
