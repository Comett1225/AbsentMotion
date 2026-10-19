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
    yield 'vivid neutral', engine.scene(engine.rect(1, 100, 100, 100, 100, 0xff808080) + engine.rect(2, 100, 100, 100, 100, 0xff808080, attrs=' blending="vivid-light"')), [((100, 100), (129, 129, 129))]
    title = "a missing picture's placeholder takes the layer opacity once"
    yield 'placeholder opacity', *engine.CASES[title]


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
