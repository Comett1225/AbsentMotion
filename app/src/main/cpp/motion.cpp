#include "scene.h"
#include "include/core/SkSamplingOptions.h"
#include "include/effects/SkImageFilters.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string_view>
#include <vector>

namespace am {
namespace {

constexpr double kTau = 6.283185307179586;

const char* const kMotionIds[] = {"absent.autoshake", "absent.randomdisplace", "absent.randomjitter", "absent.oscillate",
                                  "absent.swing", "absent.spin", "absent.pulsesize", "absent.pulseopacity",
                                  "absent.stretchaxis", "absent.echo", "absent.timequant", "absent.shutterblur"};

bool gFastShutter = false;

const Anim* Find(const LayerEffect& e, const char* name) {
    for (const Property& p : e.props) {
        if (p.name == name) return &p.anim;
    }
    return nullptr;
}

float Param(const LayerEffect& e, const char* name, float t, float fallback) {
    const Anim* a = Find(e, name);
    return a ? a->get(0, t, fallback) : fallback;
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

double Smooth(uint64_t seed, double x) { return std::clamp(SimplexNoise2(x, (seed >> 48) * 0.618034 + 0.5), -1.0, 1.0); }

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

}

bool MotionEffect(const std::string& id) {
    return std::any_of(std::begin(kMotionIds), std::end(kMotionIds), [&](const char* m) { return id == m; });
}

void ApplyMotion(const Layer& l, double timeMs, Placement* p) {
    const float t = l.localTime(timeMs);
    const double s = Seconds(l, timeMs);
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id.compare(0, 7, "absent.") != 0) continue;
        const std::string_view id = std::string_view(e.id).substr(7);
        if (id == "stretchaxis") {
            const float angle = Param(e, "angle", t, 0), amount = std::max(Param(e, "amount", t, 1.5f), 0.0f);
            SkMatrix m = SkMatrix::RotateDeg(-angle);
            m.postScale(amount, 1);
            m.postRotate(angle);
            p->stretch.postConcat(m);
            continue;
        }
        if (l.placed) continue;
        if (id == "autoshake") {
            const double x = Integral(e, "frequency", l, s, 6);
            const double magnitude = Param(e, "magnitude", t, 20);
            p->location += SkVector{(float)(magnitude * Smooth(SeedOf(l, e, t, 1), x)), (float)(magnitude * Smooth(SeedOf(l, e, t, 2), x))};
            p->rotation += (float)(Param(e, "rotation", t, 3) * Smooth(SeedOf(l, e, t, 3), x));
        } else if (id == "randomdisplace") {
            const double x = Integral(e, "speed", l, s, 1);
            const double magnitude = Param(e, "magnitude", t, 50);
            p->location += SkVector{(float)(magnitude * Smooth(SeedOf(l, e, t, 4), x)), (float)(magnitude * Smooth(SeedOf(l, e, t, 5), x))};
        } else if (id == "randomjitter") {
            const int64_t k = (int64_t)std::floor(Integral(e, "rate", l, s, 8));
            const double side = (k & 1) ? -1 : 1;
            const double amount = side * (0.35 + 0.65 * Unit(SeedOf(l, e, t, 6), k));
            p->location += Toward(Param(e, "angle", t, 0), Param(e, "magnitude", t, 25) * amount);
        } else if (id == "oscillate") {
            const double w = Wave(Integral(e, "frequency", l, s, 2) + Param(e, "phase", t, 0), Param(e, "wave", t, 0));
            p->location += Toward(Param(e, "angle", t, 45), Param(e, "magnitude", t, 25) * w);
        } else if (id == "swing") {
            const double w = Wave(Integral(e, "frequency", l, s, 1) + Param(e, "phase", t, 0), Param(e, "wave", t, 0));
            p->rotation += (float)(Param(e, "angle", t, 15) * w);
        } else if (id == "spin") {
            const double turned = Integral(e, "speed", l, s, 90);
            p->rotation += (float)std::fmod(Param(e, "direction", t, 0) >= 0.5f ? -turned : turned, 360.0);
        } else if (id == "pulsesize") {
            const double w = Wave(Integral(e, "frequency", l, s, 1) + Param(e, "phase", t, 0), Param(e, "wave", t, 0));
            const float k = (float)std::max(0.0, 1 + Param(e, "amount", t, 0.2f) * w);
            p->scale = {p->scale.fX * k, p->scale.fY * k};
        }
    }
}

float MotionOpacity(const Layer& l, double timeMs) {
    float opacity = 1;
    for (const LayerEffect& e : l.effects) {
        if (e.hidden || e.id != "absent.pulseopacity") continue;
        const float t = l.localTime(timeMs);
        const double w = Wave(Integral(e, "frequency", l, Seconds(l, timeMs), 1) + Param(e, "phase", t, 0) + 0.25, Param(e, "wave", t, 0));
        const float low = std::clamp(Param(e, "minimum", t, 0), 0.0f, 1.0f);
        opacity *= low + (1 - low) * (float)(0.5 + 0.5 * w);
    }
    return opacity;
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

void ParseSteps(Layer* l) {
    for (const LayerEffect& e : l->effects) {
        if (e.hidden || e.id != "absent.timequant") continue;
        const Anim* fps = Find(e, "fps");
        l->steps = fps && !fps->empty() ? *fps : Anim{{8.0f}};
        return;
    }
}

}
