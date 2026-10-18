#pragma once
#include "scene.h"
#include "xml.h"
#include "include/core/SkPaint.h"

namespace am {
std::shared_ptr<TextLayout> ParseText(const XmlNode& node);
SkPath TextPath(const Layer& layer, float t);
SkPath TextGlyphPath(const Layer& layer);
void DrawText(SkCanvas* canvas, const Layer& layer, float t, const SkPaint& paint);
}
