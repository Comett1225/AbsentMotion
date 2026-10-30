#!/usr/bin/env python3
import io
import os
import re
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
SCENE = ('<scene title="videoretrycheck" width="360" height="360" fps="10" totalTime="1999" bgcolor="#ff000000">'
         '<shape id="1" label="v" startTime="0" endTime="1999" fillType="media" mediaFillMode="stretch" s=".rect" '
         f'fillVideo="file://{DEVICE_DIR}/retry.mp4" outTime="2000"><transform><location value="180,180,0"/></transform>'
         '<property name="size" type="vec2" value="180,180"/></shape></scene>')


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def yellow():
    shot = np.asarray(Image.open(io.BytesIO(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)).convert("RGB")).astype(int)
    return int(((shot[:, :, 0] > 150) & (shot[:, :, 1] > 150) & (shot[:, :, 2] < 100)).sum())


def tap(description):
    adb("shell", "uiautomator", "dump", "/sdcard/videoretry-ui.xml")
    ui = adb("shell", "cat", "/sdcard/videoretry-ui.xml")
    adb("shell", "rm", "/sdcard/videoretry-ui.xml")
    found = re.search(rf'content-desc="{description}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)
    if not found:
        sys.exit(f"no '{description}' button on screen")
    x0, y0, x1, y1 = map(int, found.groups())
    adb("shell", "input", "tap", (x0 + x1) // 2, (y0 + y1) // 2)


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/video-retry-check"
    work.mkdir(parents=True, exist_ok=True)
    for k, colour in enumerate([(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]):
        Image.new("RGB", (256, 256), colour).save(work / f"{k:03d}.png")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "2", "-i", str(work / "%03d.png"), "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-g", "1", str(work / "retry.mp4")], check=True)
    (work / "retry.xml").write_text(SCENE)
    adb("shell", "rm", "-f", f"{DEVICE_DIR}/retry.mp4")
    adb("push", work / "retry.xml", f"{DEVICE_DIR}/retry.xml")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/app.absentmotion.MainActivity", "--es", "xml", f"{DEVICE_DIR}/retry.xml", "--ei", "frame", "0")
    deadline = time.time() + 60
    while "media ready" not in adb("logcat", "-d", "-s", "AMTest"):
        if time.time() > deadline:
            sys.exit("the app did not open the scene in 60 s")
        time.sleep(0.5)
    time.sleep(2)
    failed = "Cannot decode" in adb("logcat", "-d", "-s", "AMVideo")
    adb("push", work / "retry.mp4", f"{DEVICE_DIR}/retry.mp4")
    time.sleep(2)
    tap("Skip forward")
    time.sleep(3)
    shown = yellow()
    for name in ("retry.mp4", "retry.xml"):
        adb("shell", "rm", f"{DEVICE_DIR}/{name}")
    print(f"the first decode {'failed' if failed else 'did not fail'}; {shown} yellow pixels after the file appeared")
    if not failed:
        sys.exit("the video did not fail while it was missing; the check proves nothing")
    ok = shown > 10_000
    print(f"{'ok  ' if ok else 'FAIL'} a video that failed once shows its frames once it can be read")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
