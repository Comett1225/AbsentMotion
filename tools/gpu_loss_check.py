#!/usr/bin/env python3
import io
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image
from sdk import adb_command

ROOT = Path(__file__).resolve().parents[1]
ADB = adb_command()
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
SCENE = ('<scene title="gpulosscheck" width="360" height="360" fps="30" totalTime="999" bgcolor="#ff000000">'
         '<shape id="1" label="r" startTime="0" endTime="999" fillType="color" mediaFillMode="fill" s=".rect">'
         '<transform><location><kf t="0" v="60,180,0"/><kf t="1" v="300,180,0"/></location></transform>'
         '<fillColor value="#ffff0000"/><property name="size" type="vec2" value="30,30"/></shape>'
         '<shape id="2" label="p" startTime="0" endTime="999" fillType="media" mediaFillMode="stretch" s=".rect" '
         f'fillImage="file://{DEVICE_DIR}/gpuloss.png"><transform><location value="180,60,0"/></transform>'
         '<property name="size" type="vec2" value="30,30"/></shape></scene>')


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def red_x():
    shot = np.asarray(Image.open(io.BytesIO(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)).convert("RGB")).astype(int)
    xs = np.nonzero((shot[:, :, 0] > 200) & (shot[:, :, 1] < 60) & (shot[:, :, 2] < 60))[1]
    green = int(((shot[:, :, 1] > 200) & (shot[:, :, 0] < 60) & (shot[:, :, 2] < 60)).sum())
    return (float(xs.mean()) if xs.size > 50 else None), green


def frame(n, *extra):
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ei", "frame", n, *extra)
    time.sleep(5 if extra else 2)
    return red_x()


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/gpu-loss-check"
    work.mkdir(parents=True, exist_ok=True)
    (work / "gpuloss.xml").write_text(SCENE)
    Image.new("RGB", (16, 16), (0, 255, 0)).save(work / "gpuloss.png")
    for name in ("gpuloss.xml", "gpuloss.png"):
        adb("push", work / name, f"{DEVICE_DIR}/{name}")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", f"{DEVICE_DIR}/gpuloss.xml", "--ei", "frame", "0")
    deadline = time.time() + 60
    while "media ready" not in adb("logcat", "-d", "-s", "AMTest"):
        if time.time() > deadline:
            sys.exit("the app did not open the scene in 60 s")
        time.sleep(0.5)
    time.sleep(2)
    (start, picture), (moved, _) = red_x(), frame(25)
    back, _ = frame(5)
    after, kept = frame(25, "--ez", "losegpu", "true")
    for name in ("gpuloss.xml", "gpuloss.png"):
        adb("shell", "rm", f"{DEVICE_DIR}/{name}")
    print(f"square at x {start} (frame 0), {moved} (frame 25), {back} (frame 5), {after} (frame 25 after losing the GPU)")
    print(f"picture pixels {picture} before and {kept} after losing the GPU")
    if None in (start, moved, back) or moved - start < 20 or picture < 50:
        sys.exit("the preview does not show the scene; is the editor open on the emulator?")
    results = [("the preview draws again after the engine is recreated", after is not None and abs(after - moved) < 3),
               ("pictures are uploaded again to the new engine", abs(kept - picture) < picture * 0.1)]
    for title, ok in results:
        print(f"{'ok  ' if ok else 'FAIL'} {title}")
    sys.exit(0 if all(ok for _, ok in results) else 1)


if __name__ == "__main__":
    main()
