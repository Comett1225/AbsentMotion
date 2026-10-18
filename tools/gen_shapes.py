#!/usr/bin/env python3
"""Writes app/src/main/assets/shapes.xml: the 19 parametric built-in shapes (kept exactly as the file has them, ids and
parameter names are the project file format) followed by 35 drawn outlines that are built here from geometry:
formulas, circle arcs and short point tables. Nothing is traced or copied from another app.

  tools/gen_shapes.py                 rewrite the file
  tools/gen_shapes.py --check         exit 1 when the file differs from what the script writes
  tools/gen_shapes.py --sheet OUT.png also draw a contact sheet of the 35 outlines, read back from the file (needs PIL)

Every outline is closed, uses +y down, is centred on (0, 0) and fits a SIZE x SIZE box (the circle's default is 200
across). A shape with holes or separate parts has one contour per part; "exclude" contours flip what they cover, as the
engine does (a hole when inside, an added part when outside).
"""
import math
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XML = os.path.join(ROOT, "app/src/main/assets/shapes.xml")
PARAMETRIC = 19
SIZE = 200.0

class P:
    def __init__(self):
        self.cmds = []
        self.cur = None

    def m(self, x, y):
        self.cmds.append(("M", x, y)); self.cur = (x, y); return self

    def l(self, x, y):
        self.cmds.append(("L", x, y)); self.cur = (x, y); return self

    def c(self, x1, y1, x2, y2, x, y):
        self.cmds.append(("C", x1, y1, x2, y2, x, y)); self.cur = (x, y); return self

    def q(self, qx, qy, x, y, split=False):
        px, py = self.cur
        return (self.c2 if split else self.c)(px + (qx - px) * 2 / 3, py + (qy - py) * 2 / 3, x + (qx - x) * 2 / 3, y + (qy - y) * 2 / 3, x, y)

    def c2(self, x1, y1, x2, y2, x, y):
        (x0, y0) = self.cur
        a, b, c = ((x0 + x1) / 2, (y0 + y1) / 2), ((x1 + x2) / 2, (y1 + y2) / 2), ((x2 + x) / 2, (y2 + y) / 2)
        d, e = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2), ((b[0] + c[0]) / 2, (b[1] + c[1]) / 2)
        m = ((d[0] + e[0]) / 2, (d[1] + e[1]) / 2)
        return self.c(a[0], a[1], d[0], d[1], m[0], m[1]).c(e[0], e[1], c[0], c[1], x, y)

    def z(self):
        self.cmds.append(("Z",)); return self

    def arc(self, cx, cy, r, a0, a1, ry=None):
        """Circle (or axis-aligned ellipse) arc from angle a0 to a1 in degrees; angles grow clockwise on screen (+y down).
        Starts with a line to the arc's first point (a move on an empty path)."""
        ry = r if ry is None else ry
        sx, sy = cx + r * math.cos(math.radians(a0)), cy + ry * math.sin(math.radians(a0))
        if self.cur is None:
            self.m(sx, sy)
        elif math.hypot(self.cur[0] - sx, self.cur[1] - sy) > 1e-6:
            self.l(sx, sy)
        n = max(1, int(math.ceil(abs(a1 - a0) / 90 - 1e-9)))
        d = math.radians(a1 - a0) / n
        k = 4 / 3 * math.tan(d / 4)
        for i in range(n):
            t0, t1 = math.radians(a0) + i * d, math.radians(a0) + (i + 1) * d
            self.c(cx + r * (math.cos(t0) - k * math.sin(t0)), cy + ry * (math.sin(t0) + k * math.cos(t0)),
                   cx + r * (math.cos(t1) + k * math.sin(t1)), cy + ry * (math.sin(t1) - k * math.cos(t1)),
                   cx + r * math.cos(t1), cy + ry * math.sin(t1))
        return self

    def tf(self, a, b, c, d, e, f):
        """A copy mapped by x' = a x + c y + e, y' = b x + d y + f."""
        out = P()
        for cmd in self.cmds:
            v = cmd[1:]
            pts = [(a * v[i] + c * v[i + 1] + e, b * v[i] + d * v[i + 1] + f) for i in range(0, len(v), 2)]
            out.cmds.append((cmd[0],) + tuple(n for p in pts for n in p))
        return out

    def rot(self, deg, cx=0.0, cy=0.0):
        s, c = math.sin(math.radians(deg)), math.cos(math.radians(deg))
        return self.tf(c, s, -s, c, cx - c * cx + s * cy, cy - s * cx - c * cy)

    def mv(self, dx, dy):
        return self.tf(1, 0, 0, 1, dx, dy)

    def points(self, steps=24):
        """The outline as a list of points (curves sampled), for bounds and drawing."""
        pts, cur = [], None
        for cmd in self.cmds:
            if cmd[0] in "ML":
                cur = (cmd[1], cmd[2]); pts.append(cur)
            elif cmd[0] == "C":
                (x0, y0), (x1, y1, x2, y2, x3, y3) = cur, cmd[1:]
                for i in range(1, steps + 1):
                    t = i / steps; u = 1 - t
                    pts.append((u**3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t**3 * x3,
                                u**3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t**3 * y3))
                cur = (x3, y3)
        return pts

