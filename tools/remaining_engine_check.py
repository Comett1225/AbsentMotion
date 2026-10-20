#!/usr/bin/env python3
from pathlib import Path
import sys
import numpy as np
import engine_check as engine


def cases():
    yield 'top-level effect control', engine.scene(engine.rect(1, 100, 100, 100, 100, 0xffff0000, extra='<effect id="absent.invert"/>')), [((100, 100), (0, 255, 255))]
    for name, effect in [('wave edges', '<effect id="absent.wavewarp"><property name="amplitude" type="float" value="80"/><property name="direction" type="float" value="45"/></effect>'),
                         ('bulge edges', '<effect id="absent.bulge"><property name="strength" type="float" value="-1"/><property name="radius" type="float" value="1"/></effect>')]:
        yield name, engine.scene(engine.rect(1, 100, 100, 100, 100, 0xffff0000, extra=effect)), [((x, y), engine.RED) for x, y in [(1, 1), (198, 1), (1, 198), (198, 198), (1, 100), (100, 198)]]
    arrow = '<shape id="1" s=".arrow" startTime="0" endTime="1000" fillType="color">' + engine.transform('100,100,0') + '<fillColor value="#ffff0000"/><property name="start" type="vec2" value="-60,0"/><property name="end" type="vec2" value="60,0"/><property name="lineWidth" type="float" value="20"/><property name="headWidth" type="float" value="40"/><property name="headLength" type="float" value="30"/></shape>'
    yield 'arrow widths', engine.scene(arrow), [((70, 109), engine.RED), ((70, 111), engine.BLACK), ((70, 119), engine.BLACK)]
    camera = '<camera id="2" startTime="500" endTime="700">' + engine.transform('200,100,-300') + '</camera>'
    yield 'inactive camera', engine.scene(engine.rect(1, 100, 100, 15, 15, 0xffff0000) + camera), [((100, 100), engine.RED)]
    child = engine.rect(2, 100, 100, 40, 40, 0xffff0000, extra='<effect id="absent.invert"/>')
    group = '<embedScene id="1" startTime="0" endTime="1000">' + engine.transform('100,100,0') + '<scene width="200" height="200" fps="30" totalTime="1000" retime="off">' + child + '</scene></embedScene>'
    yield 'nested effects', engine.scene(group), [((100, 100), (0, 255, 255))]
    invert = '<effect id="absent.invert"/>'
    yield 'invert keeps the outside clear', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=invert)), [((100, 100), (0, 255, 255)), ((5, 5), engine.BLACK), ((50, 100), engine.BLACK)]
    keep = '<effect id="absent.invert"><property name="invert_alpha" type="float" value="0"/></effect>'
    yield 'invert without alpha keeps the outside clear', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=keep)), [((100, 100), (0, 255, 255)), ((5, 5), engine.BLACK)]
    split = '<effect id="absent.rgbsplit"><property name="splitDistance" type="float" value="0"/></effect>'
    for alpha in (0x40, 0x80, 0xc0):
        want = tuple(round(c * alpha / 255) for c in (255, 0x99, 0x33))
        yield f'rgb split at distance 0 keeps alpha {alpha:#x}', engine.scene(engine.rect(1, 100, 100, 40, 40, alpha << 24 | 0xff9933, extra=split)), [((100, 100), want), ((5, 5), engine.BLACK)]
    red = engine.rect(2, 100, 100, 40, 40, 0xffff0000)

    def framed(decoration, opacity=1.0):
        return engine.scene('<embedScene id="1" startTime="0" endTime="1000">' + engine.transform('100,100,0', opacity) + decoration + '<scene width="200" height="200" fps="30" totalTime="1000" retime="off">' + red + '</scene></embedScene>')
    yield 'group effects', framed(invert), [((100, 100), (0, 255, 255)), ((5, 5), engine.BLACK)]
    yield 'group effects keep the group opacity', framed(invert, 0.5), [((100, 100), (0, 128, 128)), ((5, 5), engine.BLACK)]
    yield 'group border', framed('<border direction="outside"><color value="#ffffffff"/><size value="40"/></border>'), [((100, 100), engine.RED), ((50, 100), (255, 255, 255)), ((100, 45), (255, 255, 255)), ((30, 100), engine.BLACK)]
    yield 'group shadow', framed('<shadow direction="outside"><color value="#ffffffff"/><size value="0"/><offset value="60,0"/></shadow>'), [((100, 100), engine.RED), ((170, 100), (255, 255, 255)), ((30, 100), engine.BLACK)]
    yield 'vivid neutral', engine.scene(engine.rect(1, 100, 100, 100, 100, 0xff808080) + engine.rect(2, 100, 100, 100, 100, 0xff808080, attrs=' blending="vivid-light"')), [((100, 100), (129, 129, 129))]
    neutral = fx('vhs', chroma=0, smear=0, scanlines=0, noise=0, tracking=0, wobble=0, saturation=1, headSwitch=0)
    yield 'vhs at zero leaves the colours', engine.scene(engine.rect(1, 100, 100, 60, 60, 0xff3366cc, extra=neutral)), [((100, 100), (0x33, 0x66, 0xcc)), ((5, 5), engine.BLACK)]
    title = "a missing picture draws nothing"
    yield 'placeholder opacity', *engine.CASES[title]


