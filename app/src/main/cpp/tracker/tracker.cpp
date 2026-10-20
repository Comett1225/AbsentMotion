#include "tracker.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

namespace motion {
namespace {

constexpr int maximum_levels = 4;
constexpr double minimum_correlation = 0.45;

struct Image {
    int width = 0;
    int height = 0;
    std::vector<float> pixels;
    std::vector<float> horizontal;
};

struct Patch {
    int radius = 0;
    std::vector<float> values;
    double energy = 0.0;
    double texture = 0.0;
    bool valid = false;
};

struct Warp {
    double x = 0.0;
    double y = 0.0;
    double a = 1.0;
    double b = 0.0;
};

struct Sample {
    double value;
    double gx;
    double gy;
};

struct PointState {
    Point origin;
    Warp warp;
    double vx = 0.0;
    double vy = 0.0;
    double da = 1.0;
    double db = 0.0;
    bool refreshed = false;
    std::array<Patch, maximum_levels> anchor;
    std::array<Patch, maximum_levels> working;
};

double square(double value) {
    return value * value;
}

bool contains(const Image& image, const Warp& warp, int radius) {
    const double extent = radius * (std::abs(warp.a) + std::abs(warp.b));
    return std::isfinite(warp.x) && std::isfinite(warp.y) &&
           std::isfinite(extent) && warp.x - extent >= 0.0 &&
           warp.y - extent >= 0.0 && warp.x + extent < image.width - 1.001 &&
           warp.y + extent < image.height - 1.001;
}

Sample sample_gradient(const Image& image, double x, double y) {
    const int ix = static_cast<int>(x);
    const int iy = static_cast<int>(y);
    const double fx = x - ix;
    const double fy = y - iy;
    const float* row = image.pixels.data() + static_cast<std::size_t>(iy) * image.width + ix;
    const double p00 = row[0];
    const double p10 = row[1];
    const double p01 = row[image.width];
    const double p11 = row[image.width + 1];
    return {p00 + fx * (p10 - p00) + fy * (p01 - p00) +
                fx * fy * (p11 - p10 - p01 + p00),
            (1.0 - fy) * (p10 - p00) + fy * (p11 - p01),
            (1.0 - fx) * (p01 - p00) + fx * (p11 - p10)};
}

double sample(const Image& image, double x, double y) {
    return sample_gradient(image, x, y).value;
}

float filtered_luma(const std::uint8_t* row, int width, int x) {
    return (row[std::max(0, x - 2)] + 4.0f * row[std::max(0, x - 1)] +
            6.0f * row[x] + 4.0f * row[std::min(width - 1, x + 1)] +
            row[std::min(width - 1, x + 2)]) * 0.0625f;
}

void prepare_patch(Patch& patch, int radius) {
    patch.radius = radius;
    patch.values.resize(static_cast<std::size_t>(2 * radius + 1) * (2 * radius + 1));
}

void extract_patch(Patch& patch, const Image& image, const Warp& warp) {
    patch.valid = false;
    patch.energy = 0.0;
    patch.texture = 0.0;
    if (!contains(image, warp, patch.radius)) {
        return;
    }
    double sum = 0.0;
    std::size_t index = 0;
    for (int y = -patch.radius; y <= patch.radius; ++y) {
        for (int x = -patch.radius; x <= patch.radius; ++x) {
            const double value = sample(image, warp.x + warp.a * x - warp.b * y,
                                        warp.y + warp.b * x + warp.a * y);
            patch.values[index++] = static_cast<float>(value);
            sum += value;
        }
    }
    const double mean = sum / patch.values.size();
    for (float& value : patch.values) {
        value = static_cast<float>(value - mean);
        patch.energy += square(value);
    }
    double xx = 0.0;
    double xy = 0.0;
    double yy = 0.0;
    const int side = 2 * patch.radius + 1;
    for (int y = 1; y < side - 1; ++y) {
        for (int x = 1; x < side - 1; ++x) {
            const std::size_t offset = static_cast<std::size_t>(y) * side + x;
            const double gx = 0.5 * (patch.values[offset + 1] - patch.values[offset - 1]);
            const double gy = 0.5 * (patch.values[offset + side] - patch.values[offset - side]);
            xx += gx * gx;
            xy += gx * gy;
            yy += gy * gy;
        }
    }
    const double eigenvalue = 0.5 * (xx + yy - std::hypot(xx - yy, 2.0 * xy));
    patch.texture = std::min(1.0, eigenvalue / (4.0 * square(side - 2)));
    patch.texture *= std::min(1.0, patch.energy / (100.0 * patch.values.size()));
    patch.valid = patch.energy > 1.0 && patch.texture > 0.01;
}

double correlation(const Patch& patch, const Image& image, const Warp& warp) {
    if (!patch.valid || !contains(image, warp, patch.radius)) {
        return -1.0;
    }
    double sum = 0.0;
    double sum_square = 0.0;
    double product = 0.0;
    std::size_t index = 0;
    for (int y = -patch.radius; y <= patch.radius; ++y) {
        for (int x = -patch.radius; x <= patch.radius; ++x) {
            const double value = sample(image, warp.x + warp.a * x - warp.b * y,
                                        warp.y + warp.b * x + warp.a * y);
            sum += value;
            sum_square += value * value;
            product += value * patch.values[index++];
        }
    }
    const double energy = sum_square - sum * sum / patch.values.size();
    if (energy < patch.values.size()) {
        return -1.0;
    }
    return std::clamp(product / std::sqrt(patch.energy * energy), -1.0, 1.0);
}

bool solve(double matrix[4][4], double right[4], double answer[4]) {
    double maximum = 0.0;
    for (int i = 0; i < 4; ++i) {
        maximum = std::max(maximum, matrix[i][i]);
    }
    for (int column = 0; column < 4; ++column) {
        int pivot = column;
        for (int row = column + 1; row < 4; ++row) {
            if (std::abs(matrix[row][column]) > std::abs(matrix[pivot][column])) {
                pivot = row;
            }
        }
        if (std::abs(matrix[pivot][column]) < std::max(1e-8, maximum * 1e-7)) {
            return false;
        }
        if (pivot != column) {
            for (int j = column; j < 4; ++j) {
                std::swap(matrix[pivot][j], matrix[column][j]);
            }
            std::swap(right[pivot], right[column]);
        }
        for (int row = column + 1; row < 4; ++row) {
            const double factor = matrix[row][column] / matrix[column][column];
            for (int j = column + 1; j < 4; ++j) {
                matrix[row][j] -= factor * matrix[column][j];
            }
            right[row] -= factor * right[column];
        }
    }
    for (int row = 3; row >= 0; --row) {
        double value = right[row];
        for (int column = row + 1; column < 4; ++column) {
            value -= matrix[row][column] * answer[column];
        }
        answer[row] = value / matrix[row][row];
        if (!std::isfinite(answer[row])) {
            return false;
        }
    }
    return true;
}

bool allowed_shape(const Warp& warp, double reference_scale) {
    const double scale = std::hypot(warp.a, warp.b);
    return scale >= 0.25 && scale <= 4.0 &&
           scale >= reference_scale * 0.75 && scale <= reference_scale * 1.34;
}

double refine(const Patch& patch, const Image& image, Warp& warp,
              double reference_scale, int iterations, std::vector<Sample>& samples) {
    double score = correlation(patch, image, warp);
    if (score < -0.5) {
        return score;
    }
    const double count = static_cast<double>(patch.values.size());
    const double radius = patch.radius;
    for (int iteration = 0; iteration < iterations; ++iteration) {
        double sum = 0.0;
        double sum_square = 0.0;
        double sum_j[4]{};
        double sum_ij[4]{};
        std::size_t index = 0;
        for (int y = -patch.radius; y <= patch.radius; ++y) {
            for (int x = -patch.radius; x <= patch.radius; ++x) {
                const Sample value = sample_gradient(image, warp.x + warp.a * x - warp.b * y,
                                                     warp.y + warp.b * x + warp.a * y);
                samples[index++] = value;
                const double jacobian[4] = {value.gx, value.gy,
                    (value.gx * x + value.gy * y) / radius,
                    (-value.gx * y + value.gy * x) / radius};
                sum += value.value;
                sum_square += value.value * value.value;
                for (int j = 0; j < 4; ++j) {
                    sum_j[j] += jacobian[j];
                    sum_ij[j] += value.value * jacobian[j];
                }
            }
        }
        const double mean = sum / count;
        const double energy = sum_square - sum * mean;
        if (energy < count) {
            break;
        }
        const double gain = std::sqrt(energy / patch.energy);
        double projection[4];
        for (int j = 0; j < 4; ++j) {
            projection[j] = (sum_ij[j] - mean * sum_j[j]) / energy;
            sum_j[j] /= count;
        }
        double matrix[4][4]{};
        double right[4]{};
        index = 0;
        for (int y = -patch.radius; y <= patch.radius; ++y) {
            for (int x = -patch.radius; x <= patch.radius; ++x) {
                const Sample value = samples[index];
                const double centered = value.value - mean;
                const double residual = centered - gain * patch.values[index++];
                double jacobian[4] = {value.gx, value.gy,
                    (value.gx * x + value.gy * y) / radius,
                    (-value.gx * y + value.gy * x) / radius};
                const double weight = std::min(1.0, 15.0 / std::max(0.001, std::abs(residual)));
                for (int j = 0; j < 4; ++j) {
                    jacobian[j] -= sum_j[j] + centered * projection[j];
                }
                for (int row = 0; row < 4; ++row) {
                    right[row] -= weight * jacobian[row] * residual;
                    for (int column = row; column < 4; ++column) {
                        matrix[row][column] += weight * jacobian[row] * jacobian[column];
                    }
                }
            }
        }
        for (int row = 0; row < 4; ++row) {
            for (int column = 0; column < row; ++column) {
                matrix[row][column] = matrix[column][row];
            }
        }
        double step[4]{};
        if (!solve(matrix, right, step)) {
            break;
        }
        const double movement = std::hypot(step[0], step[1]) + std::hypot(step[2], step[3]);
        if (movement < 0.003) {
            break;
        }
        double fraction = std::min(1.0, 3.0 / movement);
        bool improved = false;
        for (int attempt = 0; attempt < 6; ++attempt) {
            Warp candidate{warp.x + fraction * step[0], warp.y + fraction * step[1],
                           warp.a + fraction * step[2] / radius,
                           warp.b + fraction * step[3] / radius};
            const double next_score = allowed_shape(candidate, reference_scale)
                ? correlation(patch, image, candidate) : -1.0;
            if (next_score > score + 1e-9) {
                warp = candidate;
                const double improvement = next_score - score;
                score = next_score;
                improved = true;
                if (improvement < 1e-8 || fraction * movement < 0.003) {
                    return score;
                }
                break;
            }
            fraction *= 0.5;
        }
        if (!improved) {
            break;
        }
    }
    return score;
}

Transform fit(const std::array<PointState, 4>& states, const Result& result,
              unsigned mask) {
    double weight_sum = 0.0;
    Point source;
    Point target;
    for (std::size_t i = 0; i < result.count; ++i) {
        if ((mask & (1U << i)) == 0) {
            continue;
        }
        const double weight = square(result.points[i].confidence);
        weight_sum += weight;
        source.x += weight * states[i].origin.x;
        source.y += weight * states[i].origin.y;
        target.x += weight * result.points[i].position.x;
        target.y += weight * result.points[i].position.y;
    }
    if (weight_sum <= 0.0) {
        return {};
    }
    source.x /= weight_sum;
    source.y /= weight_sum;
    target.x /= weight_sum;
    target.y /= weight_sum;
    double denominator = 0.0;
    double real = 0.0;
    double imaginary = 0.0;
    for (std::size_t i = 0; i < result.count; ++i) {
        if ((mask & (1U << i)) == 0) {
            continue;
        }
        const double weight = square(result.points[i].confidence);
        const double x = states[i].origin.x - source.x;
        const double y = states[i].origin.y - source.y;
        const double u = result.points[i].position.x - target.x;
        const double v = result.points[i].position.y - target.y;
        denominator += weight * (x * x + y * y);
        real += weight * (x * u + y * v);
        imaginary += weight * (x * v - y * u);
    }
    Transform transform;
    if (denominator > 1e-12 && result.count > 1) {
        transform.a = real / denominator;
        transform.b = imaginary / denominator;
    }
    transform.tx = target.x - transform.a * source.x + transform.b * source.y;
    transform.ty = target.y - transform.b * source.x - transform.a * source.y;
    return transform;
}

int bit_count(unsigned mask) {
    int count = 0;
    while (mask != 0) {
        count += static_cast<int>(mask & 1U);
        mask >>= 1U;
    }
    return count;
}

Transform relative_transform(const Transform& current, const Transform& previous) {
    const double denominator = square(previous.a) + square(previous.b);
    Transform result;
    result.a = (current.a * previous.a + current.b * previous.b) / denominator;
    result.b = (current.b * previous.a - current.a * previous.b) / denominator;
    result.tx = current.tx - result.a * previous.tx + result.b * previous.ty;
    result.ty = current.ty - result.b * previous.tx - result.a * previous.ty;
    return result;
}

}

struct Tracker::State {
    Options options;
    std::size_t count;
    std::array<PointState, 4> points;
    std::array<Image, maximum_levels> pyramid;
    std::vector<Sample> samples;
    Transform group;
    int width = 0;
    int height = 0;
    unsigned refresh_age = 0;
    bool initialized = false;

