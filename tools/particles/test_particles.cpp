#include "particles.h"

#include <cmath>
#include <cstdio>
#include <map>
#include <vector>

using namespace am::particles;

static int failures = 0;

static void check(bool ok, const char* what) {
    if (!ok) {
        printf("FAIL %s\n", what);
        failures++;
    }
}

static Field field(Settings base, uint64_t seed = 7) {
    Field f;
    f.seed = seed;
    f.rateMost = base.rate;
    f.lifeMost = base.lifetime * (1 + base.lifeVariation);
    f.at = [base](double) { return base; };
    f.emit = [](double, float u, float v, float, float* x, float* y) {
        *x = 100 + 200 * u;
        *y = 50 + 10 * v;
    };
    return f;
}

static std::vector<Particle> at(const Field& f, double seconds) {
    std::vector<Particle> out;
    Simulate(f, seconds, &out);
    return out;
}

static bool same(const std::vector<Particle>& a, const std::vector<Particle>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++) {
        if (a[i].key != b[i].key || a[i].x != b[i].x || a[i].y != b[i].y || a[i].z != b[i].z || a[i].angle != b[i].angle || a[i].size != b[i].size ||
            a[i].alpha != b[i].alpha || a[i].r != b[i].r || a[i].frame != b[i].frame) return false;
    }
    return true;
}