def fmt(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

def to_d(path):
    return " ".join(cmd[0] if len(cmd) == 1 else cmd[0] + " " + " ".join(fmt(v) for v in cmd[1:]) for cmd in path.cmds)

def poly(pts):
    p = P().m(*pts[0])
    for pt in pts[1:]:
        p.l(*pt)
    return p.z()

def rpoly(pts, r):
    """A polygon with every corner rounded by a quadratic curve that starts r away from the corner (r may be a list)."""
    n = len(pts)
    radii = r if isinstance(r, (list, tuple)) else [r] * n
    p = P()
    for i, v in enumerate(pts):
        a, b = pts[i - 1], pts[(i + 1) % n]
        da, db = math.dist(v, a), math.dist(v, b)
        t = min(radii[i], da / 2, db / 2)
        if t <= 0:
            p.l(*v) if p.cur else p.m(*v)
            continue
        s = (v[0] + (a[0] - v[0]) / da * t, v[1] + (a[1] - v[1]) / da * t)
        e = (v[0] + (b[0] - v[0]) / db * t, v[1] + (b[1] - v[1]) / db * t)
        p.l(*s) if p.cur else p.m(*s)
        p.q(v[0], v[1], *e)
    return p.z()

def circle(cx, cy, r, ry=None):
    return P().arc(cx, cy, r, 0, 360, ry).z()

def rect(x0, y0, x1, y1, r=0):
    pts = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
    return rpoly(pts, r) if r else poly(pts)

def star(n, outer, inner, cx=0.0, cy=0.0, start=-90.0):
    pts = []
    for i in range(2 * n):
        a = math.radians(start + i * 180.0 / n)
        r = outer if i % 2 == 0 else inner
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts)

def ngon(n, radius, start=0.0):
    return poly([(radius * math.cos(math.radians(start + i * 360.0 / n)), radius * math.sin(math.radians(start + i * 360.0 / n))) for i in range(n)])

def circle_hits(c0, r0, c1, r1):
    """The two points where circle (c0, r0) meets circle (c1, r1)."""
    dx, dy = c1[0] - c0[0], c1[1] - c0[1]
    d = math.hypot(dx, dy)
    a = (r0 * r0 - r1 * r1 + d * d) / (2 * d)
    h = math.sqrt(r0 * r0 - a * a)
    mx, my = c0[0] + a * dx / d, c0[1] + a * dy / d
    return (mx + h * dy / d, my - h * dx / d), (mx - h * dy / d, my + h * dx / d)

def angle(c, p):
    return math.degrees(math.atan2(p[1] - c[1], p[0] - c[0]))

