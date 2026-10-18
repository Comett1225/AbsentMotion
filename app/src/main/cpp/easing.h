#pragma once

namespace am {

struct Easing {
    enum Type { kLinear, kCubicBezier, kBounce, kElastic, kCyclic, kRandom, kStep, kElasticStep } type = kLinear;
    float p[8] = {};
    int count = 0;
    int reversals = 0;
};

Easing ParseEasing(const char* text);

float Ease(const Easing& easing, float u);

double SimplexNoise2(double x, double y);

}
