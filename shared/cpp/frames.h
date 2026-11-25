#pragma once

#include <cmath>

namespace am {

inline double LatestMs(double timeMs) { return timeMs + 0.999; }

inline double HeldMs(double timeMs, double startMs, double fps) {
    return startMs + std::floor((LatestMs(timeMs) - startMs) * fps / 1000) * 1000 / fps;
}

inline double MiddleMs(double timeMs, double startMs, double fps) {
    return startMs + (std::fmax(std::ceil((timeMs - startMs) * fps / 1000 - 1e-6) - 1, 0.0) + 0.5) * 1000 / fps;
}

}
