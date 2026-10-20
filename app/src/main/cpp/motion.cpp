#include "scene.h"
#include "include/core/SkSamplingOptions.h"
#include "include/effects/SkImageFilters.h"

#include "include/core/SkPathMeasure.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string_view>
#include <vector>

#include "include/core/SkColor.h"
#include "include/core/SkPathMeasure.h"

namespace am {
namespace {

constexpr double kTau = 6.283185307179586;

const char* const kMotionIds[] = {"absent.autoshake", "absent.randomdisplace", "absent.randomjitter", "absent.oscillate",
                                  "absent.swing", "absent.spin", "absent.pulsesize", "absent.pulseopacity",
                                  "absent.echo", "absent.timequant", "absent.shutterblur", "absent.movealongpath"};

bool gFastShutter = false;

const Anim* Find(const LayerEffect& e, const char* name) {
    for (const Property& p : e.props) {
        if (p.name == name) return &p.anim;
    }
    return nullptr;
}

float Param(const LayerEffect& e, const char* name, float t, float fallback, int i = 0) {
    const Anim* a = Find(e, name);
    return a ? a->get(i, t, fallback) : fallback;
}

double Integral(const LayerEffect& e, const char* name, const Layer& l, double seconds, float fallback) {
    const Anim* a = Find(e, name);
    if (!a) return fallback * seconds;
    const double length = (l.endTime - l.startTime) / 1000.0;
    if (a->keys.empty() || !(length > 0)) return a->get(0, 0, fallback) * seconds;
    auto f = [&](double s) { return (double)a->get(0, (float)(s / length), fallback); };
    std::vector<double> marks = {0};
    for (const Keyframe& k : a->keys) {
        const double s = k.t * length;
        if (s > 0 && s < seconds) marks.push_back(s);
    }
    marks.push_back(seconds);
    std::sort(marks.begin(), marks.end());
    double sum = 0;
    for (size_t i = 0; i + 1 < marks.size(); i++) {
        const double a0 = marks[i], b = marks[i + 1], h = (b - a0) / 8;
        if (!(h > 0)) continue;
        double part = f(a0) + f(b);
        for (int k = 1; k < 8; k++) part += (k % 2 ? 4 : 2) * f(a0 + k * h);
        sum += part * h / 3;
    }
    return sum;
}

uint64_t Mix(uint64_t x) {
    x += 0x9e3779b97f4a7c15ull;
    x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ull;
    x = (x ^ (x >> 27)) * 0x94d049bb133111ebull;
    return x ^ (x >> 31);
}

uint64_t SeedOf(const Layer& l, const LayerEffect& e, float t, uint64_t channel) {
    const int64_t seed = std::llround((double)Param(e, "seed", t, 1) * 1000);
    return Mix(Mix((uint64_t)l.id) ^ Mix((uint64_t)seed + 0x51ed27ull) ^ (channel * 0x632be59bd9b4e019ull));
}

double Unit(uint64_t seed, int64_t k) { return (Mix(seed ^ Mix((uint64_t)k)) >> 11) * (1.0 / 9007199254740992.0); }

double Noise(double x, double y) { return std::clamp(SimplexNoise2(x, y), -1.0, 1.0); }

double Plane(float seed, int channel) { return seed * 7.919 + channel * 101.37 + 0.5; }

double Wave(double cycles, float kind) {
    const double f = cycles - std::floor(cycles);
    if (kind >= 0.5f) return 1 - 4 * std::fabs(std::fmod(f + 0.25, 1.0) - 0.5);
    return std::sin(kTau * f);
}

SkVector Toward(float degrees, double distance) {
    const double a = degrees * kTau / 360;
    return {(float)(std::cos(a) * distance), (float)(std::sin(a) * distance)};
}

double Seconds(const Layer& l, double timeMs) { return std::max(0.0, (l.steppedMs(timeMs) - l.startTime) / 1000.0); }

const char* const kRepeatIds[] = {"absent.repeat", "absent.linearrepeat", "absent.radialrepeat", "absent.gridrepeat",
                                  "absent.repeatalongpath", "absent.scatterfield"};

constexpr size_t kMaxCopies = 25000;

float ParamAt(const LayerEffect& e, const char* name, int i, float t, float fallback) {
    const Anim* a = Find(e, name);
    return a ? a->get(i, t, fallback) : fallback;
}

int CountOf(const LayerEffect& e, float t, float fallback, int most) {
    return std::clamp((int)std::lround(Param(e, "count", t, fallback)), 0, most);
}

SkColor4f ColourOf(const LayerEffect& e, const char* name, float t, SkColor4f fallback) {
    const Anim* a = Find(e, name);
    if (!a) return fallback;
    return {a->get(1, t, fallback.fR), a->get(2, t, fallback.fG), a->get(3, t, fallback.fB), a->get(0, t, fallback.fA)};
}

float Lerp(float a, float b, float u) { return a + (b - a) * u; }

struct Spot {
    SkPoint at = {0, 0};
    float turn = 0, size = 1, u = 0;
    SkMatrix outer = SkMatrix::I();
};

float Eased(int easing, float x) {
    switch (easing) {
        case 1: return x * x;
        case 2: return 1 - (1 - x) * (1 - x);
        case 3: return x * x * (3 - 2 * x);
        default: return x;
    }
}

void Ranged(const Layer& l, const LayerEffect& e, float t, std::vector<Spot> spots, std::vector<RepeatCopy>* out) {
    const SkPoint shift = {ParamAt(e, "rangePosition", 0, t, 0), ParamAt(e, "rangePosition", 1, t, 0)};
    const float scale = std::max(Param(e, "rangeScale", t, 1), 0.0f), turn = Param(e, "rangeRotation", t, 0);
    const float opacity = std::clamp(Param(e, "rangeOpacity", t, 1), 0.0f, 1.0f);
    const SkColor4f fill = ColourOf(e, "fillColor", t, {1, 1, 1, 1});
    const float fillAmount = std::clamp(Param(e, "fillAmount", t, 0), 0.0f, 1.0f);
    const float offset = Param(e, "rangeOffset", t, 0);
    const float start = Param(e, "rangeStart", t, 0) + offset, end = Param(e, "rangeEnd", t, 1) + offset;
    const int easing = (int)std::lround(Param(e, "easing", t, 0));
    if (Param(e, "randomOrder", t, 0) >= 0.5f && spots.size() > 1) {
        const uint64_t seed = SeedOf(l, e, t, 9);
        std::vector<size_t> order(spots.size());
        for (size_t i = 0; i < order.size(); i++) order[i] = i;
        std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return Unit(seed, (int64_t)a) < Unit(seed, (int64_t)b); });
        for (size_t k = 0; k < order.size(); k++) spots[order[k]].u = (float)k / (order.size() - 1);
    }
    for (auto s = spots.rbegin(); s != spots.rend(); ++s) {
        const float x = end - start > 1e-6f ? std::clamp((s->u - start) / (end - start), 0.0f, 1.0f) : (s->u >= start ? 1.0f : 0.0f);
        const float w = Eased(easing, x);
        RepeatCopy c;
        c.outer = s->outer;
        c.local = SkMatrix::Translate(s->at.fX + shift.fX * w, s->at.fY + shift.fY * w);
        c.local.preRotate(s->turn + turn * w);
        const float k = s->size * Lerp(1, scale, w);
        c.local.preScale(k, k);
        c.alpha = Lerp(1, opacity, w);
        c.tint = fill;
        c.tinted = fillAmount * w;
        out->push_back(c);
    }
}