def thick_line(pts, w, round_caps=True):
    """The outline of an open polyline drawn w wide, with mitred corners and round or flat ends."""
    h = w / 2
    n = len(pts)
    dirs = []
    for i in range(n - 1):
        dx, dy = pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]
        L = math.hypot(dx, dy)
        dirs.append((dx / L, dy / L))
    norms = [(-d[1], d[0]) for d in dirs]
    offs = []
    for i in range(n):
        if i == 0 or i == n - 1:
            nx, ny = norms[0] if i == 0 else norms[-1]
            offs.append((nx * h, ny * h))
        else:
            mx, my = norms[i - 1][0] + norms[i][0], norms[i - 1][1] + norms[i][1]
            ml = math.hypot(mx, my)
            mx, my = mx / ml, my / ml
            k = h / (mx * norms[i][0] + my * norms[i][1])
            offs.append((mx * k, my * k))

    def cap(c, o, d, p):
        a0 = angle((0, 0), o)
        cross = o[0] * d[1] - o[1] * d[0]
        if round_caps:
            p.arc(c[0], c[1], h, a0, a0 + (180 if cross > 0 else -180))
        else:
            p.l(c[0] - o[0], c[1] - o[1])

    p = P().m(pts[0][0] + offs[0][0], pts[0][1] + offs[0][1])
    for i in range(1, n):
        p.l(pts[i][0] + offs[i][0], pts[i][1] + offs[i][1])
    cap(pts[-1], offs[-1], dirs[-1], p)
    for i in range(n - 1, -1, -1):
        p.l(pts[i][0] - offs[i][0], pts[i][1] - offs[i][1])
    cap(pts[0], (-offs[0][0], -offs[0][1]), (-dirs[0][0], -dirs[0][1]), p)
    return p.z()

def droplet(cx, cy, r, h, up=True, bulge=0.05):
    """A drop: a circle of radius r with a pointed tip h from its centre (up, or down for a map pin)."""
    tip = (cx, cy - h)
    beta = math.acos(r / h)
    pr = (cx + r * math.sin(beta), cy - r * math.cos(beta))
    ux, uy = pr[0] - tip[0], pr[1] - tip[1]
    L = math.hypot(ux, uy)
    ux, uy = ux / L, uy / L
    b = bulge * h
    p = P().m(*tip)
    p.c(tip[0] + ux * L * 0.35 + b, tip[1] + uy * L * 0.35, pr[0] - ux * L * 0.35, pr[1] - uy * L * 0.35, *pr)
    p.arc(cx, cy, r, -90 + math.degrees(beta), 270 - math.degrees(beta))
    pl = (cx - r * math.sin(beta), cy - r * math.cos(beta))
    p.c(pl[0] + ux * L * 0.35, pl[1] - uy * L * 0.35, tip[0] - ux * L * 0.35 - b, tip[1] + uy * L * 0.35, *tip)
    p.z()
    return p if up else p.tf(1, 0, 0, -1, 0, 2 * cy)

SHAPES = []

def shape(name):
    def deco(fn):
        SHAPES.append((name, fn))
        return fn
    return deco

@shape("Heart")
def _():
    return [P().m(0, 85).c(-25, 65, -100, 15, -100, -35).c(-100, -75, -70, -95, -45, -95).c(-22, -95, -6, -80, 0, -62)
            .c(6, -80, 22, -95, 45, -95).c(70, -95, 100, -75, 100, -35).c(100, 15, 25, 65, 0, 85).z()]

@shape("Cloud")
def _():
    base = 60
    circles = [(cx, base - r, r) for cx, r in [(-66, 30), (-24, 46), (34, 38), (70, 26)]]
    p, cur = P(), 90.0
    for i, (cx, cy, r) in enumerate(circles):
        if i < len(circles) - 1:
            nx, ny, nr = circles[i + 1]
            hit = min(circle_hits((cx, cy), r, (nx, ny), nr), key=lambda q: q[1])
            end = angle((cx, cy), hit)
        else:
            end = 90.0
        p.arc(cx, cy, r, cur, cur + (end - cur) % 360)
        if i < len(circles) - 1:
            cur = angle((nx, ny), hit)
    return [p.z()]

@shape("Lightning Bolt")
def _():
    return [rpoly([(34, -100), (-52, 8), (-6, 8), (-34, 100), (56, -26), (10, -26)], 3)]

@shape("Check Mark")
def _():
    return [thick_line([(-72, 4), (-28, 48), (78, -58)], 36)]

@shape("X Mark")
def _():
    t, length, s = 17.0, 100.0, math.sqrt(0.5)
    u, v = (s, -s), (s, s)
    quad = [(0, -t / s)]
    quad += [(length * u[0] + sg * t * v[0], length * u[1] + sg * t * v[1]) for sg in (-1, 1)]
    quad += [(t / s, 0)]
    pts = []
    for k in range(4):
        for (x, y) in quad:
            for _ in range(k):
                x, y = -y, x
            pts.append((x, y))
    return [rpoly(pts, [2, 5, 5, 2] * 4)]

