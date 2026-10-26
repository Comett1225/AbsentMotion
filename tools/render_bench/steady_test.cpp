#include <algorithm>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

#include "scene.h"

namespace {

int failures = 0;

void Expect(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL %s\n", what);
        failures++;
    }
}

am::Keyframe Key(float t, float v, am::Easing::Type type = am::Easing::kLinear) {
    am::Keyframe k{t, {v, v * 2}, {}};
    k.easing.type = type;
    if (type == am::Easing::kCubicBezier) {
        k.easing.p[0] = 0.42f;
        k.easing.p[2] = 0.58f;
        k.easing.p[3] = 1;
    }
    return k;
}

bool Bitwise(float a, float b) { return std::memcmp(&a, &b, sizeof a) == 0; }

}

int main() {
    am::Anim flat;
    flat.value = {3};
    Expect(flat.steady(-5, 5), "unkeyed");
    am::Anim hold;
    hold.keys = {Key(0, 0), Key(0.4f, 1, am::Easing::kCubicBezier), Key(1, 1, am::Easing::kCubicBezier)};
    Expect(hold.steady(0.4f, 0.9f), "hold after move");
    Expect(hold.steady(1, 3), "after last key");
    Expect(!hold.steady(0.39f, 0.5f), "window touches move");
    Expect(!hold.steady(0.2f, 0.3f), "inside move");
    Expect(!hold.steady(0.5f, 0.4f), "reversed window");
    am::Anim lead = hold;
    lead.hasLead = true;
    lead.lead = Key(-0.1f, 5);
    Expect(!lead.steady(-0.05f, -0.01f), "lead differs");
    lead.lead = Key(-0.1f, 0);
    Expect(!lead.steady(-0.05f, 0.1f), "lead equal but move follows");
    Expect(lead.steady(-0.2f, 0), "lead equal");
    am::Anim same;
    same.keys = {Key(0.2f, 1), Key(0.2f, 1), Key(0.6f, 1)};
    Expect(same.steady(0, 1), "duplicate key times");

    std::mt19937 rng(41);
    std::uniform_real_distribution<float> unit(-0.3f, 1.3f);
    const am::Easing::Type types[] = {am::Easing::kLinear, am::Easing::kCubicBezier, am::Easing::kBounce, am::Easing::kElastic,
                                      am::Easing::kCyclic, am::Easing::kStep, am::Easing::kElasticStep};
    int checked = 0, aligned = 0;
    for (int trial = 0; trial < 20000; trial++) {
        am::Anim a;
        const int n = (int)(rng() % 5);
        std::vector<float> times;
        for (int k = 0; k < n; k++) times.push_back(rng() % 4 == 0 && k ? times.back() : unit(rng));
        std::sort(times.begin(), times.end());
        for (int k = 0; k < n; k++) {
            am::Keyframe key = Key(times[k], (float)(rng() % 2), types[rng() % 7]);
            key.easing.p[0] = 0.25f;
            key.easing.p[1] = 0.5f;
            key.easing.count = 2;
            a.keys.push_back(key);
        }
        if (n && rng() % 3 == 0) {
            a.hasLead = true;
            a.lead = Key(times[0] - 0.05f, (float)(rng() % 2));
        }
        float from = unit(rng), to = unit(rng);
        if (n && rng() % 3 == 0) from = times[rng() % n];
        if (n && rng() % 3 == 0) to = times[rng() % n];
        if (from > to) std::swap(from, to);
        if (!a.steady(from, to)) continue;
        checked++;
        aligned += std::find(times.begin(), times.end(), from) != times.end() || std::find(times.begin(), times.end(), to) != times.end();
        for (int s = 0; s <= 64; s++) {
            const float t = s == 64 ? to : from + (to - from) * s / 64;
            for (int i = 0; i < 3; i++) Expect(Bitwise(a.get(i, t, 7), a.get(i, from, 7)), "property: steady window is constant");
        }
    }
    std::printf("steady_test: %d constant windows checked, %d on key times, %d failures\n", checked, aligned, failures);
    return failures ? 1 : 0;
}
