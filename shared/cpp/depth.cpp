#include "depth.h"

#include <algorithm>
#include <cmath>

#include "include/core/SkBlender.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkPath.h"
#include "include/core/SkVertices.h"

namespace am::depth {
namespace {

thread_local Pass* gActive = nullptr;

constexpr int kMaxPlanes = 64;
constexpr int kMaxParts = 64;
constexpr int kMaxSplits = 4096;
constexpr long kMaxPairs = 1L << 21;
constexpr size_t kMaxShapes = 1 << 16;
constexpr double kTolerance = 1e-4;

struct Vertex {
    SkPoint s;
    double q;
    SkV3 w;
};

struct Shape {
    double a = 0, b = 0, c = 0, q = 0;
    SkPoint at = {0, 0};
    SkRect bounds = SkRect::MakeEmpty();
    int first = 0, count = 0, cost = 1;
    bool plane = false, splitter = false;

    double height(SkPoint p) const { return a * p.fX + b * p.fY + c; }
};

struct Fragment {
    int shape, first, count, cut, cuts, next;
    bool whole;
};

struct Node {
    double a, b, c;
    int shape, front, back, flat;
};

struct State {
    std::vector<Shape> shapes;
    std::vector<Vertex> vertices, scratch;
    std::vector<SkPoint> ring, other;
    std::vector<Fragment> fragments;
    std::vector<Node> nodes;
    std::vector<int> leaves, gather, pool;
    std::vector<std::pair<int, int>> work, sides;
    std::vector<Cut> cuts;
    int root = -1, parts = 0, splits = 0;
    bool broken = false, failed = false;
};

thread_local State gState;

bool Touch(const SkRect& a, const SkRect& b) {
    return a.fLeft <= b.fRight && b.fLeft <= a.fRight && a.fTop <= b.fBottom && b.fTop <= a.fBottom;
}

double Turn(SkPoint o, SkPoint a, SkPoint b) { return (double)(a.fX - o.fX) * (b.fY - o.fY) - (double)(a.fY - o.fY) * (b.fX - o.fX); }

double Winding(const State& st, const Shape& s) {
    double area = 0;
    for (int i = 0; i < s.count; i++) {
        const SkPoint a = st.vertices[s.first + i].s, b = st.vertices[s.first + (i + 1) % s.count].s;
        area += (double)a.fX * b.fY - (double)b.fX * a.fY;
    }
    return area >= 0 ? 1 : -1;
}

bool Within(const State& st, const Shape& p, SkPoint x) {
    const double wind = Winding(st, p);
    for (int i = 0; i < p.count; i++) {
        if (Turn(st.vertices[p.first + i].s, st.vertices[p.first + (i + 1) % p.count].s, x) * wind < 0) return false;
    }
    return true;
}

bool Overlap(State& st, const Shape& x, const Shape& p, double* lo, double* hi, double* scale) {
    st.ring.clear();
    for (int i = 0; i < x.count; i++) st.ring.push_back(st.vertices[x.first + i].s);
    const double wind = Winding(st, p);
    for (int i = 0; i < p.count && st.ring.size() >= 3; i++) {
        const SkPoint a = st.vertices[p.first + i].s, b = st.vertices[p.first + (i + 1) % p.count].s;
        st.other.clear();
        for (size_t k = 0; k < st.ring.size(); k++) {
            const SkPoint u = st.ring[k], v = st.ring[(k + 1) % st.ring.size()];
            const double du = Turn(a, b, u) * wind, dv = Turn(a, b, v) * wind;
            if (du >= 0) st.other.push_back(u);
            if ((du > 0 && dv < 0) || (du < 0 && dv > 0)) st.other.push_back(u + (v - u) * (float)(du / (du - dv)));
        }
        st.ring.swap(st.other);
    }
    if (st.ring.size() < 3) return false;
    *lo = INFINITY;
    *hi = -INFINITY;
    *scale = 0;
    for (const SkPoint o : st.ring) {
        const double under = p.height(o), e = x.height(o) - under;
        *lo = std::min(*lo, e);
        *hi = std::max(*hi, e);
        *scale = std::max(*scale, std::fabs(under));
    }
    return true;
}

bool Mark(State& st) {
    int planes = 0;
    for (const Shape& s : st.shapes) planes += s.plane;
    if (planes == 0 || planes > kMaxPlanes) return false;
    long pairs = 0;
    bool any = false;
    for (int p = 0; p < (int)st.shapes.size(); p++) {
        if (!st.shapes[p].plane) continue;
        for (int x = 0; x < (int)st.shapes.size() && !st.shapes[p].splitter; x++) {
            const Shape& P = st.shapes[p];
            const Shape& X = st.shapes[x];
            if (x == p || !Touch(P.bounds, X.bounds)) continue;
            if (++pairs > kMaxPairs) return false;
            double lo, hi, scale;
            if (X.count == 0) {
                if (!Within(st, P, X.at)) continue;
                lo = hi = X.q - P.height(X.at);
                scale = std::max(std::fabs(X.q), std::fabs(P.height(X.at)));
            } else if (!Overlap(st, X, P, &lo, &hi, &scale)) {
                continue;
            }
            const double tolerance = kTolerance * scale;
            if (hi > tolerance && lo < -tolerance) {
                st.shapes[p].splitter = true;
                if (X.plane) st.shapes[x].splitter = true;
            } else if ((hi > tolerance || lo < -tolerance) && (hi > tolerance) != (x > p)) {
                st.shapes[p].splitter = true;
            }
        }
        any = any || st.shapes[p].splitter;
    }
    return any;
}

int NewLeaf(State& st) {
    st.leaves.push_back(-1);
    return ~(int)(st.leaves.size() - 1);
}

int Side(const State& st, const Fragment& f, const Node& n) {
    const Shape& s = st.shapes[f.shape];
    double lo = INFINITY, hi = -INFINITY, scale = 0;
    if (f.count == 0) {
        lo = hi = s.q - (n.a * s.at.fX + n.b * s.at.fY + n.c);
        scale = std::fabs(s.q);
    }
    for (int i = 0; i < f.count; i++) {
        const Vertex& v = st.vertices[f.first + i];
        const double e = v.q - (n.a * v.s.fX + n.b * v.s.fY + n.c);
        lo = std::min(lo, e);
        hi = std::max(hi, e);
        scale = std::max(scale, std::fabs(v.q));
    }
    const double tolerance = kTolerance * scale;
    if (hi <= tolerance && lo >= -tolerance) return 0;
    if (lo >= -tolerance) return 1;
    if (hi <= tolerance) return -1;
    return 2;
}

int Divide(State& st, int index, const Node& n, double tolerance, bool front) {
    const Fragment parent = st.fragments[index];
    const int first = (int)st.vertices.size();
    const int count = (int)st.scratch.size();
    for (int i = 0; i < count; i++) {
        const Vertex u = st.scratch[i], v = st.scratch[(i + 1) % count];
        double eu = u.q - (n.a * u.s.fX + n.b * u.s.fY + n.c), ev = v.q - (n.a * v.s.fX + n.b * v.s.fY + n.c);
        if (!front) {
            eu = -eu;
            ev = -ev;
        }
        if (eu >= -tolerance) st.vertices.push_back(u);
        if ((eu > tolerance && ev < -tolerance) || (eu < -tolerance && ev > tolerance)) {
            const double k = eu / (eu - ev);
            st.vertices.push_back({u.s + (v.s - u.s) * (float)k, u.q + (v.q - u.q) * k, u.w + (v.w - u.w) * (float)k});
        }
    }
    const int made = (int)st.vertices.size() - first;
    if (made < 3) {
        st.vertices.resize(first);
        return -1;
    }
    Fragment f{parent.shape, first, made, (int)st.cuts.size(), 0, -1, false};
    if (st.shapes[parent.shape].plane) {
        for (int i = 0; i < parent.cuts; i++) {
            const Cut kept = st.cuts[parent.cut + i];
            st.cuts.push_back(kept);
        }
        const Shape& s = st.shapes[parent.shape];
        const double a = s.a - n.a, b = s.b - n.b, c = s.c - n.c, length = std::hypot(a, b);
        st.cuts.push_back({(float)(a / length), (float)(b / length), (float)(c / length), front});
        f.cuts = parent.cuts + 1;
    }
    st.fragments.push_back(f);
    return (int)st.fragments.size() - 1;
}

int Centre(const State& st, const Fragment& f, const Node& n) {
    double e = 0;
    for (int i = 0; i < f.count; i++) {
        const Vertex& v = st.vertices[f.first + i];
        e += v.q - (n.a * v.s.fX + n.b * v.s.fY + n.c);
    }
    return e >= 0 ? 1 : -1;
}

Node NodeOf(const State& st, int f) {
    const Shape& s = st.shapes[st.fragments[f].shape];
    return {s.a, s.b, s.c, st.fragments[f].shape, -1, -1, -1};
}

int Cleave(State& st, int f, const Node& n, int* front, int* back) {
    if (const int side = Side(st, st.fragments[f], n); side != 2) return side;
    const Shape& s = st.shapes[st.fragments[f].shape];
    const double length = std::hypot(s.a - n.a, s.b - n.b);
    if (s.plane && (st.parts += s.cost) > kMaxParts) {
        st.failed = true;
        return 0;
    }
    if ((!s.plane && st.splits >= kMaxSplits) || !(length > 0) || !std::isfinite(length)) return Centre(st, st.fragments[f], n);
    if (!s.plane) st.splits++;
    const Fragment whole = st.fragments[f];
    double scale = 0;
    st.scratch.assign(st.vertices.begin() + whole.first, st.vertices.begin() + whole.first + whole.count);
    for (const Vertex& v : st.scratch) scale = std::max(scale, std::fabs(v.q));
    *front = Divide(st, f, n, kTolerance * scale, true);
    *back = Divide(st, f, n, kTolerance * scale, false);
    return 2;
}

int Build(State& st, int begin, int end) {
    if (begin == end || st.failed) return NewLeaf(st);
    int best = begin;
    long fewest = -1;
    for (int i = begin; i < end && fewest != 0; i++) {
        const Node n = NodeOf(st, st.pool[i]);
        long spans = 0;
        for (int j = begin; j < end && (fewest < 0 || spans < fewest); j++) spans += j != i && Side(st, st.fragments[st.pool[j]], n) == 2;
        if (fewest < 0 || spans < fewest) {
            fewest = spans;
            best = i;
        }
    }
    Node node = NodeOf(st, st.pool[best]);
    node.flat = st.pool[best];
    st.fragments[node.flat].next = -1;
    const int id = (int)st.nodes.size();
    st.nodes.push_back(node);
    st.sides.clear();
    for (int j = begin; j < end; j++) {
        if (j == best) continue;
        const int f = st.pool[j];
        int front = -1, back = -1;
        const int side = Cleave(st, f, node, &front, &back);
        if (st.failed) return NewLeaf(st);
        if (side == 0) {
            st.fragments[f].next = st.nodes[id].flat;
            st.nodes[id].flat = f;
        } else if (side == 2) {
            if (front >= 0) st.sides.push_back({front, 1});
            if (back >= 0) st.sides.push_back({back, 0});
        } else {
            st.sides.push_back({f, side > 0});
        }
    }
    const int frontBegin = (int)st.pool.size();
    for (const auto& [f, side] : st.sides) if (side) st.pool.push_back(f);
    const int backBegin = (int)st.pool.size();
    for (const auto& [f, side] : st.sides) if (!side) st.pool.push_back(f);
    const int backEnd = (int)st.pool.size();
    const int front = Build(st, frontBegin, backBegin);
    st.nodes[id].front = front;
    const int back = Build(st, backBegin, backEnd);
    st.nodes[id].back = back;
    return id;
}

bool Place(State& st, int fragment) {
    st.work.clear();
    st.work.push_back({fragment, st.root});
    while (!st.work.empty()) {
        const auto [f, ref] = st.work.back();
        st.work.pop_back();
        if (ref < 0) {
            st.fragments[f].next = st.leaves[~ref];
            st.leaves[~ref] = f;
            continue;
        }
        const Node n = st.nodes[ref];
        int front = -1, back = -1;
        int side = Cleave(st, f, n, &front, &back);
        if (st.failed) return false;
        if (side == 0) side = st.fragments[f].shape > n.shape ? 1 : -1;
        if (side == 2) {
            if (front >= 0) st.work.push_back({front, n.front});
            if (back >= 0) st.work.push_back({back, n.back});
            continue;
        }
        st.work.push_back({f, side > 0 ? n.front : n.back});
    }
    return true;
}

void Gather(State& st, int head, Order* order) {
    st.gather.clear();
    for (int f = head; f >= 0; f = st.fragments[f].next) st.gather.push_back(f);
    std::sort(st.gather.begin(), st.gather.end(), [&](int a, int b) {
        const int sa = st.fragments[a].shape, sb = st.fragments[b].shape;
        return sa != sb ? sa < sb : a < b;
    });
    for (int index : st.gather) {
        const Fragment& f = st.fragments[index];
        Part part;
        part.shape = f.shape;
        part.cut = (int)order->cuts.size();
        part.cuts = f.cuts;
        order->cuts.insert(order->cuts.end(), st.cuts.begin() + f.cut, st.cuts.begin() + f.cut + f.cuts);
        if (!f.whole && !st.shapes[f.shape].plane && f.count >= 3) {
            part.vertex = (int)order->weights.size();
            part.vertices = f.count;
            for (int i = 0; i < f.count; i++) order->weights.push_back(st.vertices[f.first + i].w);
        }
        order->parts.push_back(part);
    }
}

void Emit(State& st, int ref, Order* order) {
    if (ref < 0) return Gather(st, st.leaves[~ref], order);
    const Node n = st.nodes[ref];
    Emit(st, n.back, order);
    Gather(st, n.flat, order);
    Emit(st, n.front, order);
}

void Add(State& st, Shape s, const SkPoint* points, const double* q, int count) {
    s.first = (int)st.vertices.size();
    s.count = count;
    s.bounds = count ? SkRect::MakeLTRB(points[0].fX, points[0].fY, points[0].fX, points[0].fY) : SkRect::MakeLTRB(s.at.fX, s.at.fY, s.at.fX, s.at.fY);
    for (int i = 0; i < count; i++) {
        st.vertices.push_back({points[i], q[i], {i == 0 ? 1.0f : 0.0f, i == 1 ? 1.0f : 0.0f, i == 2 ? 1.0f : 0.0f}});
        s.bounds = SkRect::MakeLTRB(std::min(s.bounds.fLeft, points[i].fX), std::min(s.bounds.fTop, points[i].fY),
                                    std::max(s.bounds.fRight, points[i].fX), std::max(s.bounds.fBottom, points[i].fY));
        if (!points[i].isFinite() || !std::isfinite(q[i])) st.broken = true;
    }
    if (!std::isfinite(s.a) || !std::isfinite(s.b) || !std::isfinite(s.c) || !std::isfinite(s.q) || !s.at.isFinite()) st.broken = true;
    st.shapes.push_back(s);
}

}

double Flat::nearness(float depth) const {
    if (!std::isfinite(depth)) return depth;
    return orthographic ? -depth / focal : focal / std::max(depth, 1e-3f);
}

void Begin() {
    State& st = gState;
    st.shapes.clear();
    st.vertices.clear();
    st.broken = false;
}

void AddPoint(SkPoint at, double q) {
    Shape s;
    s.at = at;
    s.q = q;
    Add(gState, s, nullptr, nullptr, 0);
}

void AddPlane(const Flat& flat) {
    if (!flat.valid || flat.count < 3) return AddPoint(flat.at, flat.q);
    Shape s;
    s.a = flat.plane[0];
    s.b = flat.plane[1];
    s.c = flat.plane[2];
    s.plane = true;
    s.cost = std::max(flat.cost, 1);
    s.at = flat.at;
    s.q = flat.q;
    double q[8];
    for (int i = 0; i < flat.count; i++) q[i] = s.height(flat.corners[i]);
    Add(gState, s, flat.corners.data(), q, flat.count);
}

void AddTriangle(const SkPoint p[3], const double q[3]) {
    const double x1 = p[1].fX - p[0].fX, y1 = p[1].fY - p[0].fY, x2 = p[2].fX - p[0].fX, y2 = p[2].fY - p[0].fY;
    const double det = x1 * y2 - y1 * x2, e1 = q[1] - q[0], e2 = q[2] - q[0];
    Shape s;
    s.at = {(p[0].fX + p[1].fX + p[2].fX) / 3, (p[0].fY + p[1].fY + p[2].fY) / 3};
    s.q = (q[0] + q[1] + q[2]) / 3;
    if (!(std::fabs(det) > 1e-6)) return Add(gState, s, nullptr, nullptr, 0);
    s.a = (e1 * y2 - y1 * e2) / det;
    s.b = (x1 * e2 - e1 * x2) / det;
    s.c = q[0] - s.a * p[0].fX - s.b * p[0].fY;
    Add(gState, s, p, q, 3);
}

bool Solve(Order* order) {
    State& st = gState;
    order->parts.clear();
    order->cuts.clear();
    order->weights.clear();
    for (Shape& s : st.shapes) s.splitter = false;
    if (st.broken || !Mark(st)) return false;
    st.nodes.clear();
    st.leaves.clear();
    st.fragments.clear();
    st.cuts.clear();
    st.pool.clear();
    st.parts = st.splits = 0;
    st.failed = false;
    for (int i = 0; i < (int)st.shapes.size(); i++) {
        st.fragments.push_back({i, st.shapes[i].first, st.shapes[i].count, 0, 0, -1, true});
        if (st.shapes[i].splitter) st.pool.push_back(i);
    }
    st.root = Build(st, 0, (int)st.pool.size());
    if (st.failed) return false;
    for (int i = 0; i < (int)st.shapes.size(); i++) {
        if (!st.shapes[i].splitter && !Place(st, i)) return false;
    }
    Emit(st, st.root, order);
    return true;
}

bool Inside(const std::vector<Cut>& cuts, SkPoint p) {
    for (const Cut& c : cuts) {
        if ((c.a * p.fX + c.b * p.fY + c.c >= 0) != c.front) return false;
    }
    return true;
}

void Clip(SkCanvas* canvas, const Cut* cuts, int count) {
    thread_local std::vector<SkPoint> ring, kept;
    const SkMatrix m = canvas->getTotalMatrix();
    const float scale = m.hasPerspective() ? 0 : m.getMinScale(), pixel = scale > 0 ? 1 / scale : 1;
    const SkRect area = canvas->getLocalClipBounds().makeOutset(pixel * 2, pixel * 2);
    ring.assign({{area.fLeft, area.fTop}, {area.fRight, area.fTop}, {area.fRight, area.fBottom}, {area.fLeft, area.fBottom}});
    for (int k = 0; k < count && ring.size() >= 3; k++) {
        const Cut& c = cuts[k];
        const double sign = c.front ? 1 : -1, offset = c.front ? c.c : pixel - c.c;
        kept.clear();
        for (size_t i = 0; i < ring.size(); i++) {
            const SkPoint u = ring[i], v = ring[(i + 1) % ring.size()];
            const double eu = sign * (c.a * u.fX + c.b * u.fY) + offset, ev = sign * (c.a * v.fX + c.b * v.fY) + offset;
            if (eu >= 0) kept.push_back(u);
            if ((eu > 0 && ev < 0) || (eu < 0 && ev > 0)) kept.push_back(u + (v - u) * (float)(eu / (eu - ev)));
        }
        ring.swap(kept);
    }
    canvas->clipPath(SkPath::Polygon({ring.data(), ring.size() >= 3 ? ring.size() : 0}, true), SkClipOp::kIntersect, true);
}

Pass* Active() { return gActive; }

Scope::Scope(Pass* pass) : fPrevious(gActive) { gActive = pass; }

Scope::~Scope() { gActive = fPrevious; }

int Pass::add(const SkMatrix& matrix, const SkPaint& paint) {
    sources.emplace_back();
    sources.back().matrix = matrix;
    sources.back().paint = paint;
    sources.back().flat = flat;
    return (int)sources.size() - 1;
}

void Pass::triangle(int source, const SkPoint p[3], const SkPoint uv[3], const SkColor color[3], float at, const float* depths) {
    Source& s = sources[source];
    items.push_back({at, source, (int)s.positions.size() / 3});
    s.positions.insert(s.positions.end(), p, p + 3);
    s.colors.insert(s.colors.end(), color, color + 3);
    if (uv) s.uvs.insert(s.uvs.end(), uv, uv + 3);
    if (depths) s.depths.insert(s.depths.end(), depths, depths + 3);
    else s.depths.insert(s.depths.end(), 3, at);
}

void Pass::sprite(int source, const SkRSXform& xform, const SkRect& cell, SkColor color, float at) {
    Source& s = sources[source];
    items.push_back({at, source, (int)s.xforms.size()});
    s.xforms.push_back(xform);
    s.cells.push_back(cell);
    s.colors.push_back(color);
}

void Pass::picture(sk_sp<SkPicture> recorded, const SkMatrix& matrix, float at) {
    if (!recorded) return;
    const int s = add(matrix, look);
    sources[s].picture = std::move(recorded);
    items.push_back({at, s, 0});
}

void Pass::whole(std::function<void(SkCanvas*)> draw, float at, const Flat& surface) {
    const int s = add(SkMatrix::I(), SkPaint());
    sources[s].whole = std::move(draw);
    sources[s].flat = surface;
    items.push_back({at, s, 0});
}

SkPaint Pass::styled(SkPaint paint) const {
    if (!look.isSrcOver()) paint.setBlender(look.refBlender());
    return paint;
}

namespace {

bool Arrange(const Pass& pass, SkCanvas* canvas, Order* order) {
    if (pass.items.size() > kMaxShapes) return false;
    if (std::none_of(pass.sources.begin(), pass.sources.end(), [](const Source& s) { return (s.whole || s.picture) && s.flat.valid && s.flat.count >= 3; })) return false;
    const SkMatrix base = canvas->getTotalMatrix();
    SkMatrix inverse;
    if (base.hasPerspective() || !base.invert(&inverse)) return false;
    Begin();
    for (const Item& item : pass.items) {
        const Source& s = pass.sources[item.source];
        if (!s.flat.valid) return false;
        if (s.whole || s.picture) {
            AddPlane(s.flat);
            continue;
        }
        const SkMatrix m = SkMatrix::Concat(inverse, s.matrix);
        if (m.hasPerspective()) return false;
        if (!s.xforms.empty()) {
            const SkRSXform& x = s.xforms[item.index];
            const SkRect& cell = s.cells[item.index];
            const float cx = cell.width() / 2, cy = cell.height() / 2;
            AddPoint(m.mapPoint({x.fSCos * cx - x.fSSin * cy + x.fTx, x.fSSin * cx + x.fSCos * cy + x.fTy}), s.flat.nearness(item.depth));
            continue;
        }
        const int k = item.index * 3;
        SkPoint p[3];
        double q[3];
        for (int i = 0; i < 3; i++) {
            p[i] = m.mapPoint(s.positions[k + i]);
            q[i] = s.flat.nearness(s.depths[k + i]);
        }
        AddTriangle(p, q);
    }
    return Solve(order);
}

SkColor Blend(const SkColor c[3], SkV3 w) {
    const auto channel = [&](int shift) {
        const float v = ((c[0] >> shift) & 0xff) * w.x + ((c[1] >> shift) & 0xff) * w.y + ((c[2] >> shift) & 0xff) * w.z;
        return (SkColor)std::clamp((int)std::lround(v), 0, 255) << shift;
    };
    return channel(24) | channel(16) | channel(8) | channel(0);
}

}

void Pass::draw(SkCanvas* canvas) {
    for (Item& item : items) {
        if (!std::isfinite(item.depth)) item.depth = -INFINITY;
    }
    std::stable_sort(items.begin(), items.end(), [](const Item& a, const Item& b) { return a.depth > b.depth; });
    Order order;
    const bool arranged = Arrange(*this, canvas, &order);
    const size_t count = arranged ? order.parts.size() : items.size();
    const auto itemAt = [&](size_t k) -> const Item& { return items[arranged ? order.parts[k].shape : k]; };
    const auto clip = [&](size_t k) {
        if (arranged && order.parts[k].cuts) Clip(canvas, order.cuts.data() + order.parts[k].cut, order.parts[k].cuts);
    };
    std::vector<SkRSXform> xforms;
    std::vector<SkRect> cells;
    std::vector<SkColor> colors;
    for (size_t start = 0, end; start < count; start = end) {
        const Source& s = sources[itemAt(start).source];
        for (end = start + 1; end < count && itemAt(end).source == itemAt(start).source; end++) {}
        if (s.whole) {
            for (size_t k = start; k < end; k++) {
                canvas->save();
                clip(k);
                s.whole(canvas);
                canvas->restore();
            }
            continue;
        }
        if (s.picture) {
            const bool plain = s.paint.isSrcOver() && s.paint.getAlphaf() >= 1;
            for (size_t k = start; k < end; k++) {
                canvas->save();
                clip(k);
                canvas->setMatrix(s.matrix);
                canvas->drawPicture(s.picture.get(), nullptr, plain ? nullptr : &s.paint);
                canvas->restore();
            }
            continue;
        }
        canvas->save();
        canvas->setMatrix(s.matrix);
        if (!s.xforms.empty()) {
            xforms.clear();
            cells.clear();
            colors.clear();
            for (size_t i = start; i < end; i++) {
                const int k = itemAt(i).index;
                xforms.push_back(s.xforms[k]);
                cells.push_back(s.cells[k]);
                colors.push_back(s.colors[k]);
            }
            canvas->drawAtlas(s.sprite.get(), xforms, cells, colors, SkBlendMode::kModulate, s.sampling, nullptr, &s.paint);
        } else {
            int n = 0;
            for (size_t i = start; i < end; i++) n += arranged && order.parts[i].vertices ? order.parts[i].vertices - 2 : 1;
            const bool textured = !s.uvs.empty();
            SkVertices::Builder builder(SkVertices::kTriangles_VertexMode, n * 3, 0,
                                        SkVertices::kHasColors_BuilderFlag | (textured ? SkVertices::kHasTexCoords_BuilderFlag : 0));
            int out = 0;
            for (size_t i = start; i < end; i++) {
                const int k = itemAt(i).index * 3;
                const Part* part = arranged && order.parts[i].vertices ? &order.parts[i] : nullptr;
                if (!part) {
                    std::copy_n(&s.positions[k], 3, builder.positions() + out);
                    std::copy_n(&s.colors[k], 3, builder.colors() + out);
                    if (textured) std::copy_n(&s.uvs[k], 3, builder.texCoords() + out);
                    out += 3;
                    continue;
                }
                const auto put = [&](SkV3 w) {
                    const SkPoint* p = &s.positions[k];
                    builder.positions()[out] = {p[0].fX * w.x + p[1].fX * w.y + p[2].fX * w.z, p[0].fY * w.x + p[1].fY * w.y + p[2].fY * w.z};
                    builder.colors()[out] = Blend(&s.colors[k], w);
                    if (textured) {
                        const SkPoint* uv = &s.uvs[k];
                        builder.texCoords()[out] = {uv[0].fX * w.x + uv[1].fX * w.y + uv[2].fX * w.z, uv[0].fY * w.x + uv[1].fY * w.y + uv[2].fY * w.z};
                    }
                    out++;
                };
                const SkV3* w = &order.weights[part->vertex];
                for (int j = 1; j + 1 < part->vertices; j++) {
                    put(w[0]);
                    put(w[j]);
                    put(w[j + 1]);
                }
            }
            canvas->drawVertices(builder.detach(), s.mode, s.paint);
        }
        canvas->restore();
    }
}

}
