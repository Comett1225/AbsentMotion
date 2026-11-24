#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <map>
#include <type_traits>

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkShader.h"
#include "include/core/SkVertices.h"
#include "depth.h"
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

bool SetModelParts(Model* model, std::vector<int> parts, std::vector<int> ints, std::vector<float> floats) {
    const size_t faces = model->triangles.size() / 3, count = model->positions.size() / 3;
    if (parts.empty()) parts.assign(faces, -1);
    if (parts.size() != faces) return false;
    model->parts = std::move(parts);
    if (ints.empty() && floats.empty()) return true;
    if (ints.size() < 3) return false;
    const int bones = ints[0], joints = ints[1];
    if (bones <= 0 || joints <= 0 || bones > 65536 || joints > 65536 || (size_t)ints[2] != count) return false;
    const size_t b = (size_t)bones, j = (size_t)joints;
    if (ints.size() != 3 + 2 * b + j + 4 * count || floats.size() != 16 + 16 * b + 16 * j + 10 * count) return false;
    auto skin = std::make_shared<ModelSkin>();
    auto take = [](auto& from, size_t at, size_t n) { return std::vector<typename std::decay_t<decltype(from)>::value_type>(from.begin() + at, from.begin() + at + n); };
    skin->parents = take(ints, 3, b);
    skin->ids = take(ints, 3 + b, b);
    skin->jointBones = take(ints, 3 + 2 * b, j);
    skin->joints = take(ints, 3 + 2 * b + j, 4 * count);
    for (size_t i = 0; i < b; i++) if (skin->parents[i] < -1 || skin->parents[i] >= (int)i) return false;
    for (int bone : skin->jointBones) if (bone < 0 || bone >= bones) return false;
    for (int joint : skin->joints) if (joint < -1 || joint >= joints) return false;
    skin->normalise = SkM44::ColMajor(floats.data());
    skin->locals = take(floats, 16, 16 * b);
    skin->inverseBinds = take(floats, 16 + 16 * b, 16 * j);
    skin->bind = take(floats, 16 + 16 * b + 16 * j, 3 * count);
    skin->bindNormals = take(floats, 16 + 16 * b + 16 * j + 3 * count, 3 * count);
    skin->weights = take(floats, 16 + 16 * b + 16 * j + 6 * count, 4 * count);
    model->skin = std::move(skin);
    return true;
}

