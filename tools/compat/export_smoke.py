#!/usr/bin/env python3
import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sdk

SCENE = """<scene title="export smoke" width="256" height="256" fps="30" totalTime="1000" bgcolor="#ff000000">
<shape id="1" label="red" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="64,128,0"/></transform><fillColor value="#ffff0000"/><property name="size" type="vec2" value="48,48"/></shape>
<shape id="2" label="blue" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="192,128,0"/></transform><fillColor value="#ff0000ff"/><property name="size" type="vec2" value="48,48"/></shape>
</scene>
"""
FILES = f"/sdcard/Android/data/{sdk.PACKAGE}/files"


def export(kind, extra, timeout):
    sdk.adb("shell", "am", "force-stop", sdk.PACKAGE)
    sdk.adb("shell", "rm", "-f", f"{FILES}/export.gif", f"{FILES}/export.mp4")
    sdk.adb("logcat", "-c")
    sdk.adb("shell", "am", "start", "-n", f"{sdk.PACKAGE}/.MainActivity", "--es", "xml", f"/data/data/{sdk.PACKAGE}/files/export-smoke.xml",
            "--es", "export", kind, "--ei", "fps", "30", *extra)
    deadline = time.time() + timeout
    while time.time() < deadline:
        log = sdk.adb("logcat", "-d", "-s", "AMExport", "AndroidRuntime")
        done = next((line for line in log.splitlines() if "AMExport: done" in line and (".gif" in line) == (kind == "gif")), None)
        if done:
            return done.split("done ", 1)[1].split(" ms=")[0], log
        if "AMExport: failed" in log or "FATAL EXCEPTION" in log:
            sys.exit(f"export_smoke: {kind} export failed:\n{log[-1500:]}")
        time.sleep(1)
    sys.exit(f"export_smoke: no {kind} export within {timeout} s")


def colours(image):
    h, w = image.shape[:2]
    return image[h // 2, w // 4], image[h // 2, 3 * w // 4], image[4, 4]


def check(name, image):
    red, blue, corner = colours(image)
    ok = red[0] > 180 and red[1] < 80 and red[2] < 80 and blue[2] > 180 and blue[0] < 80 and corner.max() < 40
    print(f"{name}: red {red.tolist()}, blue {blue.tolist()}, corner {corner.tolist()}: {'PASS' if ok else 'FAIL'}")
    return ok


def main():
    parser = argparse.ArgumentParser(description="Export a one-second scene as GIF and MP4 on ANDROID_SERIAL (debug build) and check the pixels")
    parser.add_argument("--kinds", default="gif,video")
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    print(f"device {sdk.serial()}: Android API {sdk.api_level()}")
    work = Path(tempfile.mkdtemp(prefix="export-smoke-"))
    (work / "scene.xml").write_text(SCENE)
    sdk.adb("push", work / "scene.xml", "/data/local/tmp/am-export-smoke.xml", check=True)
    sdk.adb("shell", "run-as", sdk.PACKAGE, "mkdir", "-p", "files")
    sdk.adb("shell", "run-as", sdk.PACKAGE, "cp", "/data/local/tmp/am-export-smoke.xml", "files/export-smoke.xml")
    sdk.adb("shell", "rm", "/data/local/tmp/am-export-smoke.xml")
    ok = True
    for kind in args.kinds.split(","):
        remote, log = export(kind, ["--ei", "short", "256"], args.timeout)
        remote = remote.replace("/storage/emulated/0/", "/sdcard/")
        path = next((line.split("through ", 1)[1] for line in log.splitlines() if "AMExport: rendering" in line), "")
        local = work / Path(remote).name
        sdk.pull_app_file(remote, local)
        sdk.adb("shell", "rm", remote)
        if kind == "gif":
            frames = Image.open(local)
            count = getattr(frames, "n_frames", 1)
            frames.seek(count // 2)
            print(f"gif: {count} frames")
            ok &= count >= 10 and check("gif", np.asarray(frames.convert("RGB")).astype(int))
        else:
            probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-count_frames", "-show_entries", "stream=codec_name,width,height,nb_read_frames",
                                    "-of", "csv=p=0", str(local)], capture_output=True, text=True).stdout.strip()
            print(f"video ({path or 'path not logged'}): {probe}")
            frame = work / "middle.png"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(local), "-vf", "select=eq(n\\,15)", "-vframes", "1", str(frame)], check=True)
            frames = int(probe.split(",")[-1]) if probe else 0
            ok &= frames >= 29 and check("video", np.asarray(Image.open(frame).convert("RGB")).astype(int))
    print("export_smoke:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


main()
