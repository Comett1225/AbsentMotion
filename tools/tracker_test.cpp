#include "tracker.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <new>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {

bool count_allocations = false;
std::size_t allocations = 0;
constexpr double pi = 3.14159265358979323846;

void record_allocation() {
    if (count_allocations) {
        ++allocations;
    }
}

}

void* operator new(std::size_t size) {
    record_allocation();
    if (void* memory = std::malloc(size == 0 ? 1 : size)) {
        return memory;
    }
    throw std::bad_alloc();
}

void* operator new[](std::size_t size) {
    return ::operator new(size);
}

void operator delete(void* memory) noexcept {
    std::free(memory);
}

void operator delete[](void* memory) noexcept {
    std::free(memory);
}

void operator delete(void* memory, std::size_t) noexcept {
    std::free(memory);
}

void operator delete[](void* memory, std::size_t) noexcept {
    std::free(memory);
}

void* operator new(std::size_t size, std::align_val_t alignment) {
    record_allocation();
    const std::size_t align = static_cast<std::size_t>(alignment);
    const std::size_t rounded = ((std::max(size, std::size_t(1)) + align - 1) / align) * align;
    if (void* memory = std::aligned_alloc(align, rounded)) {
        return memory;
    }
    throw std::bad_alloc();
}

void* operator new[](std::size_t size, std::align_val_t alignment) {
    return ::operator new(size, alignment);
}

void operator delete(void* memory, std::align_val_t) noexcept {
    std::free(memory);
}

void operator delete[](void* memory, std::align_val_t) noexcept {
    std::free(memory);
}

void operator delete(void* memory, std::size_t, std::align_val_t) noexcept {
    std::free(memory);
}

void operator delete[](void* memory, std::size_t, std::align_val_t) noexcept {
    std::free(memory);
}

namespace {

void require(bool condition, const char* message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

template<class Function>
void require_invalid(Function function) {
    bool rejected = false;
    try {
        function();
    } catch (const std::invalid_argument&) {
        rejected = true;
    }
    require(rejected, "invalid input was accepted");
}

std::uint32_t hash(std::uint32_t value) {
    value ^= value >> 16;
    value *= 0x7feb352dU;
    value ^= value >> 15;
    value *= 0x846ca68bU;
    return value ^ (value >> 16);
}

double lattice(int x, int y) {
    const std::uint32_t bits = hash(static_cast<std::uint32_t>(x) * 0x9e3779b9U ^
                                    static_cast<std::uint32_t>(y) * 0x85ebca6bU);
    return (bits & 65535U) / 32767.5 - 1.0;
}

double smooth_noise(double x, double y) {
    const int ix = static_cast<int>(std::floor(x));
    const int iy = static_cast<int>(std::floor(y));
    double fx = x - ix;
    double fy = y - iy;
    fx = fx * fx * (3.0 - 2.0 * fx);
    fy = fy * fy * (3.0 - 2.0 * fy);
    const double top = lattice(ix, iy) * (1.0 - fx) + lattice(ix + 1, iy) * fx;
    const double bottom = lattice(ix, iy + 1) * (1.0 - fx) + lattice(ix + 1, iy + 1) * fx;
    return top * (1.0 - fy) + bottom * fy;
}

struct Random {
    std::uint32_t state = 0x13579bdfU;

    double uniform() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return (static_cast<double>(state) + 0.5) / 4294967296.0;
    }

    double gaussian() {
        return std::sqrt(-2.0 * std::log(uniform())) * std::cos(2.0 * pi * uniform());
    }
};

double cubic(double p0, double p1, double p2, double p3, double t) {
    return p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 +
                                                  t * (3.0 * (p1 - p2) + p3 - p0)));
}

