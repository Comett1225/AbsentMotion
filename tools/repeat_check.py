#!/usr/bin/env python3
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

import engine_check as engine

RED, BLACK, GREEN, WHITE = engine.RED, engine.BLACK, engine.GREEN, (255, 255, 255)


def fx(name, **params):
    out = f'<effect id="absent.{name}">'
    for k, v in params.items():
        if isinstance(v, tuple):
            out += f'<property name="{k}" type="vec2" value="{v[0]},{v[1]}"/>'
        elif isinstance(v, str):
            out += f'<property name="{k}" type="color" value="{v}"/>'
        else:
            out += f'<property name="{k}" type="float" value="{v}"/>'
    return out + '</effect>'


def dot(extra, cx=100, cy=100, hw=8, argb=0xffff0000, opacity=1.0, id=1):
    return engine.rect(id, cx, cy, hw, hw, argb, opacity, extra=extra)


def ring(radius=60):
    return ('<shape id="2" label="ring" startTime="0" endTime="1000" fillType="none" s=".circle">' + engine.transform('100,100,0')
            + f'<property name="size" type="vec2" value="{radius},{radius}"/><path-stroke><color value="#ff00ff00"/><size value="2"/></path-stroke></shape>')


def keyed(extra):
    return ('<shape id="1" label="k" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location><kf t="0" v="50,100,0"/>'
            '<kf t="1" v="150,100,0"/></location></transform><fillColor value="#ffff0000"/><property name="size" type="vec2" value="6,6"/>'
            + extra + '</shape>')


def cases():
    yield 'repeat copies by the offset', engine.scene(dot(fx('repeat', count=3, offset=(40, 0)), cx=60)), [((60, 100), RED), ((100, 100), RED), ((140, 100), RED), ((80, 100), BLACK), ((180, 100), BLACK)], 0
    yield 'repeat alpha falls to the last copy', engine.scene(dot(fx('repeat', count=2, offset=(40, 0), alpha=0.5), cx=80, argb=0xffffffff)), [((80, 100), WHITE), ((120, 100), engine.HALF)], 0
    yield 'repeat count 0 draws nothing', engine.scene(dot(fx('repeat', count=0))), [((100, 100), BLACK)], 0
    yield 'repeat composites before the layer opacity', engine.scene(dot(fx('repeat', count=2, offset=(10, 0)), hw=20, argb=0xffffffff, opacity=0.5)), [((100, 100), engine.HALF), ((85, 100), engine.HALF), ((115, 100), engine.HALF)], 0
    yield 'repeat time trails the animation', engine.scene(keyed(fx('repeat', count=2, time=0.5))), [((130, 100), RED), ((80, 100), RED), ((105, 100), BLACK)], 24
    yield 'linear repeat centres the line', engine.scene(dot(fx('linearrepeat', count=3, position=(100, 0)))), [((50, 100), RED), ((100, 100), RED), ((150, 100), RED), ((75, 100), BLACK)], 0
    yield 'radial repeat starts at the top', engine.scene(dot(fx('radialrepeat', count=4, radius=50))), [((100, 50), RED), ((150, 100), RED), ((100, 150), RED), ((50, 100), RED), ((100, 100), BLACK)], 0
    yield 'radial repeat sweep', engine.scene(dot(fx('radialrepeat', count=3, radius=50, sweep=180))), [((100, 50), RED), ((150, 100), RED), ((100, 150), RED), ((50, 100), BLACK)], 0
    yield 'grid repeat', engine.scene(dot(fx('gridrepeat', count=2, position=(100, 100)))), [((50, 50), RED), ((150, 50), RED), ((50, 150), RED), ((150, 150), RED), ((100, 100), BLACK)], 0
    yield 'grid repeat stagger', engine.scene(dot(fx('gridrepeat', count=3, position=(100, 100), stagger=20), hw=6)), [((100, 50), RED), ((120, 100), RED), ((100, 100), BLACK)], 0
    yield 'repeat along the path beneath', engine.scene(ring() + dot(fx('repeatalongpath', count=4), cx=20, cy=20, hw=6)), [((160, 100), RED), ((100, 160), RED), ((40, 100), RED), ((100, 40), RED), ((20, 20), BLACK)], 0
    yield 'repeat along path end', engine.scene(ring() + dot(fx('repeatalongpath', count=2, end=0.5), cx=20, cy=20, hw=6)), [((155, 100), RED), ((45, 100), RED), ((100, 155), BLACK)], 0
    yield 'range opacity fades the last copy', engine.scene(dot(fx('linearrepeat', count=2, position=(100, 0), rangeOpacity=0))), [((50, 100), RED), ((150, 100), BLACK)], 0
    yield 'fill colour over the whole range', engine.scene(dot(fx('linearrepeat', count=2, position=(100, 0), fillColor='#ff00ff00', fillAmount=1, rangeEnd=0))), [((50, 100), GREEN), ((150, 100), GREEN)], 0
    yield 'range scale shrinks the last copy', engine.scene(dot(fx('linearrepeat', count=2, position=(100, 0), rangeScale=0.25), hw=12)), [((50, 100), RED), ((150, 100), RED), ((160, 100), BLACK), ((60, 100), RED)], 0
    yield 'two repeats multiply', engine.scene(dot(fx('linearrepeat', count=2, position=(100, 0)) + fx('linearrepeat', count=2, position=(0, 100)))), [((50, 50), RED), ((150, 50), RED), ((50, 150), RED), ((150, 150), RED), ((100, 100), BLACK)], 0


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else engine.ROOT / 'build/repeat-check'
    work.mkdir(parents=True, exist_ok=True)
    failed = 0

    def still(name, xml, frame=0):
        for _ in range(4):
            if f'still {name} ok=true' in engine.run(name, xml, work, frame=frame):
                engine.adb('pull', f'{engine.DEVICE_DIR}/{name}.png', work / f'{name}.png')
                engine.adb('shell', 'rm', f'{engine.DEVICE_DIR}/{name}.png')
                return np.asarray(Image.open(work / f'{name}.png').convert('RGB')).astype(int)
            time.sleep(10)
        sys.exit(f'the app could not render {name}')

    def report(ok, title):
        nonlocal failed
        print(('ok   ' if ok else 'FAIL ') + title, flush=True)
        failed += not ok

    for k, (title, xml, probes, frame) in enumerate(cases()):
        image = still(f'repeat{k}', xml, frame)
        bad = [(xy, tuple(int(v) for v in image[xy[1], xy[0]]), want) for xy, want in probes if np.abs(image[xy[1], xy[0]] - want).max() > engine.TOLERANCE]
        report(not bad, title + (': ' + str(bad) if bad else ''))
    hits = engine.run('repeathit', engine.scene(dot(fx('radialrepeat', count=4, radius=50), id=7)), work, [(150, 100), (100, 100), (100, 150)])
    engine.adb('shell', 'rm', f'{engine.DEVICE_DIR}/repeathit.png')
    got = [line.split()[-1] for line in hits.splitlines() if ' hit ' in line]
    report(got == ['7', '0', '7'], 'tapping a copy selects the layer: ' + str(got))
    field = lambda seed: engine.scene(dot(fx('scatterfield', seed=seed), hw=20))
    shots = [still(f'scatter{seed}{again}', field(seed)) for seed, again in [(1, 'a'), (1, 'b'), (2, 'a')]]
    report(np.array_equal(shots[0], shots[1]) and not np.array_equal(shots[0], shots[2]) and shots[0].max() > 200,
           'scatter field is steady for a seed and changes with it')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
