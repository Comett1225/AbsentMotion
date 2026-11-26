#!/usr/bin/env python3
import json
import sys
from pathlib import Path

PACK = Path(__file__).resolve().parents[1] / "shared/assets/effects"
SINCE = "3.0.0"


def angle(id, label, default):
    return {"id": id, "label": label, "type": "angle", "default": default, "min": -3600, "max": 3600, "step": 0.1}


def number(id, label, default, low, high, step=0.01):
    return {"id": id, "label": label, "type": "float", "default": default, "min": low, "max": high, "step": step}


def colour(id, label, default):
    return {"id": id, "label": label, "type": "color", "default": default}


def turns(x, y, z=0):
    return [angle("rotX", "Rotation X", x), angle("rotY", "Rotation Y", y), angle("rotZ", "Rotation Z", z)]


PLACE = [{"id": "position", "label": "Position", "type": "point", "default": [0, 0], "min": -5000, "max": 5000, "step": 1, "pixels": True},
         number("scale", "Scale", 1, 0.05, 10)]
CAMERA = [number("perspective", "Perspective", 0.5, 0, 1)]
SOFT = {"lighting": [1, 2]}
LIGHT = [number("shading", "Shading", 0.6, 0, 1), angle("light", "Light Angle", 135), number("shine", "Shine", 0.3, 0, 1),
         {"id": "lighting", "label": "Lighting", "type": "choice", "default": 0, "options": ["Classic", "Soft", "Direct"]},
         dict(number("ambient", "Ambient", 0.03, 0, 2), when=SOFT), dict(number("lightHeight", "Light Height", 3, 0, 20), when=SOFT),
         dict(colour("lightColor", "Light Color", "#ffffffff"), when=SOFT), dict(number("glossiness", "Glossiness", 15, 1, 200, 0.1), when=SOFT)]
TILED = {"texMode": [1, 2]}
TEXTURE = [{"id": "texMode", "label": "Texture", "type": "choice", "default": 0, "options": ["Fit", "Tile", "Stretch"]},
           dict(number("texRepeatX", "Repeat X", 1, 0.01, 100), when=TILED), dict(number("texRepeatY", "Repeat Y", 1, 0.01, 100), when=TILED),
           dict({"id": "texMirror", "label": "Mirror", "type": "switch", "default": False}, when=TILED),
           dict(angle("texAngle", "Texture Angle", 0), when=TILED),
           dict({"id": "texOffset", "label": "Texture Offset", "type": "point", "default": [0, 0], "min": -100, "max": 100, "step": 0.01}, when=TILED),
           dict(number("texCrop", "Texture Crop", 1, 0.05, 10), when=TILED),
           dict({"id": "texAnchor", "label": "Texture Anchor", "type": "choice", "default": 0, "options": ["Center", "Corner"]}, when=TILED)]
LIGHT = [dict(p, section="light") for p in LIGHT]
TEXTURE = [dict(p, section="texture") for p in TEXTURE]
SMOOTH = number("smoothing", "Smoothing", 0.15, 0, 1)
INSIDE = colour("interior", "Interior Color", "#ff3d4152")

COMMON = """
const float PI = 3.14159265;

float3x3 turn() {
    float3 a = radians(float3(rotX, rotY, rotZ));
    float cx = cos(a.x), sx = sin(a.x), cy = cos(a.y), sy = sin(a.y), cz = cos(a.z), sz = sin(a.z);
    return float3x3(cz, -sz, 0.0, sz, cz, 0.0, 0.0, 0.0, 1.0) * float3x3(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy) *
        float3x3(1.0, 0.0, 0.0, 0.0, cx, -sx, 0.0, sx, cx);
}

float4 pic(float2 uv) {
    return float4(image.eval(clamp(uv * size, float2(0.5), max(size - 0.5, float2(0.5)))));
}

float2 cover(float2 uv, float2 face) {
    float r = (face.x / max(face.y, 0.0001)) / (size.x / max(size.y, 0.0001));
    return (uv - 0.5) * (r > 1.0 ? float2(1.0, 1.0 / r) : float2(r, 1.0)) + 0.5;
}

float4 shade(float4 c, float3 n, float3 v) {
    float a = radians(light);
    if (lighting > 0.5) {
        float d = max(dot(n, normalize(float3(cos(a), -sin(a), -lightHeight))), 0.0);
        float3 lit = c.rgb * ((lighting > 1.5 ? 0.0 : 0.38) + (ambient + shading * d) * float3(lightColor.rgb));
        float g = shine * pow(d, glossiness);
        return float4(min(lit + g * (c.a - lit), float3(c.a)), c.a);
    }
    float3 l = normalize(float3(cos(a), -sin(a), -1.2));
    float k = 1.0 - shading + shading * (0.3 + 0.85 * max(dot(n, l), 0.0));
    float s = shine * pow(max(dot(n, normalize(l + v)), 0.0), 40.0);
    return float4(min(c.rgb * k + s * c.a, float3(c.a)), c.a);
}
"""

SHAPES = """
float4 tiled(float2 uv, float2 face) {
    float2 q = uv - 0.5;
    if (texMode < 1.5) q *= face / max(min(face.x, face.y), 0.0001);
    float t = radians(texAngle);
    q = float2(cos(t) * q.x + sin(t) * q.y, cos(t) * q.y - sin(t) * q.x) * float2(texRepeatX, texRepeatY) + 0.5 - texOffset - texAnchor * (0.5 * float2(texRepeatX, texRepeatY) + 0.5);
    float2 f = fract(q);
    if (texMirror > 0.5) f = mix(f, 1.0 - f, mod(floor(q), 2.0));
    f = (f - 0.5) * texCrop + 0.5;
    if (f.x < 0.0 || f.y < 0.0 || f.x > 1.0 || f.y > 1.0) return float4(0.0);
    return pic(f);
}

float4 tex(float2 uv, float2 face) { return texMode < 0.5 ? pic(cover(uv, face)) : tiled(uv, face); }

float4 wrap(float2 uv) { return texMode < 0.5 ? pic(uv) : tiled(uv, float2(1.0)); }

float sdBox(float3 p, float3 h) {
    float3 q = abs(p) - h;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}

float sdRoundBox(float3 p, float3 h, float r) {
    return sdBox(p, h - r) - r;
}

float extrude(float d, float z, float h, float r) {
    float2 w = float2(d + r, abs(z) - h + r);
    return min(max(w.x, w.y), 0.0) + length(max(w, 0.0)) - r;
}

float4 boxFace(float3 p, float3 n, float3 h) {
    float3 a = abs(n);
    float2 uv;
    float2 face;
    if (a.z >= a.x && a.z >= a.y) {
        uv = float2(n.z < 0.0 ? p.x : -p.x, p.y) / h.xy;
        face = h.xy;
    } else if (a.x >= a.y) {
        uv = float2(n.x > 0.0 ? p.z : -p.z, p.y) / h.zy;
        face = h.zy;
    } else {
        uv = float2(p.x, n.y < 0.0 ? -p.z : p.z) / h.xz;
        face = h.xz;
    }
    return tex(uv * 0.5 + 0.5, face);
}

float4 front(float3 p, float3 n, float r) {
    return tex(float2(n.z > 0.0 ? -p.x : p.x, p.y) / r * 0.5 + 0.5, float2(1.0));
}

float4 facet(float3 p, float3 n, float r) {
    float3 f = normalize(sign(n));
    float3 w = float3(-f.z, 0.0, f.x);
    float3 u = dot(w, w) > 0.0 ? normalize(w) : float3(1.0, 0.0, 0.0);
    return tex(float2(dot(p, u), dot(p, cross(u, f))) / r * 0.5 + 0.5, float2(1.0));
}
"""

