#pragma once

#include <cstdint>
#include <functional>
#include <utility>
#include <vector>

namespace am::particles {

enum Kind { kSnow, kSparks, kConfetti, kBubbles, kDust, kHearts, kStars, kPetals, kLayer };
enum Emitter { kPoint, kLine, kArea, kOutline };
enum Emission { kContinuous, kBurst, kBeats };
enum Colouring { kSingle, kRandomMix, kOverLife };

struct Settings {
    int kind = kSnow, emission = kContinuous, colouring = kSingle, beatEvery = 1;
    bool follow = false, prewarm = true, depth = false;
    float rate = 40, count = 100, start = 0, every = 0, bpm = 120;
    float lifetime = 10, lifeVariation = 0.2f, speed = 30, speedVariation = 0.5f, direction = 90, spread = 40;
    float gravity = 300, wind = 0, drag = 1.2f, swirl = 18, swirlSpeed = 0.25f;
    float zSpeed = 0, zSpread = 0, perspective = 1;
    float size = 1, sizeVariation = 0.6f, endSize = 1, spin = 0, spinVariation = 0.5f, randomRotation = 1;
    float fadeIn = 0.05f, fadeOut = 0.15f, twinkle = 0, colorVariation = 0;
    float color[3] = {1, 1, 1}, color2[3] = {1, 1, 1};
};

struct Particle {
    float x = 0, y = 0, z = 0, angle = 0, size = 1, alpha = 1, r = 1, g = 1, b = 1;
    int frame = 0;
    uint64_t key = 0;
};

struct Field {
    uint64_t seed = 0;
    float unit = 1, rateMost = 0, lifeMost = 0;
    bool animated = false;
    double fps = 30, origin = 0;
    std::vector<double> beats;
    std::function<Settings(double)> at;
    std::function<void(double, float, float, float, float*, float*)> emit;
    std::function<void(double, float*)> orient;
};

struct Beat {
    double since = 1e3;
    int64_t index = -1;
};

uint64_t Mix(uint64_t x);
int Capacity(int kind);
void Simulate(const Field& field, double seconds, std::vector<Particle>* out);
Beat LastBeat(const std::vector<double>& beats, double seconds, int every, double offset, double bpm);
void BeatsBetween(const std::vector<double>& beats, double from, double to, int every, double offset, double bpm,
                  std::vector<std::pair<double, int64_t>>* out);
float Envelope(double since, double decay);

}