float Along(int i, int n) { return n > 1 ? (float)i / (n - 1) : 0.5f; }

void Basic(const LayerEffect& e, float t, std::vector<RepeatCopy>* out) {
    const int n = CountOf(e, t, 10, 9999);
    SkMatrix each = SkMatrix::Translate(ParamAt(e, "offset", 0, t, 0), ParamAt(e, "offset", 1, t, 0));
    each.preRotate(Param(e, "angle", t, 0));
    const float scale = std::max(Param(e, "scale", t, 1), 0.0f);
    each.preScale(scale, scale);
    const float alpha = std::clamp(Param(e, "alpha", t, 1), 0.0f, 1.0f);
    const double step = std::max(Param(e, "time", t, 0), 0.0f) * 1000.0;
    std::vector<RepeatCopy> copies(n);
    SkMatrix m = SkMatrix::I();
    for (int i = 0; i < n; i++) {
        copies[i].local = m;
        copies[i].shiftMs = -step * i;
        copies[i].alpha = n > 1 ? Lerp(1, alpha, (float)i / (n - 1)) : 1;
        m.preConcat(each);
    }
    out->insert(out->end(), copies.rbegin(), copies.rend());
}

void Linear(const LayerEffect& e, float t, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 5, 150);
    const SkPoint span = {ParamAt(e, "position", 0, t, 500), ParamAt(e, "position", 1, t, 0)};
    for (int i = 0; i < n; i++) {
        Spot s;
        s.u = Along(i, n);
        s.at = {(s.u - 0.5f) * span.fX, (s.u - 0.5f) * span.fY};
        if (n == 1) s.u = 0;
        spots->push_back(s);
    }
}

