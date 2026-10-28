#pragma once
#include "scene.h"
#include "xml.h"
#include "include/core/SkPaint.h"

namespace am {
enum class TextPart { kAll, kPlain, kColor };
std::shared_ptr<TextLayout> ParseText(const XmlNode& node);
SkPath TextPath(const Layer& layer, float t);
SkPath TextHitPath(const Layer& layer, float t);
SkPath TextGlyphPath(const Layer& layer, float t = 0, int fps = 30);
void DrawText(SkCanvas* canvas, const Layer& layer, float t, const SkPaint& paint, int fps = 30, TextPart part = TextPart::kAll);
bool TextAnimated(const Layer& layer);
SkRect TextInkBounds(const Layer& layer);
}
