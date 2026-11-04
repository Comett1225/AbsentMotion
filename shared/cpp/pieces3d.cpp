#include "pieces3d.h"
#include "depth.h"
#include "scene.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkShader.h"
#include "include/core/SkVertices.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

namespace am::pieces3d {
namespace {

constexpr float pi = 3.14159265359f;
constexpr int maxPieces = 20000;

struct V {
    float x, y, z;
    V operator+(V b) const { return {x + b.x, y + b.y, z + b.z}; }
    V operator-(V b) const { return {x - b.x, y - b.y, z - b.z}; }
    V operator*(float s) const { return {x * s, y * s, z * s}; }
};

float dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V cross(V a, V b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
V unit(V a) { const float n = std::sqrt(dot(a, a)); return n > 1e-6f ? a * (1 / n) : V{0, 0, -1}; }
V map(const SkM44& m, V v) { const SkV4 p = m.map(v.x, v.y, v.z, 0); return {p.x, p.y, p.z}; }
SkM44 turn(float x, float y, float z) {
    return SkM44::Rotate({0, 0, 1}, z * pi / 180) * SkM44::Rotate({0, 1, 0}, y * pi / 180) * SkM44::Rotate({1, 0, 0}, x * pi / 180);
}
uint32_t mix(uint32_t x) { x ^= x >> 16; x *= 0x7feb352d; x ^= x >> 15; x *= 0x846ca68b; return x ^ (x >> 16); }
float random(uint32_t seed, uint32_t n) { return (mix(seed ^ mix(n + 1)) >> 8) * (1.0f / 16777216.0f); }

struct Vertex { V p; SkPoint uv; SkColor color; };
struct Tri { std::array<SkPoint, 3> p, uv; std::array<SkColor, 3> color; float z; std::array<float, 3> zs; };

struct Mesh {
    std::vector<Tri> triangles;
    SkPoint center;
    float distance;
    bool textured;
    const SkM44* lens = nullptr;
    float depthKey = 0;

    float key(V p) const { return lens ? lens->map(p.x, p.y, p.z, 1).z : p.z; }
    bool project(V p, SkPoint* out) const {
        if (lens) {
            const SkV4 h = lens->map(p.x, p.y, p.z, 1);
            if (!(h.z >= 1 && h.w > 0)) return false;
            *out = {h.x / h.w, h.y / h.w};
        } else {
            if (p.z <= -distance * 0.95f) return false;
            const float s = distance / (distance + p.z);
            *out = {center.x() + p.x * s, center.y() + p.y * s};
        }
        return out->isFinite();
    }
    void add(Vertex a, Vertex b, Vertex c) {
        SkPoint pa, pb, pc;
        if (!project(a.p, &pa) || !project(b.p, &pb) || !project(c.p, &pc)) return;
        triangles.push_back({{pa, pb, pc}, {a.uv, b.uv, c.uv}, {a.color, b.color, c.color}, depthKey, {key(a.p), key(b.p), key(c.p)}});
    }
    void quad(Vertex a, Vertex b, Vertex c, Vertex d) { add(a, b, c); add(a, c, d); }
    void draw(SkCanvas* canvas, SkImage* image, depth::Pass* pass) {
        if (triangles.empty()) return;
        std::stable_sort(triangles.begin(), triangles.end(), [](const Tri& a, const Tri& b) { return a.z > b.z; });
        if (pass) {
            SkPaint paint;
            paint.setColor(SK_ColorWHITE);
            paint.setAntiAlias(true);
            if (textured && image) paint.setShader(image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kLinear)));
            const int source = pass->add(canvas->getTotalMatrix(), pass->styled(paint));
            for (const Tri& t : triangles) {
                const float depths[3] = {lens ? t.zs[0] : pass->at(t.zs[0]), lens ? t.zs[1] : pass->at(t.zs[1]), lens ? t.zs[2] : pass->at(t.zs[2])};
                pass->triangle(source, t.p.data(), textured ? t.uv.data() : nullptr, t.color.data(), lens ? t.z : pass->at(t.z), depths);
            }
            return;
        }
        SkVertices::Builder builder(SkVertices::kTriangles_VertexMode, (int)triangles.size() * 3, 0,
                                    SkVertices::kHasColors_BuilderFlag | (textured ? SkVertices::kHasTexCoords_BuilderFlag : 0));
        int i = 0;
        for (const Tri& t : triangles) for (int k = 0; k < 3; k++, i++) {
            builder.positions()[i] = t.p[k];
            builder.colors()[i] = t.color[k];
            if (textured) builder.texCoords()[i] = t.uv[k];
        }
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        paint.setAntiAlias(true);
        if (textured && image) paint.setShader(image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kLinear)));
        canvas->drawVertices(builder.detach(), SkBlendMode::kModulate, paint);
    }
};

