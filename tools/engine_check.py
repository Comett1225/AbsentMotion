#!/usr/bin/env python3
import math
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from sdk import adb_command

ROOT = Path(__file__).resolve().parents[1]
ADB = adb_command()
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
TOLERANCE = 2
BLACK, RED, GREEN, BLUE, HALF = (0, 0, 0), (255, 0, 0), (0, 255, 0), (0, 0, 255), (128, 128, 128)


def scene(body, width=200, height=200, fps=30, bg="#ff000000"):
    return (f'<scene title="enginecheck" width="{width}" height="{height}" fps="{fps}" totalTime="1000" bgcolor="{bg}">'
            + body + "</scene>")


def transform(location="0,0,0", opacity=1.0):
    return f'<transform><location value="{location}"/><opacity value="{opacity}"/></transform>'


def rect(id, cx, cy, hw, hh, argb=0xffffffff, opacity=1.0, extra="", fill="color", attrs=""):
    return (f'<shape id="{id}" label="r{id}" startTime="0" endTime="1000" fillType="{fill}" mediaFillMode="fill" s=".rect"{attrs}>'
            f'{transform(f"{cx},{cy},0", opacity)}<fillColor value="#{argb:08x}"/>'
            f'<property name="size" type="vec2" value="{hw},{hh}"/>{extra}</shape>')


def picture(name, rotation, size="100,50"):
    return (f'<shape id="1" label="p" startTime="0" endTime="1000" fillType="media" mediaFillMode="stretch" s=".rect" '
            f'fillImage="file://{DEVICE_DIR}/{name}"><transform><location value="100,100,0"/><rotation value="{rotation}"/></transform>'
            f'<property name="size" type="vec2" value="{size}"/></shape>')


def exif_jpeg(path, tag):
    image = Image.new("RGB", (40, 20), RED)
    image.paste(BLUE, (20, 0, 40, 20))
    exif = Image.Exif()
    exif[0x0112] = tag
    image.save(path, "JPEG", quality=100, subsampling=0, exif=exif.tobytes())


def cutout_png(path):
    image = Image.new("RGBA", (100, 100), (0, 0, 0, 0))
    ImageDraw.Draw(image).ellipse((20, 20, 80, 80), fill=RED + (255,))
    image.save(path)


PICTURES = {"exif2.jpg": lambda path: exif_jpeg(path, 2), "exif5.jpg": lambda path: exif_jpeg(path, 5), "cutout.png": cutout_png}


def cutout(extra=""):
    return (f'<shape id="1" label="c" startTime="0" endTime="1000" fillType="media" mediaFillMode="stretch" s=".rect" '
            f'fillImage="file://{DEVICE_DIR}/cutout.png">{transform("100,100,0")}<property name="size" type="vec2" value="50,50"/>{extra}</shape>')


CUT_STROKE = '<path-stroke><color value="#ff00ff00"/><size value="6"/></path-stroke>'
CUT_GLOW = ('<border direction="outside"><color value="#ff0000ff"/><size value="8"/></border><effect id="absent.glow" hidden="false">'
            '<property name="radius" type="float" value="30"/><property name="hardness" type="float" value="1"/></effect>')


def video(path, fps, colours):
    frames = path.parent / "frames"
    frames.mkdir(exist_ok=True)
    for k, colour in enumerate(colours):
        Image.new("RGB", (256, 256), colour).save(frames / f"{k:03d}.png")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(fps), "-i", str(frames / "%03d.png"), "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-g", "1", str(path)], check=True)
    for f in frames.iterdir():
        f.unlink()


SWAPPED = scene('<shape id="1" label="v" startTime="0" endTime="1999" fillType="media" mediaFillMode="stretch" s=".rect" '
                f'fillVideo="file://{DEVICE_DIR}/swap.mp4" outTime="2000"><transform><location value="32,32,0"/></transform>'
                '<property name="size" type="vec2" value="32,32"/></shape>', 64, 64, 10)

CENTRED = '<border direction="centered"><color value="#ffffffff"/><size value="{}"/></border>'
OUTSIDE = '<border direction="outside"><color value="#ffffffff"/><size value="{}"/></border>'


