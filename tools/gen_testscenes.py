#!/usr/bin/env python3
"""Writes the sample scenes in testdata/scenes/ (the project's own test projects, in the scene format). Nothing here is read from another app: the layers are made up, the shapes come from
app/src/main/assets/shapes.xml, the media are the files in testdata/media.

  tools/gen_testscenes.py          rewrite testdata/scenes/*.xml
  tools/gen_testscenes.py --check  exit 1 when a file on disk differs from what the script writes

The tests (app/src/test, desktop/src/test), desktop/self-check.sh and testdata/golden/README.md read these files.
Media uris are paths relative to the project root (testdata/media/...): run the Mac renderer from the project root.
"""
import colorsys
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "testdata/scenes")
MEDIA = "testdata/media"

class N:
    def __init__(self, name, attrs=None, kids=None, text=""):
        self.name, self.attrs, self.kids, self.text = name, dict(attrs or {}), list(kids or []), text

def esc(s):
    out = []
    for c in s:
        out.append({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "\n": "&#10;", "\r": "&#13;", "\t": "&#9;"}.get(c, c))
    return "".join(out)

def write(n, depth=0):
    pad = "  " * depth
    head = pad + "<" + n.name + "".join(f' {k}="{esc(str(v))}"' for k, v in n.attrs.items())
    if not n.kids and not n.text:
        return head + " />\n"
    if not n.kids:
        return head + ">" + esc(n.text) + "</" + n.name + ">\n"
    return head + ">\n" + "".join(write(k, depth + 1) for k in n.kids) + pad + "</" + n.name + ">\n"

def save(name, root):
    text = "<?xml version='1.0' encoding='UTF-8' ?>\n" + write(root)
    path = os.path.join(OUT, name)
    if "--check" in sys.argv:
        if not os.path.exists(path) or open(path, encoding="utf-8").read() != text:
            print("differs:", path)
            sys.exit(1)
        return
    os.makedirs(OUT, exist_ok=True)
    open(path, "w", encoding="utf-8", newline="").write(text)
    print("wrote", os.path.relpath(path, ROOT))

def f(*v):
    return ",".join("%.6f" % x for x in v)

def fstart(fps, frame):
    return frame * 1000 // fps

def fend(fps, frame):
    """The last millisecond of [frame] (a layer's endTime)."""
    return fstart(fps, frame + 1) - 1

def rgb(h, s=0.7, v=0.95, a=255):
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, s, v)
    return "#%02x%02x%02x%02x" % (a, round(r * 255), round(g * 255), round(b * 255))

def val(tag, v, /, **attrs):
    return N(tag, {**attrs, "value": v})

def keys(tag, frames, /, **attrs):
    """Keyframes: [(t, value, easing or None), ...]; t is a fraction of the layer's length."""
    return N(tag, attrs, [N("kf", {"t": "%.6f" % t, "v": v, **({"e": e} if e else {})}) for t, v, e in frames])

def anim(tag, v, /, **attrs):
    return keys(tag, v, **attrs) if isinstance(v, list) else val(tag, v, **attrs)

def transform(location=None, pivot=None, scale=None, rotation=None, skew=None, opacity=None):
    parts = [("location", location), ("pivot", pivot), ("scale", scale), ("rotation", rotation), ("skew", skew), ("opacity", opacity)]
    return N("transform", kids=[anim(n, v) for n, v in parts if v is not None])

def prop(name, kind, v):
    return anim("property", v, name=name, type=kind)

def layer(kind, id, label, start, end, kids=(), **attrs):
    return N(kind, {"id": id, "label": label, "startTime": start, "endTime": end, **attrs}, kids)

def shape(id, label, start, end, s=None, color="#ffffffff", tf=None, kids=(), fill="color", **attrs):
    a = {"fillType": fill, "mediaFillMode": "fill"}
    if s:
        a["s"] = s
    a.update(attrs)
    k = [tf] if tf is not None else []
    if color is not None and fill != "none":
        k.append(anim("fillColor", color))
    return layer("shape", id, label, start, end, k + list(kids), **a)

def text(id, label, start, end, content, font, size, align, wrap, tf, color="#ffffffff", kids=(), fill="color", **attrs):
    a = {"fillType": fill, "mediaFillMode": "fill", "size": "%.6f" % size, "font": font, "wrapWidth": wrap, "align": align}
    a.update(attrs)
    k = [tf, anim("fillColor", color)] if color is not None else [tf]
    return layer("text", id, label, start, end, k + list(kids) + [N("content", text=content)], **a)

