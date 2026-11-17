#pragma once
#include "absent/media.h"
#include <algorithm>

inline double OnFileClock(const absent::Track& t, int64_t stamp) {
    return double(int64_t(uint64_t(stamp) - uint64_t(t.mediaStart) + uint64_t(t.delay))) / t.timescale;
}

inline double TrackStart(const absent::Track& t) {
    if (t.order.empty()) return double(t.delay) / t.timescale;
    int64_t first = t.samples[t.order.front()].pts;
    if (t.kind == absent::Kind::Audio)
        for (const absent::Sample& s : t.samples)
            if (int64_t(uint64_t(s.pts) + s.duration) > t.mediaStart) first = std::min(first, s.pts);
    return OnFileClock(t, std::max(first, t.mediaStart));
}

inline double FileStart(const absent::Reader& reader) {
    double begin = 1e300;
    for (const absent::Track& t : reader.tracks()) {
        if (t.timescale && !t.samples.empty() && t.kind != absent::Kind::Other) begin = std::min(begin, TrackStart(t));
    }
    return begin == 1e300 ? 0 : begin;
}
