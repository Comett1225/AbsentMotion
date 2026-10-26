#!/usr/bin/env python3
import argparse
import json
import statistics
import subprocess
import sys
import tarfile
import io
from pathlib import Path
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[2]
EFFECTS = ROOT / "app/src/main/assets/effects"
SIZE, FPS, LENGTH = 1080, 30, 3999
FRAMES = [0, 1, 15, 30, 45, 60, 75, 90, 105, 119]


def effect_xml(key):
    d = json.loads((EFFECTS / f"{key}.json").read_text())
    attrs = f'id="absent.{key}"' + (f" sksl={quoteattr(d['sksl'])}" if d.get("sksl") and not d.get("native") else "")
    params = []
    for p in d.get("params", []):
        v = p.get("legacyDefault", p.get("default"))
        if p["type"] == "switch":
            v = "true" if v in (True, 1) else "false"
        elif p["type"] == "point":
            v = ",".join("%.6f" % x for x in v)
        elif p["type"] == "color":
            v = v.lower() if len(v) == 9 else "#ff" + v[1:].lower()
        elif p["type"] == "text":
            v = str(v)
        else:
            v = "%.6f" % v
        params.append(f"<param name={quoteattr(p['id'])} value={quoteattr(v)}/>")
    return (f"<effect {attrs}>" + "".join(params) + "</effect>").replace("\n", "&#10;")


def fx(key, **values):
    props = "".join(f'<property name="{k}" type="float" value="{v}"/>' for k, v in values.items())
    return f'<effect id="absent.{key}">{props}</effect>'


def anim(tag, value=None, keys=None):
    if keys:
        return f"<{tag}>" + "".join(f'<kf t="{t:.6f}" v="{v}"/>' for t, v in keys) + f"</{tag}>"
    return f'<{tag} value="{value}"/>'


def shape(i, x, y, *, w=220, h=160, s=".roundrect", media=False, color="#ffd04a6a", fill=None, location=None, extra="", effects=(),
          parent=0, tilt=None, rotation=None, start=0, end=LENGTH):
    fill = f'fillType="media" fillImage="synthetic" mediaFillMode="fill"' if media else 'fillType="color" mediaFillMode="fill"'
    transform = location or anim("location", f"{x},{y},0")
    if tilt:
        transform += anim("tilt", tilt)
    if rotation:
        transform += rotation
    attrs = f' parent="{parent}"' if parent else ""
    return (f'<shape id="{i}" label="l{i}" startTime="{start}" endTime="{end}" {fill} s="{s}"{attrs}><transform>{transform}</transform>'
            f'{fill or anim("fillColor", color)}<property name="size" type="vec2" value="{w},{h}"/>'
            f'<property name="cornerRadius" type="float" value="18"/>{extra}{"".join(effects)}</shape>')


def scene(layers, title):
    return (f'<?xml version="1.0" encoding="UTF-8"?><scene title="{title}" width="{SIZE}" height="{SIZE}" bgcolor="#ff101418" '
            f'totalTime="{LENGTH}" fps="{FPS}">' + "".join(layers) + "</scene>")


