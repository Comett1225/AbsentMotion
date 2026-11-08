#include "text.h"
#include <algorithm>
#include <cmath>
#include <deque>
#include <initializer_list>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkRRect.h"
#include "include/pathops/SkPathOps.h"
#include "include/utils/SkParsePath.h"
#include "scene.h"
#include "drawing.h"

namespace am {
namespace {

const float kPi = 3.14159265f;
const float kKappa = 0.5522848f;
const float kSmooth = 0.99939f;
const float kMaxSize = 100000;

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
    int count(const char* name, float fallback, int lo, int hi) const {
        return (int)std::clamp(roundf(this->num(name, fallback)), (float)lo, (float)hi);
    }
    float part(const char* name, float fallback, float lo, float hi) const {
        return std::clamp(this->num(name, fallback), lo, hi) / 100;
    }
    bool on(const char* name, bool fallback) const { return this->num(name, fallback ? 1.0f : 0.0f) > 0.5f; }
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

struct Bend {
    SkPoint p0, c1, c2, p3;
    bool line;

    static Bend Straight(SkPoint a, SkPoint b) { return {a, a + (b - a) * (1.0f / 3), a + (b - a) * (2.0f / 3), b, true}; }

    SkPoint at(float t) const {
        const float u = 1 - t;
        return p0 * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + p3 * (t * t * t);
    }
    SkPoint direction(float t) const {
        const float u = 1 - t;
        SkPoint d = line ? p3 - p0 : (c1 - p0) * (u * u) + (c2 - c1) * (2 * u * t) + (p3 - c2) * (t * t);
        if (d.normalize()) return d;
        for (SkPoint v : {t < 0.5f ? c2 - p0 : p3 - c1, p3 - p0}) {
            if (v.normalize()) return v;
        }
        return {0, 0};
    }
    float length() const {
        if (line) return SkPoint::Distance(p0, p3);
        float sum = 0;
        SkPoint last = p0;
        for (int i = 1; i <= 16; i++) {
            const SkPoint next = this->at(i / 16.0f);
            sum += SkPoint::Distance(last, next);
            last = next;
        }
        return sum;
    }
    float at(float distance, bool fromEnd) const {
        const float total = this->length();
        if (!(total > 0)) return fromEnd ? 1 : 0;
        const float want = std::clamp(fromEnd ? total - distance : distance, 0.0f, total);
        if (line) return want / total;
        float sum = 0;
        SkPoint last = p0;
        for (int i = 1; i <= 16; i++) {
            const SkPoint next = this->at(i / 16.0f);
            const float step = SkPoint::Distance(last, next);
            if (sum + step >= want) return (i - 1 + (step > 0 ? (want - sum) / step : 0)) / 16;
            sum += step;
            last = next;
        }
        return 1;
    }
    Bend part(float t0, float t1) const {
        if (line) return Straight(p0 + (p3 - p0) * t0, p0 + (p3 - p0) * t1);
        auto split = [](const Bend& b, float t, bool left) {
            const SkPoint a = b.p0 + (b.c1 - b.p0) * t, m = b.c1 + (b.c2 - b.c1) * t, c = b.c2 + (b.p3 - b.c2) * t;
            const SkPoint d = a + (m - a) * t, e = m + (c - m) * t, f = d + (e - d) * t;
            return left ? Bend{b.p0, a, d, f, false} : Bend{f, e, c, b.p3, false};
        };
        const Bend right = t0 > 0 ? split(*this, t0, false) : *this;
        return t1 < 1 ? split(right, t0 < 1 ? (t1 - t0) / (1 - t0) : 0, true) : right;
    }
};

void RoundContour(std::vector<Bend> bends, bool closed, float radius, SkPathBuilder* out) {
    bends.erase(std::remove_if(bends.begin(), bends.end(), [](const Bend& b) { return b.length() < 1e-4f; }), bends.end());
    const int n = (int)bends.size();
    if (n == 0) return;
    std::vector<float> lengths(n), cut(n, 0), from(n, 0), to(n, 1);
    for (int i = 0; i < n; i++) lengths[i] = bends[i].length();
    for (int i = closed ? 0 : 1; i < n; i++) {
        const int before = (i + n - 1) % n;
        if (before == i || bends[before].direction(1).dot(bends[i].direction(0)) > kSmooth) continue;
        cut[i] = std::min({radius, lengths[before] / 2, lengths[i] / 2});
    }
    std::vector<Bend> kept(n);
    for (int i = 0; i < n; i++) {
        const float end = closed || i + 1 < n ? cut[(i + 1) % n] : 0;
        if (cut[i] > 0) from[i] = bends[i].at(cut[i], false);
        if (end > 0) to[i] = bends[i].at(end, true);
        kept[i] = bends[i].part(from[i], std::max(to[i], from[i]));
    }
    auto corner = [&](int i) {
        const int before = (i + n - 1) % n;
        const float handle = cut[i] * 2 / 3;
        out->cubicTo(kept[before].p3 + bends[before].direction(to[before]) * handle, kept[i].p0 - bends[i].direction(from[i]) * handle, kept[i].p0);
    };
    out->moveTo(kept[0].p0);
    for (int i = 0; i < n; i++) {
        if (i > 0 && cut[i] > 0) corner(i);
        if (kept[i].line) {
            out->lineTo(kept[i].p3);
        } else {
            out->cubicTo(kept[i].c1, kept[i].c2, kept[i].p3);
        }
    }
    if (!closed) return;
    if (cut[0] > 0) corner(0);
    out->close();
}

SkPath RoundCorners(const SkPath& path, float radius) {
    if (!(radius > 0) || path.isEmpty()) return path;
    radius = std::min(radius, kMaxSize);
    SkPathBuilder out(path.getFillType());
    std::vector<Bend> bends;
    SkPoint start{}, pen{};
    auto finish = [&](bool closed) {
        if (closed && SkPoint::Distance(pen, start) > 1e-4f) bends.push_back(Bend::Straight(pen, start));
        if (!bends.empty()) RoundContour(std::move(bends), closed, radius, &out);
        bends.clear();
    };
    for (const Piece& s : Pieces(path)) {
        switch (s.verb) {
            case SkPathVerb::kMove: finish(false); start = pen = s.p; break;
            case SkPathVerb::kCubic: bends.push_back({pen, s.c1, s.c2, s.p, false}); pen = s.p; break;
            case SkPathVerb::kClose: finish(true); pen = start; break;
            default: bends.push_back(Bend::Straight(pen, s.p)); pen = s.p; break;
        }
    }
    finish(false);
    return out.detach();
}

SkPath Oval(float cx, float cy, float rx, float ry) { return SkPath::Oval(SkRect::MakeLTRB(cx - rx, cy - ry, cx + rx, cy + ry)); }

SkPath Box(float left, float top, float right, float bottom) { return SkPath::Rect(SkRect::MakeLTRB(left, top, right, bottom)); }

SkPath Rounded(float left, float top, float right, float bottom, float radius) {
    return SkPath::RRect(SkRRect::MakeRectXY(SkRect::MakeLTRB(left, top, right, bottom), radius, radius));
}

SkPath Points(const std::vector<SkPoint>& points) { return SkPath::Polygon(points, true); }

SkPath Combine(const SkPath& a, const SkPath& b, SkPathOp op = kUnion_SkPathOp) {
    auto out = Op(a, b, op);
    return out ? *out : a;
}

SkPath Unite(const std::vector<SkPath>& parts) {
    SkOpBuilder builder;
    for (const SkPath& part : parts) builder.add(part, kUnion_SkPathOp);
    auto out = builder.resolve();
    return out ? *out : SkPath();
}

SkPath Join(const std::vector<SkPath>& parts) {
    SkPathBuilder b;
    for (const SkPath& part : parts) b.addPath(part);
    return b.detach();
}

SkPath Svg(const char* d) {
    auto path = SkParsePath::FromSVGString(d);
    return path ? *path : SkPath();
}

SkPath Fitted(const SkPath& design, float w, float h) {
    const SkRect from = design.computeTightBounds();
    if (from.isEmpty()) return SkPath();
    SkMatrix m = SkMatrix::Translate(-from.centerX(), -from.centerY());
    m.postScale(2 * w / from.width(), 2 * h / from.height());
    return design.makeTransform(m);
}

SkPath Mapped(const SkPath& design, SkRect from, float w, float h) {
    SkMatrix m = SkMatrix::Translate(-from.centerX(), -from.centerY());
    m.postScale(2 * w / from.width(), 2 * h / from.height());
    return design.makeTransform(m);
}

SkPath Regular(int corners, float outer, float inner, float start) {
    SkPathBuilder b;
    b.moveTo(Polar(outer, start));
    for (int k = 1; k < corners; k++) b.lineTo(Polar(k % 2 ? inner : outer, start + k * 360.0f / corners));
    b.close();
    return b.detach();
}

SkPath Heart(const Params& p, float w, float h) {
    const float notch = p.part("notch", 37, 0, 90), point = p.part("point", 50, 0, 100);
    const SkPoint blunt[] = {{-0.6f, 1}, {-1, 0.6f}}, usual[] = {{-0.25f, 0.778f}, {-1, 0.222f}}, sharp[] = {{-0.38f, 0.6f}, {-1, 0.05f}};
    const SkPoint* a = point < 0.5f ? blunt : usual;
    const SkPoint* b = point < 0.5f ? usual : sharp;
    const float k = point < 0.5f ? point * 2 : point * 2 - 1;
    const SkPoint left[] = {a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, {-1, -0.333f}, {-1, -0.778f}, {-0.7f, -1}, {-0.45f, -1},
                            {-0.22f, -1}, {-0.06f, 0.455f * notch - 1}, {0, notch - 1}};
    auto at = [&](SkPoint q, float side) { return SkPoint{side * q.fX * w, q.fY * h}; };
    SkPathBuilder path;
    path.moveTo(0, h);
    for (int i = 0; i < 9; i += 3) path.cubicTo(at(left[i], 1), at(left[i + 1], 1), at(left[i + 2], 1));
    for (int i = 7; i >= 1; i -= 3) path.cubicTo(at(left[i], -1), at(left[i - 1], -1), i > 1 ? at(left[i - 2], -1) : SkPoint{0, h});
    path.close();
    return path.detach();
}

SkPath Cloud(const Params& p, float w, float h) {
    const int n = p.count("bumps", 4, 2, 10);
    std::vector<float> r(n);
    float most = 0;
    for (int i = 0; i < n; i++) most = std::max(most, r[i] = 0.62f + 0.38f * sinf(kPi * (i + 0.5f) / n));
    for (float& v : r) v = std::min(v * h / most, w);
    const float x0 = -w + r[0], x1 = w - r[n - 1];
    std::vector<SkPath> parts;
    for (int i = 0; i < n; i++) parts.push_back(Oval(x0 + (x1 - x0) * i / (n - 1), h - r[i], r[i], r[i]));
    if (p.on("flatBottom", true) && x1 > x0) parts.push_back(Box(x0, h - *std::min_element(r.begin(), r.end()), x1, h));
    return Unite(parts);
}

SkPath LightningBolt(const Params&, float w, float h) {
    static const SkPath design = Polygon({{34, -100}, {-52, 8}, {-6, 8}, {-34, 100}, {56, -26}, {10, -26}});
    return Fitted(design, w, h);
}

SkPath CheckMark(const Params& p, float w, float h) {
    const float m = std::min(w, h), half = std::min(p.part("thickness", 24, 1, 100) * m, 0.9f * m);
    const float iw = w - half, ih = h - half;
    const SkPoint v{-0.413f * iw, ih}, c{iw, -ih};
    SkPoint arm = SkPoint{-iw, 0.17f * ih} - v;
    arm.setLength((c - v).length() * p.part("shortArm", 41, 5, 100));
    const SkPoint a = v + arm;
    SkPoint d1 = v - a, d2 = c - v;
    if (!d1.normalize() || !d2.normalize()) return SkPath();
    const SkPoint n1{-d1.fY, d1.fX}, n2{-d2.fY, d2.fX};
    const float side = d1.cross(d2) < 0 ? 1 : -1;
    SkPoint miter = n1 + n2;
    const float spread = sqrtf(std::max((1 + d1.dot(d2)) / 2, 0.0f));
    const bool mitred = spread > 0.25f && miter.setLength(half / spread);
    const SkPath joint = mitred ? Points({v, v + n1 * (half * side), v + miter * side, v + n2 * (half * side)})
                                : Points({v, v + n1 * (half * side), v + n2 * (half * side)});
    return Unite({Points({a + n1 * half, v + n1 * half, v - n1 * half, a - n1 * half}),
                  Points({v + n2 * half, c + n2 * half, c - n2 * half, v - n2 * half}), joint});
}

SkPath XMark(const Params& p, float w, float h) {
    const float half = std::min(p.part("thickness", 15, 1, 100) * std::min(w, h), 0.9f * std::min(w, h));
    std::vector<SkPath> bars;
    for (float s : {1.0f, -1.0f}) {
        SkPoint d{w, s * h};
        d.normalize();
        const SkPoint n{-d.fY, d.fX};
        const float k = std::max(std::min((w - half * fabsf(n.fX)) / w, (h - half * fabsf(n.fY)) / h), 0.05f);
        const SkPoint e{w * k, s * h * k};
        bars.push_back(Points({e + n * half, e - n * half, -e - n * half, -e + n * half}));
    }
    return Unite(bars);
}

SkPath Ring(const Params& p, float w, float h) {
    const float k = 1 - p.part("thickness", 38, 1, 100);
    const float start = p.num("startAngle", 90), sweep = std::clamp(p.num("sweep", 360), 0.0f, 360.0f);
    if (sweep >= 360) return k > 0 ? Join({Oval(0, 0, w, h), Oval(0, 0, w * k, h * k)}) : Oval(0, 0, w, h);
    if (!(sweep > 0)) return SkPath();
    SkPathBuilder b;
    b.moveTo(OnEllipse({0, 0}, w, h, start));
    ArcTo(b, {0, 0}, w, h, start, -sweep);
    if (k > 0) {
        b.lineTo(OnEllipse({0, 0}, w * k, h * k, start - sweep));
        ArcTo(b, {0, 0}, w * k, h * k, start - sweep, sweep);
    } else {
        b.lineTo(0, 0);
    }
    b.close();
    return b.detach();
}

SkPath SpeechBubble(const Params& p, float w, float h) {
    const SkPoint tip = p.point("tail", -69, 111);
    const float width = std::clamp(p.num("tailWidth", 34), 1.0f, 180.0f);
    const float ux = tip.fX / w, uy = tip.fY / h;
    if (ux * ux + uy * uy <= 1) return Oval(0, 0, w, h);
    const float facing = atan2f(-uy, ux) * 180 / kPi;
    SkPathBuilder b;
    b.moveTo(OnEllipse({0, 0}, w, h, facing + width / 2));
    ArcTo(b, {0, 0}, w, h, facing + width / 2, 360 - width);
    b.lineTo(tip);
    b.close();
    return b.detach();
}

SkPath Shield(const Params&, float w, float h) {
    static const SkPath design = Svg("M -85 -68 C -50 -68 -25 -84 0 -100 C 25 -84 50 -68 85 -68 C 88 -20 82 20 66 52 C 50 80 25 92 0 100 "
                                     "C -25 92 -50 80 -66 52 C -82 20 -88 -20 -85 -68 Z");
    return Fitted(design, w, h);
}

SkPath House(const Params& p, float w, float h) {
    const float eave = -h + 2 * h * p.part("roof", 48, 5, 95), door = w * p.part("door", 20, 0, 75);
    const float lintel = std::max(h - 2 * h * p.part("doorHeight", 35, 5, 95), eave + 0.02f * h);
    std::vector<SkPoint> pts = {{0, -h}, {w, eave}, {0.8f * w, eave}, {0.8f * w, h}};
    if (door > 0) pts.insert(pts.end(), {{door, h}, {door, lintel}, {-door, lintel}, {-door, h}});
    pts.insert(pts.end(), {{-0.8f * w, h}, {-0.8f * w, eave}, {-w, eave}});
    return Points(pts);
}

SkPath Bookmark(const Params& p, float w, float h) {
    return Polygon({{-w, -h}, {w, -h}, {w, h}, {0, h - 2 * h * p.part("notch", 24, 0, 95)}, {-w, h}});
}

SkPath FlagDesign(float wave) {
    SkPathBuilder b;
    b.moveTo(-80, -100).lineTo(-62, -100).cubicTo(-20, -100 - 25 * wave, 25, -100 + 25 * wave, 90, -100).lineTo(90, 10);
    b.cubicTo(25, 10 + 25 * wave, -20, 10 - 25 * wave, -62, 10).lineTo(-62, 100).lineTo(-80, 100).close();
    return b.detach();
}

SkPath Flag(const Params& p, float w, float h) {
    static const SkRect frame = FlagDesign(1).computeTightBounds();
    return Mapped(FlagDesign(p.part("wave", 100, -200, 200)), frame, w, h);
}

SkPath CrescentAndStar(const Params&, float w, float h) {
    static const SkPath design = Join({Combine(Oval(-14, 0, 90, 90), Oval(24, -8, 72, 72), kDifference_SkPathOp),
                                       Regular(10, 34, 14, 90).makeTransform(SkMatrix::Translate(50, -8))});
    return Fitted(design, w, h);
}

SkPath Sun(const Params& p, float w, float h) {
    const int n = p.count("rays", 8, 3, 32);
    const float inner = 1 - p.part("rayLength", 35, 1, 100), disc = p.part("disc", 45, 0, 100), half = p.part("rayWidth", 22, 1, 100) / 2;
    const float offset = p.num("offsetAngle", 0);
    std::vector<SkPath> parts;
    if (disc > 0) parts.push_back(Oval(0, 0, disc, disc));
    for (int i = 0; i < n; i++) {
        const SkPoint along = Polar(1, offset + i * 360.0f / n), across{-along.fY, along.fX};
        parts.push_back(Points({along * inner + across * half, along + across * (0.64f * half), along - across * (0.64f * half), along * inner - across * half}));
    }
    const bool apart = inner >= disc && half <= inner * sinf(kPi / n) * 0.98f;
    return (apart ? Join(parts) : Unite(parts)).makeTransform(SkMatrix::Scale(w, h));
}

SkPath Leaf(const Params&, float w, float h) {
    static const SkPath design = Combine(
        Combine(Svg("M -66 66 C -100 -20 -36 -92 82 -92 C 92 20 24 100 -66 66 Z"), Polygon({{-62, 58}, {-72, 70}, {-98, 98}, {-90, 106}, {-62, 82}})),
        Svg("M -52 52 Q 12 8 56 -56 Q -8 -12 -52 52 Z"), kDifference_SkPathOp);
    return Fitted(design, w, h);
}

SkPath SealBurst(const Params& p, float w, float h) {
    const int n = p.count("pointCount", 12, 3, 64);
    return Regular(2 * n, 1, p.part("innerRadius", 84, 1, 100), 90 + p.num("offsetAngle", 0)).makeTransform(SkMatrix::Scale(w, h));
}

SkPath HexagonOutline(const Params& p, float w, float h) {
    const int n = p.count("sideCount", 6, 3, 32);
    const float inner = 1 - p.part("thickness", 32, 1, 100), start = p.num("offsetAngle", 0);
    const SkPath outer = Regular(n, 1, 1, start);
    return (inner > 0 ? Join({outer, Regular(n, inner, inner, start)}) : outer).makeTransform(SkMatrix::Scale(w, h));
}

SkPath StarOutline(const Params& p, float w, float h) {
    const int n = p.count("pointCount", 5, 3, 32);
    const float ratio = p.part("innerRadius", 40, 1, 100), keep = 1 - p.part("thickness", 44, 1, 100), start = 90 + p.num("offsetAngle", 0);
    const SkPath outer = Regular(2 * n, 1, ratio, start);
    return (keep > 0 ? Join({outer, Regular(2 * n, keep, keep * ratio, start)}) : outer).makeTransform(SkMatrix::Scale(w, h));
}

SkPath Chevron(const Params& p, float w, float h) {
    const float t = std::min(2 * w * p.part("thickness", 34, 1, 100), 1.98f * w);
    return Polygon({{-w, -h}, {-w + t, -h}, {w, 0}, {-w + t, h}, {-w, h}, {w - t, 0}});
}

SkPath DoubleArrow(const Params& p, float w, float h) {
    const float s = h * p.part("shaft", 38, 1, 100), l = 2 * w * p.part("headLength", 29, 1, 50);
    return Polygon({{-w, 0}, {-w + l, -h}, {-w + l, -s}, {w - l, -s}, {w - l, -h}, {w, 0}, {w - l, h}, {w - l, s}, {-w + l, s}, {-w + l, h}});
}

SkPath CurvedArrow(const Params& p, float w, float h) {
    const float outer = 79, inner = outer * (1 - p.part("thickness", 43, 5, 95)), band = outer - inner, middle = (outer + inner) / 2;
    const float sweep = std::clamp(p.num("sweep", 180), 10.0f, 340.0f), head = p.part("headSize", 100, 10, 300);
    const float spread = std::min(1.176f * band * head, middle * 0.98f), reach = 1.647f * band * head;
    const SkPoint centre{0, 14};
    const float end = -180 - sweep;
    const SkPoint radial = OnEllipse({0, 0}, 1, 1, end), along{-radial.fY, radial.fX};
    SkPathBuilder b;
    b.moveTo(OnEllipse(centre, outer, outer, -180));
    ArcTo(b, centre, outer, outer, -180, -sweep);
    b.lineTo(centre + radial * (middle + spread)).lineTo(centre + radial * middle + along * reach).lineTo(centre + radial * (middle - spread));
    b.lineTo(OnEllipse(centre, inner, inner, end));
    ArcTo(b, centre, inner, inner, end, sweep);
    b.close();
    return Mapped(b.detach(), SkRect::MakeLTRB(-79, -65, 102, 70), w, h);
}

SkPath Parallelogram(const Params& p, float w, float h) {
    const float o = 2 * w * std::clamp(p.num("slant", 25), -100.0f, 100.0f) / 100;
    const float pos = std::max(o, 0.0f), neg = std::min(o, 0.0f);
    return Polygon({{-w + pos, -h}, {w + neg, -h}, {w - pos, h}, {-w - neg, h}});
}

SkPath Trapezoid(const Params& p, float w, float h) {
    const float top = w * p.part("topWidth", 55, 0, 200);
    return Polygon({{-top, -h}, {top, -h}, {w, h}, {-w, h}});
}

SkPath Ticket(const Params& p, float w, float h) {
    const float notch = std::min({h * p.part("notch", 30, 0, 100), 0.95f * h, w});
    SkPathBuilder b;
    b.moveTo(-w, -h).lineTo(w, -h);
    if (notch > 0) {
        b.lineTo(w, -notch);
        ArcTo(b, {w, 0}, notch, notch, 90, 180);
    }
    b.lineTo(w, h).lineTo(-w, h);
    if (notch > 0) {
        b.lineTo(-w, notch);
        ArcTo(b, {-w, 0}, notch, notch, 270, 180);
    }
    b.close();
    std::vector<SkPath> parts = {b.detach()};
    const int holes = p.count("holes", 5, 0, 20);
    float r = 0.0917f * h;
    if (holes > 1) r = std::min(r, 0.4f * 1.2f * h / (holes - 1));
    for (int i = 0; i < holes; i++) parts.push_back(Oval(0.44f * w, holes > 1 ? -0.6f * h + 1.2f * h * i / (holes - 1) : 0, r, r));
    return Join(parts);
}

SkPath Tag(const Params& p, float w, float h) {
    const float point = 2 * w * p.part("point", 28, 0, 100);
    const SkPath body = Polygon({{-w, 0}, {-w + point, -h}, {w, -h}, {w, h}, {-w + point, h}});
    const float r = std::min(h * p.part("hole", 22, 0, 90), 0.9f * h);
    const float centre = -w + std::max(point, 1.05f * r * hypotf(h, point) / h);
    if (!(r > 0) || centre + r > 0.95f * w) return body;
    return Join({body, Oval(centre, 0, r, r)});
}

SkPath Crown(const Params& p, float w, float h) {
    const int n = p.count("pointCount", 3, 2, 9);
    const float valley = -76 + 158 * p.part("depth", 46, 0, 100), ball = 11 * p.part("balls", 100, 0, 200);
    std::vector<SkPoint> outline;
    std::vector<SkPath> parts;
    for (int i = 0; i < n; i++) {
        const SkPoint peak{-90 + 180.0f * i / (n - 1), -50 - 26 * sinf(kPi * i / (n - 1))};
        if (i > 0) outline.push_back({peak.fX - 90.0f / (n - 1), valley});
        outline.push_back(peak);
        if (ball > 0.5f) parts.push_back(Oval(peak.fX, peak.fY - 8, ball, ball));
    }
    outline.insert(outline.end(), {{78, 82}, {-78, 82}});
    parts.push_back(Points(outline));
    SkPath crown = Unite(parts);
    if (p.on("jewels", true)) {
        const float r = std::min(9.0f, 48.0f / n);
        for (int j = 0; j < n; j++) crown = Combine(crown, Oval(-60 + 120.0f * (j + 0.5f) / n, 50, r, r), kDifference_SkPathOp);
    }
    return Mapped(crown, SkRect::MakeLTRB(-101, -95, 101, 82), w, h);
}

SkPath Eye(const Params& p, float w, float h) {
    SkPathBuilder b;
    b.moveTo(-w, 0).cubicTo(-0.55f * w, -1.3333f * h, 0.55f * w, -1.3333f * h, w, 0).cubicTo(0.55f * w, 1.3333f * h, -0.55f * w, 1.3333f * h, -w, 0).close();
    const SkPath almond = b.detach();
    const float iris = h * p.part("iris", 76, 0, 100), pupil = iris * p.part("pupil", 45, 0, 100);
    if (!(iris > 0)) return almond;
    const SkPath ring = Combine(Oval(0, 0, iris, iris), almond, kIntersect_SkPathOp);
    return pupil > 0 && pupil < iris ? Join({almond, ring, Oval(0, 0, pupil, pupil)}) : Join({almond, ring});
}

SkPath Umbrella(const Params&, float w, float h) {
    static const SkPath design = Unite({Svg("M -95 10 A 95 90 0 0 1 95 10 Q 63.33 -18 31.67 10 Q 0 -18 -31.67 10 Q -63.33 -18 -95 10 Z"),
                                        Box(-4, -102, 4, -70), Svg("M 5 -24 L 5 68 A 22 22 0 0 1 -39 68 L -29 68 A 12 12 0 0 0 -5 68 L -5 -24 Z")});
    return Fitted(design, w, h);
}

SkPath MusicNote(const Params&, float w, float h) {
    static const SkPath design = [] {
        SkMatrix tilt = SkMatrix::RotateDeg(-25);
        tilt.postTranslate(-34, 70);
        return Unite({Oval(0, 0, 40, 28).makeTransform(tilt), Box(-14, -100, 4, 66), Svg("M 0 -96 C 10 -62 72 -66 66 -8 C 60 -50 30 -44 0 -36 Z")});
    }();
    return Fitted(design, w, h);
}

SkPath Droplet(float cx, float cy, float r, float height) {
    const SkPoint tip{cx, cy - height};
    const float beta = acosf(r / height);
    const SkPoint right{cx + r * sinf(beta), cy - r * cosf(beta)}, left{cx - r * sinf(beta), cy - r * cosf(beta)};
    SkPoint u = right - tip;
    const float reach = u.length() * 0.35f, bulge = 0.05f * height;
    u.normalize();
    const SkPoint mirrored{-u.fX, u.fY};
    SkPathBuilder b;
    b.moveTo(tip).cubicTo(tip + u * reach + SkPoint{bulge, 0}, right - u * reach, right);
    ArcTo(b, {cx, cy}, r, r, 90 - beta * 180 / kPi, -(360 - 2 * beta * 180 / kPi));
    b.cubicTo(left - mirrored * reach, tip + mirrored * reach - SkPoint{bulge, 0}, tip).close();
    return b.detach();
}

SkPath DropletPair(const Params&, float w, float h) {
    static const SkPath design = Join({Droplet(-36, 28, 50, 104), Droplet(62, 62, 30, 62)});
    return Fitted(design, w, h);
}

SkPath Key(const Params&, float w, float h) {
    static const SkPath design = Combine(Unite({Oval(-58, 0, 42, 42), Box(-24, -10, 100, 10), Box(60, 2, 76, 38), Box(82, 2, 98, 28)}),
                                         Oval(-58, 0, 18, 18), kDifference_SkPathOp);
    return Fitted(design, w, h);
}

SkPath LocationPin(const Params& p, float w, float h) {
    const float r = std::min(w, h);
    const SkPoint centre{0, -h + r};
    const float reach = 2 * h - r;
    const float hole = r * p.part("hole", 39, 0, 95);
    SkPath body = Oval(centre.fX, centre.fY, r, r);
    if (reach > r * 1.02f) {
        const float alpha = acosf(r / reach) * 180 / kPi;
        SkPathBuilder b;
        b.moveTo(0, h).lineTo(OnEllipse(centre, r, r, alpha - 90));
        ArcTo(b, centre, r, r, alpha - 90, 360 - 2 * alpha);
        b.close();
        body = b.detach();
    }
    return hole > 0 ? Join({body, Oval(centre.fX, centre.fY, hole, hole)}) : body;
}

SkPath RoundedPlay(const Params&, float w, float h) { return Polygon({{-w, -h}, {w, 0}, {-w, h}}); }

SkPath Pause(const Params& p, float w, float h) {
    const int n = p.count("bars", 2, 1, 8);
    const float gaps = n > 1 ? 2 * w * p.part("gap", 26, 0, 95) : 0, gap = n > 1 ? gaps / (n - 1) : 0, bar = (2 * w - gaps) / n;
    std::vector<SkPath> bars;
    for (int i = 0; i < n; i++) bars.push_back(Box(-w + i * (bar + gap), -h, -w + i * (bar + gap) + bar, h));
    return Join(bars);
}

SkPath Record(const Params& p, float w, float h) {
    const float inner = 1 - p.part("ring", 16, 1, 100), dot = p.part("dot", 60, 0, 100);
    if (dot >= inner) return Oval(0, 0, w, h);
    return dot > 0 ? Join({Oval(0, 0, w, h), Oval(0, 0, w * inner, h * inner), Oval(0, 0, w * dot, h * dot)})
                   : Join({Oval(0, 0, w, h), Oval(0, 0, w * inner, h * inner)});
}

SkPath Gear(const Params& p, float w, float h) {
    const int n = p.count("teeth", 8, 3, 64);
    const float root = 1 - p.part("toothDepth", 22, 1, 90), pitch = 360.0f / n;
    const float tip = p.part("toothWidth", 40, 1, 100) * pitch / 2, base = std::max(p.part("toothBase", 62, 1, 100) * pitch / 2, tip);
    const float hole = std::min(p.part("hole", 30, 0, 95), root * 0.95f);
    SkPathBuilder b;
    for (int i = 0; i < n; i++) {
        const float centre = 90 - i * pitch;
        const SkPoint first = OnEllipse({0, 0}, w * root, h * root, centre + base);
        if (i == 0) b.moveTo(first);
        b.lineTo(OnEllipse({0, 0}, w, h, centre + tip)).lineTo(OnEllipse({0, 0}, w, h, centre - tip)).lineTo(OnEllipse({0, 0}, w * root, h * root, centre - base));
        if (pitch - 2 * base > 0.01f) ArcTo(b, {0, 0}, w * root, h * root, centre - base, -(pitch - 2 * base));
    }
    b.close();
    const SkPath gear = b.detach();
    return hole > 0 ? Join({gear, Oval(0, 0, w * hole, h * hole)}) : gear;
}

SkPath Phone(const Params& p, float w, float h) {
    const float m = std::min(w, h), r = std::clamp(p.num("roundCorners", 14), 0.0f, m);
    const float border = std::min(2 * m * p.part("border", 7, 0, 100), 0.95f * m);
    SkPath phone = Rounded(-w, -h, w, h, r);
    if (p.on("buttons", false)) {
        const float out = std::max(0.08f * m, 1.0f);
        phone = Unite({phone, Box(w - out, -0.42f * h, w + out, -0.18f * h), Box(-w - out, -0.5f * h, -w + out, -0.36f * h),
                       Box(-w - out, -0.3f * h, -w + out, -0.16f * h)});
    }
    if (!(border > 0)) return phone;
    const bool notch = p.on("notch", false);
    SkPath screen = Rounded(-w + border, -h + border, w - border, h - border, std::max(r - border, 0.0f));
    const float dot = std::max(0.06f * m, 0.5f), top = -h + border;
    if (notch) screen = Combine(screen, Box(-0.42f * (w - border), top - 1, 0.42f * (w - border), top + std::max(1.4f * border, 0.07f * h)), kDifference_SkPathOp);
    phone = Combine(phone, screen, kDifference_SkPathOp);
    if (!notch && p.on("camera", true) && h - border > top + 6 * dot) phone = Join({phone, Oval(0, top + 2.5f * dot, dot, dot)});
    return phone;
}

SkPath Panel(const Params& p, float w, float h) {
    const float m = std::min(w, h), r = std::clamp(p.num("roundCorners", 10), 0.0f, m);
    const float border = std::min(2 * m * p.part("border", 5, 0, 100), 0.95f * m);
    const SkPath panel = Rounded(-w, -h, w, h, r);
    if (!(border > 0)) return panel;
    const int columns = p.count("columns", 1, 1, 8), rows = p.count("rows", 1, 1, 8);
    const float top = -h + border + (2 * h - 2 * border) * p.part("header", 15, 0, 90);
    const float cell = (2 * w - 2 * border - (columns - 1) * border) / columns, row = (h - border - top - (rows - 1) * border) / rows;
    if (!(cell > 0 && row > 0)) return panel;
    std::vector<SkPath> parts = {panel};
    const float inner = std::min({std::max(r - border, 0.0f), cell / 2, row / 2});
    for (int y = 0; y < rows; y++) {
        for (int x = 0; x < columns; x++) {
            const float left = -w + border + x * (cell + border), upper = top + y * (row + border);
            parts.push_back(Rounded(left, upper, left + cell, upper + row, inner));
        }
    }
    return Join(parts);
}

struct Outline {
    const char* id;
    float w, h, round;
    SkPath (*make)(const Params&, float, float);
};

const Outline kOutlines[] = {
    {"heart", 100, 90, 0, Heart}, {"cloud", 100, 48, 0, Cloud}, {"lightning-bolt", 54, 100, 3, LightningBolt},
    {"check-mark", 100, 80, 19, CheckMark}, {"x-mark", 100, 100, 4, XMark}, {"ring", 100, 100, 0, Ring},
    {"speech-bubble", 89, 89, 0, SpeechBubble}, {"shield", 86, 100, 0, Shield}, {"house", 100, 96, 3, House},
    {"bookmark", 60, 100, 8, Bookmark}, {"flag", 82, 100, 0, Flag}, {"crescent-and-star", 100, 97, 0, CrescentAndStar},
    {"sun", 100, 100, 3, Sun}, {"leaf", 91, 100, 0, Leaf}, {"seal-burst", 100, 100, 0, SealBurst},
    {"hexagon-outline", 100, 100, 0, HexagonOutline}, {"star-outline", 100, 100, 0, StarOutline}, {"chevron", 58, 100, 5, Chevron},
    {"double-arrow", 100, 58, 3, DoubleArrow}, {"curved-arrow", 100, 75, 0, CurvedArrow}, {"parallelogram", 100, 62, 8, Parallelogram},
    {"trapezoid", 100, 61, 8, Trapezoid}, {"ticket", 100, 60, 10, Ticket}, {"tag", 100, 64, 8, Tag},
    {"crown", 100, 88, 4, Crown}, {"eye", 100, 56, 0, Eye}, {"umbrella", 99, 100, 0, Umbrella},
    {"music-note", 69, 100, 0, MusicNote}, {"droplet-pair", 100, 94, 0, DropletPair}, {"key", 100, 42, 2, Key},
    {"location-pin", 65, 100, 0, LocationPin}, {"rounded-play", 93, 100, 24, RoundedPlay}, {"pause", 84, 100, 15, Pause},
    {"record", 100, 100, 0, Record}, {"gear", 100, 100, 0, Gear}, {"phone", 56, 100, 14, Phone}, {"panel", 100, 70, 10, Panel},
};

const Outline* FindOutline(const std::string& id) {
    for (const Outline& o : kOutlines) {
        if (id == o.id) return &o;
    }
    return nullptr;
}

SkPath MakeOutline(const Outline& o, const Params& p) {
    const SkPoint size = p.point("size", o.w, o.h);
    const float w = std::min(fabsf(size.fX), kMaxSize), h = std::min(fabsf(size.fY), kMaxSize);
    if (!(w > 0 && h > 0)) return SkPath();
    SkPath path = o.make(p, w, h);
    path.setFillType(SkPathFillType::kEvenOdd);
    return path;
}

std::string OutlineKey(const Layer& layer, float t) {
    std::string key = layer.outlineShape;
    for (const Property& p : layer.props) {
        key += '\n' + p.name + '=';
        for (int i = 0; i < 2; i++) {
            const float v = p.anim.get(i, t, 0);
            key.append(reinterpret_cast<const char*>(&v), sizeof v);
        }
    }
    return key;
}

SkPath CachedOutline(const Layer& layer, float t, const Outline& o, const Params& p) {
    static std::mutex lock;
    static std::unordered_map<std::string, SkPath> made;
    static std::deque<std::string> order;
    const std::string key = OutlineKey(layer, t);
    {
        const std::lock_guard<std::mutex> hold(lock);
        if (auto it = made.find(key); it != made.end()) return it->second;
    }
    const SkPath path = RoundCorners(MakeOutline(o, p), p.num("roundCorners", o.round));
    const std::lock_guard<std::mutex> hold(lock);
    if (made.emplace(key, path).second) order.push_back(key);
    while (order.size() > 256) {
        made.erase(order.front());
        order.pop_front();
    }
    return path;
}

}

SkRect CroppedBox(const Layer& l, float t, size_t before) {
    SkRect box = ShapePath(l, t).getBounds();
    for (size_t k = 0; k < std::min(before, l.effects.size()); k++) {
        const LayerEffect& e = l.effects[k];
        if (e.hidden || e.id != "absent.crop") continue;
        const float left = std::clamp(e.value("left", t, 0), 0.0f, 0.999f), top = std::clamp(e.value("top", t, 0), 0.0f, 0.999f);
        const float right = std::max(1 - std::clamp(e.value("right", t, 0), 0.0f, 1.0f), left + 0.001f);
        const float bottom = std::max(1 - std::clamp(e.value("bottom", t, 0), 0.0f, 1.0f), top + 0.001f);
        box = SkRect::MakeLTRB(box.left() + left * box.width(), box.top() + top * box.height(),
                               box.left() + right * box.width(), box.top() + bottom * box.height());
    }
    return box;
}

SkPath Cropped(const Layer& l, float t, const SkPath& path, size_t before) {
    SkPath out = path;
    for (size_t k = 0; k < std::min(before, l.effects.size()); k++) {
        const LayerEffect& e = l.effects[k];
        if (e.hidden || e.id != "absent.crop") continue;
        const float radius = std::clamp(e.value("radius", t, 0), 0.0f, 1.0f);
        if (e.value("left", t, 0) <= 0 && e.value("top", t, 0) <= 0 && e.value("right", t, 0) <= 0 && e.value("bottom", t, 0) <= 0 && radius <= 0) continue;
        const SkRect box = CroppedBox(l, t, k + 1);
        const float r = radius * std::min(box.width(), box.height()) / 2;
        SkPath cut;
        if (Op(out, SkPath::RRect(SkRRect::MakeRectXY(box, r, r)), kIntersect_SkPathOp, &cut)) out = cut;
    }
    return out;
}

namespace {

SkPath Parametric(const Params& p) {
    const std::string& s = p.layer.shape;
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
        return Arrow(p.point("start", 0, 0), p.point("end", 250, -250), p.num("lineWidth", 40) / 2,
                     p.num("headWidth", 120) / 2, arrow ? p.num("headLength", 130) : 0);
    }
    return b.addRect(box).detach();
}

}

