#!/usr/bin/env python3
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

import engine_check as engine

W, H = 400, 200


def fx(name, **params):
    props = []
    for key, value in params.items():
        if isinstance(value, list):
            keys = "".join(f'<kf t="{k / (len(value) - 1):.6f}" v="{v}"/>' for k, v in enumerate(value))
            props.append(f'<property name="{key}" type="float">{keys}</property>')
        elif isinstance(value, tuple):
            props.append(f'<property name="{key}" type="vec2" value="{value[0]},{value[1]}"/>')
        elif isinstance(value, str):
            kind = "color" if value.startswith("#") else "string"
            props.append(f'<property name="{key}" type="{kind}" value="{value}"/>')
        else:
            props.append(f'<property name="{key}" type="float" value="{value}"/>')
    return f'<effect id="absent.{name}" hidden="false">' + "".join(props) + "</effect>"


def text(content, effect="", size=14, width=380, align="center", fill="color", y=100, end=999):
    paint = ('<gradient type="linear" startColor="#ffff0000" endColor="#ff0000ff" start="0,0.5" end="1,0.5"/>'
             if fill == "gradient" else '<fillColor value="#ffffffff"/>')
    return (f'<text id="7" label="t" fillType="{fill}" startTime="0" endTime="{end}" size="{size}" wrapWidth="{width}" align="{align}">'
            + engine.transform(f"{W // 2},{y},0") + f"<content>{content}</content>{paint}{effect}</text>")


def sketch(effect="", tool="pen"):
    line = ";".join(f"{x},0,1" for x in range(-150, 151, 10))
    return (f'<drawing id="8" label="d" startTime="0" endTime="999">' + engine.transform(f"{W // 2},{H // 2},0")
            + f'<stroke color="#ffffffff" width="20" type="{tool}" points="{line}"/>{effect}</drawing>')


def line_shape(effect=""):
    return ('<shape id="9" label="l" startTime="0" endTime="999" fillType="none" mediaFillMode="fill">' + engine.transform(f"{W // 2},{H // 2},0")
            + '<path d="M -150 0 L 150 0"/><path-stroke><color value="#ffffffff"/><size value="20"/></path-stroke>' + effect + "</shape>")


def scene(body, fps=30, total=1000):
    return (f'<scene title="textcheck" width="{W}" height="{H}" fps="{fps}" totalTime="{total}" bgcolor="#ff000000">'
            + body + "</scene>")


class Batch:
    def __init__(self, work):
        self.work, self.jobs, self.images = work, [], {}

    def add(self, name, xml, frame=0):
        (self.work / f"{name}.xml").write_text(xml)
        engine.adb("push", self.work / f"{name}.xml", f"{engine.DEVICE_DIR}/{name}.xml")
        self.jobs.append((name, frame))
        return name

    def run(self):
        (self.work / "list.txt").write_text("".join(f"{engine.DEVICE_DIR}/{n}.xml {f} {n}\n" for n, f in self.jobs))
        engine.adb("push", self.work / "list.txt", f"{engine.DEVICE_DIR}/textlist.txt")
        engine.adb("shell", "am", "force-stop", engine.PKG)
        engine.adb("logcat", "-c")
        engine.adb("shell", "am", "start", "-n", f"{engine.PKG}/.MainActivity", "--es", "batch", f"{engine.DEVICE_DIR}/textlist.txt")
        deadline = time.time() + 60 + 10 * len(self.jobs)
        while "batch done" not in (log := engine.adb("logcat", "-d", "-s", "AMTest")):
            if time.time() > deadline:
                sys.exit(f"the app did not finish {len(self.jobs)} stills in time")
            time.sleep(1)
        for name, _ in self.jobs:
            if f"still {name} ok=true" not in log:
                sys.exit(f"{name}: the app could not render the still")
            engine.adb("pull", f"{engine.DEVICE_DIR}/{name}.png", self.work / f"{name}.png")
            engine.adb("shell", "rm", f"{engine.DEVICE_DIR}/{name}.png", f"{engine.DEVICE_DIR}/{name}.xml")
            self.images[name] = np.asarray(Image.open(self.work / f"{name}.png").convert("RGB")).astype(int)
        engine.adb("shell", "rm", f"{engine.DEVICE_DIR}/textlist.txt")

    def __getitem__(self, name):
        return self.images[name]