def grid(n):
    return [(200 + (k % 3) * 340, 220 + (k // 3) * 320) for k in range(n)]


def scenes():
    heavy = [fx("tiles", zoom=2), fx("glow", radius=60), fx("huesaturation", hue=30), fx("vignette")]
    blur = fx("motionblur")
    out = {}
    out["steady_effects"] = scene([shape(10 + k, x, y, media=k % 2 == 0, effects=[heavy[k % 4], heavy[(k + 1) % 4], blur])
                                   for k, (x, y) in enumerate(grid(6))], "steady")

    def hold(k, x, y):
        return anim("location", keys=[(0, f"{x - 150},{y},0"), (0.35, f"{x},{y},0"), (1, f"{x},{y},0")])
    out["hold_motion"] = scene([shape(20 + k, x, y, media=k % 2 == 1, location=hold(k, x, y), effects=[heavy[k % 4], blur])
                                for k, (x, y) in enumerate(grid(6))], "hold")

    def moving(k, x, y):
        return anim("location", keys=[(0, f"{x - 120},{y - 40},0"), (1, f"{x + 120},{y + 40},0")])
    out["moving_motion"] = scene([shape(30 + k, x, y, media=k % 2 == 0, location=moving(k, x, y), effects=[heavy[k % 4], blur])
                                  for k, (x, y) in enumerate(grid(6))], "moving")
    varying = [
        shape(40, 200, 220, fill=anim("fillColor", keys=[(0, "#ffff0000"), (1, "#ff0000ff")]), effects=[fx("glow", radius=40), blur]),
        shape(41, 540, 220, media=True, effects=[fx("noise", amount=0.5), blur]),
        shape(42, 880, 220, effects=[fx("pulseopacity"), fx("tiles", zoom=2), blur]),
        '<nullobj id="49" label="p" startTime="0" endTime="3999"><transform>'
        + anim("location", keys=[(0, "300,500,0"), (1, "700,700,0")]) + "</transform></nullobj>",
        shape(43, 0, 0, media=True, parent=49, effects=[fx("huesaturation"), blur]),
        shape(44, 540, 880, effects=[fx("spin", rpm=20), fx("vignette"), blur]),
        shape(45, 880, 880, media=True, effects=[fx("filmgrain"), blur]),
    ]
    out["varying_content"] = scene(varying, "varying")
    group_children = "".join(shape(60 + k, 150 + 220 * k, 300, effects=[fx("glow", radius=30), blur]) for k in range(3))
    group = (f'<embedScene id="59" label="g" startTime="0" endTime="{LENGTH}"><transform>{anim("location", "540,760,0")}</transform>'
             f'<scene width="800" height="600" fps="{FPS}" totalTime="{LENGTH}">{group_children}</scene>'
             f'{fx("motionblur")}</embedScene>')
    out["tilt_group"] = scene([shape(50, 300, 260, media=True, tilt="20,10", effects=[fx("glow", radius=40), blur]),
                               shape(51, 760, 260, tilt="0,25", effects=[fx("tiles", zoom=2), blur]), group], "tilt")
    out["shutter_echo"] = scene([shape(70, 300, 300, media=True, effects=[fx("tiles", zoom=2), fx("shutterblur", samples=12)]),
                                 shape(71, 760, 300, effects=[fx("glow", radius=50), fx("echo", count=3, seconds=0.3), blur]),
                                 shape(72, 540, 760, media=True, start=500, end=2500, effects=[fx("huesaturation"), blur])], "shutter")
    return out


def effect_keys(text):
    import re
    return sorted(set(re.findall(r'effect id="absent\.([a-z0-9]+)"', text)))


def build(source, out):
    subprocess.run(["sh", str(ROOT / "tools/render_bench/build.sh"), str(source), str(out)], check=True)


def head_sources(rev, work):
    dest = work / f"src-{rev}"
    if not (dest / "app/src/main/cpp").exists():
        data = subprocess.run(["git", "-C", str(ROOT), "archive", rev, "app/src/main/cpp"], check=True, capture_output=True).stdout
        tarfile.open(fileobj=io.BytesIO(data)).extractall(dest)
    return dest / "app/src/main/cpp"


def run(binary, effects, scene_file, scale, runs, outdir):
    outdir.mkdir(parents=True, exist_ok=True)
    ms = [str(f * 1000.0 / FPS) for f in FRAMES]
    p = subprocess.run([str(binary), str(effects), str(scene_file), str(scale), str(runs), str(outdir)] + ms,
                       capture_output=True, text=True, check=True)
    if p.stderr.strip():
        print(p.stderr.strip(), file=sys.stderr)
    return [json.loads(line) for line in p.stdout.splitlines() if line.startswith("{")]


def summary(results):
    cold = sum(results[0]["ms"])
    warm = [sum(r["ms"]) for r in results[1:]]
    per = sorted(x for r in results[1:] for x in r["ms"])
    p95 = per[min(len(per) - 1, int(round(0.95 * (len(per) - 1))))]
    return {"cold_ms": round(cold, 2), "warm_ms": [round(w, 2) for w in warm], "warm_median_ms": round(statistics.median(warm), 2),
            "warm_frame_p95_ms": round(p95, 2), "breaks": results[-1]["breaks"]}


def compare(a, b, width, height):
    import numpy as np
    worst, changed, exact = 0, 0, 0
    for f in range(len(FRAMES)):
        x = np.fromfile(a / f"frame{f}.rgba", dtype=np.uint8).astype(np.int16)
        y = np.fromfile(b / f"frame{f}.rgba", dtype=np.uint8).astype(np.int16)
        d = np.abs(x - y)
        worst = max(worst, int(d.max()))
        changed += int((d > 0).sum())
        exact += int(d.max() == 0)
    return {"max_channel_diff": worst, "changed_channels": changed, "identical_frames": exact, "frames": len(FRAMES),
            "channels": width * height * 4 * len(FRAMES)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=str(ROOT / "build/render_bench"))
    ap.add_argument("--base", default="HEAD")
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--only", default="")
    ap.add_argument("--report", default="")
    ap.add_argument("--no-build", action="store_true")
    args = ap.parse_args()
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    base_bin, cur_bin = work / "base/render_bench", work / "current/render_bench"
    if not args.no_build:
        build(head_sources(args.base, work), work / "base")
        build(ROOT / "app/src/main/cpp", work / "current")
    report = {"base": args.base, "scale": args.scale, "runs": args.runs, "frames": FRAMES, "scenes": {}}
    for name, text in scenes().items():
        if args.only and name not in args.only.split(","):
            continue
        (work / "scenes").mkdir(exist_ok=True)
        scene_file = work / "scenes" / f"{name}.xml"
        scene_file.write_text(text)
        effects = work / "scenes" / f"{name}.effects"
        effects.write_text("\n".join(effect_xml(k) for k in effect_keys(text)) + "\n")
        base = run(base_bin, effects, scene_file, args.scale, args.runs, work / "out" / name / "base")
        cur = run(cur_bin, effects, scene_file, args.scale, args.runs, work / "out" / name / "current")
        entry = {"base": summary(base), "current": summary(cur),
                 "pixels": compare(work / "out" / name / "base", work / "out" / name / "current", base[0]["width"], base[0]["height"])}
        entry["speedup"] = round(entry["base"]["warm_median_ms"] / max(entry["current"]["warm_median_ms"], 1e-9), 2)
        report["scenes"][name] = entry
        print(name, json.dumps(entry))
    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=1) + "\n")


if __name__ == "__main__":
    main()
