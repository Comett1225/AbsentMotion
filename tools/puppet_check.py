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
        (self.work / "effects.txt").write_text('<effect id="absent.puppet"><param name="falloff" value="1.000000"/></effect>\n')

    def render(self, xml, runs=0):
        self.count += 1
        d = self.work / f"r{self.count}"
        d.mkdir()
        (d / "scene.xml").write_text(xml)
        h, w = self.picture.shape[:2]
        env = dict(os.environ, RENDER_BENCH_PICTURE=str(self.work / "pic.rgba"), RENDER_BENCH_PICTURE_WIDTH=str(w), RENDER_BENCH_PICTURE_HEIGHT=str(h))
        r = subprocess.run([str(self.binary), str(self.work / "effects.txt"), str(d / "scene.xml"), "1", str(runs), str(d), "0"],
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


def main():
    if not LIBRARY.exists() or sys.platform != "darwin":
        print("puppet_check: skipped (needs the host Skia build in third_party/skia/out/mac-v2-2-arm64)")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        build = subprocess.run(["sh", str(ROOT / "tools/render_bench/build.sh"), str(ROOT / "app/src/main/cpp"), tmp + "/bin"], capture_output=True, text=True)
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