class Scene {
public:
    Scene(int width, int height) : width_(width + 256), height_(height + 256),
        pixels_(static_cast<std::size_t>(width_) * height_) {
        for (int y = 0; y < height_; ++y) {
            for (int x = 0; x < width_; ++x) {
                const double px = x - 128.0;
                const double py = y - 128.0;
                double value = 127.0 + 28.0 * smooth_noise(px / 9.0, py / 9.0) +
                    13.0 * smooth_noise(px / 4.0 + 7.0, py / 4.0 - 11.0) +
                    19.0 * std::sin(0.113 * px + 0.071 * py + 1.0) +
                    17.0 * std::cos(0.053 * px - 0.173 * py + 0.7) +
                    10.0 * std::sin(0.237 * px + 0.149 * py + 2.1);
                const double circle = std::hypot(px - 0.47 * width, py - 0.52 * height);
                value += 24.0 * std::exp(-0.002 * (circle - 48.0) * (circle - 48.0));
                if (std::abs(px - 0.27 * width) < 32.0 && std::abs(py - 0.31 * height) < 23.0) {
                    value += 18.0;
                }
                pixels_[static_cast<std::size_t>(y) * width_ + x] =
                    static_cast<float>(std::clamp(value, 1.0, 254.0));
            }
        }
    }

    double sample(double x, double y) const {
        x = std::clamp(x + 128.0, 1.0, width_ - 3.001);
        y = std::clamp(y + 128.0, 1.0, height_ - 3.001);
        const int ix = static_cast<int>(x);
        const int iy = static_cast<int>(y);
        const double fx = x - ix;
        const double fy = y - iy;
        double rows[4];
        for (int k = -1; k <= 2; ++k) {
            const float* row = pixels_.data() + static_cast<std::size_t>(iy + k) * width_ + ix;
            rows[k + 1] = cubic(row[-1], row[0], row[1], row[2], fx);
        }
        return cubic(rows[0], rows[1], rows[2], rows[3], fy);
    }

private:
    int width_;
    int height_;
    std::vector<float> pixels_;
};

struct Buffer {
    int width;
    int height;
    int stride;
    std::vector<std::uint8_t> pixels;

    Buffer(int frame_width, int frame_height, int padding = 13)
        : width(frame_width), height(frame_height), stride(frame_width + padding),
          pixels(static_cast<std::size_t>(stride) * height, 219) {}

    motion::Frame frame() const {
        return {pixels.data(), width, height, stride};
    }

    void render(const Scene& scene, const motion::Transform& transform = {},
                double noise = 0.0, double gain = 1.0, double bias = 0.0, unsigned seed = 1) {
        Random random;
        random.state = seed;
        const double denominator = transform.a * transform.a + transform.b * transform.b;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const double px = x - transform.tx;
                const double py = y - transform.ty;
                const double sx = (transform.a * px + transform.b * py) / denominator;
                const double sy = (-transform.b * px + transform.a * py) / denominator;
                double value = gain * scene.sample(sx, sy) + bias;
                if (noise > 0.0) {
                    value += noise * random.gaussian();
                }
                pixels[static_cast<std::size_t>(y) * stride + x] =
                    static_cast<std::uint8_t>(std::clamp(std::lround(value), 0L, 255L));
            }
        }
    }

    void occlude(motion::Point center, int radius) {
        for (int y = std::max(0, static_cast<int>(center.y) - radius);
             y <= std::min(height - 1, static_cast<int>(center.y) + radius); ++y) {
            for (int x = std::max(0, static_cast<int>(center.x) - radius);
                 x <= std::min(width - 1, static_cast<int>(center.x) + radius); ++x) {
                pixels[static_cast<std::size_t>(y) * stride + x] =
                    static_cast<std::uint8_t>(hash(static_cast<std::uint32_t>(x + y * width)) & 255U);
            }
        }
    }
};

motion::Transform centered(double angle, double scale, motion::Point center, motion::Point shift) {
    const double a = scale * std::cos(angle);
    const double b = scale * std::sin(angle);
    return {a, b, center.x + shift.x - a * center.x + b * center.y,
                  center.y + shift.y - b * center.x - a * center.y};
}

double distance(motion::Point a, motion::Point b) {
    return std::hypot(a.x - b.x, a.y - b.y);
}

