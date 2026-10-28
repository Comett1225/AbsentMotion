#include "warp.h"

#include <cmath>
#include <cstdio>
#include <utility>
#include <vector>

using namespace am::warp;

static int failures = 0;

static void check(bool ok, const char* what) {
    if (!ok) {
        printf("FAIL %s\n", what);
        failures++;
    }
}

static bool near(float a, float b, float e = 1e-3f) { return std::fabs(a - b) < e; }

static Start top(const std::vector<std::pair<float, float>>& corners, int n = 256) {
    std::vector<float> lengths;
    float length = 0;
    for (size_t i = 0; i < corners.size(); i++) {
        const auto [ax, ay] = corners[i];
        const auto [bx, by] = corners[(i + 1) % corners.size()];
        lengths.push_back(std::hypot(bx - ax, by - ay));
        length += lengths.back();
    }
    std::vector<float> x, y;
    for (int k = 0; k < n; k++) {
        float d = length * k / n;
        size_t i = 0;
        while (d > lengths[i]) d -= lengths[i++];
        const auto [ax, ay] = corners[i];
        const auto [bx, by] = corners[(i + 1) % corners.size()];
        x.push_back(ax + (bx - ax) * d / lengths[i]);
        y.push_back(ay + (by - ay) * d / lengths[i]);
    }
    return TopStart(x, y, length);
}

static Start circle(float from, float turn, int n = 256) {
    std::vector<float> x, y;
    for (int k = 0; k < n; k++) {
        const float a = from + turn * 2 * (float)M_PI * k / n;
        x.push_back(100 * std::cos(a));
        y.push_back(100 * std::sin(a));
    }
    return TopStart(x, y, 200 * (float)M_PI);
}

static void source(const std::vector<Pin>& pins, float x, float y, float* sx, float* sy, float falloff = 1) {
    double dx, dy;
    Source(Solve(pins, falloff), x, y, &dx, &dy);
    *sx = (float)dx;
    *sy = (float)dy;
}

