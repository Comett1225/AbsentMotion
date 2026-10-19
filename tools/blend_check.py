#!/usr/bin/env python3
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ADB = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", os.environ.get("ANDROID_SERIAL") or "emulator-5554"]
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
NAME = "blendcheck"
CELL = 12
TOLERANCE = 3

SOURCES = [(230, 77, 153), (51, 179, 26), (0, 0, 0), (255, 255, 255), (115, 115, 204)]
SOURCE_ALPHAS = [(255, 1.0), (255, 0.45), (153, 1.0), (128, 0.7)]
BACKDROPS = [(77, 140, 204), (217, 153, 51), (26, 26, 26), (153, 153, 153)]
BACKDROP_ALPHAS = [255, 128]


def luma(c):
    return c[..., 0] * 0.299 + c[..., 1] * 0.587 + c[..., 2] * 0.114


def hi(c):
    return c.max(-1)


def lo(c):
    return c.min(-1)


def hue_shape(c):
    h, l = hi(c)[..., None], lo(c)[..., None]
    span = h - l
    return np.where(span > 0, (c - l) / np.where(span > 0, span, 1), 1.0)


def hard(light, base):
    return np.where(light <= 0.5, base * 2 * light, 1 - (1 - base) * (2 - 2 * light))


def pick(cond, a, b):
    return np.where(cond[..., None], a, b)


MODES = {
    "normal": lambda s, b: s,
    "screen": lambda s, b: s + b - s * b,
    "multiply": lambda s, b: s * b,
    "diff": lambda s, b: abs(b - s),
    "exclusion": lambda s, b: s + b - 2 * s * b,
    "lighten": np.maximum,
    "darken": np.minimum,
    "lighter-color": lambda s, b: pick(luma(s) > luma(b), s, b),
    "darker-color": lambda s, b: pick(luma(s) < luma(b), s, b),
    "color-burn": lambda s, b: np.where(b >= 1, 1, 1 - np.minimum(1, (1 - b) / np.maximum(s, 1e-6))),
    "linear-burn": lambda s, b: s + b - 1,
    "color-dodge": lambda s, b: np.where(b <= 0, 0, np.minimum(1, b / np.maximum(1 - s, 1e-6))),
    "linear-dodge": lambda s, b: s + b,
    "overlay": lambda s, b: hard(b, s),
    "soft-light": lambda s, b: 0.5 * (b + hard(s, b)),
    "hard-light": lambda s, b: hard(s, b),
    "soft-overlay": lambda s, b: 0.5 * (b + hard(b, s)),
    "vivid-light": lambda s, b: np.where(s <= 0.5, np.where(b >= 1, 1, 1 - np.minimum(1, (1 - b) / np.maximum(2 * s, 1e-6))), np.where(b <= 0, 0, np.minimum(1, b / np.maximum(2 - 2 * s, 1e-6)))),
    "linear-light": lambda s, b: b + 2 * s - 1,
    "pin-light": lambda s, b: np.where(s <= 0.5, np.minimum(b, 2 * s), np.maximum(b, 2 * s - 1)),
    "hue": lambda s, b: lo(b)[..., None] + (hi(b) - lo(b))[..., None] * hue_shape(s),
    "saturation": lambda s, b: hi(b)[..., None] * (1 - np.where(hi(s) > 0, (hi(s) - lo(s)) / np.where(hi(s) > 0, hi(s), 1), 0)[..., None] * (1 - hue_shape(b))),
    "color": lambda s, b: s + (luma(b) - luma(s))[..., None],
    "luminance": lambda s, b: b + (luma(s) - luma(b))[..., None],
    "subtract": lambda s, b: b - s,
    "divide": lambda s, b: b / np.maximum(s, 1e-6),
}

COLUMNS = [(c, a) for c in SOURCES for a in SOURCE_ALPHAS]
ROWS = [(c, a) for a in BACKDROP_ALPHAS for c in BACKDROPS]
PER_LINE = 4
BLOCK_W, BLOCK_H = len(COLUMNS) * CELL, len(ROWS) * CELL
GAP = CELL


def rect(id, x, y, w, h, argb, opacity=1.0, blending=""):
    blend = f' blending="{blending}"' if blending else ""
    return (f'<shape id="{id}" label="r{id}" startTime="0" endTime="999" fillType="color" mediaFillMode="fill" s=".rect"{blend}>'
            f'<transform><location value="{x + w / 2:.1f},{y + h / 2:.1f},0"/><opacity value="{opacity:.4f}"/></transform>'
            f'<fillColor value="#{argb:08x}"/><property name="size" type="vec2" value="{w / 2:.1f},{h / 2:.1f}"/></shape>')