void Grid(const LayerEffect& e, float t, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 5, 150);
    const SkPoint span = {ParamAt(e, "position", 0, t, 500), ParamAt(e, "position", 1, t, 500)};
    const float stagger = Param(e, "stagger", t, 0);
    const int total = n * n;
    for (int row = 0; row < n; row++) {
        for (int col = 0; col < n; col++) {
            Spot s;
            s.at = {(Along(col, n) - 0.5f) * span.fX + (row % 2 ? stagger : 0), (Along(row, n) - 0.5f) * span.fY};
            s.u = total > 1 ? (float)(row * n + col) / (total - 1) : 0;
            spots->push_back(s);
        }
    }
}

void Radial(const LayerEffect& e, float t, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 8, 150);
    const float radius = Param(e, "radius", t, 300), orientation = Param(e, "orientation", t, 0);
    const float angle = Param(e, "angle", t, 0), sweep = Param(e, "sweep", t, 360);
    const float size = std::max(Param(e, "scale", t, 1), 0.0f);
    const bool round = std::fabs(sweep) >= 360 - 1e-3f;
    for (int i = 0; i < n; i++) {
        Spot s;
        const float a = angle + (n > 1 ? sweep * i / (round ? n : n - 1) : 0);
        const double r = a * kTau / 360;
        s.at = {(float)(std::sin(r) * radius), (float)(-std::cos(r) * radius)};
        s.turn = a + orientation;
        s.size = size;
        s.u = n > 1 ? (float)i / (n - 1) : 0;
        spots->push_back(s);
    }
}