@shape("Ring")
def _():
    return [circle(0, 0, 100), (circle(0, 0, 62), True)]

@shape("Speech Bubble")
def _():
    cx, cy, r = 0, -12, 90
    a0, a1 = 132, 98
    start = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
    p = P().m(*start).arc(cx, cy, r, a0, 360 + a1)
    return [p.l(-70, 100).z()]

@shape("Shield")
def _():
    return [P().m(-85, -68).c(-50, -68, -25, -84, 0, -100).c(25, -84, 50, -68, 85, -68).c(88, -20, 82, 20, 66, 52)
            .c(50, 80, 25, 92, 0, 100).c(-25, 92, -50, 80, -66, 52).c(-82, 20, -88, -20, -85, -68).z()]

@shape("House")
def _():
    return [rpoly([(0, -100), (100, -8), (80, -8), (80, 90), (20, 90), (20, 24), (-20, 24), (-20, 90), (-80, 90), (-80, -8), (-100, -8)],
                  [4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 4])]

@shape("Bookmark")
def _():
    return [rpoly([(-60, -100), (60, -100), (60, 100), (0, 52), (-60, 100)], [12, 12, 0, 0, 0])]

@shape("Flag")
def _():
    return [P().m(-80, -100).l(-62, -100).c(-20, -125, 25, -75, 90, -100).l(90, 10).c(25, 35, -20, -15, -62, 10).l(-62, 100).l(-80, 100).z()]

@shape("Crescent and Star")
def _():
    c1, r1, c2, r2 = (-14, 0), 90, (24, -8), 72
    a, b = sorted(circle_hits(c1, r1, c2, r2), key=lambda q: q[1])

    def sweep(p, c, r, q, toward):
        a0, a1 = angle(c, p), angle(c, q)
        cw, ccw = (a1 - a0) % 360, -((a0 - a1) % 360)
        def mid(d):
            m = math.radians(a0 + d / 2)
            return math.hypot(c[0] + r * math.cos(m) - toward[0], c[1] + r * math.sin(m) - toward[1])
        d = cw if mid(cw) < mid(ccw) else ccw
        return a0, a0 + d

    o0, o1 = sweep(a, c1, r1, b, (-1000, 0))
    i0, i1 = sweep(b, c2, r2, a, c1)
    moon = P().arc(c1[0], c1[1], r1, o0, o1).arc(c2[0], c2[1], r2, i0, i1).z()
    return [moon, star(5, 34, 14, 50, -8)]

@shape("Sun")
def _():
    out = [circle(0, 0, 44)]
    for i in range(8):
        ray = rpoly([(64, -11), (98, -7), (98, 7), (64, 11)], 3)
        out.append(ray.rot(i * 45))
    return out

@shape("Leaf")
def _():
    body = P().m(-66, 66).c2(-100, -20, -36, -92, 82, -92).c2(92, 20, 24, 100, -66, 66).z()
    stem = rpoly([(-62, 58), (-72, 70), (-98, 98), (-90, 106), (-62, 82)], 3)
    a, b = (-52, 52), (56, -56)
    vein = P().m(*a).q(12, 8, *b, split=True).q(-8, -12, *a, split=True).z()
    return [body, stem, (vein, True)]

@shape("Seal Burst")
def _():
    return [star(12, 100, 84, start=-90)]

@shape("Hexagon Outline")
def _():
    return [ngon(6, 100), (ngon(6, 68), True)]

@shape("Star Outline")
def _():
    return [star(5, 100, 40), (star(5, 56, 22), True)]

@shape("Chevron")
def _():
    return [rpoly([(-60, -100), (-20, -100), (60, 0), (-20, 100), (-60, 100), (20, 0)], 5)]

@shape("Double Arrow")
def _():
    return [rpoly([(-100, 0), (-42, -58), (-42, -22), (42, -22), (42, -58), (100, 0), (42, 58), (42, 22), (-42, 22), (-42, 58)],
                  [4, 3, 0, 0, 3, 4, 3, 0, 0, 3])]

@shape("Curved Arrow")
def _():
    cy = 14
    p = P().arc(0, cy, 79, 180, 360).l(102, cy).l(62, cy + 56).l(22, cy).l(45, cy).arc(0, cy, 45, 360, 180)
    return [p.z()]

