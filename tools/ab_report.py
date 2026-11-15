#!/usr/bin/env python3
import json
import math
import os
import subprocess
import sys
import zlib


def frames(path, width, height, scale=None):
    vf = ["-vf", "extractplanes=y" + (f",scale={scale[0]}:{scale[1]}:flags=area" if scale else "")]
    w, h = scale or (width, height)
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-map", "0:v:0", "-fps_mode", "passthrough", *vf, "-pix_fmt", "gray", "-f", "rawvideo", "-"]
    with subprocess.Popen(cmd, stdout=subprocess.PIPE) as p:
        size = w * h
        while True:
            data = p.stdout.read(size)
            if len(data) < size:
                break
            yield data


def size_of(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    if not out:
        return None
    w, h = out.split("\n")[0].split(",")[:2]
    return int(w), int(h)


def reference(path, cache):
    if os.path.exists(cache) and os.path.getmtime(cache) > os.path.getmtime(path):
        return json.load(open(cache))
    dims = size_of(path)
    if not dims:
        return None
    crcs = ["%08x" % (zlib.crc32(f) & 0xFFFFFFFF) for f in frames(path, *dims)]
    grids = [f.hex() for f in frames(path, *dims, scale=(16, 9))]
    ref = {"width": dims[0], "height": dims[1], "crc": crcs, "grid": grids}
    json.dump(ref, open(cache, "w"))
    return ref


def distance(a, b):
    x, y = bytes.fromhex(a), bytes.fromhex(b)
    return sum((p - q) ** 2 for p, q in zip(x, y)) / max(1, len(x))


def check(seek, ref):
    if not ref or not seek.get("ok"):
        return None
    exact = right = 0
    worst = math.inf
    for s in seek["seeks"]:
        k = s["frame"]
        if not s["crc"] or k >= len(ref["crc"]):
            continue
        if s["crc"] == ref["crc"][k]:
            exact += 1
            right += 1
            continue
        d = [distance(s["grid"], g) for g in ref["grid"]]
        if d[k] <= min(d) + 1e-9:
            right += 1
        worst = min(worst, 99.0 if d[k] == 0 else 10 * math.log10(255 * 255 / d[k]))
    return {"exact": exact, "right": right, "of": len(seek["seeks"]), "worstGridPsnr": None if worst == math.inf else round(worst, 1)}


def cell(part, key, fmt="{:.1f}"):
    if part is None:
        return ""
    if not part.get("ok"):
        return "fails"
    v = part.get(key)
    return fmt.format(v) if isinstance(v, (int, float)) else str(v)


def main():
    folder, a_json, b_json = sys.argv[1:4]
    runs = {"Standard": json.load(open(a_json)), "FFamp": json.load(open(b_json))}
    names = sorted({f["file"] for r in runs.values() for f in r["files"]})
    rows = []
    for name in names:
        ref = reference(os.path.join(folder, name), os.path.join(folder, "." + name + ".ref.json")) if os.path.exists(os.path.join(folder, name)) else None
        for copy, run in runs.items():
            f = next((x for x in run["files"] if x["file"] == name), None)
            if not f:
                continue
            seek = f.get("seek")
            c = check(seek, ref) if seek else None
            frames_ok = ""
            if ref and f.get("index", {}).get("ok"):
                frames_ok = "" if f["index"]["frames"] == len(ref["crc"]) else f" (FFmpeg {len(ref['crc'])})"
            opened = f.get("open", {})
            rows.append([
                name if copy == "Standard" else "", copy,
                ("yes " + "/".join(t.split("/")[1][:12] for t in opened.get("tracks", []))) if opened.get("ok") else "no",
                opened.get("reader", "") + {True: ", imports", False: ", refused"}.get(opened.get("declared"), ""),
                cell(opened, "ms"),
                (cell(f.get("index"), "ms") + (f" / {f['index']['frames']}{frames_ok}" if f.get("index", {}).get("ok") else "")) if f.get("index") else "",
                (cell(seek, "medianMs") + " / " + cell(seek, "maxMs", "{:.0f}")) if seek and seek.get("ok") else cell(seek, "x"),
                (f"{c['exact']}/{c['right']}/{c['of']}" + (f" ({c['worstGridPsnr']} dB)" if c["worstGridPsnr"] is not None else "")) if c else "",
                (cell(f.get("preview"), "medianMs") + f" ({f['preview']['rightPts']}/{len(seek.get('seeks') or []) or f['preview']['shown']})") if f.get("preview", {}).get("ok") else cell(f.get("preview"), "x"),
                cell(f.get("sequential"), "fps", "{:.0f}"),
                (cell(f.get("thumbnails"), "ms", "{:.0f}") + f" ({f['thumbnails']['made']})") if f.get("thumbnails", {}).get("ok") else cell(f.get("thumbnails"), "x"),
                (cell(f.get("audio"), "speed", "{:.0f}") + "x") if f.get("audio", {}).get("ok") else cell(f.get("audio"), "x"),
                f"{f['rssGrowthMb']:.0f}",
            ])
    head = ["File", "Reader", "Opens (tracks)", "Read by", "Open+probe ms", "Frame index ms / frames", "Exact seek median / max ms", "Seek right: bit-exact/right/asked", "Preview seek median ms (right pts)", "Sequential fps", "12 thumbs ms (made)", "Sound decode speed", "Peak RSS growth MB"]
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for r in rows:
        print("| " + " | ".join(r) + " |")
    errors = [(n, c, k, v["error"]) for c, run in runs.items() for f in run["files"] for n in [f["file"]] for k, v in f.items() if isinstance(v, dict) and not v.get("ok", True)]
    if errors:
        print("\nFailures:")
        for n, c, k, e in errors:
            print(f"- {n} {c} {k}: {e}")


if __name__ == "__main__":
    main()