double check_points(const motion::Result& result, const motion::Point* points,
                    const motion::Transform& expected, double tolerance) {
    require(!result.lost, "tracking lost");
    double maximum = 0.0;
    for (std::size_t i = 0; i < result.count; ++i) {
        const double error = distance(result.points[i].position, expected.apply(points[i]));
        if (result.points[i].lost || error >= tolerance) {
            std::printf("  point %zu: error %.6f, confidence %.6f, lost %d, actual %.4f %.4f, expected %.4f %.4f\n",
                        i, error, result.points[i].confidence, result.points[i].lost,
                        result.points[i].position.x, result.points[i].position.y,
                        expected.apply(points[i]).x, expected.apply(points[i]).y);
        }
        require(!result.points[i].lost, "point lost");
        require(std::isfinite(result.points[i].confidence) && result.points[i].confidence >= 0.0 &&
                result.points[i].confidence <= 1.0, "invalid confidence");
        require(error < tolerance, "point error exceeds tolerance");
        maximum = std::max(maximum, error);
    }
    return maximum;
}

void test_translation() {
    Scene scene(512, 384);
    Buffer buffer(512, 384);
    const std::array<motion::Point, 4> points{{{151.25, 123.75}, {342.4, 126.3},
                                             {161.7, 266.1}, {351.3, 271.6}}};
    double maximum = 0.0;
    for (std::size_t count = 1; count <= 4; ++count) {
        motion::Tracker tracker(points.data(), count);
        buffer.render(scene);
        check_points(tracker.track(buffer.frame()), points.data(), {}, 1e-8);
        for (int step = 1; step <= 16; ++step) {
            const motion::Transform transform{1.0, 0.0, 0.371 * step, -0.283 * step};
            buffer.render(scene, transform);
            const motion::Result result = tracker.track(buffer.frame());
            maximum = std::max(maximum, check_points(result, points.data(), transform, 0.1));
            if (count == 1) {
                require(result.transform.a == 1.0 && result.transform.b == 0.0, "single-point transform");
            }
            require(distance(result.motion.apply({211.0, 197.0}), {211.371, 196.717}) < 0.12,
                    "incremental transform error");
        }
    }
    std::printf("PASS subpixel translations, 1-4 points: max error %.4f px (< 0.1)\n", maximum);
}

void test_pixel_noise_translation() {
    Buffer reference(320, 240);
    Buffer shifted(320, 240);
    std::uint32_t random = 198291U;
    for (int y = 0; y < reference.height; ++y) {
        for (int x = 0; x < reference.width; ++x) {
            random ^= random << 13;
            random ^= random >> 17;
            random ^= random << 5;
            const int shape = x > 100 && x < 200 && y > 70 && y < 170 ? 20 : 0;
            reference.pixels[static_cast<std::size_t>(y) * reference.stride + x] =
                static_cast<std::uint8_t>(40 + random % 160 + shape);
        }
    }
    double maximum = 0.0;
    for (double offset : {0.1, 0.2, 0.3, 0.37, 0.5, 0.7, 0.9}) {
        for (int y = 1; y < reference.height; ++y) {
            for (int x = 1; x < reference.width; ++x) {
                const std::uint8_t* row = reference.pixels.data() + static_cast<std::size_t>(y) * reference.stride;
                const std::uint8_t* previous = row - reference.stride;
                const double value = (1.0 - offset) * (1.0 - offset) * row[x] +
                    offset * (1.0 - offset) * (row[x - 1] + previous[x]) + offset * offset * previous[x - 1];
                shifted.pixels[static_cast<std::size_t>(y) * shifted.stride + x] =
                    static_cast<std::uint8_t>(std::lround(value));
            }
        }
        for (double fraction : {0.0, 0.25, 0.5, 0.75}) {
            const motion::Point point{151.0 + fraction, 113.0 + fraction};
            motion::Tracker tracker(&point, 1);
            tracker.track(reference.frame());
            maximum = std::max(maximum, check_points(tracker.track(shifted.frame()), &point,
                {1.0, 0.0, offset, offset}, 0.1));
        }
    }
    std::printf("PASS pixel-scale random noise and shapes, subpixel shifts: max error %.4f px (< 0.1)\n", maximum);
}

