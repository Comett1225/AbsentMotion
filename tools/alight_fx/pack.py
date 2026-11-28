#!/usr/bin/env python3
import argparse
import json
import shutil
from collections import Counter
from pathlib import Path
from PIL import Image, ImageDraw

CELL, SMALL, LARGE, LIMIT = 160, 192, 720, 150 * 1024


def square(path, side, back):
    im = Image.open(path)
    im.seek(0)
    im = im.convert("RGBA")
    w, h = im.size
    s = min(w, h)
    im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s)).resize((side, side), Image.LANCZOS)
    return Image.alpha_composite(Image.new("RGBA", im.size, back), im).convert("RGB")


def tile(path, back=(128, 128, 128, 255)):
    return square(path, CELL, back) if path and Path(path).is_file() else Image.new("RGB", (CELL, CELL), (60, 20, 20))


def save_webp(image, path):
    for quality in range(86, 4, -6):
        image.save(path, "WEBP", quality=quality, method=6)
        if path.stat().st_size <= LIMIT:
            return
    raise SystemExit(f"{path.name} stays above {LIMIT} bytes")


def sheets(rows, status, renders, thumbs, out, per=16, cols=2):
    out.mkdir(parents=True, exist_ok=True)
    for n in range(0, len(rows), per):
        part = rows[n:n + per]
        sheet = Image.new("RGB", (cols * (4 * CELL + 12), (len(part) + cols - 1) // cols * (CELL + 34)), (20, 20, 24))
        draw = ImageDraw.Draw(sheet)
        for k, r in enumerate(part):
            x, y = (k % cols) * (4 * CELL + 12), (k // cols) * (CELL + 34)
            thumb = thumbs / r["thumb"] if r.get("thumb") else None
            for j, p in enumerate([thumb, renders / f"{r['key']}.star.png", renders / f"{r['key']}.png", renders / f"{r['key']}.changed.png"]):
                sheet.paste(tile(p, (0, 0, 0, 255) if j < 2 else (128, 128, 128, 255)), (x + j * CELL, y + 30))
            s = status.get(r["key"], "?")
            draw.text((x + 2, y + 2), f"{r['key']}  [{r['file']}]", fill=(240, 240, 240))
            draw.text((x + 2, y + 15), s[:80], fill=(120, 230, 120) if s == "ok" else (255, 120, 120))
        sheet.save(out / f"sheet-{n // per + 1:02d}.png")


def main():
    ap = argparse.ArgumentParser(description="Builds the converted effect pack from convert.py's output and ModderPackCheckTest's results: the working effects, their Community thumbnails (<key>.webp 192 px and <key>.hero.webp 720 px), contact sheets and report.md.")
    ap.add_argument("build", type=Path, help="folder with convert.json, effects/ and check/ (check.tsv, renders/)")
    ap.add_argument("thumbs", type=Path, help="folder the XML thumb paths are relative to")
    ap.add_argument("out", type=Path, help="pack folder to (re)write")
    ap.add_argument("--notes", type=Path, help="convert.py's overrides JSON; each file's \"note\" goes in the report")
    opts = ap.parse_args()
    rows = json.loads((opts.build / "convert.json").read_text())
    status = dict(line.split("\t", 1) for line in (opts.build / "check" / "check.tsv").read_text().splitlines() if line)
    notes = json.loads(opts.notes.read_text()) if opts.notes else {}
    renders = opts.build / "check" / "renders"
    for sub in ("effects", "thumbs", "sheets"):
        shutil.rmtree(opts.out / sub, ignore_errors=True)
        (opts.out / sub).mkdir(parents=True)
    converted = [r for r in rows if r.get("key")]
    sheets(converted, status, renders, opts.thumbs, opts.out / "sheets")
    for r in rows:
        if r["status"] == "converted":
            check = status.get(r["key"], "not checked")
            r["status"], r["reason"] = ("ok", "") if check == "ok" else ("dropped", f"check: {check}")
        if r["status"] != "ok":
            continue
        key = r["key"]
        shutil.copyfile(opts.build / "effects" / f"{key}.json", opts.out / "effects" / f"{key}.json")
        provided = opts.thumbs / r["thumb"] if r.get("thumb") else None
        r["picture"] = "provided" if provided and provided.is_file() else "render"
        small = provided if r["picture"] == "provided" else renders / f"{key}.hero.png"
        save_webp(square(small, SMALL, (0, 0, 0, 255)), opts.out / "thumbs" / f"{key}.webp")
        save_webp(square(renders / f"{key}.hero.png", LARGE, (0, 0, 0, 255)), opts.out / "thumbs" / f"{key}.hero.webp")
    counts = Counter(r["status"] for r in rows)
    scope = Counter(r["reason"] for r in rows if r["status"] == "out of scope")
    lines = ["# Modder effects pack: conversion report", "",
             f"Source files: {len(rows)}. Converted and working: {counts['ok']}. Dropped: {counts['dropped']}. "
             f"Out of scope: {counts['out of scope']}. Excluded (Alight Motion's own effects): {counts['excluded']}.", "",
             "## Out of scope by reason", "", "| Reason | Files |", "| --- | --- |"]
    lines += [f"| {k} | {v} |" for k, v in scope.most_common()]
    lines += ["", "## Every file", "", "| File | Effect | Key | Category | Status | Notes |", "| --- | --- | --- | --- | --- | --- |"]
    for r in rows:
        note = "; ".join(x for x in [r.get("reason", ""), notes.get(r["file"], {}).get("note", ""),
                                      "card picture rendered (no thumbnail provided)" if r.get("picture") == "render" else ""] if x)
        name = (r.get("name") or r.get("source_id") or "").replace("|", "/")
        lines.append(f"| {r['file']} | {name} | {r.get('key', '') if r['status'] == 'ok' else ''} | {r.get('category', '')} | {r['status']} | {note.replace('|', '/')} |")
    (opts.out / "report.md").write_text("\n".join(lines) + "\n")
    print(dict(counts))


if __name__ == "__main__":
    main()
