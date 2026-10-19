#!/usr/bin/env python3
import argparse, functools, json, os, re, statistics, subprocess, sys, time
import numpy as np
from PIL import Image

ADB = [os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "-s", os.environ.get("ANDROID_SERIAL") or "emulator-5554"]
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
CLIPS = ("shapes", "pan", "line", "occlusion")
CONFIGS = {
    "nearest": ["--es", "interp", "off"],
    "blend": ["--es", "interp", "blend"],
    "flow2": ["--es", "interp", "flow", "--ei", "interpDivisor", "2"],
    "flow4": ["--es", "interp", "flow", "--ei", "interpDivisor", "4"],
}
SOURCE_FPS, SOURCE_FRAMES, STEPS, SPEED = 10, 12, 4, 0.25


@functools.lru_cache(None)
def lattice(seed):
    return np.random.default_rng(seed).random((256, 256), dtype=np.float32)


def noise(seed, x, y):
    table = lattice(seed)
    x0, y0 = np.floor(x), np.floor(y)
    fx, fy = x - x0, y - y0
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    i, j = x0.astype(np.int64) & 255, y0.astype(np.int64) & 255
    i1, j1 = (i + 1) & 255, (j + 1) & 255
    top = table[j, i] * (1 - fx) + table[j, i1] * fx
    bottom = table[j1, i] * (1 - fx) + table[j1, i1] * fx
    return top * (1 - fy) + bottom * fy


def texture(seed, x, y):
    return np.stack([0.5 * noise(seed * 3 + k, x / 90, y / 90) + 0.3 * noise(seed * 3 + k, x / 31, y / 31) +
                     0.2 * noise(seed * 3 + k, x / 11, y / 11) for k in range(3)], -1)


def disc(x, y, cx, cy, r, s):
    return np.clip((r - np.hypot(x - cx, y - cy)) * s + 0.5, 0, 1)[..., None]


def box(x, y, cx, cy, hw, hh, s):
    return (np.clip((hw - np.abs(x - cx)) * s + 0.5, 0, 1) * np.clip((hh - np.abs(y - cy)) * s + 0.5, 0, 1))[..., None]


def band(x, y, px, py, angle, half, s):
    nx, ny = np.cos(np.radians(angle)), np.sin(np.radians(angle))
    return np.clip((half - np.abs((x - px) * nx + (y - py) * ny)) * s + 0.5, 0, 1)[..., None]


def over(image, cover, color):
    return image * (1 - cover) + np.asarray(color, np.float32) * cover


def render(clip, t, height):
    s = height / 1080
    width = round(1920 * s)
    y, x = np.mgrid[0:height, 0:width].astype(np.float32)
    x, y = (x + 0.5) / s, (y + 0.5) / s
    if clip == "shapes":
        image = np.float32([0.16, 0.18, 0.24]) + 0.12 * (texture(1, x, y) - 0.5) + 0.1 * (y / 1080)[..., None]
        cx, cy = 400 + 80 * t, 300
        image = over(image, disc(x, y, cx, cy, 120, s), 0.25 + 0.75 * texture(2, x - cx, y - cy))
        cx, cy = 500 + 250 * t, 600 + 120 * t
        stripes = (0.5 + 0.5 * np.sin((x - cx) / 9 + (y - cy) / 23))[..., None]
        image = over(image, box(x, y, cx, cy, 110, 110, s), np.float32([0.95, 0.6, 0.2]) * (0.6 + 0.4 * stripes))
        cx, cy = 200 + 900 * t, 860
        image = over(image, disc(x, y, cx, cy, 60, s), [0.3, 0.9, 0.5])
    elif clip == "pan":
        image = texture(3, x + 320 * t, y + 100 * t)
    elif clip == "line":
        image = 0.35 + 0.25 * (texture(4, x, y) - 0.5)
        image = over(image, band(x, y, 300 + 700 * t, 540, 8, 1.5, s), [1, 1, 1])
        image = over(image, band(x, y, 960, 200 + 150 * t, 90, 1.5, s), [1, 0.9, 0.2])
    else:
        image = texture(5, x - 60 * t, y)
        cx, cy = 700 + 150 * t, 540
        image = over(image, disc(x, y, cx, cy, 180, s), 0.2 + 0.8 * texture(6, x - cx, y - cy))
        cx, cy = 1300 - 600 * t, 540
        stripes = (0.5 + 0.5 * np.sin((y - cy) / 7))[..., None]
        image = over(image, box(x, y, cx, cy, 150, 320, s), 0.5 * texture(7, x - cx, y - cy) + 0.5 * stripes * np.float32([0.9, 0.3, 0.3]))
    return Image.fromarray((np.clip(image, 0, 1) * 255 + 0.5).astype(np.uint8))


def encode(frames_dir, fps, out):
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-framerate", str(fps), "-i", os.path.join(frames_dir, "%03d.png"),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "10", "-g", str(fps), "-vf", "scale=out_color_matrix=bt601:out_range=tv",
                    "-pix_fmt", "yuv420p", "-colorspace", "smpte170m", "-color_primaries", "smpte170m", "-color_trc", "smpte170m", out], check=True)