SkPath ShapePath(const Layer& layer, float t, bool rounded) {
    if (layer.text) return TextPath(layer, t);
    if (layer.kind == "embedScene") return SkPath::Rect(SkRect::MakeXYWH(-layer.innerWidth / 2.0f, -layer.innerHeight / 2.0f, (float)layer.innerWidth, (float)layer.innerHeight));
    if (layer.kind == "drawing") return DrawingBounds(layer);
    if (layer.kind == "model") {
        const float r = fabsf(layer.value("size", 0, t, 200));
        return SkPath::Rect(SkRect::MakeLTRB(-r, -r, r, r));
    }
    const Params p{layer, t};
    if (layer.shape.empty() && !layer.outlineShape.empty()) {
        const Outline* o = FindOutline(layer.outlineShape);
        if (!o || layer.outlineEdited) return OutlineAt(layer, t);
        return rounded ? CachedOutline(layer, t, *o, p) : MakeOutline(*o, p);
    }
    const SkPath path = layer.shape.empty() ? OutlineAt(layer, t) : Parametric(p);
    return rounded ? RoundCorners(path, p.num("roundCorners", 0)) : path;
}

bool ShapeOutlineSvg(const std::string& sceneXml, float t, bool rounded, std::string* svg) {
    Scene scene;
    if (!ParseScene(sceneXml, &scene, nullptr) || scene.layers.empty()) return false;
    const SceneScope scope(scene);
    const Layer& layer = scene.layers.front();
    if (layer.kind != "shape") return false;
    SkPath path = ShapePath(layer, t, rounded);
    int moves = 0, closes = 0;
    for (const Piece& s : Pieces(path)) {
        if (s.verb == SkPathVerb::kMove) moves++;
        if (s.verb == SkPathVerb::kClose) closes++;
    }
    if (moves == closes) {
        if (auto simple = Simplify(path)) path = *simple;
    }
    *svg = OutlineSvg(path);
    return true;
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
