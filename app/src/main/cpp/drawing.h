#pragma once

#include <string>

#include "scene.h"
#include "xml.h"

class SkCanvas;

namespace am {

void ParseStrokes(const XmlNode& drawing, Layer* layer);
void DrawDrawing(SkCanvas* canvas, const Layer& layer, float opacity, float t);
SkPath DrawingBounds(const Layer& layer);
SkPath DrawingOutline(const Layer& layer, float t);
SkPath DrawingProgressPath(const Layer& layer, const SkPath& path, float t);
bool DrawingContains(const Layer& layer, SkPoint local);

constexpr int kPointEdit = 1 << 28;
void DrawPointEdit(SkCanvas* canvas, const Layer& layer, const SkMatrix& toTarget, float px, int code, float t);

std::string OutlineSvg(const SkPath& path);

}
