#!/usr/bin/env python3
import argparse
import html
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageStat

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sdk

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "site/data/help-shots.json"
PAGES = ROOT / "site/pages"
IMAGES = ROOT / "site/assets/img"
TRANSLATIONS = ROOT / "site/data/help-translations"
WORK = ROOT / "build/help-shots"
FONT = ROOT / "shared/assets/fonts/Roboto/Roboto-Bold.ttf"
SHOWCASE = ROOT / "tools/effect_showcase.json"
TEST_MEDIA = ROOT / "testdata/media"
ACTIVITY = "app.absentmotion.MainActivity"
SCREEN = (1080, 2400)
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
IMAGE_LINE = re.compile(r"^!\[([^\]]*)\]\(img/([a-z0-9-]+)\.webp\)$")
PERMISSIONS = ["READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "CAMERA", "RECORD_AUDIO", "POST_NOTIFICATIONS"]
STYLE = {
    "tint": 0.5, "feather": 4, "outline": 6, "pad": 8, "radius": 20, "badge": 58, "scale": 2 / 3, "max_width": 720,
    "accent": [255, 143, 102], "ink": [23, 24, 27], "status_bar": 106, "bottom": 2337, "kb": 60, "budget_kb": 3000,
}


class Failure(Exception):
    pass


def center(box):
    return (box[0] + box[2]) // 2, (box[1] + box[3]) // 2


def union(boxes):
    return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))


def esc(value):
    return html.escape(str(value), quote=True)


def flatten(root):
    nodes = []

    def walk(element, parent):
        attrs = element.attrib
        found = BOUNDS.match(attrs.get("bounds", ""))
        index = parent
        if found:
            nodes.append({
                "text": attrs.get("text", ""), "desc": attrs.get("content-desc", ""), "box": tuple(int(v) for v in found.groups()),
                "clickable": attrs.get("clickable") == "true", "checked": attrs.get("checked") == "true", "parent": parent,
            })
            index = len(nodes) - 1
        for child in element:
            walk(child, index)

    walk(root, -1)
    return nodes


def inside(point, region):
    return region[0] <= point[0] <= region[2] and region[1] <= point[1] <= region[3]


def matches(node, selector):
    if isinstance(selector, str):
        return selector in (node["text"], node["desc"])
    if "text" in selector and node["text"] != selector["text"]:
        return False
    if "desc" in selector and node["desc"] != selector["desc"]:
        return False
    if "text_re" in selector and not re.search(selector["text_re"], node["text"]):
        return False
    if "desc_re" in selector and not re.search(selector["desc_re"], node["desc"]):
        return False
    if "label" in selector and selector["label"] not in (node["text"], node["desc"]):
        return False
    if "region" in selector and not inside(center(node["box"]), selector["region"]):
        return False
    if "checked" in selector and node["checked"] != selector["checked"]:
        return False
    return any(key in selector for key in ("text", "desc", "text_re", "desc_re", "label", "checked"))


def find(nodes, selector):
    found = [n for n in nodes if matches(n, selector)]
    index = selector.get("index", 0) if isinstance(selector, dict) else 0
    if not found or index >= len(found) or index < -len(found):
        return None
    return found[index]


def hit_box(nodes, node, exact):
    if exact:
        return node["box"]
    current = node["parent"]
    while current >= 0:
        parent = nodes[current]
        area = (parent["box"][2] - parent["box"][0]) * (parent["box"][3] - parent["box"][1])
        if area > SCREEN[0] * SCREEN[1] * 0.4:
            break
        if parent["clickable"]:
            return parent["box"]
        current = parent["parent"]
    return node["box"]


def describe(selector):
    return json.dumps(selector, ensure_ascii=False)


