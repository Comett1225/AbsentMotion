#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <map>
#include <memory>

#include "easing.h"
#include "xml.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPath.h"
#include "include/core/SkImage.h"

class SkCanvas;

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
};

struct Property {
    std::string name, type;
    Anim anim;
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
    bool hidden = false, locallyApplied = false;
    std::vector<Property> props;
};

struct FreehandStroke {
    int tool = 0;
    float width = 5, color[4] = {1, 1, 1, 1};
    SkPath geometry;
    bool filled = false;
};

struct PathKnot {
    Anim p, in, out;
    bool hasIn = false, hasOut = false;
};
struct PathContour {
    SkPath path;
    bool exclude = false, closed = false;
    std::vector<PathKnot> knots;
};

struct Retiming {
    std::string method = "off";
    int length = 0, in = 0, out = 0, hostFps = 30;
    double fps = 30;
    float speed = 1.0f;
};

struct Layer {
    int64_t id = 0;
    std::string kind;
    std::string label, shape, fillType;
    std::string mediaUri, mediaFillMode;
    bool mediaMissing = false;
    int startTime = 0, endTime = 0;
    bool hidden = false;
    Anim location, pivot, scale, rotation, skew, opacity, fillColor;
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
    std::vector<Layer> children;
    int innerWidth = 0, innerHeight = 0;
    uint32_t innerBackground = 0;
    Retiming retiming;
    bool fixedPrecompose = false;
    std::vector<PathContour> contours;
    bool keyedPoints = false;
    std::vector<FreehandStroke> strokes;
    Camera camera;

    float value(const char* name, int i, float t, float fallback) const;

    const Property* prop(const char* name) const;
    bool activeAt(double timeMs) const { return !hidden && timeMs >= startTime && timeMs <= endTime; }
    float localTime(double timeMs) const {
        return endTime > startTime ? (float)((timeMs - startTime) / (endTime - startTime)) : 0.0f;
    }
};

struct Placement {
    SkPoint location, pivot, scale;
    float rotation, z;
};

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
double NestedTime(const Layer& group, double timeMs);
double RetimedTime(const Retiming& r, int startTime, int endTime, double at);
void SetCameraView(bool on);
bool SeeLayers(const std::vector<Layer>& layers, int width, int height, bool top, int fps, double timeMs, std::vector<Layer>* seen);
SkMatrix WorldMatrix(const Scene& scene, const Layer& layer, double timeMs);
int64_t TopLayerAt(const Scene& scene, double timeMs, float x, float y);
bool HitLayer(const Scene& scene, const Layer& layer, double timeMs, float x, float y);
void DrawLayerList(SkCanvas* canvas, const Scene& scene, const std::vector<Layer>& layers, double timeMs, const MediaPictures& pictures);

SkPath ShapePath(const Layer& layer, float t);
SkPath StrokePath(const Layer& layer, const SkPath& path, float t);
SkPath OutlineAt(const Layer& layer, float t);
SkMatrix LayerMatrix(const Layer& layer, float t);

void DrawMedia(SkCanvas* canvas, const Layer& layer, const SkPath& path, float opacity,
               const MediaPictures& pictures);
struct LayerEffects {
    std::function<bool(const Layer&)> active;
    std::function<bool(const Layer&)> lifted;
    std::function<bool(SkCanvas*, const Scene&, const Layer&, double, const SkRect&, const std::function<void(SkCanvas*)>&)> draw;
};
void DrawScene(SkCanvas* canvas, const Scene& scene, double timeMs, const MediaPictures& pictures,
               const LayerEffects* effects = nullptr);
void SetLayerEffects(const LayerEffects* effects, const Scene* scene);

}
