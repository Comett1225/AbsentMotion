#include "scene.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <set>

#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/pathops/SkPathOps.h"
#include "include/utils/SkParsePath.h"
#include "xml.h"
#include "text.h"
#include "drawing.h"

namespace am {

float Anim::get(int i, float t, float fallback) const {
    auto at = [&](const std::vector<float>& v) { return i < (int)v.size() ? v[i] : fallback; };
    if (keys.empty()) return value.empty() ? fallback : at(value);
    if (!std::isfinite(t)) return at(keys.front().v);
    if (hasLead && t < keys.front().t) {
        if (t <= lead.t) return at(lead.v);
        const float u = (t - lead.t) / (keys.front().t - lead.t);
        return at(lead.v) + (at(keys.front().v) - at(lead.v)) * u;
    }
    if (t <= keys.front().t) return at(keys.front().v);
    if (t >= keys.back().t) return at(keys.back().v);
    auto next = std::upper_bound(keys.begin(), keys.end(), t,
                                 [](float time, const Keyframe& k) { return time < k.t; });
    const Keyframe& b = *next;
    const Keyframe& a = *(next - 1);
    const float u = Ease(b.easing, b.t > a.t ? (t - a.t) / (b.t - a.t) : 0.0f);
    return at(a.v) + (at(b.v) - at(a.v)) * u;
}

const Property* Layer::prop(const char* propName) const {
    for (const auto& p : props) {
        if (p.name == propName) return &p;
    }
    return nullptr;
}

const LayerEffect* Layer::effect(const char* id) const {
    for (const auto& e : effects) {
        if (!e.hidden && e.id == id) return &e;
    }
    return nullptr;
}

const Property* LayerEffect::prop(const char* propName) const {
    for (const auto& p : props) {
        if (p.name == propName) return &p;
    }
    return nullptr;
}

float LayerEffect::value(const char* propName, float t, float fallback) const {
    const Property* p = this->prop(propName);
    return p ? p->anim.get(0, t, fallback) : fallback;
}

float Layer::value(const char* propName, int i, float t, float fallback) const {
    const Property* p = this->prop(propName);
    return p ? p->anim.get(i, t, fallback) : fallback;
}

const Layer* Scene::layer(int64_t id) const {
    for (const auto& l : layers) {
        if (l.id == id) return &l;
    }
    return nullptr;
}

namespace {

std::vector<float> ParseValue(const char* s) {
    std::vector<float> out;
    if (!s) return out;
    if (!strcmp(s, "true") || !strcmp(s, "false")) return {*s == 't' ? 1.0f : 0.0f};
    if (*s == '#') {
        const uint32_t argb = (uint32_t)strtoul(s + 1, nullptr, 16);
        for (int shift = 24; shift >= 0; shift -= 8) out.push_back(((argb >> shift) & 0xff) / 255.0f);
        return out;
    }
    while (*s) {
        char* end = nullptr;
        const float f = strtof(s, &end);
        if (end == s) break;
        out.push_back(f);
        s = end;
        while (*s == ',' || *s == ' ') s++;
    }
    return out;
}

Anim ParseAnim(const XmlNode* node) {
    Anim a;
    if (!node) return a;
    a.value = ParseValue(node->attr("value"));
    for (const auto& c : node->children) {
        if (c->name != "kf") continue;
        const char* t = c->attr("t");
        const float time = t ? strtof(t, nullptr) : 0.0f;
        if (std::isfinite(time)) a.keys.push_back({time, ParseValue(c->attr("v")), ParseEasing(c->attr("e"))});
    }
    std::stable_sort(a.keys.begin(), a.keys.end(), [](const Keyframe& x, const Keyframe& y) { return x.t < y.t; });
    return a;
}

PathKnot ParseKnot(const XmlNode& n) {
    PathKnot k;
    k.p = ParseAnim(n.child("p"));
    if (const XmlNode* in = n.child("in")) { k.in = ParseAnim(in); k.hasIn = true; }
    if (const XmlNode* out = n.child("out")) { k.out = ParseAnim(out); k.hasOut = true; }
    return k;
}

SkPath KnotsPath(const PathContour& c, float t) {
    SkPathBuilder b;
    const int n = (int)c.knots.size();
    if (n == 0) return b.detach();
    auto at = [&](const PathKnot& k) { return SkPoint{k.p.get(0, t, 0), k.p.get(1, t, 0)}; };
    b.moveTo(at(c.knots[0]));
    for (int i = 0; i + 1 < n + (c.closed ? 1 : 0); i++) {
        const PathKnot& a = c.knots[i];
        const PathKnot& next = c.knots[(i + 1) % n];
        const SkPoint pa = at(a), pb = at(next);
        if (!a.hasOut && !next.hasIn) {
            b.lineTo(pb);
        } else {
            const SkPoint c1 = a.hasOut ? SkPoint{pa.fX + a.out.get(0, t, 0), pa.fY + a.out.get(1, t, 0)} : pa;
            const SkPoint c2 = next.hasIn ? SkPoint{pb.fX + next.in.get(0, t, 0), pb.fY + next.in.get(1, t, 0)} : pb;
            b.cubicTo(c1, c2, pb);
        }
    }
    if (c.closed) b.close();
    return b.detach();
}

bool Keyed(const PathKnot& k) { return !k.p.keys.empty() || !k.in.keys.empty() || !k.out.keys.empty(); }

SkPath Combine(const std::vector<PathContour>& contours, float t, bool rebuild) {
    SkPath result;
    SkPathBuilder open;
    bool first = true;
    for (const PathContour& c : contours) {
        SkPath part = rebuild && !c.knots.empty() ? KnotsPath(c, t) : c.path;
        if (c.evenOdd) part.setFillType(SkPathFillType::kEvenOdd);
        if (contours.size() > 1 && !part.isLastContourClosed()) {
            open.addPath(part);
        } else if (first) {
            result = part;
            first = false;
        } else if (auto combined = Op(result, part, c.exclude ? kXOR_SkPathOp : kUnion_SkPathOp)) {
            result = *combined;
        }
    }
    if (open.isEmpty()) return result;
    SkPathBuilder all(result);
    all.addPath(open.detach());
    return all.detach();
}

void ParseOutline(const XmlNode& path, Layer* layer) {
    const char* rule = path.attr("fillRule");
    const bool evenOdd = rule && !strcmp(rule, "evenodd");
    auto add = [&](const XmlNode& n) {
        PathContour c;
        c.evenOdd = evenOdd;
        const char* d = n.attr("d");
        const char* closed = n.attr("closed");
        const char* exclude = n.attr("exclude");
        c.exclude = exclude && !strcmp(exclude, "true");
        c.closed = closed && !strcmp(closed, "true");
        bool knotted = false;
        for (const auto& k : n.children) {
            if (k->name != "knot") continue;
            knotted = true;
            c.knots.push_back(ParseKnot(*k));
            layer->keyedPoints |= Keyed(c.knots.back());
        }
        if (knotted) {
            c.path = KnotsPath(c, 0);
        } else if (d) {
            auto contour = SkParsePath::FromSVGString(d);
            if (!contour) return;
            c.path = *contour;
        } else {
            return;
        }
        if (evenOdd) c.path.setFillType(SkPathFillType::kEvenOdd);
        layer->contours.push_back(std::move(c));
    };
    add(path);
    for (const auto& c : path.children) {
        if (c->name == "contour") add(*c);
    }
    layer->outline = Combine(layer->contours, 0, false);
}

void LeadIn(Anim* location, float frame) {
    const auto& k = location->keys;
    if (k.size() < 2 || !(k[0].t > 0 && k[0].t < frame) || !(k[1].t > k[0].t)) return;
    const float back = 0.75f * k[0].t / (k[1].t - k[0].t);
    location->lead = {0.0f, k[0].v, k[0].easing};
    for (size_t i = 0; i < k[0].v.size() && i < k[1].v.size(); ++i) location->lead.v[i] -= back * (k[1].v[i] - k[0].v[i]);
    location->hasLead = true;
}

int IntAttr(const XmlNode& n, const char* key, int fallback) {
    const char* v = n.attr(key);
    return v ? (int)strtol(v, nullptr, 10) : fallback;
}

int FpsAttr(const XmlNode& n) {
    const char* v = n.attr("fps");
    if (!v || !*v) return 30;
    char* end = nullptr;
    const long long fps = strtoll(v, &end, 10);
    return *end == 0 && fps > 0 && fps <= INT32_MAX ? (int)fps : 30;
}

std::string StrAttr(const XmlNode& n, const char* key) {
    const char* v = n.attr(key);
    return v ? v : "";
}

}

Anim ParseAnimNode(const XmlNode* node) { return ParseAnim(node); }

namespace {

bool ParseLayer(const XmlNode& n, int fps, Layer* out);

void ParseCamera(const XmlNode& n, Camera* c) {
    c->orthographic = StrAttr(n, "type") == "orthographic";
    c->fov = ParseAnim(n.child("fov"));
    for (const auto& k : n.children) {
        const bool on = StrAttr(*k, "enabled") != "false";
        if (k->name == "focus-blur") {
            c->focusBlur = on;
            c->focusStrength = ParseAnim(k->child("strength"));
            c->focusDepth = ParseAnim(k->child("depth-of-field"));
            c->focusDistance = ParseAnim(k->child("distance"));
        } else if (k->name == "fog") {
            c->fog = on;
            c->fogColor = ParseAnim(k->child("color"));
            c->fogNear = ParseAnim(k->child("near-z"));
            c->fogFar = ParseAnim(k->child("far-z"));
        }
    }
}

void ParseGroup(const XmlNode& scene, int fps, Layer* group) {
    group->innerWidth = std::clamp(IntAttr(scene, "width", 1080), 1, 8192);
    group->innerHeight = std::clamp(IntAttr(scene, "height", 1080), 1, 8192);
    const char* bg = scene.attr("bgcolor");
    group->innerBackground = bg && *bg == '#' ? (uint32_t)strtoul(bg + 1, nullptr, 16) : 0;
    Retiming& r = group->retiming;
    r.length = IntAttr(scene, "totalTime", 0);
    r.fps = FpsAttr(scene);
    r.method = scene.attr("retime") ? scene.attr("retime") : "off";
    r.in = IntAttr(scene, "retimeIn", 0);
    r.out = IntAttr(scene, "retimeOut", 0);
    r.hostFps = fps;
    group->fixedPrecompose = StrAttr(scene, "precompose") == "fixedResolution";
    const int innerFps = FpsAttr(scene);
    for (const auto& n : scene.children) {
        Layer l;
        if (ParseLayer(*n, innerFps, &l)) group->children.push_back(std::move(l));
    }
}

bool ParseLayer(const XmlNode& node, int fps, Layer* out) {
    const XmlNode* n = &node;
    if (n->name != "shape" && n->name != "text" && n->name != "embedScene" && n->name != "nullobj" && n->name != "drawing" && n->name != "camera" &&
        n->name != "model") return false;
    Layer& l = *out;
    l.kind = n->name;
    if (l.kind == "text") l.text = ParseText(*n);
    if (l.kind == "embedScene") {
        if (const XmlNode* inner = n->child("scene")) ParseGroup(*inner, fps, &l);
        const float fallbackSpeed = n->attr("speed") ? strtof(n->attr("speed"), nullptr) : 1;
        const float speed = ParseAnim(n->child("speedMap")).get(0, 0, fallbackSpeed);
        if (speed > 0 && std::isfinite(speed)) l.retiming.speed = speed;
        l.retiming.cut = std::max(0, IntAttr(*n, "inTime", 0));
    }
    const char* id = n->attr("id");
    l.id = id ? strtoll(id, nullptr, 10) : 0;
    const char* noise = n->attr("noiseId");
    l.noiseId = noise ? strtoll(noise, nullptr, 10) : 0;
    l.label = StrAttr(*n, "label");
    l.shape = StrAttr(*n, "s");
    l.fillType = StrAttr(*n, "fillType");
    l.mediaUri = StrAttr(*n, "fillImage");
    if (l.mediaUri.empty()) l.mediaUri = StrAttr(*n, "fillVideo");
    if (l.kind == "model") l.mediaUri = StrAttr(*n, "src");
    l.video = l.fillType == "media" && StrAttr(*n, "fillImage").empty() && !l.mediaUri.empty();
    l.mediaFillMode = StrAttr(*n, "mediaFillMode");
    l.startTime = IntAttr(*n, "startTime", 0);
    l.endTime = IntAttr(*n, "endTime", 0);
    l.hidden = StrAttr(*n, "hidden") == "true";
    if (const XmlNode* t = n->child("transform")) {
        l.location = ParseAnim(t->child("location"));
        if (l.endTime > l.startTime) LeadIn(&l.location, 1000.0f / fps / (l.endTime - l.startTime));
        l.pivot = ParseAnim(t->child("pivot"));
        l.scale = ParseAnim(t->child("scale"));
        l.rotation = ParseAnim(t->child("rotation"));
        l.tilt = ParseAnim(t->child("tilt"));
        l.skew = ParseAnim(t->child("skew"));
        l.opacity = ParseAnim(t->child("opacity"));
    }
    l.fillColor = ParseAnim(n->child("fillColor"));
    if (l.kind == "camera") ParseCamera(*n, &l.camera);
    if (const XmlNode* path = n->child("path")) ParseOutline(*path, &l);
    if (l.kind == "drawing") ParseStrokes(*n, &l);
    if (const XmlNode* stroke = n->child("path-stroke")) {
        l.stroke.present = true;
        l.stroke.direction = StrAttr(*stroke, "direction");
        const char* endSize = stroke->attr("end-size");
        l.stroke.endSize = endSize ? strtof(endSize, nullptr) : 1.0f;
        l.stroke.color = ParseAnim(stroke->child("color"));
        l.stroke.size = ParseAnim(stroke->child("size"));
    }
    for (const auto& c : n->children) {
        if (c->name == "effect") {
            LayerEffect effect;
            effect.id = StrAttr(*c, "id");
            effect.hidden = StrAttr(*c, "hidden") == "true";
            effect.locallyApplied = StrAttr(*c, "locallyApplied") == "true";
            effect.toChildren = StrAttr(*c, "applyToChildren") == "true";
            for (const auto& p : c->children) if (p->name == "property")
                effect.props.push_back({StrAttr(*p, "name"), StrAttr(*p, "type"), ParseAnim(p.get()), StrAttr(*p, "value")});
            l.effects.push_back(std::move(effect));
        }
        if (c->name != "property") continue;
        l.props.push_back({StrAttr(*c, "name"), StrAttr(*c, "type"), ParseAnim(c.get())});
    }
    ParseSteps(&l);
    ParseStyle(*n, &l);
    return true;
}

}

bool ParseScene(const std::string& xml, Scene* scene, std::string* error) {
    auto root = ParseXml(xml, error);
    if (!root) return false;
    if (root->name != "scene") {
        if (error) *error = "root element is <" + root->name + ">, expected <scene>";
        return false;
    }
    Scene next;
    next.width = IntAttr(*root, "width", 0);
    next.height = IntAttr(*root, "height", 0);
    next.fps = FpsAttr(*root);
    next.totalTime = IntAttr(*root, "totalTime", 0);
    const char* bg = root->attr("bgcolor");
    next.bgColor = bg && *bg == '#' ? (uint32_t)strtoul(bg + 1, nullptr, 16) : 0;
    if (next.width <= 0 || next.height <= 0) {
        if (error) *error = "scene has no size";
        return false;
    }
    for (const auto& n : root->children) {
        if (n->name == "media") {
            next.mediaOrientation[StrAttr(*n, "uri")] = IntAttr(*n, "orientation", 0);
            continue;
        }
        Layer l;
        if (ParseLayer(*n, next.fps, &l)) next.layers.push_back(std::move(l));
    }
    *scene = std::move(next);
    return true;
}

const Layer* ActiveCamera(const std::vector<Layer>& layers, double timeMs) {
    const Layer* active = nullptr;
    const Layer* lowest = nullptr;
    for (const Layer& layer : layers) {
        if (layer.kind != "camera" || layer.hidden) continue;
        if (!lowest) lowest = &layer;
        if (layer.activeAt(timeMs)) active = &layer;
    }
    return active ? active : lowest;
}

double NestedTime(const Layer& group, double timeMs) {
    return RetimedTime(group.retiming, group.startTime, group.endTime, group.steppedMs(timeMs));
}

namespace {

double ToLastFrameMiddle(double length, double fps) {
    if (!(fps > 0)) fps = 30;
    const double lastFrame = std::floor(length * fps / 1000 + 1e-9);
    return std::floor((2 * lastFrame + 1) * 500 / fps);
}

}

double RetimedTime(const Retiming& r, int startTime, int endTime, double at) {
    const double origin = (double)startTime - r.cut;
    const double local = at - origin;
    if (r.method == "off") return local * r.speed;
    const double sceneEnd = ToLastFrameMiddle(std::max(0, r.length), r.fps);
    if (r.method == "blank") return local < sceneEnd ? std::round(local) : -1;
    const double layerEnd = ToLastFrameMiddle(endTime - origin, r.hostFps);
    const double in = std::clamp((double)r.in, 0.0, sceneEnd);
    const double out = r.out > 0 ? std::clamp((double)r.out, in, sceneEnd) : sceneEnd;
    const double outroStart = layerEnd - (sceneEnd - out);
    double t;
    if (local >= outroStart) {
        t = out + (local - outroStart);
    } else if (local < in) {
        t = local;
    } else {
        const double into = local - in, room = outroStart - in, middle = out - in;
        if (r.method == "stretch") {
            t = in + into * middle / room;
        } else if (r.method == "loop") {
            t = in + (middle > 0 ? std::fmod(into, middle) : 0);
        } else if (r.method == "loop-stretch" && middle > 0) {
            const double each = room / std::max(1.0, std::round(room / middle));
            t = in + std::fmod(into, each) * middle / each;
        } else {
            t = in + std::min(into, middle);
        }
    }
    return std::round(t);
}

SkPath OutlineAt(const Layer& layer, float t) {
    return layer.keyedPoints ? Combine(layer.contours, t, true) : layer.outline;
}

SkMatrix LayerMatrix(const Layer& l, float t) {
    SkMatrix m = SkMatrix::Translate(l.location.get(0, t, 0), l.location.get(1, t, 0));
    m.preRotate(l.rotation.get(0, t, 0));
    m.preSkew(tanf(l.skew.get(0, t, 0)), tanf(l.skew.get(1, t, 0)));
    m.preScale(l.scale.get(0, t, 1), l.scale.get(1, t, 1));
    m.preTranslate(-l.pivot.get(0, t, 0), -l.pivot.get(1, t, 0));
    return m;
}

SkRect MediaRect(const Layer& layer, const SkRect& bounds, int width, int height) {
    if (layer.mediaFillMode == "stretch") return bounds;
    const float sx = bounds.width() / width, sy = bounds.height() / height;
    const float scale = layer.mediaFillMode == "fit" ? std::min(sx, sy) : std::max(sx, sy);
    const float w = width * scale, h = height * scale;
    return SkRect::MakeXYWH(bounds.centerX() - w / 2, bounds.centerY() - h / 2, w, h);
}

void DrawMedia(SkCanvas* canvas, const Layer& layer, const SkPath& path, float opacity,
               const MediaPictures& pictures) {
    auto image = pictures(layer.mediaUri, layer.id);
    if (!image) return;
    const SkRect bounds = path.getBounds();
    if (bounds.isEmpty()) return;
    const SkRect dst = MediaRect(layer, bounds, image->width(), image->height());
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setAlphaf(std::clamp(opacity, 0.0f, 1.0f));
    canvas->save();
    canvas->clipPath(path, true);
    canvas->drawImageRect(image, dst, SkSamplingOptions(SkFilterMode::kLinear), &paint);
    canvas->restore();
}

void DrawScene(SkCanvas* canvas, const Scene& scene, double timeMs, const MediaPictures& pictures,
               const LayerEffects* effects) {
    canvas->save();
    canvas->clipRect(SkRect::MakeWH(scene.width, scene.height), true);
    canvas->drawColor(scene.bgColor);
    SetLayerEffects(effects, &scene);
    DrawLayerList(canvas, scene, scene.layers, timeMs, pictures);
    SetLayerEffects(nullptr, nullptr);
    canvas->restore();
}

}