MARCH = """
float3 normal(float3 p, float e) {
    float2 k = float2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * e) + k.yyx * map(p + k.yyx * e) + k.yxy * map(p + k.yxy * e) + k.xxx * map(p + k.xxx * e));
}

float4 paint(float3 p, float3 n, float3x3 m, float3 rw) {
    return shade(surface(p, n), normalize(m * n), -rw);
}

half4 main(float2 coord) {
    float unit = max(min(size.x, size.y) * 0.5 * scale, 0.5);
    float rb = bound();
    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);
    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));
    float3 ow = float3(0.0, 0.0, -dist);
    float reach = rb + 2.0 * dist / (unit * max(dist - rb, 0.1));
    float b = dot(ow, rw);
    float disc = b * b - dot(ow, ow) + reach * reach;
    if (disc <= 0.0) return half4(0.0);
    float3x3 m = orient * turn();
    float3 ro = ow * m;
    float3 rd = rw * m;
    float far = -b + sqrt(disc);
    float t = max(-b - sqrt(disc), 0.0);
    float px = unit * dist / rw.z;
    float best = 1e9;
    float tb = t;
    bool hit = false;
    for (int i = 0; i < STEPS; i++) {
        float h = map(ro + rd * t);
        float g = h * px / max(t, 0.0001);
        if (g < best) { best = g; tb = t; }
        if (g < 0.2) { hit = true; break; }
        t += h * 0.9;
        if (t > far) break;
    }
    if (!hit && best >= 1.0) return half4(0.0);
    if (!hit) t = tb;
    float e = max(0.5 * t / px, 0.0005);
    float3 p = ro + rd * t;
    float3 n = normal(p, e);
    if (!hit) p -= n * map(p);
    float4 c = paint(p, n, m, rw);
    if (hit && c.a < 0.996 && lighting < 0.5) {
        float s = far;
        bool back = false;
        for (int i = 0; i < 64; i++) {
            float h = map(ro + rd * s);
            if (h * px / max(s, 0.0001) < 0.2) { back = true; break; }
            s -= h * 0.9;
            if (s < t) break;
        }
        if (back) {
            float3 q = ro + rd * s;
            c += paint(q, -normal(q, e), m, rw) * (1.0 - c.a);
        }
    }
    return half4(hit ? c : c * (1.0 - best));
}
"""


def uniforms(params, oriented=True):
    lines = ["uniform shader image;", "uniform float2 size;", "uniform float time;"] + (["uniform float3x3 orient;"] if oriented else [])
    for p in params:
        kind = p["type"]
        lines.append(f"uniform float2 {p['id']};" if kind == "point" else
                     f"layout(color) uniform half4 {p['id']};" if kind == "color" else f"uniform float {p['id']};")
    return "\n".join(lines) + "\n"


def solid(key, name, description, dims, code, x=-20, y=-35, steps=96, after="", extra=()):
    params = turns(x, y) + dims + PLACE + CAMERA + LIGHT + TEXTURE + list(extra)
    march = MARCH.replace("STEPS", str(steps))
    if after:
        march = march.replace("half4 main(float2 coord)", "half4 shape(float2 coord)") + after
    sksl = uniforms(params) + COMMON + SHAPES + code + march
    return key, name, description, params, sksl


INTERIOR = "float4 inside() { return float4(interior.rgb * interior.a, interior.a); }\n"
DIMS = "float3 dims() { float side = min(size.x, size.y); return float3(width * size.x / side, height * size.y / side, depth); }\n"