def repeated_late(extra):
    return ('<shape id="1" label="m" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect">'
            '<transform><location><kf t="0" v="40,60,0"/><kf t="1" v="160,60,0"/></location></transform>'
            '<fillColor value="#ffff0000"/><property name="size" type="vec2" value="20,20"/>'
            '<effect id="absent.repeat" hidden="false"><property name="count" type="float" value="2"/>'
            f'<property name="time" type="float" value="0.5"/></effect>{extra}</shape>')


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
    "a missing picture draws nothing": (
        scene('<media uri="file:///nonexistent/missing.png" missing="true"/>'
              + rect(1, 100, 100, 50, 50, opacity=0.5, fill="media", attrs=' fillImage="file:///nonexistent/missing.png"')),
        [((53, 53), BLACK), ((59, 53), BLACK), ((53, 59), BLACK), ((147, 147), BLACK), ((30, 30), BLACK)]),
    "a picture tagged as mirrored shows unmirrored": (
        scene(picture("exif2.jpg", 0)), [((50, 100), BLUE), ((150, 100), RED)]),
    "a picture tagged as transposed shows turned and mirrored": (
        scene(picture("exif5.jpg", 270)), [((100, 50), RED), ((100, 150), BLUE)]),
    "a stroke on a cut-out picture follows its visible pixels": (
        scene(cutout(CUT_STROKE)), [((100, 70), GREEN), ((100, 100), RED), ((52, 100), BLACK), ((55, 55), BLACK)]),
    "a border on a cut-out picture with Glow follows its visible pixels": (
        scene(cutout(CUT_GLOW)), [((100, 68), BLUE), ((100, 100), RED), ((52, 100), BLACK), ((55, 55), BLACK)]),
    "an outside border stays outside a see-through fill": (
        scene(rect(1, 100, 100, 50, 50, 0x80ff0000, extra=OUTSIDE.format(12))),
        [((100, 100), (128, 0, 0)), ((52, 100), (128, 0, 0)), ((100, 47), (255, 255, 255)), ((100, 30), BLACK)]),
    "an outside border stays outside see-through text": (
        scene('<text id="1" label="t" fillType="color" startTime="0" endTime="1000" size="150" wrapWidth="190" align="center">'
              f'{transform("100,100,0")}<content>I</content><fillColor value="#80ff0000"/>{OUTSIDE.format(10)}</text>'),
        [((100, 100), (128, 0, 0))]),
    "a stroke on a drawing follows the drawn line, not its bounding box": (
        scene(f'<drawing id="1" label="d" startTime="0" endTime="1000">{transform()}'
              f'<stroke color="#ffffffff" width="20" type="pen" points="40,40,1;160,160,1"/>{CUT_STROKE}</drawing>'),
        [((100, 100), (255, 255, 255)), ((107, 93), GREEN), ((168, 100), BLACK), ((165, 35), BLACK)]),
    "a stroke on a group follows what is inside it": (
        scene(f'<embedScene id="1" label="g" startTime="0" endTime="1000">{transform("100,100,0")}'
              '<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#00000000">'
              + rect(2, 100, 100, 40, 40, 0xffff0000) + f'</scene>{CUT_STROKE}</embedScene>'),
        [((100, 100), RED), ((60, 100), GREEN), ((100, 140), GREEN), ((50, 100), BLACK)]),
    "a border follows a Repeat copy that runs late": (
        scene(repeated_late(OUTSIDE.format(8)), fps=10),
        [((76, 60), RED), ((136, 60), RED), ((54, 60), (255, 255, 255)), ((98, 60), (255, 255, 255)), ((114, 60), (255, 255, 255)),
         ((158, 60), (255, 255, 255)), ((106, 60), BLACK)], 8),
    "a centred border follows a Repeat copy that runs late": (
        scene(repeated_late(CENTRED.format(6).replace("#ffffffff", "#ff0000ff")), fps=10),
        [((76, 60), RED), ((56, 60), BLUE), ((96, 60), BLUE), ((116, 60), BLUE), ((156, 60), BLUE), ((106, 60), BLACK)], 8),
    "a shutter blur on a still layer keeps its first frame at full strength": (
        scene(rect(1, 100, 100, 50, 50, extra='<effect id="absent.shutterblur" hidden="false"><property name="shutter" type="float" value="180"/></effect>')),
        [((100, 100), (255, 255, 255))]),
    "a drawing made of one tap draws a dot": (
        scene(f'<drawing id="1" label="d" startTime="0" endTime="1000">{transform()}<stroke color="#ffffffff" width="20" type="pen" points="100,100,1"/></drawing>'),
        [((100, 100), (255, 255, 255)), ((100, 130), BLACK)]),
    "a stroke on text follows the letters, not the text box": (
        scene('<text id="1" label="t" fillType="color" startTime="0" endTime="1000" size="30" wrapWidth="190" align="center">'
              f'{transform("100,100,0")}<content>Hi</content><fillColor value="#ffffffff"/>'
              '<path-stroke><color value="#ff00ff00"/><size value="4"/></path-stroke></text>'),
        [((5, 100), BLACK), ((195, 100), BLACK), ((64, 100), GREEN)]),
    "an open part of a shape with several parts keeps its stroke": (
        scene('<shape id="1" label="icon" startTime="0" endTime="1000" fillType="none" mediaFillMode="fill">'
              f'{transform()}<path><contour d="M 60 60 L 140 60 L 140 140 L 60 140 Z"/><contour d="M 20 20 L 180 180"/></path>'
              '<path-stroke><color value="#ffffffff"/><size value="8"/></path-stroke></shape>'),
        [((30, 30), (255, 255, 255)), ((170, 170), (255, 255, 255)), ((60, 100), (255, 255, 255)), ((120, 80), BLACK), ((170, 30), BLACK)]),
    "a backdrop effect moves the layers behind it and hides its own fill": (
        scene(rect(1, 50, 100, 50, 100, 0xffff0000) + rect(2, 150, 100, 50, 100, 0xff0000ff)
              + rect(3, 100, 100, 60, 60, extra='<effect id="absent.displacementmap" hidden="false">'
                     '<property name="offset" type="vec2" value="-50,0"/></effect>')
              + rect(4, 150, 60, 10, 10, 0xff00ff00)),
        [((60, 100), BLUE), ((20, 100), RED), ((180, 100), BLUE), ((150, 60), GREEN), ((100, 60), BLUE)]),
    "an effect can read the layer below as a map, even at zero opacity": (
        scene(rect(1, 50, 100, 50, 100, opacity=0.0)
              + rect(2, 150, 100, 50, 100, 0xff00ff00, extra='<effect id="absent.displacementmap" hidden="false">'
                     '<property name="offset" type="vec2" value="-50,0"/><property name="map" type="float" value="1"/></effect>')),
        [((75, 100), GREEN), ((25, 100), BLACK), ((150, 100), GREEN)]),
}

