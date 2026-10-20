#ifndef MOTION_TRACKER_H
#define MOTION_TRACKER_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace motion {

struct Point {
    double x = 0.0;
    double y = 0.0;
};

struct Frame {
    const std::uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    int stride = 0;
};

struct Options {
    int patch_radius = 16;
    int search_radius = 64;
    int pyramid_levels = 4;
    int max_iterations = 20;
    int refresh_interval = 30;
    double min_confidence = 0.60;
};

struct Transform {
    double a = 1.0;
    double b = 0.0;
    double tx = 0.0;
    double ty = 0.0;

    Point apply(Point point) const noexcept;
    double scale() const noexcept;
    double rotation() const noexcept;
};

struct PointResult {
    Point position;
    double confidence = 0.0;
    bool lost = true;
};

struct Result {
    std::array<PointResult, 4> points{};
    std::size_t count = 0;
    Transform transform;
    Transform motion;
    bool lost = true;
};

class Tracker {
public:
    Tracker(const Point* points, std::size_t count, Options options = {});
    ~Tracker();
    Tracker(Tracker&&) noexcept;
    Tracker& operator=(Tracker&&) noexcept;
    Tracker(const Tracker&) = delete;
    Tracker& operator=(const Tracker&) = delete;

    Result track(Frame frame);
    void reset() noexcept;

private:
    struct State;
    std::unique_ptr<State> state_;
};

}

#endif