void test_large_motion() {
    Scene scene(640, 480);
    Buffer buffer(640, 480);
    const std::array<motion::Point, 4> points{{{210.3, 170.7}, {407.4, 168.8},
                                             {212.1, 307.2}, {408.9, 309.5}}};
    const std::array<motion::Point, 6> shifts{{{59.7, -48.2}, {1.1, 11.6}, {-58.7, -34.1},
                                             {-0.2, 25.3}, {59.8, -0.7}, {0.0, 0.0}}};
    double maximum = 0.0;
    for (std::size_t count : {std::size_t(1), std::size_t(4)}) {
        for (int levels = 3; levels <= 4; ++levels) {
            motion::Options options;
            options.pyramid_levels = levels;
            options.patch_radius = 24;
            motion::Tracker tracker(points.data(), count, options);
            buffer.render(scene);
            tracker.track(buffer.frame());
            for (motion::Point shift : shifts) {
                motion::Transform transform{1.0, 0.0, shift.x, shift.y};
                buffer.render(scene, transform);
                maximum = std::max(maximum, check_points(tracker.track(buffer.frame()), points.data(), transform, 0.1));
            }
        }
    }
    std::printf("PASS large motion and reversals, 1/4 points, 3/4 levels: max error %.4f px\n", maximum);
}

void test_similarity() {
    Scene scene(640, 480);
    Buffer buffer(640, 480);
    const std::array<motion::Point, 4> points{{{224.2, 165.7}, {416.4, 167.3},
                                             {228.7, 309.1}, {413.3, 312.6}}};
    double maximum = 0.0;
    double maximum_scale = 0.0;
    double maximum_angle = 0.0;
    for (std::size_t count = 2; count <= 4; ++count) {
        for (double angle : {-10.0, 10.0}) {
            for (double scale : {0.9, 1.1}) {
                motion::Options options;
                options.patch_radius = 20;
                motion::Tracker tracker(points.data(), count, options);
                buffer.render(scene);
                tracker.track(buffer.frame());
                for (int step = 1; step <= 4; ++step) {
                    const auto transform = centered(angle * pi / 180.0 * step, std::pow(scale, step),
                                                    {320.0, 240.0}, {1.17 * step, -0.83 * step});
                    buffer.render(scene, transform);
                    const motion::Result result = tracker.track(buffer.frame());
                    maximum = std::max(maximum, check_points(result, points.data(), transform, 0.22));
                    maximum_scale = std::max(maximum_scale, std::abs(result.transform.scale() - transform.scale()));
                    maximum_angle = std::max(maximum_angle, std::abs(result.transform.rotation() - transform.rotation()));
                    for (motion::Point probe : points) {
                        require(distance(result.transform.apply(probe), transform.apply(probe)) < 0.25,
                                "similarity reprojection error");
                    }
                    require(maximum_scale < 0.002, "similarity scale error");
                    require(maximum_angle < 0.002, "similarity angle error");
                }
            }
        }
    }
    std::printf("PASS similarity, 2-4 points, +/-10 deg and 0.9/1.1 per frame: max point %.4f px, scale %.6f, angle %.4f deg\n",
                maximum, maximum_scale, maximum_angle * 180.0 / pi);
}

void test_noise() {
    Scene scene(512, 384);
    Buffer buffer(512, 384);
    const std::array<motion::Point, 4> points{{{142.3, 125.2}, {352.7, 124.6},
                                             {149.1, 269.8}, {348.4, 268.3}}};
    motion::Options options;
    options.patch_radius = 32;
    motion::Tracker tracker(points.data(), points.size(), options);
    buffer.render(scene, {}, 6.0, 1.0, 0.0, 27);
    tracker.track(buffer.frame());
    double maximum = 0.0;
    for (int step = 1; step <= 12; ++step) {
        const auto transform = centered(0.013 * step, 1.0 + 0.002 * step,
                                        {256.0, 192.0}, {0.41 * step, -0.31 * step});
        buffer.render(scene, transform, 6.0, 1.0 + 0.009 * step, 0.31 * step,
                      static_cast<unsigned>(step * 19 + 1));
        maximum = std::max(maximum, check_points(tracker.track(buffer.frame()), points.data(), transform, 0.24));
    }
    std::printf("PASS Gaussian noise sigma 6 and changing brightness: max error %.4f px\n", maximum);
}

