#!/usr/bin/env python3
import json
import math
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/render_bench"))
from run import effect_xml
LIBRARY = ROOT / "third_party/skia/out/mac-v2-2-arm64/libskia.a"
SIZE = 320
FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append(name)
        print(f"FAIL {name} {detail}")


def power(falloff):
    a = min(max(falloff if math.isfinite(falloff) else 1.0, 0.1), 4.0)
    return 0.5 + 0.5 / (1 + a)


def solve(pins, falloff):
    groups = []
    for x, y, rx, ry in pins[:32]:
        for g in groups:
            if (x - g[0]) ** 2 + (y - g[1]) ** 2 < 0.25:
                break
        else:
            g = [x, y, 0.0, 0.0, 0.0, 0.0, 0]
            groups.append(g)
        g[2] += x
        g[3] += y
        g[4] += rx
        g[5] += ry
        g[6] += 1
    h = power(falloff)
    if not groups:
        return np.zeros(2), np.zeros((0, 2)), np.zeros((0, 2)), np.array([1.0, 0, 0, 0]), h
    p = np.array([[g[2] / g[6], g[3] / g[6]] for g in groups])
    q = np.array([[g[4] / g[6], g[5] / g[6]] for g in groups])
    centre = p.mean(0)
    p, q = p - centre, q - centre
    n = len(p)
    if n == 1:
        t = (q - p).mean(0)
        return centre, np.zeros((0, 2)), np.zeros((0, 2)), np.array([1.0, 0, t[0], t[1]]), h
    m = 2 * n + 4
    a = np.zeros((m, m))
    b = np.zeros(m)
    k = (((p[:, None] - p[None]) ** 2).sum(-1)) ** h
    for i in range(n):
        a[2 * i, 0:2 * n:2] = k[i]
        a[2 * i + 1, 1:2 * n:2] = k[i]
        for r, row in ((2 * i, [p[i, 0], -p[i, 1], 1, 0]), (2 * i + 1, [p[i, 1], p[i, 0], 0, 1])):
            a[r, 2 * n:] = row
            a[2 * n:, r] = row
        b[2 * i], b[2 * i + 1] = q[i]
    x = np.linalg.solve(a, b)
    return centre, p, x[:2 * n].reshape(n, 2), x[2 * n:], h


def source(bend, x, y):
    centre, p, c, s, h = bend
    x = np.asarray(x, float) - centre[0]
    y = np.asarray(y, float) - centre[1]
    sx = s[0] * x - s[1] * y + s[2]
    sy = s[1] * x + s[0] * y + s[3]
    for (px, py), (cx, cy) in zip(p, c):
        r = ((px - x) ** 2 + (py - y) ** 2) ** h
        sx = sx + cx * r
        sy = sy + cy * r
    return sx + centre[0], sy + centre[1]


class Place:
    def __init__(self, cx, cy, angle=0.0, scale=1.0):
        self.cx, self.cy, self.angle, self.scale = cx, cy, angle, scale

    def to_scene(self, x, y):
        a = math.radians(self.angle)
        x, y = np.asarray(x, float) * self.scale, np.asarray(y, float) * self.scale
        return self.cx + x * math.cos(a) - y * math.sin(a), self.cy + x * math.sin(a) + y * math.cos(a)

    def to_local(self, x, y):
        a = math.radians(-self.angle)
        x, y = np.asarray(x, float) - self.cx, np.asarray(y, float) - self.cy
        return (x * math.cos(a) - y * math.sin(a)) / self.scale, (x * math.sin(a) + y * math.cos(a)) / self.scale


def effect(pins, falloff, children=False):
    shared = ' applyToChildren="true"' if children else ""
    out = [f'<effect id="absent.puppet" hidden="false"{shared}>',
           f'<property name="falloff" type="float" value="{falloff:.6f}"/>']
    for k, (x, y, rx, ry) in enumerate(pins, 1):
        out.append(f'<property name="rest{k}" type="vec2" value="{rx:.6f},{ry:.6f}"/>')
        out.append(f'<property name="pin{k}" type="vec2" value="{x:.6f},{y:.6f}"/>')
    return "".join(out) + "</effect>"


def shape(id, place, half, fx="", extra="", fill='fillType="media" fillImage="synthetic" mediaFillMode="fill"', inner=""):
    return (f'<shape id="{id}" label="l{id}" startTime="0" endTime="1000" {fill} s=".rect"{extra}><transform>'
            f'<location value="{place.cx},{place.cy},0"/><rotation value="{place.angle:.6f}"/><scale value="{place.scale:.6f},{place.scale:.6f}"/>'
            f'</transform><property name="size" type="vec2" value="{half[0]},{half[1]}"/>{inner}{fx}</shape>')