EFFECTS = [
    solid("cube", "Cube", "Wraps the layer onto every face of a turning cube with rounded edges and lighting.",
          [number("edge", "Size", 0.7, 0.05, 5), SMOOTH], """
float bound() { return edge * 1.7321; }
float map(float3 p) { return sdRoundBox(p, float3(edge), smoothing * edge * 0.5); }
float4 surface(float3 p, float3 n) { return boxFace(p, n, float3(edge)); }
"""),
    solid("box", "Box", "Wraps the layer onto a box whose width, height and depth you choose.",
          [number("width", "Width", 0.75, 0.02, 5), number("height", "Height", 0.75, 0.02, 5), number("depth", "Depth", 0.4, 0.01, 5), SMOOTH], DIMS + """
float bound() { return length(dims()); }
float map(float3 p) { float3 h = dims(); return sdRoundBox(p, h, smoothing * min(h.x, min(h.y, h.z)) * 0.5); }
float4 surface(float3 p, float3 n) { return boxFace(p, n, dims()); }
"""),
    solid("hollowbox", "Hollow Box", "A box with open top and bottom: the layer covers the outside and the inside takes the interior color.",
          [number("width", "Width", 0.7, 0.02, 5), number("height", "Height", 0.7, 0.02, 5), number("depth", "Depth", 0.7, 0.02, 5),
           number("thickness", "Thickness", 0.06, 0.005, 1), SMOOTH, INSIDE], INTERIOR + DIMS + """
float bound() { return length(dims()); }
float hole(float3 p) { float3 h = dims(); float w = min(thickness, min(h.x, h.z) * 0.9); return sdBox(p, float3(h.x - w, h.y + 1.0, h.z - w)); }
float map(float3 p) { float3 h = dims(); return max(sdRoundBox(p, h, smoothing * min(thickness, min(h.x, min(h.y, h.z))) * 0.5), -hole(p)); }
float4 surface(float3 p, float3 n) {
    float3 h = dims();
    if (abs(n.y) > 0.7 || abs(hole(p)) < abs(sdBox(p, h))) return inside();
    return boxFace(p, n, h);
}
""", x=-28),
    solid("cylinder", "Cylinder", "Wraps the layer around a cylinder and onto its round ends.",
          [number("radius", "Radius", 0.6, 0.02, 5), number("height", "Height", 0.85, 0.02, 5), SMOOTH], """
float bound() { return length(float2(radius, height)); }
float map(float3 p) {
    float r = smoothing * min(radius, height) * 0.5;
    float2 d = float2(length(p.xz) - radius + r, abs(p.y) - height + r);
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
}
float4 surface(float3 p, float3 n) {
    if (abs(n.y) > 0.7) return tex(float2(p.x, n.y < 0.0 ? -p.z : p.z) / radius * 0.5 + 0.5, float2(1.0));
    return wrap(float2(atan(p.x, -p.z) / (2.0 * PI) + 0.5, p.y / height * 0.5 + 0.5));
}
"""),
    solid("ellipsoid", "Ellipsoid", "Wraps the layer around a sphere that you can squeeze and flatten.",
          [number("radius", "Radius", 0.8, 0.02, 5), dict(number("squeezeRatio", "Squeeze", 1, 0.1, 1.9), percent=True),
           dict(number("flattenRatio", "Flatten", 1, 0.05, 1), percent=True)], """
float3 radii() { return radius * float3(1.0, squeezeRatio, flattenRatio); }
float bound() { return radius * max(1.0, squeezeRatio); }
float map(float3 p) { float3 r = radii(); float k0 = length(p / r); float k1 = length(p / (r * r)); return k0 * (k0 - 1.0) / max(k1, 0.0001); }
float4 surface(float3 p, float3 n) {
    float3 q = normalize(p / radii());
    return wrap(float2(atan(q.x, -q.z) / (2.0 * PI) + 0.5, acos(clamp(-q.y, -1.0, 1.0)) / PI));
}
""", x=-15, y=0, steps=64),
    solid("torus", "Torus", "Wraps the layer around a ring.",
          [number("radius", "Radius", 0.62, 0.02, 5), number("thickness", "Thickness", 0.28, 0.01, 3)], """
float bound() { return radius + thickness; }
float map(float3 p) { return length(float2(length(p.xy) - radius, p.z)) - thickness; }
float4 surface(float3 p, float3 n) {
    return wrap(float2(atan(p.x, -p.y) / (2.0 * PI) + 0.5, atan(p.z, length(p.xy) - radius) / (2.0 * PI) + 0.5));
}
""", x=-35, y=-20),
    solid("pyramid", "Pyramid", "Puts the layer on the faces of a square pyramid.",
          [number("base", "Base", 0.75, 0.02, 5), number("height", "Height", 1.3, 0.02, 5)], """
float sdPyramid(float3 p, float h) {
    float m2 = h * h + 0.25;
    p.xz = abs(p.xz);
    p.xz = (p.z > p.x) ? p.zx : p.xz;
    p.xz -= 0.5;
    float3 q = float3(p.z, h * p.y - 0.5 * p.x, h * p.x + 0.5 * p.y);
    float s = max(-q.x, 0.0);
    float t = clamp((q.y - 0.5 * p.z) / (m2 + 0.25), 0.0, 1.0);
    float a = m2 * (q.x + s) * (q.x + s) + q.y * q.y;
    float b = m2 * (q.x + 0.5 * t) * (q.x + 0.5 * t) + (q.y - m2 * t) * (q.y - m2 * t);
    float d2 = min(q.y, -q.x * m2 - q.y * 0.5) > 0.0 ? 0.0 : min(a, b);
    return sqrt((d2 + q.z * q.z) / m2) * sign(max(q.z, -p.y));
}
float bound() { return length(float3(base, height * 0.5, base)); }
float map(float3 p) { float s = 2.0 * base; return sdPyramid(float3(p.x, height * 0.5 - p.y, p.z) / s, height / s) * s; }
float4 surface(float3 p, float3 n) { return boxFace(p, n, float3(base, height * 0.5, base)); }
""", x=-15),
    solid("octahedron", "Octahedron", "Puts the layer on the eight faces of an octahedron.",
          [number("radius", "Radius", 0.95, 0.02, 5)], """
float bound() { return radius; }
float map(float3 p) {
    p = abs(p);
    float m = p.x + p.y + p.z - radius;
    float3 q;
    if (3.0 * p.x < m) q = p.xyz;
    else if (3.0 * p.y < m) q = p.yzx;
    else if (3.0 * p.z < m) q = p.zxy;
    else return m * 0.57735027;
    float k = clamp(0.5 * (q.z - q.y + radius), 0.0, radius);
    return length(float3(q.x, q.y - radius + k, q.z - k));
}
float4 surface(float3 p, float3 n) { return facet(p, n, radius * 0.6); }
""", x=-15),
    solid("hexprism", "Hexagonal Prism", "Puts the layer on the front and back of a six-sided prism.",
          [number("radius", "Radius", 0.75, 0.02, 5), number("depth", "Depth", 0.35, 0.01, 5), SMOOTH], """
float bound() { return length(float2(radius * 1.1547, depth)); }
float map(float3 p) {
    float r = smoothing * min(radius, depth) * 0.5;
    float2 h = float2(radius - r, depth - r);
    float3 k = float3(-0.8660254, 0.5, 0.57735);
    p = abs(p);
    p.xy -= 2.0 * min(dot(k.xy, p.xy), 0.0) * k.xy;
    float2 d = float2(length(p.xy - float2(clamp(p.x, -k.z * h.x, k.z * h.x), h.x)) * sign(p.y - h.x), p.z - h.y);
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
}
float4 surface(float3 p, float3 n) { return front(p, n, radius * 1.1547); }
"""),
    solid("starprism", "Star Prism", "Cuts the layer into a star and gives it depth.",
          [number("points", "Points", 5, 3, 12, 1), number("radius", "Radius", 0.95, 0.02, 5), number("inner", "Inner Radius", 0.45, 0.05, 1),
           number("depth", "Depth", 0.25, 0.01, 5), SMOOTH], """
float star(float2 p, float outer, float inner, float n) {
    float an = PI / n;
    float b = mod(atan(p.x, -p.y), 2.0 * an);
    b = b > an ? 2.0 * an - b : b;
    float2 q = length(p) * float2(cos(b), sin(b));
    float2 a = float2(outer, 0.0);
    float2 e = inner * float2(cos(an), sin(an)) - a;
    float2 w = q - a;
    float d = length(w - e * clamp(dot(w, e) / dot(e, e), 0.0, 1.0));
    return e.x * w.y - e.y * w.x > 0.0 ? -d : d;
}
float pointed(float3 p, float outer, float low, float n, float h) {
    float an = PI / n;
    float b = mod(atan(p.x, -p.y), 2.0 * an);
    b = b > an ? 2.0 * an - b : b;
    float3 q = float3(length(p.xy) * float2(cos(b), sin(b)), abs(p.z));
    float3 a = float3(0.0, 0.0, h);
    float3 f = normalize(cross(float3(outer, 0.0, -h), float3(low * cos(an), low * sin(an), -h)));
    return dot(q - a, f.z < 0.0 ? -f : f);
}
float bound() { return length(float2(radius, depth)); }
float map(float3 p) {
    if (starStyle > 0.5) return pointed(p, radius, radius * inner, floor(points + 0.5), depth);
    float r = smoothing * min(radius * inner, depth) * 0.5;
    return extrude(star(p.xy, radius, radius * inner, floor(points + 0.5)), p.z, depth, r);
}
float4 surface(float3 p, float3 n) { return front(p, n, radius); }
""", steps=112, extra=[{"id": "starStyle", "label": "Style", "type": "choice", "default": 0, "options": ["Flat", "Pointed"]}]),
    solid("starpolyhedron", "Star Polyhedron", "Puts the layer on a star made of two crossed pyramids.",
          [number("radius", "Radius", 0.95, 0.02, 5)], """
float tetra(float3 q) { return (max(abs(q.x + q.y) - q.z, abs(q.x - q.y) + q.z) - 1.0) * 0.57735027; }
float bound() { return radius; }
float map(float3 p) { float k = radius * 0.57735027; float3 q = p / k; return min(tetra(q), tetra(-q)) * k; }
float4 surface(float3 p, float3 n) { return facet(p, n, radius * 0.8); }
""", x=-25, steps=112),
    solid("heart", "Heart", "Cuts the layer into a puffy heart.",
          [number("heartSize", "Size", 1.3, 0.02, 5), number("depth", "Depth", 0.3, 0.01, 5), number("smoothing", "Smoothing", 0.7, 0, 1)], """
float heart2(float2 p) {
    p.x = abs(p.x);
    if (p.y + p.x > 1.0) return length(p - float2(0.25, 0.75)) - 0.35355339;
    float2 a = p - float2(0.0, 1.0);
    float2 b = p - 0.5 * max(p.x + p.y, 0.0);
    return sqrt(min(dot(a, a), dot(b, b))) * sign(p.x - p.y);
}
float bound() { return length(float2(heartSize * 0.7, depth)); }
float map(float3 p) {
    float s = heartSize;
    float d = heart2(float2(p.x, -p.y) / s + float2(0.0, 0.55)) * s;
    return extrude(d, p.z, depth, smoothing * depth * 0.95);
}
float4 surface(float3 p, float3 n) { return front(p, n, heartSize * 0.62); }
""", y=-30, steps=112),
    solid("cross", "Three-axis Cross", "Puts the layer on three bars that cross at the middle.",
          [number("reach", "Length", 0.9, 0.02, 5), number("thickness", "Thickness", 0.22, 0.01, 2), SMOOTH], """
float bound() { return length(float3(reach, thickness, thickness)); }
float map(float3 p) {
    float r = smoothing * thickness * 0.5;
    return min(sdRoundBox(p, float3(reach, thickness, thickness), r), min(sdRoundBox(p, float3(thickness, reach, thickness), r),
        sdRoundBox(p, float3(thickness, thickness, reach), r)));
}
float4 surface(float3 p, float3 n) { return boxFace(p, n, float3(reach)); }
""", x=-25, extra=[{"id": "keep", "label": "Keep Layer", "type": "switch", "default": False}], after="""
half4 main(float2 coord) {
    half4 c = shape(coord);
    if (keep < 0.5 || coord.x < 0.0 || coord.y < 0.0 || coord.x > size.x || coord.y > size.y) return c;
    return c + image.eval(coord) * (1.0 - c.a);
}
"""),
    solid("spherize", "Spherize", "Wraps the layer onto a sphere using a front projection or an equirectangular texture.",
          [number("radius", "Radius", 0.9, 0.02, 5), number("coverage", "Coverage", 0.5, 0.2, 1),
           {"id": "projection", "label": "Projection", "type": "choice", "default": 0, "options": ["Front", "Equirectangular"]}], """
float bound() { return radius; }
float map(float3 p) { return length(p) - radius; }
float4 surface(float3 p, float3 n) {
    float3 q = normalize(p);
    if (projection > 0.5) {
        float longitude = length(q.xz) > 0.00001 ? atan(q.x, -q.z) : 0.0;
        return wrap(float2(longitude / (2.0 * PI) + 0.5, acos(clamp(-q.y, -1.0, 1.0)) / PI));
    }
    float2 uv = 0.5 + q.xy / max(length(q.xy), 0.00001) * acos(clamp(-q.z, -1.0, 1.0)) / (PI * coverage) * 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return float4(0.0);
    return wrap(uv);
}
""", x=0, y=0, steps=64),
]