    State(const Point* positions, std::size_t point_count, Options settings)
        : options(settings), count(point_count) {
        const int side = 2 * options.patch_radius + 1;
        samples.resize(static_cast<std::size_t>(side) * side);
        for (std::size_t i = 0; i < count; ++i) {
            points[i].origin = positions[i];
            for (int level = 0; level < options.pyramid_levels; ++level) {
                const int divisor = 1 << level;
                const int radius = std::max(4, (options.patch_radius + divisor - 1) / divisor);
                prepare_patch(points[i].anchor[level], radius);
                prepare_patch(points[i].working[level], radius);
            }
        }
    }

    void allocate(int frame_width, int frame_height) {
        const int target_width = frame_width;
        const int target_height = frame_height;
        width = height = 0;
        initialized = false;
        for (int level = 0; level < options.pyramid_levels; ++level) {
            Image& image = pyramid[level];
            image.width = frame_width;
            image.height = frame_height;
            image.pixels.resize(static_cast<std::size_t>(frame_width) * frame_height);
            if (level + 1 < options.pyramid_levels) {
                image.horizontal.resize(static_cast<std::size_t>(level == 0 ? frame_width :
                    (frame_width + 1) / 2) * frame_height);
            }
            frame_width = (frame_width + 1) / 2;
            frame_height = (frame_height + 1) / 2;
        }
        width = target_width;
        height = target_height;
    }

