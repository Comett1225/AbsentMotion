#include "easing.h"

#include <cfloat>
#include <climits>
#include <cmath>
#include <locale>
#include <sstream>
#include <string>

namespace am {
namespace {

constexpr double kPi = 3.14159265358979323846;

double BezierAt(double a, double b, double s) {
    const double c1 = 3 * a, c2 = 3 * (b - a) - c1, c3 = 1 - c1 - c2;
    return ((c3 * s + c2) * s + c1) * s;
}

double BezierSlope(double a, double b, double s) {
    const double c1 = 3 * a, c2 = 3 * (b - a) - c1, c3 = 1 - c1 - c2;
    return (3 * c3 * s + 2 * c2) * s + c1;
}

double CubicBezier(double x1, double y1, double x2, double y2, double u) {
    x1 = std::fmin(std::fmax(x1, 0.0), 1.0);
    x2 = std::fmin(std::fmax(x2, 0.0), 1.0);
    if (u <= 0) return u * (x1 > 0 ? y1 / x1 : y1 == 0 && x2 > 0 ? y2 / x2 : 0);
    if (u >= 1) return 1 + (u - 1) * (x2 < 1 ? (y2 - 1) / (x2 - 1) : y2 == 1 && x1 < 1 ? (y1 - 1) / (x1 - 1) : 0);
    double s = u;
    bool found = false;
    for (int i = 0; i < 8; i++) {
        const double miss = BezierAt(x1, x2, s) - u;
        if (std::fabs(miss) < 1e-5) { found = s >= 0 && s <= 1; break; }
        const double slope = BezierSlope(x1, x2, s);
        if (std::fabs(slope) < 1e-6) break;
        s -= miss / slope;
    }
    if (!found) {
        double lo = 0, hi = 1;
        for (int i = 0; i < 60; i++) {
            s = (lo + hi) / 2;
            if (BezierAt(x1, x2, s) < u) lo = s;
            else hi = s;
        }
    }
    return BezierAt(y1, y2, s);
}

constexpr double kMinBounce = 0.0055;

double Bounce(double L, double b, double u) {
    if (!(L > 0)) return u;
    const double drop = L / 2;
    if (u < drop) return (u / drop) * (u / drop);
    double start = drop, length = L * b, height = b;
    for (int i = 0; i < 10000 && height >= kMinBounce && start + length <= 1; i++) {
        if (u < start + length) return 1 - 4 * height * (u - start) * (start + length - u) / (length * length);
        start += length;
        length *= b;
        height *= b;
    }
    return 1;
}

double Elastic(double a, double c, double d, double u) {
    if (!(a > 0)) return u;
    const double power = 1 + 15 * c * c;
    auto ring = [&](double t) { return 1 - d * std::pow(std::fabs(1 - t), power) * std::cos(kPi * t / a); };
    const double x = u / a;
    if (x >= 1) return ring(u);
    const double fade = x * x * x;
    return fade * ring(u) + (1 - fade) * ring(a) * (1 - std::cos(kPi * x)) / 2;
}

double Cyclic(double a, double b, double c, double d, double u) {
    if (!(a > 0)) return u;
    double phase = u / a;
    phase -= std::floor(phase);
    const double s = phase < c ? phase / c : (1 - phase) / (1 - c);
    const double wave = (1 - b) * (1 - std::cos(kPi * s)) / 2 + b * s;
    return d * u + (1 - d * u) * wave;
}

double Random(const Easing& e, double u) {
    u = std::fmin(std::fmax(u, 0.0), 1.0);
    const double frequency = e.p[0], magnitude = e.p[1], seed = e.p[2];
    const double attenuation = e.count > 3 ? e.p[3] : 0.5;
    const double warp = e.count > 4 ? e.p[4] : 0.5;
    const double tilt = e.count > 5 ? e.p[5] : 0.0;
    const double k = 1 + 10 * std::fabs(warp - 0.5);
    const double line = warp < 0.5 ? std::pow(u, k) : 1 - std::pow(1 - u, k);
    const double trend = line + tilt * (1 - 2 * line);
    const double ends = 1 - std::pow(std::fabs(1 - 2 * u), 3);
    const double envelope = std::fmin(1.0, 2 - 2 * attenuation) * u + std::fmin(1.0, 2 * attenuation) * (1 - u);
    const double noise = SimplexNoise2(frequency * 30 * u, seed * 10) +
                         0.5 * SimplexNoise2(frequency * 60 * u, seed * 10 + 23453.234534);
    return trend + magnitude * ends * envelope * envelope * noise;
}

double Step(double L, double smoothing, double u) {
    if (!(L > 0)) return u;
    const double stair = std::floor(u / L);
    const double rise = smoothing * L;
    const double into = u - stair * L - (L - rise);
    double up = 0;
    if (rise > 0 && into > 0) {
        const double x = into / rise;
        up = x * x * (3 - 2 * x);
    }
    return L * (stair + up);
}

double ElasticStep(double L, double magnitude, double u) {
    if (!(L > 0)) return u;
    const double stair = std::floor(u / L);
    if (stair < 1) return 0;
    const double spring = 1 - magnitude / 2;
    return L * (stair - 1 + Elastic(0.5 - 0.45 * magnitude, spring, spring, u / L - stair));
}

double Curve(const Easing& e, double u) {
    const float* p = e.p;
    switch (e.type) {
        case Easing::kCubicBezier: return CubicBezier(p[0], p[1], p[2], p[3], u);
        case Easing::kBounce: return Bounce(p[0], p[1], u);
        case Easing::kElastic: return Elastic(p[0], p[2], p[3], u);
        case Easing::kCyclic: return Cyclic(p[0], p[1], p[2], p[3], u);
        case Easing::kRandom: return Random(e, u);
        case Easing::kStep: return Step(p[0], p[1], u);
        case Easing::kElasticStep: return ElasticStep(p[0], p[1], u);
        default: return u;
    }
}

}

Easing ParseEasing(const char* text) {
    Easing e;
    if (!text) return e;
    std::istringstream in(text);
    in.imbue(std::locale::classic());
    std::string word;
    while (in >> word) {
        if (word == "reverse") {
            if (e.reversals < INT_MAX) e.reversals++;
        } else if (word != "local") {
            break;
        }
    }
    static const struct { const char* name; Easing::Type type; } kTypes[] = {
        {"cubicBezier", Easing::kCubicBezier}, {"bounce", Easing::kBounce}, {"elastic", Easing::kElastic},
        {"cyclic", Easing::kCyclic},           {"random", Easing::kRandom}, {"step", Easing::kStep},
        {"elasticStep", Easing::kElasticStep},
    };
    for (const auto& t : kTypes)
        if (word == t.name) e.type = t.type;
    if (e.type == Easing::kLinear) return e;
    float v;
    while (e.count < 8 && in >> v) e.p[e.count++] = v;
    return e;
}

float Ease(const Easing& easing, float time) {
    double u = std::isfinite(time) ? time : time > 0 ? 1 : 0;
    const bool reversed = easing.reversals & 1;
    if (reversed) u = 1 - u;
    double v = Curve(easing, u);
    if (!std::isfinite(v)) v = u;
    if (reversed) v = 1 - v;
    return (float)std::fmin(std::fmax(v, -FLT_MAX), FLT_MAX);
}

double SimplexNoise2(double x, double y, bool standard) {
    if (!(std::fabs(x) < 1e9 && std::fabs(y) < 1e9)) return 0;
    static const unsigned char kTable[256] = {
        151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
        140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
        247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
        57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
        74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
        60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
        65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
        200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
        52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
        207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
        119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
        129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
        218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
        81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
        184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
        222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180,
    };
    static const signed char kGrad[12][2] = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {1, 0}, {-1, 0},
                                             {1, 0}, {-1, 0}, {0, 1},  {0, -1},  {0, 1}, {0, -1}};
    auto perm = [standard](int i) { return kTable[standard ? i & 255 : i % 255]; };
    const double F2 = 0.36602540378443864676;
    const double G2 = 0.21132486540518711775;
    const double skew = (x + y) * F2;
    const double i = std::floor(x + skew), j = std::floor(y + skew);
    const double unskew = (i + j) * G2;
    const double x0 = x - (i - unskew), y0 = y - (j - unskew);
    const int i1 = x0 > y0, j1 = !i1;
    const double dx[3] = {x0, x0 - i1 + G2, x0 - 1 + 2 * G2};
    const double dy[3] = {y0, y0 - j1 + G2, y0 - 1 + 2 * G2};
    const int ii = (int)i & 255, jj = (int)j & 255;
    const int grad[3] = {perm(ii + perm(jj)) % 12, perm(ii + i1 + perm(jj + j1)) % 12, perm(ii + 1 + perm(jj + 1)) % 12};
    double sum = 0;
    for (int c = 0; c < 3; c++) {
        double w = 0.5 - dx[c] * dx[c] - dy[c] * dy[c];
        if (w > 0) {
            w *= w;
            sum += w * w * (kGrad[grad[c]][0] * dx[c] + kGrad[grad[c]][1] * dy[c]);
        }
    }
    return 70 * sum;
}

}
