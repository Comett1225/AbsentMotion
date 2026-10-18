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
constexpr const char* kMotionBlurAngle = "angle";
constexpr const char* kMotionBlurLength = "length";

constexpr float kDefaultFov = 60;
constexpr float kNearest = 1;
constexpr float kFocusBlurPerDepth = 0.1f;

bool gCameraView = true;

float FocalLength(int width, int height, float fov) {
    const float half = std::clamp(fov, 0.1f, 179.9f) * 0.5f * 3.14159265f / 180;
    return std::max(width, height) / (2 * tanf(half));
}

struct View {
    SkPoint eye, middle;
    float eyeZ = 0, rotation = 0, focal = 1;
    bool orthographic = false;
};

View DefaultView(int width, int height) {
    View v;
    v.middle = v.eye = {width / 2.0f, height / 2.0f};
    v.focal = FocalLength(width, height, kDefaultFov);
    v.eyeZ = -v.focal;
    return v;
}

View LayerView(const std::vector<Layer>& layers, const Layer& camera, int width, int height, double timeMs) {
    const Placement p = EffectivePlacement(layers, camera, timeMs);
    View v;
    v.middle = {width / 2.0f, height / 2.0f};
    v.eye = p.location;
    v.eyeZ = p.z;
    v.rotation = p.rotation;
    v.focal = FocalLength(width, height, camera.camera.fov.get(0, camera.localTime(timeMs), kDefaultFov));
    v.orthographic = camera.camera.orthographic;
    return v;
}

const Layer* ActiveCamera(const std::vector<Layer>& layers, double timeMs) {
    const Layer* active = nullptr;
    for (const Layer& l : layers) {
        if (l.kind != "camera" || l.hidden) continue;
        if (!active || (timeMs >= l.startTime && timeMs <= l.endTime)) active = &l;
    }
    return active;
}

struct Seen {
    SkPoint location;
    SkVector scale;
    float rotation, depth;
    bool visible;
};

Seen Look(const View& v, const Placement& p) {
    Seen s;
    s.depth = p.z - v.eyeZ;
    s.visible = s.depth >= kNearest;
    const float k = v.focal / (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(s.depth, kNearest));
    s.location = v.middle + SkMatrix::RotateDeg(-v.rotation).mapVector((p.location - v.eye) * k);
    s.scale = {p.scale.fX * k, p.scale.fY * k};
    s.rotation = p.rotation - v.rotation;
    return s;
}

Anim Moving(float tBefore, float tNow, std::vector<float> before, std::vector<float> now) {
    Anim a;
    if (tBefore < tNow && before != now) {
        a.keys.push_back({tBefore, std::move(before), {}});
        a.keys.push_back({tNow, std::move(now), {}});
    } else {
        a.value = std::move(now);
    }
    return a;
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

bool HasMotionBlur(const Layer& camera) {
    for (const LayerEffect& e : camera.effects) {
        if (!e.hidden && e.id == kMotionBlurId) return true;
    }
    return false;
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
    if (HasMotionBlur(camera)) {
        const SkVector moved = now - before;
        const float length = moved.length();
        if (length > 0) {
            const float angle = atan2f(moved.fY, moved.fX) * 180 / 3.14159265f;
            seen->effects.push_back(Effect(kMotionBlurId, {{kMotionBlurAngle, {angle}}, {kMotionBlurLength, {length}}}));
        }
    }
}

}

void SetCameraView(bool on) { gCameraView = on; }

bool SeeLayers(const std::vector<Layer>& layers, int width, int height, bool top, int fps, double timeMs, std::vector<Layer>* seen) {
    const Layer* camera = top && gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    if (!camera) {
        bool flat = true;
        for (const Layer& l : layers) flat = flat && EffectivePlacement(layers, l, timeMs).z == 0;
        if (flat) return false;
    }
    const double beforeMs = timeMs - 1000.0 / std::max(fps, 1);
    const View now = camera ? LayerView(layers, *camera, width, height, timeMs) : DefaultView(width, height);
    const View before = camera ? LayerView(layers, *camera, width, height, beforeMs) : now;

    std::vector<Layer> copies;
    std::vector<float> depth;
    std::vector<bool> dropped;
    copies.reserve(layers.size());
    for (const Layer& l : layers) {
        const Placement p = EffectivePlacement(layers, l, timeMs);
        Layer copy = l;
        copy.parent = 0;
        float d = 0;
        bool drop = false;
        const float tNow = l.localTime(timeMs);
        if (l.kind == "camera") {
            copy.location = Anim{{p.location.fX, p.location.fY, p.z}};
            copy.scale = Anim{{p.scale.fX, p.scale.fY}};
            copy.rotation = Anim{{p.rotation}};
        } else {
            const Seen s = Look(now, p);
            const Seen b = Look(before, EffectivePlacement(layers, l, beforeMs));
            const float tBefore = l.localTime(beforeMs);
            d = s.depth;
            drop = !s.visible;
            copy.location = Moving(tBefore, tNow, {b.location.fX, b.location.fY, 0}, {s.location.fX, s.location.fY, 0});
            copy.scale = Moving(tBefore, tNow, {b.scale.fX, b.scale.fY}, {s.scale.fX, s.scale.fY});
            copy.rotation = Moving(tBefore, tNow, {b.rotation}, {s.rotation});
            if (camera) AddCameraEffects(*camera, timeMs, d, b.location, s.location, &copy);
        }
        copies.push_back(std::move(copy));
        depth.push_back(d);
        dropped.push_back(drop);
    }

    std::vector<size_t> starts;
    for (size_t i = 0; i < copies.size(); i++) {
        if (i == 0 || !copies[i].clippingMask) starts.push_back(i);
    }
    std::vector<size_t> order(starts.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return depth[starts[a]] > depth[starts[b]]; });
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
