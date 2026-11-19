#!/usr/bin/env python3
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "shared/assets/presets/placeholders"
SIDE = 1080
BIG = SIDE * 2


def gradient(top, bottom, size=BIG):
    image = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / (size - 1)
        draw.line([(0, y), (size, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return image


def ridge(draw, base, height, waves, colour, phase):
    points = [(0, BIG)]
    for x in range(0, BIG + 1, 8):
        y = base - height * sum(math.sin(x / BIG * math.pi * w + phase * w) / w for w in waves)
        points.append((x, y))
    points.append((BIG, BIG))
    draw.polygon(points, fill=colour)


def hills():
    image = gradient((255, 170, 110), (120, 70, 170))
    draw = ImageDraw.Draw(image)
    draw.ellipse([1300, 520, 1700, 920], fill=(255, 236, 170))
    ridge(draw, 1280, 160, (1.3, 3.1), (150, 80, 160), 0.4)
    ridge(draw, 1450, 140, (1.7, 4.3), (95, 55, 140), 1.9)
    ridge(draw, 1640, 120, (2.2, 5.0), (55, 35, 105), 0.9)
    ridge(draw, 1860, 90, (1.1, 6.2), (30, 22, 70), 2.7)
    return image


def city():
    image = gradient((14, 18, 52), (70, 40, 110))
    draw = ImageDraw.Draw(image)
    rng = random.Random(7)
    for _ in range(140):
        x, y, r = rng.randrange(BIG), rng.randrange(1100), rng.choice((2, 3, 4))
        draw.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 230))
    draw.ellipse([1500, 220, 1760, 480], fill=(250, 240, 210))
    draw.ellipse([1560, 200, 1820, 460], fill=(20, 24, 60))
    x = -40
    while x < BIG:
        width = rng.randrange(170, 320)
        top = rng.randrange(820, 1500)
        shade = rng.randrange(22, 48)
        draw.rectangle([x, top, x + width, BIG], fill=(shade, shade + 6, shade + 30))
        for wy in range(top + 40, BIG - 60, 70):
            for wx in range(x + 30, x + width - 40, 56):
                if rng.random() < 0.45:
                    draw.rectangle([wx, wy, wx + 28, wy + 36], fill=rng.choice(((255, 214, 110), (255, 180, 90), (140, 220, 255))))
        x += width + rng.randrange(6, 30)
    return image


def shapes():
    image = gradient((255, 236, 210), (200, 225, 255))
    draw = ImageDraw.Draw(image)
    draw.ellipse([180, 220, 1060, 1100], fill=(255, 112, 120))
    draw.rounded_rectangle([1050, 260, 1880, 1090], radius=160, fill=(70, 120, 255))
    draw.polygon([(420, 1950), (980, 1050), (1540, 1950)], fill=(255, 196, 60))
    draw.ellipse([1380, 1240, 1980, 1840], outline=(40, 190, 140), width=90)
    draw.rounded_rectangle([160, 1300, 560, 1700], radius=60, fill=(150, 90, 230))
    return image


def ocean():
    image = gradient((120, 190, 245), (255, 205, 160))
    draw = ImageDraw.Draw(image)
    horizon = 1240
    draw.ellipse([780, 640, 1380, 1240], fill=(255, 150, 90))
    sea = gradient((40, 120, 190), (10, 50, 110))
    image.paste(sea.crop((0, 0, BIG, BIG - horizon)), (0, horizon))
    rng = random.Random(3)
    for row in range(26):
        y = horizon + 18 + row * row * 1.3 + row * 10
        width = 120 + row * 26
        for _ in range(3 + row // 3):
            centre = 1080 + rng.gauss(0, 120 + row * 40)
            draw.rounded_rectangle([centre - width / 2, y, centre + width / 2, y + 6 + row // 4], radius=6, fill=(255, 190, 130))
    return image.filter(ImageFilter.GaussianBlur(1.2))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, make in (("hills", hills), ("city", city), ("shapes", shapes), ("ocean", ocean)):
        target = OUT / f"{name}.webp"
        make().resize((SIDE, SIDE), Image.LANCZOS).save(target, "WEBP", quality=80, method=6)
        print(target.relative_to(ROOT), target.stat().st_size)


if __name__ == "__main__":
    main()
