#!/usr/bin/env python3
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from sdk import adb_command

ADB = adb_command()
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
WIDTH, HEIGHT, FPS, LENGTH = 1080, 1920, 30, 12000
VIDEOS = [("bench-a.mp4", 1920, 1080, 30, "testsrc2"), ("bench-b.mp4", 1920, 1080, 30, "smptehdbars"),
          ("bench-c.mp4", 1920, 1080, 30, "mandelbrot")]
LARGE = [("bench-4k-a.mp4", 3840, 2160, 300, "testsrc2"), ("bench-4k-b.mp4", 3840, 2160, 300, "smptehdbars"),
         ("bench-4k-c.mp4", 3840, 2160, 300, "mandelbrot")]
EFFECTS = [
    '<effect id="absent.glow"><property name="radius" type="float" value="45"/></effect>',
    '<effect id="absent.huesaturation"><property name="hue" type="float" value="40"/></effect>',
    '<effect id="absent.blur"><property name="strength" type="float" value="4"/></effect>',
    '<effect id="absent.vignette"/>',
    '<effect id="absent.rgbsplit"/>',
]


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def make_video(path, width, height, gop, source):
    if path.exists():
        return
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"{source}=size={width}x{height}:rate={FPS}",
                    "-t", str(LENGTH / 1000 + 1), "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "32",
                    "-g", str(gop), str(path)], check=True)


def moving(x0, y0, x1, y1):
    return (f'<location><kf t="0" v="{x0},{y0},0"/><kf t="0.5" v="{x1},{y1},0"/><kf t="1" v="{x0},{y0},0"/></location>')


def layer_times():
    return f'startTime="0" endTime="{LENGTH - 1}"'


def video_layer(k, name, width, height):
    return (f'<shape id="{100 + k}" label="v{k}" {layer_times()} fillType="media" mediaFillMode="fill" s=".rect" '
            f'fillVideo="file://{DEVICE_DIR}/{name}" outTime="{LENGTH}"><transform>{moving(300 + 240 * k, 500 + 400 * k, 780 - 240 * k, 900 + 200 * k)}'
            f'<opacity value="{1 - 0.2 * k}"/></transform><property name="size" type="vec2" value="480,270"/></shape>')


def shape_layer(k):
    shape = [".rect", ".circle", ".star", ".roundrect"][k % 4]
    return (f'<shape id="{300 + k}" label="s{k}" {layer_times()} fillType="color" s="{shape}"><transform>'
            f'{moving(100 + 70 * k, 200 + 120 * k, 980 - 70 * k, 1700 - 100 * k)}<rotation><kf t="0" v="0"/><kf t="1" v="360"/></rotation></transform>'
            f'<fillColor value="#ff{(40 * k) % 256:02x}80c0"/><property name="size" type="vec2" value="60,60"/>'
            '<border direction="inside"><color value="#fffad961"/><size value="6"/></border>'
            '<shadow direction="outside"><color value="#ff000000"/><size value="8"/><offset value="4,4"/></shadow></shape>')


def text_layer(k):
    effects = "".join(EFFECTS[(k + i) % len(EFFECTS)] for i in range(2 + k % 2))
    return (f'<text id="{500 + k}" label="t{k}" {layer_times()} fillType="color" size="{14 + k % 5 * 3}" wrapWidth="900" align="center">'
            f'<transform>{moving(540, 120 + 85 * k, 540 + (k % 3 - 1) * 200, 1800 - 85 * k)}</transform>'
            f'<fillColor value="#ffffffff"/><content>Benchmark line {k} AbsentMotion</content>{effects}</text>')


def group_layer():
    inner = "".join(
        f'<shape id="{700 + k}" label="g{k}" startTime="0" endTime="{LENGTH - 1}" fillType="color" s=".circle"><transform>'
        f'{moving(100 + 100 * k, 100, 400 - 100 * k, 300)}</transform><fillColor value="#ff30c0{40 * k:02x}"/>'
        '<property name="size" type="vec2" value="50,50"/></shape>' for k in range(4))
    return (f'<embedScene id="699" label="group" {layer_times()}><transform><location value="540,960,0"/>'
            f'<rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform>'
            f'<scene width="500" height="400" fps="{FPS}" totalTime="{LENGTH}">{inner}</scene></embedScene>')


def scene(videos):
    media = "".join(f'<media uri="file://{DEVICE_DIR}/{name}" type="video/mp4" width="{w}" height="{h}" duration="{LENGTH * 1000}" fps="{FPS}"/>'
                    for name, w, h, *_ in videos)
    body = (media + "".join(video_layer(k, v[0], v[1], v[2]) for k, v in enumerate(videos)) + group_layer()
            + "".join(shape_layer(k) for k in range(12)) + "".join(text_layer(k) for k in range(20)))
    return (f'<scene title="perfbench" width="{WIDTH}" height="{HEIGHT}" fps="{FPS}" totalTime="{LENGTH}" bgcolor="#ff202020">'
            + body + "</scene>")


def run(work, seconds, label, videos, extras):
    for name, w, h, gop, source in videos:
        make_video(work / name, w, h, gop, source)
        if name not in adb("shell", "ls", DEVICE_DIR):
            adb("push", work / name, f"{DEVICE_DIR}/{name}")
    xml = work / "perfbench.xml"
    xml.write_text(scene(videos))
    adb("push", xml, f"{DEVICE_DIR}/perfbench.xml")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/app.absentmotion.MainActivity", "--es", "xml", f"{DEVICE_DIR}/perfbench.xml", "--ei", "bench", seconds, *extras)
    deadline = time.time() + 600 + 4 * seconds
    while not (log := adb("logcat", "-d", "-s", "AMBench")).rstrip().endswith("done"):
        if time.time() > deadline:
            sys.exit("the benchmark did not finish")
        time.sleep(1)
    results = [json.loads(line[line.index("{"):]) for line in log.splitlines() if "{" in line]
    with (work / "results.jsonl").open("a") as out:
        for r in results:
            out.write(json.dumps({"label": label, **r}) + "\n")
    for r in results:
        print(f"{label:>12} {r['phase']:>6}: preview {r['previewFps']:5.1f} fps ({r['distinct']} of {r['advanced']} frames), "
              f"render p50 {r['renderP50']:6.2f} p95 {r['renderP95']:6.2f} max {r['renderMax']:6.1f} ms, "
              f"ui p50 {r['uiP50']:5.1f} p95 {r['uiP95']:5.1f} ms, slow {r['uiSlow']}/{r['uiFrames']}")
        print(" " * 21 + ", ".join(f"{k[2:-3].lower()} {r[k]:.1f}" for k in r if k.startswith("ui") and k.endswith("P50") and k != "uiP50"))
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("work", type=Path)
    parser.add_argument("--label", default="run")
    parser.add_argument("--seconds", type=int, default=6)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--large", action="store_true", help="three 4K videos with 10 s between key frames")
    parser.add_argument("--copies", choices=["on", "off"], help="turn preview copies of large videos on or off first")
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    extras = (["--ez", "previewCopies", "true" if args.copies == "on" else "false"] if args.copies else []) + (["--ez", "proxies", "true"] if args.large else [])
    for _ in range(args.repeat):
        run(args.work, args.seconds, args.label, LARGE if args.large else VIDEOS, extras)


if __name__ == "__main__":
    main()
