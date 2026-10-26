#pragma once

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <map>
#include <memory>

#include "easing.h"
#include "frames.h"
#include "xml.h"
#include "include/core/SkM44.h"
#include "include/core/SkBlendMode.h"
#include "include/core/SkColor.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPath.h"
#include "include/core/SkImage.h"

class SkCanvas;
class SkImageFilter;

namespace am {

using MediaPictures = std::function<sk_sp<SkImage>(const std::string&, int64_t)>;

struct Keyframe {
    float t;
    std::vector<float> v;
    Easing easing;
};

struct Anim {
    std::vector<float> value;
    std::vector<Keyframe> keys;
    Keyframe lead;
    bool hasLead = false;

    bool empty() const { return value.empty() && keys.empty(); }
    float get(int i, float t, float fallback) const;
    bool steady(float from, float to) const;
};

struct Property {
    std::string name, type;
    Anim anim;
    std::string text;
};

struct Stroke {
    bool present = false;
    std::string direction;
    bool enabled = true;
    std::string cap = "round", join = "bevel", start = "none", end = "none";
    float endSize = 1.0f;
    Anim color, size;
};

struct Edge {
    bool enabled = true;
    std::string direction;
    Anim color, size, opacity, hardness, offset;
};

struct Gradient {
    bool present = false;
    std::string type = "linear";
    float startColor[4] = {1, 0, 0, 0}, endColor[4] = {1, 1, 1, 1};
    float start[2] = {0, 0}, end[2] = {1, 1};
};

struct TextLayout;

struct Camera {
    bool orthographic = false;
    bool focusBlur = false, fog = false;
    Anim fov;
    Anim focusStrength, focusDepth, focusDistance;
    Anim fogColor, fogNear, fogFar;
};

struct LayerEffect {
    std::string id;
    bool hidden = false, locallyApplied = false, toChildren = false;
    std::vector<Property> props;

    float value(const char* name, float t, float fallback) const;
    const Property* prop(const char* name) const;
};

struct FreehandStroke {
    int tool = 0;
    float width = 5, color[4] = {1, 1, 1, 1};
    SkPath geometry;
    bool filled = false;
    std::vector<SkPoint> points;
    std::vector<float> radius;
};

struct PathKnot {
    Anim p, in, out;
    bool hasIn = false, hasOut = false;
};
struct PathContour {
    SkPath path;
    bool exclude = false, closed = false, evenOdd = false;
    std::vector<PathKnot> knots;
};

struct Retiming {
    std::string method = "off";
    int length = 0, in = 0, out = 0, hostFps = 30, cut = 0, clock = -1;
    double fps = 30;
    float speed = 1.0f;
};

struct Layer;

struct Sight {
    const std::vector<Layer>* layers = nullptr;
    const Layer* layer = nullptr;
    const Layer* camera = nullptr;
    int width = 0, height = 0;
};

struct Layer {
    int64_t id = 0;
    int64_t noiseId = 0;
    std::string kind;
    std::string label, shape, fillType;
    std::string mediaUri, mediaFillMode;
    int startTime = 0, endTime = 0;
    bool hidden = false, video = false;
    Anim location, pivot, scale, rotation, skew, opacity, fillColor, tilt;
    std::vector<Property> props;
    std::vector<LayerEffect> effects;
    Stroke stroke;
    std::shared_ptr<TextLayout> text;
    SkPath outline;
    std::vector<Edge> borders;
    Edge shadow;
    Gradient gradient;
    std::string blending;
    bool clippingMask = false;
    int64_t parent = 0;
    int64_t pathLayer = 0;
    std::vector<Layer> children;
    int innerWidth = 0, innerHeight = 0;
    uint32_t innerBackground = 0;
    Retiming retiming;
    bool fixedPrecompose = false;
    std::vector<PathContour> contours;
    bool keyedPoints = false;
    std::vector<FreehandStroke> strokes;
    Camera camera;
    Anim steps;
    bool placed = false;
    bool projected = false;
    Sight sight;

    float value(const char* name, int i, float t, float fallback) const;

