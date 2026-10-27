#!/usr/bin/env python3
import json
import math
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.sax.saxutils import quoteattr

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
EFFECT = ROOT / "app/src/main/assets/effects/rays.json"
LIBRARY = ROOT / "third_party/skia/out/mac-v2-2-arm64/libskia.a"
SIZE = 360
FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append(name)
        print(f"FAIL {name} {detail}")


def load_line(legacy):
    d = json.loads(EFFECT.read_text())
    params = []
    for p in d["params"]:
        v = p.get("legacyDefault", p["default"]) if legacy else p["default"]
        if p["type"] == "point":
            v = ",".join("%.6f" % x for x in v)
        elif p["type"] == "color":
            v = v.lower() if len(v) == 9 else "#ff" + v[1:].lower()
        else:
            v = "%.6f" % v
        params.append(f"<param name={quoteattr(p['id'])} value={quoteattr(v)}/>")
    return (f'<effect id="absent.rays" sksl={quoteattr(d["sksl"])}>' + "".join(params) + "</effect>").replace("\n", "&#10;")


def prop(name, value):
    if isinstance(value, str):
        return f'<property name="{name}" type="color" value="{value}"/>'
    if isinstance(value, (list, tuple)):
        return f'<property name="{name}" type="vec2" value="{value[0]:.6f},{value[1]:.6f}"/>'
    return f'<property name="{name}" type="float" value="{value:.6f}"/>'


def scene_xml(props, size, layer=None):
    cx, cy, hx, hy = layer or (size / 2, size / 2, size / 2, size / 2)
    effect = "" if props is None else '<effect id="absent.rays">' + "".join(prop(k, v) for k, v in props.items()) + "</effect>"
    return (f'<?xml version="1.0" encoding="UTF-8"?><scene title="t" width="{size}" height="{size}" bgcolor="#00000000" totalTime="1000" fps="30">'
            f'<shape id="1" label="l" startTime="0" endTime="1000" fillType="media" fillImage="synthetic" mediaFillMode="fill" s=".rect">'
            f'<transform><location value="{cx},{cy},0"/></transform><property name="size" type="vec2" value="{hx},{hy}"/>{effect}</shape></scene>')


class Bench:
    def __init__(self, binary, work):
        self.binary = binary
        self.work = Path(work)
        self.count = 0

    def render(self, picture, props, scale=1.0, legacy=False, layer=None):
        self.count += 1
        d = self.work / f"r{self.count}"
        d.mkdir()
        size = picture.shape[0]
        (d / "effects.txt").write_text(load_line(legacy) + "\n")
        (d / "scene.xml").write_text(scene_xml(props, size, layer))
        (d / "pic.rgba").write_bytes(np.ascontiguousarray(picture, dtype=np.uint8).tobytes())
        env = dict(os.environ, RENDER_BENCH_PICTURE=str(d / "pic.rgba"), RENDER_BENCH_PICTURE_WIDTH=str(size), RENDER_BENCH_PICTURE_HEIGHT=str(size))
        r = subprocess.run([str(self.binary), str(d / "effects.txt"), str(d / "scene.xml"), str(scale), "0", str(d), "0"], capture_output=True, text=True, env=env)
        if r.returncode != 0 or "effect:" in r.stderr or "scene:" in r.stderr:
            raise RuntimeError(r.stderr)
        last = json.loads(r.stdout.strip().splitlines()[-1])
        return np.fromfile(d / "frame0.rgba", dtype=np.uint8).reshape(last["height"], last["width"], 4).astype(float)


def blank(size=SIZE):
    return np.zeros((size, size, 4), float)


def dot(picture, x, y, radius, colour, alpha=1.0):
    h, w = picture.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    cover = np.clip(radius + 0.5 - np.hypot(xx + 0.5 - x, yy + 0.5 - y), 0, 1) * alpha
    for k in range(3):
        picture[..., k] = np.maximum(picture[..., k], colour[k] * cover)
    picture[..., 3] = np.maximum(picture[..., 3], 255 * cover)
    return picture


def mass(a, base=None):
    delta = a[..., 3] - (0 if base is None else base[..., 3])
    return float(np.clip(delta, 0, None).sum())


def unpremultiplied_mean(a, mask):
    alpha = a[..., 3][mask]
    if alpha.size == 0:
        return np.full(3, np.nan)
    return (a[..., :3][mask] / np.maximum(alpha, 1)[:, None]).mean(0)


def far_mask(size, centre, spot, radius, margin):
    yy, xx = np.mgrid[0:size, 0:size]
    return np.hypot(xx + 0.5 - spot[0], yy + 0.5 - spot[1]) > radius + margin