@shape("Parallelogram")
def _():
    return [rpoly([(-50, -60), (100, -60), (50, 60), (-100, 60)], 9)]

@shape("Trapezoid")
def _():
    return [rpoly([(-55, -60), (55, -60), (100, 60), (-100, 60)], 9)]

@shape("Ticket")
def _():
    p = (P().m(-90, -60).l(90, -60).arc(90, -50, 10, 270, 360).l(100, -18).arc(100, 0, 18, 270, 90)
         .l(100, 50).arc(90, 50, 10, 0, 90).l(-90, 60).arc(-90, 50, 10, 90, 180).l(-100, 18).arc(-100, 0, 18, 90, -90)
         .l(-100, -50).arc(-90, -50, 10, 180, 270).z())
    holes = [(circle(44, y, 5.5), True) for y in (-36, -18, 0, 18, 36)]
    return [p] + holes

@shape("Tag")
def _():
    return [rpoly([(-100, 0), (-44, -62), (96, -62), (96, 62), (-44, 62)], [7, 9, 9, 9, 9]), (circle(-46, 0, 14), True)]

@shape("Crown")
def _():
    body = rpoly([(-90, -50), (-46, -4), (0, -76), (46, -4), (90, -50), (78, 82), (-78, 82)], [4, 3, 3, 3, 4, 7, 7])
    balls = [circle(-90, -58, 11), circle(0, -84, 11), circle(90, -58, 11)]
    jewels = [(circle(x, 50, 9), True) for x in (-44, 0, 44)]
    return [body] + balls + jewels

@shape("Eye")
def _():
    almond = P().m(-100, 0).c2(-55, -74, 55, -74, 100, 0).c2(55, 74, -55, 74, -100, 0).z()
    return [almond, (circle(0, 0, 42), True), (circle(0, 0, 19), True)]

@shape("Umbrella")
def _():
    dome = P().arc(0, 10, 95, 180, 360, ry=90)
    xs = [95, 95 - 190 / 3, -95 + 190 / 3, -95]
    for i in range(3):
        dome.q((xs[i] + xs[i + 1]) / 2, 10 - 28, xs[i + 1], 10)
    dome.z()
    nub = rect(-4, -102, 4, -70, 3)
    handle = P().m(5, -24).l(5, 68).arc(-17, 68, 22, 0, 180).l(-29, 68).arc(-17, 68, 12, 180, 0).l(-5, -24).z()
    return [dome, nub, handle]

@shape("Music Note")
def _():
    head = circle(0, 0, 40, 28).rot(-25).mv(-34, 70)
    stem = rect(-14, -100, 4, 66)
    flag = P().m(0, -96).c(10, -62, 72, -66, 66, -8).c(60, -50, 30, -44, 0, -36).z()
    return [head, stem, flag]

@shape("Droplet Pair")
def _():
    return [droplet(-36, 28, 50, 104), droplet(62, 62, 30, 62)]

@shape("Key")
def _():
    return [circle(-58, 0, 42), rect(-24, -10, 100, 10, 2), rect(60, 2, 76, 38, 2), rect(82, 2, 98, 28, 2), (circle(-58, 0, 18), True)]

@shape("Location Pin")
def _():
    return [droplet(0, -28, 62, 128, up=False, bulge=0.03), (circle(0, -28, 24), True)]

@shape("Rounded Play")
def _():
    return [rpoly([(-70, -92), (-70, 92), (96, 0)], 26)]

@shape("Pause")
def _():
    return [rect(-76, -90, -20, 90, 14), rect(20, -90, 76, 90, 14)]

@shape("Record")
def _():
    return [circle(0, 0, 100), (circle(0, 0, 84), True), circle(0, 0, 60)]

@shape("Gear")
def _():
    n, ro, rr, at, ar = 8, 100, 78, 9.0, 14.0
    p = P()
    for i in range(n):
        t = i * 360.0 / n - 90

        def at_(a, r):
            return (r * math.cos(math.radians(t + a)), r * math.sin(math.radians(t + a)))
        if i == 0:
            p.m(*at_(-ar, rr))
        p.l(*at_(-at, ro)).l(*at_(at, ro)).l(*at_(ar, rr))
        p.arc(0, 0, rr, t + ar, t + 360.0 / n - ar)
    return [p.z(), (circle(0, 0, 30), True)]