void test_flat() {
    Buffer buffer(256, 192);
    const motion::Point point{127.3, 96.8};
    for (int variant = 0; variant < 2; ++variant) {
        for (int y = 0; y < buffer.height; ++y) {
            for (int x = 0; x < buffer.width; ++x) {
                buffer.pixels[static_cast<std::size_t>(y) * buffer.stride + x] =
                    static_cast<std::uint8_t>(variant == 0 ? 117 : 40 + x / 2);
            }
        }
        motion::Tracker tracker(&point, 1);
        for (int frame = 0; frame < 12; ++frame) {
            const motion::Result result = tracker.track(buffer.frame());
            require(result.lost && result.points[0].lost, "flat/aperture region was accepted");
            require(result.points[0].confidence < 0.05, "flat/aperture confidence too high");
            require(distance(result.points[0].position, point) == 0.0, "flat region wandered");
        }
    }
    std::printf("PASS flat and one-dimensional regions: low confidence, lost, no wandering\n");
}

void test_occlusion() {
    Scene scene(640, 480);
    Buffer buffer(640, 480);
    const std::array<motion::Point, 4> points{{{210.3, 161.9}, {426.7, 163.2},
                                             {214.4, 318.2}, {423.6, 319.7}}};
    motion::Tracker tracker(points.data(), points.size());
    buffer.render(scene);
    tracker.track(buffer.frame());
    double maximum = 0.0;
    for (int step = 1; step <= 12; ++step) {
        const auto transform = centered(0.019 * step, 1.0 + 0.002 * step,
                                        {320.0, 240.0}, {0.8 * step, -0.4 * step});
        buffer.render(scene, transform);
        const bool hidden = step >= 3 && step <= 8;
        if (hidden) {
            buffer.occlude(transform.apply(points[1]), 32);
        }
        const motion::Result result = tracker.track(buffer.frame());
        require(!result.lost, "group lost during one-point occlusion");
        if (hidden) {
            require(result.points[1].lost && result.points[1].confidence < 0.6, "occlusion not rejected");
        } else {
            require(!result.points[1].lost, "occluded point did not recover");
        }
        for (std::size_t i = 0; i < points.size(); ++i) {
            if (i != 1 || !hidden) {
                require(!result.points[i].lost, "visible point lost during occlusion");
            }
            const double error = distance(result.transform.apply(points[i]), transform.apply(points[i]));
            maximum = std::max(maximum, error);
            require(error < 0.18, "group transform corrupted by occlusion");
        }
    }
    std::printf("PASS one-of-four occlusion and recovery: max group error %.4f px\n", maximum);
}

void test_complete_loss() {
    Scene scene(384, 288);
    Buffer buffer(384, 288);
    const motion::Point points[2] = {{123.4, 107.8}, {259.1, 183.3}};
    motion::Tracker tracker(points, 2);
    buffer.render(scene);
    tracker.track(buffer.frame());
    const motion::Transform shift{1.0, 0.0, 3.7, -2.3};
    buffer.render(scene, shift);
    const motion::Result valid = tracker.track(buffer.frame());
    check_points(valid, points, shift, 0.1);
    std::fill(buffer.pixels.begin(), buffer.pixels.end(), std::uint8_t(117));
    for (int step = 0; step < 3; ++step) {
        const motion::Result lost = tracker.track(buffer.frame());
        require(lost.lost && lost.points[0].lost && lost.points[1].lost, "complete loss was accepted");
        require(lost.transform.a == valid.transform.a && lost.transform.b == valid.transform.b &&
                lost.transform.tx == valid.transform.tx && lost.transform.ty == valid.transform.ty,
                "lost group transform changed");
        require(lost.motion.a == 1.0 && lost.motion.b == 0.0 &&
                lost.motion.tx == 0.0 && lost.motion.ty == 0.0, "lost group motion changed");
    }
    buffer.render(scene, {1.0, 0.0, 6.1, -3.8});
    check_points(tracker.track(buffer.frame()), points, {1.0, 0.0, 6.1, -3.8}, 0.1);
    std::printf("PASS complete occlusion: hold transform and recover on reappearance\n");
}

