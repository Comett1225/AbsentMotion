#!/usr/bin/env python3
import argparse
import json
import random
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
ADB = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", "emulator-5554"]
PKG = "app.absentmotion"
APP_DIR = "files/effect-thumbs"
APP_PATH = f"/data/user/0/{PKG}/{APP_DIR}"
OUT_DIR = f"/sdcard/Android/data/{PKG}/files"
RENDER, THUMB, SAME = 384, 192, 2.0
FREE = {"cc0", "pd"}
TYPES = {"point": "vec2", "color": "color", "switch": "bool"}
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
    return f"{float(v):.6f}"


def layer(id, image, effect=""):
    c = RENDER // 2
    return (f'<shape id="{id}" label="{image}" startTime="0" endTime="999" s=".rect" fillType="media" mediaFillMode="fill" '
            f'fillImage="file://{APP_PATH}/{image}.png"><transform><location value="{c},{c},0"/></transform>'
            f'<property name="size" type="vec2" value="{c},{c}"/>{effect}</shape>')


def scene(show, effect):
    fx = ""
    if effect:
        params = {p["id"]: p["default"] for p in effect["params"]}
        unknown = set(show.get("params", {})) - set(params)
        if unknown:
            sys.exit(f"{effect['id']}: no such parameter {', '.join(sorted(unknown))}")
        params.update(show.get("params", {}))
        fx = (f'<effect id="absent.{effect["id"]}" hidden="false">'
              + "".join(f'<property name="{p["id"]}" type="{TYPES.get(p["type"], "float")}" value="{value(p["type"], params[p["id"]])}"/>'
                        for p in effect["params"]) + "</effect>")
    layers = [layer(1, show["under"])] if "under" in show else []
    layers.append(layer(2, show["photo"], fx))
    if "split" in show:
        layers.append(layer(3, f'{show["photo"]}-before{round(show["split"] * 100)}'))
    return (f'<scene title="thumb" width="{RENDER}" height="{RENDER}" fps="30" totalTime="1000" bgcolor="#00000000">'
            + "".join(layers) + "</scene>")


def render(work, name, xml, frame):
    local = work / "thumb.xml"
    local.write_text(xml)
    push(local, "thumb.xml")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", f"{APP_PATH}/thumb.xml", "--es", "still", name, "--ei", "frame", frame)
    deadline = time.time() + 90
    while f"still {name} ok=" not in (log := adb("logcat", "-d", "-s", "AMTest")):
        if time.time() > deadline:
            sys.exit(f"{name}: the app wrote no still in 90 s")
        time.sleep(0.3)
    if f"still {name} ok=true" not in log:
        sys.exit(f"{name}: the app could not render the still")
    adb("pull", f"{OUT_DIR}/{name}.png", work / f"{name}.png")
    adb("shell", "rm", f"{OUT_DIR}/{name}.png")
    return Image.open(work / f"{name}.png").convert("RGBA").resize((THUMB, THUMB), Image.LANCZOS)


def grain(image, amount):
    rng = random.Random(1)
    noise = Image.new("L", (RENDER, RENDER))
    noise.putdata([rng.randrange(256) for _ in range(RENDER * RENDER)])
    noise = noise.point(lambda v: round(128 + (v - 128) * 2 * amount)).convert("RGB")
    return ImageChops.add(image, noise, 1.0, -128).convert("RGBA").resize((THUMB, THUMB), Image.LANCZOS)


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
    for key in keys:
        show = SHOWCASE["effects"][key]
        names = [show["photo"]] + ([show["under"]] if "under" in show else [])
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
        frame = show.get("frame", 0)
        base = (show["photo"], show.get("under"), show.get("split"), frame)
        if show.get("local") == "grain":
            plain.setdefault(base, made[show["photo"]].convert("RGBA").resize((THUMB, THUMB), Image.LANCZOS))
            image = grain(made[show["photo"]], show["params"]["amount"])
        else:
            if base not in plain:
                plain[base] = render(opts.work, f"plain{len(plain)}", scene(show, None), frame)
            image = render(opts.work, key, scene(show, json.loads((PACK / f"{key}.json").read_text())), frame)
        diff = change(image, plain[base])
        thumbs.append((key, image, diff))
        print(f"{key:20} {show['photo']:12} {diff:6.1f}{'   <- hardly visible' if diff < SAME else ''}", flush=True)
        if diff < SAME:
            unchanged.append(key)
        out = image if image.getextrema()[3][0] < 255 else image.convert("RGB")
        out.save(PACK / "thumb" / f"{key}.webp", quality=82, method=6)
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
