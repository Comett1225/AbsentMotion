#!/usr/bin/env python3
import argparse
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sdk

SCENE = """<scene title="smoke" width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000">
<shape id="1" label="red" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="60,100,0"/></transform><fillColor value="#ffff0000"/><property name="size" type="vec2" value="40,40"/></shape>
<shape id="2" label="blue" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".roundrect"><transform><location value="140,100,0"/></transform><fillColor value="#ff0000ff"/><property name="size" type="vec2" value="40,40"/></shape>
</scene>
"""


def main():
    parser = argparse.ArgumentParser(description="Install a debug APK on a device or emulator of any Android version, render a still and check the pixels")
    parser.add_argument("apk", nargs="?", help="debug APK to install first (adb install -r); leave out to test what is installed")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    api = sdk.api_level()
    if not api:
        sys.exit(f"smoke_check: no device answers for {sdk.serial()}")
    print(f"device {sdk.serial()}: Android API {api}, {sdk.adb('shell', 'getprop', 'ro.product.model').strip()}")
    if args.apk:
        sdk.adb("install", "-r", args.apk, check=True)
    work = Path(tempfile.mkdtemp(prefix="smoke-"))
    scene = work / "smoke.xml"
    scene.write_text(SCENE)
    package = sdk.PACKAGE
    sdk.adb("push", scene, "/data/local/tmp/am-smoke.xml", check=True)
    sdk.adb("shell", "am", "force-stop", package)
    copied = sdk.adb("shell", "run-as", package, "cp", "/data/local/tmp/am-smoke.xml", "files/smoke.xml")
    if copied.strip():
        sys.exit(f"smoke_check: cannot copy into the app (is it a debug build?): {copied.strip()}")
    sdk.adb("shell", "rm", "/data/local/tmp/am-smoke.xml")
    sdk.adb("logcat", "-c")
    internal = f"/data/data/{package}/files/smoke.xml"
    sdk.adb("shell", "am", "start", "-n", f"{package}/.MainActivity", "--es", "xml", internal, "--es", "still", "smoke", "--ei", "frame", "0")
    deadline = time.time() + args.timeout
    log = ""
    while time.time() < deadline:
        log = sdk.adb("logcat", "-d", "-s", "AMTest", "AMEngine", "AndroidRuntime")
        if "still smoke ok=" in log or "FATAL EXCEPTION" in log:
            break
        time.sleep(1)
    engine = next((line.split("engine ready:")[1].strip() for line in log.splitlines() if "engine ready:" in line), "")
    print("engine:", engine[:140] or "(no engine ready line)")
    if "FATAL EXCEPTION" in log:
        sys.exit("smoke_check: the app crashed:\n" + "\n".join(line for line in log.splitlines() if "AndroidRuntime" in line)[:1200])
    if "still smoke ok=true" not in log:
        sys.exit("smoke_check: no still was rendered within the time limit:\n" + log[-800:])
    png = work / "smoke.png"
    sdk.adb("pull", f"/sdcard/Android/data/{package}/files/smoke.png", png, check=True)
    sdk.adb("shell", "rm", f"/sdcard/Android/data/{package}/files/smoke.png")
    image = np.asarray(Image.open(png).convert("RGB")).astype(int)
    h, w = image.shape[:2]
    scale = w / 200
    red, blue, corner = image[int(100 * scale), int(60 * scale)], image[int(100 * scale), int(140 * scale)], image[int(5 * scale), int(5 * scale)]
    print(f"red square {red.tolist()}, blue square {blue.tolist()}, corner {corner.tolist()} ({w}x{h})")
    ok = red[0] > 200 and red[1] < 60 and red[2] < 60 and blue[2] > 200 and blue[0] < 60 and corner.max() < 20
    print("smoke_check:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


main()
