#include <algorithm>
#include <cmath>
#include <map>

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkShader.h"
#include "include/core/SkVertices.h"
#include "scene.h"

namespace am {
namespace {

constexpr float kRadians = 3.14159265f / 180;
constexpr float kNear = 1;

std::map<std::string, std::shared_ptr<const Model>>& Models() {
    thread_local std::map<std::string, std::shared_ptr<const Model>> models;
    return models;
}

struct Face {
    float depth;
    int index;
    bool back;
};

}

void SetModel(const std::string& uri, std::shared_ptr<const Model> model) {
    if (model) Models()[uri] = std::move(model);
    else Models().erase(uri);
}

void DrawModel(SkCanvas* canvas, const Layer& l, double timeMs, float alpha, const MediaPictures& pictures) {
    auto found = Models().find(l.mediaUri);
    Placement p;
    if (found == Models().end() || !l.projected || !SeenPlacement(l, timeMs, &p)) return;
    const Model& m = *found->second;
    const float t = l.localTime(timeMs);
    const float size = l.value("size", 0, t, 200);
    const float seconds = (float)((l.steppedMs(timeMs) - l.startTime) / 1000);
    const SkM44 spin = SkM44::Rotate({0, 1, 0}, -l.value("spin", 0, t, 0) * seconds * kRadians);
    const float sx = p.scale.fX, sy = p.scale.fY;
    const SkM44 full = canvas->getLocalToDevice() * p.space * SkM44::Scale(sx, sy, std::sqrt(std::fabs(sx * sy))) *
                       SkM44::Translate(-p.pivot.fX, -p.pivot.fY) * spin * SkM44::Scale(size, size, size);
    const SkM44 turn = p.turn * spin;
    const float mirror = sx * sy < 0 ? -1.0f : 1.0f;
    const float angle = l.value("lightAngle", 0, t, 135) * kRadians;
    const SkV3 light = SkV3{std::cos(angle), -std::sin(angle), -1.2f}.normalize();
    const float ambient = l.value("ambient", 0, t, 0.35f), intensity = l.value("intensity", 0, t, 1);

    const int count = (int)(m.positions.size() / 3);
    std::vector<SkV4> clip(count);
    for (int v = 0; v < count; v++) clip[v] = full.map(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2], 1);
    auto screen = [&](int v) { return SkPoint{clip[v].x / clip[v].w, clip[v].y / clip[v].w}; };

    std::vector<Face> faces;
    faces.reserve(m.triangles.size() / 3);
    for (int k = 0; k + 2 < (int)m.triangles.size(); k += 3) {
        const int a = m.triangles[k], b = m.triangles[k + 1], c = m.triangles[k + 2];
        if (!(clip[a].w >= kNear && clip[b].w >= kNear && clip[c].w >= kNear)) continue;
        const SkPoint pa = screen(a), pb = screen(b), pc = screen(c);
        if (!(pa.isFinite() && pb.isFinite() && pc.isFinite())) continue;
        const bool back = ((pb - pa).cross(pc - pa)) * mirror >= 0;
        if (back && !m.twoSided) continue;
        faces.push_back({clip[a].z + clip[b].z + clip[c].z, k / 3, back});
    }
    std::sort(faces.begin(), faces.end(), [](const Face& x, const Face& y) { return x.depth > y.depth; });

    std::map<int, sk_sp<SkImage>> images;
    auto imageOf = [&](const Face& f) {
        const int slot = m.slots[f.index];
        auto [at, fresh] = images.try_emplace(slot);
        if (fresh && slot >= 0) at->second = pictures(l.mediaUri + "#" + std::to_string(slot), 0);
        return at->second.get();
    };

    canvas->save();
    canvas->resetMatrix();
    for (size_t start = 0, end; start < faces.size(); start = end) {
        SkImage* const image = imageOf(faces[start]);
        for (end = start + 1; end < faces.size() && imageOf(faces[end]) == image;) end++;
        SkVertices::Builder builder(SkVertices::kTriangles_VertexMode, (int)(end - start) * 3, 0,
                                    SkVertices::kHasColors_BuilderFlag | (image ? SkVertices::kHasTexCoords_BuilderFlag : 0));
        SkPoint* positions = builder.positions();
        SkPoint* uvs = builder.texCoords();
        SkColor* colors = builder.colors();
        int out = 0;
        for (auto f = faces.begin() + start; f != faces.begin() + end; ++f) {
            for (int i = 0; i < 3; i++, out++) {
                const int v = m.triangles[f->index * 3 + i];
                positions[out] = screen(v);
                if (uvs) uvs[out] = {m.uvs[v * 2] * image->width(), m.uvs[v * 2 + 1] * image->height()};
                const SkV4 n = turn.map(m.normals[v * 3], m.normals[v * 3 + 1], m.normals[v * 3 + 2], 0);
                const float length = SkV3{n.x, n.y, n.z}.length();
                const float facing = length > 0 ? SkV3{n.x, n.y, n.z}.dot(light) / length * (f->back ? -1.0f : 1.0f) : 0.0f;
                const float k = std::clamp(ambient + intensity * std::max(facing, 0.0f), 0.0f, 4.0f);
                const uint32_t c = m.colors[v];
                auto lit = [&](int shift) { return (U8CPU)std::min(255.0f, ((c >> shift) & 0xff) * k + 0.5f); };
                colors[out] = SkColorSetARGB(c >> 24, lit(16), lit(8), lit(0));
            }
        }
        SkPaint paint;
        paint.setColor4f({1, 1, 1, std::clamp(alpha, 0.0f, 1.0f)});
        if (image) paint.setShader(image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, SkSamplingOptions(SkFilterMode::kLinear)));
        canvas->drawVertices(builder.detach(), SkBlendMode::kModulate, paint);
    }
    canvas->restore();
}

}
