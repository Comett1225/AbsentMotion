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
    "flow": ["--es", "interp", "flow"],
    "flow2": ["--es", "interp", "flow", "--ei", "interpDivisor", "2"],
}
SOURCE_FPS, SOURCE_FRAMES, STEPS, SPEED = 10, 12, 4, 0.25
SOURCES = {fps: round((SOURCE_FRAMES - 1) * fps / SOURCE_FPS) + 1 for fps in (10, 30, 60)}
SPAN_MS = (SOURCE_FRAMES - 1) * 1000 // SOURCE_FPS


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


def source(base, clip, height, fps):
    path = os.path.join(base, f"{clip}-{fps}fps.mp4")
    if not os.path.exists(path):
        frames = os.path.join(base, clip, f"{fps}fps")
        os.makedirs(frames, exist_ok=True)
        for n in range(SOURCES[fps]):
            render(clip, n / fps, height).save(os.path.join(frames, f"{n:03d}.png"))
        encode(frames, fps, path)
        for f in os.listdir(frames):
            os.remove(os.path.join(frames, f))
        os.rmdir(frames)
    return path


def truth(base, clip, height, name, fps, count):
    folder = os.path.join(base, clip, name)
    if not os.path.isdir(folder) or len(os.listdir(folder)) < count:
        os.makedirs(folder, exist_ok=True)
        for j in range(count):
            render(clip, j / fps, height).save(os.path.join(folder, f"{j:02d}.png"))
    return [os.path.join(folder, f"{j:02d}.png") for j in range(count)]


def make(folder, height):
    base = os.path.join(folder, str(height))
    for clip in CLIPS:
        start = time.time()
        for fps in SOURCES:
            source(base, clip, height, fps)
        truth(base, clip, height, "truth", SOURCE_FPS * STEPS, (SOURCE_FRAMES - 1) * STEPS + 1)
        print(f"{clip} {height}p: {time.time() - start:.0f} s", flush=True)


def scene_xml(height, fps, total, layers):
    width = round(1920 * height / 1080)
    media, shapes = [], []
    for i, (name, clip_fps, speed, part) in enumerate(layers):
        uri = "file://" + remote(height, f"{name}.mp4")
        duration = SOURCES[clip_fps] * 1000 // clip_fps
        if uri not in "".join(media):
            media.append(f'  <media uri="{uri}" filename="{height}-{name}.mp4" title="{name}" type="video/mp4" size="1" orientation="0" '
                         f'duration="{duration * 1000}" fps="{clip_fps}" width="{width}" height="{height}" />\n')
        left = {None: 0, "left": 0, "right": width / 2}[part]
        w = width if part is None else width / 2
        shapes.append(f"""  <shape id="{i + 1}" label="Clip {i + 1}" startTime="0" endTime="{total}" fillType="media" mediaFillMode="stretch" s=".rect" fillVideo="{uri}" inTime="0" outTime="{duration}" speed="{speed:g}">
    <transform>
      <location value="{left + w / 2:f},{height / 2:f},0.000000" />
    </transform>
    <property name="size" type="vec2" value="{w / 2:f},{height / 2:f}" />
  </shape>
""")
    return (f"""<?xml version='1.0' encoding='UTF-8' ?>
<scene title="interp-{height}" width="{width}" height="{height}" fps="{fps}" totalTime="{total}" bgcolor="#ff000000" am="app.absentmotion" amplatform="android">
""" + "".join(media) + "".join(shapes) + "</scene>\n")


