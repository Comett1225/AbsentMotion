#!/usr/bin/env python3
import math, os, sys

BG = "#FF5A36"
FG = "#FFFFFF"
C = 54.0
R = 24.0
SW = 7.0
HEAD = -30.0
DASHES = [130.0, 60.0, 24.0, 0.0]
GAP = 26.0
CORE = 7.0

def polar(a, r=R):
    return C + r * math.cos(math.radians(a)), C + r * math.sin(math.radians(a))

def dashes():
    out, end = [], HEAD
    for length in DASHES:
        out.append((end - length, end))
        end = end - length - GAP
    return out

def f(v):
    return ("%.3f" % v).rstrip("0").rstrip(".")

def arc_path(a0, a1):
    (x0, y0), (x1, y1) = polar(a0), polar(a1)
    large = 1 if (a1 - a0) > 180 else 0
    return "M%s,%s A%s,%s 0 %d 1 %s,%s" % (f(x0), f(y0), f(R), f(R), large, f(x1), f(y1))

def shapes():
    items = []
    for a0, a1 in dashes():
        if a1 - a0 < 0.01:
            x, y = polar(a0)
            items.append(("dot", x, y, SW / 2))
        else:
            items.append(("arc", arc_path(a0, a1)))
    items.append(("dot", C, C, CORE))
    return items

def circle_path(cx, cy, r):
    return "M%s,%s a%s,%s 0 1 0 %s,0 a%s,%s 0 1 0 %s,0 z" % (f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))

def svg_marks():
    out = []
    for it in shapes():
        if it[0] == "arc":
            out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%s" stroke-linecap="round"/>' % (it[1], FG, f(SW)))
        else:
            out.append('<circle cx="%s" cy="%s" r="%s" fill="%s"/>' % (f(it[1]), f(it[2]), f(it[3]), FG))
    return "\n  ".join(out)

def write_svg(path):
    with open(path, "w") as fh:
        fh.write('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'
                 '  <rect width="108" height="108" fill="%s"/>\n  %s\n</svg>\n' % (BG, svg_marks()))

def write_android(root):
    d = os.path.join(root, "app/src/main/res/drawable")
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="108dp" android:height="108dp"',
             '    android:viewportWidth="108" android:viewportHeight="108">']
    for it in shapes():
        if it[0] == "arc":
            lines.append('    <path android:pathData="%s" android:strokeColor="%s" android:strokeWidth="%s" android:strokeLineCap="round" />'
                         % (it[1], "#FF" + FG[1:], f(SW)))
        else:
            lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % ("#FF" + FG[1:], circle_path(it[1], it[2], it[3])))
    lines.append('</vector>')
    with open(os.path.join(d, "ic_launcher_foreground.xml"), "w") as fh:
        fh.write("\n".join(lines) + "\n")
    with open(os.path.join(d, "ic_launcher_background.xml"), "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                 '    android:width="108dp" android:height="108dp"\n'
                 '    android:viewportWidth="108" android:viewportHeight="108">\n'
                 '    <path android:fillColor="%s" android:pathData="M0,0h108v108h-108z" />\n'
                 '</vector>\n' % ("#FF" + BG[1:]))

if __name__ == "__main__":
    root = os.getcwd()
    here = os.path.join(root, "tools/icon")
    write_svg(os.path.join(here, "mark.svg"))
    write_android(root)
    print("ok")
