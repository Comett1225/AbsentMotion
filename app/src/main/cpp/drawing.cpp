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

void DrawDrawing(SkCanvas* canvas, const Layer& layer, float opacity) {
    bool erases = false;
    for (const FreehandStroke& s : layer.strokes) erases |= s.tool == kEraser;
    if (erases) canvas->saveLayer(nullptr, nullptr);
    for (const FreehandStroke& s : layer.strokes) {
        if (s.geometry.isEmpty()) continue;
        SkPaint paint;
        paint.setAntiAlias(true);
        if (s.tool == kEraser) {
            paint.setBlendMode(SkBlendMode::kClear);
        } else {
            paint.setColor4f({s.color[1], s.color[2], s.color[3], std::clamp(s.color[0] * opacity, 0.0f, 1.0f)});
        }
        if (!s.filled) {
            paint.setStyle(SkPaint::kStroke_Style);
            paint.setStrokeWidth(s.width);
            paint.setStrokeCap(SkPaint::kRound_Cap);
            paint.setStrokeJoin(SkPaint::kRound_Join);
        }
        canvas->drawPath(s.geometry, paint);
    }
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

SkPath DrawingOutline(const Layer& layer) {
    SkPathBuilder b;
    for (const FreehandStroke& s : layer.strokes) {
        if (s.tool == kEraser || s.geometry.isEmpty()) continue;
        b.addPath(s.filled ? s.geometry : StrokedOutline(s));
    }
    return b.detach();
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
