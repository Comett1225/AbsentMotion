#!/usr/bin/env python3
import argparse
import hashlib
import json
import math
import re
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import effect_thumbs as et

ROOT = et.ROOT
SCENES = ROOT / "testdata/scenes"
MEDIA = ROOT / "testdata/media"
CROP = {"photo": "portrait", "params": {"left": 0.2, "top": 0.1, "right": 0.15, "bottom": 0.1, "radius": 0.3, "feather": 4}}


def synthetic(title, path):
    rng = np.random.default_rng(int(hashlib.sha1(title.encode()).hexdigest()[:8], 16))
    size = 768
    y, x = np.mgrid[0:size, 0:size] / size
    dark = "black background" in title.lower()
    if dark:
        base = np.zeros((size, size, 3))
    else:
        waves = [0.5 + 0.5 * np.sin(6.3 * (x * rng.uniform(0.5, 2) + y * rng.uniform(-1, 1) + rng.uniform(0, 1))) for _ in range(3)]
        base = np.stack(waves, -1) * 0.6 + 0.2
    image = Image.fromarray((base * 255).astype(np.uint8))
    draw = ImageDraw.Draw(image)
    for _ in range(12 if dark else 40):
        cx, cy = rng.uniform(size * (0.25 if dark else 0), size * (0.75 if dark else 1), 2)
        r = rng.uniform(10, 120)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=tuple(int(v) for v in rng.integers(60, 256, 3)))
    if not dark:
        for k in range(0, size, 48):
            draw.line([(k, 0), (k + size // 3, size)], fill=(250, 250, 250), width=2)
        draw.text((24, 24), title[:24], fill=(255, 255, 255))
    pixels = np.asarray(image).astype(float) + (0 if dark else rng.normal(0, 6, (size, size, 3)))
    Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8)).save(path, quality=92)


et.download = synthetic


class Jobs:
    def __init__(self, work):
        self.work, self.lines, self.items, self.pushed, self.made = work, [], [], set(), {}
        (work / "scenes").mkdir(parents=True, exist_ok=True)

    def picture(self, name):
        if name in self.pushed:
            return
        et.photo(name, self.work, self.made).save(self.work / f"{name}.png")
        et.push(self.work / f"{name}.png", f"{name}.png")
        self.pushed.add(name)

    def add(self, group, name, xml, frames):
        path = self.work / "scenes" / f"{name}.xml"
        path.write_text(xml)
        et.push(path, f"{name}.xml")
        for frame in frames:
            out = f"glc-{name}-{frame}"
            self.lines.append(f"{et.APP_PATH}/{name}.xml {frame} {out}")
            self.items.append((group, f"{name}@{frame}", out))


def effects(jobs, keys):
    index = json.loads((et.PACK / "index.json").read_text())
    for key in keys or index:
        show = et.SHOWCASE["effects"].get(key, CROP)
        for name in ([show["photo"]] if "photo" in show else []) + et.backdrops(show):
            jobs.picture(name)
        if "split" in show:
            name = f'{show["photo"]}-before{round(show["split"] * 100)}'
            if name not in jobs.pushed:
                et.before(jobs.made[show["photo"]], show["split"]).save(jobs.work / f"{name}.png")
                et.push(jobs.work / f"{name}.png", f"{name}.png")
                jobs.pushed.add(name)
        animated = show.get("animate", 0)
        frames = sorted({0, animated // 2, animated - 1}) if animated else show.get("frames", [show.get("frame", 0)])
        effect = json.loads((et.PACK / f"{key}.json").read_text())
        known = {p["id"] for p in effect["params"]}
        stale = set(show.get("params", {})) - known
        if stale:
            print(f"{key}: the showcase sets {', '.join(sorted(stale))}, which the effect no longer has; left out", flush=True)
            show = {**show, "params": {k: v for k, v in show["params"].items() if k in known}}
        jobs.add("effect", key, et.scene(show, effect), frames)
        for preset in effect.get("presets", []):
            slug = et.slug(preset["name"])
            sample = {"layout": show["layout"], "text": show.get("presets", {}).get(slug, preset["name"]), "animate": et.PRESET_FRAMES}
            jobs.add("preset", f"{key}.{slug}", et.scene(sample, effect, preset), [et.PRESET_FRAMES // 2])


def scene_frames(xml, parts=(0.25, 0.5, 0.75)):
    fps = int(re.search(r'\bfps="(\d+)"', xml).group(1))
    total = int(re.search(r'totalTime="(\d+)"', xml).group(1))
    return sorted({int(total * p / 1000 * fps) for p in parts})


def testdata(jobs):
    for media in MEDIA.iterdir():
        et.push(media, media.name)
    for path in sorted(SCENES.glob("*.xml")):
        xml = path.read_text().replace('"testdata/media/', f'"file://{et.APP_PATH}/')
        jobs.add("scene", f"scene-{path.stem}", xml, scene_frames(xml))


def generated(jobs, folder):
    for name in ("portrait", "landscape", "colour"):
        jobs.picture(name)
    for path in sorted(Path(folder).glob("*.xml")):
        xml = path.read_text()
        parts = (0.35, 0.5, 0.65) if path.stem.startswith("transition-") else (0.1, 0.3, 0.6)
        jobs.add("motion", path.stem, xml, scene_frames(xml, parts))


def blends(jobs):
    for name in ("colour", "sunset"):
        jobs.picture(name)
    source = (Path(__file__).resolve().parent / "blend_check.py").read_text()
    modes = re.findall(r'^    "([a-z-]+)": ', source.split("MODES = {", 1)[1].split("\n}", 1)[0], re.M)
    c = et.RENDER // 2
    for mode in modes:
        xml = (f'<scene title="blend" width="{et.RENDER}" height="{et.RENDER}" fps="30" totalTime="1000" bgcolor="#ff000000">'
               + et.layer(1, "colour")
               + f'<shape id="2" label="over" startTime="0" endTime="999" s=".rect" fillType="media" mediaFillMode="fill" blending="{mode}" '
               f'fillImage="file://{et.APP_PATH}/sunset.png"><transform><location value="{c},{c},0"/></transform>'
               f'<opacity value="0.85"/><property name="size" type="vec2" value="{c - 24},{c - 24}"/></shape>'
               f'<shape id="3" label="ramp" startTime="0" endTime="999" s=".ellipse" fillType="color" blending="{mode}">'
               f'<transform><location value="{c},{c},0"/></transform><fillColor value="#c0ff8040"/>'
               f'<property name="size" type="vec2" value="{c // 2},{c // 2}"/></shape></scene>')
        jobs.add("blend", f"blend-{mode}", xml, [0])


def render(jobs, gl, out):
    out.mkdir(parents=True, exist_ok=True)
    (jobs.work / "list.txt").write_text("\n".join(jobs.lines) + "\n")
    et.push(jobs.work / "list.txt", "list.txt")
    et.adb("shell", "am", "force-stop", et.PKG)
    et.adb("logcat", "-c")
    et.adb("shell", "am", "start", "-n", f"{et.PKG}/app.absentmotion.MainActivity", "--es", "batch", f"{et.APP_PATH}/list.txt", "--ez", "gl", "true" if gl else "false")
    deadline = time.time() + 120 + 15 * len(jobs.lines)
    while "batch done" not in (log := et.adb("logcat", "-d", "-s", "AMTest")):
        if time.time() > deadline:
            sys.exit(f"the app did not finish {len(jobs.lines)} stills in time")
        time.sleep(2)
    engine = next((line for line in et.adb("logcat", "-d", "-s", "AMEngine").splitlines() if "engine ready" in line), "")
    backend = re.search(r'"backend":"(\w+)"', engine)
    if not backend or (backend.group(1) == "gl") != gl:
        sys.exit(f"wanted {'OpenGL' if gl else 'Vulkan'}, the engine says: {engine[-160:]}")
    missing = []
    for _, _, name in jobs.items:
        if f"still {name} ok=true" not in log:
            missing.append(name)
            continue
        et.adb("pull", f"{et.OUT_DIR}/{name}.png", out / f"{name}.png")
        et.adb("shell", "rm", f"{et.OUT_DIR}/{name}.png")
    return backend.group(1), missing


def score(a, b):
    a = np.asarray(Image.open(a).convert("RGBA")).astype(float)
    b = np.asarray(Image.open(b).convert("RGBA")).astype(float)
    if a.shape != b.shape:
        return None
    d = np.abs(a - b)
    mse = float((d ** 2).mean())
    return {"mean": round(float(d.mean()), 3), "p99": round(float(np.percentile(d.max(-1), 99)), 1), "peak": int(d.max()),
            "psnr": round(99.0 if mse == 0 else 10 * math.log10(255 ** 2 / mse), 2)}


def sheet(rows, left, right, path, size=192):
    if not rows:
        return
    canvas = Image.new("RGB", (size * 3 + 40, (size + 24) * len(rows)), (24, 24, 28))
    draw = ImageDraw.Draw(canvas)
    for k, row in enumerate(rows):
        a = Image.open(left / f"{row['out']}.png").convert("RGB")
        b = Image.open(right / f"{row['out']}.png").convert("RGB")
        d = np.clip(np.abs(np.asarray(a).astype(int) - np.asarray(b).astype(int)) * 4, 0, 255).astype(np.uint8)
        y = k * (size + 24)
        for i, image in enumerate((a, b, Image.fromarray(d))):
            canvas.paste(image.resize((size, size)), (i * (size + 20), y + 20))
        draw.text((4, y + 4), f"{row['name']}  mean {row['mean']}  p99 {row['p99']}  psnr {row['psnr']}", fill=(230, 230, 230))
    canvas.save(path)


def main():
    parser = argparse.ArgumentParser(description="Renders effects, presets, test scenes, motions and blend modes on ANDROID_SERIAL "
                                     "(debug build, a device with Vulkan) once with Vulkan and once with OpenGL, and compares them")
    parser.add_argument("--work", type=Path, required=True, help="folder for the renders and the report, outside the repository")
    parser.add_argument("--motions", type=Path, help="folder of extra scene XML files, such as generated transitions and animations")
    parser.add_argument("--groups", default="effect,preset,scene,motion,blend")
    parser.add_argument("--mean", type=float, default=1.0, help="flag pairs whose mean difference is above this")
    parser.add_argument("--p99", type=float, default=24.0, help="flag pairs whose 99th percentile difference is above this")
    parser.add_argument("--worst", type=int, default=24)
    parser.add_argument("keys", nargs="*", help="only these effects")
    opts = parser.parse_args()
    groups = set(opts.groups.split(","))
    opts.work.mkdir(parents=True, exist_ok=True)
    et.adb("shell", "am", "force-stop", et.PKG)
    et.adb("shell", "run-as", et.PKG, "mkdir", "-p", et.APP_DIR)
    jobs = Jobs(opts.work)
    if "effect" in groups or "preset" in groups:
        effects(jobs, opts.keys)
    if "scene" in groups:
        testdata(jobs)
    if "motion" in groups and opts.motions:
        generated(jobs, opts.motions)
    if "blend" in groups:
        blends(jobs)
    jobs.items = [item for item in jobs.items if item[0] in groups]
    jobs.lines = [line for line in jobs.lines if line.split()[2] in {out for _, _, out in jobs.items}]
    print(f"{len(jobs.items)} stills per back end", flush=True)
    vk, gl = opts.work / "vulkan", opts.work / "gl"
    first, lost_vk = render(jobs, False, vk)
    second, lost_gl = render(jobs, True, gl)
    rows = []
    for group, name, out in jobs.items:
        if out in lost_vk or out in lost_gl:
            rows.append({"group": group, "name": name, "out": out, "missing": [b for b, lost in (("vulkan", lost_vk), ("gl", lost_gl)) if out in lost]})
            continue
        result = score(vk / f"{out}.png", gl / f"{out}.png")
        rows.append({"group": group, "name": name, "out": out, **(result or {"size": "differs"})})
    flagged = [r for r in rows if "missing" in r or "size" in r or r["mean"] > opts.mean or r["p99"] > opts.p99]
    (opts.work / "report.json").write_text(json.dumps({"vulkan": first, "gl": second, "rows": rows}, indent=1))
    worst = sorted((r for r in rows if "mean" in r), key=lambda r: -r["mean"])[:opts.worst]
    sheet(worst, vk, gl, opts.work / "worst.png")
    for group in sorted(groups):
        mine = [r for r in rows if r["group"] == group]
        if mine:
            print(f"{group:7} {len(mine):4} stills, {sum(r in flagged for r in mine):3} flagged")
    for r in flagged:
        print("FLAG", r["name"], {k: v for k, v in r.items() if k not in ("group", "name", "out")})
    print(f"{len(flagged)} of {len(rows)} differ; report {opts.work / 'report.json'}, worst cases {opts.work / 'worst.png'}")
    sys.exit(1 if flagged else 0)


if __name__ == "__main__":
    main()