int main() {
    {
        const Spot a = Along(50, 100, false), b = Along(-20, 100, false), c = Along(130, 100, false);
        check(near(a.at, 50) && near(a.beyond, 0), "inside an open path");
        check(near(b.at, 0) && near(b.beyond, -20), "before the start of an open path continues along the first tangent");
        check(near(c.at, 100) && near(c.beyond, 30), "past the end of an open path continues along the last tangent");
        const Spot d = Along(-20, 100, true), e = Along(250, 100, true);
        check(near(d.at, 80) && near(d.beyond, 0), "a closed path wraps backwards");
        check(near(e.at, 50) && near(e.beyond, 0), "a closed path wraps forwards");
        check(near(Along(5, 0, false).at, 0) && near(Along(NAN, 10, true).at, 0), "an empty path or a bad distance stays at the start");
        check(near(Anchor(0, 10, 30), 10) && near(Anchor(1, 10, 30), 20) && near(Anchor(2, 10, 30), 30), "left, centre and right anchors");
    }
    {
        const float quarter = 50 * (float)M_PI;
        const Start a = circle(-(float)M_PI / 2, 1), b = circle(0, 1), c = circle(0, -1), d = circle(0.3f, 1, 97);
        check((near(a.at, 0, 0.05f) || near(a.at, 4 * quarter, 0.05f)) && !a.flip, "a clockwise circle from 12 o'clock has its top at the start");
        check(near(b.at, 3 * quarter, 0.05f) && !b.flip, "a clockwise circle from 3 o'clock has its top three quarters along");
        check(near(c.at, quarter, 0.05f) && c.flip, "an anticlockwise circle is flipped and its top is a quarter along");
        check(near(d.at, 3 * quarter - 30, 0.5f) && !d.flip, "the top of a circle sampled away from it is found between the samples");
        const Start e = top({{0, 0}, {200, 0}, {200, 100}, {0, 100}}), f = top({{0, 100}, {0, 0}, {200, 0}, {200, 100}});
        const Start g = top({{100, 0}, {200, 0}, {200, 100}, {0, 100}, {0, 0}}), h = top({{0, 0}, {0, 100}, {200, 100}, {200, 0}});
        check(near(e.at, 100, 1.5f) && !e.flip, "a rectangle from its top-left corner has its top in the middle of the top edge");
        check(near(f.at, 200, 1.5f) && !f.flip, "a rectangle from its bottom-left corner has its top in the middle of the top edge");
        check((near(g.at, 0, 1.5f) || near(g.at, 600, 1.5f)) && !g.flip, "a top edge across the start of the path is found");
        check(near(h.at, 500, 1.5f) && h.flip, "an anticlockwise rectangle is flipped");
        check(near(TopStart({1, 2}, {1, 2}, 5).at, 0) && near(TopStart({}, {}, 0).at, 0), "too few samples or no length give the start");
    }
    {
        float x, y;
        source({}, 12, 34, &x, &y);
        check(near(x, 12) && near(y, 34), "no pins: nothing moves");
        const std::vector<Pin> rest = {{10, 10, 10, 10}, {90, 20, 90, 20}, {50, 80, 50, 80}};
        check(AtRest(rest), "pins where they were placed are at rest");
        for (float px : {0.0f, 33.0f, 70.0f}) {
            source(rest, px, 41, &x, &y);
            check(near(x, px, 1e-2f) && near(y, 41, 1e-2f), "pins at rest leave every point where it is");
        }
        const std::vector<Pin> one = {{60, 50, 40, 50}};
        source(one, 100, 100, &x, &y);
        check(near(x, 80) && near(y, 100), "one moved pin moves the whole picture with it");
        const std::vector<Pin> moved = {{0, 0, 0, 0}, {120, 0, 100, 0}, {0, 100, 0, 100}};
        for (const Pin& p : moved) {
            source(moved, p.x, p.y, &x, &y);
            check(near(x, p.restX, 0.05f) && near(y, p.restY, 0.05f), "the picture under a pin comes from where the pin was placed");
        }
        const std::vector<Pin> turned = {{0, 0, 0, 0}, {0, 100, 100, 0}};
        source(turned, 0, 50, &x, &y);
        check(near(x, 50, 0.5f) && near(y, 0, 0.5f), "two pins turn the picture rigidly between them");
        const std::vector<Pin> same = {{30, 30, 10, 10}, {30, 30, 10, 10}};
        source(same, 50, 50, &x, &y);
        check(near(x, 30) && near(y, 30), "pins on top of each other act as one pin");
        const std::vector<Pin> stacked = {{30, 30, 10, 10}, {30, 30, 20, 10}};
        source(stacked, 50, 50, &x, &y);
        check(std::isfinite(x) && std::isfinite(y), "contradicting pins on one spot stay finite");
        float a, b, c, d;
        source(moved, 60.0f, 40.0f, &a, &b);
        source(moved, 60.01f, 40.0f, &c, &d);
        check(near(a, c, 0.05f) && near(b, d, 0.05f), "the warp is smooth");
        source(moved, 60, 40, &a, &b, 0.5f);
        source(moved, 60, 40, &c, &d, 3.0f);
        check(!(near(a, c, 1e-3f) && near(b, d, 1e-3f)), "falloff changes how far a pin reaches");
        std::vector<Pin> many;
        for (int i = 0; i < 40; i++) many.push_back({(float)i * 3, 0, (float)i * 3, 0});
        many.back().x += 50;
        source(many, 10, 10, &x, &y);
        check(near(x, 10, 1e-2f) && near(y, 10, 1e-2f), "pins after the 32nd are ignored");
    }
    {
        const std::vector<Pin> bent = {{0, 0, 0, 0}, {150, 30, 100, 0}, {50, 120, 40, 100}, {200, 200, 210, 190}};
        const std::vector<Pin> pair = {{10, 10, 10, 10}, {90, 20, 95, 30}, {50, 80, 50, 80}, {50.2f, 80.1f, 40, 70}};
        struct Fixture {
            const std::vector<Pin>* pins;
            float falloff, x, y, sx, sy;
        };
        const Fixture fixtures[] = {{&bent, 1, 70, 40, 45.8442f, 21.5051f}, {&bent, 0.1f, -30, 160, -5.7997f, 154.4276f},
                                    {&bent, 4, 120, 90, 93.4713f, 66.2163f}, {&pair, 2, 60, 60, 57.8627f, 59.4605f}};
        float x, y;
        for (const Fixture& f : fixtures) {
            source(*f.pins, f.x, f.y, &x, &y, f.falloff);
            check(near(x, f.sx, 2e-3f) && near(y, f.sy, 2e-3f), "the warp matches the shared fixture the Kotlin copy also checks");
        }
        check(Power(1) == 0.75 && Power(NAN) == 0.75 && near((float)Power(-5), (float)Power(0.1f), 1e-6f) && Power(99) == Power(4), "falloff is clamped and NaN means 1");
        const std::vector<Pin> held = {{0, 0, 0, 0}, {400, 0, 400, 0}, {200, 300, 200, 200}};
        for (float falloff : {0.1f, 0.25f, 0.5f, 1.0f, 2.0f, 4.0f}) {
            bool still = true;
            for (float d : {0.0f, 0.5f, 2.0f}) {
                source(held, d, 0, &x, &y, falloff);
                still = still && std::hypot(x - d, y) < 0.1f;
                source(held, 400 - d, 0, &x, &y, falloff);
                still = still && std::hypot(x - 400 + d, y) < 0.1f;
            }
            source(held, 200.3f, 300, &x, &y, falloff);
            check(still && std::hypot(x - 200.3f, y - 200) < 0.05f, "at every falloff unmoved pins hold the picture and a moved pin carries it");
        }
        for (float falloff : {0.1f, 1.0f, 4.0f}) {
            const Bend before = Solve(bent, falloff);
            double rx, ry;
            Source(before, 70, 40, &rx, &ry);
            std::vector<Pin> added = bent;
            added.push_back({70, 40, (float)rx, (float)ry});
            const Bend after = Solve(added, falloff);
            double worst = 0;
            for (int gx = -100; gx <= 300; gx += 10) {
                for (int gy = -100; gy <= 300; gy += 10) {
                    double ax, ay, bx, by;
                    Source(before, gx, gy, &ax, &ay);
                    Source(after, gx, gy, &bx, &by);
                    worst = std::max(worst, std::hypot(ax - bx, ay - by));
                }
            }
            check(worst < 1e-3, "a pin added where the warp already puts the picture moves nothing anywhere");
        }
        const Bend merged = Solve({{30, 30, 10, 10}, {30.2f, 30, 20, 10}, {200, 30, 200, 30}}, 1);
        check(merged.cx.size() == 2, "pins closer than half a unit act as one pin");
        for (float scale : {1.0f, 1e-3f, 1e3f}) {
            std::vector<Pin> far;
            for (const Pin& p : bent) far.push_back({p.x * scale, p.y * scale, p.restX * scale, p.restY * scale});
            source(far, 1e6f, -1e6f, &x, &y, 4);
            check(std::isfinite(x) && std::isfinite(y), "far from tiny or huge pins the warp stays finite at the highest falloff");
        }
    }
    if (failures) {
        printf("%d warp check(s) failed\n", failures);
        return 1;
    }
    printf("warp: all checks passed\n");
    return 0;
}
