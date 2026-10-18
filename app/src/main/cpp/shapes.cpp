#include "text.h"
#include <algorithm>
#include <cmath>
#include <initializer_list>

#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkRRect.h"
#include "scene.h"
#include "drawing.h"

namespace am {
namespace {

const float kPi = 3.14159265f;
const float kKappa = 0.5522848f;

struct Params {
    const Layer& layer;
    float t;

    float num(const char* name, float fallback, int i = 0) const {
        const float v = layer.value(name, i, t, fallback);
        return std::isfinite(v) ? v : fallback;
    }
    SkPoint point(const char* name, float x, float y) const {
        return {this->num(name, x, 0), this->num(name, y, 1)};
    }
    int count(const char* name, float fallback) const {
        return (int)std::clamp(roundf(this->num(name, fallback)), 3.0f, 360.0f);
    }
};

SkPoint OnEllipse(SkPoint centre, float rx, float ry, float degrees) {
    const float a = degrees * kPi / 180;
    return {centre.fX + rx * cosf(a), centre.fY - ry * sinf(a)};
}

SkPoint Polar(float radius, float degrees) { return OnEllipse({0, 0}, radius, radius, degrees); }

void ArcTo(SkPathBuilder& b, SkPoint centre, float rx, float ry, float from, float sweep) {
    const int n = std::max(1, (int)ceilf(fabsf(sweep) / 90));
    const float step = sweep / n;
    const float k = 4.0f / 3 * tanf(step * kPi / 720);
    for (int i = 0; i < n; i++) {
        const float d0 = from + step * i, d1 = d0 + step;
        const float a0 = d0 * kPi / 180, a1 = d1 * kPi / 180;
        const SkPoint p0 = OnEllipse(centre, rx, ry, d0), p3 = OnEllipse(centre, rx, ry, d1);
        b.cubicTo(p0.fX - k * rx * sinf(a0), p0.fY - k * ry * cosf(a0),
                  p3.fX + k * rx * sinf(a1), p3.fY + k * ry * cosf(a1), p3.fX, p3.fY);
    }
}

SkPath Polygon(std::initializer_list<SkPoint> points, bool closed = true) {
    SkPathBuilder b;
    for (const SkPoint& p : points) {
        if (b.isEmpty()) {
            b.moveTo(p);
        } else {
            b.lineTo(p);
        }
    }
    if (closed) b.close();
    return b.detach();
}

SkPath Points(const Params& p, std::initializer_list<SkPoint> defaults) {
    static const char* const kNames[] = {"p1", "p2", "p3", "p4", "p5"};
    SkPathBuilder b;
    int i = 0;
    for (const SkPoint& d : defaults) {
        const SkPoint pt = p.point(kNames[i], d.fX, d.fY);
        if (i++ == 0) {
            b.moveTo(pt);
        } else {
            b.lineTo(pt);
        }
    }
    if (p.num("closed", 1) != 0) b.close();
    return b.detach();
}

SkPath Corners(int corners, float radius0, float radius1, float offset) {
    SkPathBuilder b;
    b.moveTo(Polar(radius0, 90 + offset));
    for (int k = 1; k < corners; k++) b.lineTo(Polar(k % 2 ? radius1 : radius0, 90 + offset + k * 360.0f / corners));
    b.close();
    return b.detach();
}

SkPath Multifoil(int points, float outer, float inner, float offset) {
    const float half = 180.0f / points;
    const float handle = inner * kPi / points;
    SkPathBuilder b;
    b.moveTo(Polar(inner, 90 + offset - half));
    for (int k = 0; k < points; k++) {
        const float a = 90 + offset + k * 2 * half;
        const SkPoint tip = Polar(outer, a), before = Polar(inner, a - half), after = Polar(inner, a + half);
        const SkPoint along = Polar(handle, a + 90);
        b.cubicTo(before, tip - along, tip);
        b.cubicTo(tip + along, after, after);
    }
    b.close();
    return b.detach();
}

float ClockwiseSweep(float start, float end) {
    const float sweep = fmodf(start - end, 360);
    return sweep > 0 ? sweep : sweep + 360;
}

SkPath Moon(float radius, float offset) {
    const float cut = std::clamp(1 - offset / 500, -1.0f, 1.0f) * radius;
    SkPathBuilder b;
    b.moveTo(0, -radius);
    ArcTo(b, {0, 0}, radius, radius, 90, 180);
    ArcTo(b, {0, 0}, cut, radius, 270, -180);
    b.close();
    return b.detach();
}

SkPath Teardrop(float radius, float tail, float squeeze, float tipHalf) {
    const float s = std::max(squeeze, 0.01f);
    const float cx = radius / s, cy = -tail / s, tx = tipHalf;
    SkPathBuilder b;
    b.moveTo(radius, 0);
    b.cubicTo(radius, -kKappa * radius, cx, cy, tx, -tail);
    b.lineTo(-tx, -tail);
    b.cubicTo(-cx, cy, -radius, -kKappa * radius, -radius, 0);
    ArcTo(b, {0, 0}, radius, radius, 180, 180);
    b.close();
    return b.detach();
}

template <typename Edge>
SkPath RoundedBox(float w, float h, float radius, Edge&& edge) {
    const float r = std::clamp(radius, 0.0f, std::min(w, h));
    const SkPoint starts[] = {{-w + r, -h}, {w, -h + r}, {w - r, h}, {-w, h - r}};
    const SkPoint dirs[] = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    SkPathBuilder b;
    b.moveTo(starts[0]);
    for (int i = 0; i < 4; i++) {
        const SkPoint along = dirs[i], inward = {-along.fY, along.fX};
        const float length = 2 * ((i % 2 ? h : w) - r);
        edge(b, i, starts[i], along, length);
        const SkPoint end = starts[i] + along * length;
        b.lineTo(end);
        ArcTo(b, end + inward * r, r, r, 90 - 90 * i, -90);
    }
    b.close();
    return b.detach();
}

SkPath Callout(float w, float h, float radius, SkPoint tip, float tailWidth) {
    const float ax = fabsf(tip.fX), ay = fabsf(tip.fY);
    int side = -1;
    if (ax > w || ay > h) side = ay * w >= ax * h ? (tip.fY > 0 ? 2 : 0) : (tip.fX > 0 ? 1 : 3);
    return RoundedBox(w, h, radius, [&](SkPathBuilder& b, int i, SkPoint from, SkPoint along, float length) {
        if (i != side) return;
        const float base = std::clamp(tailWidth, 0.0f, length);
        const float start = (tip - (from + along * (length / 2))).dot(along) < 0 ? 0 : length - base;
        b.lineTo(from + along * start);
        b.lineTo(tip);
        b.lineTo(from + along * (start + base));
    });
}

SkPath Stamp(float w, float h, float radius, float size, float spacing) {
    return RoundedBox(w, h, radius, [&](SkPathBuilder& b, int, SkPoint from, SkPoint along, float length) {
        const float fit = size > 0 && size + spacing > 0 ? (length - spacing) / (size + spacing) : 0;
        const int count = fit >= 1 ? (int)std::min(fit, 512.0f) : 0;
        const float gap = (length - count * size) / (count + 1);
        const SkPoint inward = {-along.fY, along.fX}, handle = along * (kKappa * size / 2);
        for (int k = 0; k < count; k++) {
            const SkPoint in = from + along * (gap + k * (size + gap)), out = in + along * size;
            const SkPoint bottom = in + (along + inward) * (size / 2);
            b.lineTo(in);
            b.cubicTo(in, bottom - handle, bottom);
            b.cubicTo(bottom + handle, out, out);
        }
    });
}

SkPath Arrow(SkPoint start, SkPoint end, float half, float headHalf, float headLength) {
    SkPoint dir = end - start;
    const float length = dir.length();
    if (!dir.normalize()) return SkPath();
    const SkPoint n = {-dir.fY, dir.fX};
    if (headLength <= 0) return Polygon({start + n * half, end + n * half, end - n * half, start - n * half});
    const SkPoint neck = end - dir * std::min(headLength, length);
    return Polygon({start + n * half, neck + n * half, neck + n * headHalf, end,
                    neck - n * headHalf, neck - n * half, start - n * half});
}

}

SkPath ShapePath(const Layer& layer, float t) {
    if (layer.text) return TextPath(layer, t);
    if (layer.kind == "embedScene") return SkPath::Rect(SkRect::MakeXYWH(-layer.innerWidth / 2.0f, -layer.innerHeight / 2.0f, (float)layer.innerWidth, (float)layer.innerHeight));
    if (layer.kind == "drawing") return DrawingBounds(layer);
    if (layer.shape.empty()) return OutlineAt(layer, t);
    const Params p{layer, t};
    const std::string& s = layer.shape;
    const SkPoint size = p.point("size", s == ".stamp" ? 150 : 100, s == ".stamp" ? 150 : 100);
    const float w = fabsf(size.fX), h = fabsf(size.fY);
    const SkRect box = SkRect::MakeLTRB(-w, -h, w, h);
    const float offset = p.num("offsetAngle", 0);
    SkPathBuilder b;

    if (s == ".circle") return b.addOval(box).detach();
    if (s == ".roundrect") {
        const float r = p.num("cornerRadius", 25);
        return b.addRRect(SkRRect::MakeRectXY(box, r, r)).detach();
    }
    if (s == ".plus") {
        const float stem = std::clamp(p.num("stemSize", 50) / 2, 0.0f, std::min(w, h));
        return Polygon({{-stem, -h}, {stem, -h}, {stem, -stem}, {w, -stem}, {w, stem}, {stem, stem},
                        {stem, h}, {-stem, h}, {-stem, stem}, {-w, stem}, {-w, -stem}, {-stem, -stem}});
    }
    if (s == ".triangle") return Points(p, {{-100, 100}, {0, -100}, {100, 100}});
    if (s == ".quad") return Points(p, {{-100, -100}, {50, -100}, {100, 100}, {-100, 100}});
    if (s == ".penta") return Points(p, {{-100, -100}, {0, -100}, {0, 0}, {100, 100}, {-100, 100}});
    if (s == ".line") return Polygon({p.point("p1", -100, 100), p.point("p2", 100, -100)}, false);
    if (s == ".poly") {
        const float r = p.num("radius", 100);
        return Corners(p.count("sideCount", 6), r, r, offset);
    }
    if (s == ".star") {
        return Corners(2 * p.count("pointCount", 5), p.num("outerRadius", 100), p.num("innerRadius", 50), offset);
    }
    if (s == ".multifoil") {
        return Multifoil(p.count("pointCount", 5), p.num("outerRadius", 100), p.num("innerRadius", 50), offset);
    }
    if (s == ".arc" || s == ".pie") {
        const float r = p.num("radius", 100);
        const float start = p.num("startAngle", s == ".pie" ? 45 : 0), end = p.num("endAngle", 90);
        if (s == ".pie") {
            b.moveTo(0, 0).lineTo(Polar(r, start));
        } else {
            b.moveTo(Polar(r, start));
        }
        ArcTo(b, {0, 0}, r, r, start, -ClockwiseSweep(start, end));
        if (s == ".pie" || p.num("closed", 0) != 0) b.close();
        return b.detach();
    }
    if (s == ".moon") return Moon(p.num("radius", 100), p.num("offset", 250));
    if (s == ".teardrop") {
        return Teardrop(p.num("radius", 100), p.num("tail", 200), p.num("squeeze", 2.5f), p.num("tailWidth", 0));
    }
    if (s == ".callout.roundrect") {
        return Callout(w, h, p.num("cornerRadius", 25), p.point("tail", -50, 150), p.num("tailWidth", 40));
    }
    if (s == ".stamp") {
        return Stamp(w, h, p.num("cornerRadius", 5), p.num("perforationSize", 40), p.num("perforationSpacing", 20));
    }
    if (s == ".arrow" || s == ".wideline") {
        const bool arrow = s == ".arrow";
        return Arrow(p.point("start", 0, 0), p.point("end", 250, -250), p.num("lineWidth", 40),
                     p.num("headWidth", 120), arrow ? p.num("headLength", 130) : 0);
    }
    return b.addRect(box).detach();
}

SkPath StrokePath(const Layer& layer, const SkPath& path, float t) {
    const float width = layer.stroke.present && layer.stroke.enabled ? layer.stroke.size.get(0, t, 0) : 0;
    if (!(width > 0)) return SkPath();
    SkPaint stroke;
    stroke.setStyle(SkPaint::kStroke_Style);
    stroke.setStrokeWidth(width);
    stroke.setStrokeCap(SkPaint::kRound_Cap);
    stroke.setStrokeJoin(SkPaint::kRound_Join);
    return skpathutils::FillPathWithPaint(path, stroke);
}

}
