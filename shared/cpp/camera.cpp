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

SkM44 Lens(const View& v) {
    const float mx = v.middle.fX, my = v.middle.fY, f = v.focal, c = std::max(-v.eyeZ, kNearest);
    return v.orthographic ? SkM44(f, 0, 0, mx * c, 0, f, 0, my * c, 0, 0, 1, 0, 0, 0, 0, c)
                          : SkM44(f, 0, mx, 0, 0, f, my, 0, 0, 0, 1, 0, 0, 0, 1, 0);
}

SkM44 Space(const View& v, const Placement& p) {
    const float r = 3.14159265f / 180;
    const SkM44 world = SkM44::Translate(p.location.fX, p.location.fY, p.z) * SkM44::Rotate({0, 0, 1}, p.rotation * r) * p.tilt;
    const SkM44 eye = v.tilt * SkM44::Rotate({0, 0, 1}, -v.rotation * r) * SkM44::Translate(-v.eye.fX, -v.eye.fY, -v.eyeZ);
    return Lens(v) * eye * world;
}

bool Solid(const Layer& l, const Placement& p) { return p.tilt != SkM44() || l.kind == "model"; }

bool HasComponent(const Anim& a, size_t component) {
    const auto nonzero = [component](const std::vector<float>& v) { return component < v.size() && v[component] != 0; };
    if (nonzero(a.value) || (a.hasLead && nonzero(a.lead.v))) return true;
    return std::any_of(a.keys.begin(), a.keys.end(), [&](const Keyframe& k) { return nonzero(k.v); });
}

bool Deep(const Layer& l, double timeMs) {
    return std::any_of(l.effects.begin(), l.effects.end(), [&](const LayerEffect& e) {
        return !e.hidden && ((ParticleEffect(e.id) && e.value("depth", l.localTime(timeMs), 0) >= 0.5f) || e.id == "absent.scatter3d" || e.id == "absent.fracture3d");
    });
}

bool Spatial(const Layer& l, const Placement& p, double timeMs) {
    if (l.force3d || p.z != 0 || Solid(l, p) || HasComponent(l.location, 2) || HasComponent(l.tilt, 0) || HasComponent(l.tilt, 1)) return true;
    return Deep(l, timeMs);
}

bool Idle(const Layer& l, double timeMs) { return l.kind == "camera" || l.kind == "nullobj" || l.kind == "light" || !l.activeAt(timeMs); }

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

SkMatrix PlaneMatrix(const std::vector<Layer>& layers, const Layer& layer, int width, int height, double timeMs, bool depthOrder) {
    const Layer* camera = gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    const View v = camera ? LayerView(layers, *camera, width, height, timeMs, depthOrder) : DefaultView(width, height);
    const Placement p = EffectivePlacement(layers, layer, timeMs);
    if (v.tilt != SkM44()) {
        Placement plane{};
        plane.z = p.z;
        const SkM44 m = Space(v, plane);
        const float x = p.location.x(), y = p.location.y();
        const float u = m.rc(0, 0) * x + m.rc(0, 1) * y + m.rc(0, 3), w = m.rc(3, 0) * x + m.rc(3, 1) * y + m.rc(3, 3);
        const float s = m.rc(1, 0) * x + m.rc(1, 1) * y + m.rc(1, 3);
        if (w > 0) {
            const float a = (m.rc(0, 0) * w - u * m.rc(3, 0)) / (w * w), b = (m.rc(0, 1) * w - u * m.rc(3, 1)) / (w * w);
            const float c = (m.rc(1, 0) * w - s * m.rc(3, 0)) / (w * w), d = (m.rc(1, 1) * w - s * m.rc(3, 1)) / (w * w);
            const SkMatrix local = SkMatrix::MakeAll(a, b, u / w - a * x - b * y, c, d, s / w - c * x - d * y, 0, 0, 1);
            if (local.isFinite() && local.invert(nullptr)) return local;
        }
    }
    const float depth = p.z - v.eyeZ;
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
            if (v.tilt != SkM44() && Spatial(l, p, timeMs)) p.turn = v.tilt;
            p.location = seen.location;
            p.scale = seen.scale;
            p.rotation = seen.rotation;
            p.z = 0;
        }
    }
    *out = p;
    return true;
}