EXTRUDE_PARAMS = turns(-20, -35) + [number("depth", "Depth", 0.25, 0.005, 5), INSIDE] + PLACE + CAMERA + LIGHT
EXTRUDE = uniforms(EXTRUDE_PARAMS) + COMMON + INTERIOR + """
float alpha(float2 q, float2 h) { return pic(q / h * 0.5 + 0.5).a; }

half4 main(float2 coord) {
    float side = min(size.x, size.y);
    float unit = max(side * 0.5 * scale, 0.5);
    float3 h = float3(size / side, depth);
    float rb = length(h);
    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);
    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));
    float3 ow = float3(0.0, 0.0, -dist);
    float b = dot(ow, rw);
    float disc = b * b - dot(ow, ow) + rb * rb;
    if (disc <= 0.0) return half4(0.0);
    float3x3 m = orient * turn();
    float3 ro = ow * m;
    float3 rd = rw * m;
    float3 inv = 1.0 / max(abs(rd), float3(0.000001)) * sign(rd + 0.0000001);
    float3 t1 = (-h - ro) * inv;
    float3 t2 = (h - ro) * inv;
    float3 lo = min(t1, t2);
    float3 hi = max(t1, t2);
    float t0 = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float tEnd = min(hi.x, min(hi.y, hi.z));
    if (t0 >= tEnd) return half4(0.0);
    float dt = (tEnd - t0) / 64.0;
    float t = t0;
    float peak = 0.0;
    float tp = t0;
    bool hit = false;
    for (int i = 0; i <= 64; i++) {
        t = t0 + dt * float(i);
        float a = alpha((ro + rd * t).xy, h.xy);
        if (a > peak) { peak = a; tp = t; }
        if (a > 0.5) { hit = true; break; }
    }
    if (!hit && peak < 0.02) return half4(0.0);
    if (hit) {
        float lo2 = max(t - dt, t0);
        float hi2 = t;
        for (int i = 0; i < 6; i++) {
            float mid = 0.5 * (lo2 + hi2);
            if (alpha((ro + rd * mid).xy, h.xy) > 0.5) hi2 = mid; else lo2 = mid;
        }
        t = hi2;
    } else {
        t = tp;
    }
    float3 p = ro + rd * t;
    float e = 1.5 / unit;
    float4 c;
    float3 n;
    if (abs(p.z) > h.z - max(dt * abs(rd.z) * 1.5, 0.0005)) {
        float4 s = pic(p.xy / h.xy * 0.5 + 0.5);
        c = float4(s.rgb / max(s.a, 0.0001), 1.0);
        n = float3(0.0, 0.0, sign(p.z));
    } else {
        float2 g = float2(alpha(p.xy + float2(e, 0.0), h.xy) - alpha(p.xy - float2(e, 0.0), h.xy),
                          alpha(p.xy + float2(0.0, e), h.xy) - alpha(p.xy - float2(0.0, e), h.xy));
        n = length(g) > 0.0001 ? float3(-normalize(g), 0.0) : float3(0.0, 0.0, -1.0);
        c = inside();
    }
    c = shade(c, normalize(m * n), -rw);
    return half4(hit ? c : c * min(peak * 2.0, 1.0));
}
"""

FLIP_PARAMS = turns(0, 35) + [angle("axis", "Axis", 0), {"id": "back", "label": "Back", "type": "choice", "default": 0, "options": ["See-through", "Picture", "Color"]},
                              colour("backColor", "Back Color", "#ffffffff")] + PLACE + CAMERA + LIGHT + TEXTURE + [
    {"id": "pivot", "label": "Pivot", "type": "point", "default": [0, 0], "min": -5000, "max": 5000, "step": 1, "pixels": True},
    number("pivotDepth", "Pivot Depth", 0, -10, 10)]
FLIP = uniforms(FLIP_PARAMS) + COMMON + SHAPES + """
float3x3 spin(float d) {
    float a = radians(d);
    return float3x3(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0);
}

half4 main(float2 coord) {
    float side = min(size.x, size.y);
    float unit = max(side * 0.5 * scale, 0.5);
    float2 h = size / side;
    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), length(h) + 0.5);
    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));
    float3x3 m = orient * spin(axis) * turn() * spin(-axis);
    float3 pv = float3(pivot / unit, pivotDepth);
    float3 ro = (float3(0.0, 0.0, -dist) - pv) * m;
    float3 rd = rw * m;
    if (abs(rd.z) < 0.000001) return half4(0.0);
    float t = (-pv.z - ro.z) / rd.z;
    if (t <= 0.0) return half4(0.0);
    float2 p = (ro + rd * t).xy + pv.xy;
    float px = unit * dist / (t * rw.z);
    float edge = clamp(min(h.x - abs(p.x), h.y - abs(p.y)) * px + 0.5, 0.0, 1.0);
    if (edge <= 0.0) return half4(0.0);
    bool seen = rd.z > 0.0;
    float2 uv = p / h * 0.5 + 0.5;
    float4 c = tex(uv, h);
    if (!seen && back > 1.5) c = float4(backColor.rgb * backColor.a, backColor.a);
    else if (!seen && back > 0.5) c = tex(float2(1.0 - uv.x, uv.y), h);
    return half4(shade(c, normalize(m * float3(0.0, 0.0, seen ? -1.0 : 1.0)), -rw) * edge);
}
"""

TUNNEL_PARAMS = turns(0, 0) + [number("phase", "Phase", 0, -1000, 1000), number("speed", "Speed", 1, -20, 20), number("twist", "Twist", 0, -360, 360, 0.1),
                               number("repeat", "Repeat", 3, 1, 16, 1), number("fog", "Fog", 0.4, 0, 1), colour("fogColor", "Fog Color", "#ff000000")] + PLACE
TUNNEL = uniforms(TUNNEL_PARAMS, False) + COMMON.split("float4 shade")[0] + """
half4 main(float2 coord) {
    float unit = max(min(size.x, size.y) * 0.5 * scale, 0.5);
    float3 rd = turn() * normalize(float3((coord - size * 0.5 - position) / unit, 1.4));
    float r = length(rd.xy);
    float4 haze = float4(fogColor.rgb * fogColor.a, fogColor.a);
    if (r < 0.0001) return half4(haze);
    float3 p = rd / r;
    float tile = 2.0 * PI / max(floor(repeat + 0.5), 1.0);
    float along = tile * size.y / max(size.x, 0.0001);
    float z = p.z + time * speed;
    float u = fract((atan(p.y, p.x) + radians(twist) * p.z) / tile);
    float v = fract(z / along + phase);
    float4 c = pic(float2(u, v));
    float k = 1.0 - exp(-abs(p.z) * fog * 0.6);
    return half4(mix(c, haze, k));
}
"""

EFFECTS += [
    ("rasterextrude", "Raster Extrude", "Pushes the layer's picture out into a solid block; the sides take the interior color.", EXTRUDE_PARAMS, EXTRUDE),
    ("flip", "Flip Layer", "Turns the layer like a card in 3D, with its back see-through, a picture or a color.", FLIP_PARAMS, FLIP),
    ("tunnel", "Tunnel", "Lines an endless tunnel with the layer and flies through it. Phase shifts the texture along its length.", TUNNEL_PARAMS, TUNNEL),
]

SHORTCUTS = {
    "rasterextrude": [{"key": "text3d", "name": "3D Text",
                       "description": "Turns a text layer into solid 3D letters you can turn in 3D. It adds Raster Extrude, which works on any layer.",
                       "since": "5.1.0", "also": ["text"]}],
}

ORDER = ["cube", "box", "hollowbox", "cylinder", "ellipsoid", "torus", "pyramid", "octahedron", "hexprism", "starprism",
         "starpolyhedron", "heart", "cross", "rasterextrude", "flip", "spherize", "tunnel"]


STANDARD_STYLE = {"id": "style", "label": "Style", "type": "choice", "default": 0, "options": ["Classic", "Standard"], "when": {"style": [1]}}
STANDARD_EXTRA = {"tunnel": [{"id": "wrapStyle", "label": "Style", "type": "choice", "default": 0, "options": ["Classic", "Standard"], "when": {"wrapStyle": [2]}},
                             dict(number("depth", "Depth", 0.3, 0, 100), when={"wrapStyle": [1]})],
                  "flip": [dict(number("alZ", "Position Z", 0, -100000, 100000), when={"style": [1]})],
                  "rasterextrude": [dict({"id": "alLight", "label": "Direction", "type": "point", "default": [75, -75], "min": -100000, "max": 100000, "step": 1}, when={"style": [1]}),
                                    dict(number("alElev", "Elevation", 100, -100000, 100000, 0.1), when={"style": [1]})]}

