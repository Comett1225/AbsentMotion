#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.sax.saxutils import quoteattr

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
LIBRARY = ROOT / "third_party/skia/out/mac-v2-2-arm64/libskia.a"
FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append(name)
        print(f"FAIL {name} {detail}")


def crop_definition():
    effect = json.loads((ROOT / "app/src/main/assets/effects/crop.json").read_text())
    params = "".join(f'<param name="{p["id"]}" value="{p["default"]:.6f}"/>' for p in effect["params"])
    return f"<effect id=\"absent.crop\" sksl={quoteattr(effect['sksl'])}>{params}</effect>"


def effect(id, **values):
    props = "".join(f'<property name="{k}" type="float" value="{v:.6f}"/>' for k, v in values.items())
    return f'<effect id="{id}" hidden="false">{props}</effect>'


def scene(*effects):
    return ('<?xml version="1.0" encoding="UTF-8"?><scene title="t" width="320" height="320" bgcolor="#00000000" totalTime="1000" fps="30">'
            '<shape id="1" label="l" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="160,160,0"/></transform>'
            '<fillColor value="#ffffffff"/><property name="size" type="vec2" value="40,40"/>' + "".join(effects) + "</shape></scene>")


def render(binary, work, xml):
    work.mkdir(exist_ok=True)
    (work / "effects.txt").write_text(crop_definition() + '\n<effect id="absent.blur"><param name="strength" value="10.000000"/></effect>\n')
    (work / "scene.xml").write_text(xml)
    r = subprocess.run([str(binary), str(work / "effects.txt"), str(work / "scene.xml"), "1", "0", str(work), "0"], capture_output=True, text=True)
    if r.returncode != 0 or "effect:" in r.stderr or "scene:" in r.stderr:
        raise RuntimeError(r.stderr)
    last = json.loads(r.stdout.strip().splitlines()[-1])
    return np.fromfile(work / "frame0.rgba", dtype=np.uint8).reshape(last["height"], last["width"], 4).astype(float)


def run(binary, tmp):
    n = [0]

    def draw(*effects):
        n[0] += 1
        return render(binary, tmp / f"r{n[0]}", scene(*effects))

    blur = effect("absent.blur", strength=40)
    plain = draw(blur)
    halo = plain[160, 100:116, 3]
    check("the blur reaches beyond the layer rectangle", bool(halo.min() > 8), f"alpha {halo}")
    untouched = draw(blur, effect("absent.crop"))
    check("Crop with every side, Roundness and Feather at zero changes nothing", float(np.abs(untouched - plain).max()) == 0, f"{np.abs(untouched - plain).max()}")
    check("a Crop at zero keeps the halo on every side", bool(untouched[160, 100:116, 3].min() > 8 and untouched[100:116, 160, 3].min() > 8 and untouched[205:220, 160, 3].min() > 8))
    left = draw(blur, effect("absent.crop", left=0.25))
    check("cropping a side still removes that side", bool(left[160, 120:130, 3].max() < 1) and float(np.abs(left - plain).max()) > 50)
    round_only = draw(blur, effect("absent.crop", radius=0.5))
    check("Roundness alone still cuts the corners", float(np.abs(round_only - plain).max()) > 50)
    feather_only = draw(blur, effect("absent.crop", feather=12))
    check("Feather alone still softens the edge", float(np.abs(feather_only - plain).max()) > 8)


def main():
    if not LIBRARY.exists() or sys.platform != "darwin":
        print("crop_check: skipped (needs the host Skia build in third_party/skia/out/mac-v2-2-arm64)")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        build = subprocess.run(["sh", str(ROOT / "tools/render_bench/build.sh"), str(ROOT / "app/src/main/cpp"), tmp + "/bin"], capture_output=True, text=True)
        if build.returncode != 0:
            print(build.stdout[-2000:], build.stderr[-2000:])
            return 1
        run(Path(tmp) / "bin/render_bench", Path(tmp))
    print(f"crop_check: {CHECKS[0]} checks, {len(FAILURES)} failed")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