    const Property* prop(const char* name) const;
    const LayerEffect* effect(const char* id) const;
    bool activeAt(double timeMs) const { return !hidden && timeMs >= startTime && timeMs < endTime + 1.0; }
    double steppedMs(double timeMs) const {
        if (steps.empty() || endTime <= startTime) return timeMs;
        const float fps = steps.get(0, (float)((timeMs - startTime) / (endTime - startTime)), 0);
        if (!(fps > 0)) return timeMs;
        return HeldMs(timeMs, startTime, fps);
    }
    float localTime(double timeMs) const {
        return endTime > startTime ? (float)((steppedMs(timeMs) - startTime) / (endTime - startTime)) : 0.0f;
    }
};

struct Placement {
    SkPoint location, pivot, scale;
    float rotation, z;
    SkM44 tilt, space, turn;
};

struct Echo {
    int count = 0;
    float seconds = 0, alpha = 1;
    bool atop = false;
};

struct ShutterBlur {
    float angle = 0;
    int samples = 0;
    bool fast = false;
    bool adaptive = false;
    bool position = true, scale = true, rotation = true;
};

bool MotionEffect(const std::string& id);
bool TextEffect(const std::string& id);
bool DrawingEffect(const std::string& id);
bool ShutterBlurOf(const Layer& layer, double timeMs, ShutterBlur* blur);
void SetFastShutterBlur(bool fast);
sk_sp<SkImageFilter> SmearFilter(SkVector moved);
void ApplyMotion(const Layer& layer, double timeMs, Placement* placement);
float MotionOpacity(const Layer& layer, double timeMs);
bool EchoOf(const Layer& layer, double timeMs, Echo* echo);
bool FollowsPath(const Layer& layer);
void FollowPath(const Layer& layer, double timeMs, const SkPath& path, Placement* placement);
void ParseSteps(Layer* layer);

struct RepeatCopy {
    SkMatrix outer = SkMatrix::I(), local = SkMatrix::I();
    double shiftMs = 0;
    float alpha = 1, tinted = 0;
    SkColor4f tint = {1, 1, 1, 1};
    SkBlendMode tintMode = SkBlendMode::kSrcIn, mode = SkBlendMode::kSrcOver;
};

struct RepeatSpace {
    SkPoint anchor = {0, 0}, pivot = {0, 0};
    SkMatrix layer = SkMatrix::I();
    SkPath below;
    float width = 0, height = 0, radius = 0;
};

bool RepeatEffect(const std::string& id);
bool HasRepeat(const Layer& layer);
bool RepeatsAlongPath(const Layer& layer);
void RepeatCopies(const Layer& layer, double timeMs, const RepeatSpace& space, std::vector<RepeatCopy>* copies);

struct Scene {
    int width = 0, height = 0, fps = 30, totalTime = 0;
    uint32_t bgColor = 0;
    std::vector<Layer> layers;
    std::map<std::string, int> mediaOrientation;

    const Layer* layer(int64_t id) const;
};

bool ParseScene(const std::string& xml, Scene* scene, std::string* error);
Anim ParseAnimNode(const XmlNode* node);
void ParseStyle(const XmlNode& node, Layer* layer);
Placement EffectivePlacement(const std::vector<Layer>& siblings, const Layer& layer, double timeMs);
const Layer* ActiveCamera(const std::vector<Layer>& layers, double timeMs);
double NestedTime(const Layer& group, double timeMs);
double RetimedTime(const Retiming& r, int startTime, int endTime, double at);
void SetCameraView(bool on);
SkMatrix PlaneMatrix(const std::vector<Layer>& layers, const Layer& layer, int width, int height, double timeMs);
bool CameraViewOn();
bool CameraFrame(const std::vector<Layer>& layers, const Layer& camera, int width, int height, double timeMs, SkPoint corners[4]);
bool SeeLayers(const std::vector<Layer>& layers, int width, int height, bool top, int fps, double timeMs, std::vector<Layer>* seen);
bool SeenPlacement(const Layer& layer, double timeMs, Placement* placement);
SkMatrix WorldMatrix(const Scene& scene, const Layer& layer, double timeMs);
int64_t TopLayerAt(const Scene& scene, double timeMs, float x, float y, const MediaPictures& masks);
SkRect SelectionBounds(const Layer& layer, double timeMs, const SkMatrix& matrix);
void DrawLayerList(SkCanvas* canvas, const Scene& scene, const std::vector<Layer>& layers, double timeMs, const MediaPictures& pictures);

SkPath ShapePath(const Layer& layer, float t);
SkPath StrokePath(const Layer& layer, const SkPath& path, float t);
SkPath OutlineAt(const Layer& layer, float t);
SkMatrix LayerMatrix(const Layer& layer, float t);

SkRect MediaRect(const Layer& layer, const SkRect& bounds, int width, int height);
void DrawMedia(SkCanvas* canvas, const Layer& layer, const SkPath& path, float opacity,
               const MediaPictures& pictures);

struct Model {
    std::vector<float> positions, normals, uvs;
    std::vector<uint32_t> colors;
    std::vector<int> triangles, slots;
    bool twoSided = false;
};
void SetModel(const std::string& uri, std::shared_ptr<const Model> model);
void DrawModel(SkCanvas* canvas, const Layer& layer, double timeMs, float alpha, const MediaPictures& pictures);
struct LayerEffects {
    std::function<bool(const Layer&)> active;
    std::function<bool(const Layer&)> lifted;
    std::function<bool(const Layer&)> behind;
    std::function<bool(SkCanvas*, const Scene&, const Layer&, double, const SkRect&, const SkMatrix&, const std::function<void(SkCanvas*)>&,
                       const std::function<void(SkCanvas*)>&)> draw;
    std::function<bool(const Layer&, float, float)> steady;
};
void DrawScene(SkCanvas* canvas, const Scene& scene, double timeMs, const MediaPictures& pictures,
               const LayerEffects* effects = nullptr);
void SetLayerEffects(const LayerEffects* effects, const Scene* scene, bool world = false);
void SetLayerBreak(const std::function<void()>* each);
bool WorldView();

}