def layout():
    modes = list(MODES)
    lines = -(-len(modes) // PER_LINE)
    width = PER_LINE * (BLOCK_W + GAP) + GAP
    height = lines * (BLOCK_H + GAP) + GAP
    shapes, next_id = [], 1
    for line in range(lines):
        top = GAP + line * (BLOCK_H + GAP)
        for r, ((cr, cg, cb), a) in enumerate(ROWS):
            shapes.append(rect(next_id, 0, top + r * CELL, width, CELL, a << 24 | cr << 16 | cg << 8 | cb))
            next_id += 1
    origins = {}
    for k, mode in enumerate(modes):
        left = GAP + (k % PER_LINE) * (BLOCK_W + GAP)
        top = GAP + (k // PER_LINE) * (BLOCK_H + GAP)
        origins[mode] = (left, top)
        for c, ((cr, cg, cb), (a, opacity)) in enumerate(COLUMNS):
            shapes.append(rect(next_id, left + c * CELL, top, CELL, BLOCK_H, a << 24 | cr << 16 | cg << 8 | cb, opacity, mode))
            next_id += 1
    xml = (f'<scene title="blendcheck" width="{width}" height="{height}" fps="30" totalTime="1000" bgcolor="#00000000">'
           + "".join(shapes) + "</scene>")
    return xml, origins


def stored(colour, alpha):
    return np.round(colour * alpha / 255) / alpha


def expected(mode):
    a = np.array([[a for _, (a, _) in COLUMNS]] * len(ROWS), float)[..., None]
    s = stored(np.array([[c for c, _ in COLUMNS]] * len(ROWS), float), a)
    sa = a / 255 * np.array([[o for _, (_, o) in COLUMNS]] * len(ROWS), float)[..., None]
    a = np.array([[a for _, a in ROWS]] * len(COLUMNS), float).T[..., None]
    b = stored(np.array([[c for c, _ in ROWS]] * len(COLUMNS), float).transpose(1, 0, 2), a)
    ba = a / 255
    mixed = np.clip(MODES[mode](s, b), 0, 1)
    alpha = sa + ba * (1 - sa)
    rgb = sa * (1 - ba) * s + ba * (1 - sa) * b + sa * ba * mixed
    return np.concatenate([rgb, alpha], -1) * 255


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def render(xml, work):
    local = work / f"{NAME}.xml"
    local.write_text(xml)
    remote = f"{DEVICE_DIR}/{NAME}.xml"
    adb("push", local, remote)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", remote, "--es", "still", NAME, "--ei", "frame", "0")
    deadline = time.time() + 120
    while f"still {NAME} ok=" not in (log := adb("logcat", "-d", "-s", "AMTest")):
        if time.time() > deadline:
            sys.exit("the app wrote no still in 120 s")
        time.sleep(0.5)
    if f"still {NAME} ok=true" not in log:
        sys.exit("the app could not render the still")
    adb("pull", f"{DEVICE_DIR}/{NAME}.png", work / f"{NAME}.png")
    adb("shell", "rm", f"{DEVICE_DIR}/{NAME}.png", remote)
    return np.asarray(Image.open(work / f"{NAME}.png").convert("RGBA")).astype(float)


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/blend-check"
    work.mkdir(parents=True, exist_ok=True)
    xml, origins = layout()
    image = render(xml, work)
    failed = 0
    for mode, (left, top) in origins.items():
        ys = top + np.arange(len(ROWS)) * CELL + CELL // 2
        xs = left + np.arange(len(COLUMNS)) * CELL + CELL // 2
        got = image[np.ix_(ys, xs)]
        got = np.concatenate([got[..., :3] * got[..., 3:] / 255, got[..., 3:]], -1)
        error = np.abs(got - expected(mode)).max(-1)
        bad = int((error > TOLERANCE).sum())
        failed += bad
        print(f"{'FAIL' if bad else 'ok  '} {mode:14} max {error.max():6.1f}  mean {error.mean():5.2f}  {bad:3d} of {error.size} cells off")
    print(f"\n{failed} cells differ from the W3C compositing by more than {TOLERANCE} levels")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
