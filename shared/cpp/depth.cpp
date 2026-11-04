#include "depth.h"

#include <algorithm>
#include <cmath>

#include "include/core/SkBlender.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkVertices.h"

namespace am::depth {
namespace {

thread_local Pass* gActive = nullptr;

}

Pass* Active() { return gActive; }

Scope::Scope(Pass* pass) : fPrevious(gActive) { gActive = pass; }

Scope::~Scope() { gActive = fPrevious; }

int Pass::add(const SkMatrix& matrix, const SkPaint& paint) {
    sources.emplace_back();
    sources.back().matrix = matrix;
    sources.back().paint = paint;
    return (int)sources.size() - 1;
}

void Pass::triangle(int source, const SkPoint p[3], const SkPoint uv[3], const SkColor color[3], float at) {
    Source& s = sources[source];
    items.push_back({at, source, (int)s.positions.size() / 3});
    s.positions.insert(s.positions.end(), p, p + 3);
    s.colors.insert(s.colors.end(), color, color + 3);
    if (uv) s.uvs.insert(s.uvs.end(), uv, uv + 3);
}

void Pass::sprite(int source, const SkRSXform& xform, const SkRect& cell, SkColor color, float at) {
    Source& s = sources[source];
    items.push_back({at, source, (int)s.xforms.size()});
    s.xforms.push_back(xform);
    s.cells.push_back(cell);
    s.colors.push_back(color);
}

void Pass::picture(sk_sp<SkPicture> recorded, const SkMatrix& matrix, float at) {
    if (!recorded) return;
    const int s = add(matrix, look);
    sources[s].picture = std::move(recorded);
    items.push_back({at, s, 0});
}

void Pass::whole(std::function<void(SkCanvas*)> draw, float at) {
    const int s = add(SkMatrix::I(), SkPaint());
    sources[s].whole = std::move(draw);
    items.push_back({at, s, 0});
}

SkPaint Pass::styled(SkPaint paint) const {
    if (!look.isSrcOver()) paint.setBlender(look.refBlender());
    return paint;
}

void Pass::draw(SkCanvas* canvas) {
    for (Item& item : items) {
        if (!std::isfinite(item.depth)) item.depth = -INFINITY;
    }
    std::stable_sort(items.begin(), items.end(), [](const Item& a, const Item& b) { return a.depth > b.depth; });
    std::vector<SkRSXform> xforms;
    std::vector<SkRect> cells;
    std::vector<SkColor> colors;
    for (size_t start = 0, end; start < items.size(); start = end) {
        const Source& s = sources[items[start].source];
        for (end = start + 1; end < items.size() && items[end].source == items[start].source; end++) {}
        if (s.whole) {
            s.whole(canvas);
            continue;
        }
        canvas->save();
        canvas->setMatrix(s.matrix);
        if (s.picture) {
            const bool plain = s.paint.isSrcOver() && s.paint.getAlphaf() >= 1;
            canvas->drawPicture(s.picture.get(), nullptr, plain ? nullptr : &s.paint);
        } else if (!s.xforms.empty()) {
            xforms.clear();
            cells.clear();
            colors.clear();
            for (size_t i = start; i < end; i++) {
                const int k = items[i].index;
                xforms.push_back(s.xforms[k]);
                cells.push_back(s.cells[k]);
                colors.push_back(s.colors[k]);
            }
            canvas->drawAtlas(s.sprite.get(), xforms, cells, colors, SkBlendMode::kModulate, s.sampling, nullptr, &s.paint);
        } else {
            const int n = (int)(end - start);
            const bool textured = !s.uvs.empty();
            SkVertices::Builder builder(SkVertices::kTriangles_VertexMode, n * 3, 0,
                                        SkVertices::kHasColors_BuilderFlag | (textured ? SkVertices::kHasTexCoords_BuilderFlag : 0));
            for (int i = 0; i < n; i++) {
                const int k = items[start + i].index * 3;
                std::copy_n(&s.positions[k], 3, builder.positions() + i * 3);
                std::copy_n(&s.colors[k], 3, builder.colors() + i * 3);
                if (textured) std::copy_n(&s.uvs[k], 3, builder.texCoords() + i * 3);
            }
            canvas->drawVertices(builder.detach(), s.mode, s.paint);
        }
        canvas->restore();
    }
}

}