void OnPath(const LayerEffect& e, float t, const RepeatSpace& space, std::vector<Spot>* spots) {
    const int n = CountOf(e, t, 25, 150);
    if (n == 0) return;
    float total = 0;
    int contours = 0;
    bool closed = false;
    SkPathMeasure measure(space.below, false);
    do {
        const float length = measure.getLength();
        if (!(length > 0)) continue;
        total += length;
        closed = ++contours == 1 && measure.isClosed();
    } while (measure.nextContour());
    if (!(total > 0)) {
        spots->push_back(Spot());
        return;
    }
    const float start = std::clamp(Param(e, "start", t, 0), 0.0f, 1.0f), end = std::clamp(Param(e, "end", t, 1), 0.0f, 1.0f);
    const float phase = Param(e, "phase", t, 0), span = end - start;
    const float spacing = n > 1 ? span / (closed && span >= 1 - 1e-4f ? n : n - 1) : 0;
    const bool tangent = Param(e, "tangent", t, 0) >= 0.5f;
    std::vector<std::pair<float, float>> placed;
    for (int i = 0; i < n; i++) {
        float f = start + phase + spacing * i;
        if (closed) f -= std::floor(f);
        else if (f < -1e-4f || f > 1 + 1e-4f) continue;
        placed.push_back({std::clamp(f, 0.0f, 1.0f) * total, n > 1 ? (float)i / (n - 1) : 0});
    }
    std::vector<size_t> order(placed.size());
    for (size_t i = 0; i < order.size(); i++) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return placed[a].first < placed[b].first; });
    std::vector<Spot> found(placed.size());
    std::vector<bool> ok(placed.size());
    SkPathMeasure contour(space.below, false);
    float contourStart = 0, contourLength = contour.getLength();
    for (size_t k : order) {
        const float d = placed[k].first;
        while (contourStart + contourLength < d - 1e-3f && contour.nextContour()) {
            contourStart += contourLength;
            contourLength = contour.getLength();
        }
        SkPoint p;
        SkVector v;
        if (!contour.getPosTan(std::clamp(d - contourStart, 0.0f, contourLength), &p, &v)) continue;
        Spot s;
        s.u = placed[k].second;
        s.outer = SkMatrix::Translate(p.fX, p.fY);
        if (tangent) s.outer.preRotate(std::atan2(v.fY, v.fX) * 360 / (float)kTau);
        s.outer.preTranslate(-space.anchor.fX, -space.anchor.fY);
        found[k] = s;
        ok[k] = true;
    }
    for (size_t k = 0; k < found.size(); k++) {
        if (ok[k]) spots->push_back(found[k]);
    }
}

SkColor4f Gradient3(const SkColor4f c[3], float u, bool hue) {
    u = std::clamp(u, 0.0f, 1.0f) * 2;
    const int i = std::min((int)u, 1);
    const float f = u - i;
    const SkColor4f a = c[i], b = c[i + 1];
    if (!hue) return {Lerp(a.fR, b.fR, f), Lerp(a.fG, b.fG, f), Lerp(a.fB, b.fB, f), 1};
    SkScalar ha[3], hb[3];
    SkColorToHSV(a.toSkColor(), ha);
    SkColorToHSV(b.toSkColor(), hb);
    float dh = hb[0] - ha[0];
    if (dh > 180) dh -= 360;
    if (dh < -180) dh += 360;
    SkScalar mixed[3] = {std::fmod(ha[0] + dh * f + 360, 360.0f), Lerp(ha[1], hb[1], f), Lerp(ha[2], hb[2], f)};
    return SkColor4f::FromColor(SkHSVToColor(mixed));
}

