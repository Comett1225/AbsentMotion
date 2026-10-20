#!/usr/bin/env python3
import subprocess
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
    eye = '<camera id="9" label="e" startTime="0" endTime="1000"><transform><location value="100,100,-173.2051"/></transform></camera>'
    yield 'repeat time trails the animation under a camera', engine.scene(keyed(fx('repeat', count=2, time=0.5)) + eye), [((130, 100), RED), ((80, 100), RED), ((105, 100), BLACK)], 24
    yield 'an adjustment layer repeated by a grid adjusts only inside its copies', engine.scene(engine.rect(2, 100, 100, 100, 100, 0xffff0000) + adjustment(2, 100)), [((50, 50), CYAN), ((150, 150), CYAN), ((100, 100), RED), ((30, 30), RED)], 0


CYAN = (0, 255, 255)


def adjustment(count, span, hw=10):
    return dot(fx('backdrop', mix=0) + '<effect id="absent.invert"/>' + fx('gridrepeat', count=count, position=(span, span)), hw=hw, id=3)


def moving_video(path, frames=60, width=256, height=256):
    folder = path.parent / 'mosh-frames'
    folder.mkdir(exist_ok=True)
    k = width / 256
    y, x = np.mgrid[0:height, 0:width]
    base = np.stack([x * 255 // width, y * 255 // height, 255 - (x * 255 // width + y * 255 // height) // 2], axis=-1).astype(np.uint8)
    base[((x // round(16 * k) + y // round(16 * k)) % 2 == 0)] //= 2
    for n in range(frames):
        image = base.copy()
        cx, cy = round((30 + n * 3) * k), round(height / 2 + 40 * k * np.sin(n / 9))
        a, b = round(25 * k), round(10 * k)
        image[cy - a:cy + a, cx - a:cx + a] = (255, 255, 255)
        image[cy - b:cy + b, cx - b:cx + b] = (255, 0, 0)
        Image.fromarray(image).save(folder / f'{n:03d}.png')
    Image.open(folder / '000.png').save(path.with_suffix('.png'))
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', '30', '-i', str(folder / '%03d.png'), '-pix_fmt', 'yuv420p',
                    '-c:v', 'libx264', '-g', '30', str(path)], check=True)
    for f in folder.iterdir():
        f.unlink()


def mosh_scene(effect, video=True):
    media = f'<media uri="file://{engine.DEVICE_DIR}/mosh.mp4" type="video/mp4" duration="2000000"/>' if video else ''
    fill = (f'fillVideo="file://{engine.DEVICE_DIR}/mosh.mp4" outTime="2000"' if video else f'fillImage="file://{engine.DEVICE_DIR}/mosh.png"')
    layer = (f'<shape id="3" label="m" startTime="0" endTime="1999" fillType="media" {fill} mediaFillMode="stretch" s=".rect">'
             + engine.transform('100,100,0') + '<property name="size" type="vec2" value="100,100"/>' + effect + '</shape>')
    return engine.scene(media + layer).replace('totalTime="1000"', 'totalTime="2000"', 1)


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

    part = sys.argv[2] if len(sys.argv) > 2 else 'all'
    if part in ('all', 'repeat'):
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
        started = time.time()
        crowd = still('adjustgrid', engine.scene(engine.rect(2, 100, 100, 100, 100, 0xffff0000) + adjustment(30, 190, 2)))
        seconds = time.time() - started
        near = lambda x, y, want: np.abs(crowd[y, x] - want).max() <= engine.TOLERANCE
        report(seconds < 6 and near(5, 5, CYAN) and near(195, 195, CYAN) and near(8, 8, RED),
               f'an adjustment layer in a 30 x 30 grid renders in {seconds:.1f} s')
    if part in ('all', 'mosh'):
        video = work / 'mosh.mp4'
        moving_video(video)
        engine.adb('push', video, f'{engine.DEVICE_DIR}/mosh.mp4')
        engine.adb('push', video.with_suffix('.png'), f'{engine.DEVICE_DIR}/mosh.png')
        effect = fx('datamosh', start=0.2, duration=1.5)

        def timed(name, xml, frame):
            image = still(name, xml, frame)
            lines = [line for line in engine.adb('logcat', '-d', '-s', 'AMMosh').splitlines() if ' step ' in line]
            totals = [float(line.split('total=')[1].split()[0]) for line in lines]
            flows = [float(line.split('flow=')[1].split()[0]) for line in lines]
            size = lines[-1].split('size=')[1].split()[0] if lines else '-'
            print(f'     {name}: {len(lines)} steps at {size}, flow {np.mean(flows) if flows else 0:.1f} ms, step {np.mean(totals) if totals else 0:.1f} ms', flush=True)
            return image

        before = [still('moshplain3', mosh_scene(''), 3), still('mosh3', mosh_scene(effect), 3)]
        report(np.array_equal(*before), 'datamosh leaves the video alone before its start')
        first = timed('mosh20a', mosh_scene(effect), 20)
        timed('mosh35', mosh_scene(effect), 35)
        again = timed('mosh20b', mosh_scene(effect), 20)
        engine.adb('shell', 'am', 'force-stop', engine.PKG)
        fresh = timed('mosh20c', mosh_scene(effect), 20)
        plain = still('moshplain20', mosh_scene(''), 20)
        smear = float(np.abs(first - plain).mean())
        report(np.array_equal(first, again) and np.array_equal(first, fresh) and smear > 3,
               f'datamosh video stills repeat exactly and differ from the plain frame by {smear:.1f}')
        after = [still('moshplain55', mosh_scene(''), 55), still('mosh55', mosh_scene(effect), 55)]
        report(np.array_equal(*after), 'datamosh ends after its length')
        blocks = [still(f'moshpicture{again}', mosh_scene(effect, video=False), 15) for again in 'ab']
        flat = still('moshpictureplain', mosh_scene('', video=False), 15)
        early = [still('moshpicture3', mosh_scene(effect, video=False), 3), still('moshpictureplain3', mosh_scene('', video=False), 3)]
        moved = float(np.abs(blocks[0] - flat).mean())
        report(np.array_equal(*blocks) and np.array_equal(*early) and moved > 1,
               f'block datamosh on a picture is steady, starts on time and moves blocks by {moved:.1f}')
        ended = [still('moshpictureend', mosh_scene(fx('datamosh', start=0.2, duration=0.3), video=False), 30), still('moshpictureplain30', mosh_scene('', video=False), 30)]
        report(np.array_equal(*ended), 'block datamosh on a picture ends after its length')
        still('moshclose', mosh_scene(effect), 20)
        pid = engine.adb('shell', 'pidof', engine.PKG).strip()
        engine.adb('logcat', '-b', 'all', '-c')
        for _ in range(4):
            engine.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            time.sleep(1.5)
        time.sleep(3)
        alive = engine.adb('shell', 'pidof', engine.PKG).strip()
        crashes = engine.adb('logcat', '-d', '-b', 'crash').strip()
        again = still('moshreopen', mosh_scene(effect), 20)
        report(bool(pid) and alive == pid and not crashes and np.array_equal(again, first),
               f'closing the app after a video datamosh frees it without a crash (pid {pid} -> {alive or "gone"}, crash log {crashes[-300:]!r})')
        for name in ('mosh.mp4', 'mosh.png'):
            engine.adb('shell', 'rm', f'{engine.DEVICE_DIR}/{name}')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