void SeenPieces(const Layer& l, double timeMs, SkPoint center, const SkM44& orientation, PieceView* view) {
    const Sight& s = l.sight;
    if (!s.layers || !s.layer || !s.camera || l.projected || l.kind == "camera") return;
    const View v = LayerView(*s.layers, *s.camera, s.width, s.height, timeMs, s.depthOrder);
    const Placement p = EffectivePlacement(*s.layers, *s.layer, timeMs);
    const SkM44 turn = v.tilt * SkM44::Rotate({0, 0, 1}, -v.rotation * 3.14159265f / 180);
    const SkV4 anchor = turn.map(p.location.fX - v.eye.fX, p.location.fY - v.eye.fY, p.z - v.eyeZ, 0);
    const float k = v.focal / (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(anchor.z, kNearest));
    view->unit = 1 / k;
    view->turn = turn.transpose();
    view->tilted = v.tilt != SkM44();
    if (!view->tilted) return;
    const SkPoint seen = Look(v, p).location;
    const SkV4 shift = orientation.map((center.fX - seen.fX) / k, (center.fY - seen.fY) / k, 0, 0);
    view->lens = Lens(v) * SkM44::Translate(anchor.x + shift.x, anchor.y + shift.y, anchor.z + shift.z) * SkM44::Scale(view->unit, view->unit, view->unit);
}

bool SeenFlat(const Layer& l, double timeMs, depth::Flat* out) {
    *out = depth::Flat();
    const Sight& s = l.sight;
    if (!s.layers || !s.layer) return false;
    const View v = s.camera ? LayerView(*s.layers, *s.camera, s.width, s.height, timeMs, s.depthOrder) : DefaultView(s.width, s.height);
    const Seen seen = Look(v, EffectivePlacement(*s.layers, *s.layer, timeMs));
    out->focal = v.focal;
    out->orthographic = v.orthographic;
    out->at = seen.location;
    out->q = out->nearness(seen.depth);
    out->valid = seen.location.isFinite() && std::isfinite(out->q);
    out->cost = std::any_of(l.effects.begin(), l.effects.end(), [](const LayerEffect& e) { return !e.hidden; }) ? 4 : 1;
    Placement p;
    if (!out->valid || l.kind == "model" || HasRepeat(l) || Deep(l, timeMs) || !SeenPlacement(l, timeMs, &p)) return out->valid;
    const float t = l.localTime(timeMs);
    const SkRect box = ShapePath(l, t).getBounds();
    if (box.isEmpty() || !box.isFinite()) return true;
    SkMatrix local;
    local.setSkew(tanf(l.skew.get(0, t, 0)), tanf(l.skew.get(1, t, 0)));
    local.preScale(p.scale.fX, p.scale.fY);
    local.preTranslate(-p.pivot.fX, -p.pivot.fY);
    SkPoint corners[4];
    box.toQuad(corners);
    std::array<SkV4, 8> clip;
    int n = 0;
    if (l.projected) {
        const SkM44 full = p.space * SkM44(local);
        for (int i = 0; i < 4; i++) {
            const SkV4 u = full.map(corners[i].fX, corners[i].fY, 0, 1), w = full.map(corners[(i + 1) % 4].fX, corners[(i + 1) % 4].fY, 0, 1);
            const float du = u.z - kNearest, dw = w.z - kNearest;
            if (du >= 0) clip[n++] = u;
            if ((du > 0 && dw < 0) || (du < 0 && dw > 0)) clip[n++] = u + (w - u) * (du / (du - dw));
        }
    } else {
        const SkMatrix m = SkMatrix::Translate(p.location.fX, p.location.fY).preRotate(p.rotation).preConcat(local);
        for (const SkPoint& c : corners) {
            const SkPoint at = m.mapPoint(c);
            clip[n++] = {at.fX, at.fY, seen.depth, 1};
        }
    }
    double q[8];
    for (int i = 0; i < n; i++) {
        if (!(clip[i].w > 0)) return true;
        out->corners[i] = {clip[i].x / clip[i].w, clip[i].y / clip[i].w};
        q[i] = out->nearness(clip[i].z);
        if (!out->corners[i].isFinite() || !std::isfinite(q[i])) return true;
    }
    int best = 0;
    double most = 0;
    for (int i = 1; i + 1 < n; i++) {
        const SkVector a = out->corners[i] - out->corners[0], b = out->corners[i + 1] - out->corners[0];
        const double area = std::fabs((double)a.fX * b.fY - (double)a.fY * b.fX);
        if (area > most) {
            most = area;
            best = i;
        }
    }
    if (!(most > 1)) return true;
    const SkPoint o = out->corners[0], a = out->corners[best], b = out->corners[best + 1];
    const double x1 = a.fX - o.fX, y1 = a.fY - o.fY, x2 = b.fX - o.fX, y2 = b.fY - o.fY, det = x1 * y2 - y1 * x2;
    const double e1 = q[best] - q[0], e2 = q[best + 1] - q[0];
    out->plane[0] = (e1 * y2 - y1 * e2) / det;
    out->plane[1] = (x1 * e2 - e1 * x2) / det;
    out->plane[2] = q[0] - out->plane[0] * o.fX - out->plane[1] * o.fY;
    out->count = n;
    return true;
}

