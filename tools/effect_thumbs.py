#!/usr/bin/env python3
"""Makes the effect thumbnails: app/src/main/assets/effects/thumb/<key>.png, 128 x 128.

Each effect is drawn at its defaults on a test picture this script paints itself, by the Mac app's headless renderer
(`--render`, see desktop/README.md). The picture is also rendered once with no effect, and an effect whose result is
(nearly) the same as that is reported: its defaults should be changed so a newly added effect shows what it does.

  tools/effect_thumbs.py [--app "<command>"] [key ...]
      --app   how to start AbsentMotion (default: build/release/mac/AbsentMotion.app/Contents/MacOS/AbsentMotion);
              it is split like a shell command line and gets --data-dir, --render and its arguments appended
      key     only these effects (default: every effect in index.json)
"""
import argparse, json, shlex, subprocess, sys, tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "app/src/main/assets/effects"
SIZE = 256
SAME = 1.0

def test_picture(path):
    """A gradient with a sun, a green card (for the keys), a red triangle, stripes and a dark corner."""
    img = Image.new("RGBA", (SIZE, SIZE))
    draw = ImageDraw.Draw(img)
    for y in range(SIZE):
        t = y / (SIZE - 1)
        draw.line([(0, y), (SIZE, y)], fill=(int(40 + 200 * t), int(90 + 60 * t), int(220 - 170 * t), 255))
    for i in range(6):
        draw.rectangle([0, 150 + i * 16, SIZE, 150 + i * 16 + 6], fill=(30, 30, 60, 255))
    draw.ellipse([28, 24, 128, 124], fill=(255, 236, 160, 255), outline=(255, 255, 255, 255), width=4)
    draw.rounded_rectangle([140, 40, 230, 130], 14, fill=(20, 200, 60, 255))
    draw.polygon([(60, 230), (130, 140), (200, 230)], fill=(220, 40, 50, 255))
    draw.rectangle([200, 190, SIZE, SIZE], fill=(10, 10, 10, 255))
    img.save(path)

def value(param):
    """A param's default as the project file writes it (EffectParam.parse)."""
    d, kind = param["default"], param["type"]
    if kind == "point": return f"{float(d[0]):.6f},{float(d[1]):.6f}"
    if kind == "color":
        c = d.lower()
        return "#ff" + c[1:] if len(c) == 7 else c
    if kind == "switch": return "true" if d is True or d == 1 else "false"
    return f"{float(d):.6f}"

def scene(picture, effect):
    """The test picture filling a SIZE x SIZE scene, with `effect` (a pack entry) or nothing on it."""
    fx = ""
    if effect:
        props = "".join(f'<property name="{p["id"]}" type="{ {"point": "vec2", "color": "color", "switch": "bool"}.get(p["type"], "float") }" '
                        f'value="{value(p)}"/>' for p in effect["params"])
        fx = f'<effect id="absent.{effect["id"]}" hidden="false">{props}</effect>'
    c = SIZE // 2
    return (f'<scene title="thumb" width="{SIZE}" height="{SIZE}" fps="30" totalTime="1000" bgcolor="#00000000">'
            f'<shape id="1" label="picture" startTime="0" endTime="999" s=".rect" fillType="media" fillImage="{picture.as_uri()}">'
            f'<transform><location value="{c},{c},0"/></transform><property name="size" type="vec2" value="{SIZE},{SIZE}"/>{fx}</shape></scene>')

def render(app, work, name, xml):
    (work / f"{name}.xml").write_text(xml)
    out = work / f"{name}.png"
    subprocess.run(app + ["--data-dir", str(work / f"data-{name}"), "--render", str(work / f"{name}.xml"), "15", str(out)],
                   check=True, capture_output=True, timeout=120)
    return Image.open(out).convert("RGBA")

def main():
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("--app", default=str(ROOT / "build/release/mac/AbsentMotion.app/Contents/MacOS/AbsentMotion"))
    args.add_argument("keys", nargs="*")
    opts = args.parse_args()
    app = shlex.split(opts.app)
    keys = opts.keys or json.loads((PACK / "index.json").read_text())
    (PACK / "thumb").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        picture = work / "picture.png"
        test_picture(picture)
        plain = render(app, work, "plain", scene(picture, None))

        def one(key):
            effect = json.loads((PACK / f"{key}.json").read_text())
            image = render(app, work, key, scene(picture, effect))
            change = sum(ImageStat.Stat(ImageChops.difference(image, plain)).mean) / 4
            image.resize((SIZE // 2, SIZE // 2), Image.LANCZOS).save(PACK / "thumb" / f"{key}.png", optimize=True)
            return key, change

        unchanged = []
        with ThreadPoolExecutor(4) as pool:
            for key, change in pool.map(one, keys):
                print(f"{key:24} {change:7.2f}{'   <- defaults change nothing' if change < SAME else ''}")
                if change < SAME: unchanged.append(key)
    if unchanged:
        print("defaults that leave the picture as it is:", " ".join(unchanged))
        sys.exit(1)

if __name__ == "__main__":
    main()
