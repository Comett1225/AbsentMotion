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
SCENE = (f'<scene title="refreshcheck" width="360" height="360" fps="30" totalTime="11999" bgcolor="#ff000000">'
         f'<shape id="1" label="p" startTime="0" endTime="11999" fillType="media" mediaFillMode="stretch" s=".rect" '
         f'fillImage="file://{DEVICE_DIR}/refresh.jpg"><transform><location value="180,180,0"/></transform>'
         f'<property name="size" type="vec2" value="180,180"/></shape></scene>')


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/refresh-check"
    work.mkdir(parents=True, exist_ok=True)
    pixels = np.random.default_rng(1).integers(0, 256, (3000, 3000, 3), dtype=np.uint8)
    pixels[:, :, 0] = 255
    Image.fromarray(pixels).save(work / "refresh.jpg", quality=95)
    (work / "refresh.xml").write_text(SCENE)
    adb("push", work / "refresh.jpg", f"{DEVICE_DIR}/refresh.jpg")
    adb("push", work / "refresh.xml", f"{DEVICE_DIR}/refresh.xml")
    adb("shell", "rm", "-rf", f"{DEVICE_DIR}/export.seq")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", f"{DEVICE_DIR}/refresh.xml",
        "--es", "export", "sequence", "--ei", "short", "90", "--es", "format", "png")
    resumes, deadline = 0, time.time() + 300
    while "AMExport: done" not in (log := adb("logcat", "-d", "-s", "AMExport")) and "E AMExport" not in log:
        if time.time() > deadline:
            sys.exit("the export did not finish in 300 s")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(0.4)
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        resumes += 1
        time.sleep(0.8)
    if "done" not in log:
        sys.exit(f"the export failed: {log.strip().splitlines()[-1:]}")
    local = work / "export.seq"
    subprocess.run(["rm", "-rf", str(local)])
    adb("pull", f"{DEVICE_DIR}/export.seq", work)
    frames = sorted(local.glob("frame_*.png"))
    missing = [f.name for f in frames if np.asarray(Image.open(f).convert("RGB"))[:, :, 0].mean() < 200]
    for name in ("refresh.jpg", "refresh.xml", "export.seq"):
        adb("shell", "rm", "-rf", f"{DEVICE_DIR}/{name}")
    print(f"{len(frames)} frames exported while the app was resumed {resumes} times")
    print(f"{'FAIL' if missing or not frames else 'ok  '} every exported frame shows the picture" + "".join(f"\n       {m} has no picture" for m in missing))
    sys.exit(1 if missing or not frames else 0)


if __name__ == "__main__":
    main()