class Device:
    def __init__(self, package):
        if "ANDROID_SERIAL" not in os.environ:
            sys.exit("help_shots: set ANDROID_SERIAL to the emulator you claimed, for example emulator-5558")
        self.serial = os.environ["ANDROID_SERIAL"]
        if not self.serial.startswith("emulator-"):
            sys.exit("help_shots: only an emulator may be driven, never a phone or a tablet")
        self.package = package
        self.base = sdk.adb_command()
        self.nodes = []

    def run(self, *args, binary=False):
        result = subprocess.run(self.base + [str(a) for a in args], capture_output=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, *args):
        return self.run("shell", *args)

    def check_screen(self):
        size = self.shell("wm", "size").strip()
        if "%dx%d" % SCREEN not in size:
            sys.exit("help_shots: the screen must be %dx%d (AVD AbsentApi33), this one says: %s" % (SCREEN + (size,)))
        if self.package not in self.shell("pm", "list", "packages", self.package):
            sys.exit("help_shots: %s is not installed on %s (adb install the test copy first)" % (self.package, self.serial))
        probe = self.shell("run-as", self.package, "pwd")
        if "not debuggable" in probe or "Unknown package" in probe or not probe.strip().startswith("/data"):
            sys.exit("help_shots: %s is not a debug build, so it cannot be driven" % self.package)

    def screenshot(self):
        return Image.open(io.BytesIO(self.run("exec-out", "screencap", "-p", binary=True))).convert("RGB")

    def tree(self):
        for _ in range(6):
            self.shell("uiautomator", "dump", "/sdcard/help_shots_ui.xml")
            try:
                root = ET.fromstring(self.run("exec-out", "cat", "/sdcard/help_shots_ui.xml"))
            except ET.ParseError:
                time.sleep(0.6)
                continue
            self.nodes = flatten(root)
            return self.nodes
        raise Failure("the screen has no readable UI tree")

    def wait(self, selector, timeout=12.0):
        deadline = time.time() + timeout
        while True:
            node = find(self.tree(), selector)
            if node:
                return node
            if time.time() > deadline:
                raise Failure("not found on screen: " + describe(selector))
            time.sleep(0.5)

    def present(self, selector):
        return find(self.tree(), selector) is not None

    def vanish(self, selector, timeout=8.0):
        deadline = time.time() + timeout
        while self.present(selector):
            if time.time() > deadline:
                raise Failure("still on screen: " + describe(selector))
            time.sleep(0.5)

    def tap(self, x, y):
        self.shell("input", "tap", int(x), int(y))

    def settle(self, timeout=4.0, interval=0.35):
        deadline = time.time() + timeout
        last, calm, shot = None, 0, None
        while time.time() < deadline:
            shot = self.screenshot()
            small = shot.convert("L").resize((90, 200))
            if last is not None and ImageStat.Stat(ImageChops.difference(small, last)).mean[0] < 0.4:
                calm += 1
                if calm >= 2:
                    return shot
            else:
                calm = 0
            last = small
            time.sleep(interval)
        return shot or self.screenshot()

    def put(self, local, name, folder="files/media"):
        staging = "/data/local/tmp/help-shots-" + name
        self.run("push", str(local), staging)
        self.shell("run-as", self.package, "mkdir", "-p", folder)
        self.shell("run-as", self.package, "cp", staging, "%s/%s" % (folder, name))
        self.shell("rm", staging)

    def grant(self):
        for name in PERMISSIONS:
            self.shell("pm", "grant", self.package, "android.permission." + name)

    def start(self, *extras):
        self.shell("am", "force-stop", self.package)
        self.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(0.6)
        self.shell("am", "start", "-n", "%s/%s" % (self.package, ACTIVITY), *extras)

    def publish(self, local, path):
        self.run("push", str(local), "/sdcard/" + path)
        self.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/" + path)

    def ancestor(self, nodes, node):
        current = node
        while current["parent"] >= 0:
            current = nodes[current["parent"]]
            if current["clickable"]:
                return current
        return node

    def demo_mode(self):
        self.home()
        apply_step(self, {"tap": {"desc": "Settings"}})
        apply_step(self, {"tap": "Projects and media"})
        nodes = self.tree()
        label = find(nodes, "Demo Mode")
        if label is None:
            raise Failure("the Demo Mode switch was not found; only a debug build has it")
        row = self.ancestor(nodes, label)
        if not row["checked"]:
            self.tap(*center(row["box"]))
            apply_step(self, {"tap": "OK"})
        self.shell("input", "keyevent", "KEYCODE_BACK")

    def dismiss(self):
        for label in ("Don't ask again", "Not now", "Later"):
            node = find(self.tree(), label)
            if node:
                self.tap(*center(node["box"]))
                time.sleep(1.0)
                return True
        return False

    def home(self):
        self.scene("demo", 45)
        self.settle(4.0)
        apply_step(self, {"tap": "Exit to project list"})
        self.wait("New project", 30)
        self.settle(6.0)

    def scene(self, name, frame):
        path = "/data/data/%s/files/shots/%s.xml" % (self.package, name)
        self.start("--es", "xml", path, "--ei", "frame", str(frame))
        self.wait("Add layer", 90)

    def first_run(self):
        self.shell("pm", "clear", self.package)
        self.grant()
        self.start()
        deadline = time.time() + 90
        while time.time() < deadline:
            nodes = self.tree()
            if find(nodes, {"desc": "New project"}):
                if not self.dismiss():
                    return
                continue
            checked = [n for n in nodes if n["checked"]]
            if checked and find(nodes, "Skip"):
                self.tap(*center(checked[0]["box"]))
                time.sleep(0.8)
            for label in ("Continue", "Skip", "Don't ask again"):
                node = find(nodes, label)
                if node:
                    self.tap(*center(node["box"]))
                    break
            time.sleep(1.5)
        raise Failure("the first start did not reach the home screen")


def apply_step(device, step):
    if "scene" in step:
        device.scene(step["scene"], step.get("frame", 45))
    elif step.get("home"):
        device.home()
    elif "tap" in step:
        node = device.wait(step["tap"], step.get("timeout", 12))
        x, y = center(node["box"])
        offset = step["tap"].get("offset", [0, 0]) if isinstance(step["tap"], dict) else [0, 0]
        device.tap(x + offset[0], y + offset[1])
    elif "tap_if" in step:
        node = find(device.tree(), step["tap_if"])
        if node:
            device.tap(*center(node["box"]))
    elif "tap_xy" in step:
        device.tap(*step["tap_xy"])
    elif "long_press" in step:
        x, y = center(device.wait(step["long_press"])["box"])
        device.shell("input", "swipe", x, y, x, y, step.get("ms", 900))
    elif "long_press_xy" in step:
        x, y = step["long_press_xy"]
        device.shell("input", "swipe", x, y, x, y, step.get("ms", 900))
    elif "swipe" in step:
        device.shell("input", "swipe", *step["swipe"], step.get("ms", 300))
    elif "back" in step:
        for _ in range(int(step["back"])):
            device.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.6)
    elif "type" in step:
        device.shell("input", "text", step["type"].replace(" ", "%s"))
    elif "wait" in step:
        time.sleep(float(step["wait"]))
    elif "expect" in step:
        device.wait(step["expect"], step.get("timeout", 12))
    elif "absent" in step:
        device.vanish(step["absent"], step.get("timeout", 8.0))
    else:
        raise Failure("unknown step " + describe(step))
    if not step.get("quick"):
        device.settle(step.get("settle", 3.0))


def run_steps(device, steps):
    for step in steps:
        apply_step(device, step)