namespace {

std::vector<SkM44> BoneWorlds(const ModelSkin& s, const Layer& l, float t, bool* moved) {
    const size_t count = s.parents.size();
    std::vector<SkV3> turn(count, SkV3{0, 0, 0});
    for (const Property& p : l.props) {
        if (p.name.size() < 8 || p.name.compare(0, 5, "bone.") != 0) continue;
        const size_t dot = p.name.size() - 2;
        const char axis = p.name.back();
        if (p.name[dot] != '.' || axis < 'x' || axis > 'z') continue;
        char* end = nullptr;
        const long id = strtol(p.name.c_str() + 5, &end, 10);
        if (end != p.name.c_str() + dot) continue;
        const auto at = std::find(s.ids.begin(), s.ids.end(), (int)id);
        if (at == s.ids.end()) continue;
        const float degrees = p.anim.get(0, t, 0);
        if (!std::isfinite(degrees) || degrees == 0) continue;
        SkV3& r = turn[(size_t)(at - s.ids.begin())];
        (axis == 'x' ? r.x : axis == 'y' ? r.y : r.z) = degrees;
        if (moved) *moved = true;
    }
    std::vector<SkM44> worlds(count);
    for (size_t b = 0; b < count; b++) {
        SkM44 local = SkM44::ColMajor(&s.locals[b * 16]);
        const SkV3 r = turn[b];
        if (r.x != 0 || r.y != 0 || r.z != 0)
            local = local * SkM44::Rotate({0, 0, 1}, r.z * kRadians) * SkM44::Rotate({0, 1, 0}, r.y * kRadians) * SkM44::Rotate({1, 0, 0}, r.x * kRadians);
        worlds[b] = s.parents[b] >= 0 ? worlds[(size_t)s.parents[b]] * local : local;
    }
    return worlds;
}

struct Shape {
    std::vector<float> posed, turned;
    const float* positions(const Model& m) const { return posed.empty() ? m.positions.data() : posed.data(); }
    const float* normals(const Model& m) const { return turned.empty() ? m.normals.data() : turned.data(); }
};

Shape Posed(const Model& m, const Layer& l, float t) {
    Shape shape;
    if (!m.skin) return shape;
    const ModelSkin& s = *m.skin;
    bool moved = false;
    const std::vector<SkM44> worlds = BoneWorlds(s, l, t, &moved);
    if (!moved) return shape;
    std::vector<std::array<float, 16>> joints(s.jointBones.size());
    for (size_t j = 0; j < joints.size(); j++)
        (s.normalise * worlds[(size_t)s.jointBones[j]] * SkM44::ColMajor(&s.inverseBinds[j * 16])).getColMajor(joints[j].data());
    shape.posed = m.positions;
    shape.turned = m.normals;
    const size_t count = m.positions.size() / 3;
    for (size_t v = 0; v < count; v++) {
        float k[16] = {};
        bool any = false;
        for (int i = 0; i < 4; i++) {
            const int joint = s.joints[v * 4 + i];
            const float w = s.weights[v * 4 + i];
            if (joint < 0 || !(w > 0)) continue;
            any = true;
            const float* from = joints[(size_t)joint].data();
            for (int e = 0; e < 16; e++) k[e] += from[e] * w;
        }
        if (!any) continue;
        const float* b = &s.bind[v * 3];
        const float* n = &s.bindNormals[v * 3];
        for (int r = 0; r < 3; r++) {
            shape.posed[v * 3 + r] = k[r] * b[0] + k[4 + r] * b[1] + k[8 + r] * b[2] + k[12 + r];
            shape.turned[v * 3 + r] = k[r] * n[0] + k[4 + r] * n[1] + k[8 + r] * n[2];
        }
    }
    return shape;
}

uint32_t Tinted(uint32_t c, uint32_t tint) {
    auto mix = [&](int shift) { return ((((c >> shift) & 0xff) * ((tint >> shift) & 0xff) + 127) / 255) << shift; };
    return mix(24) | mix(16) | mix(8) | mix(0);
}

SkM44 Spin(const Layer& l, double timeMs) {
    const float seconds = (float)((l.steppedMs(timeMs) - l.startTime) / 1000);
    return SkM44::Rotate({0, 1, 0}, -l.value("spin", 0, l.localTime(timeMs), 0) * seconds * kRadians);
}

SkM44 Body(const Layer& l, const Placement& p, double timeMs) {
    const float size = l.value("size", 0, l.localTime(timeMs), 200);
    const float sx = p.scale.fX, sy = p.scale.fY;
    return p.space * SkM44::Scale(sx, sy, std::sqrt(std::fabs(sx * sy))) * SkM44::Translate(-p.pivot.fX, -p.pivot.fY) * Spin(l, timeMs) *
           SkM44::Scale(size, size, size);
}

}

size_t ModelFaces(const Layer& l) {
    auto found = Models().find(l.mediaUri);
    return found == Models().end() ? 0 : found->second->triangles.size() / 3;
}

SkRect ModelBounds(const Layer& l, double timeMs) {
    auto found = Models().find(l.mediaUri);
    Placement p;
    if (found == Models().end() || !l.projected || !SeenPlacement(l, timeMs, &p)) return SkRect::MakeEmpty();
    const Model& m = *found->second;
    const SkM44 body = Body(l, p, timeMs);
    const Shape shape = Posed(m, l, l.localTime(timeMs));
    const float* positions = shape.positions(m);
    float left = INFINITY, top = INFINITY, right = -INFINITY, bottom = -INFINITY;
    for (size_t v = 0; v + 2 < m.positions.size(); v += 3) {
        const SkV4 c = body.map(positions[v], positions[v + 1], positions[v + 2], 1);
        if (!(c.w >= kNear)) continue;
        const SkPoint at{c.x / c.w, c.y / c.w};
        if (!at.isFinite()) continue;
        left = std::min(left, at.fX);
        top = std::min(top, at.fY);
        right = std::max(right, at.fX);
        bottom = std::max(bottom, at.fY);
    }
    return left <= right && top <= bottom ? SkRect::MakeLTRB(left - 1, top - 1, right + 1, bottom + 1) : SkRect::MakeEmpty();
}