def remote(height, name):
    return f"{DEVICE_DIR}/interp/{height}-{name}"


def scene_xml(clip, height, size):
    width = round(1920 * height / 1080)
    uri = "file://" + remote(height, f"{clip}-10fps.mp4")
    frames = SOURCE_FRAMES * STEPS - STEPS + 1
    total = frames * 1000 // SOURCE_FPS - 1
    duration = SOURCE_FRAMES * 1000 // SOURCE_FPS
    return f"""<?xml version='1.0' encoding='UTF-8' ?>
<scene title="interp-{clip}-{height}" width="{width}" height="{height}" fps="{SOURCE_FPS}" totalTime="{total}" bgcolor="#ff000000" am="app.absentmotion" amplatform="android">
  <media uri="{uri}" filename="{height}-{clip}-10fps.mp4" title="{clip}" type="video/mp4" size="{size}" orientation="0" duration="{duration * 1000}" fps="{SOURCE_FPS}" width="{width}" height="{height}" />
  <shape id="1" label="Clip" startTime="0" endTime="{total}" fillType="media" mediaFillMode="stretch" s=".rect" fillVideo="{uri}" inTime="0" outTime="{duration}" speed="{SPEED}">
    <transform>
      <location value="{width / 2:f},{height / 2:f},0.000000" />
    </transform>
    <property name="size" type="vec2" value="{width / 2:f},{height / 2:f}" />
  </shape>
</scene>
"""


def make(folder, height):
    base = os.path.join(folder, str(height))
    for clip in CLIPS:
        start = time.time()
        for name, fps, count in (("10fps", SOURCE_FPS, SOURCE_FRAMES), ("60fps", 60, (SOURCE_FRAMES - 1) * 6 + 1)):
            frames = os.path.join(base, clip, name)
            os.makedirs(frames, exist_ok=True)
            for n in range(count):
                render(clip, n / fps, height).save(os.path.join(frames, f"{n:03d}.png"))
            encode(frames, fps, os.path.join(base, f"{clip}-{name}.mp4"))
            for f in os.listdir(frames):
                os.remove(os.path.join(frames, f))
            os.rmdir(frames)
        truth = os.path.join(base, clip, "truth")
        os.makedirs(truth, exist_ok=True)
        for j in range((SOURCE_FRAMES - 1) * STEPS + 1):
            render(clip, j / (SOURCE_FPS * STEPS), height).save(os.path.join(truth, f"{j:02d}.png"))
        size = os.path.getsize(os.path.join(base, f"{clip}-10fps.mp4"))
        with open(os.path.join(base, f"{clip}.xml"), "w") as out:
            out.write(scene_xml(clip, height, size))
        print(f"{clip} {height}p: {time.time() - start:.0f} s")


def adb(*args, check=False):
    return subprocess.run([*ADB, *args], capture_output=True, check=check).stdout.decode("utf-8", "replace")


def luma(a):
    return a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114


def blur(a):
    k = np.exp(-0.5 * ((np.arange(11) - 5) / 1.5) ** 2)
    k /= k.sum()
    rows = sum(k[i] * a[:, i:a.shape[1] - 10 + i] for i in range(11))
    return sum(k[i] * rows[i:rows.shape[0] - 10 + i] for i in range(11))


def ssim(a, b):
    x, y = luma(a), luma(b)
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    mx, my = blur(x), blur(y)
    vx, vy, cov = blur(x * x) - mx * mx, blur(y * y) - my * my, blur(x * y) - mx * my
    return float(np.mean((2 * mx * my + c1) * (2 * cov + c2) / ((mx * mx + my * my + c1) * (vx + vy + c2))))


def psnr(a, b, mask=None):
    d = (a - b) ** 2
    mse = float(d[mask].mean()) if mask is not None else float(d.mean())
    return 99.0 if mse == 0 else 10 * np.log10(255 ** 2 / mse)


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), np.float64)


def export(scene, height, extra):
    adb("shell", "rm", "-rf", f"{DEVICE_DIR}/export.seq")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", scene, "--es", "export", "sequence",
        "--ei", "short", str(height), "--ei", "fps", str(SOURCE_FPS), "--es", "format", "png", *extra)
    log = ""
    for _ in range(3600):
        time.sleep(1)
        log = adb("logcat", "-d", "-s", "AMExport", "AMInterp", "AMVideo")
        if "I AMExport: done" in log or "E AMExport" in log:
            break
    if "I AMExport: done" not in log:
        sys.exit(f"export of {scene} failed:\n{log[-3000:]}")
    return log