    void build_pyramid(Frame frame) {
        Image& base = pyramid[0];
        for (int y = 0; y < height; ++y) {
            const std::uint8_t* source = frame.data + static_cast<std::size_t>(y) * frame.stride;
            float* destination = base.horizontal.data() + static_cast<std::size_t>(y) * width;
            int x = 0;
            for (; x < std::min(width, 2); ++x) {
                destination[x] = filtered_luma(source, width, x);
            }
            for (; x < width - 2; ++x) {
                destination[x] = (source[x - 2] + 4.0f * source[x - 1] + 6.0f * source[x] +
                                  4.0f * source[x + 1] + source[x + 2]) * 0.0625f;
            }
            for (; x < width; ++x) {
                destination[x] = filtered_luma(source, width, x);
            }
        }
        for (int y = 0; y < height; ++y) {
            const float* rows[5];
            for (int k = 0; k < 5; ++k) {
                rows[k] = base.horizontal.data() +
                    static_cast<std::size_t>(std::clamp(y + k - 2, 0, height - 1)) * width;
            }
            float* destination = base.pixels.data() + static_cast<std::size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                destination[x] = (rows[0][x] + 4.0f * rows[1][x] + 6.0f * rows[2][x] +
                                  4.0f * rows[3][x] + rows[4][x]) * 0.0625f;
            }
        }
        for (int level = 1; level < options.pyramid_levels; ++level) {
            Image& source = pyramid[level - 1];
            Image& destination = pyramid[level];
            for (int y = 0; y < source.height; ++y) {
                const float* row = source.pixels.data() + static_cast<std::size_t>(y) * source.width;
                float* output = source.horizontal.data() + static_cast<std::size_t>(y) * destination.width;
                for (int x = 0; x < destination.width; ++x) {
                    const int center = 2 * x;
                    output[x] = (row[std::max(0, center - 2)] +
                        4.0f * row[std::max(0, center - 1)] + 6.0f * row[center] +
                        4.0f * row[std::min(source.width - 1, center + 1)] +
                        row[std::min(source.width - 1, center + 2)]) * 0.0625f;
                }
            }
            for (int y = 0; y < destination.height; ++y) {
                const int center = 2 * y;
                const float* rows[5];
                for (int k = 0; k < 5; ++k) {
                    rows[k] = source.horizontal.data() +
                        static_cast<std::size_t>(std::clamp(center + k - 2, 0, source.height - 1)) * destination.width;
                }
                float* output = destination.pixels.data() + static_cast<std::size_t>(y) * destination.width;
                for (int x = 0; x < destination.width; ++x) {
                    output[x] = (rows[0][x] + 4.0f * rows[1][x] + 6.0f * rows[2][x] +
                                 4.0f * rows[3][x] + rows[4][x]) * 0.0625f;
                }
            }
        }
    }

