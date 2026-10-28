#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "cutout.h"

namespace {

int failures = 0;

void expect(bool ok, const char *what) {
    if (ok) return;
    std::printf("FAIL %s\n", what);
    failures++;
}

struct Plane {
    int w, h;
    std::vector<float> a;
    Plane(int width, int height) : w(width), h(height), a(static_cast<size_t>(width) * height, 0.f) {}
    float &at(int x, int y) { return a[static_cast<size_t>(y) * w + x]; }
    void disc(int cx, int cy, int r, float v) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) at(x, y) = v;
    }
    void clean() { cutout::clean(a.data(), w, h); }
};

}

int main() {
    {
        Plane p(768, 768);
        p.disc(384, 384, 200, 1.f);
        p.disc(60, 60, 5, .9f);
        p.clean();
        expect(p.at(384, 384) == 1.f, "subject core is untouched");
        expect(p.at(60, 60) == 0.f, "isolated speck is removed");
    }
    {
        Plane p(768, 768);
        p.disc(384, 384, 200, 1.f);
        for (int y = 0; y < 768; y++)
            for (int x = 0; x < 100; x++) p.at(x, y) = .03f;
        for (int y = 0; y < 768; y++)
            for (int x = 100; x < 150; x++) p.at(x, y) = .08f;
        p.clean();
        expect(p.at(20, 400) == 0.f, "faint haze below the floor goes to zero");
        expect(p.at(120, 400) < .08f && p.at(120, 400) > 0.f - 1e-6f, "ramped haze is reduced, not raised");
    }
    {
        Plane p(768, 768);
        p.disc(384, 384, 200, 1.f);
        for (int y = 0; y < 768; y++)
            for (int x = 0; x < 768; x++) {
                const float d = std::sqrt(static_cast<float>((x - 384) * (x - 384) + (y - 384) * (y - 384)));
                if (d > 200 && d < 204) p.at(x, y) = .03f;
            }
        p.clean();
        expect(p.at(384 + 202, 384) == .03f, "faint edge right next to the subject is kept");
    }
    {
        Plane p(768, 768);
        p.disc(384, 384, 150, .5f);
        p.clean();
        expect(std::fabs(p.at(384, 384) - .5f) < 1e-6f, "a large translucent subject survives");
    }
    {
        Plane p(768, 768);
        p.disc(200, 300, 120, 1.f);
        p.disc(600, 500, 90, 1.f);
        p.clean();
        expect(p.at(200, 300) == 1.f && p.at(600, 500) == 1.f, "two separate subjects both survive");
    }
    {
        Plane p(768, 768);
        for (int y = 300; y < 304; y++)
            for (int x = 300; x < 304; x++) p.at(x, y) = .5f;
        p.clean();
        expect(std::fabs(p.at(301, 301) - .5f) < 1e-6f, "with nothing else to keep the mask is left alone");
    }
    {
        Plane p(768, 768);
        p.disc(384, 384, 200, 1.f);
        for (int y = 380; y < 388; y++)
            for (int x = 150; x < 158; x++) p.at(x, y) = .9f;
        for (int y = 380; y < 388; y++)
            for (int x = 596; x < 604; x++) p.at(x, y) = .05f;
        p.clean();
        expect(p.at(153, 383) == 0.f, "a small detached bright blob is removed");
        expect(p.at(599, 383) < .005f, "a faint detached blob is almost gone");
    }
    {
        Plane p(1024, 1024);
        p.disc(512, 512, 300, 1.f);
        for (int y = 0; y < 1024; y++)
            for (int x = 0; x < 1024; x++) {
                const float d = std::sqrt(static_cast<float>((x - 512) * (x - 512) + (y - 512) * (y - 512)));
                if (d > 300 && d <= 340) p.at(x, y) = .3f * (1.f - (d - 300.f) / 40.f);
            }
        const float before = p.at(512 + 305, 512);
        p.clean();
        expect(p.at(512 + 305, 512) == before, "a soft halo hugging the subject keeps its shape");
    }
    if (failures) return 1;
    std::printf("cutout clean: all checks passed\n");
    return 0;
}