void Scatter(const Layer& l, const LayerEffect& e, float t, const RepeatSpace& space, std::vector<RepeatCopy>* out) {
    const float w = std::max(space.width, 1.0f), h = std::max(space.height, 1.0f), focal = std::max(w, h);
    const float cell = focal * 0.2f * std::clamp(Param(e, "scale", t, 1), 0.05f, 20.0f);
    const float density = std::clamp(Param(e, "density", t, 0.3f), 0.0f, 1.0f);
    const float nearZ = cell, farZ = std::max(std::clamp(Param(e, "amount", t, 15), 0.0f, 40.0f) * cell, nearZ + 1);
    const SkPoint shift = {ParamAt(e, "position", 0, t, 0), ParamAt(e, "position", 1, t, 0)};
    const float depth = Param(e, "z", t, 0), evolution = Param(e, "evolution", t, 0);
    const float slack = std::clamp(Param(e, "slack", t, 1), 0.0f, 1.0f), size = std::max(Param(e, "size", t, 0.5f), 0.0f);
    const float sizeVariance = std::clamp(Param(e, "sizeVariance", t, 0.5f), 0.0f, 1.0f);
    const float angle = Param(e, "angle", t, 0), angleVariance = std::clamp(Param(e, "angleVariance", t, 0), 0.0f, 1.0f);
    const float fade = std::clamp(Param(e, "fade", t, 1), 0.0f, 1.0f), alpha = std::clamp(Param(e, "alpha", t, 1), 0.0f, 1.0f);
    const SkBlendMode modes[] = {SkBlendMode::kSrcOver, SkBlendMode::kPlus, SkBlendMode::kScreen, SkBlendMode::kMultiply,
                                 SkBlendMode::kLighten, SkBlendMode::kDarken, SkBlendMode::kOverlay, SkBlendMode::kDifference};
    const SkBlendMode mode = modes[std::clamp((int)std::lround(Param(e, "blending", t, 0)), 0, 7)];
    const int coloring = (int)std::lround(Param(e, "coloring", t, 0));
    const SkColor4f colours[3] = {ColourOf(e, "color1", t, SkColor4f::FromColor(0xffff55aa)), ColourOf(e, "color2", t, SkColor4f::FromColor(0xff55aaff)),
                                  ColourOf(e, "color3", t, SkColor4f::FromColor(0xffffaa55))};
    const bool lockLuma = Param(e, "lockLuminance", t, 1) >= 0.5f, lockSaturation = Param(e, "lockSaturation", t, 0) >= 0.5f;
    const bool hue = Param(e, "interpolateHue", t, 0) >= 0.5f;
    const SkBlendMode tintMode = lockSaturation ? SkBlendMode::kHue : lockLuma ? SkBlendMode::kColor : SkBlendMode::kSrcIn;
    if (!(density > 0) || !(alpha > 0)) return;
    const uint64_t seed = SeedOf(l, e, t, 11);
    std::vector<std::pair<float, RepeatCopy>> found;
    const int z0 = (int)std::floor((nearZ + depth) / cell) - 1, z1 = (int)std::ceil((farZ + depth) / cell) + 1;
    for (int iz = z0; iz <= z1 && found.size() < kMaxCopies; iz++) {
        const float reach = ((iz + 1.5f) * cell - depth) / focal;
        if (!(reach > 0)) continue;
        const float hx = w / 2 * reach + cell + space.radius, hy = h / 2 * reach + cell + space.radius;
        const int x0 = (int)std::floor((shift.fX - hx) / cell), x1 = (int)std::ceil((shift.fX + hx) / cell);
        const int y0 = (int)std::floor((shift.fY - hy) / cell), y1 = (int)std::ceil((shift.fY + hy) / cell);
        for (int iy = y0; iy <= y1; iy++) {
            for (int ix = x0; ix <= x1 && found.size() < kMaxCopies; ix++) {
                const uint64_t k = Mix(seed ^ Mix((uint64_t)(int64_t)ix * 0x9e37ull) ^ Mix((uint64_t)(int64_t)iy * 0x85ebull + 7) ^ Mix((uint64_t)(int64_t)iz * 0xc2b2ull + 13));
                if (Unit(k, 0) >= density) continue;
                auto jitter = [&](int channel) {
                    return slack * (float)(Unit(k, channel) - 0.5 + 0.35 * SimplexNoise2(evolution * 0.5 + Unit(k, channel + 10) * 97, channel * 3.7));
                };
                const float z = (iz + 0.5f + jitter(3)) * cell - depth;
                if (z <= nearZ || z >= farZ) continue;
                const float k3 = focal / z;
                const SkPoint world = {(ix + 0.5f + jitter(1)) * cell - shift.fX, (iy + 0.5f + jitter(2)) * cell - shift.fY};
                const SkPoint at = {w / 2 + world.fX * k3, h / 2 + world.fY * k3};
                const float scale = size * std::max(0.0f, 1 + sizeVariance * (float)(2 * Unit(k, 4) - 1)) * k3 * cell * 0.5f / std::max(space.radius, 1.0f);
                const float reachOut = space.radius * scale;
                if (!(scale > 0) || at.fX + reachOut < 0 || at.fX - reachOut > w || at.fY + reachOut < 0 || at.fY - reachOut > h) continue;
                const float depthU = (z - nearZ) / (farZ - nearZ);
                RepeatCopy c;
                c.outer = SkMatrix::Translate(at.fX, at.fY);
                c.outer.preRotate(angle + angleVariance * 180 * (float)(2 * Unit(k, 5) - 1));
                c.outer.preScale(scale, scale);
                c.outer.preTranslate(-space.anchor.fX, -space.anchor.fY);
                const float nearFade = std::clamp((z - nearZ) / cell, 0.0f, 1.0f);
                c.alpha = alpha * (1 - fade * depthU) * nearFade * nearFade * (3 - 2 * nearFade);
                if (!(c.alpha > 0.002f)) continue;
                c.mode = mode;
                if (coloring > 0) {
                    c.tint = Gradient3(colours, coloring == 1 ? (float)Unit(k, 6) : depthU, hue);
                    c.tinted = 1;
                    c.tintMode = tintMode;
                }
                found.push_back({z, c});
            }
        }
    }
    std::stable_sort(found.begin(), found.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    for (const auto& f : found) out->push_back(f.second);
}

}