    Result initialize() {
        group = {};
        refresh_age = 0;
        Result result;
        result.count = count;
        unsigned mask = 0;
        for (std::size_t i = 0; i < count; ++i) {
            PointState& point = points[i];
            point.warp = {point.origin.x, point.origin.y, 1.0, 0.0};
            point.vx = point.vy = point.db = 0.0;
            point.da = 1.0;
            point.refreshed = false;
            for (int level = 0; level < options.pyramid_levels; ++level) {
                const double divisor = 1 << level;
                extract_patch(point.anchor[level], pyramid[level],
                              {point.origin.x / divisor, point.origin.y / divisor, 1.0, 0.0});
            }
            result.points[i].position = point.origin;
            result.points[i].confidence = point.anchor[0].valid ? point.anchor[0].texture : 0.0;
            result.points[i].lost = result.points[i].confidence < options.min_confidence;
            if (!result.points[i].lost) {
                mask |= 1U << i;
            }
        }
        result.lost = bit_count(mask) < (count == 1 ? 1 : 2);
        initialized = true;
        return result;
    }

    Warp descend(PointState& point, Warp warp, int top_level) {
        const double reference_scale = std::hypot(point.warp.a, point.warp.b);
        for (int level = top_level; level >= 0; --level) {
            const double divisor = 1 << level;
            Warp local{warp.x / divisor, warp.y / divisor, warp.a, warp.b};
            const Patch& anchor = point.anchor[level];
            if (!anchor.valid) {
                continue;
            }
            if (point.refreshed && level > 0) {
                refine(point.working[level], pyramid[level], local, reference_scale,
                       options.max_iterations / 2, samples);
            }
            refine(anchor, pyramid[level], local, reference_scale, options.max_iterations, samples);
            warp = {local.x * divisor, local.y * divisor, local.a, local.b};
        }
        const Patch& anchor = point.anchor[0];
        Warp best = warp;
        double best_score = correlation(anchor, pyramid[0], best);
        for (int y = -1; y <= 1; ++y) {
            for (int x = -1; x <= 1; ++x) {
                if (x == 0 && y == 0) {
                    continue;
                }
                const Warp candidate{warp.x + 0.75 * x, warp.y + 0.75 * y, warp.a, warp.b};
                const double score = correlation(anchor, pyramid[0], candidate);
                if (score > best_score) {
                    best_score = score;
                    best = candidate;
                }
            }
        }
        if (best.x != warp.x || best.y != warp.y) {
            refine(anchor, pyramid[0], best, reference_scale, options.max_iterations, samples);
        }
        return best;
    }

