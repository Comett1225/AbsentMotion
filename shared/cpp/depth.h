#pragma once

#include <array>
#include <functional>
#include <vector>

#include "include/core/SkBlendMode.h"
#include "include/core/SkColor.h"
#include "include/core/SkImage.h"
#include "include/core/SkM44.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPicture.h"
#include "include/core/SkRSXform.h"
#include "include/core/SkSamplingOptions.h"

class SkCanvas;

namespace am {

struct Layer;

namespace depth {

struct Cut {
    float a = 0, b = 0, c = 0;
    bool front = true;
};

struct Flat {
    double plane[3] = {0, 0, 0};
    std::array<SkPoint, 8> corners;
    int count = 0;
    SkPoint at = {0, 0};
    double q = 0;
    float focal = 1;
    int cost = 1;
    bool orthographic = false, valid = false;

    double nearness(float depth) const;
};

struct Part {
    int shape = 0, cut = 0, cuts = 0, vertex = 0, vertices = 0;
};

struct Order {
    std::vector<Part> parts;
    std::vector<Cut> cuts;
    std::vector<SkV3> weights;
};

void Begin();
void AddPlane(const Flat& flat);
void AddPoint(SkPoint at, double q);
void AddTriangle(const SkPoint p[3], const double q[3]);
bool Solve(Order* order);
bool Inside(const std::vector<Cut>& cuts, SkPoint p);
void Clip(SkCanvas* canvas, const Cut* cuts, int count);

struct Source {
    SkMatrix matrix;
    SkPaint paint;
    SkBlendMode mode = SkBlendMode::kModulate;
    SkSamplingOptions sampling;
    sk_sp<SkImage> sprite;
    std::vector<SkPoint> positions, uvs;
    std::vector<SkColor> colors;
    std::vector<float> depths;
    std::vector<SkRSXform> xforms;
    std::vector<SkRect> cells;
    sk_sp<SkPicture> picture;
    std::function<void(SkCanvas*)> whole;
    Flat flat;
};

struct Item {
    float depth;
    int source, index;
};

struct Pass {
    const Layer* target = nullptr;
    float depth = 0, perPixel = 0;
    SkPaint look;
    Flat flat;
    std::vector<Source> sources;
    std::vector<Item> items;

    int add(const SkMatrix& matrix, const SkPaint& paint);
    void triangle(int source, const SkPoint p[3], const SkPoint uv[3], const SkColor color[3], float depth, const float* depths = nullptr);
    void sprite(int source, const SkRSXform& xform, const SkRect& cell, SkColor color, float depth);
    void picture(sk_sp<SkPicture> picture, const SkMatrix& matrix, float depth);
    void whole(std::function<void(SkCanvas*)> draw, float depth, const Flat& flat = Flat());
    SkPaint styled(SkPaint paint) const;
    float at(float local) const { return depth + local * perPixel; }
    void draw(SkCanvas* canvas);
};

Pass* Active();

class Scope {
public:
    explicit Scope(Pass* pass);
    ~Scope();

private:
    Pass* fPrevious;
};

}
}