bool MotionEffect(const std::string& id) {
    return std::any_of(std::begin(kMotionIds), std::end(kMotionIds), [&](const char* m) { return id == m; });
}

void ApplyMotion(const Layer& l, double timeMs, Placement* p) {
    if (l.placed) return;
    const float t = l.localTime(timeMs);
    const double s = Seconds(l, timeMs);
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id.compare(0, 7, "absent.") != 0) continue;
        const std::string_view id = std::string_view(e.id).substr(7);
        if (id == "autoshake") {
            const double x = Integral(e, "frequency", l, s, 2) + Param(e, "evolution", t, 0);
            const float seed = Param(e, "seed", t, 0), m = Param(e, "magnitude", t, 50), angle = Param(e, "angle", t, 45);
            p->location += Toward(angle, m * Noise(x, Plane(seed, 0))) + Toward(angle + 90, m * Param(e, "slack", t, 0.25f) * Noise(x, Plane(seed, 1)));
            p->z += (float)(Param(e, "zshake", t, 0) * Noise(x, Plane(seed, 2)));
        } else if (id == "randomdisplace") {
            const float scatter = Param(e, "scatter", t, 0.5f) / 100, m = Param(e, "magnitude", t, 50);
            const double x = Param(e, "evolution", t, 0) + scatter * p->location.fX;
            const double y = Plane(Param(e, "seed", t, 0), 0) + scatter * p->location.fY;
            p->location += SkVector{(float)(m * Noise(x, y)), (float)(m * Noise(x + 57.31, y + 23.17))};
        } else if (id == "randomjitter") {
            const int64_t k = (int64_t)std::floor(Integral(e, "frequency", l, s, 30) + 1e-6);
            const uint64_t seed = Mix((uint64_t)std::llround((double)Param(e, "seed", t, 0) * 1000) + 0x51ed27ull);
            const float m = Param(e, "magnitude", t, 25), angle = Param(e, "angle", t, 45);
            const double along = ((k & 1) ? -1 : 1) * (0.35 + 0.65 * Unit(seed, k));
            p->location += Toward(angle, m * along) + Toward(angle + 90, m * Param(e, "slack", t, 0) * (2 * Unit(seed ^ 1, k) - 1));
            p->z += (float)(Param(e, "zjitter", t, 0) * (2 * Unit(seed ^ 2, k) - 1));
        } else if (id == "oscillate") {
            const double c = Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0) / 2;
            const float kind = Param(e, "wave", t, 0), m = Param(e, "magnitude", t, 25), angle = Param(e, "angle", t, 45);
            const long direction = std::lround(Param(e, "direction", t, 0));
            if (direction == 1) p->z += (float)(m * Wave(c, kind));
            else if (direction == 2) p->location += Toward(angle, m * Wave(c + 0.25, kind)) + Toward(angle + 90, m * Wave(c, kind));
            else p->location += Toward(angle, m * Wave(c, kind));
        } else if (id == "swing") {
            const double w = Wave(Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0) / 2, Param(e, "wave", t, 0));
            const float a1 = Param(e, "angle1", t, -30), a2 = Param(e, "angle2", t, 30);
            p->rotation += (float)((a1 + a2) / 2 + (a2 - a1) / 2 * w);
        } else if (id == "spin") {
            p->rotation += (float)std::fmod(6 * Integral(e, "rpm", l, s, 60), 360.0);
        } else if (id == "pulsesize") {
            const double w = Wave(Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0) / 2, Param(e, "wave", t, 0));
            const float lo = Param(e, "shrink", t, 0.9f), hi = Param(e, "grow", t, 1.1f);
            const float k = (float)std::max(0.0, (lo + hi) / 2 + (hi - lo) / 2 * w);
            p->scale = {p->scale.fX * k, p->scale.fY * k};
        }
    }
}

