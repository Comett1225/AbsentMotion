#include "particles.h"

#include <algorithm>
#include <cmath>
#include <unordered_map>

namespace am::particles {
namespace {

constexpr double kTau = 6.283185307179586;
constexpr double kEarly = 1e-3;

double Unit(uint64_t key, uint64_t channel) {
    return (Mix(key ^ Mix(channel * 0x9e3779b97f4a7c15ull + 0x632be59bd9b4e019ull)) >> 11) * (1.0 / 9007199254740992.0);
}

double Period(double bpm) { return 60.0 / std::clamp(std::isfinite(bpm) ? bpm : 120.0, 20.0, 400.0); }

float Smooth(float x) {
    x = std::clamp(x, 0.0f, 1.0f);
    return x * x * (3 - 2 * x);
}

float Clamp01(float v) { return std::isfinite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f; }

void Turn(float* c, double turns) {
    const double a = turns * kTau, k = std::cos(a), s = std::sin(a) / std::sqrt(3.0), m = (1 - k) / 3;
    const double r = c[0], g = c[1], b = c[2];
    c[0] = (float)std::clamp(r * (k + m) + g * (m - s) + b * (m + s), 0.0, 1.0);
    c[1] = (float)std::clamp(r * (m + s) + g * (k + m) + b * (m - s), 0.0, 1.0);
    c[2] = (float)std::clamp(r * (m - s) + g * (m + s) + b * (k + m), 0.0, 1.0);
}

}

uint64_t Mix(uint64_t x) {
    x += 0x9e3779b97f4a7c15ull;
    x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ull;
    x = (x ^ (x >> 27)) * 0x94d049bb133111ebull;
    return x ^ (x >> 31);
}

int Capacity(int kind) { return kind == kLayer ? 400 : 1500; }

float Envelope(double since, double decay) {
    const double x = since / std::max(std::isfinite(decay) ? decay : 0.4, 0.01);
    if (!(x >= 0) || x >= 1) return x < 0 ? 1.0f : 0.0f;
    return (float)((1 - x) * (1 - x));
}

Beat LastBeat(const std::vector<double>& beats, double seconds, int every, double offset, double bpm) {
    Beat out;
    every = std::max(every, 1);
    const double at = seconds - offset + kEarly;
    int64_t k;
    double when;
    if (beats.empty()) {
        const double p = Period(bpm);
        if (!(at >= 0)) return out;
        k = (int64_t)std::floor(at / p);
        k -= k % every;
        when = k * p;
    } else {
        k = (int64_t)(std::upper_bound(beats.begin(), beats.end(), at) - beats.begin()) - 1;
        if (k < 0) return out;
        k -= k % every;
        when = beats[(size_t)k];
    }
    out.since = std::max(0.0, seconds - offset - when);
    out.index = k / every;
    return out;
}

void BeatsBetween(const std::vector<double>& beats, double from, double to, int every, double offset, double bpm,
                  std::vector<std::pair<double, int64_t>>* out) {
    out->clear();
    every = std::max(every, 1);
    const double lo = from - offset, hi = to - offset + kEarly;
    if (!(hi >= lo)) return;
    if (beats.empty()) {
        const double p = Period(bpm);
        int64_t k = std::max<int64_t>(0, (int64_t)std::ceil(lo / p));
        k += (every - k % every) % every;
        for (; k * p <= hi && out->size() < 4096; k += every) out->push_back({k * p + offset, k});
        return;
    }
    for (size_t k = (size_t)(std::lower_bound(beats.begin(), beats.end(), lo) - beats.begin()); k < beats.size() && beats[k] <= hi; k++) {
        if (k % (size_t)every == 0) out->push_back({beats[k] + offset, (int64_t)k});
    }
}

void Simulate(const Field& f, double t, std::vector<Particle>* out) {
    out->clear();
    if (!f.at || !f.emit || !std::isfinite(t)) return;
    const Settings now = f.at(t);
    const size_t cap = (size_t)Capacity(now.kind);
    const double longest = std::clamp(std::isfinite(f.lifeMost) ? (double)f.lifeMost : 0.0, 0.02, 120.0);
    const double from = t - longest, fps = std::clamp(f.fps, 1.0, 240.0);
    std::unordered_map<int64_t, Settings> made;
    const auto settled = [&](double b) -> const Settings& {
        if (!f.animated) return now;
        const int64_t frame = (int64_t)std::floor(b * fps);
        auto found = made.find(frame);
        if (found == made.end()) found = made.emplace(frame, f.at(frame / fps)).first;
        return found->second;
    };
    const auto amount = [&](double b, size_t room) {
        const float n = settled(b).count;
        return std::isfinite(n) ? std::min(room, (size_t)std::lround(std::clamp(n, 0.0f, 100000.0f))) : size_t{0};
    };
    std::vector<std::pair<double, uint64_t>> births;
    if (now.emission == kContinuous) {
        const double most = std::isfinite(f.rateMost) ? std::min((double)f.rateMost, 1000.0) : 0.0;
        if (most > 0) {
            const double keep = std::min(1.0, cap / (most * longest));
            const int64_t last = (int64_t)std::floor(t * most);
            for (int64_t k = (int64_t)std::floor(from * most) - 1; k <= last; k++) {
                const uint64_t key = Mix(f.seed ^ Mix((uint64_t)k));
                const double b = (k + Unit(key, 0)) / most;
                if (b > t || b < from || (!now.prewarm && b < 0)) continue;
                if (Unit(key, 1) * most >= settled(b).rate * keep) continue;
                births.push_back({b, key});
            }
        }
    } else if (now.emission == kBurst) {
        const double every = now.every > 0 ? std::max((double)now.every, 0.05) : 0.0, first = std::isfinite(now.start) ? now.start : 0.0;
        int64_t j0 = 0, j1 = first <= t && first >= from ? 0 : -1;
        if (every > 0) {
            j0 = std::max<int64_t>(0, (int64_t)std::ceil((from - first) / every));
            j1 = (int64_t)std::floor((t - first) / every);
        }
        const size_t share = std::max<size_t>(1, cap / (size_t)(every > 0 ? std::ceil(longest / every) : 1));
        for (int64_t j = j0; j <= j1; j++) {
            const double b = first + j * every;
            const size_t n = amount(b, share);
            for (size_t m = 0; m < n; m++) births.push_back({b, Mix(f.seed ^ Mix((uint64_t)j * 0x100000001b3ull + m) ^ 0xb5ull)});
        }
    } else {
        std::vector<std::pair<double, int64_t>> beats;
        BeatsBetween(f.beats, f.origin + from, f.origin + t, now.beatEvery, 0, now.bpm, &beats);
        size_t room = cap;
        for (auto it = beats.rbegin(); it != beats.rend() && room > 0; ++it) {
            const double b = std::min(t, it->first - f.origin);
            if (!now.prewarm && b < 0) continue;
            const size_t n = amount(b, room);
            room -= n;
            for (size_t m = 0; m < n; m++) births.push_back({b, Mix(f.seed ^ Mix((uint64_t)it->second * 0x100000001b3ull + m) ^ 0xbeull)});
        }
        std::stable_sort(births.begin(), births.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    }
    const double gx = (std::isfinite(now.wind) ? now.wind : 0.0) * f.unit, gy = (std::isfinite(now.gravity) ? now.gravity : 0.0) * f.unit;
    const double k = std::isfinite(now.drag) ? std::clamp((double)now.drag, 0.0, 50.0) : 0.0;
    const double swirl = std::isfinite(now.swirl) ? std::max(0.0f, now.swirl) * f.unit : 0.0;
    const double swirlSpeed = std::isfinite(now.swirlSpeed) ? now.swirlSpeed : 0.0;
    const float fadeIn = Clamp01(now.fadeIn), fadeOut = Clamp01(now.fadeOut), twinkle = Clamp01(now.twinkle);
    const float endSize = std::isfinite(now.endSize) ? std::max(0.0f, now.endSize) : 1.0f;
    out->reserve(std::min(births.size(), cap));
    for (const auto& [b, key] : births) {
        const Settings& s = settled(b);
        const double life = std::max(0.02, s.lifetime * (1 + Clamp01(s.lifeVariation) * (2 * Unit(key, 2) - 1)));
        const double age = t - b;
        if (!(age >= 0) || age >= life) continue;
        const float u = (float)(age / life);
        float ex = 0, ey = 0;
        f.emit(b, (float)Unit(key, 3), (float)Unit(key, 4), s.direction, &ex, &ey);
        const double heading = (s.direction + s.spread * (Unit(key, 5) - 0.5)) * kTau / 360;
        const double speed = std::max(0.0, s.speed * (1 + Clamp01(s.speedVariation) * (2 * Unit(key, 6) - 1))) * f.unit;
        const double vx = speed * std::cos(heading), vy = speed * std::sin(heading);
        double f1, f2, fade;
        if (k * age < 1e-4) {
            f1 = age - 0.5 * k * age * age;
            f2 = 0.5 * age * age - k * age * age * age / 6;
            fade = 1 - k * age;
        } else {
            fade = std::exp(-k * age);
            f1 = (1 - fade) / k;
            f2 = (age - f1) / k;
        }
        double x = ex + vx * f1 + gx * f2, y = ey + vy * f1 + gy * f2;
        double dx = vx * fade + gx * f1, dy = vy * fade + gy * f1;
        if (swirl > 0 && swirlSpeed != 0) {
            const double radius = swirl * (0.5 + Unit(key, 7));
            const double w = kTau * swirlSpeed * (0.6 + 0.8 * Unit(key, 8)) * (Unit(key, 9) < 0.5 ? -1 : 1);
            const double phase = kTau * Unit(key, 10), turn = w * age + phase;
            x += radius * (std::cos(turn) - std::cos(phase));
            y += radius * (std::sin(turn) - std::sin(phase));
            dx -= radius * w * std::sin(turn);
            dy += radius * w * std::cos(turn);
        }
        if (!std::isfinite(x) || !std::isfinite(y)) continue;
        Particle p;
        if (now.depth) {
            const double z = (std::isfinite(s.zSpread) ? s.zSpread : 0) * f.unit * (2 * Unit(key, 21) - 1) +
                             (std::isfinite(s.zSpeed) ? s.zSpeed : 0) * f.unit * f1;
            float m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
            if (f.orient) f.orient(b, m);
            const double ox = x - ex, oy = y - ey;
            const double rx = m[0] * ox + m[1] * oy + m[2] * z;
            const double ry = m[3] * ox + m[4] * oy + m[5] * z;
            const double rz = m[6] * ox + m[7] * oy + m[8] * z;
            const double perspective = std::clamp(std::isfinite(now.perspective) ? (double)now.perspective : 1.0, 0.0, 10.0);
            const double scale = std::clamp(1.0 / std::max(0.1, 1.0 + rz * perspective / std::max(1.0, 1080.0 * f.unit)), 0.1, 10.0);
            x = ex + rx * scale;
            y = ey + ry * scale;
            p.z = (float)rz;
            p.size = (float)scale;
        }
        p.key = key;
        p.x = (float)x;
        p.y = (float)y;
        if (now.follow) {
            p.angle = (float)(dx * dx + dy * dy > 1e-9 ? std::atan2(dy, dx) : heading);
        } else {
            const float spread = Clamp01(s.spinVariation);
            const double spin = s.spin * (1 + spread * (2 * Unit(key, 11) - 1)) * (Unit(key, 12) < 0.5 * spread ? -1 : 1);
            p.angle = (float)(std::fmod(Clamp01(s.randomRotation) * (Unit(key, 13) - 0.5) * kTau + spin * age * kTau / 360, kTau));
        }
        p.size *= s.size * std::max(0.0f, 1 + Clamp01(s.sizeVariation) * (float)(2 * Unit(key, 14) - 1)) * (1 + (endSize - 1) * u);
        if (!(p.size > 0) || !std::isfinite(p.size)) continue;
        p.alpha = (fadeIn > 0 ? Smooth(u / fadeIn) : 1.0f) * (fadeOut > 0 ? Smooth((1 - u) / fadeOut) : 1.0f);
        if (twinkle > 0) p.alpha *= 1 - twinkle * (float)(0.5 + 0.5 * std::sin(kTau * ((1.5 + 2.5 * Unit(key, 15)) * age + Unit(key, 16))));
        if (!(p.alpha > 0.002f)) continue;
        const float blend = s.colouring == kRandomMix ? (float)Unit(key, 17) : s.colouring == kOverLife ? u : 0.0f;
        float c[3];
        for (int i = 0; i < 3; i++) c[i] = Clamp01(s.color[i] + (s.color2[i] - s.color[i]) * blend);
        if (s.colorVariation > 0) Turn(c, Clamp01(s.colorVariation) * (Unit(key, 18) - 0.5));
        p.r = c[0];
        p.g = c[1];
        p.b = c[2];
        if (now.kind == kConfetti) {
            const double flip = std::fabs(std::cos(kTau * ((0.6 + 1.6 * Unit(key, 19)) * age + Unit(key, 20))));
            p.frame = std::min(3, (int)((1 - flip) * 4));
        }
        out->push_back(p);
    }
    if (out->size() > cap) out->erase(out->begin(), out->begin() + (std::ptrdiff_t)(out->size() - cap));
    if (now.depth) std::stable_sort(out->begin(), out->end(), [](const Particle& a, const Particle& b) { return a.z > b.z; });
}

}
