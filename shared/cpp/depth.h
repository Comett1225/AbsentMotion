#pragma once

#include <functional>
#include <vector>

#include "include/core/SkBlendMode.h"
#include "include/core/SkColor.h"
#include "include/core/SkImage.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPicture.h"
#include "include/core/SkRSXform.h"
#include "include/core/SkSamplingOptions.h"

class SkCanvas;

namespace am {

struct Layer;

namespace depth {

struct Source {
    SkMatrix matrix;
    SkPaint paint;
    SkBlendMode mode = SkBlendMode::kModulate;
    SkSamplingOptions sampling;
    sk_sp<SkImage> sprite;
    std::vector<SkPoint> positions, uvs;
    std::vector<SkColor> colors;
    std::vector<SkRSXform> xforms;
    std::vector<SkRect> cells;
    sk_sp<SkPicture> picture;
    std::function<void(SkCanvas*)> whole;
};

struct Item {
    float depth;
    int source, index;
};

struct Pass {
    const Layer* target = nullptr;
    float depth = 0, perPixel = 0;
    SkPaint look;
    std::vector<Source> sources;
    std::vector<Item> items;

    int add(const SkMatrix& matrix, const SkPaint& paint);
    void triangle(int source, const SkPoint p[3], const SkPoint uv[3], const SkColor color[3], float depth);
    void sprite(int source, const SkRSXform& xform, const SkRect& cell, SkColor color, float depth);
    void picture(sk_sp<SkPicture> picture, const SkMatrix& matrix, float depth);
    void whole(std::function<void(SkCanvas*)> draw, float depth);
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