STANDARD = {
    'cube': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\n'),
        ('float4 pic(float2 uv) {\n    return float4(image.eval(clamp(uv * size, float2(0.5), max(size - 0.5, float2(0.5)))));\n',
         'float4 pic(float2 uv) {\n    float2 at = clamp(uv * size, float2(0.5), max(size - 0.5, float2(0.5)));\n    float f = size.x / max(2.0 * edge * min(size.x, size.y) * 0.5 * scale, 1.0);\n    if (style < 0.5 || f < 1.5) return float4(image.eval(at));\n    float4 sum = float4(0.0);\n    for (int i = 0; i < 4; i++) {\n        for (int j = 0; j < 4; j++) sum += float4(image.eval(clamp(at + (float2(float(i), float(j)) - 1.5) * 0.25 * f, float2(0.5), max(size - 0.5, float2(0.5)))));\n    }\n    return sum / 16.0;\n'),
    ],
    'hollowbox': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\n'),
        ('float4 pic(float2 uv) {\n    return float4(image.eval(clamp(uv * size, float2(0.5), max(size - 0.5, float2(0.5)))));\n',
         'float4 pic(float2 uv) {\n    float2 at = clamp(uv * size, float2(0.5), max(size - 0.5, float2(0.5)));\n    float f = size.x / max(2.0 * width * min(size.x, size.y) * 0.5 * scale, 1.0);\n    if (style < 0.5 || f < 1.5) return float4(image.eval(at));\n    float4 sum = float4(0.0);\n    for (int i = 0; i < 4; i++) {\n        for (int j = 0; j < 4; j++) sum += float4(image.eval(clamp(at + (float2(float(i), float(j)) - 1.5) * 0.25 * f, float2(0.5), max(size - 0.5, float2(0.5)))));\n    }\n    return sum / 16.0;\n'),
    ],
    'cross': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\n'),
        ('    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);\n    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));\n',
         '    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);\n    float3 rw = normalize(float3((coord - size * 0.5 - position * (style > 0.5 ? scale : 1.0)) / unit, dist));\n'),
    ],
    'heart': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\n'),
        ('    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);\n    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));\n',
         '    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);\n    float3 rw = normalize(float3((coord - size * 0.5 - position * (style > 0.5 ? scale : 1.0)) / unit, dist));\n'),
    ],
    'rasterextrude': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\nuniform float2 alLight;\nuniform float alElev;\n'),
        ('float alpha(float2 q, float2 h) {',
         'float4 alShade(float4 c, float3 n) {\n    float3 l = normalize(float3(alLight, -4.1 * alElev));\n    float d = max(dot(n, l), 0.0);\n    float g = shine * pow(d, max(glossiness, 0.0001));\n    float3 lc = float3(lightColor.rgb);\n    return float4(c.rgb * (shading * mix(d, 1.0, ambient) * (1.0 - 0.1 * shine) * (1.0 - g)) * lc + g * lc * c.a, c.a);\n}\n\nfloat alpha(float2 q, float2 h) {'),
        ('    c = shade(c, normalize(m * n), -rw);\n',
         '    c = style > 0.5 ? alShade(c, normalize(m * n)) : shade(c, normalize(m * n), -rw);\n'),
    ],
    'starprism': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\n'),
        ('    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);\n    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));\n',
         '    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), rb + 0.5);\n    float3 rw = normalize(float3((coord - size * 0.5 - position * (style > 0.5 ? scale : 1.0)) / unit, dist));\n'),
    ],
    'flip': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\nuniform float alZ;\n'),
        ('    float3 pv = float3(pivot / unit, pivotDepth);\n    float3 ro = (float3(0.0, 0.0, -dist) - pv) * m;\n',
         '    float3 pv = float3(pivot / unit, pivotDepth);\n    if (style > 0.5) {\n        float3 bh = float3(h, 0.0096);\n        float3 rl = (float3(0.0, 0.0, -dist - alZ) - pv) * m + pv;\n        float3 rdl = rw * m;\n        float3 inv = 1.0 / max(abs(rdl), float3(0.000001)) * sign(rdl + 0.0000001);\n        float3 lo = min((-bh - rl) * inv, (bh - rl) * inv);\n        float3 hi = max((-bh - rl) * inv, (bh - rl) * inv);\n        float tn = max(max(lo.x, lo.y), lo.z);\n        if (tn > min(min(hi.x, hi.y), hi.z) || tn <= 0.0) return half4(0.0);\n        float3 q = rl + rdl * tn;\n        bool face = lo.z >= max(lo.x, lo.y);\n        float4 c = tex(face ? q.xy / h * 0.5 + 0.5 : float2(0.5 - q.y / h.y * 0.5, 0.5 + q.z / bh.z * 0.5), h);\n        if (face && rdl.z < 0.0 && back > 1.5) c = float4(backColor.rgb * backColor.a, backColor.a);\n        else if (face && rdl.z < 0.0 && back > 0.5) c = tex(float2(0.5 - q.x / h.x * 0.5, 0.5 + q.y / h.y * 0.5), h);\n        return half4(shade(c, normalize(m * (face ? float3(0.0, 0.0, -sign(rdl.z)) : float3(sign(q.x), 0.0, 0.0))), -rw));\n    }\n    float3 ro = (float3(0.0, 0.0, -dist) - pv) * m;\n'),
    ],
    'tunnel': [
        ('uniform float scale;\n',
         'uniform float scale;\nuniform float style;\nuniform float wrapStyle;\nuniform float depth;\n'),
        ('half4 main(float2 coord) {\n',
         'half4 main(float2 coord) {\n    if (style > 0.5 && wrapStyle > 0.5) {\n        float2 n = (coord - size * 0.5 - position) / max(size * 0.5 * scale, float2(0.5));\n        float r = length(n);\n        if (r > 1.0) return half4(0.0);\n        float a = (atan(n.x, -n.y) + radians(twist) * r) * max(floor(repeat + 0.5), 1.0) / (2.0 * PI);\n        float2 q = 1.0 - abs(1.0 - mod(float2(2.0 * depth / max(r, 0.0001) + 1.0 + phase + time * speed, a), 2.0));\n        return half4(mix(float4(fogColor.rgb * fogColor.a, fogColor.a), pic((q - 0.5) * 0.995 + 0.5), clamp(1.0 - fog * (1.0 - r), 0.0, 1.0)));\n    }\n    if (style > 0.5) {\n        float2 n = (coord - size * 0.5 - position) / max(size * 0.5 * scale, float2(0.5));\n        float r = length(n);\n        if (r > 0.995) return half4(0.0);\n        float4 shade = float4(fogColor.rgb * fogColor.a, fogColor.a);\n        if (r < 0.0001) return half4(shade);\n        float g = 1.0 / r + phase + time * speed;\n        float2 q = 0.5 + 0.5 * float2(-abs(n.x), n.y) / r * g;\n        q = 1.0 - abs(1.0 - mod(q, 2.0));\n        float4 c = pic(q);\n        return half4(mix(c, shade, 1.0 - exp(-0.45 * fog * (1.0 / r - 1.0))));\n    }\n'),
    ],
}


ALIGHT_PARAMS = [{"id": "alight", "label": "Style", "type": "choice", "default": 0, "options": ["Classic", "Standard"], "when": {"alight": [1]}},
                 dict(number("alZ", "Position Z", 600, -100000, 100000, 1), when={"alight": [1]}),
                 dict({"id": "alLight", "label": "Direction", "type": "point", "default": [75, -75], "min": -100000, "max": 100000, "step": 1}, when={"alight": [1]}),
                 dict(number("alElev", "Elevation", 100, -100000, 100000, 0.1), when={"alight": [1]})]

ALIGHT_EXTRA = {"octahedron": [dict(number("alHeight", "Height", 1, 0.0001, 100000), when={"alight": [1]})],
                "box": [{"id": "faceStyle", "label": "Style", "type": "choice", "default": 0, "options": ["Classic", "Standard"], "when": {"faceStyle": [2]}},
                        {"id": "lightStyle", "label": "Style", "type": "choice", "default": 0, "options": ["Classic", "Standard"], "when": {"lightStyle": [2]}}]}

ALIGHT_POST = {"pyramid": [("    float4 col = alShade(surface(p, n), normalize(m * n));", "    float4 col = alShade(surfaceV(p, n, rd), normalize(m * n));")],
               "box": [("float4 alShade(float4 c, float3 n) {\n",
                        "float4 alShade(float4 c, float3 n) {\n    if (lightStyle > 0.5) return float4(min(c.rgb * c.rgb / max(c.a, 0.0001) * 1.49, float3(c.a)), c.a);\n")]}