void SplitSeen(std::vector<Layer>* seen, double timeMs) {
    std::vector<Layer>& list = *seen;
    static thread_local std::vector<size_t> groups, idle;
    static thread_local depth::Order order;
    std::vector<Layer> out;
    size_t copied = 0;
    const auto after = [&](size_t i) {
        size_t end = i + 1;
        while (end < list.size() && list[end].clippingMask) end++;
        return end;
    };
    for (size_t i = 0; i < list.size();) {
        const int run = list[i].sight.run;
        if (run == 0 || Idle(list[i], timeMs)) {
            i = after(i);
            continue;
        }
        groups.clear();
        idle.clear();
        size_t j = i;
        for (; j < list.size() && (list[j].sight.run == run || Idle(list[j], timeMs)); j = after(j)) (Idle(list[j], timeMs) ? idle : groups).push_back(j);
        const size_t start = i;
        i = j;
        if (groups.size() < 2) continue;
        depth::Begin();
        bool known = true;
        for (size_t g : groups) {
            depth::Flat flat;
            known = known && SeenFlat(list[g], timeMs, &flat);
            depth::AddPlane(flat);
        }
        if (!known || !depth::Solve(&order)) continue;
        if (out.empty()) out.reserve(list.size() + order.parts.size());
        for (; copied < start; copied++) out.push_back(std::move(list[copied]));
        for (size_t g : idle) {
            for (size_t k = g; k < after(g); k++) out.push_back(std::move(list[k]));
        }
        for (size_t p = 0; p < order.parts.size(); p++) {
            const depth::Part& part = order.parts[p];
            const size_t g = groups[part.shape];
            const bool last = std::none_of(order.parts.begin() + p + 1, order.parts.end(), [&](const depth::Part& o) { return o.shape == part.shape; });
            for (size_t k = g, end = after(g); k < end; k++) {
                if (last) out.push_back(std::move(list[k]));
                else out.push_back(list[k]);
                out.back().sight.cuts.assign(order.cuts.begin() + part.cut, order.cuts.begin() + part.cut + part.cuts);
            }
        }
        copied = j;
    }
    if (copied == 0) return;
    for (; copied < list.size(); copied++) out.push_back(std::move(list[copied]));
    list.swap(out);
}

float DepthPerPixel(const Layer& l, double timeMs) {
    const Sight& s = l.sight;
    if (!s.layers || !s.layer) return 0;
    const View v = s.camera ? LayerView(*s.layers, *s.camera, s.width, s.height, timeMs, s.depthOrder) : DefaultView(s.width, s.height);
    const float depth = Look(v, EffectivePlacement(*s.layers, *s.layer, timeMs)).depth;
    return (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(depth, kNearest)) / v.focal;
}

bool SeeLayers(const std::vector<Layer>& layers, int width, int height, bool top, int fps, double timeMs, bool depthOrder, std::vector<Layer>* seen,
               bool pieces) {
    const Layer* camera = top && gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    if (!camera) {
        bool flat = true;
        for (const Layer& l : layers) {
            const Placement p = EffectivePlacement(layers, l, timeMs);
            flat = flat && p.z == 0 && !Solid(l, p) && !(pieces && Spatial(l, p, timeMs));
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
        copy.sight.depth = d;
        copies.push_back(std::move(copy));
        depth.push_back(d);
        dropped.push_back(drop);
        if (depthOrder) spatial.push_back(Spatial(l, p, timeMs));
    }

    std::vector<size_t> starts;
    for (size_t i = 0; i < copies.size(); i++) {
        if (i == 0 || !copies[i].clippingMask) starts.push_back(i);
    }
    std::vector<size_t> order(starts.size());
    std::iota(order.begin(), order.end(), 0);
    const auto farther = [&](size_t a, size_t b) { return depth[starts[a]] > depth[starts[b]]; };
    const auto mark = [&](size_t k, int run) {
        const size_t end = k + 1 < starts.size() ? starts[k + 1] : copies.size();
        for (size_t i = starts[k]; i < end; i++) copies[i].sight.run = run;
    };
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
                const bool fa = std::isfinite(da), fb = std::isfinite(db);
                if (fa != fb) return fa;
                if (fa && da != db) return da > db;
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
    if (depthOrder) {
        int run = 0;
        bool open = false;
        for (size_t k : order) {
            const Layer& layer = layers[starts[k]];
            if (layer.kind == "camera" || layer.kind == "nullobj" || layer.kind == "light") continue;
            if (!spatial[starts[k]]) {
                open = false;
                continue;
            }
            if (!open) run++;
            open = true;
            mark(k, run);
        }
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