float MotionOpacity(const Layer& l, double timeMs) {
    float opacity = 1;
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.pulseopacity") continue;
        const float t = l.localTime(timeMs);
        const double w = Wave(Integral(e, "frequency", l, Seconds(l, timeMs), 2) + Param(e, "phase", t, 0) / 2, Param(e, "wave", t, 0));
        opacity *= 1 - std::clamp(Param(e, "strength", t, 1), 0.0f, 1.0f) * (float)(0.5 - 0.5 * w);
    }
    return opacity;
}

bool FollowsPath(const Layer& l) {
    return !l.placed && std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && e.id == "absent.movealongpath"; });
}

void FollowPath(const Layer& l, double timeMs, const SkPath& path, Placement* p) {
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.movealongpath") continue;
        const float t = l.localTime(timeMs);
        std::vector<double> lengths;
        double total = 0;
        SkPathMeasure measure(path, false);
        do {
            lengths.push_back(measure.getLength());
            total += lengths.back();
        } while (measure.nextContour());
        if (!(total > 0)) return;
        double at = std::clamp((double)Param(e, "progress", t, 0), 0.0, 1.0) * total;
        SkPathMeasure walk(path, false);
        for (size_t i = 0; i + 1 < lengths.size() && (at > lengths[i] || !(lengths[i] > 0)); i++) {
            at -= lengths[i];
            walk.nextContour();
        }
        SkPoint pos;
        SkVector tangent;
        if (!walk.getPosTan((SkScalar)at, &pos, &tangent)) return;
        SkVector out{tangent.fY, -tangent.fX};
        if (path.contains(pos.fX + out.fX * 0.5f, pos.fY + out.fY * 0.5f)) out = -out;
        const float turn = (Param(e, "rotate", t, 1) >= 0.5f ? (float)(std::atan2(tangent.fY, tangent.fX) * 360 / kTau) : 0.0f) + Param(e, "orientation", t, 0);
        const SkVector shift = SkMatrix::RotateDeg(turn).mapVector(Param(e, "offset", t, 0, 0), Param(e, "offset", t, 0, 1));
        p->location = pos + out * Param(e, "inset", t, 0) + shift;
        p->rotation += turn;
        return;
    }
}

bool EchoOf(const Layer& l, double timeMs, Echo* echo) {
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.echo") continue;
        const float t = l.localTime(timeMs);
        echo->count = std::clamp((int)std::floor(Param(e, "count", t, 1) + 1e-4f), 0, 20);
        echo->seconds = std::clamp(Param(e, "seconds", t, 0.5f), -60.0f, 60.0f);
        echo->alpha = std::clamp(Param(e, "alpha", t, 1), 0.0f, 1.0f);
        echo->atop = Param(e, "composite", t, 0) >= 0.5f;
        return echo->count > 0 && echo->seconds != 0;
    }
    return false;
}