ALIGHT_MAIN = """
float4 alShade(float4 c, float3 n) {
    float3 l = normalize(float3(alLight, -4.1 * alElev));
    float d = max(dot(n, l), 0.0);
    float g = shine * pow(d, max(glossiness, 0.0001));
    float3 lc = float3(lightColor.rgb);
    return float4(c.rgb * (shading * mix(d, 1.0, ambient) * (1.0 - 0.1 * shine) * (1.0 - g)) * lc + g * lc * c.a, c.a);
}

half4 alMain(float2 coord) {
    float f = size.y;
    float3 rw = normalize(float3((coord - size * 0.5) / (f * max(scale, 0.0001)), 1.0));
    float3 c = float3(position.x, 100.0 - position.y, alZ + 100.0) / 100.0;
    float rb = bound() * 1.02 + 0.02;
    float b = -dot(c, rw);
    float disc = b * b - dot(c, c) + rb * rb;
    if (disc <= 0.0) return half4(0.0);
    float3x3 m = orient * turn();
    float3 ro = -c * m;
    float3 rd = rw * m;
    float far = -b + sqrt(disc);
    float t = max(-b - sqrt(disc), 0.0);
    float px = f * scale / rw.z;
    float best = 1e9;
    float tb = t;
    bool hit = false;
    for (int i = 0; i < STEPS; i++) {
        float h = map(ro + rd * t);
        float g = h * px / max(t, 0.0001);
        if (g < best) { best = g; tb = t; }
        if (g < 0.2) { hit = true; break; }
        t += h * 0.9;
        if (t > far) break;
    }
    if (!hit && best >= EDGE) return half4(0.0);
    if (!hit) t = tb;
    float e = max(0.5 * t / px, 0.00005);
    float3 p = ro + rd * t;
    float3 n = normal(p, e);
    if (!hit) p -= n * map(p);
    float4 col = alShade(surface(p, n), normalize(m * n));
    return half4(hit ? col : col * FADE);
}
"""


def edged(main, hard=False):
    main = main if hard else main.replace("if (g < 0.2) { hit = true; break; }", "if (g < 0.05) { hit = true; break; }")
    return main.replace("EDGE", "1.5" if hard else "0.5").replace("FADE", "1.0" if hard else "(0.5 - best)")


ALIGHT = {
    "cube": [],
    "ellipsoid": [("float3 radii() { return radius * float3(1.0, squeezeRatio, flattenRatio); }",
                   "float3 radii() { return radius * (alight > 0.5 ? float3(squeezeRatio, flattenRatio, 1.0) : float3(1.0, squeezeRatio, flattenRatio)); }"),
                  ("    float3 q = normalize(p / radii());", "    float3 q = normalize(alight > 0.5 ? p : p / radii());")],
    "hexprism": [("    float3 k = float3(-0.8660254, 0.5, 0.57735);\n    p = abs(p);\n",
                  "    float3 k = float3(-0.8660254, 0.5, 0.57735);\n    p = abs(alight > 0.5 ? p.xzy : p);\n"),
                 ("float4 surface(float3 p, float3 n) { return front(p, n, radius * 1.1547); }",
                  "float4 surface(float3 p, float3 n) {\n    if (alight > 0.5 && abs(n.y) > 0.7) return wrap(float2(0.5 + p.x / (4.0 * radius), 0.5 - p.z / (4.0 * radius)));\n    if (alight > 0.5) return wrap(float2(0.5 + atan(p.x, -p.z) / (2.0 * PI), 0.5 + p.y / (2.0 * depth)));\n    return front(p, n, radius * 1.1547);\n}")],
    "starprism": [("    if (starStyle > 0.5) return pointed(",
                   "    if (alight > 0.5) { float k = floor(points + 0.0001); return extrude(star(p.xy, radius, radius * 0.4985 / sin(PI / k + 0.5231), k), p.z, depth, 0.0); }\n    if (starStyle > 0.5) return pointed("),
                  ("float4 surface(float3 p, float3 n) { return front(p, n, radius); }",
                   "float4 surface(float3 p, float3 n) {\n    if (alight > 0.5) {\n        float2 f = float2(0.45 + 0.5607 * p.x / radius, 0.5496 + 0.5607 * p.y / radius);\n        float d = 0.3735 * p.z / max(depth, 0.0001);\n        if (abs(n.z) < 0.5 && abs(n.x) * 1.1 > abs(n.y)) f.x = 0.45 + sign(n.x) * d;\n        if (abs(n.z) < 0.5 && abs(n.x) * 1.1 <= abs(n.y)) f.y = 0.5496 + sign(n.y) * d;\n        return wrap(f);\n    }\n    return front(p, n, radius);\n}")],
    "octahedron": [("float bound() { return radius; }", "float bound() { return alight > 0.5 ? radius * max(alHeight, 1.0) : radius; }"),
                   ("float map(float3 p) {\n    p = abs(p);\n",
                    "float map(float3 p) {\n    if (alight > 0.5) { float h = max(alHeight, 0.0001); p = abs(p); return (p.x + p.y / h + p.z - radius) / sqrt(2.0 + 1.0 / (h * h)); }\n    p = abs(p);\n"),
                   ("float4 surface(float3 p, float3 n) { return facet(p, n, radius * 0.6); }",
                    "float4 surface(float3 p, float3 n) {\n    float3 q = abs(p) / (radius * float3(1.0, max(alHeight, 0.0001), 1.0));\n    if (alight > 0.5) return wrap(float2(0.9971 * (0.5 * q.y + q.z) + 0.0005, 0.867 * (1.0 - q.y) + 0.0006));\n    return facet(p, n, radius * 0.6);\n}")],
    "pyramid": [("float bound() { return length(float3(base, height * 0.5, base)); }",
                 "float bound() { return alight > 0.5 ? base * max(height, 1.0) : length(float3(base, height * 0.5, base)); }"),
                ("float map(float3 p) { float s = 2.0 * base;",
                 "float map(float3 p) {\n    if (alight > 0.5) { float k = 1.0 / max(height, 0.0001); return max((abs(p.x) + abs(p.z) - p.y * k - base) / sqrt(2.0 + k * k), p.y); }\n    float s = 2.0 * base;"),
                ("float4 surface(float3 p, float3 n) { return boxFace(p, n, float3(base, height * 0.5, base)); }",
                 "float4 surface(float3 p, float3 n) { return boxFace(p, n, float3(base, height * 0.5, base)); }\nfloat4 surfaceV(float3 p, float3 n, float3 rd) {\n    float3 q = abs(p) / (base * float3(1.0, max(height, 0.0001), 1.0));\n    float2 f = float2(0.9971 * (0.5 * q.y + q.z) + 0.0005, 0.867 * (1.0 - q.y) + 0.0006);\n    float v = clamp(2.0 - 3.3 * rd.y, 0.0, 1.0);\n    float t = 0.0115 * v;\n    float w = 0.8 * v * (1.0 - min(q.y * q.y / max(t * t, 0.00000001), 1.0));\n    return wrap(mix(f, float2(0.5 + 0.5 * (p.x - p.z) / base, 0.5 + 0.5 * (p.x + p.z) / base), w));\n}")],
    "spherize": [("float4 surface(float3 p, float3 n) {\n    float3 q = normalize(p);\n",
                  "float4 surface(float3 p, float3 n) {\n    float3 q = normalize(p);\n    if (alight > 0.5) return wrap(float2(0.5 + atan(q.x, -q.z) / (2.0 * PI), acos(clamp(-q.y, -1.0, 1.0)) / PI));\n")],
    "torus": [("float map(float3 p) { return length(float2(length(p.xy) - radius, p.z)) - thickness; }",
               "float map(float3 p) { if (alight > 0.5) p = p.xzy; return length(float2(length(p.xy) - radius, p.z)) - thickness; }"),
              ("float4 surface(float3 p, float3 n) {\n    return wrap(",
               "float4 surface(float3 p, float3 n) {\n    if (alight > 0.5) return wrap(float2(0.5 - atan(p.x, -p.z) / PI, 0.25 - atan(-p.y, length(p.xz) - radius) / (2.0 * PI)));\n    return wrap(")],
    "cylinder": [("float bound() { return length(float2(radius, height)); }",
                  "float rad() { return alight > 0.5 ? 2.0 * radius : radius; }\nfloat bound() { return length(float2(rad(), height)); }"),
                 ("    float r = smoothing * min(radius, height) * 0.5;\n    float2 d = float2(length(p.xz) - radius + r,",
                  "    float r = smoothing * min(rad(), height) * 0.5;\n    float2 d = float2(length(p.xz) - rad() + r,"),
                 ("float2(p.x, n.y < 0.0 ? -p.z : p.z) / radius * 0.5 + 0.5", "float2(p.x, n.y < 0.0 ? -p.z : p.z) / rad() * 0.5 + 0.5")],
    "cross": [("float bound() { return length(float3(reach, thickness, thickness)); }\nfloat map(float3 p) {\n    float r = smoothing * thickness * 0.5;\n    return min(sdRoundBox(p, float3(reach, thickness, thickness), r), min(sdRoundBox(p, float3(thickness, reach, thickness), r),\n        sdRoundBox(p, float3(thickness, thickness, reach), r)));",
               "float bar() { return alight > 0.5 ? thickness * reach : thickness; }\nfloat bound() { return length(float3(reach, bar(), bar())); }\nfloat map(float3 p) {\n    float w = bar();\n    float r = smoothing * w * 0.5;\n    return min(sdRoundBox(p, float3(reach, w, w), r), min(sdRoundBox(p, float3(w, reach, w), r),\n        sdRoundBox(p, float3(w, w, reach), r)));")],
    "hollowbox": [("float3 dims() { float side", "float3 dims() { if (alight > 0.5) return float3(width, height, depth); float side"),
                  ("float hole(float3 p) { float3 h = dims();", "float hole(float3 p) { if (alight > 0.5) return 1e9; float3 h = dims();"),
                  ("    if (abs(n.y) > 0.7 || abs(hole(p)) < abs(sdBox(p, h))) return inside();", "    if (alight < 0.5 && (abs(n.y) > 0.7 || abs(hole(p)) < abs(sdBox(p, h)))) return inside();"),
                  ("    float f = size.x / max(2.0 * width * min(size.x, size.y) * 0.5 * scale, 1.0);",
                   "    float f = size.x / max(alight > 0.5 ? 2.0 * width * size.y * scale / max(alZ / 100.0 + 1.0 - depth, 0.1) : 2.0 * width * min(size.x, size.y) * 0.5 * scale, 1.0);")],
    "heart": [("float bound() { return length(float2(heartSize * 0.7, depth)); }",
               "float bound() { return alight > 0.5 ? heartSize * 1.75 + 0.6 : length(float2(heartSize * 0.7, depth)); }"),
              ("float map(float3 p) {\n    float s = heartSize;",
               'float map(float3 p) {\n    if (alight > 0.5) {\n        float k = heartSize / 15.0 * 1.0055;\n        float3 q = float3(p.x, -p.y - 0.5011, p.z - 0.0036) / k;\n        float x = abs(q.x);\n        float y = 3.9351 + 1.2106 * q.y - 1.0126 * x * sqrt(max((20.0 - x) / 15.0, 0.0));\n        return (length(float3(x, y, q.z * (2.015 - 0.1211 * y / 15.0))) - 15.0) * k * 0.3;\n    }\n    float s = heartSize;'),
              ("float4 surface(float3 p, float3 n) { return front(p, n, heartSize * 0.62); }",
               'float4 surface(float3 p, float3 n) {\n    if (alight > 0.5) {\n        float3 q = float3(p.x, -p.y - 0.5011, p.z - 0.0036) / (heartSize / 15.0 * 1.0055);\n        return pic(fract(float2((q.x < 0.0 ? -1.0 : 1.0) * (0.1182 - 6.14 * atan(1.2 * abs(q.x), 0.5 - q.z) / (2.0 * PI)), 0.5326 - 0.169 * q.y)));\n    }\n    return front(p, n, heartSize * 0.62);\n}')],
    "box": [("float3 dims() { float side", "float3 dims() { if (alight > 0.5) return float3(width, height, depth); float side"),
            ("float4 surface(float3 p, float3 n) { return boxFace(p, n, dims()); }",
             "float4 surface(float3 p, float3 n) { return boxFace(p, n, alight > 0.5 && faceStyle < 0.5 ? float3(1.0) : dims()); }")],
}


