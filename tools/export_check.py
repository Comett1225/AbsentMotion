#!/usr/bin/env python3
import os, subprocess, sys, time
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = [os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "-s", os.environ.get("ANDROID_SERIAL") or "emulator-5554"]
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
OUT = os.path.join(ROOT, "build/export-check")
LIMIT = 400

def adb(*a):
    return subprocess.run([*ADB, *a], capture_output=True).stdout

def main():
    if len(sys.argv) < 3:
        sys.exit("usage: export_check.py <scene.xml> <stills folder> [export options]")
    scene, folder = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    os.makedirs(OUT, exist_ok=True)
    remote = f"{DEVICE_DIR}/exportcheck.xml"
    adb("push", scene, remote)
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", remote, "--es", "export", "video", *sys.argv[3:])
    for _ in range(240):
        time.sleep(0.5)
        log = adb("logcat", "-d", "-s", "AMExport").decode("utf-8", "replace")
        if "I AMExport: done" in log or "E AMExport" in log:
            break
    if "I AMExport: done" not in log:
        sys.exit("export failed or did not finish")
    local = os.path.join(OUT, "exportcheck.mp4")
    adb("pull", f"{DEVICE_DIR}/export.mp4", local)
    frames = int(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=nb_frames", "-of", "csv=p=0", local],
                                capture_output=True, text=True).stdout.strip())
    good = total = 0
    for name in sorted(f for f in os.listdir(folder) if f[:5].isdigit() and f.endswith(".png")):
        k = int(name[:5])
        still = Image.open(os.path.join(folder, name)).convert("RGB")
        width, height = still.size
        ref = np.asarray(still).astype(int)
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", local, "-vf", f"select=eq(n\\,{k}),scale={width}:{height}", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                             capture_output=True).stdout
        total += 1
        if len(raw) != width * height * 3:
            print(f"FAIL  frame {k:4d} is not in the file ({frames} frames)")
            continue
        ours = np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 3).astype(int)
        wrong = np.abs(ours - ref).max(2) > 48
        core = wrong.copy()
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                core &= np.roll(np.roll(wrong, dy, 0), dx, 1)
        n = int(core[4:-4, 4:-4].sum())
        good += n < LIMIT
        print(f"{'PASS' if n < LIMIT else 'FAIL'}  frame {k:4d} {n:7d} wrong pixels")
    print(f"\n{good} of {total} checked frames match ({frames} frames in the file)")
    sys.exit(0 if total and good == total else 1)

if __name__ == "__main__":
    main()