    bool within_search(const PointState& point, const Warp& warp) const {
        return std::abs(warp.x - point.warp.x) <= options.search_radius + 0.1 &&
               std::abs(warp.y - point.warp.y) <= options.search_radius + 0.1 &&
               allowed_shape(warp, std::hypot(point.warp.a, point.warp.b));
    }

    Warp locate(PointState& point) {
        Warp prediction{point.warp.x + std::clamp(point.vx, -double(options.search_radius), double(options.search_radius)),
                        point.warp.y + std::clamp(point.vy, -double(options.search_radius), double(options.search_radius)),
                        point.warp.a * point.da - point.warp.b * point.db,
                        point.warp.b * point.da + point.warp.a * point.db};
        if (!allowed_shape(prediction, std::hypot(point.warp.a, point.warp.b))) {
            prediction.a = point.warp.a;
            prediction.b = point.warp.b;
        }
        Warp best = descend(point, prediction, options.pyramid_levels - 1);
        double best_score = within_search(point, best) ? correlation(point.anchor[0], pyramid[0], best) : -1.0;
        if (best_score >= 0.90) {
            return best;
        }
        int level = options.pyramid_levels - 1;
        while (level > 0 && !point.anchor[level].valid) {
            --level;
        }
        const double divisor = 1 << level;
        const int radius = (options.search_radius + static_cast<int>(divisor) - 1) /
                           static_cast<int>(divisor);
        std::array<Warp, 8> candidates{};
        std::array<double, 8> scores;
        scores.fill(-1.0);
        for (int y = -radius; y <= radius; ++y) {
            for (int x = -radius; x <= radius; ++x) {
                const Warp candidate{point.warp.x / divisor + x, point.warp.y / divisor + y,
                                     point.warp.a, point.warp.b};
                const double score = correlation(point.anchor[level], pyramid[level], candidate);
                std::size_t slot = 0;
                for (std::size_t i = 0; i < candidates.size(); ++i) {
                    if (scores[i] > -1.0 && square(candidate.x - candidates[i].x) +
                        square(candidate.y - candidates[i].y) < 4.0) {
                        slot = i;
                        break;
                    }
                    if (scores[i] < scores[slot]) {
                        slot = i;
                    }
                }
                if (score > scores[slot]) {
                    scores[slot] = score;
                    candidates[slot] = candidate;
                }
            }
        }
        for (std::size_t attempt = 0; attempt < candidates.size(); ++attempt) {
            const auto peak = std::max_element(scores.begin(), scores.end());
            if (*peak <= -1.0) {
                break;
            }
            Warp candidate = candidates[static_cast<std::size_t>(peak - scores.begin())];
            *peak = -1.0;
            candidate.x *= divisor;
            candidate.y *= divisor;
            candidate = descend(point, candidate, level);
            const double score = within_search(point, candidate)
                ? correlation(point.anchor[0], pyramid[0], candidate) : -1.0;
            if (score > best_score) {
                best_score = score;
                best = candidate;
            }
            if (best_score >= 0.995) {
                break;
            }
        }
        return best;
    }