HITS = {
    "a stroke end arrow can be tapped": (
        scene('<shape id="7" label="line" startTime="0" endTime="1000" fillType="none" mediaFillMode="fill">'
              f'{transform()}<path d="M 40 100 L 140 100"/>'
              '<path-stroke end="arrow-filled" end-size="3"><color value="#ffffffff"/><size value="10"/></path-stroke></shape>'),
        [((130, 85), 7), ((60, 100), 7), ((60, 85), 0), ((180, 100), 0)]),
    "only the visible pixels of a cut-out picture can be tapped": (
        scene(cutout(CUT_STROKE)), [((100, 100), 1), ((100, 68), 1), ((55, 55), 0), ((100, 60), 0)]),
}


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def run(name, xml, work, hits=(), frame=0):
    local = work / f"{name}.xml"
    local.write_text(xml)
    remote = f"{DEVICE_DIR}/{name}.xml"
    adb("push", local, remote)
    adb("logcat", "-c")
    extras = ["--es", "hit", "'" + ";".join(f"{x},{y}" for x, y in hits) + "'"] if hits else []
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", remote, "--es", "still", name, "--ei", "frame", frame, *extras)
    deadline, pid = time.time() + 120, None
    while f"still {name} ok=" not in (log := adb("logcat", "-d", "-s", "AMTest")) or log.count(" hit ") < len(hits):
        now = adb("shell", "pidof", PKG).strip()
        pid = pid or now
        if pid and now != pid:
            break
        if time.time() > deadline:
            sys.exit(f"the app did not answer {name} in 120 s")
        time.sleep(0.5)
    adb("shell", "rm", remote)
    return log


