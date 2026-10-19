#!/usr/bin/env python3
import json, os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "app/build/easing_check")
DATA = os.path.join(ROOT, "testdata/behaviour/easing")
MAIN = r'''
#include <cstdio>
#include <cstdlib>
#include "easing.h"
int main(int argc, char** argv) {
    const am::Easing e = am::ParseEasing(argv[1]);
    for (int i = 2; i < argc; i++) printf("%.9g\n", am::Ease(e, strtof(argv[i], nullptr)));
}
'''

def build():
    os.makedirs(BUILD, exist_ok=True)
    main = os.path.join(BUILD, "main.cpp")
    open(main, "w").write(MAIN)
    src = os.path.join(ROOT, "app/src/main/cpp")
    subprocess.run(["clang++", "-std=c++20", "-O1", "-I", src, main, os.path.join(src, "easing.cpp"), "-o", os.path.join(BUILD, "easing")], check=True)

def ease(e, us):
    out = subprocess.run([os.path.join(BUILD, "easing"), e, *[repr(u) for u in us]], capture_output=True, text=True, check=True).stdout
    return [float(v) for v in out.split()]

def probes(names):
    folder = os.path.join(DATA, "probe")
    names = names or sorted(f[:-14] for f in os.listdir(folder) if f.endswith(".variants.json") and os.path.exists(os.path.join(folder, f[:-14] + ".json")))
    ok = True
    for name in names:
        for e, points in json.load(open(os.path.join(folder, name + ".json"))).items():
            if not points:
                print(f"SKIP  {e}: no samples")
                continue
            ps = [p for p, _ in points]
            near = [ease(e, [min(max(p + d, 0), 1) for p in ps]) if e else ps for d in (0, -1e-4, 1e-4)]
            errors = [max(min(n[i] for n in near) - v, v - max(n[i] for n in near), 0) for i, (_, v) in enumerate(points)]
            worst = max(range(len(points)), key=lambda i: errors[i])
            good = errors[worst] < 5e-4
            ok &= good
            print(f"{'PASS' if good else 'FAIL'}  {e or 'linear':40} {len(points):4} samples, largest error {errors[worst]:.2e} at p = {ps[worst]:.4f} (reference {points[worst][1]:.5f}, ours {near[0][worst]:.5f})")
    sys.exit(0 if ok else 1)

def main():
    build()
    if sys.argv[1:2] == ["--probe"]:
        probes(sys.argv[2:])
    samples = json.load(open(os.path.join(DATA, "samples.json")))
    strings = json.load(open(os.path.join(DATA, "strings.json")))
    ok = True
    for name in sys.argv[1:] or samples:
        if name not in strings:
            print(f"SKIP  {name}: no easing string recorded")
            continue
        points = sorted(set(map(tuple, samples[name])))
        got = ease(strings[name], [p for p, _ in points])
        worst = max(zip((abs(g - v) for g, (_, v) in zip(got, points)), points, got))
        good = worst[0] < 2e-5
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  {name:13} {len(points):3} samples, largest error {worst[0]:.2e} at {worst[1][0]:.6f} (reference {worst[1][1]:.6f}, ours {worst[2]:.6f})  [{strings[name]}]")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