    unsigned consensus(const Result& result, unsigned mask) {
        if (bit_count(mask) <= 2) {
            return mask;
        }
        int best_count = -1;
        double best_weight = -1.0;
        double best_error = std::numeric_limits<double>::infinity();
        unsigned best_mask = mask;
        for (std::size_t i = 0; i < count; ++i) {
            for (std::size_t j = i + 1; j < count; ++j) {
                const unsigned pair = (1U << i) | (1U << j);
                if ((mask & pair) != pair) {
                    continue;
                }
                const Transform model = fit(points, result, pair);
                unsigned inliers = 0;
                double weight = 0.0;
                double error = 0.0;
                for (std::size_t k = 0; k < count; ++k) {
                    if ((mask & (1U << k)) == 0) {
                        continue;
                    }
                    const Point predicted = model.apply(points[k].origin);
                    const double distance = square(predicted.x - result.points[k].position.x) +
                                            square(predicted.y - result.points[k].position.y);
                    if (distance <= 2.25) {
                        inliers |= 1U << k;
                        weight += square(result.points[k].confidence);
                        error += distance;
                    }
                }
                const int inlier_count = bit_count(inliers);
                if (inlier_count > best_count || (inlier_count == best_count &&
                    (weight > best_weight + 1e-9 ||
                     (std::abs(weight - best_weight) <= 1e-9 && error < best_error)))) {
                    best_count = inlier_count;
                    best_weight = weight;
                    best_error = error;
                    best_mask = inliers;
                }
            }
        }
        return best_mask;
    }

