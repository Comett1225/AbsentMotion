#!/usr/bin/env python3
import json
import sys
from pathlib import Path

PACK = Path(__file__).resolve().parents[1] / "app/src/main/assets/effects"
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
LIGHT = [number("shading", "Shading", 0.6, 0, 1), angle("light", "Light Angle", 135), number("shine", "Shine", 0.3, 0, 1)]
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
    float3 l = normalize(float3(cos(a), -sin(a), -1.2));
    float k = 1.0 - shading + shading * (0.3 + 0.85 * max(dot(n, l), 0.0));
    float s = shine * pow(max(dot(n, normalize(l + v)), 0.0), 40.0);
    return float4(c.rgb * k + s * c.a, c.a);
}
"""

SHAPES = """
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
    return pic(cover(uv * 0.5 + 0.5, face));
}

float4 front(float3 p, float3 n, float r) {
    return pic(cover(float2(n.z > 0.0 ? -p.x : p.x, p.y) / r * 0.5 + 0.5, float2(1.0)));
}

float4 facet(float3 p, float3 n, float r) {
    float3 f = normalize(sign(n));
    float3 w = float3(-f.z, 0.0, f.x);
    float3 u = dot(w, w) > 0.0 ? normalize(w) : float3(1.0, 0.0, 0.0);
    return pic(cover(float2(dot(p, u), dot(p, cross(u, f))) / r * 0.5 + 0.5, float2(1.0)));
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
    float3x3 m = turn();
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
    if (hit && c.a < 0.996) {
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


def uniforms(params):
    lines = ["uniform shader image;", "uniform float2 size;", "uniform float time;"]
    for p in params:
        kind = p["type"]
        lines.append(f"uniform float2 {p['id']};" if kind == "point" else
                     f"layout(color) uniform half4 {p['id']};" if kind == "color" else f"uniform float {p['id']};")
    return "\n".join(lines) + "\n"


def solid(key, name, description, dims, code, x=-20, y=-35, steps=96):
    params = turns(x, y) + dims + PLACE + CAMERA + LIGHT
    sksl = uniforms(params) + COMMON + SHAPES + code + MARCH.replace("STEPS", str(steps))
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
    if (abs(n.y) > 0.7) return pic(cover(float2(p.x, n.y < 0.0 ? -p.z : p.z) / radius * 0.5 + 0.5, float2(1.0)));
    return pic(float2(atan(p.x, -p.z) / (2.0 * PI) + 0.5, p.y / height * 0.5 + 0.5));
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
    return pic(float2(atan(q.x, -q.z) / (2.0 * PI) + 0.5, acos(clamp(-q.y, -1.0, 1.0)) / PI));
}
""", x=-15, y=0, steps=64),
    solid("torus", "Torus", "Wraps the layer around a ring.",
          [number("radius", "Radius", 0.62, 0.02, 5), number("thickness", "Thickness", 0.28, 0.01, 3)], """
float bound() { return radius + thickness; }
float map(float3 p) { return length(float2(length(p.xy) - radius, p.z)) - thickness; }
float4 surface(float3 p, float3 n) {
    return pic(float2(atan(p.x, -p.y) / (2.0 * PI) + 0.5, atan(p.z, length(p.xy) - radius) / (2.0 * PI) + 0.5));
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
float bound() { return length(float2(radius, depth)); }
float map(float3 p) {
    float r = smoothing * min(radius * inner, depth) * 0.5;
    return extrude(star(p.xy, radius, radius * inner, floor(points + 0.5)), p.z, depth, r);
}
float4 surface(float3 p, float3 n) { return front(p, n, radius); }
""", steps=112),
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
""", x=-25),
    solid("spherize", "Spherize", "Wraps the layer onto a sphere using a front projection or an equirectangular texture.",
          [number("radius", "Radius", 0.9, 0.02, 5), number("coverage", "Coverage", 0.5, 0.2, 1),
           {"id": "projection", "label": "Projection", "type": "choice", "default": 0, "options": ["Front", "Equirectangular"]}], """
float bound() { return radius; }
float map(float3 p) { return length(p) - radius; }
float4 surface(float3 p, float3 n) {
    float3 q = normalize(p);
    if (projection > 0.5) {
        float longitude = length(q.xz) > 0.00001 ? atan(q.x, -q.z) : 0.0;
        return pic(float2(longitude / (2.0 * PI) + 0.5, acos(clamp(-q.y, -1.0, 1.0)) / PI));
    }
    float2 uv = 0.5 + q.xy / max(length(q.xy), 0.00001) * acos(clamp(-q.z, -1.0, 1.0)) / (PI * coverage) * 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return float4(0.0);
    return pic(uv);
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
    float3x3 m = turn();
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

FLIP_PARAMS = turns(0, 35) + [{"id": "back", "label": "Back", "type": "choice", "default": 0, "options": ["See-through", "Picture", "Color"]},
                              colour("backColor", "Back Color", "#ffffffff")] + PLACE + CAMERA + LIGHT
FLIP = uniforms(FLIP_PARAMS) + COMMON + """
half4 main(float2 coord) {
    float side = min(size.x, size.y);
    float unit = max(side * 0.5 * scale, 0.5);
    float2 h = size / side;
    float dist = max(2.0 + 40.0 * pow(1.0 - clamp(perspective, 0.0, 1.0), 3.0), length(h) + 0.5);
    float3 rw = normalize(float3((coord - size * 0.5 - position) / unit, dist));
    float3x3 m = turn();
    float3 ro = float3(0.0, 0.0, -dist) * m;
    float3 rd = rw * m;
    if (abs(rd.z) < 0.000001) return half4(0.0);
    float t = -ro.z / rd.z;
    if (t <= 0.0) return half4(0.0);
    float2 p = (ro + rd * t).xy;
    float px = unit * dist / (t * rw.z);
    float edge = clamp(min(h.x - abs(p.x), h.y - abs(p.y)) * px + 0.5, 0.0, 1.0);
    if (edge <= 0.0) return half4(0.0);
    bool seen = rd.z > 0.0;
    float2 uv = p / h * 0.5 + 0.5;
    float4 c = pic(uv);
    if (!seen && back > 1.5) c = float4(backColor.rgb * backColor.a, backColor.a);
    else if (!seen && back > 0.5) c = pic(float2(1.0 - uv.x, uv.y));
    return half4(shade(c, normalize(m * float3(0.0, 0.0, seen ? -1.0 : 1.0)), -rw) * edge);
}
"""

TUNNEL_PARAMS = turns(0, 0) + [number("phase", "Phase", 0, -1000, 1000), number("speed", "Speed", 1, -20, 20), number("twist", "Twist", 0, -360, 360, 0.1),
                               number("repeat", "Repeat", 3, 1, 16, 1), number("fog", "Fog", 0.4, 0, 1), colour("fogColor", "Fog Color", "#ff000000")] + PLACE
TUNNEL = uniforms(TUNNEL_PARAMS) + COMMON.split("float4 shade")[0] + """
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

ORDER = ["cube", "box", "hollowbox", "cylinder", "ellipsoid", "torus", "pyramid", "octahedron", "hexprism", "starprism",
         "starpolyhedron", "heart", "cross", "rasterextrude", "flip", "spherize", "tunnel"]


def main():
    found = {key: (name, description, params, sksl) for key, name, description, params, sksl in EFFECTS}
    assert sorted(found) == sorted(ORDER)
    for key in ORDER:
        name, description, params, sksl = found[key]
        effect = {"id": key, "name": name, "category": "3d", "since": SINCE, "description": description, "params": params, "sksl": sksl}
        text = json.dumps(effect, indent=1, ensure_ascii=False) + "\n"
        path = PACK / f"{key}.json"
        if "--check" in sys.argv:
            if not path.exists() or path.read_text() != text:
                sys.exit(f"{path.name} is out of date: run python3 tools/gen_3d_effects.py")
        else:
            path.write_text(text)
    index = json.loads((PACK / "index.json").read_text())
    missing = [k for k in ORDER if k not in index]
    if missing and "--check" in sys.argv:
        sys.exit("index.json misses " + " ".join(missing))
    if missing:
        (PACK / "index.json").write_text(json.dumps(index + missing, indent=1) + "\n")


if __name__ == "__main__":
    main()