def scene(*layers, size=SIZE):
    return (f'<?xml version="1.0" encoding="UTF-8"?><scene title="t" width="{size[0] if isinstance(size, tuple) else size}" '
            f'height="{size[1] if isinstance(size, tuple) else size}" bgcolor="#00000000" totalTime="1000" fps="30">' + "".join(layers) + "</scene>")


class Bench:
    def __init__(self, binary, work, picture):
        self.binary, self.work, self.picture, self.count = binary, Path(work), picture, 0
        (self.work / "pic.rgba").write_bytes(np.ascontiguousarray(picture, dtype=np.uint8).tobytes())
        (self.work / "effects.txt").write_text('<effect id="absent.puppet"><param name="falloff" value="1.000000"/></effect>\n' + effect_xml("glow") + "\n")

    def render(self, xml, runs=0, time=0):
        self.count += 1
        d = self.work / f"r{self.count}"
        d.mkdir()
        (d / "scene.xml").write_text(xml)
        h, w = self.picture.shape[:2]
        env = dict(os.environ, RENDER_BENCH_PICTURE=str(self.work / "pic.rgba"), RENDER_BENCH_PICTURE_WIDTH=str(w), RENDER_BENCH_PICTURE_HEIGHT=str(h))
        r = subprocess.run([str(self.binary), str(self.work / "effects.txt"), str(d / "scene.xml"), "1", str(runs), str(d), str(time)],
                           capture_output=True, text=True, env=env)
        if r.returncode != 0 or "effect:" in r.stderr or "scene:" in r.stderr:
            raise RuntimeError(r.stderr)
        lines = [json.loads(line) for line in r.stdout.strip().splitlines()]
        last = lines[-1]
        image = np.fromfile(d / "frame0.rgba", dtype=np.uint8).reshape(last["height"], last["width"], 4).astype(float)
        return image, min(run["ms"][0] for run in lines)


def bilinear(image, x, y):
    h, w = image.shape[:2]
    x, y = x - 0.5, y - 0.5
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = (x - x0)[..., None], (y - y0)[..., None]
    padded = np.zeros((h + 4, w + 4, 4))
    padded[2:-2, 2:-2] = image

    def at(yy, xx):
        return padded[np.clip(yy + 2, 0, h + 3), np.clip(xx + 2, 0, w + 3)]

    return (at(y0, x0) * (1 - fx) * (1 - fy) + at(y0, x0 + 1) * fx * (1 - fy) + at(y0 + 1, x0) * (1 - fx) * fy + at(y0 + 1, x0 + 1) * fx * fy)


def expected(plain, place, pins, falloff, owner=None):
    owner = owner or place
    yy, xx = np.mgrid[0:plain.shape[0], 0:plain.shape[1]].astype(float) + 0.5
    lx, ly = owner.to_local(xx, yy)
    sx, sy = source(solve(pins, falloff), lx, ly)
    gx, gy = owner.to_scene(sx, sy)
    return bilinear(plain, gx, gy)


def compare(name, got, want, mean_limit=1.5, edge_limit=24.0):
    diff = np.abs(got - want).max(-1)
    mean, edge = float(diff.mean()), float(np.percentile(diff, 99.5))
    check(name, mean < mean_limit and edge < edge_limit, f"mean {mean:.3f} p99.5 {edge:.1f}")


def picture(size=256):
    yy, xx = np.mgrid[0:size, 0:size].astype(float)
    image = np.zeros((size, size, 4))
    image[..., 0] = 128 + 110 * np.sin(xx / 13.0)
    image[..., 1] = 128 + 110 * np.cos(yy / 17.0)
    image[..., 2] = 128 + 110 * np.sin((xx + yy) / 23.0)
    image[..., 3] = 255
    return image


def marks(points, size=256):
    yy, xx = np.mgrid[0:size, 0:size].astype(float) + 0.5
    image = np.zeros((size, size, 4))
    image[..., 3] = 255
    for x, y in points:
        px, py = (x + 100) * size / 200, (y + 100) * size / 200
        image[..., 0] = np.maximum(image[..., 0], 255 * np.clip(5.5 - np.hypot(xx - px, yy - py), 0, 1))
    return image