    Result update() {
        Result result;
        result.count = count;
        std::array<Warp, 4> found{};
        unsigned mask = 0;
        for (std::size_t i = 0; i < count; ++i) {
            PointState& point = points[i];
            found[i] = point.anchor[0].valid ? locate(point) : point.warp;
            const double score = correlation(point.anchor[0], pyramid[0], found[i]);
            double confidence = point.anchor[0].texture *
                std::clamp((score - minimum_correlation) / (1.0 - minimum_correlation), 0.0, 1.0);
            if (!within_search(point, found[i])) {
                confidence = 0.0;
            }
            result.points[i] = {{found[i].x, found[i].y}, confidence, confidence < options.min_confidence};
            if (!result.points[i].lost) {
                mask |= 1U << i;
            }
        }
        mask = consensus(result, mask);
        if (count > 1 && bit_count(mask) >= 1) {
            Transform model = fit(points, result, mask);
            if (bit_count(mask) == 1) {
                for (std::size_t i = 0; i < count; ++i) {
                    if ((mask & (1U << i)) != 0) {
                        model.a = found[i].a;
                        model.b = found[i].b;
                        model.tx = found[i].x - model.a * points[i].origin.x + model.b * points[i].origin.y;
                        model.ty = found[i].y - model.b * points[i].origin.x - model.a * points[i].origin.y;
                    }
                }
            }
            for (std::size_t i = 0; i < count; ++i) {
                if ((mask & (1U << i)) != 0 || !points[i].anchor[0].valid) {
                    continue;
                }
                PointState& point = points[i];
                const Point predicted = model.apply(point.origin);
                Warp candidate{predicted.x, predicted.y, model.a, model.b};
                const double reference_scale = std::hypot(point.warp.a, point.warp.b);
                for (int level = options.pyramid_levels - 1; level >= 0; --level) {
                    const double divisor = 1 << level;
                    Warp local{candidate.x / divisor, candidate.y / divisor, candidate.a, candidate.b};
                    refine(point.anchor[level], pyramid[level], local, reference_scale,
                           options.max_iterations, samples);
                    candidate = {local.x * divisor, local.y * divisor, local.a, local.b};
                }
                const double score = correlation(point.anchor[0], pyramid[0], candidate);
                const double confidence = point.anchor[0].texture *
                    std::clamp((score - minimum_correlation) / (1.0 - minimum_correlation), 0.0, 1.0);
                if (confidence >= options.min_confidence &&
                    (square(candidate.x - predicted.x) + square(candidate.y - predicted.y)) <= 2.25 &&
                    within_search(point, candidate)) {
                    found[i] = candidate;
                    result.points[i] = {{candidate.x, candidate.y}, confidence, false};
                    mask |= 1U << i;
                }
            }
        }
        result.lost = bit_count(mask) < (count == 1 ? 1 : 2);
        result.transform = result.lost ? group : fit(points, result, mask);
        if (!std::isfinite(result.transform.scale()) || result.transform.scale() < 1e-6) {
            result.lost = true;
            result.transform = group;
        }
        result.motion = result.lost ? Transform{} : relative_transform(result.transform, group);
        const bool refresh = ++refresh_age >= static_cast<unsigned>(options.refresh_interval);
        if (refresh) {
            refresh_age = 0;
        }
        for (std::size_t i = 0; i < count; ++i) {
            PointState& point = points[i];
            if ((mask & (1U << i)) != 0) {
                const Warp previous = point.warp;
                point.warp = found[i];
                point.vx = point.warp.x - previous.x;
                point.vy = point.warp.y - previous.y;
                const double denominator = square(previous.a) + square(previous.b);
                point.da = (point.warp.a * previous.a + point.warp.b * previous.b) / denominator;
                point.db = (point.warp.b * previous.a - point.warp.a * previous.b) / denominator;
                if (refresh && result.points[i].confidence > 0.95) {
                    for (int level = 0; level < options.pyramid_levels; ++level) {
                        const double divisor = 1 << level;
                        extract_patch(point.working[level], pyramid[level],
                            {point.warp.x / divisor, point.warp.y / divisor, point.warp.a, point.warp.b});
                    }
                    point.refreshed = true;
                }
            } else {
                result.points[i].lost = true;
                result.points[i].confidence = std::min(result.points[i].confidence, options.min_confidence * 0.5);
                if (!result.lost) {
                    const Point predicted = result.transform.apply(point.origin);
                    point.vx = predicted.x - point.warp.x;
                    point.vy = predicted.y - point.warp.y;
                    point.warp = {predicted.x, predicted.y, result.transform.a, result.transform.b};
                    point.da = result.motion.a;
                    point.db = result.motion.b;
                } else {
                    point.vx = point.vy = point.db = 0.0;
                    point.da = 1.0;
                }
                result.points[i].position = {point.warp.x, point.warp.y};
            }
        }
        group = result.transform;
        return result;
    }
};