bool ShutterBlurOf(const Layer& l, double timeMs, ShutterBlur* blur) {
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.shutterblur") continue;
        const float t = l.localTime(timeMs);
        blur->angle = std::clamp(Param(e, "shutter", t, 180), 0.0f, 360.0f);
        blur->samples = std::clamp((int)std::lround(Param(e, "samples", t, 8)), 2, 32);
        blur->fast = gFastShutter;
        return blur->angle > 0;
    }
    return false;
}

void SetFastShutterBlur(bool fast) { gFastShutter = fast; }

sk_sp<SkImageFilter> SmearFilter(SkVector moved) {
    const float length = moved.length();
    if (!(length >= 0.5f) || !std::isfinite(length)) return nullptr;
    const float angle = atan2f(moved.fY, moved.fX) * 180 / 3.14159265f;
    const SkSamplingOptions linear(SkFilterMode::kLinear);
    auto turned = SkImageFilters::MatrixTransform(SkMatrix::RotateDeg(-angle), linear, nullptr);
    auto blurred = SkImageFilters::Blur(length * 0.3f, 0, SkTileMode::kDecal, turned);
    return SkImageFilters::MatrixTransform(SkMatrix::RotateDeg(angle), linear, blurred);
}

bool RepeatEffect(const std::string& id) {
    return std::any_of(std::begin(kRepeatIds), std::end(kRepeatIds), [&](const char* m) { return id == m; });
}

bool HasRepeat(const Layer& l) {
    return std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && RepeatEffect(e.id); });
}

bool RepeatsAlongPath(const Layer& l) {
    return std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden && e.id == "absent.repeatalongpath"; });
}

void RepeatCopies(const Layer& l, double timeMs, const RepeatSpace& space, std::vector<RepeatCopy>* copies) {
    const float t = l.localTime(timeMs);
    std::vector<RepeatCopy> result(1);
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || !RepeatEffect(e.id)) continue;
        const std::string_view id = std::string_view(e.id).substr(7);
        std::vector<RepeatCopy> made;
        std::vector<Spot> spots;
        if (id == "repeat") Basic(e, t, &made);
        else if (id == "scatterfield") Scatter(l, e, t, space, &made);
        else {
            if (id == "linearrepeat") Linear(e, t, &spots);
            else if (id == "gridrepeat") Grid(e, t, &spots);
            else if (id == "radialrepeat") Radial(e, t, &spots);
            else OnPath(e, t, space, &spots);
            Ranged(l, e, t, std::move(spots), &made);
        }
        std::vector<RepeatCopy> next;
        next.reserve(std::min(kMaxCopies, made.size() * result.size()));
        for (const RepeatCopy& m : made) {
            for (const RepeatCopy& r : result) {
                if (next.size() >= kMaxCopies) break;
                RepeatCopy c = r;
                c.outer = m.outer * r.outer;
                c.local = m.local * r.local;
                c.shiftMs = r.shiftMs + m.shiftMs;
                c.alpha = r.alpha * m.alpha;
                if (m.tinted > r.tinted) {
                    c.tint = m.tint;
                    c.tinted = m.tinted;
                    c.tintMode = m.tintMode;
                }
                if (m.mode != SkBlendMode::kSrcOver) c.mode = m.mode;
                next.push_back(c);
            }
        }
        result = std::move(next);
    }
    *copies = std::move(result);
}

void ParseSteps(Layer* l) {
    for (const LayerEffect& e : l->effects) {
        if (e.hidden || e.id != "absent.timequant") continue;
        const Anim* fps = Find(e, "fps");
        l->steps = fps && !fps->empty() ? *fps : Anim{{8.0f}};
        return;
    }
}

}