def scene(title, w, h, fps, total, bg, kids, **extra):
    attrs = {"title": title, "width": w, "height": h, "exportWidth": w, "exportHeight": h, "precompose": "dynamicResolution",
             "bgcolor": bg, "totalTime": total, "fps": fps, "am": "app.absentmotion", "amplatform": "android",
             "retime": "freeze", "retimeAdaptFPS": "false"}
    attrs.update(extra)
    return N("scene", attrs, kids)

def border(direction, color, size, id, **a):
    return N("border", {"direction": direction, "id": id, **a}, [val("color", color), val("size", size)])

def shadow(color="#ff000000", opacity=0.7, size=16.0, hardness=0.0, offset=(12.0, 12.0)):
    return N("shadow", {"direction": "outside"}, [val("color", color), val("opacity", "%.6f" % opacity), val("size", "%.6f" % size),
                                                  val("hardness", "%.6f" % hardness), val("offset", f(*offset))])

def gradient(kind, c0, c1, start="0.000000,0.500000", end="1.000000,0.500000"):
    return N("gradient", {"type": kind, "startColor": c0, "endColor": c1, "start": start, "end": end})

def group_scene(w, h, fps, total, kids, **extra):
    """The nested scene of a group: as big as the project, transparent."""
    attrs = {"title": "", "width": w, "height": h, "exportWidth": w, "exportHeight": h, "bgcolor": "#00000000", "totalTime": total, "fps": fps}
    attrs.update(extra)
    return N("scene", attrs, kids)

