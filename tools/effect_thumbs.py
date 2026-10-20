#!/usr/bin/env python3
import argparse
import html
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "app/src/main/assets/effects"
SHOWCASE = json.loads((ROOT / "tools/effect_showcase.json").read_text())
ADB = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", os.environ.get("ANDROID_SERIAL") or "emulator-5554"]
PKG = "app.absentmotion"
APP_DIR = "files/effect-thumbs"
APP_PATH = f"/data/user/0/{PKG}/{APP_DIR}"
OUT_DIR = f"/sdcard/Android/data/{PKG}/files"
RENDER, THUMB, SAME = 384, 192, 2.0
ANIM_FPS, ANIM_BYTES = 12, 150_000
FREE = {"cc0", "pd"}
TYPES = {"point": "vec2", "color": "color", "switch": "bool", "text": "string"}
UA = {"User-Agent": "AbsentMotion-effect-thumbs/1.0"}


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], check=True, capture_output=True, text=True).stdout


def push(local, name):
    adb("push", local, f"/data/local/tmp/am-{name}")
    adb("shell", "run-as", PKG, "cp", f"/data/local/tmp/am-{name}", f"{APP_DIR}/{name}")
    adb("shell", "rm", f"/data/local/tmp/am-{name}")


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()


def download(title, path):
    query = urllib.parse.urlencode({"action": "query", "titles": title, "prop": "imageinfo", "iiprop": "url|extmetadata",
                                    "iiurlwidth": 1024, "format": "json", "formatversion": 2})
    info = json.loads(fetch("https://commons.wikimedia.org/w/api.php?" + query))["query"]["pages"][0]["imageinfo"][0]
    licence = info["extmetadata"].get("License", {}).get("value", "")
    if licence not in FREE:
        sys.exit(f"{title}: licence '{licence}', only CC0 or public domain photos may be used")
    path.write_bytes(fetch(info["thumburl"]))
    image = Image.open(path).convert("RGB")
    image.thumbnail((1024, 1024), Image.LANCZOS)
    image.save(path, quality=92)


def photo(name, work, made):
    if name in made:
        return made[name]
    spec = SHOWCASE["photos"][name]
    if "grid" in spec:
        made[name] = Image.new("RGB", (RENDER, RENDER), (38, 40, 50))
        draw = ImageDraw.Draw(made[name])
        for k in range(0, RENDER + 1, spec["grid"]):
            draw.line([(k, 0), (k, RENDER)], fill=(78, 82, 98), width=2)
            draw.line([(0, k), (RENDER, k)], fill=(78, 82, 98), width=2)
        return made[name]
    if "from" in spec:
        base = photo(spec["from"], work, made)
        lo, hi = spec["cut"]
        mask = base.convert("L").point(lambda v: max(0, min(255, round((v / 255 - lo) / (hi - lo) * 255))))
        made[name] = Image.composite(base, Image.new("RGB", base.size, spec["backdrop"]), mask)
        return made[name]
    source = work / f"source-{name}.jpg"
    if not source.exists():
        download(spec["commons"], source)
    image = Image.open(source).convert("RGB")
    cx, cy, side = spec["crop"]
    s = side * min(image.size)
    x = min(max(cx * image.width - s / 2, 0), image.width - s)
    y = min(max(cy * image.height - s / 2, 0), image.height - s)
    made[name] = image.resize((RENDER, RENDER), Image.LANCZOS, box=(x, y, x + s, y + s))
    return made[name]


def before(image, at):
    over = image.convert("RGBA")
    x = round(at * RENDER)
    draw = ImageDraw.Draw(over)
    draw.rectangle([x, 0, RENDER, RENDER], fill=(0, 0, 0, 0))
    draw.rectangle([x - 2, 0, x - 1, RENDER], fill=(255, 255, 255, 255))
    return over


def value(kind, v):
    if kind == "point":
        return ",".join(f"{float(x):.6f}" for x in v)
    if kind == "color":
        c = v.lower()
        return "#ff" + c[1:] if len(c) == 7 else c
    if kind == "switch":
        return "true" if v is True or v == 1 else "false"
    if kind == "text":
        return html.escape(v)
    return f"{float(v):.6f}"


def prop(p, v):
    kind = TYPES.get(p["type"], "float")
    if isinstance(v, list) and p["type"] in ("float", "angle"):
        keys = "".join(f'<kf t="{k / (len(v) - 1):.6f}" v="{value(p["type"], x)}"/>' for k, x in enumerate(v))
        return f'<property name="{p["id"]}" type="{kind}">{keys}</property>'
    return f'<property name="{p["id"]}" type="{kind}" value="{value(p["type"], v)}"/>'


