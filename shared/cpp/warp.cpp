#include "warp.h"

#include <algorithm>
#include <cmath>

namespace am::warp {

Spot Along(float distance, float length, bool closed) {
    if (!(length > 0) || !std::isfinite(distance)) return {0, 0};
    if (closed) {
        float d = std::fmod(distance, length);
        if (d < 0) d += length;
        return {std::min(d, length), 0};
    }
    if (distance < 0) return {0, distance};
    if (distance > length) return {length, distance - length};
    return {distance, 0};
}

float Anchor(int align, float left, float right) {
    if (align <= 0) return left;
    if (align >= 2) return right;
    return (left + right) / 2;
}

Start TopStart(const std::vector<float>& x, const std::vector<float>& y, float length) {
    const size_t n = std::min(x.size(), y.size());
    if (n < 3 || !(length > 0)) return {};
    double area = 0;
    size_t lowest = 0;
    for (size_t i = 0; i < n; i++) {
        const size_t j = (i + 1) % n;
        area += (double)x[i] * y[j] - (double)x[j] * y[i];
        if (y[i] < y[lowest]) lowest = i;
    }
    const float near = length * 1e-6f + 1e-4f;
    double c = 0, s = 0;
    int ties = 0;
    for (size_t i = 0; i < n; i++) {
        if (!(y[i] <= y[lowest] + near)) continue;
        const double angle = 2 * M_PI * i / n;
        c += std::cos(angle);
        s += std::sin(angle);
        ties++;
    }
    double step = std::atan2(s, c) / (2 * M_PI) * n;
    if (ties == 1) {
        const float before = y[(lowest + n - 1) % n], at = y[lowest], after = y[(lowest + 1) % n];
        const float bend = before - 2 * at + after;
        step = lowest + (bend > 0 ? std::clamp(0.5f * (before - after) / bend, -0.5f, 0.5f) : 0.0f);
    }
    const double turn = std::fmod(step / n + 1, 1.0);
    return {(float)(turn * length), area < 0};
}

bool AtRest(const std::vector<Pin>& pins) {
    return std::all_of(pins.begin(), pins.end(), [](const Pin& p) { return std::fabs(p.x - p.restX) < 1e-4f && std::fabs(p.y - p.restY) < 1e-4f; });
}

double Power(float falloff) {
    const double a = std::clamp(std::isfinite(falloff) ? (double)falloff : 1.0, 0.1, 4.0);
    return 0.5 + 0.5 / (1 + a);
}

static bool SolveLinear(std::vector<double>& a, std::vector<double>& b, int n) {
    for (int c = 0; c < n; c++) {
        int best = c;
        for (int r = c + 1; r < n; r++) {
            if (std::fabs(a[r * n + c]) > std::fabs(a[best * n + c])) best = r;
        }
        if (!(std::fabs(a[best * n + c]) > 1e-300)) return false;
        if (best != c) {
            for (int k = 0; k < n; k++) std::swap(a[c * n + k], a[best * n + k]);
            std::swap(b[c], b[best]);
        }
        for (int r = c + 1; r < n; r++) {
            const double f = a[r * n + c] / a[c * n + c];
            if (f == 0) continue;
            for (int k = c; k < n; k++) a[r * n + k] -= f * a[c * n + k];
            b[r] -= f * b[c];
        }
    }
    for (int c = n - 1; c >= 0; c--) {
        double s = b[c];
        for (int k = c + 1; k < n; k++) s -= a[c * n + k] * b[k];
        b[c] = s / a[c * n + c];
        if (!std::isfinite(b[c])) return false;
    }
    return true;
}

Bend Solve(const std::vector<Pin>& pins, float falloff) {
    Bend bend;
    bend.power = Power(falloff);
    struct Group {
        double firstX, firstY, x = 0, y = 0, restX = 0, restY = 0;
        int count = 0;
    };
    std::vector<Group> groups;
    const size_t used = std::min(pins.size(), (size_t)kMaxPins);
    for (size_t i = 0; i < used; i++) {
        const Pin& p = pins[i];
        auto g = std::find_if(groups.begin(), groups.end(), [&](const Group& other) {
            const double dx = p.x - other.firstX, dy = p.y - other.firstY;
            return dx * dx + dy * dy < kMergeDistance * kMergeDistance;
        });
        if (g == groups.end()) g = groups.insert(groups.end(), Group{p.x, p.y});
        g->x += p.x;
        g->y += p.y;
        g->restX += p.restX;
        g->restY += p.restY;
        g->count++;
    }
    const int n = (int)groups.size();
    if (n == 0) return bend;
    double qx0 = 0, qy0 = 0;
    for (const Group& g : groups) {
        bend.centerX += g.x / g.count / n;
        bend.centerY += g.y / g.count / n;
    }
    std::vector<double> qx, qy;
    for (const Group& g : groups) {
        bend.x.push_back(g.x / g.count - bend.centerX);
        bend.y.push_back(g.y / g.count - bend.centerY);
        qx.push_back(g.restX / g.count - bend.centerX);
        qy.push_back(g.restY / g.count - bend.centerY);
        qx0 += (qx.back() - bend.x.back()) / n;
        qy0 += (qy.back() - bend.y.back()) / n;
    }
    bend.tx = qx0;
    bend.ty = qy0;
    if (n == 1) {
        bend.x.clear();
        bend.y.clear();
        return bend;
    }
    const int m = 2 * n + 4;
    std::vector<double> a(m * m, 0.0), b(m, 0.0);
    for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
            const double dx = bend.x[i] - bend.x[j], dy = bend.y[i] - bend.y[j];
            const double k = i == j ? 0 : std::pow(dx * dx + dy * dy, bend.power);
            a[2 * i * m + 2 * j] = k;
            a[(2 * i + 1) * m + 2 * j + 1] = k;
        }
        const double rowX[4] = {bend.x[i], -bend.y[i], 1, 0}, rowY[4] = {bend.y[i], bend.x[i], 0, 1};
        for (int k = 0; k < 4; k++) {
            a[2 * i * m + 2 * n + k] = a[(2 * n + k) * m + 2 * i] = rowX[k];
            a[(2 * i + 1) * m + 2 * n + k] = a[(2 * n + k) * m + 2 * i + 1] = rowY[k];
        }
        b[2 * i] = qx[i];
        b[2 * i + 1] = qy[i];
    }
    if (!SolveLinear(a, b, m)) {
        bend.x.clear();
        bend.y.clear();
        return bend;
    }
    for (int i = 0; i < n; i++) {
        bend.cx.push_back(b[2 * i]);
        bend.cy.push_back(b[2 * i + 1]);
    }
    bend.ar = b[2 * n];
    bend.ai = b[2 * n + 1];
    bend.tx = b[2 * n + 2];
    bend.ty = b[2 * n + 3];
    return bend;
}

void Source(const Bend& bend, double x, double y, double* sourceX, double* sourceY) {
    const double vx = x - bend.centerX, vy = y - bend.centerY;
    double sx = bend.ar * vx - bend.ai * vy + bend.tx, sy = bend.ai * vx + bend.ar * vy + bend.ty;
    for (size_t i = 0; i < bend.cx.size(); i++) {
        const double dx = bend.x[i] - vx, dy = bend.y[i] - vy, r = std::pow(dx * dx + dy * dy, bend.power);
        sx += bend.cx[i] * r;
        sy += bend.cy[i] * r;
    }
    *sourceX = sx + bend.centerX;
    *sourceY = sy + bend.centerY;
}

}
