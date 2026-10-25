#include "preview.h"
#include "drawing.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/core/SkPathBuilder.h"
#include <algorithm>
#include <cmath>
namespace am {
static bool PreviewQuad(const Scene& scene, int64_t id, double time, float xy[8]) {
    const Layer* layer=scene.layer(id);
    if(!layer || !layer->activeAt(time))return false;
    SkPoint q[4];SelectionBounds(*layer,time,SkMatrix::I()).toQuad(q);
    WorldMatrix(scene,*layer,time).mapPoints(q);
    for(int i=0;i<4;i++){xy[2*i]=q[i].fX;xy[2*i+1]=q[i].fY;}
    return true;
}
static void DrawSelection(SkCanvas* canvas, const SkPoint q[4], int handles, float px) {
    const SkPath outline = SkPath::Polygon({q, 4}, true);
    const SkPoint centre = (q[0] + q[2]) * 0.5f;
    SkPaint paint;
    paint.setAntiAlias(true);
    auto stroke = [&](SkColor color, float width) {
        paint.setStyle(SkPaint::kStroke_Style);
        paint.setStrokeWidth(width * px);
        paint.setColor(color);
        canvas->drawPath(outline, paint);
        paint.setStyle(SkPaint::kFill_Style);
    };
    auto dot = [&](SkPoint at, SkColor color, float radius) {
        paint.setColor(color);
        canvas->drawCircle(at, radius * px, paint);
    };
    auto handle = [&](int i) { return i < 4 ? q[i] : (q[i - 4] + q[(i - 3) % 4]) * 0.5f; };
    if (handles >= 0 && handles < 8) {
        const SkColor accent = SkColorSetRGB(0xff, 0x8f, 0x66);
        dot(handle(handles), SkColorSetA(accent, 0x40), 42.5f);
        dot(handle(handles), SK_ColorBLACK, 18.5f);
        dot(handle(handles), accent, 15.5f);
    } else {
        stroke(SK_ColorBLACK, 7.3f);
        stroke(SK_ColorWHITE, 5.3f);
        paint.setStyle(SkPaint::kStroke_Style);
        paint.setStrokeWidth(1.1f * px);
        dot(centre, SkColorSetRGB(0x80, 0x80, 0x80), 3.4f);
        paint.setStyle(SkPaint::kFill_Style);
        for (int i = 0; i < 8 && handles == 8; i++) {
            dot(handle(i), SK_ColorBLACK, 15.5f);
            dot(handle(i), SK_ColorWHITE, 12.5f);
        }
    }
}

static long FrameOf(double ms, int fps) { return ((std::lround(ms) + 1) * fps + 999) / 1000 - 1; }

static void DrawMotionPath(SkCanvas* canvas, const std::vector<Layer>& siblings, const Layer& layer, double timeMs, int fps,
                           const SkMatrix& toTarget, float px) {
    SkPaint paint;
    paint.setAntiAlias(true);
    const float t = layer.localTime(timeMs);
    auto place = [&](double ms, SkPoint own) {
        return toTarget.mapPoint(layer.parent != 0 ? EffectivePlacement(siblings, layer, ms).location : own);
    };
    std::vector<SkPoint> points;
    int here = -1;
    for (const Keyframe& k : layer.location.keys) {
        if (k.v.size() < 2) continue;
        const double keyMs = layer.startTime + (double)k.t * (layer.endTime - layer.startTime);
        if (FrameOf(keyMs, fps) == FrameOf(timeMs, fps)) here = (int)points.size();
        points.push_back(place(keyMs, {k.v[0], k.v[1]}));
    }
    if (points.empty()) points.push_back(place(timeMs, {layer.location.get(0, t, 0), layer.location.get(1, t, 0)}));
    paint.setStyle(SkPaint::kStroke_Style);
    paint.setStrokeWidth(3 * px);
    canvas->drawPath(SkPath::Polygon({points.data(), points.size()}, false), paint);
    paint.setStyle(SkPaint::kFill_Style);
    for (int i = 0; i < (int)points.size(); i++) {
        if (i == here) continue;
        canvas->drawCircle(points[i], 5.5f * px, paint);
    }
    if (here >= 0) {
        paint.setColor(SkColorSetARGB(0x80, 0xff, 0xff, 0xff));
        canvas->drawCircle(points[here], 9.5f * px, paint);
        paint.setColor(SkColorSetRGB(0xff, 0x8f, 0x66));
        canvas->drawCircle(points[here], 8 * px, paint);
    }
}

static void DrawGrid(SkCanvas* canvas, const SkISize& size, const Scene& scene, float scale, float dx, float dy, float spacing) {
    const float gap = spacing * scale;
    const float fade = std::clamp((gap - 6) / 18, 0.0f, 1.0f);
    SkRect view = SkRect::MakeIWH(size.width(), size.height());
    if (!(fade > 0) || !view.intersect(SkRect::MakeXYWH(dx, dy, scene.width * scale, scene.height * scale))) return;
    SkPaint paint;
    paint.setStyle(SkPaint::kStroke_Style);
    paint.setStrokeWidth(0);
    paint.setColor(SkColorSetARGB((U8CPU)lroundf(120 * fade), 0x80, 0x80, 0x80));
    auto crisp = [](float v) { return floorf(v) + 0.5f; };
    for (float k = ceilf((view.left() - dx) / gap); dx + k * gap <= view.right(); k++) {
        const float x = crisp(dx + k * gap);
        canvas->drawLine(x, view.top(), x, view.bottom(), paint);
    }
    for (float k = ceilf((view.top() - dy) / gap); dy + k * gap <= view.bottom(); k++) {
        const float y = crisp(dy + k * gap);
        canvas->drawLine(view.left(), y, view.right(), y, paint);
    }
}

static void DrawCameraFrames(SkCanvas* canvas, const Scene& scene, double timeMs, bool seenThrough, int64_t selectedId,
                             const SkMatrix& toTarget, float px) {
    const Layer* active = ActiveCamera(scene.layers, timeMs);
    const SkColor line = SkColorSetARGB(0xdc, 0x3a, 0x3a, 0x3a);
    SkPaint paint;
    paint.setAntiAlias(true);
    auto stroke = [&](const SkPath& path, SkColor color, float width) {
        paint.setStyle(SkPaint::kStroke_Style);
        paint.setStrokeWidth(width * px);
        paint.setColor(color);
        canvas->drawPath(path, paint);
    };
    for (const Layer& l : scene.layers) {
        if (l.kind != "camera" || !l.activeAt(timeMs)) continue;
        const bool selected = l.id == selectedId;
        SkPoint q[4];
        if (!(seenThrough && &l == active) && CameraFrame(scene.layers, l, scene.width, scene.height, timeMs, q)) {
            toTarget.mapPoints(q);
            const SkPath box = SkPath::Polygon({q, 4}, true);
            if (selected) {
                stroke(box, SK_ColorBLACK, 7.3f);
                stroke(box, SK_ColorWHITE, 5.3f);
            } else {
                stroke(box, line, 1.5f);
            }
            if (!l.camera.orthographic) {
                SkPathBuilder cross;
                cross.moveTo(q[0]).lineTo(q[2]).moveTo(q[1]).lineTo(q[3]);
                stroke(cross.detach(), line, 1.5f);
            }
        }
        if (selected) {
            const SkPoint at = toTarget.mapPoint(EffectivePlacement(scene.layers, l, timeMs).location);
            paint.setStyle(SkPaint::kFill_Style);
            paint.setColor(SK_ColorWHITE);
            canvas->drawCircle(at, 5.5f * px, paint);
            paint.setColor(line);
            canvas->drawCircle(at, 3.6f * px, paint);
        }
    }
}

static void DrawWorld(SkCanvas* canvas, const Scene& scene, double timeMs, const MediaPictures& pictures, const LayerEffects* effects) {
    canvas->drawColor(scene.bgColor);
    SetLayerEffects(effects, &scene, true);
    DrawLayerList(canvas, scene, scene.layers, timeMs, pictures);
    SetLayerEffects(nullptr, nullptr);
}

static void DrawSnapGuides(SkCanvas* canvas, const SkISize& size, const std::vector<SnapGuide>& guides, float scale, float dx, float dy, float px) {
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setColor(SkColorSetRGB(0xff, 0x3b, 0x30));
    for (const SnapGuide& g : guides) {
        paint.setStrokeWidth((g.wide ? 3.0f : 1.5f) * px);
        if (g.vertical) {
            const float x = dx + g.at * scale;
            canvas->drawLine(x, 0, x, (float)size.height(), paint);
        } else {
            const float y = dy + g.at * scale;
            canvas->drawLine(0, y, (float)size.width(), y, paint);
        }
    }
}

void RenderPreview(SkCanvas* canvas, int width, int height, const Scene& scene, double timeMs,
    float scale, float dx, float dy, uint32_t background, int64_t selectedId, int handles,
    const PreviewOptions& view, bool camera, const MediaPictures& pictures, const LayerEffects& effects) {
    SetCameraView(camera);
    Scene soloed;
    const Scene* shown = &scene;
    if (view.solo != 0 && !view.ids.empty()) {
        soloed = scene;
        for (Layer& l : soloed.layers) {
            if (l.kind == "camera") continue;
            const bool picked = std::find(view.ids.begin(), view.ids.end(), l.id) != view.ids.end();
            if (view.solo == 1 && !picked) l.hidden = true;
            if (view.solo == 2 && picked) l.opacity = Anim{{0.5f}};
        }
        shown = &soloed;
    }

        canvas->clear(background);
        const float px = view.refPx > 0 ? view.refPx : width / 1080.0f;
        const bool world = ActiveCamera(scene.layers, timeMs) != nullptr;
        auto drawScene = [&](SkCanvas* target, const LayerEffects* fx) {
            if (world) DrawWorld(target, *shown, timeMs, pictures, fx);
            else DrawScene(target, *shown, timeMs, pictures, fx);
        };
        if ((scene.bgColor >> 24) != 0xff) {
            const SkRect area = world ? SkRect::MakeIWH(width, height) : SkRect::MakeXYWH(dx, dy, scene.width * scale, scene.height * scale);
            const float cell = 25.0f * px;
            SkPaint dark;
            dark.setColor(SkColorSetRGB(0xcc, 0xcc, 0xcc));
            canvas->save();
            canvas->clipRect(area, true);
            canvas->drawColor(SkColorSetRGB(0xe5, 0xe5, 0xe5));
            const SkRect seen = canvas->getLocalClipBounds();
            const int firstRow = std::max(0, (int)floorf((area.bottom() - seen.bottom()) / cell));
            const int firstCol = std::max(0, (int)floorf((seen.left() - area.left()) / cell));
            for (int row = firstRow; row * cell < area.bottom() - seen.top(); row++) {
                for (int col = firstCol + ((firstCol + row + 1) & 1); col * cell < seen.right() - area.left(); col += 2) {
                    canvas->drawRect(SkRect::MakeXYWH(area.left() + col * cell, area.bottom() - (row + 1) * cell, cell, cell), dark);
                }
            }
            canvas->restore();
        }
        const LayerEffects& fx = effects;
        const int divisor = std::max(view.pixelDivisor, 1);
        const int bufferW = (int)lroundf(width / scale / divisor), bufferH = (int)lroundf(height / scale / divisor);
        sk_sp<SkSurface> buffer;
        if (view.pixels && bufferW > 0 && bufferH > 0 && (bufferW < width || bufferH < height)) {
            buffer = canvas->makeSurface(canvas->imageInfo().makeWH(bufferW, bufferH));
        }
        if (buffer) {
            const float kx = (float)bufferW / width, ky = (float)bufferH / height;
            SkCanvas* small = buffer->getCanvas();
            small->clear(SK_ColorTRANSPARENT);
            small->translate(roundf(dx * kx), roundf(dy * ky));
            small->scale(scale * kx, scale * ky);
            drawScene(small, &fx);
            canvas->drawImageRect(buffer->makeImageSnapshot(), SkRect::MakeWH((float)width, (float)height),
                                  SkSamplingOptions(SkFilterMode::kNearest));
        } else {
            canvas->save();
            canvas->translate(dx, dy);
            canvas->scale(scale, scale);
            drawScene(canvas, &fx);
            canvas->restore();
        }
        if (view.gridSpacing > 0) DrawGrid(canvas, canvas->getBaseLayerSize(), scene, scale, dx, dy, view.gridSpacing);
        DrawCameraFrames(canvas, scene, timeMs, camera, selectedId, SkMatrix::Translate(dx, dy).preScale(scale, scale), px);
        float xy[8];
        const Layer* selected = selectedId != 0 ? scene.layer(selectedId) : nullptr;
        if (selected && selected->kind == "camera") selected = nullptr;
        if (selected && handles == -2) {
            if (selected->activeAt(timeMs)) {
                DrawMotionPath(canvas, scene.layers, *selected, timeMs, scene.fps, SkMatrix::Translate(dx, dy).preScale(scale, scale), px);
            }
        } else if (selected && handles >= kPointEdit) {
            if (selected->activeAt(timeMs)) {
                const SkMatrix toTarget = SkMatrix::Translate(dx, dy).preScale(scale, scale).preConcat(WorldMatrix(scene, *selected, timeMs));
                DrawPointEdit(canvas, *selected, toTarget, px, handles, selected->localTime(timeMs));
            }
        } else if (selected && PreviewQuad(scene, selectedId, timeMs, xy)) {
            SkPoint q[4];
            for (int i = 0; i < 4; i++) q[i] = {xy[2 * i] * scale + dx, xy[2 * i + 1] * scale + dy};
            DrawSelection(canvas, q, handles, px);
        }
        DrawSnapGuides(canvas, canvas->getBaseLayerSize(), view.guides, scale, dx, dy, px);
}
}