def shapes():
    fps, frames = 30, 90
    end = fend(fps, frames - 1)
    catalog = [s for s in ET.parse(os.path.join(ROOT, "app/src/main/assets/shapes.xml")).getroot() if s.get("s")]

    def copy(e):
        return N(e.tag, dict(e.attrib), [copy(c) for c in e])

    layers = []
    cols, cw, ch, x0, y0 = 4, 270, 300, 135, 170
    cells = []

    def place(i):
        return (x0 + cw * (i % cols), y0 + ch * (i // cols))

    for i, s in enumerate(catalog):
        x, y = place(i)
        sid = s.get("s")
        pivot = f(125, -125, 0) if sid in (".arrow", ".wideline") else None
        tf = transform(location=f(x, y, 0), scale=f(0.6, 0.6), pivot=pivot)
        layers.append(shape(1001 + i, s.get("name"), 0, end, s=sid, color=rgb(i / len(catalog)), tf=tf,
                            kids=[copy(c) for c in s], fill=s.get("fillType")))
    n = len(catalog)
    heart = N("path", {"d": "M 0 70 C -120 -30 -60 -110 0 -45 C 60 -110 120 -30 0 70 Z"})
    hole = N("path", kids=[N("contour", {"d": "M -90 -90 L 90 -90 L 90 90 L -90 90 Z", "closed": "true"}),
                           N("contour", {"d": "M -40 -40 L 40 -40 L 40 40 L -40 40 Z", "closed": "true", "exclude": "true"})])

    def knot(p, i=None, o=None):
        return N("knot", kids=[val("p", f(*p)) if not isinstance(p, list) else keys("p", p)] +
                 ([val("in", f(*i))] if i else []) + ([val("out", f(*o))] if o else []))

    blob = N("path", {"closed": "true"}, [
        knot((-80, 60), (-30, 0), (30, 0)),
        knot((0, -90), (-40, 0), (40, 0)),
        knot([(0.0, f(80, 60), None), (1.0, f(80, -10), "cubicBezier 0.42 0 0.58 1")], (0, -40), (0, 40)),
    ])
    for j, (label, path) in enumerate([("Drawn path", heart), ("Drawn contours", hole), ("Drawn knots", blob)]):
        x, y = place(n + j)
        layers.append(shape(1001 + n + j, label, 0, end, color=rgb((n + j) / (n + 3)), tf=transform(location=f(x, y, 0), scale=f(0.6, 0.6)),
                            kids=[path]))
    return scene("Shapes", 1080, 1920, fps, end, "#ff1b1d27", layers)

def texts():
    fps, end = 30, fend(30, 89)

    def at(y, x=540):
        return transform(location=f(x, y, 0))

    def gf(family, weight=400, italic=False):
        return f"googlefonts?name={family}&weight={weight}" + ("&italic=1" if italic else "")

    layers = [
        text(2001, "Title", 0, end, "AbsentMotion", gf("Roboto", 700), 30, "center", 1000, at(200)),
        text(2002, "Gradient", 0, end, "Gradient fill", gf("Pacifico"), 36, "center", 1000, at(450), color=None, fill="gradient",
             kids=[gradient("linear", "#ffff7a00", "#ff00c2ff")]),
        text(2003, "Left", 0, end, "The quick brown fox jumps over the lazy dog.\nPack my box with five dozen liquor jugs.",
             gf("Lora", 400, True), 14, "left", 900, at(720), color="#ffe6e8f0"),
        text(2004, "Right", 0, end, "Right aligned\nin two lines", gf("Oswald", 500), 20, "right", 900, at(1000), color="#fff4c542"),
        text(2005, "Justify", 0, end, "Justified text spreads its words across the whole width of the box when a line wraps.",
             gf("Playfair Display", 400), 12, "justify", 700, at(1250), color="#ffcfd3e0"),
        text(2006, "Outlined", 0, end, "Border", gf("Roboto", 900), 40, "center", 1000, at(1500), color="#ff2d9cdb",
             kids=[border("outside", "#ffffffff", "8.000000", 1), shadow()]),
        text(2007, "Fading", 0, end, "Fade in and out", gf("Montserrat", 600), 22, "center", 1000,
             transform(location=f(540, 1740, 0), opacity=[(0.0, "0.000000", None), (0.5, "1.000000", "cubicBezier 0.42 0 0.58 1"),
                                                         (1.0, "0.000000", "cubicBezier 0.42 0 0.58 1")]), color="#ffffffff"),
    ]
    return scene("Text", 1080, 1920, fps, end, "#ff14161f", layers)

EASINGS = [
    ("linear", None),
    ("cubicBezier ease in", "cubicBezier 0.42 0 1 1"),
    ("cubicBezier ease out", "cubicBezier 0 0 0.58 1"),
    ("cubicBezier ease in out", "cubicBezier 0.42 0 0.58 1"),
    ("cubicBezier overshoot", "cubicBezier 0.25 -0.4 0.75 1.4"),
    ("bounce", "bounce 0.3 0.8"),
    ("elastic", "elastic 0.25 1 0.5 1"),
    ("cyclic", "cyclic 0.2857143 0 0.5 0 0"),
    ("random (6 numbers)", "random 0.5 0.5 0.5 0.5 0.5 0"),
    ("random (3 numbers)", "random 0.5 0.5 0.5"),
    ("step", "step 0.25 0"),
    ("elasticStep", "elasticStep 0.2 0.5"),
    ("reverse bounce", "reverse bounce 0.3 0.8"),
    ("reverse elastic", "reverse elastic 0.25 1 0.5 1"),
    ("reverse elasticStep", "reverse elasticStep 0.2 0.5"),
    ("reverse cubicBezier", "reverse cubicBezier 0.42 0 1 1"),
    ("local cubicBezier", "local cubicBezier 0.42 0 0.58 1"),
]

def easing():
    fps, end = 60, fend(60, 120)
    layers = []
    for i, (label, e) in enumerate(EASINGS):
        y = 110 + 100 * i
        layers.append(shape(3001 + i, label, 0, end, s=".circle", color=rgb(i / len(EASINGS)),
                            tf=transform(location=[(0.0, f(120, y, 0), None), (1.0, f(960, y, 0), e)]),
                            kids=[prop("size", "vec2", f(36, 36))]))
    y = 110 + 100 * len(EASINGS)
    layers.append(shape(3001 + len(EASINGS), "Chain", 0, end, s=".rect", color=[(0.0, "#ffff4d4d", None), (1.0, "#ff4d9dff", "bounce 0.3 0.8")],
                        tf=transform(
                            location=[(0.0, f(120, y, 0), None), (0.33, f(420, y, 0), "cubicBezier 0.42 0 0.58 1"),
                                      (0.66, f(700, y, 0), "elastic 0.25 1 0.5 1"), (1.0, f(960, y, 0), "reverse bounce 0.3 0.8")],
                            scale=[(0.0, f(1, 1), None), (1.0, f(1.5, 1.5), "step 0.25 0")],
                            rotation=[(0.0, "0.000000", None), (1.0, "-180.000000", "cyclic 0.2857143 0 0.5 0 0")]),
                        kids=[prop("size", "vec2", f(32, 32))]))
    return scene("Easing", 1080, 1920, fps, end, "#ff10131c", layers)

def audit():
    fps = 60
    end = fend(fps, 180)
    split = fstart(fps, 91)
    ease = "cubicBezier 0.42 0 0.58 1"
    layers = [
        shape(4001, "Backdrop", 0, end, s=".rect", color="#ff12151f", tag="+gray", tf=transform(location=f(540, 960, 0)),
              kids=[prop("size", "vec2", f(540, 960))]),
        shape(4002, "Mover", 0, end, s=".rect", color="#ffee4767", tag="+red", kids=[prop("size", "vec2", f(100, 100))], tf=transform(
            location=[(0.0, f(200, 400, 0), None), (0.5, f(880, 400, 0), ease), (1.0, f(200, 700, 0), "bounce 0.3 0.8")],
            pivot=[(0.0, f(0, 0), None), (1.0, f(20, -20), "step 0.25 0")],
            scale=[(0.0, f(1, 1), None), (0.5, f(1.6, 0.6), "elastic 0.25 1 0.5 1"), (1.0, f(1, 1), "reverse bounce 0.3 0.8")],
            rotation=[(0.0, "0.000000", None), (1.0, "-360.000000", "cyclic 0.2857143 0 0.5 0 0")],
            skew=[(0.0, f(0, 0), None), (1.0, f(0.3, 0), "random 0.5 0.5 0.5 0.5 0.5 0")],
            opacity=[(0.0, "1.000000", None), (0.5, "0.400000", "elasticStep 0.2 0.5"), (1.0, "1.000000", "local cubicBezier 0.25 0.1 0.25 1")])),
        layer("nullobj", 4003, "Parent", 0, end, [transform(
            location=[(0.0, f(300, 1100, 0), None), (1.0, f(780, 1100, 0), None)],
            rotation=[(0.0, "0.000000", None), (1.0, "-90.000000", ease)])], tag="+blue"),
        shape(4004, "Child", 0, end, s=".circle", color="#ff52ccbc", tag="+green", parent=4003, kids=[prop("size", "vec2", f(40, 40))],
              tf=transform(location=f(150, 0, 0), scale=[(0.0, f(1, 1), None), (1.0, f(0.5, 0.5), "step 0.25 0")])),
        shape(4005, "Hidden mover", 0, end, s=".star", color="#ff9621f0", tag="+purple", hidden="true",
              tf=transform(location=[(0.0, f(540, 1300, 0), None), (1.0, f(540, 1500, 0), "reverse cubicBezier 0.42 0 1 1")])),
        shape(4006, "Split A", 0, split - 1, s=".circle", color="#fffad961", tag="+yellow", kids=[prop("size", "vec2", f(50, 50))],
              tf=transform(location=[(0.0, f(150, 1650, 0), None), (1.0, f(540, 1650, 0), ease)])),
        shape(4007, "Split B", split, end, s=".circle", color="#fffad961", tag="+yellow", kids=[prop("size", "vec2", f(50, 50))],
              tf=transform(location=[(0.0, f(540, 1650, 0), None), (1.0, f(930, 1650, 0), ease)])),
        layer("embedScene", 4008, "Group", 0, end, [
            transform(location=f(540, 960, 0)),
            group_scene(1080, 1920, fps, end, [
                shape(5001, "Inner mover", 0, end, s=".rect", color="#fffaa261", kids=[prop("size", "vec2", f(60, 60))],
                      tf=transform(location=[(0.0, f(300, 300, 0), None), (1.0, f(780, 300, 0), ease)],
                                   rotation=[(0.0, "0.000000", None), (1.0, "-180.000000", "bounce 0.3 0.8")])),
                shape(5002, "Inner child", 0, end, s=".circle", color="#ff46a7ee", parent=5001, kids=[prop("size", "vec2", f(25, 25))],
                      tf=transform(location=f(120, 0, 0))),
            ], retime="off"),
        ], tag="+orange"),
        layer("embedScene", 4009, "Element instance", 0, end, [
            transform(location=f(540, 1450, 0)),
            group_scene(600, 300, fps, 1500, [
                shape(6001, "Element dot", 0, 1499, s=".circle", color="#ffd500ff", kids=[prop("size", "vec2", f(30, 30))],
                      tf=transform(location=[(0.0, f(100, 150, 0), None), (1.0, f(500, 150, 0), "bounce 0.3 0.8")])),
            ], type="element", retime="loop", retimeIn="500", retimeOut="1000", precompose="dynamicResolution"),
        ], link="9a1d3c52-6f1e-4b0b-8a57-3d2f6e0c1a44"),
    ]
    marks = [N("bookmark", {"t": str(t)}) for t in (500, split, 2500)]
    return scene("Audit", 1080, 1920, fps, end, "#ff101018", marks + layers)

def styles():
    fps, end = 30, fend(30, 89)
    X = (200, 540, 880)

    def at(c, r, **kw):
        return transform(location=f(X[c], 200 + 250 * r, 0), **kw)

    big = prop("size", "vec2", f(70, 70))
    layers = []
    n = [0]

    def add(label, s, color="#ff52ccbc", kids=(), tf=None, c=0, r=0, extra=None, **attrs):
        n[0] += 1
        layers.append(shape(7000 + n[0], label, 0, end, s=s, color=color, tf=tf or at(c, r), kids=list(kids), **(extra or {}), **attrs))

    for c, d in enumerate(("inside", "outside", "centered")):
        add(f"Border {d}", ".roundrect", "#ff1d6bd6", [big, prop("cornerRadius", "float", "25.000000"), border(d, "#fffad961", "12.000000", 1)], c=c, r=0)
    add("Shadow", ".star", "#ffee4767", [shadow()], c=0, r=1)
    add("Border and shadow", ".poly", "#ff9621f0", [border("outside", "#ffffffff", "8.000000", 1), shadow(opacity=0.5, size=24.0, offset=(0.0, 20.0))], c=1, r=1)
    add("Animated border", ".circle", "#fff0f0f0", [big, N("border", {"direction": "outside", "id": "1"}, [
        keys("color", [(0.0, "#ffff0000", None), (1.0, "#ff0000ff", ebz())]), keys("size", [(0.0, "2.000000", None), (1.0, "24.000000", ebz())])])], c=2, r=1)
    add("Linear gradient", ".roundrect", None, [big, prop("cornerRadius", "float", "25.000000"), gradient("linear", "#ffff7a00", "#ff00c2ff", "0,0", "1,1")],
        c=0, r=2, fill="gradient")
    add("Radial gradient", ".circle", None, [big, gradient("radial", "#ffffffff", "#ff1d6bd6", "0.5,0.5", "1,0.5")], c=1, r=2, fill="gradient")
    add("Sweep gradient", ".poly", None, [gradient("sweep", "#ff52ccbc", "#ffd500ff", "0.5,0.5", "1,0.5")], c=2, r=2, fill="gradient")
    def stroke(color, size, **a):
        return N("path-stroke", {"direction": "centered", "end-size": "1.500000", **a}, [val("color", color), val("size", size)])
    add("Arc round cap", ".arc", None, [prop("startAngle", "float", "0.000000"), prop("endAngle", "float", "270.000000"), prop("radius", "float", "90.000000"),
                                         prop("closed", "bool", "false"), stroke("#fffad961", "24.000000", cap="round", join="round")], c=0, r=3, fill="none")
    add("Line arrow ends", ".line", None, [prop("p1", "vec2", f(-100, 80)), prop("p2", "vec2", f(100, -80)),
                                           stroke("#ff52ccbc", "14.000000", cap="butt", start="arrow", end="arrow-filled")], c=1, r=3, fill="none")
    add("Triangle miter join", ".triangle", "#ffee4767", [stroke("#ffffffff", "12.000000", join="miter", cap="square")], c=2, r=3)
    layers.append(shape(7100, "Backing", 0, end, s=".rect", color="#fff0a040", tf=transform(location=f(540, 1450, 0)),
                        kids=[prop("size", "vec2", f(500, 120))]))
    for i, mode in enumerate(("multiply", "screen", "overlay", "diff", "lighten")):
        layers.append(shape(7101 + i, f"Blend {mode}", 0, end, s=".circle", color="#ff2d9cdb", blending=mode,
                            tf=transform(location=f(150 + 195 * i, 1450, 0)), kids=[prop("size", "vec2", f(90, 90))]))
    layers.append(shape(7201, "Mask base", 0, end, s=".circle", color="#ff52ccbc", tf=transform(location=f(300, 1750, 0)), kids=[prop("size", "vec2", f(100, 100))]))
    layers.append(shape(7202, "Masked", 0, end, s=".rect", color="#fffad961", clippingMask="true", tf=transform(location=f(350, 1750, 0)),
                        kids=[prop("size", "vec2", f(100, 40))]))
    layers.append(shape(7301, "Half opacity", 0, end, s=".star", color="#ffee4767", tf=transform(location=f(700, 1750, 0), opacity="0.500000")))
    layers.append(shape(7302, "Flipped", 0, end, s=".arrow", color="#ffffffff", tf=transform(location=f(900, 1750, 0), pivot=f(125, -125, 0), scale=f(-0.6, 0.6))))
    return scene("Styles", 1080, 1920, fps, end, "#ff1b1d27", layers)

def ebz():
    return "cubicBezier 0.42 0 0.58 1"

def media():
    fps, end = 30, fend(30, 149)
    files = {n: os.path.join(ROOT, MEDIA, n) for n in os.listdir(os.path.join(ROOT, MEDIA))} if os.path.isdir(os.path.join(ROOT, MEDIA)) else {}

    def size(name, fallback):
        return os.path.getsize(files[name]) if name in files else fallback

    def uri(name):
        return f"{MEDIA}/{name}"

    records = [
        N("media", {"uri": uri("test-image.jpg"), "filename": "test-image.jpg", "title": "test-image.jpg", "type": "image/jpeg",
                    "size": size("test-image.jpg", 198836), "orientation": 0, "infoUpdated": 1760000000000, "width": 1600, "height": 1200}),
        N("media", {"uri": uri("test-alpha.png"), "filename": "test-alpha.png", "title": "test-alpha.png", "type": "image/png",
                    "size": size("test-alpha.png", 8089), "orientation": 0, "infoUpdated": 1760000000000, "width": 800, "height": 800}),
        N("media", {"uri": uri("test-video.mp4"), "filename": "test-video.mp4", "title": "test-video.mp4", "type": "video/mp4",
                    "size": size("test-video.mp4", 119175), "orientation": 0, "duration": 5000000, "fps": "0.30",
                    "infoUpdated": 1760000000000, "width": 1280, "height": 720}),
        N("media", {"uri": uri("test-audio.m4a"), "filename": "test-audio.m4a", "title": "test-audio.m4a", "type": "audio/mp4",
                    "duration": 10000000, "size": size("test-audio.m4a", 160821), "infoUpdated": 1760000000000}),
    ]
    layers = [
        shape(8001, "Picture fill", 0, end, s=".rect", color=None, fill="media", fillImage=uri("test-image.jpg"),
              tf=transform(location=f(540, 400, 0)), kids=[prop("size", "vec2", f(540, 405))]),
        shape(8002, "Picture fit", 0, end, s=".rect", color=None, fill="media", fillImage=uri("test-image.jpg"), mediaFillMode="fit",
              tf=transform(location=f(300, 1000, 0)), kids=[prop("size", "vec2", f(240, 160))]),
        shape(8003, "Picture with alpha", 0, end, s=".rect", color=None, fill="media", fillImage=uri("test-alpha.png"), mediaFillMode="stretch",
              tf=transform(location=f(800, 1000, 0), rotation="-15.000000", opacity="0.800000"), kids=[prop("size", "vec2", f(200, 200)), border("outside", "#ffffffff", "6.000000", 1)]),
        shape(8004, "Video", 0, end, s=".rect", color=None, fill="media", fillVideo=uri("test-video.mp4"), inTime=0, outTime=5000, speed="1.000000",
              tf=transform(location=f(540, 1500, 0)), kids=[prop("size", "vec2", f(540, 303.5)), val("gain", "1.000000")]),
        layer("audio", 8005, "Sound", 0, end, [val("gain", "0.800000")], src=uri("test-audio.m4a"), outTime=5000, mediaFillMode="fill"),
        text(8006, "Picture text", 0, end, "MEDIA", "googlefonts?name=Roboto&weight=900", 60, "center", 1000, transform(location=f(540, 1800, 0)),
             color=None, fill="media", fillImage=uri("test-image.jpg")),
    ]
    return scene("Media", 1080, 1920, fps, end, "#ff14161f", records + layers)

if __name__ == "__main__":
    for name, build in (("shapes.xml", shapes), ("text.xml", texts), ("audit.xml", audit), ("easing.xml", easing), ("styles.xml", styles),
                        ("media.xml", media)):
        save(name, build())