SkColor shade(SkColor c, float light, float alpha = 1) {
    const auto channel = [&](int v) { return (int)std::clamp(std::round(v * light), 0.0f, 255.0f); };
    return SkColorSetARGB((int)(SkColorGetA(c) * std::clamp(alpha, 0.0f, 1.0f)), channel(SkColorGetR(c)), channel(SkColorGetG(c)), channel(SkColorGetB(c)));
}

SkColor shadeWithLights(SkColor c, float fallback, const LightSet& lights, const PieceView& view, V normal, V position, float alpha = 1) {
    if (!lights.count) return shade(c, fallback, alpha);
    const bool turned = view.turn != SkM44();
    const V n = turned ? map(view.turn, normal) : normal, at = turned ? map(view.turn, position * view.unit) : position * view.unit;
    const SkV3 lit = Illuminate(lights, {n.x, n.y, n.z}, {view.origin.x + at.x, view.origin.y + at.y, view.origin.z + at.z});
    const auto channel = [](int value, float intensity) { return (int)std::clamp(std::round(value * intensity), 0.0f, 255.0f); };
    return SkColorSetARGB((int)(SkColorGetA(c) * std::clamp(alpha, 0.0f, 1.0f)), channel(SkColorGetR(c), lit.x), channel(SkColorGetG(c), lit.y), channel(SkColorGetB(c), lit.z));
}

SkColor sample(const std::vector<unsigned char>& pixels, int w, int h, float x, float y, const SkRect& r) {
    const int ix = std::clamp((int)((x - r.left()) * w / r.width()), 0, w - 1);
    const int iy = std::clamp((int)((y - r.top()) * h / r.height()), 0, h - 1);
    const unsigned char* p = &pixels[((size_t)iy * w + ix) * 4];
    return SkColorSetARGB(p[3], p[0], p[1], p[2]);
}

V aim(const PieceView& view, V light) { return view.turn == SkM44() ? light : unit(map(view.turn.transpose(), light)); }

float lighting(V normal, V light, float intensity, float shading) {
    return std::clamp(1 - shading + shading * (0.25f + intensity * std::max(0.0f, dot(unit(normal), light))), 0.0f, 1.5f);
}