def fx(name, **params):
    return f'<effect id="absent.{name}">' + ''.join(f'<property name="{k}" type="float" value="{v}"/>' for k, v in params.items()) + '</effect>'


def moving(x0, x1):
    return f'<transform><location><kf t="0" v="{x0},100,0"/><kf t="1" v="{x1},100,0"/></location></transform>'


def keyed(id, x0, x1, extra):
    return (f'<shape id="{id}" label="k" startTime="0" endTime="1000" fillType="color" s=".rect">{moving(x0, x1)}'
            f'<fillColor value="#ffff0000"/><property name="size" type="vec2" value="10,10"/>{extra}</shape>')


def motion_cases():
    red, black, half = engine.RED, engine.BLACK, engine.HALF
    dot = lambda extra, hw=10, hh=10, argb=0xffff0000, attrs='': engine.rect(1, 100, 100, hw, hh, argb, extra=extra, attrs=attrs)
    yield 'oscillate right', engine.scene(dot(fx('oscillate', angle=0, frequency=0.5, magnitude=50))), [((150, 100), red), ((100, 100), black)], 15
    yield 'oscillate down', engine.scene(dot(fx('oscillate', angle=90, frequency=0.5, magnitude=50))), [((100, 150), red), ((100, 100), black)], 15
    yield 'oscillate triangle at a quarter cycle', engine.scene(dot(fx('oscillate', angle=0, frequency=0.25, magnitude=60, wave=1))), [((130, 100), red), ((100, 100), black), ((160, 100), black)], 15
    yield 'oscillate phase', engine.scene(dot(fx('oscillate', angle=0, frequency=0, magnitude=50, phase=0.75))), [((50, 100), red), ((100, 100), black)], 0
    yield 'spin clockwise', engine.scene(dot(fx('spin', speed=90), 60, 8)), [((130, 130), red), ((130, 70), black), ((160, 100), black)], 15
    yield 'spin counterclockwise', engine.scene(dot(fx('spin', speed=90, direction=1), 60, 8)), [((130, 70), red), ((130, 130), black)], 15
    yield 'swing', engine.scene(dot(fx('swing', angle=45, frequency=0.5), 60, 8)), [((130, 130), red), ((160, 100), black)], 15
    yield 'pulse size', engine.scene(dot(fx('pulsesize', amount=0.5, frequency=0.5), 40, 40)), [((155, 100), red), ((165, 100), black)], 15
    yield 'pulse opacity starts opaque', engine.scene(dot(fx('pulseopacity', frequency=0.5), argb=0xffffffff)), [((100, 100), (255, 255, 255))], 0
    yield 'pulse opacity half way', engine.scene(dot(fx('pulseopacity', frequency=0.5), argb=0xffffffff)), [((100, 100), half)], 15
    yield 'stretch axis', engine.scene(dot(fx('stretchaxis', angle=0, amount=2), 30, 30)), [((155, 100), red), ((100, 140), black)], 0
    yield 'stretch axis upright', engine.scene(dot(fx('stretchaxis', angle=90, amount=2), 30, 30)), [((100, 155), red), ((155, 100), black)], 0
    parent = '<nullobj id="5" label="n" startTime="0" endTime="1000">' + engine.transform('100,100,0') + fx('oscillate', angle=0, frequency=0.5, magnitude=50) + '</nullobj>'
    yield 'a child follows its moving parent', engine.scene(parent + engine.rect(1, 0, 0, 10, 10, 0xffff0000, attrs=' parent="5"')), [((150, 100), red), ((100, 100), black)], 15
    deep = ('<shape id="2" label="d" startTime="0" endTime="1000" fillType="color" s=".rect">' + engine.transform('20,20,60')
            + '<fillColor value="#ff00ff00"/><property name="size" type="vec2" value="5,5"/></shape>')
    yield 'moves are not applied twice in depth', engine.scene(dot(fx('oscillate', angle=0, frequency=0.5, magnitude=50)) + deep), [((150, 100), red), ((100, 100), black)], 15
    yield 'echo behind', engine.scene(keyed(1, 50, 150, fx('echo', seconds=0.5, count=1, alpha=0.5))), [((130, 100), red), ((80, 100), (128, 0, 0)), ((105, 100), black)], 24
    yield 'echo count', engine.scene(keyed(1, 50, 150, fx('echo', seconds=0.6, count=2, alpha=1))), [((130, 100), red), ((100, 100), red), ((70, 100), red), ((115, 100), black)], 24
    yield 'time quantization', engine.scene(keyed(1, 50, 150, fx('timequant', fps=2))), [((100, 100), red), ((117, 100), black)], 20