def fitted(contours):
    paths = [c[0] if isinstance(c, tuple) else c for c in contours]
    pts = [q for p in paths for q in p.points()]
    x0, x1 = min(q[0] for q in pts), max(q[0] for q in pts)
    y0, y1 = min(q[1] for q in pts), max(q[1] for q in pts)
    s = SIZE / max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    return [(p.tf(s, 0, 0, s, -cx * s, -cy * s), isinstance(c, tuple)) for p, c in zip(paths, contours)]

def entry(name, contours):
    out = ['  <shape name="%s" fillType="color">' % name]
    if len(contours) == 1:
        out.append('    <path d="%s" />' % to_d(contours[0][0]))
    else:
        out.append("    <path>")
        for p, exclude in contours:
            out.append('      <contour %sd="%s" closed="true" />' % ('exclude="true" ' if exclude else "", to_d(p)))
        out.append("    </path>")
    out.append("  </shape>")
    return "\n".join(out) + "\n"

def build():
    text = open(XML).read()
    starts = [m.start() for m in re.finditer(r"^  <shape\b", text, re.M)]
    assert len(starts) >= PARAMETRIC
    head = text[:starts[PARAMETRIC]] if len(starts) > PARAMETRIC else text[:text.rindex("</shapes>")]
    assert head.count(' s="') >= PARAMETRIC and 'name="Shape"' not in head, "the first %d entries must be the parametric ones" % PARAMETRIC
    names = [n for n, _ in SHAPES]
    assert len(names) == 35 and len(set(names)) == 35
    return head + "".join(entry(name, fitted(fn())) for name, fn in SHAPES) + "</shapes>"

def read_d(d):
    toks = re.findall(r"[MLCZ]|-?\d*\.?\d+", d)
    out, i, cur = [], 0, None
    while i < len(toks):
        t = toks[i]; i += 1
        if t == "M" or t == "L":
            cur = (float(toks[i]), float(toks[i + 1])); i += 2; out.append(cur)
        elif t == "C":
            v = [float(x) for x in toks[i:i + 6]]; i += 6
            for k in range(1, 25):
                s = k / 24; u = 1 - s
                out.append((u**3 * cur[0] + 3 * u * u * s * v[0] + 3 * u * s * s * v[2] + s**3 * v[4],
                            u**3 * cur[1] + 3 * u * u * s * v[1] + 3 * u * s * s * v[3] + s**3 * v[5]))
            cur = (v[4], v[5])
    return out

def sheet(png, cols=7, cell=170):
    import xml.etree.ElementTree as ET
    from PIL import Image, ImageChops, ImageDraw
    shapes = [s for s in ET.parse(XML).getroot() if not s.get("s")]
    rows = (len(shapes) + cols - 1) // cols
    ss = 3
    img = Image.new("RGB", (cols * cell, rows * cell), (30, 32, 40))
    draw = ImageDraw.Draw(img)
    for i, s in enumerate(shapes):
        ox, oy = (i % cols) * cell, (i // cols) * cell
        path = s.find("path")
        parts = [(path.get("d"), False)] if path.get("d") else [(c.get("d"), c.get("exclude") == "true") for c in path.findall("contour")]
        mask = Image.new("L", (cell * ss, cell * ss), 0)
        for d, exclude in parts:
            m = Image.new("L", mask.size, 0)
            ImageDraw.Draw(m).polygon([(cell * ss / 2 + x * ss * 0.75, cell * ss / 2 + y * ss * 0.75) for x, y in read_d(d)], fill=255)
            mask = ImageChops.difference(mask, m) if exclude else ImageChops.lighter(mask, m)
        mask = mask.resize((cell, cell), Image.LANCZOS)
        draw.rectangle([ox + 8, oy + 8, ox + cell - 9, oy + cell - 9], outline=(60, 64, 76))
        img.paste((235, 235, 240), (ox, oy), mask)
        draw.text((ox + 6, oy + cell - 14), "%d %s" % (i + 20, s.get("name")), fill=(150, 200, 255))
    img.save(png)

if __name__ == "__main__":
    new = build()
    if "--check" in sys.argv:
        sys.exit(0 if open(XML).read() == new else 1)
    open(XML, "w").write(new)
    if "--sheet" in sys.argv:
        sheet(sys.argv[sys.argv.index("--sheet") + 1])
