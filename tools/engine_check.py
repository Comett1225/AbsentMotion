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
TOLERANCE = 2
BLACK, RED, HALF = (0, 0, 0), (255, 0, 0), (128, 128, 128)


def scene(body, width=200, height=200, fps=30, bg="#ff000000"):
    return (f'<scene title="enginecheck" width="{width}" height="{height}" fps="{fps}" totalTime="1000" bgcolor="{bg}">'
            + body + "</scene>")


def transform(location="0,0,0", opacity=1.0):
    return f'<transform><location value="{location}"/><opacity value="{opacity}"/></transform>'


def rect(id, cx, cy, hw, hh, argb=0xffffffff, opacity=1.0, extra="", fill="color", attrs=""):
    return (f'<shape id="{id}" label="r{id}" startTime="0" endTime="1000" fillType="{fill}" mediaFillMode="fill" s=".rect"{attrs}>'
            f'{transform(f"{cx},{cy},0", opacity)}<fillColor value="#{argb:08x}"/>'
            f'<property name="size" type="vec2" value="{hw},{hh}"/>{extra}</shape>')


CENTRED = '<border direction="centered"><color value="#ffffffff"/><size value="{}"/></border>'


def fill_stroke(points):
    return '<stroke color="#ffffffff" width="4.0" type="fill" points="' + ";".join(f"{x},{y},1" for x, y in points) + '"/>'


CASES = {
    "drawing strokes share the layer opacity": (
        scene(f'<drawing id="1" label="d" startTime="0" endTime="1000">{transform(opacity=0.5)}'
              + fill_stroke([(20, 20), (120, 20), (120, 120), (20, 120)])
              + fill_stroke([(80, 80), (180, 80), (180, 180), (80, 180)]) + "</drawing>"),
        [((100, 100), HALF), ((50, 50), HALF), ((150, 150), HALF), ((180, 30), BLACK)]),
    "fill and stroke share the layer opacity": (
        scene(rect(1, 100, 100, 50, 50, opacity=0.5, extra='<path-stroke><color value="#ffffffff"/><size value="40"/></path-stroke>')),
        [((100, 100), HALF), ((60, 100), HALF), ((40, 100), HALF), ((10, 100), BLACK)]),
    "a centred border shares the layer opacity": (
        scene(rect(1, 100, 100, 50, 50, opacity=0.5, extra=CENTRED.format(40))),
        [((100, 100), HALF), ((60, 100), HALF), ((40, 100), HALF), ((10, 100), BLACK)]),
    "a centred border follows the layer blend mode": (
        scene(rect(1, 100, 100, 100, 100, 0xffff0000) + rect(2, 100, 100, 50, 50, fill="none", extra=CENTRED.format(12), attrs=' blending="multiply"')),
        [((50, 100), RED), ((150, 100), RED), ((100, 100), RED)]),
    "embedded scene layers use its frame rate": (
        scene(f'<embedScene id="1" label="g" startTime="0" endTime="1000">{transform("100,100,0")}'
              '<scene width="200" height="200" fps="24" totalTime="1000" bgcolor="#00000000" retime="off">'
              '<shape id="2" label="r" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect">'
              '<transform><location><kf t="0.03" v="100,100,0"/><kf t="0.5" v="1100,100,0"/></location></transform>'
              '<fillColor value="#ffff0000"/><property name="size" type="vec2" value="10,30"/></shape></scene></embedScene>', fps=60),
        [((52, 100), RED), ((100, 100), BLACK)]),
}


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def run(name, xml, work):
    local = work / f"{name}.xml"
    local.write_text(xml)
    remote = f"{DEVICE_DIR}/{name}.xml"
    adb("push", local, remote)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", remote, "--es", "still", name, "--ei", "frame", "0")
    deadline = time.time() + 120
    while f"still {name} ok=" not in (log := adb("logcat", "-d", "-s", "AMTest")):
        if time.time() > deadline:
            sys.exit(f"the app did not answer {name} in 120 s")
        time.sleep(0.5)
    adb("shell", "rm", remote)
    return log


def still(name, xml, work):
    if f"still {name} ok=true" not in run(name, xml, work):
        sys.exit(f"the app could not render {name}")
    adb("pull", f"{DEVICE_DIR}/{name}.png", work / f"{name}.png")
    adb("shell", "rm", f"{DEVICE_DIR}/{name}.png")
    return np.asarray(Image.open(work / f"{name}.png").convert("RGB")).astype(int)


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/engine-check"
    work.mkdir(parents=True, exist_ok=True)
    results = []
    for k, (title, (xml, probes)) in enumerate(CASES.items()):
        image = still(f"enginecheck{k}", xml, work)
        results.append((title, [f"{xy} is {tuple(int(v) for v in image[xy[1], xy[0]])}, expected {want}" for xy, want in probes
                                if np.abs(image[xy[1], xy[0]] - want).max() > TOLERANCE]))
    failed = 0
    for title, bad in results:
        failed += bool(bad)
        print(f"{'FAIL' if bad else 'ok  '} {title}" + "".join(f"\n       {b}" for b in bad))
    print(f"\n{failed} of {len(results)} checks failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