def item_box(nodes, item):
    if "box" in item:
        box = tuple(item["box"])
    elif "union" in item:
        box = union([item_box(nodes, {"select": s, "exact": item.get("exact", False)}) for s in item["union"]])
    else:
        node = find(nodes, item["select"])
        if node is None:
            raise Failure("highlight not found: " + describe(item["select"]))
        box = hit_box(nodes, node, item.get("exact", False))
    grow = item.get("grow", [0, 0, 0, 0])
    return (box[0] + grow[0], box[1] + grow[1], box[2] + grow[2], box[3] + grow[3])


def clamp_box(box, margin):
    return (max(0, box[0] - margin), max(0, box[1] - margin), min(SCREEN[0], box[2] + margin), min(SCREEN[1], box[3] + margin))


def badge_center(rect, where, style, at=None):
    if at:
        return tuple(at)
    d = style["badge"]
    left, top, right, bottom = rect
    spots = {
        "tl": (left, top), "tr": (right, top), "bl": (left, bottom), "br": (right, bottom),
        "l": (left - d * 0.72, (top + bottom) / 2), "r": (right + d * 0.72, (top + bottom) / 2),
        "t": ((left + right) / 2, top - d * 0.72), "b": ((left + right) / 2, bottom + d * 0.72),
    }
    x, y = spots[where]
    half = d / 2 + 6
    return min(max(x, half), SCREEN[0] - half), min(max(y, half), SCREEN[1] - half)


def marks(nodes, shot, style):
    rects, badges = [], []
    for item in shot.get("highlight", []):
        box = item_box(nodes, item)
        rect = clamp_box(box, item.get("pad", style["pad"]))
        if item.get("ring", True) or item.get("clear"):
            width, height = rect[2] - rect[0], rect[3] - rect[1]
            radius = item.get("radius", min(style["radius"], min(width, height) / 2))
            rects.append((rect, radius, item.get("ring", True)))
        if item.get("n"):
            badges.append((badge_center(rect, item.get("badge", "tl"), style, item.get("badge_at")), item["n"]))
    return rects, badges


def ring(image, rect, radius, style):
    width = style["outline"]
    pad = width * 2
    left, top, right, bottom = [int(v) for v in rect]
    w, h = right - left + pad * 2, bottom - top + pad * 2
    k = 4
    mask = Image.new("L", (w * k, h * k), 0)
    ImageDraw.Draw(mask).rounded_rectangle((pad * k, pad * k, (w - pad) * k, (h - pad) * k), radius=radius * k, outline=255, width=width * k)
    mask = mask.resize((w, h), Image.LANCZOS)
    image.paste(Image.new("RGB", (w, h), tuple(style["accent"])), (left - pad, top - pad), mask)