std::vector<float> ModelJoints(const std::vector<Layer>& layers, const Layer& layer, int width, int height, double timeMs, bool depthOrder) {
    auto found = Models().find(layer.mediaUri);
    if (found == Models().end() || !found->second->skin) return {};
    const Layer l = Sighted(layers, layer, width, height, timeMs, depthOrder);
    Placement p;
    if (!l.projected || !SeenPlacement(l, timeMs, &p)) return {};
    const ModelSkin& s = *found->second->skin;
    const SkM44 body = Body(l, p, timeMs) * s.normalise;
    const std::vector<SkM44> worlds = BoneWorlds(s, l, l.localTime(timeMs), nullptr);
    std::vector<float> xy;
    xy.reserve(worlds.size() * 2);
    for (const SkM44& world : worlds) {
        const SkV4 c = (body * world).map(0, 0, 0, 1);
        const bool seen = c.w >= kNear;
        xy.push_back(seen ? c.x / c.w : NAN);
        xy.push_back(seen ? c.y / c.w : NAN);
    }
    return xy;
}

void DrawModel(SkCanvas* canvas, const Layer& l, double timeMs, float alpha, const MediaPictures& pictures) {
    auto found = Models().find(l.mediaUri);
    Placement p;
    if (found == Models().end() || !l.projected || !SeenPlacement(l, timeMs, &p)) return;
    const Model& m = *found->second;
    const float t = l.localTime(timeMs);
    const float size = l.value("size", 0, t, 200);
    const SkM44 spin = Spin(l, timeMs);
    const float sx = p.scale.fX, sy = p.scale.fY;
    const SkM44 full = canvas->getLocalToDevice() * Body(l, p, timeMs);
    const float sz = std::sqrt(std::fabs(sx * sy));
    const SkM44 unscale = SkM44::Scale(sx != 0 ? 1 / sx : 0, sy != 0 ? 1 / sy : 0, sz != 0 ? 1 / sz : 0);
    const SkM44 turn = p.turn * unscale * spin;
    const SkM44 worldTurn = SkM44::Rotate({0, 0, 1}, p.rotation * kRadians) * p.tilt * unscale * spin;
    const SkM44 worldPlace = SkM44::Rotate({0, 0, 1}, p.rotation * kRadians) * p.tilt * SkM44::Scale(sx, sy, sz) *
                             SkM44::Translate(-p.pivot.fX, -p.pivot.fY) * spin * SkM44::Scale(size, size, size);
    const LightSet lights = l.sight.layers ? SceneLights(*l.sight.layers, timeMs) : LightSet{};
    const float mirror = sx * sy < 0 ? -1.0f : 1.0f;
    const float angle = l.value("lightAngle", 0, t, 135) * kRadians;
    const SkV3 light = SkV3{std::cos(angle), -std::sin(angle), -1.2f}.normalize();
    const float ambient = l.value("ambient", 0, t, 0.35f), intensity = l.value("intensity", 0, t, 1);

    const Shape shape = Posed(m, l, t);
    const float* vertexAt = shape.positions(m);
    const float* normalAt = shape.normals(m);
    std::vector<uint32_t> tints;
    if (!l.tints.empty() && !m.parts.empty()) {
        const int most = *std::max_element(m.parts.begin(), m.parts.end());
        if (most >= 0) tints.assign((size_t)most + 1, 0xffffffff);
        for (const auto& [index, tint] : l.tints) if (index >= 0 && (size_t)index < tints.size()) tints[(size_t)index] = tint;
    }
    const int count = (int)(m.positions.size() / 3);
    std::vector<SkV4> clip(count);
    for (int v = 0; v < count; v++) clip[v] = full.map(vertexAt[v * 3], vertexAt[v * 3 + 1], vertexAt[v * 3 + 2], 1);
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
        const float depth = clip[a].z + clip[b].z + clip[c].z;
        if (std::isfinite(depth)) faces.push_back({depth, k / 3, back});
    }
    std::sort(faces.begin(), faces.end(), [](const Face& x, const Face& y) { return x.depth > y.depth; });

    std::map<int, sk_sp<SkImage>> images;
    auto imageOf = [&](const Face& f) {
        const int slot = m.slots[f.index];
        auto [at, fresh] = images.try_emplace(slot);
        if (fresh && slot >= 0) at->second = pictures(l.mediaUri + "#" + std::to_string(slot), 0);
        return at->second.get();
    };

    depth::Pass* const pass = depth::Active() && depth::Active()->target == &l ? depth::Active() : nullptr;
    std::map<SkImage*, int> sources;
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
            const int part = tints.empty() ? -1 : m.parts[(size_t)f->index];
            const uint32_t tint = part >= 0 && (size_t)part < tints.size() ? tints[(size_t)part] : 0xffffffff;
            for (int i = 0; i < 3; i++, out++) {
                const int v = m.triangles[f->index * 3 + i];
                positions[out] = screen(v);
                if (uvs) uvs[out] = {m.uvs[v * 2] * image->width(), m.uvs[v * 2 + 1] * image->height()};
                const SkV4 n = turn.map(normalAt[v * 3], normalAt[v * 3 + 1], normalAt[v * 3 + 2], 0);
                const float length = SkV3{n.x, n.y, n.z}.length();
                const float facing = length > 0 ? SkV3{n.x, n.y, n.z}.dot(light) / length * (f->back ? -1.0f : 1.0f) : 0.0f;
                const float k = std::clamp(ambient + intensity * std::max(facing, 0.0f), 0.0f, 4.0f);
                const uint32_t c = tint == 0xffffffff ? m.colors[v] : Tinted(m.colors[v], tint);
                SkV3 illumination{k, k, k};
                if (lights.count) {
                    const SkV4 wn = worldTurn.map(normalAt[v * 3], normalAt[v * 3 + 1], normalAt[v * 3 + 2], 0);
                    const SkV4 wp = worldPlace.map(vertexAt[v * 3], vertexAt[v * 3 + 1], vertexAt[v * 3 + 2], 1);
                    const float side = f->back ? -1.0f : 1.0f;
                    illumination = Illuminate(lights, {wn.x * side, wn.y * side, wn.z * side}, {wp.x + p.location.x(), wp.y + p.location.y(), wp.z + p.z});
                }
                auto lit = [&](int shift, float value) { return (U8CPU)std::min(255.0f, ((c >> shift) & 0xff) * std::clamp(value, 0.0f, 4.0f) + 0.5f); };
                colors[out] = SkColorSetARGB(c >> 24, lit(16, illumination.x), lit(8, illumination.y), lit(0, illumination.z));
            }
        }
        SkPaint paint;
        paint.setColor4f({1, 1, 1, std::clamp(alpha, 0.0f, 1.0f)});
        if (image) paint.setShader(image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, SkSamplingOptions(SkFilterMode::kLinear)));
        if (pass) {
            auto [at, fresh] = sources.try_emplace(image);
            if (fresh) at->second = pass->add(SkMatrix::I(), pass->styled(paint));
            for (size_t k = 0; k < end - start; k++) {
                const Face& f = faces[start + k];
                const int* corner = &m.triangles[f.index * 3];
                const float depths[3] = {clip[corner[0]].z, clip[corner[1]].z, clip[corner[2]].z};
                pass->triangle(at->second, positions + k * 3, uvs ? uvs + k * 3 : nullptr, colors + k * 3, f.depth / 3, depths);
            }
            continue;
        }
        canvas->drawVertices(builder.detach(), SkBlendMode::kModulate, paint);
    }
    canvas->restore();
}

}
