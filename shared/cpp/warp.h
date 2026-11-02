#pragma once

#include <vector>

namespace am::warp {

struct Pin {
    float x = 0, y = 0, restX = 0, restY = 0;
};

struct Spot {
    float at = 0, beyond = 0;
};

struct Start {
    float at = 0;
    bool flip = false;
};

struct Bend {
    std::vector<double> x, y, cx, cy;
    double centerX = 0, centerY = 0, ar = 1, ai = 0, tx = 0, ty = 0, power = 0.75;
};

constexpr int kMaxPins = 32;
constexpr double kMergeDistance = 0.5;

Spot Along(float distance, float length, bool closed);
float Anchor(int align, float left, float right);
Start TopStart(const std::vector<float>& x, const std::vector<float>& y, float length);
bool AtRest(const std::vector<Pin>& pins);
double Power(float falloff);
Bend Solve(const std::vector<Pin>& pins, float falloff);
void Source(const Bend& bend, double x, double y, double* sourceX, double* sourceY);

}