def centre(image):
    ys, xs = np.nonzero(image[:, :, 0] > 128)
    return (xs.mean(), ys.mean()) if len(xs) else (None, None)


def text(content):
    return engine.scene('<text id="1" fillType="color" startTime="0" endTime="1000" size="40" wrapWidth="150" align="left">' + engine.transform('20,70,0') + '<content>' + content + '</content><fillColor value="#ffffffff"/></text>')


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else engine.ROOT / 'build/remaining-engine-check'
    work.mkdir(parents=True, exist_ok=True)
    failed = 0
    for k, (name, xml, probes) in enumerate(cases()):
        image = engine.still('remaining' + str(k), xml, work)
        bad = [(xy, tuple(image[xy[1], xy[0]]), want) for xy, want in probes if np.abs(image[xy[1], xy[0]] - want).max() > engine.TOLERANCE]
        print(('FAIL ' if bad else 'ok   ') + name + (': ' + str(bad) if bad else ''), flush=True)
        failed += bool(bad)
    for tag, rotation, top, bottom in [(5, 270, engine.RED, engine.BLUE), (7, 90, engine.BLUE, engine.RED)]:
        name = 'legacy' + str(tag) + '.jpg'
        engine.exif_jpeg(work / name, tag)
        engine.adb('push', work / name, engine.DEVICE_DIR + '/' + name)
        xml = engine.scene('<media uri="file://' + engine.DEVICE_DIR + '/' + name + '" type="image/jpeg" orientation="0"/>' + engine.picture(name, 0))
        image = engine.still('remainingexif' + str(tag), xml, work)
        ok = all(np.abs(image[y, 100] - color).max() <= engine.TOLERANCE for y, color in [(50, top), (150, bottom)])
        print(('ok   ' if ok else 'FAIL ') + 'legacy EXIF ' + str(tag), flush=True)
        failed += not ok
        engine.adb('shell', 'rm', engine.DEVICE_DIR + '/' + name)
    for k, (name, xml, probes, frame) in enumerate(motion_cases()):
        image = engine.still('motion' + str(k), xml, work, frame)
        bad = [(xy, tuple(image[xy[1], xy[0]]), want) for xy, want in probes if np.abs(image[xy[1], xy[0]] - want).max() > engine.TOLERANCE]
        print(('FAIL ' if bad else 'ok   ') + name + (': ' + str(bad) if bad else ''), flush=True)
        failed += bool(bad)
    for effect, params in [('autoshake', {'magnitude': 40, 'rotation': 0}), ('randomdisplace', {'magnitude': 60}), ('randomjitter', {'angle': 90, 'magnitude': 40})]:
        shots = [engine.still(f'{effect}{seed}{again}', engine.scene(engine.rect(1, 100, 100, 10, 10, 0xffff0000, extra=fx(effect, seed=seed, **params))), work, 10)
                 for seed, again in [(1, 'a'), (1, 'b'), (2, 'a')]]
        moved = [tuple(round(float(v), 1) for v in centre(shot)) for shot in shots]
        ok = np.array_equal(shots[0], shots[1]) and not np.array_equal(shots[0], shots[2]) and np.hypot(moved[0][0] - 99.5, moved[0][1] - 99.5) > 1.5
        if effect == 'randomjitter':
            ok = ok and abs(moved[0][0] - 99.5) < 1 and abs(moved[0][1] - 99.5) > 1
        print(('ok   ' if ok else 'FAIL ') + effect + ' is steady for a seed and changes with it: ' + str(moved), flush=True)
        failed += not ok
    tape = engine.scene(engine.rect(1, 100, 100, 90, 90, 0xff3366cc, extra=fx('vhs')))
    shots = [engine.still(f'vhs{frame}{again}', tape, work, frame) for frame, again in [(10, 'a'), (10, 'b'), (20, 'a')]]
    ok = np.array_equal(shots[0], shots[1]) and not np.array_equal(shots[0], shots[2]) and np.abs(shots[0][100, 100] - (0x33, 0x66, 0xcc)).max() > 0
    print(('ok   ' if ok else 'FAIL ') + 'vhs is steady for a frame and moves with time', flush=True)
    failed += not ok
    hits = engine.run('motionhit', engine.scene(engine.rect(7, 100, 100, 10, 10, 0xffff0000, extra=fx('oscillate', angle=0, frequency=0.5, magnitude=50))), work, [(150, 100), (100, 100)], 15)
    engine.adb('shell', 'rm', engine.DEVICE_DIR + '/motionhit.png')
    got = [line.split()[-1] for line in hits.splitlines() if ' hit ' in line]
    ok = got == ['7', '0']
    print(('ok   ' if ok else 'FAIL ') + 'tapping follows an oscillating layer: ' + str(got), flush=True)
    failed += not ok
    lf = engine.still('remaininglf', text('A&#10;B'), work)
    crlf = engine.still('remainingcrlf', text('A&#13;&#10;B'), work)
    if lf.max() < 100:
        sys.exit('text control did not draw any glyphs')
    same = np.array_equal(lf, crlf)
    print(('ok   ' if same else 'FAIL ') + 'CRLF matches LF', flush=True)
    failed += not same
    timed = text('A B C D E').replace('</text>', '<effect id="com.alightcreative.effects.textspacing"><property name="letterspacing" type="float"><kf t="0" v="0"/><kf t="1" v="0.3"/></property></effect></text>')
    child = timed[timed.index('<text'):timed.index('</text>') + len('</text>')]
    group = '<embedScene id="9" startTime="500" endTime="1499">' + engine.transform('100,100,0') + '<scene width="200" height="200" fps="24" totalTime="1000" retime="off">' + child + '</scene></embedScene>'
    direct = engine.still('remainingtextdirect', timed.replace('fps="30"', 'fps="24"'), work, frame=6)
    nested = engine.still('remainingtextnested', engine.scene(group, fps=60).replace('totalTime="1000"', 'totalTime="1499"', 1), work, frame=45)
    same = np.array_equal(direct, nested)
    print(('ok   ' if same else 'FAIL ') + 'nested text spacing uses local time', flush=True)
    failed += not same
    video = work / 'nested.mp4'
    engine.video(video, 2, [engine.RED, engine.GREEN, engine.BLUE, (255, 255, 0)])
    engine.adb('push', video, engine.DEVICE_DIR + '/nested.mp4')
    media = '<media uri="file://' + engine.DEVICE_DIR + '/nested.mp4" type="video/mp4" duration="2000000"/>'
    layer = '<shape id="2" startTime="0" endTime="1999" fillType="media" fillVideo="file://' + engine.DEVICE_DIR + '/nested.mp4" outTime="2000" s=".rect">' + engine.transform('100,100,0') + '<property name="size" type="vec2" value="100,100"/></shape>'
    xml = engine.scene(media + '<embedScene id="1" startTime="500" endTime="2499">' + engine.transform('100,100,0') + '<scene width="200" height="200" fps="30" totalTime="1999" retime="off">' + layer + '</scene></embedScene>').replace('totalTime="1000"', 'totalTime="2499"', 1)
    r, g, b = engine.still('remainingvideo', xml, work, frame=45)[100, 100]
    ok = b > 150 and r < 100 and g < 100
    print(('ok   ' if ok else 'FAIL ') + 'nested video local time: ' + str((int(r), int(g), int(b))), flush=True)
    failed += not ok
    engine.adb('shell', 'rm', engine.DEVICE_DIR + '/nested.mp4')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