Point Transform::apply(Point point) const noexcept {
    return {a * point.x - b * point.y + tx, b * point.x + a * point.y + ty};
}

double Transform::scale() const noexcept {
    return std::hypot(a, b);
}

double Transform::rotation() const noexcept {
    return std::atan2(b, a);
}

Tracker::Tracker(const Point* points, std::size_t count, Options options) {
    if (points == nullptr || count < 1 || count > 4 ||
        options.patch_radius < 8 || options.patch_radius > 48 ||
        options.search_radius < 1 || options.search_radius > 512 ||
        options.pyramid_levels < 3 || options.pyramid_levels > 4 ||
        options.max_iterations < 1 || options.max_iterations > 100 ||
        options.refresh_interval < 1 || !std::isfinite(options.min_confidence) ||
        options.min_confidence <= 0.0 || options.min_confidence > 1.0) {
        throw std::invalid_argument("invalid tracker configuration");
    }
    for (std::size_t i = 0; i < count; ++i) {
        if (!std::isfinite(points[i].x) || !std::isfinite(points[i].y)) {
            throw std::invalid_argument("nonfinite point");
        }
        for (std::size_t j = 0; j < i; ++j) {
            if (std::hypot(points[i].x - points[j].x, points[i].y - points[j].y) < 0.001) {
                throw std::invalid_argument("coincident points");
            }
        }
    }
    state_ = std::make_unique<State>(points, count, options);
}

Tracker::~Tracker() = default;
Tracker::Tracker(Tracker&&) noexcept = default;
Tracker& Tracker::operator=(Tracker&&) noexcept = default;

Result Tracker::track(Frame frame) {
    if (!state_) {
        throw std::logic_error("moved tracker");
    }
    if (frame.data == nullptr || frame.width < 1 || frame.height < 1 ||
        frame.width > 16384 || frame.height > 16384 || frame.stride < frame.width) {
        throw std::invalid_argument("invalid luma frame");
    }
    if (frame.width != state_->width || frame.height != state_->height) {
        state_->allocate(frame.width, frame.height);
    }
    state_->build_pyramid(frame);
    return state_->initialized ? state_->update() : state_->initialize();
}

void Tracker::reset() noexcept {
    if (state_) {
        state_->initialized = false;
    }
}

}