AL_LIGHT = [dict(number("intensity", "Light Intensity", 1, 0, 10), section="light"), dict(number("shine", "Shine", 0.8, 0, 1), section="light"),
            dict(number("spec", "Specularity", 30, 0.1, 500, 0.1), section="light"), dict(number("ambient", "Ambient", 0.1, 0, 1), section="light"),
            dict(colour("lightColor", "Color", "#ffffffff"), section="light"),
            dict({"id": "lightDir", "label": "Direction", "type": "point", "default": [75, -75], "min": -100000, "max": 100000, "step": 1}, section="light"),
            dict(number("lightDepth", "Elevation", 100, -100000, 100000, 0.1), section="light")]
AL_TEXTURE = [dict({"id": "texMirror", "label": "Mirror", "type": "switch", "default": False}, section="texture"),
              dict(number("texRepeatX", "Repeat X", 1, 0.01, 100), section="texture"), dict(number("texRepeatY", "Repeat Y", 1, 0.01, 100), section="texture"),
              dict(angle("texAngle", "Texture Angle", 0), section="texture"), dict(number("texCrop", "Crop", 1, 0.01, 100), section="texture")]
AL_TURN = [angle("orientA", "Orientation \u03b1", 0), angle("orientB", "Orientation \u03b2", 0), angle("orientG", "Orientation \u03b3", 0)] + turns(0, 0)
AL_PLACE = [{"id": "position", "label": "Position", "type": "point", "default": [0, 100], "min": -100000, "max": 100000, "step": 1},
            number("alZ", "Position Z", 600, -100000, 100000, 1), number("scale", "Scale", 1, 0.01, 100)]

AL_CORE = """
float3x3 euler(float3 d) {
    float3 a = radians(d);
    float cx = cos(a.x), sx = sin(a.x), cy = cos(a.y), sy = sin(a.y), cz = cos(a.z), sz = sin(a.z);
    return float3x3(cz, -sz, 0.0, sz, cz, 0.0, 0.0, 0.0, 1.0) * float3x3(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy) *
        float3x3(1.0, 0.0, 0.0, 0.0, cx, -sx, 0.0, sx, cx);
}

float3x3 turn() { return euler(float3(orientA, orientB, orientG)) * euler(float3(rotX, rotY, rotZ)); }

float4 pic(float2 uv) {
    return float4(image.eval(clamp(uv * size, float2(0.5), max(size - 0.5, float2(0.5)))));
}

float4 wrap(float2 uv) {
    float t = radians(texAngle);
    float2 q = uv - 0.5;
    q = float2(cos(t) * q.x + sin(t) * q.y, cos(t) * q.y - sin(t) * q.x) * float2(texRepeatX, texRepeatY) + 0.5;
    float2 f = fract(q);
    if (texMirror > 0.5) f = mix(f, 1.0 - f, mod(floor(q), 2.0));
    return pic((f - 0.5) / max(texCrop, 0.0001) + 0.5);
}

float4 alShade(float4 c, float3 n) {
    float3 l = normalize(float3(lightDir, -4.1 * lightDepth));
    float d = max(dot(n, l), 0.0);
    float g = shine * pow(d, max(spec, 0.0001));
    float3 lc = float3(lightColor.rgb);
    return float4(c.rgb * (intensity * mix(d, 1.0, ambient) * (1.0 - 0.1 * shine) * (1.0 - g)) * lc + g * lc * c.a, c.a);
}

float3 normal(float3 p, float e) {
    float2 k = float2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * e) + k.yyx * map(p + k.yyx * e) + k.yxy * map(p + k.yxy * e) + k.xxx * map(p + k.xxx * e));
}
"""


def alight_solid(key, name, description, dims, code, steps=128):
    params = AL_TURN + dims + AL_PLACE + AL_LIGHT + AL_TEXTURE
    main = edged(ALIGHT_MAIN[ALIGHT_MAIN.index("half4 alMain"):].replace("half4 alMain(float2 coord)", "half4 main(float2 coord)").replace("STEPS", str(steps)))
    sksl = uniforms(params) + "\nconst float PI = 3.14159265;\n" + code.split("//CORE")[0] + AL_CORE + code.split("//CORE")[1] + main
    return key, name, description, params, sksl


ALIGHT_EFFECTS = [
    alight_solid("ring", "Ring", "Wraps the layer around a ring with flat faces, like a short pipe, with light and depth.",
                 [number("radius", "Radius", 100, 0, 100000, 0.1), number("thickness", "Width", 10, 0, 100000, 0.1), number("height", "Length", 50, 0, 100000, 0.1),
                  number("smoothing", "Smoothing", 0, 0, 1, 0.001)], """
float map(float3 p) {
    float3 q = p * 100.0;
    float s = smoothing * min(thickness, height) * 0.5;
    float r = length(q.xz);
    float2 w = float2(max(r - radius, radius - thickness - r), abs(q.y) - height) + s;
    return (min(max(w.x, w.y), 0.0) + length(max(w, 0.0)) - s) / 100.0;
}
//CORE
float bound() { return length(float2(radius, height)) / 100.0 + 0.01; }
float4 surface(float3 p, float3 n) {
    float3 q = p * 100.0;
    float c = max(radius * 4.0 / 3.0, 0.0001);
    if (abs(n.y) > 0.7) return wrap(float2(0.5 + q.x / c, 0.5 + sign(n.y) * q.z / c));
    return wrap(float2(0.5 + (atan(-q.x, q.z) - PI) * radius / 200.0, 0.5 + q.y / 200.0));
}
"""),
]