void scatter(Mesh& mesh, const SkRect& area, const SkM44& orientation, const Setting& v, const std::vector<unsigned char>& pixels, int w, int h, const LightSet& lights, const PieceView& view) {
    const int shape = std::clamp((int)std::round(v("shape", 0, 0)), 0, 3);
    const int depth = std::clamp((int)std::round(v("depth", 0, 1)), 1, 16);
    float spacing = std::clamp(v("spacing", 0, 24), 1.0f, 10000.0f);
    spacing = std::max(spacing, std::sqrt(area.width() * area.height() * depth / (maxPieces * 0.85f)));
    int nx, ny;
    do {
        nx = std::max(1, (int)std::ceil(area.width() / spacing));
        ny = std::max(1, (int)std::ceil(area.height() / spacing));
        if ((int64_t)nx * ny * depth <= maxPieces) break;
        spacing *= 1.05f;
    } while (true);
    mesh.triangles.reserve((size_t)nx * ny * depth * (shape == 0 ? 6 : shape == 2 ? 2 : 12));
    const float size = spacing * std::clamp(v("size", 0, 0.8f), 0.0f, 2.0f) * 0.5f;
    const float scatter = std::clamp(v("scatter", 0, 0), 0.0f, 5000.0f);
    const float spin = std::clamp(v("spin", 0, 0), 0.0f, 3600.0f);
    const float dz = std::clamp(v("depthSpacing", 0, 24), 0.0f, 5000.0f);
    const float twist = std::clamp(v("twist", 0, 0), -3600.0f, 3600.0f);
    const int axis = std::clamp((int)std::round(v("axis", 0, 1)), 0, 2);
    const uint32_t seed = (uint32_t)(int)std::clamp(v("seed", 0, 0), -1000000.0f, 1000000.0f);
    const SkM44 grid = orientation * turn(v("rotX", 0, 0), v("rotY", 0, 0), v("rotZ", 0, 0));
    const float angle = v("light", 0, 135) * pi / 180;
    const V light = aim(view, unit({std::cos(angle), -std::sin(angle), -std::clamp(v("lightHeight", 0, 1), 0.0f, 4.0f)}));
    const float intensity = std::clamp(v("intensity", 0, 1), 0.0f, 2.0f), shading = std::clamp(v("shading", 0, 0.6f), 0.0f, 1.0f);
    const int faces[6][4] = {{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{0,1,5,4},{3,7,6,2}};
    const V corners[8] = {{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
    for (int z = 0; z < depth; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
        const float sx = area.left() + (x + 0.5f) * area.width() / nx, sy = area.top() + (y + 0.5f) * area.height() / ny;
        const SkColor color = sample(pixels, w, h, sx, sy, area);
        if (!SkColorGetA(color)) continue;
        const uint32_t index = (uint32_t)((z * ny + y) * nx + x);
        V pos{sx - area.centerX(), sy - area.centerY(), (z - (depth - 1) * 0.5f) * dz};
        const float across = axis == 0 ? pos.x / std::max(area.width(), 1.0f) : axis == 1 ? pos.y / std::max(area.height(), 1.0f) : (depth > 1 ? (float)z / (depth - 1) - 0.5f : 0);
        const SkM44 tw = turn(axis == 0 ? twist * across : 0, axis == 1 ? twist * across : 0, axis == 2 ? twist * across : 0);
        pos = map(tw, pos);
        pos = pos + V{random(seed,index*6)-0.5f, random(seed,index*6+1)-0.5f, random(seed,index*6+2)-0.5f} * (scatter * 2);
        const V center = map(grid, pos);
        mesh.depthKey = mesh.key(center);
        const SkM44 rot = grid * tw * turn((random(seed,index*6+3)-0.5f)*2*spin, (random(seed,index*6+4)-0.5f)*2*spin, (random(seed,index*6+5)-0.5f)*2*spin);
        const auto vert = [&](V p, SkColor c) { return Vertex{center + map(rot, p * size), {}, c}; };
        if (shape == 0) {
            for (const auto& face : faces) {
                const V a = map(rot, corners[face[0]]), b = map(rot, corners[face[1]]), c = map(rot, corners[face[2]]);
                const V n = cross(b - a, c - a);
                if (n.z >= 0) continue;
                const SkColor lit = shadeWithLights(color, lighting(n, light, intensity, shading), lights, view, n, center);
                mesh.quad(vert(corners[face[0]],lit), vert(corners[face[1]],lit), vert(corners[face[2]],lit), vert(corners[face[3]],lit));
            }
        } else if (shape == 2) {
            const V normal = map(rot,{0,0,-1});
            const SkColor lit = shadeWithLights(color, lighting(normal, light, intensity, shading), lights, view, normal, center);
            mesh.quad(vert({-1,-1,0},lit), vert({1,-1,0},lit), vert({1,1,0},lit), vert({-1,1,0},lit));
        } else {
            constexpr int segments = 12;
            const bool sphere = shape == 1;
            const V normal = sphere ? V{0,0,-1} : map(rot,{0,0,-1});
            const SkColor mid = shadeWithLights(color, sphere ? std::min(1.3f, lighting({0,0,-1}, light, intensity, shading) + intensity * shading * 0.3f) : lighting(normal, light, intensity, shading), lights, view, normal, center);
            for (int i = 0; i < segments; i++) {
                const float a = 2*pi*i/segments, b = 2*pi*(i+1)/segments;
                const V p{std::cos(a), std::sin(a), 0}, q{std::cos(b), std::sin(b), 0};
                const SkColor cp = sphere ? shadeWithLights(color, lighting(unit({p.x,p.y,-0.25f}), light, intensity, shading), lights, view, unit({p.x,p.y,-0.25f}), center + p*size) : mid;
                const SkColor cq = sphere ? shadeWithLights(color, lighting(unit({q.x,q.y,-0.25f}), light, intensity, shading), lights, view, unit({q.x,q.y,-0.25f}), center + q*size) : mid;
                if (sphere) {
                    const V highlight = center + V{light.x, light.y, 0} * (size * 0.32f);
                    mesh.add({highlight,{},mid}, {center + p*size,{},cp}, {center + q*size,{},cq});
                } else mesh.add(vert({0,0,0},mid), vert(p,cp), vert(q,cq));
            }
        }
    }
}

void fracture(Mesh& mesh, const SkRect& area, const SkM44& orientation, const Setting& v, int w, int h, const LightSet& lights, const PieceView& view) {
    const int count = std::clamp((int)std::round(v("pieces", 0, 40)), 1, 128);
    mesh.triangles.reserve((size_t)count * 16);
    const uint32_t seed = (uint32_t)(int)std::clamp(v("seed", 0, 0), -1000000.0f, 1000000.0f);
    const float progress = std::clamp(v("progress", 0, 0), 0.0f, 1.0f);
    const float force = std::clamp(v("force", 0, 300), 0.0f, 5000.0f);
    const float spread = std::clamp(v("spread", 0, 1), 0.0f, 1.0f);
    const float direction = v("direction", 0, 0) * pi / 180;
    const float gravity = std::clamp(v("gravity", 0, 100), -5000.0f, 5000.0f);
    const float spin = std::clamp(v("spin", 0, 180), 0.0f, 3600.0f);
    const float push = std::clamp(v("depthPush", 0, 150), -5000.0f, 5000.0f);
    const float thickness = std::clamp(v("thickness", 0, 4), 0.0f, 100.0f);
    const float fade = 1 - std::clamp(v("fade", 0, 1), 0.0f, 1.0f) * std::clamp((progress - 0.7f) / 0.3f, 0.0f, 1.0f);
    const float angle = v("light", 0, 135) * pi / 180;
    const V light = aim(view, unit({std::cos(angle), -std::sin(angle), -1.2f}));
    const float intensity = std::clamp(v("intensity", 0, 1), 0.0f, 2.0f);
    const SkPoint impact{area.centerX() + v("impact", 0, 0), area.centerY() + v("impact", 1, 0)};
    std::array<SkPoint, 128> sites;
    for (int i = 0; i < count; i++) sites[i] = {area.left() + area.width() * random(seed, i * 2), area.top() + area.height() * random(seed, i * 2 + 1)};
    const auto uv = [&](SkPoint p) { return SkPoint{(p.x() - area.left()) * w / area.width(), (p.y() - area.top()) * h / area.height()}; };
    for (int i = 0; i < count; i++) {
        std::array<SkPoint, 256> a, b;
        int n = 4;
        a[0] = {area.left(), area.top()}; a[1] = {area.right(), area.top()};
        a[2] = {area.right(), area.bottom()}; a[3] = {area.left(), area.bottom()};
        for (int j = 0; j < count && n >= 3; j++) {
            if (i == j) continue;
            const SkPoint normal = sites[j] - sites[i];
            const float limit = (sites[j].x() * sites[j].x() + sites[j].y() * sites[j].y() - sites[i].x() * sites[i].x() - sites[i].y() * sites[i].y()) * 0.5f;
            const auto side = [&](SkPoint p) { return p.x() * normal.x() + p.y() * normal.y() - limit; };
            int m = 0;
            for (int k = 0; k < n; k++) {
                const SkPoint p = a[k], q = a[(k + 1) % n];
                const float dp = side(p), dq = side(q);
                if (dp <= 0 && m < 256) b[m++] = p;
                if ((dp < 0 && dq > 0) || (dp > 0 && dq < 0)) {
                    const float f = dp / (dp - dq);
                    if (m < 256) b[m++] = p + (q - p) * f;
                }
            }
            n = m;
            std::copy_n(b.begin(), n, a.begin());
        }
        if (n < 3) continue;
        SkPoint center{0, 0};
        for (int k = 0; k < n; k++) center += a[k];
        center *= 1.0f / n;
        const V radial = unit({center.x() - impact.x(), center.y() - impact.y(), 0});
        const V heading = unit(radial * spread + V{std::cos(direction), -std::sin(direction), 0} * (1 - spread));
        const float variation = 0.6f + 0.8f * random(seed, 1000 + i);
        const V move{heading.x * force * progress * variation, heading.y * force * progress * variation + gravity * progress * progress, push * progress * variation};
        const SkM44 rot = orientation * turn((random(seed,2000+i*3)-0.5f)*2*spin*progress,
                                               (random(seed,2001+i*3)-0.5f)*2*spin*progress,
                                               (random(seed,2002+i*3)-0.5f)*2*spin*progress);
        const V origin = map(orientation, V{center.x() - area.centerX(), center.y() - area.centerY(), 0} + move);
        mesh.depthKey = mesh.key(origin);
        const auto point = [&](SkPoint p, float z) { return origin + map(rot, {p.x() - center.x(), p.y() - center.y(), z}); };
        const V normal = map(rot, {0, 0, -1});
        const float faceLight = 1 + (lighting(normal.z < 0 ? normal : normal * -1, light, intensity, 0.35f) - 1) * progress;
        const SkColor face = shadeWithLights(SK_ColorWHITE, faceLight, lights, view, normal.z < 0 ? normal : normal * -1, origin, fade);
        const auto vertex = [&](SkPoint p, float z, SkColor color) { return Vertex{point(p, z), uv(p), color}; };
        if (thickness > 0 && progress > 0) for (int k = 0; k < n; k++) {
            const SkPoint p = a[k], q = a[(k+1)%n];
            const V edge = map(rot, {q.x()-p.x(), q.y()-p.y(), 0});
            const V side = cross(edge, map(rot, {0,0,1}));
            if (side.z >= 0) continue;
            const SkColor color = shadeWithLights(SK_ColorWHITE, lighting(side, light, intensity, 0.8f), lights, view, side, origin, fade);
            mesh.quad(vertex(p,0,color), vertex(q,0,color), vertex(q,thickness,color), vertex(p,thickness,color));
        }
        for (int k = 1; k + 1 < n; k++) mesh.add(vertex(a[0], 0, face), vertex(a[k], 0, face), vertex(a[k+1], 0, face));
    }
}

}

bool IsEffect(const std::string& id) { return id == "absent.scatter3d" || id == "absent.fracture3d"; }

void Draw(SkCanvas* canvas, const std::string& id, const SkRect& area, const SkM44& orientation, const Setting& value,
          const std::vector<unsigned char>& pixels, int width, int height, SkImage* image, const LightSet& lights, const PieceView& view,
          depth::Pass* pass) {
    if (area.isEmpty() || width <= 0 || height <= 0 || pixels.size() != (size_t)width * height * 4) return;
    const bool textured = id == "absent.fracture3d";
    const float perspective = textured ? 0.5f : std::clamp(value("perspective", 0, 0.5f), 0.0f, 1.0f);
    const float distance = std::max(1.0f, std::min(area.width(), area.height()) * 0.5f * (2 + 40 * std::pow(1.0f - perspective, 3.0f)));
    Mesh mesh{{}, area.center(), distance, textured, view.tilted ? &view.lens : nullptr};
    if (textured) fracture(mesh, area, orientation, value, width, height, lights, view);
    else scatter(mesh, area, orientation, value, pixels, width, height, lights, view);
    mesh.draw(canvas, image, pass);
}

}