def run(bench, dots):
    place = Place(160, 160)
    half = (100, 100)
    plain, _ = bench.render(scene(shape(1, place, half)))
    arm = [(-90, -90, -90, -90), (90, -90, 90, -90), (-90, 90, -90, 90), (0, 0, 0, 0), (90, 30, 90, 90)]
    for falloff in (0.1, 1.0, 4.0):
        got, _ = bench.render(scene(shape(1, place, half, effect(arm, falloff))))
        compare(f"the engine draws the same warp as the reference maths at falloff {falloff}", got, expected(plain, place, arm, falloff))
        marked, _ = dots.render(scene(shape(1, place, half, effect(arm, falloff))))
        for x, y, rx, ry in arm:
            gx, gy = place.to_scene(x, y)
            yy, xx = np.mgrid[int(gy) - 10:int(gy) + 11, int(gx) - 10:int(gx) + 11]
            light = marked[int(gy) - 10:int(gy) + 11, int(gx) - 10:int(gx) + 11, 0]
            mx, my = (light * (xx + 0.5)).sum() / light.sum(), (light * (yy + 0.5)).sum() / light.sum()
            what = "an unmoved pin holds the picture under it" if (x, y) == (rx, ry) else "the picture sits under a dragged pin"
            check(f"{what} at falloff {falloff}", math.hypot(mx - gx, my - gy) < 0.75, f"{math.hypot(mx - gx, my - gy):.2f} px at {x},{y}")

    sway_props = ('<property name="sway" type="switch" value="true"/>'
                  '<property name="swayAmount" type="float" value="25"/>'
                  '<property name="swaySpeed" type="float" value="1"/>'
                  '<property name="swayDirection" type="float" value="1"/>'
                  '<property name="swaySeed" type="float" value="2"/>')
    base_fx = effect(arm, 1.0)
    sway_fx = base_fx.replace('</effect>', sway_props + '</effect>')
    sway_scene = scene(shape(1, place, half, sway_fx))
    frame0, _ = bench.render(sway_scene, time=0)
    frame1, _ = bench.render(sway_scene, time=250)
    repeat, _ = bench.render(sway_scene, time=250)
    check("sway changes the image over time", float(np.abs(frame0 - frame1).mean()) > 1)
    check("sway repeats exactly at the same time", bool(np.array_equal(frame1, repeat)))
    selected = sway_fx.replace('</effect>', '<property name="swayPins" type="float" value="1"/></effect>')
    no_selected, _ = bench.render(scene(shape(1, place, half, selected)), time=250)
    legacy, _ = bench.render(scene(shape(1, place, half, base_fx)), time=250)
    check("selected sway leaves unchecked pins still", bool(np.array_equal(no_selected, legacy)))
    selected = selected.replace('</effect>', '<property name="swayPin5" type="float" value="1"/></effect>')
    one_selected, _ = bench.render(scene(shape(1, place, half, selected)), time=250)
    check("selected sway moves a checked pin", float(np.abs(one_selected - legacy).mean()) > 0.1)

    def props(fx, values):
        return fx.replace('</effect>', ''.join(f'<property name="{name}" type="float" value="{value}"/>' for name, value in values.items()) + '</effect>')

    strip = [(-90, 0, -90, 0), (20, -60, 90, 0)]
    strip_fx = effect(strip, 1.0)
    legacy_strip, _ = bench.render(scene(shape(1, place, (100, 40), strip_fx)))
    explicit_legacy, _ = bench.render(scene(shape(1, place, (100, 40), props(strip_fx, {"density": 0, "expansion": 0}))))
    check("legacy mesh settings preserve the old warp exactly", bool(np.array_equal(legacy_strip, explicit_legacy)))
    dense_fx = props(strip_fx, {"density": 32})
    dense, _ = bench.render(scene(shape(1, place, (100, 40), dense_fx)))
    coarse, _ = bench.render(scene(shape(1, place, (100, 40), props(strip_fx, {"density": 8}))))
    expanded, _ = bench.render(scene(shape(1, place, (100, 40), props(strip_fx, {"density": 32, "expansion": 80}))))
    check("a mesh curls a two-pin strip differently from the legacy rigid transform", float(np.abs(dense - legacy_strip).mean()) > 1)
    check("triangle density changes the interpolated mesh", float(np.abs(dense - coarse).mean()) > 0.05)
    check("mesh expansion changes the interpolated mesh", float(np.abs(dense - expanded).mean()) > 0.05)
    clamped, _ = bench.render(scene(shape(1, place, (100, 40), props(strip_fx, {"density": 1000, "expansion": 1000}))))
    maximum, _ = bench.render(scene(shape(1, place, (100, 40), props(strip_fx, {"density": 48, "expansion": 500}))))
    check("mesh settings are capped", bool(np.array_equal(clamped, maximum)))
    held_fx = props(effect([(40, 10, 0, 0), (80, -30, 80, 0)], 1), {"density": 32, "pinMode1": 2})
    rest_fx = props(effect([(0, 0, 0, 0), (80, -30, 80, 0)], 1), {"density": 32, "pinMode1": 2})
    held, _ = bench.render(scene(shape(1, place, half, held_fx)))
    rest, _ = bench.render(scene(shape(1, place, half, rest_fx)))
    check("a stiff pin stays at its rest position", bool(np.array_equal(held, rest)))
    bend_fx = props(effect([(0, 0, 0, 0), (80, 0, 80, 0)], 1), {"density": 32, "pinMode2": 1, "pinRotation2": 60})
    bend, _ = bench.render(scene(shape(1, place, half, bend_fx)))
    check("a bend pin rotates its area while its centre is still", float(np.abs(bend - plain).mean()) > 1)
    scaled_fx = props(effect([(0, 0, 0, 0), (80, 0, 80, 0)], 1), {"density": 32, "pinMode2": 1, "pinScale2": 1.5})
    scaled, _ = bench.render(scene(shape(1, place, half, scaled_fx)))
    check("a bend pin scales its area while its centre is still", float(np.abs(scaled - plain).mean()) > 1)
    rotated_one = props(effect([(0, 0, 0, 0)], 1), {"density": 48, "pinMode1": 1, "pinRotation1": 90})
    rotated_image, _ = bench.render(scene(shape(1, place, (100, 40), rotated_one)))
    rotated_reference, _ = bench.render(scene(shape(1, Place(160, 160, 90), (100, 40))))
    compare("a bend rotation draws the whole image beyond its original bounds", rotated_image, rotated_reference, mean_limit=1.5, edge_limit=30)
    scaled_one = props(effect([(0, 0, 0, 0)], 1), {"density": 48, "pinMode1": 1, "pinScale1": 1.5})
    scaled_image, _ = bench.render(scene(shape(1, place, (100, 40), scaled_one)))
    scaled_reference, _ = bench.render(scene(shape(1, Place(160, 160, 0, 1.5), (100, 40))))
    compare("a bend scale draws the whole image beyond its original bounds", scaled_image, scaled_reference, mean_limit=1.5, edge_limit=55)
    animated = props(effect([(0, 0, 0, 0), (80, 0, 80, 0)], 1), {"density": 32, "pinMode2": 1}).replace(
        '</effect>', '<property name="pinRotation2" type="float"><kf t="0" v="0"/><kf t="1" v="60"/></property>'
                     '<property name="pinScale2" type="float"><kf t="0" v="1"/><kf t="1" v="1.5"/></property></effect>')
    a0, _ = bench.render(scene(shape(1, place, half, animated)), time=0)
    a1, _ = bench.render(scene(shape(1, place, half, animated)), time=500)
    check("bend rotation and scale animate", float(np.abs(a0 - a1).mean()) > 1)

    pin_work = bench.work / "mesh_marks"
    pin_work.mkdir()
    pin_bench = Bench(bench.binary, pin_work, marks([(-90, 0), (90, 0)]))
    marked, _ = pin_bench.render(scene(shape(1, place, (100, 40), props(strip_fx, {"density": 48}))))
    for x, y in ((70, 160), (180, 100)):
        light = marked[y - 10:y + 11, x - 10:x + 11, 0]
        yy, xx = np.mgrid[y - 10:y + 11, x - 10:x + 11]
        mass = light.sum()
        distance = math.hypot((light * (xx + 0.5)).sum() / max(mass, 1) - x, (light * (yy + 0.5)).sum() / max(mass, 1) - y)
        check("the mesh keeps picture marks under position pins", mass > 1000 and distance < 1, f"{distance:.2f} px")

    swaying = props(effect([(-90, 0, -90, 0), (90, 0, 90, 0)], 1),
                    {"density": 48, "sway": 1, "swayAmount": 25, "swaySpeed": 1, "swayDirection": 1,
                     "swaySeed": 2, "swayPins": 1, "swayPin1": 1})
    marked, _ = pin_bench.render(scene(shape(1, place, (100, 40), swaying)), time=250)
    x, y = 70 + 6.7639, 160 + 3.6464
    ix, iy = round(x), round(y)
    light = marked[iy - 10:iy + 11, ix - 10:ix + 11, 0]
    yy, xx = np.mgrid[iy - 10:iy + 11, ix - 10:ix + 11]
    mass = light.sum()
    distance = math.hypot((light * (xx + 0.5)).sum() / max(mass, 1) - x, (light * (yy + 0.5)).sum() / max(mass, 1) - y)
    check("swayed mesh matches the editor handle position", mass > 1000 and distance < 1, f"{distance:.2f} px")

    mesh_fx = props(effect(arm, 1.0), {"density": 32})
    normal_mesh, _ = bench.render(scene(shape(1, place, half, mesh_fx)))
    mesh_place = Place(170, 150, 30, 0.6)
    turned_mesh, _ = bench.render(scene(shape(1, mesh_place, half, mesh_fx)))
    yy, xx = np.mgrid[0:SIZE, 0:SIZE].astype(float) + 0.5
    lx, ly = mesh_place.to_local(xx, yy)
    px, py = place.to_scene(lx, ly)
    compare("mesh pins stay attached to a turned and scaled layer", turned_mesh, bilinear(normal_mesh, px, py), mean_limit=1.5, edge_limit=26)

    turned = Place(170, 150, 30, 0.6)
    turned_plain, _ = bench.render(scene(shape(1, turned, half)))
    got, _ = bench.render(scene(shape(1, turned, half, effect(arm, 1.0))))
    compare("pins stay attached to a turned and scaled layer", got, expected(turned_plain, turned, arm, 1.0))

    one = [(0, 0, 25, -10)]
    got, _ = bench.render(scene(shape(1, place, half, effect(one, 1.0))))
    compare("one moved pin moves the whole picture", got, expected(plain, place, one, 1.0))

    odd = ''.join(f'<property name="{n}" type="vec2" value="{v}"/>' for n, v in (
        ("rest0", "1,1"), ("pin0", "25,25"), ("rest01", "1,1"), ("pin01", "25,25"), ("rest1", "1,1"), ("pin1", "nan,5"), ("rest2", "1,1"),
        ("pin2", "1e40,5"), ("rest3", "2,2"), ("pin3", "7"), ("rest4", "0,0"), ("pin4", "12 , -8")))
    odd_fx = f'<effect id="absent.puppet" hidden="false"><property name="falloff" type="float" value="nan"/>{odd}</effect>'
    got, _ = bench.render(scene(shape(1, place, half, odd_fx)))
    same, _ = bench.render(scene(shape(1, place, half, effect([(12, -8, 0, 0)], 1.0))))
    check("odd pin names and values are skipped the way the app skips them", float(np.abs(got - same).max()) == 0)

    before, _ = bench.render(scene(shape(1, place, half, effect(arm, 1.0))))
    sx, sy = source(solve(arm, 1.0), 40.0, -30.0)
    added = arm + [(40.0, -30.0, float(sx), float(sy))]
    after, _ = bench.render(scene(shape(1, place, half, effect(added, 1.0))))
    worst = float(np.abs(after - before).max())
    check("adding a pin to a bent layer leaves the whole picture where it is", worst <= 2, f"{worst:.1f}")

    small = Place(110, 120)
    small_half = (30, 30)
    far = [(-25, -25, -25, -25), (25, -25, 25, -25), (0, 25, 0, -5), (25, 25, 25, 25)]
    far[2] = (120, 140, 0, 25)
    stroke = '<path-stroke><color value="#ffffffff"/><size value="18"/></path-stroke>'
    small_plain, _ = bench.render(scene(shape(1, small, small_half, inner=stroke)))
    got, _ = bench.render(scene(shape(1, small, small_half, effect(far, 1.0), inner=stroke)))
    compare("a small layer bent far outside its box, stroke included, is drawn whole", got, expected(small_plain, small, far, 1.0))

    pulled, _ = bench.render(scene(shape(1, Place(160, 300), half, effect([(0, -50, 0, 50)], 1.0))))
    lifted, _ = bench.render(scene(shape(1, Place(160, 200), half)))
    compare("a pin pulls the part of the picture below the canvas into view", pulled, lifted)
    soft = '<effect id="absent.blur"><property name="strength" type="float" value="0.5"/></effect>'
    pulled, _ = bench.render(scene(shape(1, Place(160, 300), half, soft + effect([(0, -50, 0, 50)], 1.0))))
    lifted, _ = bench.render(scene(shape(1, Place(160, 200), half, soft)))
    compare("an effect before the pin keeps the part below the canvas", pulled, lifted)
    glow = '<effect id="absent.glow"><property name="radius" type="float" value="20"/></effect>'
    for name, before, after in (("a glow after the pin", "", glow), ("a glow before the pin", glow, "")):
        pulled, _ = bench.render(scene(shape(1, Place(160, 300), half, before + effect([(0, -50, 0, 50)], 1.0) + after)))
        lifted, _ = bench.render(scene(shape(1, Place(160, 200), half, glow)))
        compare(f"{name} keeps the part below the canvas", pulled, lifted)

    tiny = Place(160, 160, 0, 0.01)
    tiny_pins = [(-9000, -9000, -9000, -9000), (9000, 9000, 9000, 9000), (0, 0, 2000, -3000)]
    tiny_plain, _ = bench.render(scene(shape(1, tiny, (10000, 10000))))
    got, _ = bench.render(scene(shape(1, tiny, (10000, 10000), effect(tiny_pins, 4.0))))
    check("a layer at scale 0.01 with falloff 4 draws no garbage", bool(np.isfinite(got).all()) and float(np.abs(got - expected(tiny_plain, tiny, tiny_pins, 4.0)).max(-1).mean()) < 1.5)

    parent = Place(160, 160)
    child = Place(200, 130)
    clear = 'fillType="color"'
    kid_plain, _ = bench.render(scene(shape(1, parent, half, fill=clear, inner='<fillColor value="#00000000"/>'),
                                      shape(2, Place(40, -30), (50, 40), extra=' parent="1"')))
    got, _ = bench.render(scene(shape(1, parent, half, effect(arm, 1.0, children=True), fill=clear, inner='<fillColor value="#00000000"/>'),
                                shape(2, Place(40, -30), (50, 40), extra=' parent="1"')))
    compare("Apply to children bends a child with the parent's pins where they are drawn", got, expected(kid_plain, child, arm, 1.0, owner=parent))

    big = (1080, 1920)
    pins_small = [(-20, -20, -20, -20), (20, 20, 25, 10)]
    _, small_ms = bench.render(scene(shape(1, Place(200, 300), (30, 30), effect(pins_small, 1.0)), size=big), runs=3)
    _, full_ms = bench.render(scene(shape(1, Place(540, 960), (540, 960), effect([(-500, -900, -500, -900), (500, 900, 520, 880)], 1.0)), size=big), runs=3)
    check("a small bent layer costs far less than a full-frame one", small_ms < full_ms * 0.2, f"{small_ms:.1f} ms vs {full_ms:.1f} ms")
    _, small_mesh_ms = bench.render(scene(shape(1, Place(200, 300), (30, 30), props(effect(pins_small, 1.0), {"density": 24})), size=big), runs=3)
    _, full_mesh_ms = bench.render(scene(shape(1, Place(540, 960), (540, 960), props(effect([(-500, -900, -500, -900), (500, 900, 520, 880)], 1.0), {"density": 24})), size=big), runs=3)
    check("a small meshed layer avoids full-frame work", small_mesh_ms < full_mesh_ms * 0.2, f"{small_mesh_ms:.1f} ms vs {full_mesh_ms:.1f} ms")


def main():
    if not LIBRARY.exists() or sys.platform != "darwin":
        print("puppet_check: skipped (needs the host Skia build in third_party/skia/out/mac-v2-2-arm64)")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        build = subprocess.run(["sh", str(ROOT / "tools/render_bench/build.sh"), str(ROOT / "shared/cpp"), tmp + "/bin"], capture_output=True, text=True)
        if build.returncode != 0:
            print(build.stdout[-2000:], build.stderr[-2000:])
            return 1
        work, marked = Path(tmp) / "work", Path(tmp) / "marked"
        work.mkdir()
        marked.mkdir()
        arm_rests = [(-90, -90), (90, -90), (-90, 90), (0, 0), (90, 90)]
        run(Bench(Path(tmp) / "bin/render_bench", work, picture()), Bench(Path(tmp) / "bin/render_bench", marked, marks(arm_rests)))
    print(f"puppet_check: {CHECKS[0]} checks, {len(FAILURES)} failed")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
