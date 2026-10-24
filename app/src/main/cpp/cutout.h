#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace cutout {

struct Letterbox {
    int left, top, width, height;
};

Letterbox letterbox(int width, int height, int size);

void unpremultiply(uint8_t *rgba, size_t stride, int width, int height);

void netInput(const uint8_t *rgba, size_t stride, int width, int height, int size, const Letterbox &box, float *planes);

int refine(uint8_t *rgba, size_t stride, int width, int height, const float *alpha, int w, int h, const std::atomic<bool> &cancel);

}
