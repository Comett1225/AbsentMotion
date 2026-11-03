#include <algorithm>
#include <cmath>
#include <numeric>

#include "include/core/SkMatrix.h"
#include "scene.h"

namespace am {
namespace {

constexpr const char* kFocusBlurId = "absent.blur";
constexpr const char* kFocusBlurStrength = "strength";
constexpr const char* kFogId = "absent.solid";
constexpr const char* kFogColor = "color";
constexpr const char* kFogAmount = "amount";
constexpr const char* kMotionBlurId = "absent.motionblur";
constexpr const char* kMotionBlurTune = "tune";
constexpr const char* kSmearId = "absent.smear";
constexpr const char* kSmearAngle = "angle";
constexpr const char* kSmearLength = "length";

constexpr float kDefaultFov = 60;
constexpr float kNearest = 1;
constexpr float kFocusBlurPerDepth = 0.1f;

thread_local bool gCameraView = true;

float FocalLength(int width, int height, float fov) {
    const float half = std::clamp(fov, 0.1f, 179.9f) * 0.5f * 3.14159265f / 180;
    return std::max(width, height) / (2 * tanf(half));
}

struct View {
    SkPoint eye, middle;
    float eyeZ = 0, rotation = 0, focal = 1;
    bool orthographic = false;
    SkM44 tilt;
};

View DefaultView(int width, int height) {
    View v;
    v.middle = v.eye = {width / 2.0f, height / 2.0f};
    v.focal = FocalLength(width, height, kDefaultFov);
    v.eyeZ = -v.focal;
    return v;
}

View LayerView(const std::vector<Layer>& layers, const Layer& camera, int width, int height, double timeMs, bool depthOrder = false) {
    const Placement p = EffectivePlacement(layers, camera, timeMs);
    View v;
    v.middle = {width / 2.0f, height / 2.0f};
    v.eye = p.location;
    v.eyeZ = p.z;
    v.rotation = p.rotation;
    if (depthOrder) v.tilt = p.tilt.transpose();
    v.focal = FocalLength(width, height, camera.camera.fov.get(0, camera.localTime(timeMs), kDefaultFov));
    v.orthographic = camera.camera.orthographic;
    return v;
}


struct Seen {
    SkPoint location;
    SkVector scale;
    float rotation, depth;
    bool visible;
};

Seen Look(const View& v, const Placement& p) {
    Seen s;
    SkV4 point{p.location.x() - v.eye.x(), p.location.y() - v.eye.y(), p.z - v.eyeZ, 0};
    if (v.tilt != SkM44()) {
        const SkVector rotated = SkMatrix::RotateDeg(-v.rotation).mapVector({point.x, point.y});
        point = v.tilt.map(rotated.x(), rotated.y(), point.z, 0);
    }
    s.depth = point.z;
    s.visible = s.depth >= kNearest;
    const float k = v.focal / (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(s.depth, kNearest));
    s.location = v.middle + (v.tilt == SkM44() ? SkMatrix::RotateDeg(-v.rotation).mapVector(SkVector{point.x, point.y} * k) : SkVector{point.x * k, point.y * k});
    s.scale = {p.scale.fX * k, p.scale.fY * k};
    s.rotation = p.rotation - v.rotation;
    return s;
}

SkM44 Space(const View& v, const Placement& p) {
    const float r = 3.14159265f / 180, mx = v.middle.fX, my = v.middle.fY, f = v.focal;
    const SkM44 world = SkM44::Translate(p.location.fX, p.location.fY, p.z) * SkM44::Rotate({0, 0, 1}, p.rotation * r) * p.tilt;
    const SkM44 eye = v.tilt * SkM44::Rotate({0, 0, 1}, -v.rotation * r) * SkM44::Translate(-v.eye.fX, -v.eye.fY, -v.eyeZ);
    const float c = std::max(-v.eyeZ, kNearest);
    const SkM44 lens = v.orthographic ? SkM44(f, 0, 0, mx * c, 0, f, 0, my * c, 0, 0, 1, 0, 0, 0, 0, c)
                                      : SkM44(f, 0, mx, 0, 0, f, my, 0, 0, 0, 1, 0, 0, 0, 1, 0);
    return lens * eye * world;
}

bool Solid(const Layer& l, const Placement& p) { return p.tilt != SkM44() || l.kind == "model"; }

bool HasComponent(const Anim& a, size_t component) {
    const auto nonzero = [component](const std::vector<float>& v) { return component < v.size() && v[component] != 0; };
    if (nonzero(a.value) || (a.hasLead && nonzero(a.lead.v))) return true;
    return std::any_of(a.keys.begin(), a.keys.end(), [&](const Keyframe& k) { return nonzero(k.v); });
}

bool Spatial(const Layer& l, const Placement& p, double timeMs) {
    if (l.force3d || p.z != 0 || Solid(l, p) || HasComponent(l.location, 2) || HasComponent(l.tilt, 0) || HasComponent(l.tilt, 1)) return true;
    return std::any_of(l.effects.begin(), l.effects.end(), [&](const LayerEffect& e) {
        return !e.hidden && ((ParticleEffect(e.id) && e.value("depth", l.localTime(timeMs), 0) >= 0.5f) || e.id == "absent.scatter3d" || e.id == "absent.fracture3d");
    });
}

LayerEffect Effect(const char* id, std::vector<std::pair<const char*, std::vector<float>>> values) {
    LayerEffect e;
    e.id = id;
    for (auto& [name, value] : values) {
        Property p;
        p.name = name;
        p.type = value.size() == 4 ? "color" : "float";
        p.anim.value = std::move(value);
        e.props.push_back(std::move(p));
    }
    return e;
}

float MotionBlurTune(const Layer& camera, float t) {
    for (const LayerEffect& e : camera.effects) {
        if (e.hidden || e.id != kMotionBlurId) continue;
        for (const Property& p : e.props) {
            if (p.name == kMotionBlurTune) return std::clamp(p.anim.get(0, t, 1), 0.0f, 4.0f);
        }
        return 1;
    }
    return 0;
}

void AddCameraEffects(const Layer& camera, double timeMs, float depth, SkPoint before, SkPoint now, Layer* seen) {
    const Camera& c = camera.camera;
    const float t = camera.localTime(timeMs);
    if (c.focusBlur) {
        const float strength = c.focusStrength.get(0, t, 0.5f), band = c.focusDepth.get(0, t, 100) / 2;
        const float outside = std::fabs(depth - c.focusDistance.get(0, t, 500)) - band;
        const float radius = strength * outside * kFocusBlurPerDepth;
        if (radius > 0) seen->effects.push_back(Effect(kFocusBlurId, {{kFocusBlurStrength, {radius}}}));
    }
    if (c.fog) {
        const float nearZ = c.fogNear.get(0, t, 0), farZ = c.fogFar.get(0, t, 500);
        const float amount = farZ > nearZ ? std::clamp((depth - nearZ) / (farZ - nearZ), 0.0f, 1.0f) : depth >= nearZ ? 1 : 0;
        if (amount > 0) {
            std::vector<float> color = {c.fogColor.get(0, t, 1), c.fogColor.get(1, t, 1), c.fogColor.get(2, t, 1), c.fogColor.get(3, t, 1)};
            seen->effects.push_back(Effect(kFogId, {{kFogColor, color}, {kFogAmount, {amount}}}));
        }
    }
    if (const float tune = MotionBlurTune(camera, t); tune > 0) {
        const SkVector moved = now - before;
        const float length = moved.length() * tune;
        if (length > 0) {
            const float angle = atan2f(moved.fY, moved.fX) * 180 / 3.14159265f;
            seen->effects.push_back(Effect(kSmearId, {{kSmearAngle, {angle}}, {kSmearLength, {length}}}));
        }
    }
}

}

SkMatrix PlaneMatrix(const std::vector<Layer>& layers, const Layer& layer, int width, int height, double timeMs) {
    const Layer* camera = gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    const View v = camera ? LayerView(layers, *camera, width, height, timeMs) : DefaultView(width, height);
    const float depth = EffectivePlacement(layers, layer, timeMs).z - v.eyeZ;
    const float k = v.focal / (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(depth, kNearest));
    return SkMatrix::Translate(v.middle.fX, v.middle.fY).preRotate(-v.rotation).preScale(k, k).preTranslate(-v.eye.fX, -v.eye.fY);
}

void SetCameraView(bool on) { gCameraView = on; }

bool CameraViewOn() { return gCameraView; }

LightSet SceneLights(const std::vector<Layer>& layers, double timeMs) {
    LightSet result;
    for (const Layer& layer : layers) {
        if (layer.kind != "light" || !layer.activeAt(timeMs)) continue;
        if (result.count == (int)result.lights.size()) break;
        const Placement p = EffectivePlacement(layers, layer, timeMs);
        const float t = layer.localTime(timeMs);
        const SkM44 turn = SkM44::Rotate({0, 0, 1}, p.rotation * 3.14159265f / 180) * p.tilt;
        const SkV4 d = turn.map(0, 0, -1, 0);
        Light& light = result.lights[result.count++];
        light.type = layer.lightType == "ambient" ? 0 : layer.lightType == "point" ? 2 : 1;
        light.position = {p.location.x(), p.location.y(), p.z};
        light.direction = {d.x, d.y, d.z};
        light.color = {std::clamp(layer.value("color", 1, t, 1), 0.0f, 1.0f), std::clamp(layer.value("color", 2, t, 1), 0.0f, 1.0f), std::clamp(layer.value("color", 3, t, 1), 0.0f, 1.0f)};
        light.intensity = std::clamp(layer.value("intensity", 0, t, 1), 0.0f, 4.0f);
        light.falloff = std::clamp(layer.value("falloff", 0, t, 0), 0.0f, 100.0f);
    }
    return result;
}

SkV3 Illuminate(const LightSet& lights, SkV3 normal, SkV3 position) {
    SkV3 sum{0, 0, 0};
    const float length = normal.length();
    if (length > 0) normal = normal * (1 / length);
    for (int i = 0; i < lights.count; i++) {
        const Light& l = lights.lights[i];
        float amount = l.intensity;
        if (l.type != 0) {
            SkV3 direction = l.direction;
            if (l.type == 2) {
                direction = {l.position.x - position.x, l.position.y - position.y, l.position.z - position.z};
                const float distance = direction.length();
                if (distance > 0) direction = direction * (1 / distance);
                const float scaled = distance / 1000;
                amount /= 1 + l.falloff * scaled * scaled;
            }
            amount *= std::max(0.0f, normal.dot(direction));
        }
        sum.x += amount * l.color.x;
        sum.y += amount * l.color.y;
        sum.z += amount * l.color.z;
    }
    return sum;
}

bool CameraFrame(const std::vector<Layer>& layers, const Layer& camera, int width, int height, double timeMs, SkPoint corners[4]) {
    if (width <= 0 || height <= 0) return false;
    const Placement p = EffectivePlacement(layers, camera, timeMs);
    const float distance = camera.camera.orthographic ? std::max(-p.z, kNearest) : -p.z;
    if (!(distance > 0)) return false;
    const float reach = distance / FocalLength(width, height, camera.camera.fov.get(0, camera.localTime(timeMs), kDefaultFov));
    const float w = width * 0.5f * reach, h = height * 0.5f * reach;
    SkRect::MakeLTRB(-w, -h, w, h).toQuad(corners);
    SkMatrix::Translate(p.location.fX, p.location.fY).preRotate(p.rotation).mapPoints({corners, 4});
    return true;
}

bool SeenPlacement(const Layer& l, double timeMs, Placement* out) {
    const Sight& s = l.sight;
    if (!s.layers) return false;
    Placement p = EffectivePlacement(*s.layers, *s.layer, timeMs);
    if (l.kind != "camera") {
        const View v = s.camera ? LayerView(*s.layers, *s.camera, s.width, s.height, timeMs, s.depthOrder) : DefaultView(s.width, s.height);
        if (l.projected) {
            p.space = Space(v, p);
            p.turn = v.tilt * SkM44::Rotate({0, 0, 1}, (p.rotation - v.rotation) * 3.14159265f / 180) * p.tilt;
        } else {
            const Seen seen = Look(v, p);
            p.location = seen.location;
            p.scale = seen.scale;
            p.rotation = seen.rotation;
            p.z = 0;
        }
    }
    *out = p;
    return true;
}

bool SeeLayers(const std::vector<Layer>& layers, int width, int height, bool top, int fps, double timeMs, bool depthOrder, std::vector<Layer>* seen) {
    const Layer* camera = top && gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    if (!camera) {
        bool flat = true;
        for (const Layer& l : layers) {
            const Placement p = EffectivePlacement(layers, l, timeMs);
            flat = flat && p.z == 0 && !Solid(l, p);
        }
        if (flat) return false;
    }
    const double beforeMs = timeMs - 1000.0 / std::max(fps, 1);
    const View now = camera ? LayerView(layers, *camera, width, height, timeMs, depthOrder) : DefaultView(width, height);
    const View before = camera ? LayerView(layers, *camera, width, height, beforeMs, depthOrder) : now;

    std::vector<Layer> copies;
    std::vector<float> depth;
    std::vector<bool> dropped;
    static thread_local std::vector<bool> spatial;
    spatial.clear();
    copies.reserve(layers.size());
    for (const Layer& l : layers) {
        const Placement p = EffectivePlacement(layers, l, timeMs);
        Layer copy = l;
        copy.parent = 0;
        copy.placed = true;
        copy.sight = {&layers, &l, camera, width, height, depthOrder};
        float d = 0;
        bool drop = false;
        if (l.kind != "camera") {
            const Seen s = Look(now, p);
            d = s.depth;
            drop = !s.visible;
            copy.projected = Solid(l, p) || (camera && now.tilt != SkM44() && Spatial(l, p, timeMs));
            if (camera) AddCameraEffects(*camera, timeMs, d, Look(before, EffectivePlacement(layers, l, beforeMs)).location, s.location, &copy);
        }
        copies.push_back(std::move(copy));
        depth.push_back(d);
        dropped.push_back(drop);
        if (depthOrder && camera) spatial.push_back(Spatial(l, p, timeMs));
    }

    std::vector<size_t> starts;
    for (size_t i = 0; i < copies.size(); i++) {
        if (i == 0 || !copies[i].clippingMask) starts.push_back(i);
    }
    std::vector<size_t> order(starts.size());
    std::iota(order.begin(), order.end(), 0);
    const auto farther = [&](size_t a, size_t b) { return depth[starts[a]] > depth[starts[b]]; };
    if (!depthOrder || !camera) {
        std::stable_sort(order.begin(), order.end(), farther);
    } else {
        static thread_local std::vector<size_t> slots, sorted;
        slots.clear();
        sorted.clear();
        slots.reserve(starts.size());
        sorted.reserve(starts.size());
        const auto flush = [&] {
            sorted.clear();
            for (size_t slot : slots) sorted.push_back(order[slot]);
            std::sort(sorted.begin(), sorted.end(), [&](size_t a, size_t b) {
                const float da = depth[starts[a]], db = depth[starts[b]];
                if (std::isfinite(da) != std::isfinite(db)) return std::isfinite(da);
                if (da > db) return true;
                if (db > da) return false;
                return a < b;
            });
            for (size_t i = 0; i < slots.size(); i++) order[slots[i]] = sorted[i];
            slots.clear();
        };
        for (size_t i = 0; i < starts.size(); i++) {
            const Layer& layer = layers[starts[i]];
            if (layer.kind == "camera" || layer.kind == "nullobj" || layer.kind == "light") continue;
            if (spatial[starts[i]]) slots.push_back(i);
            else flush();
        }
        flush();
    }
    seen->clear();
    for (size_t k : order) {
        const size_t first = starts[k], end = k + 1 < starts.size() ? starts[k + 1] : copies.size();
        if (dropped[first]) continue;
        for (size_t i = first; i < end; i++) {
            if (!dropped[i]) seen->push_back(std::move(copies[i]));
        }
    }
    return true;
}

}