def badge(image, point, number, style):
    d = style["badge"]
    size = d + 14
    k = 4
    layer = Image.new("RGBA", (size * k, size * k), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    mid, radius = size * k / 2, d * k / 2
    ring_width = 5 * k
    draw.ellipse((mid - radius - ring_width, mid - radius - ring_width, mid + radius + ring_width, mid + radius + ring_width), fill=(255, 255, 255, 255))
    draw.ellipse((mid - radius, mid - radius, mid + radius, mid + radius), fill=tuple(style["accent"]) + (255,))
    font = ImageFont.truetype(str(FONT), int(d * 0.64 * k))
    left, top, right, bottom = draw.textbbox((0, 0), str(number), font=font)
    draw.text((mid - (left + right) / 2, mid - (top + bottom) / 2), str(number), font=font, fill=tuple(style["ink"]) + (255,))
    layer = layer.resize((size, size), Image.LANCZOS)
    image.paste(layer, (int(point[0] - size / 2), int(point[1] - size / 2)), layer)


def annotate(image, rects, badges, style):
    if not rects and not badges:
        return image
    out = image
    if rects:
        if style["tint"] > 0:
            mask = Image.new("L", image.size, 0)
            draw = ImageDraw.Draw(mask)
            for rect, radius, _ in rects:
                draw.rounded_rectangle(rect, radius=radius, fill=255)
            mask = mask.filter(ImageFilter.GaussianBlur(style["feather"]))
            dark = Image.blend(image, Image.new("RGB", image.size, (0, 0, 0)), style["tint"])
            out = Image.composite(image, dark, mask)
        for rect, radius, ringed in rects:
            if ringed:
                ring(out, rect, radius, style)
    for point, number in badges:
        badge(out, point, number, style)
    return out


def crop_box(shot, rects, badges, style):
    full = (0, style["status_bar"], SCREEN[0], style["bottom"])
    spec = shot.get("crop")
    if spec == "auto":
        boxes = [r[0] for r in rects] + [(p[0] - 40, p[1] - 40, p[0] + 40, p[1] + 40) for p, _ in badges]
        left, top, right, bottom = clamp_box(union(boxes), shot.get("margin", 40))
        return (0, max(top, full[1]), SCREEN[0], min(bottom, full[3])) if shot.get("full_width", True) else (left, max(top, full[1]), right, min(bottom, full[3]))
    if isinstance(spec, dict):
        return (spec.get("left", full[0]), spec.get("top", full[1]), spec.get("right", full[2]), spec.get("bottom", full[3]))
    return tuple(spec) if spec else full


def encode(image, kb):
    chosen = None
    for quality in range(92, 39, -4):
        buffer = io.BytesIO()
        image.save(buffer, "WEBP", quality=quality, method=6)
        chosen = (buffer.getvalue(), quality)
        if len(chosen[0]) <= kb * 1024:
            break
    return chosen


def finish(image, box, style, kb):
    cropped = image.crop(box)
    width = min(style["max_width"], round(cropped.width * style["scale"]))
    height = round(cropped.height * width / cropped.width)
    return encode(cropped.resize((width, height), Image.LANCZOS), kb)


def commons_photo(name):
    target = WORK / "photos" / (name + ".jpg")
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        import effect_thumbs
        effect_thumbs.download(json.loads(SHOWCASE.read_text())["photos"][name]["commons"], target)
    return target


def make_media(name, fallback, arguments):
    target = WORK / "media" / name
    if target.is_file():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    if shutil.which("ffmpeg"):
        done = subprocess.run(["ffmpeg", "-v", "error", "-y"] + arguments + [str(target)], capture_output=True)
        if done.returncode == 0:
            return target
    target.unlink(missing_ok=True)
    return TEST_MEDIA / fallback


def music_clip():
    expression = "0.55*sin(2*PI*196*t)*exp(-5*mod(t,0.5))+0.3*sin(2*PI*294*t)*exp(-2.5*mod(t,1))+0.15*sin(2*PI*392*t)*exp(-1.5*mod(t,2))"
    return make_media("music.m4a", "test-audio.m4a", ["-f", "lavfi", "-i", "aevalsrc='%s':s=44100:d=10" % expression, "-c:a", "aac", "-b:a", "96k"])


def beach_clip():
    sunset = commons_photo("sunset")
    graph = ("[0:v]scale=1920:-2,zoompan=z='1.0+0.0004*on':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=1280x720:fps=30[bg];"
             "[bg][1:v]overlay=x='320+120*t':y='440+30*sin(t*2)':shortest=1,format=yuv420p[v]")
    return make_media("beach.mp4", "test-video.mp4", [
        "-loop", "1", "-framerate", "30", "-i", str(sunset), "-f", "lavfi", "-i",
        "color=c=0xFF5A36:s=64x64:r=30,format=rgba,drawbox=x=12:y=12:w=40:h=40:color=white:t=fill",
        "-t", "5", "-filter_complex", graph, "-map", "[v]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-an"])


def long_clip():
    sunset = commons_photo("sunset")
    graph = ("[0:v]scale=1920:-2,zoompan=z='1.0+0.0002*on':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=1280x720:fps=30[bg];"
             "[bg][1:v]overlay=x='320+20*t':y='440+30*sin(t*0.6)':shortest=1,format=yuv420p[v]")
    return make_media("long.mp4", "test-video.mp4", [
        "-loop", "1", "-framerate", "30", "-i", str(sunset), "-f", "lavfi", "-i",
        "color=c=0xFF5A36:s=64x64:r=30,format=rgba,drawbox=x=12:y=12:w=40:h=40:color=white:t=fill",
        "-t", "45", "-filter_complex", graph, "-map", "[v]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-g", "30", "-an"])


def media_uri(package, name):
    return "file:///data/user/0/%s/files/media/%s" % (package, name)


def record(package, name, kind, path, extra=""):
    return '<media uri="%s" filename="%s" title="%s" type="%s" size="%d" orientation="0"%s/>' % (media_uri(package, name), name, name, kind, Path(path).stat().st_size, extra)


def hd_clip():
    sunset = commons_photo("sunset")
    graph = ("[0:v]scale=2400:-2,zoompan=z='1.0+0.0006*on':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=1920x1080:fps=30,noise=alls=14:allf=t[bg];"
             "[bg][1:v]overlay=x='360+180*t':y='560+40*sin(t*2)':shortest=1,format=yuv420p[v]")
    return make_media("sunset-hd.mp4", "test-video.mp4", [
        "-loop", "1", "-framerate", "30", "-i", str(sunset), "-f", "lavfi", "-i",
        "color=c=0xFF5A36:s=96x96:r=30,format=rgba,drawbox=x=18:y=18:w=60:h=60:color=white:t=fill",
        "-t", "10", "-filter_complex", graph, "-map", "[v]", "-c:v", "libx264", "-preset", "veryfast",
        "-b:v", "14M", "-maxrate", "14M", "-bufsize", "28M", "-g", "30", "-an"])


def photo_record(package, name, path):
    width, height = Image.open(path).size
    return record(package, name, "image/jpeg", path, ' width="%d" height="%d"' % (width, height))


def scene_wrap(title, width, height, total, body, fps=30):
    return ('<scene title="%s" width="%d" height="%d" exportWidth="%d" exportHeight="%d" fps="%d" totalTime="%d" bgcolor="#ff000000" '
            'precompose="dynamicResolution" am="app.absentmotion" amplatform="android">%s</scene>') % (esc(title), width, height, width, height, fps, total, body)


def photo_layer(ident, label, uri, half, at, start, end, extra=""):
    return ('<shape id="%d" label="%s" startTime="%d" endTime="%d" fillType="media" mediaFillMode="fill" s=".rect" fillImage="%s">'
            '<transform><location value="%s,0"/></transform><property name="size" type="vec2" value="%s"/>%s</shape>') % (ident, esc(label), start, end, uri, at, half, extra)


def text_layer(ident, label, content, size, weight, at, color, start, end, opacity=""):
    return ('<text id="%d" label="%s" startTime="%d" endTime="%d" fillType="color" mediaFillMode="fill" size="%d" '
            'font="googlefonts?name=Roboto&amp;weight=%d" wrapWidth="1600" align="center"><transform><location value="%s,0"/>%s</transform>'
            '<fillColor value="%s"/><content>%s</content></text>') % (ident, esc(label), start, end, size, weight, at, opacity, color, esc(content))


def demo_scene(package):
    lake, music = commons_photo("landscape"), music_clip()
    end = 5999
    fade = '<opacity><kf t="0.000000" v="0.000000"/><kf t="0.250000" v="1.000000" e="cubicBezier 0.42 0 0.58 1"/></opacity>'
    sun = ('<shape id="5" label="Sun" startTime="0" endTime="%d" fillType="color" mediaFillMode="fill" s=".star"><transform><location>'
           '<kf t="0.000000" v="300,250,0"/><kf t="0.500000" v="1400,250,0" e="cubicBezier 0.42 0 0.58 1"/></location></transform>'
           '<fillColor value="#ffffd65c"/><property name="pointCount" type="float" value="8"/><property name="outerRadius" type="float" value="90"/>'
           '<property name="innerRadius" type="float" value="55"/><property name="offsetAngle" type="float" value="0"/>'
           '<effect id="absent.glow" hidden="false"><property name="radius" type="float" value="60"/></effect></shape>') % end
    shade = ('<shape id="2" label="Shade" startTime="0" endTime="%d" fillType="color" mediaFillMode="fill" s=".rect"><transform><location value="960,900,0"/>'
             '<opacity value="0.45"/></transform><fillColor value="#ff000000"/><property name="size" type="vec2" value="960,180"/></shape>') % end
    sound = ('<audio id="6" label="Music" startTime="0" endTime="%d" src="%s" outTime="6000" mediaFillMode="fill"><gain value="0.8"/></audio>'
             % (end, media_uri(package, "demo-music.m4a")))
    body = (photo_record(package, "demo-lake.jpg", lake) + record(package, "demo-music.m4a", "audio/mp4", music, ' duration="10000000"') + sound
            + photo_layer(1, "Lake", media_uri(package, "demo-lake.jpg"), "960,540", "960,540", 0, end) + shade
            + text_layer(3, "Summer Trip", "Summer Trip", 40, 700, "960,830", "#ffffffff", 0, end, fade)
            + text_layer(4, "A quiet morning", "A quiet morning at the lake", 20, 400, "960,960", "#ffffd65c", 600, end) + sun)
    return scene_wrap("Mountain Lake", 1920, 1080, end, body), {"demo-lake.jpg": lake, "demo-music.m4a": music}


def clips_scene(package):
    lake, sunset = commons_photo("landscape"), commons_photo("sunset")
    body = (photo_record(package, "demo-lake.jpg", lake) + photo_record(package, "demo-sunset.jpg", sunset)
            + photo_layer(1, "Lake", media_uri(package, "demo-lake.jpg"), "960,540", "960,540", 0, 3499)
            + photo_layer(2, "Sunset", media_uri(package, "demo-sunset.jpg"), "960,540", "960,540", 3000, 5999))
    return scene_wrap("Two Clips", 1920, 1080, 5999, body), {"demo-lake.jpg": lake, "demo-sunset.jpg": sunset}


def video_scene(package):
    clip, sunset = beach_clip(), commons_photo("sunset")
    body = (record(package, "demo-beach.mp4", "video/mp4", clip, ' width="1280" height="720" duration="5000000" fps="30.00"')
            + '<shape id="1" label="Beach" startTime="0" endTime="4999" fillType="media" mediaFillMode="fill" s=".rect" fillVideo="%s" inTime="0" outTime="5000" speed="1.000000">'
            '<transform><location value="960,540,0"/></transform><property name="size" type="vec2" value="960,540"/><gain value="1.000000"/></shape>' % media_uri(package, "demo-beach.mp4")
            + text_layer(2, "Buoy", "Buoy", 24, 700, "960,900", "#ffffffff", 0, 4999))
    return scene_wrap("Beach Clip", 1920, 1080, 4999, body), {"demo-beach.mp4": clip, "demo-sunset.jpg": sunset}


def long_scene(package):
    clip = long_clip()
    body = (record(package, "demo-long.mp4", "video/mp4", clip, ' width="1280" height="720" duration="45000000" fps="30.00"')
            + '<shape id="1" label="Beach" startTime="0" endTime="44999" fillType="media" mediaFillMode="fill" s=".rect" fillVideo="%s" inTime="0" outTime="45000" speed="1.000000">'
            '<transform><location value="960,540,0"/></transform><property name="size" type="vec2" value="960,540"/><gain value="1.000000"/></shape>' % media_uri(package, "demo-long.mp4"))
    return scene_wrap("Long Clip", 1920, 1080, 44999, body), {"demo-long.mp4": clip}


def heart_scene(package):
    lake = commons_photo("landscape")
    heart = ('<shape id="2" label="Heart" startTime="0" endTime="5999" fillType="color" mediaFillMode="fill"><transform><location value="960,500,0"/>'
             '<scale value="3.6,3.6"/></transform><fillColor value="#ffe5484d"/><path d="M 0 70 C -120 -30 -60 -110 0 -45 C 60 -110 120 -30 0 70 Z"/></shape>')
    body = photo_record(package, "demo-lake.jpg", lake) + photo_layer(1, "Lake", media_uri(package, "demo-lake.jpg"), "960,540", "960,540", 0, 5999) + heart
    return scene_wrap("Heart Shape", 1920, 1080, 5999, body), {"demo-lake.jpg": lake}


SCENES = {"demo": demo_scene, "clips": clips_scene, "video": video_scene, "heart": heart_scene, "long": long_scene}


def load_spec():
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    spec["style"] = dict(STYLE, **spec.get("style", {}))
    ids = [s["id"] for s in spec["shots"]]
    if len(ids) != len(set(ids)):
        sys.exit("help_shots: duplicate shot ids in " + str(SPEC))
    return spec


def languages():
    return ["en"] + sorted(p.stem for p in TRANSLATIONS.glob("*.json"))


def starts_of(shot):
    start = shot.get("start", "home")
    return start if isinstance(start, dict) else {"home": True} if start == "home" else {"scene": start}


def scene_names(shots):
    return sorted({starts_of(s)["scene"] for s in shots if "scene" in starts_of(s)} | {step["scene"] for s in shots for step in s.get("steps", []) if "scene" in step})


def install_scenes(device, names):
    for name in names:
        xml, files = SCENES[name](device.package)
        work = WORK / "scenes"
        work.mkdir(parents=True, exist_ok=True)
        (work / (name + ".xml")).write_text(xml, encoding="utf-8")
        device.put(work / (name + ".xml"), name + ".xml", "files/shots")
        for remote, local in files.items():
            device.put(local, remote)


def prepare(device, shots, fresh):
    device.check_screen()
    if fresh:
        device.first_run()
    else:
        device.grant()
    names = sorted(SCENES)
    install_scenes(device, names)
    device.shell("mkdir", "-p", "/sdcard/Pictures/Demo", "/sdcard/Movies/Demo")
    for label, local in (("Lake", commons_photo("landscape")), ("Sunset", commons_photo("sunset")), ("Macaw", commons_photo("colour")), ("Flower", commons_photo("flower"))):
        device.publish(local, "Pictures/Demo/%s.jpg" % label)
    device.publish(beach_clip(), "Movies/Demo/Beach.mp4")
    device.publish(hd_clip(), "Movies/Demo/Sunset-HD.mp4")
    device.demo_mode()
    if fresh or any(starts_of(s).get("home") for s in shots):
        for name in names:
            device.scene(name, 45)
            device.settle(6.0)
            apply_step(device, {"tap": "Exit to project list", "quick": True})
            device.wait("New project", 30)
            device.settle(6.0)


def produce(device, shot, spec, keep):
    style = dict(spec["style"], **shot.get("style", {}))
    begin = starts_of(shot)
    if "scene" in begin:
        device.scene(begin["scene"], begin.get("frame", 45))
    else:
        device.home()
    device.settle(6.0)
    run_steps(device, shot.get("steps", []))
    image = device.settle(8.0)
    nodes = device.tree()
    rects, badges = marks(nodes, shot, style)
    box = crop_box(shot, rects, badges, style)
    for point, number in badges:
        if not inside(point, box):
            raise Failure("badge %s is outside the crop %s" % (number, list(box)))
    WORK.mkdir(parents=True, exist_ok=True)
    if keep:
        (WORK / "raw").mkdir(exist_ok=True)
        image.save(WORK / "raw" / (shot["id"] + ".png"))
    data, quality = finish(annotate(image.copy(), rects, badges, style), box, style, shot.get("max_kb", style["kb"]))
    IMAGES.mkdir(parents=True, exist_ok=True)
    (IMAGES / (shot["id"] + ".webp")).write_bytes(data)
    return len(data), quality, box


def selected(spec, args):
    if args.only:
        wanted = set(args.only.split(","))
        unknown = wanted - {s["id"] for s in spec["shots"]}
        if unknown:
            sys.exit("help_shots: unknown shot ids " + ", ".join(sorted(unknown)))
        return [s for s in spec["shots"] if s["id"] in wanted]
    return [s for s in spec["shots"] if args.all or s.get("phase", 1) == args.phase]


def command_run(args):
    spec = load_spec()
    shots = selected(spec, args)
    if not shots:
        sys.exit("help_shots: no shots selected")
    device = Device(args.package)
    prepare(device, shots, args.fresh)
    failed = []
    for shot in shots:
        started = time.time()
        try:
            size, quality, box = produce(device, shot, spec, args.keep)
            print("%-22s %6.1f KB  q%d  crop %s  %.0fs" % (shot["id"], size / 1024, quality, list(box), time.time() - started))
        except Failure as error:
            failed.append(shot["id"])
            print("%-22s FAILED: %s" % (shot["id"], error))
            WORK.mkdir(parents=True, exist_ok=True)
            device.screenshot().save(WORK / ("failed-" + shot["id"] + ".png"))
    device.shell("am", "force-stop", device.package)
    if failed:
        sys.exit("help_shots: %d shot(s) failed: %s" % (len(failed), ", ".join(failed)))


def command_dump(args):
    device = Device(args.package)
    device.check_screen()
    steps = [json.loads(s) for s in args.step]
    install_scenes(device, scene_names([{"steps": steps}]))
    run_steps(device, steps)
    image = device.settle(6.0)
    WORK.mkdir(parents=True, exist_ok=True)
    image.save(WORK / "dump.png")
    image.resize((image.width * 5 // 9, image.height * 5 // 9), Image.LANCZOS).save(WORK / "dump.jpg", quality=80)
    for node in device.tree():
        if node["text"] or node["desc"] or node["clickable"]:
            print(node["box"], "C" if node["clickable"] else " ", "K" if node["checked"] else " ", repr(node["text"]) if node["text"] else "", ("desc=" + repr(node["desc"])) if node["desc"] else "")
    print("screenshot: %s and %s" % (WORK / "dump.png", WORK / "dump.jpg"))


def split_front(text):
    if text.startswith("---\n"):
        head, sep, body = text[4:].partition("\n---\n")
        return "---\n" + head + sep, body
    return "", text


def split_blocks(body):
    blocks, offset, start, last, fence = [], 0, None, 0, False
    for line in body.split("\n"):
        end = offset + len(line)
        if line.startswith("```"):
            fence = not fence
        if line.strip() or fence or line.startswith("```"):
            if start is None:
                start = offset
            last = end
        elif start is not None:
            blocks.append(block_of(body, start, last))
            start = None
        offset = end + 1
    if start is not None:
        blocks.append(block_of(body, start, last))
    return blocks


def block_of(body, start, end):
    first = body[start:end].split("\n", 1)[0]
    if re.match(r"^#{2,4} ", first):
        kind = "heading"
    elif IMAGE_LINE.match(body[start:end].strip()):
        kind = "image"
    elif first.startswith("```"):
        kind = "code"
    elif first.startswith("|"):
        kind = "table"
    elif re.match(r"^\s*([-*]|\d+\.)\s+", first):
        kind = "list"
    elif first.startswith(":::"):
        kind = "container"
    elif first.startswith(">"):
        kind = "quote"
    else:
        kind = "paragraph"
    return {"start": start, "end": end, "kind": kind, "first": first}


def sections(blocks):
    out = [{"title": "", "blocks": []}]
    for block in blocks:
        if block["kind"] == "heading":
            out.append({"title": re.sub(r"^#{2,4} ", "", block["first"]).strip(), "heading": block, "blocks": []})
        elif block["kind"] != "image":
            out[-1]["blocks"].append(block)
    return out


def strip_pictures(body, ids):
    for ident in ids:
        pattern = re.compile(r"(?m)^!\[[^\]]*\]\(img/%s\.webp\)[ \t]*\n?" % re.escape(ident))
        while True:
            found = pattern.search(body)
            if not found:
                break
            before, after = body[:found.start()].rstrip("\n"), body[found.end():].lstrip("\n")
            body = before + ("\n\n" + after if after.strip() else "\n") if before else after
    return body


def anchor_of(english, translated, place, where):
    en_sections, tr_sections = sections(split_blocks(english)), sections(split_blocks(translated))
    if len(en_sections) != len(tr_sections):
        raise Failure("%s: the translation has %d headings, the English %d" % (where, len(tr_sections) - 1, len(en_sections) - 1))
    titles = [s["title"] for s in en_sections]
    wanted = place.get("section", "")
    if titles.count(wanted) != 1:
        raise Failure("%s: section %r matches %d headings" % (where, wanted, titles.count(wanted)))
    index = titles.index(wanted)
    en, tr = en_sections[index], tr_sections[index]
    n = place.get("block", 1)
    if n == 0:
        if not tr.get("heading"):
            raise Failure("%s: the intro has no heading to place a picture after" % where)
        return tr["heading"]["end"]
    if n == -1:
        if not tr["blocks"]:
            raise Failure("%s: section %r is empty" % (where, wanted))
        return tr["blocks"][-1]["end"]
    if len(en["blocks"]) != len(tr["blocks"]) or n > len(tr["blocks"]):
        raise Failure("%s: section %r has %d blocks, the English %d: place the picture at the end of the section (block -1) or in another section" % (where, wanted, len(tr["blocks"]), len(en["blocks"])))
    if en["blocks"][n - 1]["kind"] != tr["blocks"][n - 1]["kind"]:
        raise Failure("%s: block %d of section %r is a %s, the English a %s" % (where, n, wanted, tr["blocks"][n - 1]["kind"], en["blocks"][n - 1]["kind"]))
    return tr["blocks"][n - 1]["end"]


def placements(spec, page, ready):
    out = []
    for shot in spec["shots"]:
        if shot["id"] not in ready:
            continue
        for place in [shot] + shot.get("also", []):
            if place.get("page") == page:
                out.append((shot, place))
    return out


def picture_line(shot, tag):
    return "![%s](img/%s.webp)" % (shot["alt"][tag], shot["id"])


def place_in(english_body, body, slug, tag, items, all_ids):
    stripped = strip_pictures(body, all_ids)
    chosen = []
    for order, (shot, place) in enumerate(items):
        where = "%s/%s picture %s" % (tag, slug, shot["id"])
        chosen.append((anchor_of(strip_pictures(english_body, all_ids), stripped, place, where), order, picture_line(shot, tag)))
    for offset, _, line in sorted(chosen, key=lambda c: (c[0], c[1]), reverse=True):
        stripped = stripped[:offset] + "\n\n" + line + stripped[offset:]
    return stripped


def mentions(body, ids):
    return any(found.group(2) in ids for found in (IMAGE_LINE.match(line.strip()) for line in body.split("\n")) if found)


def alt_problem(alt):
    if not isinstance(alt, str) or not alt or alt != alt.strip():
        return "is empty"
    if re.search(r'[\[\]"<>*`\n]|\{\{', alt):
        return "has one of [ ] \" < > * ` {{ or a line break"
    return None


def command_place(args):
    spec = load_spec()
    all_ids = [s["id"] for s in spec["shots"]]
    ready = {s["id"] for s in spec["shots"] if (IMAGES / (s["id"] + ".webp")).is_file()}
    tags = languages()
    problems = ["%s: alt text for %s %s" % (s["id"], t, alt_problem(s.get("alt", {}).get(t))) for s in spec["shots"] if s["id"] in ready for t in tags if alt_problem(s.get("alt", {}).get(t))]
    if problems:
        sys.exit("\n".join(problems))
    translations = {t: json.loads((TRANSLATIONS / (t + ".json")).read_text(encoding="utf-8")) for t in tags if t != "en"}
    changed, errors, stale, writes = [], [], [], []
    for path in sorted(PAGES.glob("*.md")):
        slug = path.stem
        items = placements(spec, slug, ready)
        front, english = split_front(path.read_text(encoding="utf-8"))
        if not items and not mentions(english, all_ids):
            continue
        try:
            rewritten = place_in(english, english, slug, "en", items, all_ids)
        except Failure as error:
            errors.append(str(error))
            continue
        if front + rewritten != path.read_text(encoding="utf-8"):
            changed.append(str(path.relative_to(ROOT)))
            writes.append((path, front + rewritten))
        for tag, data in translations.items():
            page = data["pages"].get(slug)
            if page is None:
                continue
            try:
                body = place_in(english, page["body"], slug, tag, items, all_ids)
            except Failure as error:
                stale.append("%s/%s" % (tag, slug))
                continue
            if body != page["body"]:
                page["body"] = body
                changed.append("%s/%s" % (tag, slug))
    if errors or (stale and args.strict):
        sys.exit("help_shots place: nothing written, fix these first:\n" + "\n".join(errors + ["stale translation " + name for name in stale]))
    if not args.dry_run:
        for path, text in writes:
            path.write_text(text, encoding="utf-8")
        for tag, data in translations.items():
            (TRANSLATIONS / (tag + ".json")).write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("help_shots place: %d page text(s) %s" % (len(changed), "would change" if args.dry_run else "updated"))
    if stale:
        print("help_shots place: skipped %d translated page(s) whose blocks no longer match the English (re-translate them, then run place again): %s" % (len(stale), ", ".join(stale)))


def command_check(args):
    spec = load_spec()
    problems = []
    tags = languages()
    total = 0
    for shot in spec["shots"]:
        image = IMAGES / (shot["id"] + ".webp")
        if image.is_file():
            total += image.stat().st_size
        elif shot.get("phase", 1) == 1 and args.strict:
            problems.append("%s: no picture yet, run help_shots.py run --only %s" % (shot["id"], shot["id"]))
        for tag in tags if shot.get("phase", 1) == 1 or image.is_file() else ["en"]:
            found = alt_problem(shot.get("alt", {}).get(tag))
            if found:
                problems.append("%s: alt text for %s %s" % (shot["id"], tag, found))
        if not shot.get("page"):
            problems.append("%s: no page" % shot["id"])
        elif not (PAGES / (shot["page"] + ".md")).is_file():
            problems.append("%s: page %s does not exist" % (shot["id"], shot["page"]))
    if total > spec["style"]["budget_kb"] * 1024:
        problems.append("the pictures take %.0f KB, the budget is %d KB" % (total / 1024, spec["style"]["budget_kb"]))
    for line in problems:
        print(line, file=sys.stderr)
    print("help_shots check: %d problem(s); %d shots, %.0f KB of pictures" % (len(problems), len(spec["shots"]), total / 1024))
    return 1 if problems else 0


def command_blocks(args):
    front, body = split_front((PAGES / (args.page + ".md")).read_text(encoding="utf-8"))
    for section in sections(split_blocks(body)):
        print("section %r" % section["title"])
        for number, block in enumerate(section["blocks"], 1):
            print("  block %d  %-9s %s" % (number, block["kind"], block["first"][:78]))


def command_list(args):
    spec = load_spec()
    for shot in spec["shots"]:
        image = IMAGES / (shot["id"] + ".webp")
        size = "%6.1f KB" % (image.stat().st_size / 1024) if image.is_file() else "    none"
        print("%-24s phase %d  %-18s %s" % (shot["id"], shot.get("phase", 1), shot.get("page", "?"), size))


USAGE = """
how it works:
  The shots are described in site/data/help-shots.json: where a picture goes (page, section, block, also), the words under it
  (alt, one per language), how to reach the screen (start, steps), what to circle (highlight) and how to crop it.
  run drives the debug test copy of the app on the emulator in ANDROID_SERIAL (AVD AbsentApi33, 1080x2400), writes
  site/assets/img/<id>.webp, place writes the picture lines into site/pages and every help translation.

re-run everything:
  ANDROID_SERIAL=emulator-5558 python3 tools/help_shots.py run --fresh --all
  python3 tools/help_shots.py place && python3 tools/site_build.py && python3 tools/site_build.py --check

add a shot:
  python3 tools/help_shots.py blocks PAGE              numbered sections and blocks of a page, to choose the place
  python3 tools/help_shots.py dump STEP... (JSON)      run steps and print the buttons on the screen, to write steps and highlights
  add an object to the spec, then: run --only ID, place, site_build.py, site_build.py --check
"""


def main():
    parser = argparse.ArgumentParser(description="Capture the annotated pictures of the help from an emulator and place them in the pages",
                                     epilog=USAGE, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--package", default=os.environ.get("AM_PACKAGE") or "app.absentmotion.t")
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="capture shots from the emulator named by ANDROID_SERIAL")
    run.add_argument("--only", help="comma separated shot ids")
    run.add_argument("--phase", type=int, default=1)
    run.add_argument("--all", action="store_true")
    run.add_argument("--fresh", action="store_true", help="clear the app data and go through the first start first")
    run.add_argument("--keep", action="store_true", help="also keep the raw screenshots in build/help-shots/raw")
    dump = commands.add_parser("dump", help="run steps (JSON objects) and print what is on the screen")
    dump.add_argument("step", nargs="*")
    place = commands.add_parser("place", help="write the picture lines into the pages and every translation")
    place.add_argument("--dry-run", action="store_true")
    place.add_argument("--strict", action="store_true", help="fail instead of skipping translated pages that no longer match the English")
    check = commands.add_parser("check", help="check the spec")
    check.add_argument("--strict", action="store_true")
    commands.add_parser("list", help="list the shots")
    blocks = commands.add_parser("blocks", help="print the sections and numbered blocks of a page, to choose where a picture goes")
    blocks.add_argument("page")
    args = parser.parse_args()
    handler = {"run": command_run, "dump": command_dump, "place": command_place, "check": command_check, "list": command_list, "blocks": command_blocks}[args.command]
    return handler(args) or 0


if __name__ == "__main__":
    sys.exit(main())