void test_long_sequence() {
    Scene scene(512, 384);
    Buffer buffer(512, 384);
    const std::array<motion::Point, 4> points{{{148.2, 129.6}, {342.7, 127.3},
                                             {154.3, 272.8}, {341.9, 271.5}}};
    motion::Options options;
    options.refresh_interval = 9;
    motion::Tracker tracker(points.data(), points.size(), options);
    buffer.render(scene);
    tracker.track(buffer.frame());
    double maximum = 0.0;
    allocations = 0;
    count_allocations = true;
    for (int step = 1; step <= 300; ++step) {
        const auto transform = centered(0.12 * std::sin(step * 0.037), 1.0 + 0.045 * std::sin(step * 0.029),
            {256.0, 192.0}, {0.173 * step + 3.0 * std::sin(step * 0.071), -0.091 * step});
        buffer.render(scene, transform, 0.0, 1.0 + 0.08 * std::sin(step * 0.02),
                      3.0 * std::sin(step * 0.041));
        maximum = std::max(maximum, check_points(tracker.track(buffer.frame()), points.data(), transform, 1.0));
    }
    count_allocations = false;
    require(allocations == 0, "steady-state frame allocation");
    std::printf("PASS 300-frame anchored sequence with refresh: max drift %.4f px (< 1), %zu allocations\n",
                maximum, allocations);
}

void test_api_and_edges() {
    const motion::Point point{90.3, 80.7};
    require_invalid([&] { motion::Tracker tracker(nullptr, 1); });
    require_invalid([&] { motion::Tracker tracker(&point, 0); });
    require_invalid([&] { motion::Tracker tracker(&point, 5); });
    const motion::Point invalid{std::numeric_limits<double>::quiet_NaN(), 0.0};
    require_invalid([&] { motion::Tracker tracker(&invalid, 1); });
    const motion::Point duplicates[2] = {point, point};
    require_invalid([&] { motion::Tracker tracker(duplicates, 2); });
    motion::Options options;
    options.patch_radius = 7;
    require_invalid([&] { motion::Tracker tracker(&point, 1, options); });
    options.patch_radius = 16;
    options.min_confidence = std::numeric_limits<double>::quiet_NaN();
    require_invalid([&] { motion::Tracker tracker(&point, 1, options); });
    Scene scene(319, 241);
    Buffer buffer(319, 241, 19);
    buffer.render(scene);
    motion::Tracker tracker(&point, 1);
    require_invalid([&] { tracker.track({}); });
    auto bad_stride = buffer.frame();
    bad_stride.stride = 100;
    require_invalid([&] { tracker.track(bad_stride); });
    check_points(tracker.track(buffer.frame()), &point, {}, 1e-8);
    buffer.render(scene, {1.0, 0.0, 0.43, -0.31});
    check_points(tracker.track(buffer.frame()), &point, {1.0, 0.0, 0.43, -0.31}, 0.1);
    tracker.reset();
    allocations = 0;
    count_allocations = true;
    const motion::Result reset = tracker.track(buffer.frame());
    count_allocations = false;
    require(allocations == 0, "reset allocated");
    require(distance(reset.points[0].position, point) == 0.0, "reset did not restore origins");
    motion::Tracker moved(std::move(tracker));
    require(!moved.track(buffer.frame()).lost, "moved tracker failed");
    Buffer resized(321, 243);
    resized.render(scene);
    allocations = 0;
    count_allocations = true;
    const motion::Result size_changed = moved.track(resized.frame());
    count_allocations = false;
    require(allocations > 0, "allocation instrumentation did not observe size change");
    require(distance(size_changed.points[0].position, point) == 0.0, "resize did not reset");
    const motion::Point edge{2.0, 2.0};
    motion::Tracker edge_tracker(&edge, 1);
    require(edge_tracker.track(buffer.frame()).lost, "border support not rejected");
    Buffer tiny(1, 1, 0);
    require(edge_tracker.track(tiny.frame()).lost, "tiny frame not lost");
    for (int radius : {8, 48}) {
        motion::Options setting;
        setting.patch_radius = radius;
        motion::Tracker sized(&point, 1, setting);
        buffer.render(scene);
        sized.track(buffer.frame());
        buffer.render(scene, {1.0, 0.0, 0.39, -0.27});
        check_points(sized.track(buffer.frame()), &point, {1.0, 0.0, 0.39, -0.27}, 0.1);
    }
    std::printf("PASS API validation, odd/padded strides, move/reset/resize, borders, tiny frames, radii 8/48\n");
}