SLOW = (SOURCE_FPS, round(SPAN_MS / SPEED) + 1000 // SOURCE_FPS - 1)
PROJECTS = {
    "slow": (SLOW, lambda clip: [(f"{clip}-10fps", 10, SPEED, None)]),
    "normal": ((SOURCE_FPS, SPAN_MS + 1000 // SOURCE_FPS - 1), lambda clip: [(f"{clip}-10fps", 10, 1, None)]),
    "thirty": ((30, SPAN_MS - 1), lambda clip: [(f"{clip}-30fps", 30, 1, None)]),
    "sixty": ((30, SPAN_MS - 1), lambda clip: [(f"{clip}-60fps", 60, 1, None)]),
    "mixed": ((30, SPAN_MS - 1), lambda clip: [(f"{clip}-30fps", 30, 1, "left"), (f"{clip}-60fps", 60, 1, "right")]),
}


def push_project(base, height, clip, name):
    (fps, total), layers = PROJECTS[name][0], PROJECTS[name][1](clip)
    for layer, clip_fps, _, _ in layers:
        adb("push", source(base, clip, height, clip_fps), remote(height, f"{layer}.mp4"), check=True)
    local = os.path.join(base, f"{clip}-{name}.xml")
    with open(local, "w") as out:
        out.write(scene_xml(height, fps, total, layers))
    adb("push", local, remote(height, f"{clip}-{name}.xml"), check=True)
    return remote(height, f"{clip}-{name}.xml")


def adb(*args, check=False):
    return subprocess.run([*ADB, *args], capture_output=True, check=check).stdout.decode("utf-8", "replace")


def export(scene, height, fps, extra):
    adb("shell", "rm", "-rf", f"{DEVICE_DIR}/export.seq")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", scene, "--es", "export", "sequence",
        "--ei", "short", str(height), "--ei", "fps", str(fps), "--es", "format", "png", *extra)
    log = ""
    for _ in range(3600):
        time.sleep(1)
        log = adb("logcat", "-d", "-s", "AMExport", "AMInterp", "AMVideo")
        if "I AMExport: done" in log or "E AMExport" in log:
            break
    if "I AMExport: done" not in log:
        sys.exit(f"export of {scene} failed:\n{log[-3000:]}")
    return log


def pull(local):
    os.makedirs(local, exist_ok=True)
    for f in os.listdir(local):
        os.remove(os.path.join(local, f))
    adb("pull", f"{DEVICE_DIR}/export.seq/.", local, check=True)
    return sorted(os.listdir(local))


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


def timings(log):
    flows = re.findall(r"flow a=\d+ b=\d+ size=\S+ field=\S+ levels=(\d+) fuse=\d+ passes=(\d+) recordings=(\d+) estimate=([\d.]+) "
                       r"held=([\d.]+)MB cache=([\d.]+)MB", log)
    synthesis = [float(v) for v in re.findall(r"mode=\w+ synthesis=([\d.]+)", log)]
    imports = [float(v) for v in re.findall(r"import pts=\d+ size=\S+ ms=([\d.]+)", log)]
    estimate = [float(f[3]) for f in flows]
    median = lambda v: round(statistics.median(v), 1) if v else None
    return {"estimate_ms": median(estimate[1:] or estimate), "first_estimate_ms": estimate[0] if estimate else None,
            "synthesis_ms": median(synthesis[1:] or synthesis), "import_ms": median(imports),
            "passes": max((int(f[1]) for f in flows), default=0), "recordings": max((int(f[2]) for f in flows), default=0),
            "pyramid_levels": max((int(f[0]) for f in flows), default=0), "held_mb": max((float(f[4]) for f in flows), default=0.0),
            "cache_mb": max((float(f[5]) for f in flows), default=0.0), "fallbacks": log.count("Decoder timed out")}


def level_fit(frames, truths, fit):
    if not fit:
        return np.ones(3), np.zeros(3)
    fitted = [np.polyfit(np.concatenate([t[..., c].ravel() for t in truths]), np.concatenate([f[..., c].ravel() for f in frames]), 1)
              for c in range(3)]
    return np.float64([f[0] for f in fitted]), np.float64([f[1] for f in fitted])


def score(got, want, before, after):
    moving = (np.abs(want - before).max(-1) > 8) | (np.abs(want - after).max(-1) > 8) | (np.abs(before - after).max(-1) > 8)
    return psnr(got, want), ssim(got, want), psnr(got, want, moving) if moving.any() else None


def print_timings(height, results):
    print(f"\n{height}p  clip/config            estimate ms  first ms  synthesis ms  import ms  passes  recordings  held MB  cache MB")
    for key, r in results.items():
        print(f"      {key:22s} {str(r['estimate_ms']):>11}  {str(r['first_estimate_ms']):>8}  {str(r['synthesis_ms']):>12}  "
              f"{str(r['import_ms']):>9}  {r['passes']:>6}  {r['recordings']:>10}  {r['held_mb']:>7}  {r['cache_mb']:>8}")


def measure(folder, height, clips, configs, extra, fit):
    base = os.path.join(folder, str(height))
    adb("shell", "mkdir", "-p", f"{DEVICE_DIR}/interp")
    results = {}
    for clip in clips:
        truths = [load(p) for p in truth(base, clip, height, "truth", SOURCE_FPS * STEPS, (SOURCE_FRAMES - 1) * STEPS + 1)]
        scene = push_project(base, height, clip, "slow")
        for config in configs:
            log = export(scene, height, SOURCE_FPS, CONFIGS[config] + extra)
            local = os.path.join(base, clip, config)
            pull(local)
            frames = [load(os.path.join(local, f"frame_{j + 1:05d}.png")) for j in range(len(truths))]
            sources = range(0, len(truths), STEPS)
            gain, offset = level_fit([frames[j] for j in sources], [truths[j] for j in sources], fit)
            rows = {}
            for j, want in enumerate(truths):
                got = np.clip((frames[j] - offset) / gain, 0, 255)
                k, step = divmod(j, STEPS)
                if step:
                    rows.setdefault(step / STEPS, []).append(score(got, want, truths[k * STEPS], truths[(k + 1) * STEPS]))
                else:
                    rows.setdefault(0.0, []).append((psnr(got, want), ssim(got, want), None))
            for f in os.listdir(local):
                os.remove(os.path.join(local, f))
            os.rmdir(local)
            summary = {f"{f:.2f}": {"psnr": round(float(np.mean([r[0] for r in v])), 2), "ssim": round(float(np.mean([r[1] for r in v])), 4),
                                    "psnr_moving": round(float(np.mean([r[2] for r in v if r[2] is not None])), 2) if any(r[2] for r in v) else None}
                       for f, v in sorted(rows.items())}
            results[f"{clip}/{config}"] = {"fractions": summary, "levels": [round(float(v), 3) for v in (*gain, *offset)], **timings(log)}
            print(json.dumps({f"{clip}/{config}": results[f"{clip}/{config}"]}), flush=True)
    with open(os.path.join(base, f"results{'' if fit else '-nofit'}.json"), "w") as out:
        json.dump(results, out, indent=1)
    print(f"\n{height}p slow motion, {'fitted levels' if fit else 'no level fit'}  clip/config            f   PSNR    SSIM   PSNR-moving")
    for key, r in results.items():
        for f, m in r["fractions"].items():
            print(f"      {key:22s} {f}  {m['psnr']:6.2f}  {m['ssim']:.4f}  {m['psnr_moving'] if m['psnr_moving'] is not None else '':>6}")
    print_timings(height, results)


def rates(folder, height, clips, configs, fit):
    base = os.path.join(folder, str(height))
    adb("shell", "mkdir", "-p", f"{DEVICE_DIR}/interp")
    results, failures = {}, 0
    count = SPAN_MS * 60 // 1000
    for clip in clips:
        truths = [load(p) for p in truth(base, clip, height, "truth60", 60, count)]
        scene = push_project(base, height, clip, "thirty")
        off = None
        for config in ["nearest"] + [c for c in configs if c != "nearest"]:
            log = export(scene, height, 60, CONFIGS[config])
            local = os.path.join(base, clip, f"thirty-{config}")
            names = pull(local)
            frames = [load(os.path.join(local, n)) for n in names[:count]]
            if off is None:
                off = frames
            even = range(0, count, 2)
            gain, offset = level_fit([frames[k] for k in even], [truths[k] for k in even], fit)
            odd = [score(np.clip((frames[k] - offset) / gain, 0, 255), truths[k], truths[k - 1], truths[min(k + 1, count - 1)])
                   for k in range(1, count, 2)]
            same_even = sum(np.array_equal(frames[k], off[k]) for k in even)
            changed_odd = sum(not np.array_equal(frames[k], off[k]) for k in range(1, count, 2))
            results[f"{clip}/{config}"] = {"odd_psnr": round(float(np.mean([r[0] for r in odd])), 2),
                                          "odd_ssim": round(float(np.mean([r[1] for r in odd])), 4),
                                          "odd_psnr_moving": round(float(np.mean([r[2] for r in odd if r[2] is not None])), 2),
                                          "even_same_as_off": f"{same_even}/{len(even)}", "odd_changed": f"{changed_odd}/{count // 2}",
                                          **timings(log)}
            if config != "nearest" and (same_even != len(even) or changed_odd == 0):
                failures += 1
            print(json.dumps({f"{clip}/{config}": results[f"{clip}/{config}"]}), flush=True)
        sixty = push_project(base, height, clip, "sixty")
        steps = {}
        for config in ("nearest", "flow"):
            export(sixty, height, 60, CONFIGS[config])
            local = os.path.join(base, clip, f"sixty-{config}")
            steps[config] = distinct([load(os.path.join(local, n)) for n in pull(local)[:count]])
        print(f"{clip} 60 fps clip in a 30 fps project at 60 fps: {steps['nearest'] * 1000 / SPAN_MS:.0f} distinct frames per second "
              f"with interpolation off, {steps['flow'] * 1000 / SPAN_MS:.0f} with optical flow", flush=True)
        if steps["nearest"] != count // 2 or steps["flow"] != count:
            failures += 1
        mixed = push_project(base, height, clip, "mixed")
        halves = {}
        for config in ("nearest", "flow"):
            export(mixed, height, 60, CONFIGS[config])
            local = os.path.join(base, clip, f"mixed-{config}")
            halves[config] = [load(os.path.join(local, n)) for n in pull(local)[:count]]
        width = halves["flow"][0].shape[1]
        left, right = (lambda f: f[:, :width // 2 - 2]), (lambda f: f[:, width // 2 + 2:])
        changed = [sum(not np.array_equal(side(halves["nearest"][k]), side(halves["flow"][k])) for k in range(1, count, 2)) for side in (left, right)]
        even = sum(np.array_equal(halves["nearest"][k], halves["flow"][k]) for k in range(0, count, 2))
        right_steps = distinct([right(f) for f in halves["flow"]])
        print(f"{clip} mixed 30 and 60 fps at 60 fps with optical flow: in-between frames changed in {changed[0]}/{count // 2} (30 fps half) "
              f"and {changed[1]}/{count // 2} (60 fps half), 60 fps half {right_steps * 1000 / SPAN_MS:.0f} distinct frames per second, "
              f"whole frame unchanged in {even}/{(count + 1) // 2} source frames", flush=True)
        if changed != [count // 2, count // 2] or right_steps != count or even != (count + 1) // 2:
            failures += 1
    print(f"\n{height}p 30 fps clips exported at 60 fps, in-between frames against a true 60 fps render ({'fitted levels' if fit else 'no level fit'})")
    print("      clip/config            PSNR    SSIM   PSNR-moving  source frames same as off  in-between frames changed")
    for key, r in results.items():
        print(f"      {key:22s} {r['odd_psnr']:6.2f}  {r['odd_ssim']:.4f}  {r['odd_psnr_moving']:>11}  {r['even_same_as_off']:>25}  {r['odd_changed']:>25}")
    print_timings(height, results)
    print(f"rates: {failures} problems")
    return failures


def distinct(frames):
    return 1 + sum(not np.array_equal(a, b) for a, b in zip(frames, frames[1:]))


def offcheck(folder, height, clips, base_apk, new_apk):
    base = os.path.join(folder, str(height))
    variants = {"slow-off": ("slow", SOURCE_FPS, "off"), "normal-flow": ("normal", SOURCE_FPS, "flow"), "thirty-at-60-off": ("thirty", 60, "off"),
                "sixty-at-30-flow": ("sixty", 30, "flow"), "sixty-at-60-off": ("sixty", 60, "off"), "mixed-at-60-off": ("mixed", 60, "off")}
    for name, apk in (("base", base_apk), ("new", new_apk)):
        adb("install", "-r", apk, check=True)
        adb("shell", "mkdir", "-p", f"{DEVICE_DIR}/interp")
        for clip in clips:
            for variant, (project, fps, mode) in variants.items():
                export(push_project(base, height, clip, project), height, fps, ["--es", "interp", mode if name == "new" else "off"])
                pull(os.path.join(base, "offcheck", name, f"{clip}-{variant}"))
    failures = 0
    for clip in clips:
        for variant in variants:
            old, new = (os.path.join(base, "offcheck", name, f"{clip}-{variant}") for name in ("base", "new"))
            names = sorted(os.listdir(old))
            same = names == sorted(os.listdir(new)) and all(np.array_equal(load(os.path.join(old, f)), load(os.path.join(new, f))) for f in names)
            files = same and all(open(os.path.join(old, f), "rb").read() == open(os.path.join(new, f), "rb").read() for f in names)
            print(f"{clip:10s} {variant:17s} {len(names)} frames: {'identical pixels' if same else 'DIFFERENT'}{', identical files' if files else ''}", flush=True)
            failures += not same
    print(f"offcheck: {failures} of {len(clips) * len(variants)} sequences differ")
    return failures


def main():
    parser = argparse.ArgumentParser(description="make: render the frame interpolation test clips and ground truth into a folder outside "
                                     "the repository. measure: export a slowed 10 fps clip with each interpolation mode and compare every "
                                     "frame with the ground truth. rates: export 30 fps clips at 60 fps and compare the in-between frames "
                                     "with a true 60 fps render, count distinct frames of a 60 fps clip in a 30 fps project, and check a project that mixes 30 "
                                     "and 60 fps clips. offcheck: export the "
                                     "test projects with interpolation off (and clips that need none set to optical flow) from two builds "
                                     "and compare every frame.")
    parser.add_argument("action", choices=("make", "measure", "rates", "offcheck"))
    parser.add_argument("folder")
    parser.add_argument("--height", type=int, default=540)
    parser.add_argument("--clips", default=",".join(CLIPS))
    parser.add_argument("--configs", default="nearest,blend,flow")
    parser.add_argument("--no-fit", action="store_true", help="compute PSNR without the per-channel gain and offset correction")
    parser.add_argument("--base-apk")
    parser.add_argument("--new-apk")
    parser.add_argument("--extra", nargs=argparse.REMAINDER, default=[])
    a = parser.parse_args()
    folder = os.path.abspath(a.folder)
    if folder.startswith(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))):
        sys.exit("keep generated files outside the repository")
    clips = a.clips.split(",")
    if a.action == "make":
        make(folder, a.height)
    elif a.action == "measure":
        measure(folder, a.height, clips, a.configs.split(","), a.extra, not a.no_fit)
    elif a.action == "rates":
        sys.exit(1 if rates(folder, a.height, clips, a.configs.split(","), not a.no_fit) else 0)
    else:
        if not (a.base_apk and a.new_apk):
            sys.exit("offcheck needs --base-apk and --new-apk")
        sys.exit(1 if offcheck(folder, a.height, clips, a.base_apk, a.new_apk) else 0)


if __name__ == "__main__":
    main()