def run(bench):
    spot = (270.0, 110.0)
    centre = (SIZE / 2, SIZE / 2)
    radius = 6
    white = dot(blank(), spot[0], spot[1], radius, (255, 255, 255))
    yellow = dot(blank(), spot[0], spot[1], radius, (255, 200, 60))
    base_white = bench.render(white, None)

    for name, props in (("intensity zero", {"intensity": 0.0}), ("length zero", {"reach": 0.0}), ("negative length", {"reach": -5.0}),
                        ("threshold above content", {"threshold": 1.0, "thresholdAdd": "#ff333333"})):
        grey = dot(blank(), spot[0], spot[1], radius, (200, 200, 200))
        same = bench.render(grey, props)
        plain = bench.render(grey, None)
        check(f"identity: {name}", np.abs(same - plain).max() == 0, str(np.abs(same - plain).max()))

    sweep = [{"center": c, "reach": r, "intensity": i, "threshold": t, "blend": b}
             for c in ([0.5, 0.5], [-8.0, -8.0], [9.0, 9.0], [1e6, -1e6], [0.0, 0.0])
             for r, i, t, b in ((0.001, 1.0, 0.6, 0.0), (0.15, 5.0, 0.0, 1.0), (4.0, 100.0, 0.6, 0.5), (1000.0, 5.0, 1.0, 0.3), (1e9, 3.0, 0.2, 0.0))]
    mixed = dot(dot(dot(blank(), spot[0], spot[1], radius, (255, 255, 255)), 80, 250, 20, (255, 210, 80), 0.6), 200, 300, 3, (90, 220, 255))
    mixed[0:40, 0:40] = (255, 255, 255, 255)
    reference = bench.render(mixed, None)
    worst_excess = 0.0
    lowest_alpha = 0.0
    for props in sweep:
        a = bench.render(mixed, props)
        worst_excess = max(worst_excess, float((a[..., :3] - a[..., 3:4]).max()))
        lowest_alpha = min(lowest_alpha, float((a[..., 3] - reference[..., 3]).min()))
    check("premultiplied colour never exceeds alpha across the parameter sweep", worst_excess <= 1.0, f"{worst_excess}")
    check("alpha never drops below the layer's own alpha across the sweep", lowest_alpha >= -1.0, f"{lowest_alpha}")

    plain = bench.render(white, {"reach": 0.5, "intensity": 2.0})
    angle = math.atan2(spot[1] - centre[1], spot[0] - centre[0])
    yy, xx = np.mgrid[0:SIZE, 0:SIZE]
    px, py = xx + 0.5 - centre[0], yy + 0.5 - centre[1]
    rp = np.hypot(px, py)
    rd = math.hypot(spot[0] - centre[0], spot[1] - centre[1])
    behind = rp < rd - radius - 3
    check("no light appears nearer to the centre than the bright spot", plain[..., 3][behind].max() == 0, f"{plain[..., 3][behind].max()}")
    wrapped = np.abs((np.arctan2(py, px) - angle + math.pi) % (2 * math.pi) - math.pi)
    outside_cone = wrapped > (radius + 3) / np.minimum(np.maximum(rp, 1), rd)
    check("no light appears outside the cone behind the bright spot", plain[..., 3][outside_cone & (rp > 4)].max() == 0, f"{plain[..., 3][outside_cone & (rp > 4)].max()}")
    beyond = far_mask(SIZE, centre, spot, radius, 8) & (rp > rd)
    check("light streaks away from the centre past the spot", plain[..., 3][beyond].max() > 8)

    for centre_value in ([0.25, 0.7], [0.5, 0.5], [0.8, 0.2]):
        c = (centre_value[0] * SIZE, centre_value[1] * SIZE)
        a = bench.render(white, {"center": centre_value, "reach": 0.6, "intensity": 2.0})
        added = np.clip(a[..., 3] - base_white[..., 3], 0, None)
        keep = far_mask(SIZE, c, spot, radius, 8) & (np.hypot(xx + 0.5 - c[0], yy + 0.5 - c[1]) > math.hypot(spot[0] - c[0], spot[1] - c[1]))
        weight = added * keep
        if weight.sum() <= 0:
            check(f"streak direction for centre {centre_value}", False, "no light")
            continue
        mx, my = (weight * (xx + 0.5)).sum() / weight.sum() - spot[0], (weight * (yy + 0.5)).sum() / weight.sum() - spot[1]
        expected = math.atan2(spot[1] - c[1], spot[0] - c[0])
        got = math.atan2(my, mx)
        off = abs((got - expected + math.pi) % (2 * math.pi) - math.pi)
        check(f"streak direction follows the centre {centre_value}", math.degrees(off) < 4, f"{math.degrees(off):.2f} degrees")

    placed = (240.0, 120.0, 90.0, 90.0)
    offset_picture = dot(blank(), 200, 300, 8, (255, 255, 255))
    offset_base = bench.render(offset_picture, None, layer=placed)
    offset_out = bench.render(offset_picture, {"reach": 0.5, "intensity": 2.0}, layer=placed)
    spot_on_canvas = (placed[0] - placed[2] + 200 * placed[2] / (SIZE / 2), placed[1] - placed[3] + 300 * placed[3] / (SIZE / 2))
    added = np.clip(offset_out[..., 3] - offset_base[..., 3], 0, None)
    keep = (np.hypot(xx + 0.5 - spot_on_canvas[0], yy + 0.5 - spot_on_canvas[1]) > 14) & (np.hypot(xx + 0.5 - placed[0], yy + 0.5 - placed[1]) > math.hypot(spot_on_canvas[0] - placed[0], spot_on_canvas[1] - placed[1]))
    weight = added * keep
    if weight.sum() <= 0:
        check("centre is measured against the layer", False, "no light")
    else:
        mx = (weight * (xx + 0.5)).sum() / weight.sum() - spot_on_canvas[0]
        my = (weight * (yy + 0.5)).sum() / weight.sum() - spot_on_canvas[1]
        expected = math.atan2(spot_on_canvas[1] - placed[1], spot_on_canvas[0] - placed[0])
        off = abs((math.atan2(my, mx) - expected + math.pi) % (2 * math.pi) - math.pi)
        check("centre is measured against the layer, not the canvas", math.degrees(off) < 4, f"{math.degrees(off):.2f} degrees")

    near = (240.0, 150.0)
    near_spot = dot(blank(), near[0], near[1], radius, (255, 255, 255))
    near_base = bench.render(near_spot, None)
    near_distance = math.hypot(near[0] - centre[0], near[1] - centre[1])
    masses = [mass(bench.render(near_spot, {"reach": r}), near_base) for r in (0.0, 0.01, 0.05, 0.15)]
    check("longer rays carry more light, none at zero length", masses[0] == 0 and all(masses[i] < masses[i + 1] for i in range(len(masses) - 1)), str(masses))
    extents = []
    for r in (0.02, 0.05, 0.15):
        a = bench.render(near_spot, {"reach": r})
        lit = (a[..., 3] - near_base[..., 3] > 3) & (rp > near_distance)
        extents.append(float(rp[lit].max() - near_distance) if lit.any() else 0.0)
    check("ray extent grows with length", extents[0] < extents[1] < extents[2], str(extents))

    dim = dot(blank(), spot[0], spot[1], radius, (190, 190, 190))
    dim_base = bench.render(dim, None)
    half = mass(bench.render(dim, {"intensity": 0.2}), dim_base)
    full = mass(bench.render(dim, {"intensity": 0.4}), dim_base)
    check("intensity scales the light linearly before clipping", 0.4 < half / max(full, 1e-9) < 0.6, f"{half / max(full, 1e-9):.3f}")
    check("intensity 0 leaves no light", mass(bench.render(dim, {"intensity": 0.0}), dim_base) == 0)

    streak = beyond & (rp < rd * 1.5)
    for blend, expectation in ((0.0, "source"), (1.0, "tint")):
        a = bench.render(yellow, {"reach": 0.4, "intensity": 2.0, "blend": blend, "color": "#ff0000ff"})
        rgb = unpremultiplied_mean(a, streak & (a[..., 3] > 12))
        if expectation == "source":
            check("blend 0 keeps the layer's own colours", rgb[0] > rgb[1] > rgb[2] * 2 and abs(rgb[0] - 1.0) < 0.05, str(rgb))
        else:
            check("blend 1 tints the light with Color", rgb[2] > 0.5 and rgb[0] < 0.05 and rgb[1] < 0.05, str(rgb))
    half_blend = bench.render(yellow, {"reach": 0.4, "intensity": 2.0, "blend": 0.5, "color": "#ff0000ff"})
    rgb = unpremultiplied_mean(half_blend, streak & (half_blend[..., 3] > 12))
    check("blend 0.5 is between the layer's colours and Color", 0.4 < rgb[0] < 0.6 and rgb[2] > 0.4, str(rgb))

    two = dot(dot(blank(), 270, 110, radius, (230, 230, 230)), 90, 70, radius, (110, 110, 110))
    for threshold, add, dark_expected, bright_expected in ((0.6, "#ff000000", False, True), (0.3, "#ff000000", True, True), (0.2, "#ff808080", False, True), (0.5, "#ff808080", False, False)):
        a = bench.render(two, {"threshold": threshold, "thresholdAdd": add, "reach": 0.5, "intensity": 2.0})
        extra = np.clip(a[..., 3] - bench.render(two, None)[..., 3], 0, None)
        dark_zone = (np.hypot(xx + 0.5 - 90, yy + 0.5 - 70) > 14) & (np.hypot(xx + 0.5 - 90, yy + 0.5 - 70) < 80) & (np.hypot(px, py) > math.hypot(90 - 180, 70 - 180))
        bright_zone = (np.hypot(xx + 0.5 - 270, yy + 0.5 - 110) > 14) & (np.hypot(xx + 0.5 - 270, yy + 0.5 - 110) < 80) & (rp > rd)
        check(f"threshold {threshold} add {add}: dim spot", (extra[dark_zone].sum() > 50) == dark_expected, f"{extra[dark_zone].sum():.1f}")
        check(f"threshold {threshold} add {add}: bright spot", (extra[bright_zone].sum() > 50) == bright_expected, f"{extra[bright_zone].sum():.1f}")

    solid = mass(bench.render(white, {"reach": 0.5, "intensity": 0.5}), base_white)
    faint_spot = dot(blank(), spot[0], spot[1], radius, (255, 255, 255), 0.5)
    faint_base = bench.render(faint_spot, None)
    faint_out = bench.render(faint_spot, {"reach": 0.5, "intensity": 0.5})
    faint = mass(faint_out, faint_base)
    check("translucent light is proportional to the layer's alpha", 0.35 < faint / max(solid, 1e-9) < 0.65, f"{faint / max(solid, 1e-9):.3f}")
    ray = beyond & (faint_out[..., 3] > 3)
    rgb = unpremultiplied_mean(faint_out, ray)
    check("translucent light keeps its colour once unpremultiplied", bool(np.all(rgb > 0.9)), str(rgb))

    saved = bench.render(white, {"reach": 0.4, "intensity": 2.0, "color": "#fffff2d0"}, legacy=True)
    rgb = unpremultiplied_mean(saved, beyond & (saved[..., 3] > 12))
    check("a saved effect without Blend tints with its Color", bool(np.all(np.abs(rgb - np.array([1.0, 0.949, 0.816])) < 0.08)), str(rgb))
    classic = bench.render(white, {"reach": 0.4, "intensity": 2.0, "style": 0.0}, legacy=True)
    check("a saved effect without Style renders as Classic", np.abs(saved - classic).max() == 0, str(np.abs(saved - classic).max()))
    unaffected = bench.render(white, {"reach": 0.4, "intensity": 2.0, "color": "#fffff2d0", "blend": 0.0, "thresholdAdd": "#ff808080"}, legacy=True)
    check("Classic ignores Blend and Threshold Add like the saved formula", np.abs(saved - unaffected).max() == 0, str(np.abs(saved - unaffected).max()))
    chosen = {"center": [0.5, 0.5], "reach": 0.4, "intensity": 2.0, "color": "#fffff2d0", "blend": 0.0, "style": 1.0}
    standard_saved = bench.render(white, chosen, legacy=True)
    standard_new = bench.render(white, chosen)
    check("choosing Standard on a saved effect gives the new look", np.abs(standard_saved - standard_new).max() == 0, str(np.abs(standard_saved - standard_new).max()))
    check("Classic and Standard differ", np.abs(bench.render(white, dict(chosen, style=0.0)) - standard_new).max() > 8)

    fine = bench.render(mixed, {"reach": 1.0, "intensity": 2.0}, scale=1.0)
    for scale in (0.5, 0.25):
        small = bench.render(mixed, {"reach": 1.0, "intensity": 2.0}, scale=scale)
        k = int(round(1 / scale))
        pooled = fine.reshape(small.shape[0], k, small.shape[1], k, 4).mean((1, 3))
        error = float(np.abs(pooled - small).mean())
        check(f"the same picture at scale {scale} matches full size", error < 3.0, f"{error:.3f}")


def main():
    if not LIBRARY.exists() or sys.platform != "darwin":
        print("rays_check: skipped (needs the host Skia build in third_party/skia/out/mac-v2-2-arm64)")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        build = subprocess.run(["sh", str(ROOT / "tools/render_bench/build.sh"), str(ROOT / "app/src/main/cpp"), tmp + "/bin"], capture_output=True, text=True)
        if build.returncode != 0:
            print(build.stdout[-2000:], build.stderr[-2000:])
            return 1
        work = Path(tmp) / "work"
        work.mkdir()
        run(Bench(Path(tmp) / "bin/render_bench", work))
    print(f"rays_check: {CHECKS[0]} checks, {len(FAILURES)} failed")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