def ink(image, level=60):
    return image.max(axis=2) > level


def columns(image):
    cols = np.nonzero(ink(image).any(axis=0))[0]
    return (int(cols[0]), int(cols[-1])) if len(cols) else None


def rows(image):
    found = np.nonzero(ink(image).any(axis=1))[0]
    return (int(found[0]), int(found[-1])) if len(found) else None


def diff(a, b):
    return int(np.abs(a - b).max())


def same_text(a, b):
    ea, eb = columns(a) + rows(a), columns(b) + rows(b)
    return max(abs(x - y) for x, y in zip(ea, eb)) <= 1 and abs(a.sum() - b.sum()) <= 0.02 * b.sum()


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else engine.ROOT / "build/text-effect-check"
    work.mkdir(parents=True, exist_ok=True)
    b = Batch(work)
    hello = "Hello wide world"
    b.add("plain", scene(text(hello)))
    for p in (0, 0.25, 0.5, 0.75, 1):
        b.add(f"progress{int(p * 100)}", scene(text(hello, fx("textprogress", progress=p, unit=0, appearance=1))))
    b.add("progress50again", scene(text(hello, fx("textprogress", progress=0.5, unit=0, appearance=1))))
    b.add("progressend50", scene(text(hello, fx("textprogress", progress=0.5, direction=1, appearance=0))))
    b.add("progresscentre50", scene(text(hello, fx("textprogress", progress=0.5, direction=2, appearance=0))))
    b.add("progressrandom50", scene(text(hello, fx("textprogress", progress=0.5, direction=3, appearance=0, seed=3))))
    b.add("progressrandom50again", scene(text(hello, fx("textprogress", progress=0.5, direction=3, appearance=0, seed=3))))
    b.add("progressword", scene(text(hello, fx("textprogress", progress=0.3, unit=1, appearance=0))))
    for k, appearance in enumerate(range(9)):
        b.add(f"appear{appearance}", scene(text(hello, fx("textprogress", progress=0.5, appearance=appearance, softness=4, distance=40))))
    b.add("keyed0", scene(text(hello, fx("textprogress", progress=[0, 1])), total=1000), 0)
    b.add("keyed29", scene(text(hello, fx("textprogress", progress=[0, 1, 1])), total=1000), 29)
    b.add("lines", scene(text("one\ntwo\nthree", fx("textprogress", progress=0.5, unit=2, appearance=0), size=8)))
    b.add("linesplain", scene(text("one\ntwo\nthree", size=8)))
    b.add("emoji", scene(text("Hi \U0001F44D\U0001F3FD \U0001F1EC\U0001F1E7 é", fx("textprogress", progress=0.6, appearance=6))))
    b.add("rtlplain", scene(text("مرحبا بك")))
    b.add("rtl", scene(text("مرحبا بك", fx("textprogress", progress=0.5, appearance=0))))
    b.add("gradientplain", scene(text(hello, fill="gradient")))
    b.add("gradient50", scene(text(hello, fx("textprogress", progress=0.5, appearance=0), fill="gradient")))
    b.add("moved", scene(text(hello, fx("texttransform", position=(0, -50)))))
    b.add("movedplain", scene(text(hello, y=50)))
    b.add("rangehalf", scene(text(hello, fx("texttransform", start=0, end=0.5, opacity=0))))
    b.add("rangeoffset", scene(text(hello, fx("texttransform", start=0, end=0.5, offset=0.5, opacity=0))))
    b.add("tint", scene(text(hello, fx("texttransform", tint="#ffff0000"))))
    b.add("spin", scene(text(hello, fx("texttransform", rotation=90, shape=3))))
    b.add("random", scene(text(hello, fx("texttransform", randomOrder=1, end=0.5, opacity=0, seed=2))))
    b.add("random2", scene(text(hello, fx("texttransform", randomOrder=1, end=0.5, opacity=0, seed=2))))
    b.add("scramble0", scene(text(hello, fx("textrandomizer", progress=0))))
    b.add("scramble0again", scene(text(hello, fx("textrandomizer", progress=0))))
    b.add("scramble0later", scene(text(hello, fx("textrandomizer", progress=0))), 15)
    b.add("scramble1", scene(text(hello, fx("textrandomizer", progress=1))))
    b.add("count", scene(text("Score 1,000", fx("countupdown", multiply=0.5, separator=1))))
    b.add("countplain", scene(text("Score 500")))
    b.add("countkeyed", scene(text("1000", fx("countupdown", multiply=[0, 1]))), 15)
    b.add("timecode", scene(text("x", fx("timecode", format=0), end=2999), fps=30, total=3000), 45)
    b.add("timecodeplain", scene(text("00:00:01:15", end=2999), fps=30, total=3000))
    b.add("countdown", scene(text("x", fx("timecode", format=2, offset=10, speed=-1), end=2999), fps=30, total=3000), 60)
    b.add("countdownplain", scene(text("00:08", end=2999), fps=30, total=3000))
    b.add("sketch", scene(sketch()))
    b.add("sketchhalf", scene(sketch(fx("drawingprogress", end=0.5))))
    b.add("sketchnone", scene(sketch(fx("drawingprogress", end=0))))
    b.add("sketchmiddle", scene(sketch(fx("drawingprogress", start=0.25, end=0.75))))
    b.add("brushhalf", scene(sketch(fx("drawingprogress", end=0.5), tool="brush")))
    b.add("linehalf", scene(line_shape(fx("drawingprogress", end=0.5))))
    b.add("line", scene(line_shape()))
    b.add("taper", scene(sketch(fx("stroketaper", start=0.5, end=0.5))))
    b.add("taperhard", scene(sketch(fx("stroketaper", start=0.5, end=0.5, squeeze=100))))
    b.run()

    results = []

    def check(title, ok, detail=""):
        results.append((title, ok))
        print(("ok   " if ok else "FAIL ") + title + (f" ({detail})" if detail and not ok else ""), flush=True)

    plain = b["plain"]
    full = columns(plain)
    check("plain text draws", full is not None)
    check("progress 0 shows nothing", not ink(b["progress0"]).any())
    check("progress 100 matches the plain text", diff(b["progress100"], plain) <= 8, f"max diff {diff(b['progress100'], plain)}")
    shown = [columns(b[f"progress{p}"]) for p in (25, 50, 75)]
    check("progress from start grows to the right", all(s and s[0] <= full[0] + 2 for s in shown) and shown[0][1] < shown[1][1] < shown[2][1] < full[1],
          f"{shown} of {full}")
    check("repeated renders are identical", diff(b["progress50"], b["progress50again"]) == 0)
    end = columns(b["progressend50"])
    check("progress from end shows the right side", end and end[1] >= full[1] - 2 and end[0] > full[0] + 20, f"{end}")
    centre = columns(b["progresscentre50"])
    check("progress from centre shows the middle", centre and centre[0] > full[0] + 20 and centre[1] < full[1] - 20, f"{centre}")
    check("random order repeats for a seed", diff(b["progressrandom50"], b["progressrandom50again"]) == 0)
    check("random order differs from start order", diff(b["progressrandom50"], b["progressend50"]) > 50)
    word = columns(b["progressword"])
    check("one word of three shows", word and word[1] < full[0] + (full[1] - full[0]) * 0.45, f"{word} of {full}")
    looks = [b[f"appear{k}"] for k in range(9)]
    distinct = sum(1 for i in range(9) for j in range(i + 1, 9) if diff(looks[i], looks[j]) > 30)
    check("every appearance looks different", distinct == 36, f"{distinct} of 36 pairs differ")
    check("blur appearance softens the edge", ink(looks[8], 5).sum() > ink(looks[1], 5).sum())
    check("keyframed progress starts empty and ends full", not ink(b["keyed0"]).any() and diff(b["keyed29"], plain) <= 8)
    lines, lines_plain = rows(b["lines"]), rows(b["linesplain"])
    check("line progress shows the first lines only", lines and lines[0] == lines_plain[0] and lines[1] < lines_plain[1] - 10, f"{lines} of {lines_plain}")
    check("emoji, flags and accents draw", ink(b["emoji"]).any())
    rtl, rtl_plain = columns(b["rtl"]), columns(b["rtlplain"])
    check("right-to-left text starts on the right", rtl and rtl[1] >= rtl_plain[1] - 2 and rtl[0] > rtl_plain[0] + 10, f"{rtl} of {rtl_plain}")
    g, gp = b["gradient50"], b["gradientplain"]
    gcols = columns(g)
    check("gradient text reveals with its own colours", gcols and gcols[1] < columns(gp)[1] - 20 and g[ink(g)].max(axis=0)[0] > 150)
    moved, moved_plain = rows(b["moved"]), rows(b["movedplain"])
    check("transform position moves every letter", moved and abs(moved[0] - moved_plain[0]) <= 1 and abs(moved[1] - moved_plain[1]) <= 1, f"{moved} vs {moved_plain}")
    half, offset = columns(b["rangehalf"]), columns(b["rangeoffset"])
    check("range start/end selects the first half", half and half[0] > full[0] + 40, f"{half}")
    check("range offset moves the selection", offset and offset[1] < full[1] - 40 and offset[0] <= full[0] + 2, f"{offset}")
    tint = b["tint"]
    check("tint colours the letters", tint[ink(tint)][:, 0].mean() > 200 and tint[ink(tint)][:, 2].mean() < 40)
    check("rotation turns the letters", diff(b["spin"], plain) > 100)
    check("random order is repeatable", diff(b["random"], b["random2"]) == 0 and diff(b["random"], b["rangehalf"]) > 50)
    check("scramble changes the letters", diff(b["scramble0"], plain) > 100)
    check("scramble repeats at the same time", diff(b["scramble0"], b["scramble0again"]) == 0)
    check("scramble changes over time", diff(b["scramble0"], b["scramble0later"]) > 100)
    check("settled scramble is the text", diff(b["scramble1"], plain) <= 8, f"max diff {diff(b['scramble1'], plain)}")
    check("count multiplies numbers and groups thousands", same_text(b["count"], b["countplain"]))
    check("keyframed count is part way", diff(b["countkeyed"], plain) > 0 and ink(b["countkeyed"]).any())
    check("timecode shows the layer time", same_text(b["timecode"], b["timecodeplain"]))
    check("a timecode counts down from an offset", same_text(b["countdown"], b["countdownplain"]))
    sk, half, middle = columns(b["sketch"]), columns(b["sketchhalf"]), columns(b["sketchmiddle"])
    check("drawing progress draws the first half of a stroke", half and abs(half[0] - sk[0]) <= 2 and abs(half[1] - (sk[0] + sk[1]) / 2) <= 12, f"{half} of {sk}")
    check("drawing progress at 0 draws nothing", not ink(b["sketchnone"]).any())
    check("drawing progress start and end cut both ends", middle and middle[0] > sk[0] + 50 and middle[1] < sk[1] - 50, f"{middle} of {sk}")
    brush = columns(b["brushhalf"])
    check("brush strokes are cut too", brush and brush[1] < sk[1] - 100, f"{brush}")
    line, lhalf = columns(b["line"]), columns(b["linehalf"])
    check("drawing progress trims a shape outline", lhalf and abs(lhalf[0] - line[0]) <= 2 and lhalf[1] < line[1] - 120, f"{lhalf} of {line}")
    taper, hard = b["taper"], b["taperhard"]
    ends = lambda image: int(ink(image)[:, sk[0]:sk[0] + 20].sum())
    check("stroke taper thins the ends", ends(taper) < ends(b["sketch"]) / 2 and rows(taper)[1] - rows(taper)[0] >= rows(b["sketch"])[1] - rows(b["sketch"])[0] - 2,
          f"ends {ends(taper)} vs {ends(b['sketch'])}")
    check("squeeze sharpens the taper", ink(hard).sum() < ink(taper).sum())
    failed = [t for t, ok in results if not ok]
    print(f"\n{len(results) - len(failed)} of {len(results)} text effect checks passed", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