void test_determinism() {
    Scene scene(319, 241);
    Buffer buffer(319, 241);
    const motion::Point points[2] = {{100.2, 91.7}, {219.8, 150.1}};
    motion::Tracker first(points, 2);
    motion::Tracker second(points, 2);
    for (int step = 0; step < 14; ++step) {
        buffer.render(scene, centered(0.01 * step, 1.0, {160.0, 120.0}, {0.23 * step, -0.19 * step}));
        const motion::Result a = first.track(buffer.frame());
        const motion::Result b = second.track(buffer.frame());
        require(a.lost == b.lost && a.transform.a == b.transform.a && a.transform.b == b.transform.b &&
                a.transform.tx == b.transform.tx && a.transform.ty == b.transform.ty, "nondeterministic group");
        for (std::size_t i = 0; i < a.count; ++i) {
            require(a.points[i].position.x == b.points[i].position.x &&
                    a.points[i].position.y == b.points[i].position.y &&
                    a.points[i].confidence == b.points[i].confidence && a.points[i].lost == b.points[i].lost,
                    "nondeterministic point");
        }
    }
    std::printf("PASS deterministic repeated runs\n");
}

void benchmark(int width, int height, std::size_t count, int radius, bool large_motion = false) {
    Scene scene(width, height);
    Buffer first(width, height);
    Buffer second(width, height);
    first.render(scene);
    second.render(scene, {1.0, 0.0, large_motion ? 59.7 : 0.37, -0.29});
    const motion::Point points[4] = {{width * 0.35, height * 0.35}, {width * 0.65, height * 0.35},
                                    {width * 0.35, height * 0.65}, {width * 0.65, height * 0.65}};
    motion::Options options;
    options.patch_radius = radius;
    options.refresh_interval = 11;
    motion::Tracker tracker(points, count, options);
    tracker.track(first.frame());
    for (int step = 0; step < 10; ++step) {
        require(!tracker.track((step & 1) == 0 ? second.frame() : first.frame()).lost, "benchmark warmup lost");
    }
    std::array<double, 80> elapsed{};
    allocations = 0;
    count_allocations = true;
    for (std::size_t step = 0; step < elapsed.size(); ++step) {
        const auto start = std::chrono::steady_clock::now();
        const motion::Result result = tracker.track((step & 1U) == 0 ? second.frame() : first.frame());
        const auto end = std::chrono::steady_clock::now();
        check_points(result, points, {1.0, 0.0, (step & 1U) == 0 ?
            (large_motion ? 59.7 : 0.37) : 0.0, (step & 1U) == 0 ? -0.29 : 0.0}, 0.1);
        elapsed[step] = std::chrono::duration<double, std::milli>(end - start).count();
    }
    count_allocations = false;
    require(allocations == 0, "benchmark frame allocation");
    std::sort(elapsed.begin(), elapsed.end());
    const double median = 0.5 * (elapsed[39] + elapsed[40]);
    const double p95 = elapsed[75];
    std::printf("TIME %dx%d, %zu point(s), radius %d, %s: median %.3f ms/frame, p95 %.3f, %.3f ms/point, %zu allocations\n",
                width, height, count, radius, large_motion ? "60 px reversals" : "subpixel reversals",
                median, p95, median / count, allocations);
}

}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    try {
        test_translation();
        test_pixel_noise_translation();
        test_large_motion();
        test_similarity();
        test_noise();
        test_flat();
        test_occlusion();
        test_complete_loss();
        test_long_sequence();
        test_api_and_edges();
        test_determinism();
        if (std::getenv("TRACKER_TEST_SKIP_BENCHMARKS") == nullptr) {
            benchmark(1920, 1080, 1, 16);
            benchmark(1920, 1080, 4, 16);
            benchmark(1920, 1080, 1, 48);
            benchmark(1920, 1080, 1, 16, true);
            benchmark(1920, 1080, 4, 16, true);
            benchmark(1920, 1080, 1, 48, true);
            benchmark(3840, 2160, 4, 16);
        }
        std::printf("All tests passed.\n");
        return 0;
    } catch (const std::exception& error) {
        count_allocations = false;
        std::fprintf(stderr, "FAIL: %s\n", error.what());
        return 1;
    }
}
