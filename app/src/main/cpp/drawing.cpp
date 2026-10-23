#include "drawing.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <vector>

#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPathMeasure.h"
#include "include/core/SkPathUtils.h"

namespace am {
namespace {

enum Tool { kPen = 0, kBrush = 1, kFill = 2, kEraser = 3 };

SkPath StrokedOutline(const FreehandStroke& s) {
    SkPaint p;
    p.setStyle(SkPaint::kStroke_Style);
    p.setStrokeWidth(s.width);
    p.setStrokeCap(SkPaint::kRound_Cap);
    p.setStrokeJoin(SkPaint::kRound_Join);
    return skpathutils::FillPathWithPaint(s.geometry, p);
}

SkPath Band(const std::vector<SkPoint>& pts, const std::vector<float>& radius) {
    SkPathBuilder b;
    for (size_t i = 0; i < pts.size(); i++) b.addCircle(pts[i].fX, pts[i].fY, radius[i], SkPathDirection::kCW);
    for (size_t i = 0; i + 1 < pts.size(); i++) {
        const SkPoint a = pts[i], c = pts[i + 1];
        const float r0 = radius[i], r1 = radius[i + 1];
        const float d = SkPoint::Distance(a, c);
        if (d <= fabsf(r0 - r1) + 1e-4f) continue;
        const SkPoint u = {(c.fX - a.fX) / d, (c.fY - a.fY) / d};
        const SkPoint n = {-u.fY, u.fX};
        const float k = (r0 - r1) / d, h = sqrtf(std::max(0.0f, 1 - k * k));
        const SkPoint m1 = {k * u.fX + h * n.fX, k * u.fY + h * n.fY};
        const SkPoint m2 = {k * u.fX - h * n.fX, k * u.fY - h * n.fY};
        SkPoint q[4] = {{a.fX + r0 * m1.fX, a.fY + r0 * m1.fY}, {c.fX + r1 * m1.fX, c.fY + r1 * m1.fY},
                        {c.fX + r1 * m2.fX, c.fY + r1 * m2.fY}, {a.fX + r0 * m2.fX, a.fY + r0 * m2.fY}};
        float area = 0;
        for (int j = 0; j < 4; j++) area += q[j].fX * q[(j + 1) % 4].fY - q[(j + 1) % 4].fX * q[j].fY;
        if (area < 0) std::swap(q[0], q[3]), std::swap(q[1], q[2]);
        b.addPolygon({q, 4}, true);
    }
    return b.detach();
}

void BuildGeometry(FreehandStroke* s, const std::vector<SkPoint>& pts, const std::vector<float>& pressure) {
    if (pts.empty()) return;
    s->points = pts;
    for (size_t i = 0; i < pts.size(); i++) s->radius.push_back(std::max(0.01f, (s->tool == kBrush ? pressure[i] : 1.0f) * s->width / 2));
    switch (s->tool) {
        case kFill: {
            s->geometry = SkPath::Polygon({pts.data(), pts.size()}, true);
            s->filled = true;
            break;
        }
        case kBrush: {
            std::vector<float> radius(pts.size());
            for (size_t i = 0; i < pts.size(); i++) radius[i] = std::max(0.01f, pressure[i] * s->width / 2);
            s->geometry = Band(pts, radius);
            s->filled = true;
            break;
        }
        default:
            if (pts.size() == 1) {
                s->geometry = SkPath::Circle(pts[0].fX, pts[0].fY, std::max(0.01f, s->width / 2));
                s->filled = true;
            } else {
                s->geometry = SkPath::Polygon({pts.data(), pts.size()}, false);
            }
    }
}

}

bool DrawingEffect(const std::string& id) { return id == "absent.drawingprogress" || id == "absent.stroketaper"; }

void ParseStrokes(const XmlNode& drawing, Layer* layer) {
    for (const auto& n : drawing.children) {
        if (n->name != "stroke") continue;
        FreehandStroke s;
        s.color[0] = 1; s.color[1] = 0; s.color[2] = 0; s.color[3] = 1;
        if (const char* c = n->attr("color"); c && *c == '#') {
            const uint32_t argb = (uint32_t)strtoul(c + 1, nullptr, 16);
            for (int i = 0; i < 4; i++) s.color[i] = ((argb >> (24 - 8 * i)) & 0xff) / 255.0f;
        }
        if (const char* w = n->attr("width")) s.width = strtof(w, nullptr);
        if (const char* type = n->attr("type")) {
            if (!strcasecmp(type, "brush")) s.tool = kBrush;
            else if (!strcasecmp(type, "fill")) s.tool = kFill;
            else if (!strcasecmp(type, "eraser")) s.tool = kEraser;
        }
        std::vector<SkPoint> pts;
        std::vector<float> pressure;
        const char* p = n->attr("points");
        while (p && *p) {
            float v[3];
            int count = 0;
            while (*p && *p != ';') {
                char* end = nullptr;
                const float f = strtof(p, &end);
                if (end == p) { p++; continue; }
                if (count < 3) v[count] = f;
                count++;
                p = end;
                if (*p == ',') p++;
            }
            if (*p == ';') p++;
            if (count >= 3 && std::isfinite(v[0]) && std::isfinite(v[1])) {
                pts.push_back({v[0], v[1]});
                pressure.push_back(std::isfinite(v[2]) ? std::max(0.0f, v[2]) : 1.0f);
            }
        }
        BuildGeometry(&s, pts, pressure);
        layer->strokes.push_back(std::move(s));
    }
}

namespace {

struct Reveal {
    float start = 0, end = 1, taperStart = 0, taperEnd = 1, squeeze = 0;
    bool tapered = false;
};

Reveal RevealOf(const Layer& layer, float t) {
    Reveal r;
    if (const LayerEffect* e = layer.effect("absent.drawingprogress")) {
        r.start = std::clamp(e->value("start", t, 0), 0.0f, 1.0f);
        r.end = std::clamp(e->value("end", t, 1), 0.0f, 1.0f);
    }
    if (const LayerEffect* e = layer.effect("absent.stroketaper")) {
        r.taperStart = std::clamp(e->value("start", t, 0), 0.0f, 1.0f);
        r.taperEnd = std::clamp(e->value("end", t, 1), 0.0f, 1.0f);
        r.squeeze = std::clamp(e->value("squeeze", t, 0), 0.0f, 100.0f);
        r.tapered = r.taperStart > 0 || r.taperEnd < 1;
    }
    return r;
}

float Length(const FreehandStroke& s) {
    float length = 0;
    for (size_t i = 0; i + 1 < s.points.size(); i++) length += SkPoint::Distance(s.points[i], s.points[i + 1]);
    return length;
}

float Taper(const Reveal& r, float u) {
    float f = 1;
    if (r.taperStart > 0 && u < r.taperStart) f = std::min(f, u / r.taperStart);
    if (r.taperEnd < 1 && u > r.taperEnd) f = std::min(f, (1 - u) / (1 - r.taperEnd));
    return std::pow(std::clamp(f, 0.0f, 1.0f), 1 + r.squeeze / 25);
}

void Slice(const FreehandStroke& s, float lo, float hi, float step, std::vector<SkPoint>* pts, std::vector<float>* radius,
           std::vector<float>* along) {
    const auto& p = s.points;
    if (p.size() == 1) {
        pts->push_back(p[0]);
        radius->push_back(s.radius[0]);
        along->push_back(0);
        return;
    }
    float a0 = 0;
    for (size_t i = 0; i + 1 < p.size(); i++) {
        const float d = SkPoint::Distance(p[i], p[i + 1]), a1 = a0 + d;
        const float from = std::max(lo, a0), to = std::min(hi, a1);
        if (to >= from && !(d <= 0 && !pts->empty())) {
            auto emit = [&](float a) {
                const float u = d > 0 ? (a - a0) / d : 0;
                pts->push_back(p[i] + (p[i + 1] - p[i]) * u);
                radius->push_back(s.radius[i] + (s.radius[i + 1] - s.radius[i]) * u);
                along->push_back(a);
            };
            if (pts->empty()) emit(from);
            const int pieces = step > 0 ? std::max(1, (int)std::ceil((to - from) / step)) : 1;
            for (int k = 1; k <= pieces; k++) emit(from + (to - from) * k / pieces);
        }
        a0 = a1;
    }
}

template <typename F> void EachPiece(const Layer& layer, float t, F&& draw) {
    const Reveal r = RevealOf(layer, t);
    float total = 0;
    std::vector<float> lengths;
    for (const FreehandStroke& s : layer.strokes) total += lengths.emplace_back(Length(s));
    const float start = r.start * total, end = r.end * total;
    float at = 0;
    for (size_t k = 0; k < layer.strokes.size(); k++) {
        const FreehandStroke& s = layer.strokes[k];
        const float length = lengths[k], lo = std::max(start - at, 0.0f), hi = std::min(end - at, length);
        const bool seen = length > 0 ? hi > lo : total > 0 ? at >= start && at <= end && end > start : r.end > r.start;
        at += length;
        if (s.geometry.isEmpty() || !seen) continue;
        const bool taper = r.tapered && (s.tool == kPen || s.tool == kBrush);
        if (!taper && lo <= 0 && hi >= length) {
            draw(s, s.geometry, s.filled);
            continue;
        }
        std::vector<SkPoint> pts;
        std::vector<float> radius, along;
        Slice(s, lo, hi, taper ? std::max(length / 200, 1.5f) : 0, &pts, &radius, &along);
        if (pts.empty()) continue;
        if (s.tool == kFill) {
            draw(s, SkPath::Polygon({pts.data(), pts.size()}, true), true);
        } else if (taper) {
            for (size_t i = 0; i < pts.size(); i++) radius[i] = std::max(0.01f, radius[i] * Taper(r, length > 0 ? along[i] / length : 0));
            draw(s, Band(pts, radius), true);
        } else if (s.tool == kBrush) {
            draw(s, Band(pts, radius), true);
        } else if (pts.size() == 1) {
            draw(s, SkPath::Circle(pts[0].fX, pts[0].fY, std::max(0.01f, s.width / 2)), true);
        } else {
            draw(s, SkPath::Polygon({pts.data(), pts.size()}, false), false);
        }
    }
}

}

SkPath DrawingProgressPath(const Layer& layer, const SkPath& path, float t) {
    const LayerEffect* e = layer.effect("absent.drawingprogress");
    if (!e) return path;
    const float start = std::clamp(e->value("start", t, 0), 0.0f, 1.0f), end = std::clamp(e->value("end", t, 1), 0.0f, 1.0f);
    if (start <= 0 && end >= 1) return path;
    float total = 0;
    SkPathMeasure measure(path, false);
    do total += measure.getLength(); while (measure.nextContour());
    SkPathBuilder b;
    if (!(total > 0) || end <= start) return b.detach();
    SkPathMeasure pieces(path, false);
    float at = 0;
    do {
        const float length = pieces.getLength(), lo = std::max(start * total - at, 0.0f), hi = std::min(end * total - at, length);
        if (hi > lo) {
            pieces.getSegment(lo, hi, &b, true);
            if (lo <= 0 && hi >= length && pieces.isClosed()) b.close();
        }
        at += length;
    } while (pieces.nextContour());
    return b.detach();
}

void DrawDrawing(SkCanvas* canvas, const Layer& layer, float opacity, float t) {
    bool erases = false;
    for (const FreehandStroke& s : layer.strokes) erases |= s.tool == kEraser;
    if (erases) canvas->saveLayer(nullptr, nullptr);
    EachPiece(layer, t, [&](const FreehandStroke& s, const SkPath& geometry, bool filled) {
        SkPaint paint;
        paint.setAntiAlias(true);
        if (s.tool == kEraser) {
            paint.setBlendMode(SkBlendMode::kClear);
        } else {
            paint.setColor4f({s.color[1], s.color[2], s.color[3], std::clamp(s.color[0] * opacity, 0.0f, 1.0f)});
        }
        if (!filled) {
            paint.setStyle(SkPaint::kStroke_Style);
            paint.setStrokeWidth(s.width);
            paint.setStrokeCap(SkPaint::kRound_Cap);
            paint.setStrokeJoin(SkPaint::kRound_Join);
        }
        canvas->drawPath(geometry, paint);
    });
    if (erases) canvas->restore();
}

SkPath DrawingBounds(const Layer& layer) {
    SkRect all = SkRect::MakeEmpty();
    bool any = false;
    for (const FreehandStroke& s : layer.strokes) {
        if (s.tool == kEraser || s.geometry.isEmpty()) continue;
        SkRect r = s.geometry.getBounds();
        if (!s.filled) r.outset(s.width / 2, s.width / 2);
        if (any) all.join(r); else all = r;
        any = true;
    }
    return any ? SkPath::Rect(all) : SkPath();
}

bool DrawingContains(const Layer& layer, SkPoint local) {
    for (auto it = layer.strokes.rbegin(); it != layer.strokes.rend(); ++it) {
        if (it->geometry.isEmpty() || (it->tool != kEraser && it->color[0] <= 0)) continue;
        if ((it->filled ? it->geometry : StrokedOutline(*it)).contains(local.fX, local.fY)) return it->tool != kEraser;
    }
    return false;
}

namespace {

struct Knot {
    SkPoint p{}, in{}, out{};
    bool hasIn = false, hasOut = false;
};
struct Sub {
    std::vector<Knot> knots;
    bool closed = false;
};

struct Piece {
    SkPathVerb verb;
    SkPoint c1, c2, p;
};
std::vector<Piece> Pieces(const SkPath& path) {
    std::vector<Piece> out;
    auto quad = [&](SkPoint a, SkPoint q, SkPoint p) {
        out.push_back({SkPathVerb::kCubic, a + (q - a) * (2.0f / 3), p + (q - p) * (2.0f / 3), p});
    };
    SkPathIter it = path.iter();
    while (auto r = it.next()) {
        const auto& q = r->fPoints;
        switch (r->fVerb) {
            case SkPathVerb::kMove: out.push_back({SkPathVerb::kMove, {}, {}, q[0]}); break;
            case SkPathVerb::kLine: out.push_back({SkPathVerb::kLine, {}, {}, q[1]}); break;
            case SkPathVerb::kQuad: quad(q[0], q[1], q[2]); break;
            case SkPathVerb::kConic: {
                SkPoint qs[5];
                const int n = SkPath::ConvertConicToQuads(q[0], q[1], q[2], r->fConicWeight, qs, 1);
                for (int i = 0; i < n; i++) quad(qs[2 * i], qs[2 * i + 1], qs[2 * i + 2]);
                break;
            }
            case SkPathVerb::kCubic: out.push_back({SkPathVerb::kCubic, q[1], q[2], q[3]}); break;
            case SkPathVerb::kClose: out.push_back({SkPathVerb::kClose, {}, {}, {}}); break;
        }
    }
    return out;
}

std::vector<Sub> Knots(const SkPath& path) {
    std::vector<Sub> subs;
    Sub cur;
    auto finish = [&](bool closed) {
        auto& k = cur.knots;
        if (k.empty()) return;
        if (closed && k.size() > 2 && k.back().p == k.front().p && !k.back().hasOut) {
            const Knot last = k.back();
            k.pop_back();
            if (!k.front().hasIn && last.hasIn) {
                k.front().in = last.in;
                k.front().hasIn = true;
            }
        }
        cur.closed = closed;
        subs.push_back(std::move(cur));
        cur = Sub();
    };
    for (const Piece& s : Pieces(path)) {
        if (s.verb == SkPathVerb::kMove) finish(false);
        if (s.verb == SkPathVerb::kClose) { finish(true); continue; }
        Knot next;
        next.p = s.p;
        if (s.verb == SkPathVerb::kCubic && !cur.knots.empty()) {
            Knot& last = cur.knots.back();
            if (s.c1 != last.p) { last.out = s.c1; last.hasOut = true; }
            if (s.c2 != s.p) { next.in = s.c2; next.hasIn = true; }
        }
        cur.knots.push_back(next);
    }
    finish(false);
    return subs;
}

}

void DrawPointEdit(SkCanvas* canvas, const Layer& layer, const SkMatrix& toTarget, float px, int code, float t) {
    code -= kPointEdit;
    const int selContour = (code >> 20) & 0x7f, selPoint = (code >> 4) & 0xffff, selHandle = code & 0xf;
    const SkColor accent = SkColorSetRGB(0xff, 0x8f, 0x66);
    SkPaint paint;
    paint.setAntiAlias(true);
    auto map = [&](SkPoint q) { return toTarget.mapPoint(q); };
    auto line = [&](SkPoint a, SkPoint b) {
        paint.setStyle(SkPaint::kStroke_Style);
        paint.setStrokeWidth(4.5f * px);
        paint.setColor(SK_ColorBLACK);
        canvas->drawLine(a, b, paint);
        paint.setStrokeWidth(2.2f * px);
        paint.setColor(SK_ColorWHITE);
        canvas->drawLine(a, b, paint);
        paint.setStyle(SkPaint::kFill_Style);
    };
    auto dot = [&](SkPoint at, float radius, SkColor fill) {
        paint.setColor(SK_ColorBLACK);
        canvas->drawCircle(at, (radius + 3) * px, paint);
        paint.setColor(fill);
        canvas->drawCircle(at, radius * px, paint);
    };
    auto square = [&](SkPoint at, float half, SkColor fill) {
        paint.setColor(SK_ColorBLACK);
        canvas->drawRect(SkRect::MakeXYWH(at.fX - (half + 2.5f) * px, at.fY - (half + 2.5f) * px, (half + 2.5f) * 2 * px, (half + 2.5f) * 2 * px), paint);
        paint.setColor(fill);
        canvas->drawRect(SkRect::MakeXYWH(at.fX - half * px, at.fY - half * px, half * 2 * px, half * 2 * px), paint);
    };
    paint.setStyle(SkPaint::kFill_Style);
    int contour = 0;
    for (const PathContour& c : layer.contours) {
        std::vector<Sub> subs;
        if (c.knots.empty()) {
            subs = Knots(c.path);
        } else {
            Sub sub;
            sub.closed = c.closed;
            for (const PathKnot& k : c.knots) {
                Knot knot;
                knot.p = {k.p.get(0, t, 0), k.p.get(1, t, 0)};
                if (k.hasIn) { knot.in = knot.p + SkPoint{k.in.get(0, t, 0), k.in.get(1, t, 0)}; knot.hasIn = true; }
                if (k.hasOut) { knot.out = knot.p + SkPoint{k.out.get(0, t, 0), k.out.get(1, t, 0)}; knot.hasOut = true; }
                sub.knots.push_back(knot);
            }
            subs.push_back(std::move(sub));
        }
        for (const Sub& sub : subs) {
            const int n = (int)sub.knots.size();
            for (int i = 0; i < n; i++) {
                const Knot& k = sub.knots[i];
                const bool chosen = contour == selContour && i == selPoint;
                const bool near = contour == selContour && n > 0 &&
                                  (abs(i - selPoint) <= 1 || (sub.closed && abs(i - selPoint) == n - 1));
                const SkPoint at = map(k.p);
                if (near) {
                    if (k.hasIn) {
                        const SkPoint h = map(k.in);
                        line(at, h);
                        square(h, 6.5f, chosen && selHandle == 0 ? accent : SK_ColorWHITE);
                    }
                    if (k.hasOut) {
                        const SkPoint h = map(k.out);
                        line(at, h);
                        square(h, 6.5f, chosen && selHandle == 2 ? accent : SK_ColorWHITE);
                    }
                }
                dot(at, chosen && selHandle == 1 ? 11.5f : 8.5f, chosen && selHandle == 1 ? accent : SK_ColorWHITE);
            }
            contour++;
        }
    }
}

std::string OutlineSvg(const SkPath& path) {
    auto num = [](float v) {
        char b[32];
        snprintf(b, sizeof b, "%.4f", v);
        std::string n = b;
        n.erase(n.find_last_not_of('0') + 1);
        if (n.back() == '.') n.pop_back();
        return n == "-0" ? std::string("0") : n;
    };
    auto pt = [&](SkPoint p) { return num(p.fX) + " " + num(p.fY); };
    std::string d;
    for (const Piece& s : Pieces(path)) {
        switch (s.verb) {
            case SkPathVerb::kMove: d += "M " + pt(s.p); break;
            case SkPathVerb::kCubic: d += "C " + pt(s.c1) + ", " + pt(s.c2) + ", " + pt(s.p); break;
            case SkPathVerb::kClose: d += "Z"; break;
            default: d += "L " + pt(s.p); break;
        }
    }
    return d;
}

}