def layer(id, image, effect="", half=RENDER // 2, keys=None, end=999):
    c = RENDER // 2
    location = (f'<location value="{c},{c},0"/>' if not keys else
                "<location>" + "".join(f'<kf t="{k / (len(keys) - 1):.6f}" v="{c + x},{c + y},0"/>' for k, (x, y) in enumerate(keys)) + "</location>")
    return (f'<shape id="{id}" label="{image}" startTime="0" endTime="{end}" s=".rect" fillType="media" mediaFillMode="fill" '
            f'fillImage="file://{APP_PATH}/{image}.png"><transform>{location}</transform>'
            f'<property name="size" type="vec2" value="{half},{half}"/>{effect}</shape>')


def words(id, content, effect="", end=999):
    c = RENDER // 2
    return (f'<text id="{id}" label="text" startTime="0" endTime="{end}" fillType="color" size="18" wrapWidth="{RENDER - 48}" '
            f'align="center" font="googlefonts?name=Montserrat&amp;weight=700"><transform><location value="{c},{c},0"/></transform>'
            f'<fillColor value="#ffffffff"/><content>{content}</content>{effect}</text>')


def backdrops(show):
    return ["grid"] if show.get("layout") in ("move", "text") else [show["under"]] if "under" in show else []


def scene(show, effect):
    fx = ""
    if effect:
        params = {p["id"]: p["default"] for p in effect["params"]}
        unknown = set(show.get("params", {})) - set(params)
        if unknown:
            sys.exit(f"{effect['id']}: no such parameter {', '.join(sorted(unknown))}")
        params.update(show.get("params", {}))
        fx = f'<effect id="absent.{effect["id"]}" hidden="false">' + "".join(prop(p, params[p["id"]]) for p in effect["params"]) + "</effect>"
    fps, total = (ANIM_FPS, round(show["animate"] * 1000 / ANIM_FPS)) if "animate" in show else (30, 1000)
    layers = [layer(1, name, end=total - 1) for name in backdrops(show)]
    move = show.get("layout") == "move"
    if show.get("layout") == "text":
        layers.append(words(2, show["text"], fx, total - 1))
    else:
        layers.append(layer(2, show["photo"], fx, RENDER // 4 if move else RENDER // 2, show.get("keys"), total - 1))
    if "split" in show:
        layers.append(layer(3, f'{show["photo"]}-before{round(show["split"] * 100)}', end=total - 1))
    return (f'<scene title="thumb" width="{RENDER}" height="{RENDER}" fps="{fps}" totalTime="{total}" bgcolor="#00000000">'
            + "".join(layers) + "</scene>")


class Batch:
    def __init__(self, work):
        self.work, self.jobs, self.images = work, [], {}

    def add(self, name, xml, frames):
        (self.work / f"{name}.xml").write_text(xml)
        push(self.work / f"{name}.xml", f"{name}.xml")
        names = [f"thumbs-{name}-{f}" for f in frames]
        self.jobs += [f"{APP_PATH}/{name}.xml {f} {out}" for f, out in zip(frames, names)]
        return names

    def run(self):
        (self.work / "list.txt").write_text("\n".join(self.jobs) + "\n")
        push(self.work / "list.txt", "list.txt")
        adb("logcat", "-c")
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "batch", f"{APP_PATH}/list.txt")
        deadline = time.time() + 60 + 10 * len(self.jobs)
        while "batch done" not in (log := adb("logcat", "-d", "-s", "AMTest")):
            if time.time() > deadline:
                sys.exit(f"the app did not finish {len(self.jobs)} stills in time")
            time.sleep(1)
        for job in self.jobs:
            out = job.split()[2]
            if f"still {out} ok=true" not in log:
                sys.exit(f"{out}: the app could not render the still")
            adb("pull", f"{OUT_DIR}/{out}.png", self.work / f"{out}.png")
            adb("shell", "rm", f"{OUT_DIR}/{out}.png")
            self.images[out] = Image.open(self.work / f"{out}.png").convert("RGBA").resize((THUMB, THUMB), Image.LANCZOS)

    def __getitem__(self, names):
        return [self.images[n] for n in names]


def save_animated(frames, path):
    opaque = all(f.getextrema()[3][0] == 255 for f in frames)
    frames = [f.convert("RGB") if opaque else f for f in frames]
    for quality in range(80, 20, -10):
        frames[0].save(path, save_all=True, append_images=frames[1:], duration=round(1000 / ANIM_FPS), loop=0,
                       quality=quality, method=6, minimize_size=True)
        if path.stat().st_size <= ANIM_BYTES:
            return quality
    sys.exit(f"{path.name} stays above {ANIM_BYTES} bytes")


def grid(images):
    if len(images) == 1:
        return images[0]
    side = THUMB // 2
    out = Image.new("RGBA", (THUMB, THUMB))
    for i, image in enumerate(images):
        cell = Image.alpha_composite(Image.new("RGBA", image.size, (0, 0, 0, 255)), image).resize((side - 4, side - 4), Image.LANCZOS)
        out.paste(cell, (i % 2 * side + 2, i // 2 * side + 2))
    return out


def change(a, b):
    grey = Image.new("RGBA", a.size, (128, 128, 128, 255))
    a, b = Image.alpha_composite(grey, a).convert("RGB"), Image.alpha_composite(grey, b).convert("RGB")
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3


def sheet(path, thumbs):
    cols = 8
    rows = -(-len(thumbs) // cols)
    out = Image.new("RGB", (cols * (THUMB + 8), rows * (THUMB + 24)), (34, 36, 44))
    draw = ImageDraw.Draw(out)
    for i, (key, image, diff) in enumerate(thumbs):
        x, y = (i % cols) * (THUMB + 8) + 4, (i // cols) * (THUMB + 24) + 4
        out.paste(Image.alpha_composite(Image.new("RGBA", image.size, (60, 62, 72, 255)), image).convert("RGB"), (x, y))
        draw.text((x, y + THUMB + 4), f"{key} {diff:.1f}", fill=(255, 255, 255) if diff >= SAME else (255, 90, 90))
    out.save(path)


def main():
    args = argparse.ArgumentParser(description="Renders the effect thumbnails on the emulator from tools/effect_showcase.json.")
    args.add_argument("--work", type=Path, required=True, help="folder for the downloaded photos and renders, outside the repository")
    args.add_argument("--sheet", type=Path, help="also write a contact sheet of the thumbnails here")
    args.add_argument("keys", nargs="*", help="only these effects (default: every effect in index.json)")
    opts = args.parse_args()
    index = json.loads((PACK / "index.json").read_text())
    missing = [k for k in index if k not in SHOWCASE["effects"]]
    if missing:
        sys.exit("no showcase for " + " ".join(missing))
    keys = opts.keys or index
    opts.work.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "run-as", PKG, "mkdir", "-p", APP_DIR)
    made, pushed, plain, thumbs, unchanged = {}, set(), {}, [], []
    batch, planned = Batch(opts.work), []
    for key in keys:
        show = SHOWCASE["effects"][key]
        names = ([show["photo"]] if "photo" in show else []) + backdrops(show)
        for name in names:
            if name not in pushed:
                photo(name, opts.work, made).save(opts.work / f"{name}.png")
                push(opts.work / f"{name}.png", f"{name}.png")
                pushed.add(name)
        if "split" in show:
            name = f'{show["photo"]}-before{round(show["split"] * 100)}'
            if name not in pushed:
                before(made[show["photo"]], show["split"]).save(opts.work / f"{name}.png")
                push(opts.work / f"{name}.png", f"{name}.png")
                pushed.add(name)
        animated = show.get("animate", 0)
        frames = list(range(animated)) if animated else show.get("frames", [show.get("frame", 0)])
        base = (show.get("photo"), show.get("text"), show.get("under"), show.get("split"), tuple(frames), show.get("layout"), json.dumps(show.get("keys")), animated)
        if base not in plain:
            plain[base] = batch.add(f"plain{len(plain)}", scene(show, None), frames)
        effect = json.loads((PACK / f"{key}.json").read_text())
        planned.append((key, show, base, batch.add(key, scene(show, effect), frames)))
    batch.run()
    for key, show, base, rendered in planned:
        reference, frames = batch[plain[base]], batch[rendered]
        if show.get("animate"):
            changes = [change(f, r) for f, r in zip(frames, reference)]
            diff, moving = max(changes), max(change(frames[0], f) for f in frames)
            image = frames[changes.index(diff)]
        else:
            image, moving = grid(frames), None
            diff = change(image, grid(reference))
        thumbs.append((key, image, diff))
        note = "   <- hardly visible" if diff < SAME else "   <- does not move" if moving is not None and moving < SAME else ""
        target = PACK / "thumb" / f"{key}.webp"
        if show.get("animate"):
            quality = save_animated(frames, target)
            note += f"   animated q{quality} {target.stat().st_size // 1024} KB"
        else:
            out = image if image.getextrema()[3][0] < 255 else image.convert("RGB")
            out.save(target, quality=82, method=6)
        print(f"{key:20} {show.get('photo', 'text'):12} {diff:6.1f}{note}", flush=True)
        if diff < SAME or (moving is not None and moving < SAME):
            unchanged.append(key)
    adb("shell", "run-as", PKG, "rm", "-r", APP_DIR)
    if not opts.keys:
        for stale in (PACK / "thumb").iterdir():
            if stale.name not in {f"{k}.webp" for k in index}:
                stale.unlink()
    if opts.sheet:
        sheet(opts.sheet, thumbs)
    if unchanged:
        sys.exit("hardly visible: " + " ".join(unchanged))


if __name__ == "__main__":
    main()