def timings(log):
    estimate = [float(v) for v in re.findall(r"estimate=([\d.]+)", log)]
    synthesis = [float(v) for v in re.findall(r"synthesis=([\d.]+)", log)]
    imports = [float(v) for v in re.findall(r"import pts=\d+ size=\S+ ms=([\d.]+)", log)]
    passes = [int(v) for v, e in re.findall(r"passes=(\d+) estimate=([\d.]+)", log)]
    median = lambda v: round(statistics.median(v), 1) if v else None
    return {"estimate_ms": median(estimate[1:] or estimate), "synthesis_ms": median(synthesis[1:] or synthesis),
            "import_ms": median(imports), "passes": max(passes) if passes else 0, "fallbacks": log.count("Decoder timed out")}


def measure(folder, height, clips, configs, extra):
    base = os.path.join(folder, str(height))
    adb("shell", "mkdir", "-p", f"{DEVICE_DIR}/interp")
    results = {}
    for clip in clips:
        adb("push", os.path.join(base, f"{clip}-10fps.mp4"), remote(height, f"{clip}-10fps.mp4"), check=True)
        adb("push", os.path.join(base, f"{clip}.xml"), remote(height, f"{clip}.xml"), check=True)
        truth = [load(os.path.join(base, clip, "truth", f"{j:02d}.png")) for j in range((SOURCE_FRAMES - 1) * STEPS + 1)]
        for config in configs:
            log = export(remote(height, f"{clip}.xml"), height, CONFIGS[config] + extra)
            local = os.path.join(base, clip, config)
            os.makedirs(local, exist_ok=True)
            adb("pull", f"{DEVICE_DIR}/export.seq/.", local, check=True)
            frames = [load(os.path.join(local, f"frame_{j + 1:05d}.png")) for j in range(len(truth))]
            sources = range(0, len(truth), STEPS)
            fit = [np.polyfit(np.concatenate([truth[j][..., c].ravel() for j in sources]),
                              np.concatenate([frames[j][..., c].ravel() for j in sources]), 1) for c in range(3)]
            gain, offset = np.float64([f[0] for f in fit]), np.float64([f[1] for f in fit])
            rows = {}
            for j, want in enumerate(truth):
                got = np.clip((frames[j] - offset) / gain, 0, 255)
                k, step = divmod(j, STEPS)
                moving = None
                if step:
                    a, b = truth[k * STEPS], truth[(k + 1) * STEPS]
                    moving = (np.abs(want - a).max(-1) > 8) | (np.abs(want - b).max(-1) > 8) | (np.abs(a - b).max(-1) > 8)
                rows.setdefault(step / STEPS, []).append((psnr(got, want), ssim(got, want), psnr(got, want, moving) if moving is not None and moving.any() else None))
            for f in os.listdir(local):
                os.remove(os.path.join(local, f))
            os.rmdir(local)
            summary = {f"{f:.2f}": {"psnr": round(float(np.mean([r[0] for r in v])), 2), "ssim": round(float(np.mean([r[1] for r in v])), 4),
                                    "psnr_moving": round(float(np.mean([r[2] for r in v if r[2] is not None])), 2) if any(r[2] for r in v) else None}
                       for f, v in sorted(rows.items())}
            results[f"{clip}/{config}"] = {"fractions": summary, "levels": [round(float(v), 3) for v in (*gain, *offset)], **timings(log)}
            print(json.dumps({f"{clip}/{config}": results[f"{clip}/{config}"]}), flush=True)
    with open(os.path.join(base, "results.json"), "w") as out:
        json.dump(results, out, indent=1)
    print(f"\n{height}p  clip/config            f   PSNR    SSIM   PSNR-moving")
    for key, r in results.items():
        for f, m in r["fractions"].items():
            print(f"      {key:22s} {f}  {m['psnr']:6.2f}  {m['ssim']:.4f}  {m['psnr_moving'] if m['psnr_moving'] is not None else '':>6}")
    print(f"\n{height}p  clip/config            estimate ms  synthesis ms  import ms  passes")
    for key, r in results.items():
        print(f"      {key:22s} {str(r['estimate_ms']):>11}  {str(r['synthesis_ms']):>12}  {str(r['import_ms']):>9}  {r['passes']:>6}")


def main():
    parser = argparse.ArgumentParser(description="make: render the frame interpolation test clips, ground truth and test projects into a folder "
                                     "outside the repository. measure: export them on the emulator with each interpolation mode and "
                                     "compare every exported frame with the ground truth.")
    parser.add_argument("action", choices=("make", "measure"))
    parser.add_argument("folder")
    parser.add_argument("--height", type=int, default=540)
    parser.add_argument("--clips", default=",".join(CLIPS))
    parser.add_argument("--configs", default=",".join(CONFIGS))
    parser.add_argument("--extra", nargs=argparse.REMAINDER, default=[])
    a = parser.parse_args()
    folder = os.path.abspath(a.folder)
    if folder.startswith(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))):
        sys.exit("keep generated files outside the repository")
    if a.action == "make":
        make(folder, a.height)
    else:
        measure(folder, a.height, a.clips.split(","), a.configs.split(","), a.extra)


if __name__ == "__main__":
    main()
