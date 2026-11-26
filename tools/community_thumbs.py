#!/usr/bin/env python3
import argparse
import subprocess
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
SMALL, LARGE, LIMIT, FPS, MOVING = 192, 720, 150 * 1024, 15, 1.0


def flat(image):
    return Image.alpha_composite(Image.new("RGBA", image.size, (128, 128, 128, 255)), image).convert("RGB")


def change(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(flat(a), flat(b))).mean) / 3


def plain(image):
    return image if image.getextrema()[3][0] < 255 else image.convert("RGB")


def save_small(frames, best, path):
    if max(change(frames[0], f) for f in frames) >= MOVING:
        shown = [plain(f) for f in frames] if all(f.getextrema()[3][0] == 255 for f in frames) else frames
        for step in (1, 2, 3):
            for quality in (80, 65, 50, 40, 30, 22):
                shown[0].save(path, save_all=True, append_images=shown[step::step], duration=round(1000 * step / FPS), loop=0, quality=quality, method=4)
                if path.stat().st_size <= LIMIT:
                    return f"animated q{quality} {FPS // step} fps"
    for quality in range(82, 20, -6):
        plain(frames[best]).save(path, quality=quality, method=6)
        if path.stat().st_size <= LIMIT:
            return "still"
    sys.exit(f"{path.name} stays above {LIMIT} bytes")


def save_large(image, path):
    for quality in range(80, 4, -6):
        plain(image).save(path, quality=quality, alpha_quality=quality, method=4)
        if path.stat().st_size <= LIMIT:
            return
    sys.exit(f"{path.name} stays above {LIMIT} bytes")


def main():
    args = argparse.ArgumentParser(description="Renders the thumbnails of Community effects with the desktop app and writes <item>/<id>.webp and <item>/<id>.hero.webp.")
    args.add_argument("effects", type=Path, help="the effects folder of the Community repository, or a folder holding item folders with one .abmfx or .json each")
    args.add_argument("out", type=Path, help="where to write the thumbnails, one folder per item")
    args.add_argument("--frames", type=Path, required=True, help="folder for the rendered frames, outside the repository")
    opts = args.parse_args()
    subprocess.run(["./gradlew", "test", "--tests", "app.absentmotion.pc.CommunityEffectThumbsTest", "-q",
                    f"-PeffectThumbs={opts.effects.resolve()}", f"-PeffectThumbsOut={opts.frames.resolve()}"], cwd=ROOT / "pc", check=True)
    for folder in sorted(p for p in opts.frames.glob("*/*") if p.is_dir()):
        frames = [Image.open(f).convert("RGBA").resize((SMALL, SMALL), Image.LANCZOS) for f in sorted(folder.glob("f*.png"))]
        best = int((folder / "best.txt").read_text())
        target = opts.out / folder.parent.name
        target.mkdir(parents=True, exist_ok=True)
        kind = save_small(frames, best, target / f"{folder.name}.webp")
        save_large(Image.open(folder / "hero.png").convert("RGBA"), target / f"{folder.name}.hero.webp")
        print(f"{folder.parent.name}/{folder.name:28} {kind:12} {(target / f'{folder.name}.webp').stat().st_size // 1024} KB + "
              f"{(target / f'{folder.name}.hero.webp').stat().st_size // 1024} KB", flush=True)


if __name__ == "__main__":
    main()
