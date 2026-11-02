#pragma once
#include "scene.h"
#include "engine.h"
namespace am {
void RenderPreview(SkCanvas* canvas, int width, int height, const Scene& scene, double timeMs,
    float scale, float dx, float dy, uint32_t background, int64_t selectedId, int handles,
    const PreviewOptions& view, bool camera, const MediaPictures& pictures, const LayerEffects& effects);
}