int main() {
    Settings snow;
    const Field f = field(snow);
    {
        const auto first = at(f, 4.25);
        at(f, 9.0);
        at(f, 0.5);
        const auto again = at(f, 4.25);
        check(!first.empty() && same(first, again), "the same time gives the same particles whatever was drawn before");
        const Field other = field(snow, 8);
        check(!same(first, at(other, 4.25)), "another seed gives other particles");
    }
    {
        const auto full = at(f, 0.0);
        const double expected = snow.rate * snow.lifetime;
        check(std::fabs(full.size() - expected) < expected * 0.2, "start full fills the field at the first frame");
        Settings empty = snow;
        empty.prewarm = false;
        check(at(field(empty), 0.0).empty(), "without start full nothing is there at the first frame");
        const size_t growing = at(field(empty), 2.0).size();
        check(std::fabs(growing - 2.0 * snow.rate) < snow.rate * 0.5, "particles build up at the rate");
    }
    {
        const auto a = at(f, 3.0), b = at(f, 3.001);
        std::map<uint64_t, const Particle*> before;
        for (const Particle& p : a) before[p.key] = &p;
        float most = 0;
        for (const Particle& p : b) {
            auto found = before.find(p.key);
            if (found != before.end()) most = std::max(most, std::hypot(p.x - found->second->x, p.y - found->second->y));
        }
        check(most < 2.0f, "particles move continuously between nearby times");
    }
    {
        Settings drop;
        drop.emission = kBurst;
        drop.count = 1;
        drop.start = 1;
        drop.lifetime = 10;
        drop.lifeVariation = 0;
        drop.speed = 0;
        drop.drag = 0;
        drop.swirl = 0;
        drop.wind = 0;
        drop.gravity = 100;
        drop.fadeIn = drop.fadeOut = 0;
        Field g = field(drop);
        check(at(g, 0.99).empty(), "a burst waits for its time");
        const auto falling = at(g, 3.0);
        check(falling.size() == 1, "a burst of one is one particle");
        check(falling.size() == 1 && falling[0].y >= 249.9f && falling[0].y <= 260.1f, "free fall follows half g t squared");
        check(at(g, 11.5).empty(), "particles end with their lifetime");
        drop.drag = 2;
        g = field(drop);
        const auto a = at(g, 9.0), b = at(g, 9.1);
        check(a.size() == 1 && b.size() == 1 && std::fabs((b[0].y - a[0].y) / 0.1f - 50.0f) < 1.0f, "air resistance gives a terminal speed of gravity over drag");
    }
    {
        Settings busy = snow;
        busy.rate = 500;
        busy.lifetime = 30;
        check(at(field(busy), 40.0).size() <= (size_t)Capacity(kSnow), "the particle count is capped");
        busy.kind = kLayer;
        check(at(field(busy), 40.0).size() <= (size_t)Capacity(kLayer), "copies of the layer have a lower cap");
    }
    {
        Settings ramp = snow;
        ramp.prewarm = false;
        Field r = field(ramp);
        r.animated = true;
        r.at = [ramp](double s) {
            Settings x = ramp;
            x.rate = s < 2 ? 0 : 40;
            return x;
        };
        check(at(r, 1.9).empty(), "a keyframed rate of zero emits nothing");
        check(!at(r, 3.0).empty(), "particles start when the rate rises");
    }
    {
        const std::vector<double> marks = {1.0, 2.0, 3.5, 5.0};
        Beat b = LastBeat(marks, 0.5, 1, 0, 120);
        check(b.index == -1 && b.since >= 100, "no beat before the first mark");
        b = LastBeat(marks, 2.25, 1, 0, 120);
        check(b.index == 1 && std::fabs(b.since - 0.25) < 1e-9, "the latest mark counts");
        b = LastBeat(marks, 3.0, 2, 0, 120);
        check(b.index == 0 && std::fabs(b.since - 2.0) < 1e-9, "every second mark skips the odd ones");
        b = LastBeat(marks, 3.6, 2, 0, 120);
        check(b.index == 1 && std::fabs(b.since - 0.1) < 1e-9, "every second mark counts the even ones");
        b = LastBeat(marks, 3.4996, 1, 0, 120);
        check(b.index == 2 && b.since == 0, "a mark a hair ahead already counts");
        b = LastBeat(marks, 1.05, 1, 0.1, 120);
        check(b.index == -1, "an offset moves the beat later");
        b = LastBeat({}, 1.3, 1, 0, 120);
        check(b.index == 2 && std::fabs(b.since - 0.3) < 1e-9, "without marks the BPM sets the beat");
        b = LastBeat({}, 1.3, 4, 0, 120);
        check(b.index == 0 && std::fabs(b.since - 1.3) < 1e-9, "every fourth beat at 120 BPM is every two seconds");
        std::vector<std::pair<double, int64_t>> found;
        BeatsBetween(marks, 1.5, 5.0, 1, 0, 120, &found);
        check(found.size() == 3 && found[0].second == 1 && found[2].first == 5.0, "beats between two times");
        BeatsBetween({}, 0, 2.0, 2, 0, 60, &found);
        check(found.size() == 2 && found[1].first == 2.0, "BPM beats between two times");
        check(Envelope(0, 0.4) == 1.0f && Envelope(0.4, 0.4) == 0.0f && Envelope(0.2, 0.4) == 0.25f, "the beat envelope falls from 1 to 0");
    }
    {
        Settings onBeat = snow;
        onBeat.emission = kBeats;
        onBeat.count = 20;
        onBeat.lifetime = 1;
        onBeat.lifeVariation = 0;
        Field b = field(onBeat);
        b.beats = {2.0};
        b.origin = 1.0;
        check(at(b, 0.9).empty(), "beat particles wait for the beat in layer time");
        check(at(b, 1.2).size() == 20, "each beat sends its count");
        check(at(b, 2.1).empty(), "beat particles end with their lifetime");
    }
    {
        Settings depth = snow;
        depth.depth = true;
        depth.zSpeed = 100;
        depth.zSpread = 200;
        depth.perspective = 1;
        Field d = field(depth);
        const auto first = at(d, 4.25);
        at(d, 8);
        check(!first.empty() && same(first, at(d, 4.25)), "depth is deterministic");
        bool sorted = true, changed = false;
        for (size_t i = 1; i < first.size(); i++) sorted &= first[i - 1].z >= first[i].z;
        for (const Particle& p : first) changed |= p.z != 0 && p.size != 1;
        check(sorted && changed, "depth projects particles and sorts far to near");
        depth.perspective = 0;
        const auto flat = at(field(depth), 4.25);
        check(!same(first, flat), "perspective changes projected particles");
        d.orient = [](double, float* m) { m[0] = 0; m[2] = 1; m[6] = -1; m[8] = 0; };
        check(!same(first, at(d, 4.25)), "tilt rotates depth motion");
        depth.depth = false;
        d = field(depth);
        d.orient = [](double, float* m) { m[0] = 0; m[2] = 1; m[6] = -1; m[8] = 0; };
        check(same(at(d, 4.25), at(f, 4.25)), "depth off keeps the old simulation");
    }
    if (failures) {
        printf("%d particle checks failed\n", failures);
        return 1;
    }
    printf("particle checks passed\n");
    return 0;
}