STAR_STANDARD = alight_solid("starpolyhedron", "", "",
    [number("starSize", "Size", 50, 0, 100000, 0.1), number("depth", "Depth", 50, -100000, 100000, 0.1),
     number("pointLength", "Point Length", 100, 0, 100000, 0.1), number("smoothing", "Smoothing", 0, 0, 1, 0.001),
     number("points", "Points", 5, 2, 100, 1)], """
float starOuter() { return starSize * (0.8 + pointLength / 100.0); }
float starPoint(float3 q, float w, float ro, float h) {
    return max(-q.y, (abs(q.x) / w + q.y / ro + abs(q.z) / h - 1.0) / length(float3(1.0 / w, 1.0 / ro, 1.0 / h)));
}
float map(float3 p) {
    if (depth * starSize <= 0.0 || starSize <= 0.0) return 1.0;
    float3 q = p * 100.0 * float3(1.0, -1.0, 1.0);
    float w = max(starSize, 0.001);
    float ro = max(starOuter(), 0.001);
    float h = max(0.84 * depth * starSize / 100.0, 0.001);
    float sec = 2.0 * PI / max(floor(points + 0.0001), 2.0);
    float a = atan(q.x, q.y);
    float a0 = floor(a / sec + 0.5) * sec;
    float r = length(q.xy);
    float d = 1e9;
    for (int k = -1; k <= 1; k++) {
        float b = a - a0 - float(k) * sec;
        d = min(d, starPoint(float3(r * sin(b), r * cos(b), q.z), w, ro, h));
    }
    return d / 100.0;
}
//CORE
float bound() { return max(starOuter(), abs(depth * starSize / 100.0)) / 100.0 + 0.01; }
float4 surface(float3 p, float3 n) {
    float3 q = p * 100.0;
    float th = 1.52 * starSize;
    return wrap(float2(0.5 + q.x / (th * size.x / size.y), 0.5 + q.y / th));
}
""")


STAR_ON = True
STAR_NEW = {"rotX": 0, "rotY": 0, "position": [0, 100], "scale": 3, "shine": 0.8, "texMirror": False, "texRepeatX": 1, "texRepeatY": 1, "texAngle": 0}
STAR_RENAMED = {"position": "alPos", "scale": "alScale", "shine": "alShine", "ambient": "alAmbient", "lightColor": "alColor", "texMirror": "alMirror",
                "texRepeatX": "alRepeatX", "texRepeatY": "alRepeatY", "texAngle": "alTexAngle", "texCrop": "alCrop", "depth": "starDepth"}
STAR_FUNCS = ["euler", "turn", "pic", "wrap", "alShade", "normal", "map", "bound", "surface", "starOuter", "main"]


def star_combined(old_params, old_sksl):
    import re
    key, name, description, params, sksl = STAR_STANDARD
    new = []
    for p in params:
        p = dict(p)
        if p["id"] in ("rotX", "rotY", "rotZ"):
            continue
        if p["id"] == "alZ": p["default"] = 500
        if p["id"] == "scale": p["default"] = 3
        if p["id"] in STAR_RENAMED: p["id"] = STAR_RENAMED[p["id"]]
        if p["id"] in STAR_NEW.values() and False: pass
        new.append(dict(p, when={"style": [1]}) if "when" not in p else p)
    for old, ren in STAR_RENAMED.items():
        sksl = re.sub(r"\b%s\b" % old, ren, sksl)
    for f in STAR_FUNCS:
        sksl = re.sub(r"\b%s\(" % f, f + "S(", sksl)
    sksl = sksl.replace("alZ + 100.0) / 100.0;", "alZ + 100.0) / 100.0 + float3(0.0, 0.05, 0.0);")
    sksl = sksl.replace("const float PI", "const float PIS").replace("PI ", "PIS ").replace("PI)", "PIS)").replace("PI;", "PIS;")
    body = sksl[sksl.index("\n\n") + 2:]
    olds = [dict(p, when={"style": [0]}) if "when" not in p and p["id"] not in ("rotX", "rotY", "rotZ") else p for p in old_params]
    rot = [dict(p, default=0, legacyDefault=p["default"]) if p["id"] in ("rotX", "rotY") else p for p in olds if p["id"] in ("rotX", "rotY", "rotZ")]
    rest = [p for p in olds if p["id"] not in ("rotX", "rotY", "rotZ")]
    style = {"id": "style", "label": "Style", "type": "choice", "default": 1, "options": ["Classic", "Standard"], "legacyDefault": 0, "when": {"style": [0]}}
    orient = [p for p in new if p["id"].startswith("orient")]
    shape = [p for p in new if not p["id"].startswith("orient")]
    sec = lambda ps, k: [p for p in ps if p.get("section") == k]
    plain = lambda ps: [p for p in ps if "section" not in p]
    allp = orient + rot + plain(shape) + plain(rest) + sec(shape, "light") + sec(rest, "light") + sec(shape, "texture") + sec(rest, "texture") + [style]
    assert len(allp) == len(orient + rot + shape + rest) + 1
    head = uniforms(allp)
    old_body = old_sksl[len(uniforms(old_params)):]
    old_body = old_body.replace("half4 main(float2 coord) {", "half4 main(float2 coord) {\n    if (style > 0.5) return mainS(coord);", 1)
    return allp, head + body + old_body


def alightened(sksl, edits, steps, hard=False):
    for before, after in edits:
        assert sksl.count(before) == 1, before
        sksl = sksl.replace(before, after)
    front = ""
    head = "half4 shape(float2 coord)" if "half4 shape(float2 coord)" in sksl else "half4 main(float2 coord)"
    assert sksl.count(head) == 1
    sksl = sksl.replace(head, front + edged(ALIGHT_MAIN.replace("STEPS", str(steps)), hard) + "\n" + head + " {\n    if (alight > 0.5) return alMain(coord);", 1)
    return sksl.replace(head + " {\n    if (alight > 0.5) return alMain(coord); {", head + " {\n    if (alight > 0.5) return alMain(coord);", 1)


def main():
    found = {key: (name, description, params, sksl) for key, name, description, params, sksl in EFFECTS}
    assert sorted(found) == sorted(ORDER)
    for key in ORDER:
        name, description, params, sksl = found[key]
        if key in STANDARD:
            params = params + [STANDARD_STYLE] + STANDARD_EXTRA.get(key, [])
            for before, after in STANDARD[key]:
                assert sksl.count(before) == 1, (key, before)
                sksl = sksl.replace(before, after)
        if key == "starpolyhedron" and STAR_ON:
            params, sksl = star_combined(params, sksl)
            description = "Puts the layer on a star whose points rise to a ridge in front and behind, with light and depth."
        if key in ALIGHT:
            extra = ALIGHT_EXTRA.get(key, [])
            params = params + ALIGHT_PARAMS + extra
            sksl = sksl.replace("uniform float time;\n", "uniform float time;\nuniform float alight;\nuniform float alZ;\nuniform float2 alLight;\nuniform float alElev;\n" + "".join(f"uniform float {p['id']};\n" for p in extra), 1)
            sksl = alightened(sksl, ALIGHT[key], 128, key in ("ellipsoid", "spherize"))
            for before, after in ALIGHT_POST.get(key, []):
                assert sksl.count(before) == 1, before
                sksl = sksl.replace(before, after)
        effect = {"id": key, "name": name, "category": "3d", "since": SINCE, "description": description, "params": params, "sksl": sksl}
        if key in SHORTCUTS:
            effect["shortcuts"] = SHORTCUTS[key]
        text = json.dumps(effect, indent=1, ensure_ascii=False) + "\n"
        path = PACK / f"{key}.json"
        if "--check" in sys.argv:
            if not path.exists() or path.read_text() != text:
                sys.exit(f"{path.name} is out of date: run python3 tools/gen_3d_effects.py")
        else:
            path.write_text(text)
    for key, name, description, params, sksl in ALIGHT_EFFECTS:
        effect = {"id": key, "name": name, "category": "3d", "since": "5.3.2", "description": description, "params": params, "sksl": sksl}
        text = json.dumps(effect, indent=1, ensure_ascii=False) + "\n"
        path = PACK / f"{key}.json"
        if "--check" in sys.argv:
            if not path.exists() or path.read_text() != text:
                sys.exit(f"{path.name} is out of date: run python3 tools/gen_3d_effects.py")
        else:
            path.write_text(text)
    index = json.loads((PACK / "index.json").read_text())
    missing = [k for k in ORDER + [e[0] for e in ALIGHT_EFFECTS] if k not in index]
    if missing and "--check" in sys.argv:
        sys.exit("index.json misses " + " ".join(missing))
    if missing:
        (PACK / "index.json").write_text(json.dumps(index + missing, indent=1) + "\n")


if __name__ == "__main__":
    main()