def still(name, xml, work, frame=0):
    if f"still {name} ok=true" not in run(name, xml, work, frame=frame):
        sys.exit(f"the app could not render {name}")
    adb("pull", f"{DEVICE_DIR}/{name}.png", work / f"{name}.png")
    adb("shell", "rm", f"{DEVICE_DIR}/{name}.png")
    return np.asarray(Image.open(work / f"{name}.png").convert("RGB")).astype(int)


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/engine-check"
    work.mkdir(parents=True, exist_ok=True)
    results = []
    for name, make in PICTURES.items():
        make(work / name)
        adb("push", work / name, f"{DEVICE_DIR}/{name}")
    for k, (title, (xml, probes, *frame)) in enumerate(CASES.items()):
        image = still(f"enginecheck{k}", xml, work, *frame)
        results.append((title, [f"{xy} is {tuple(int(v) for v in image[xy[1], xy[0]])}, expected {want}" for xy, want in probes
                                if np.abs(image[xy[1], xy[0]] - want).max() > TOLERANCE]))
    for k, (title, (xml, probes)) in enumerate(HITS.items()):
        log = run(f"enginehit{k}", xml, work, [xy for xy, _ in probes])
        adb("shell", "rm", f"{DEVICE_DIR}/enginehit{k}.png")
        got = {line.split(" hit ")[1].split()[0]: int(line.split()[-1]) for line in log.splitlines() if " hit " in line}
        results.append((title, [f"{xy} hits {got.get(f'{xy[0]},{xy[1]}')}, expected {want}" for xy, want in probes
                                if got.get(f"{xy[0]},{xy[1]}") != want]))
    for name in PICTURES:
        adb("shell", "rm", f"{DEVICE_DIR}/{name}")
    video(work / "swap-a.mp4", 10, [(255, 255, 255)] * 20)
    video(work / "swap-b.mp4", 2, [RED, GREEN, BLUE, (255, 255, 0)])
    adb("push", work / "swap-a.mp4", f"{DEVICE_DIR}/swap.mp4")
    still("engineswap0", SWAPPED, work, 6)
    adb("push", work / "swap-b.mp4", f"{DEVICE_DIR}/swap.mp4")
    r, g, b = still("engineswap1", SWAPPED, work, 6)[32, 32]
    adb("shell", "rm", f"{DEVICE_DIR}/swap.mp4")
    results.append(("a video replaced in place uses the new file's frame times",
                    [] if g > 150 and r < 100 and b < 100 else [f"(32, 32) is {(int(r), int(g), int(b))}, expected the green frame at 0.5 s"]))
    limit = adb("shell", "getprop", "dalvik.vm.heapgrowthlimit").strip()
    side = math.isqrt(int(limit.rstrip("m")) * 2 ** 20 * 5 // 16) + 1
    pid = adb("shell", "pidof", PKG).strip()
    log = run("enginebig", scene("", side, side), work)
    alive = adb("shell", "pidof", PKG).strip()
    adb("shell", "rm", "-f", f"{DEVICE_DIR}/enginebig.png")
    results.append((f"a {side} x {side} still that does not fit the {limit} heap fails cleanly",
                    [] if "ok=false" in log and alive == pid else [f"app process {pid} -> {alive or 'gone'}, log: {log.strip().splitlines()[-1:]}"]))
    failed = 0
    for title, bad in results:
        failed += bool(bad)
        print(f"{'FAIL' if bad else 'ok  '} {title}" + "".join(f"\n       {b}" for b in bad))
    print(f"\n{failed} of {len(results)} checks failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
