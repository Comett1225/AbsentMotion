#!/usr/bin/env python3
from pathlib import Path
import io
import json
import struct
import sys
import numpy as np
from PIL import Image
import engine_check as engine


def cases():
    yield 'top-level effect control', engine.scene(engine.rect(1, 100, 100, 100, 100, 0xffff0000, extra='<effect id="absent.invert"/>')), [((100, 100), (0, 255, 255))]
    bulge = '<effect id="absent.bulge"><property name="amount" type="float" value="1"/><property name="radius" type="float" value="1"/></effect>'
    yield 'bulge edges', engine.scene(engine.rect(1, 100, 100, 100, 100, 0xffff0000, extra=bulge)), [((x, y), engine.RED) for x, y in [(1, 1), (198, 1), (1, 198), (198, 198), (1, 100), (100, 198)]]
    pinch = '<effect id="absent.bulge"><property name="amount" type="float" value="-1"/><property name="radius" type="float" value="1"/></effect>'
    yield 'pinch stays inside its layer', engine.scene(engine.rect(1, 100, 100, 50, 50, 0xffff0000, extra=pinch)), [((100, 100), engine.RED), ((5, 5), engine.BLACK), ((100, 30), engine.BLACK)]
    wave = '<effect id="absent.wavewarp"><property name="magnitude" type="float" value="10"/><property name="spacing" type="float" value="20"/></effect>'
    yield 'wave warp stays near its layer', engine.scene(engine.rect(1, 100, 100, 50, 50, 0xffff0000, extra=wave)), [((100, 100), engine.RED), ((5, 5), engine.BLACK), ((100, 25), engine.BLACK), ((100, 175), engine.BLACK), ((20, 100), engine.BLACK)]
    arrow = '<shape id="1" s=".arrow" startTime="0" endTime="1000" fillType="color">' + engine.transform('100,100,0') + '<fillColor value="#ffff0000"/><property name="start" type="vec2" value="-60,0"/><property name="end" type="vec2" value="60,0"/><property name="lineWidth" type="float" value="20"/><property name="headWidth" type="float" value="40"/><property name="headLength" type="float" value="30"/></shape>'
    yield 'arrow widths', engine.scene(arrow), [((70, 109), engine.RED), ((70, 111), engine.BLACK), ((70, 119), engine.BLACK)]
    camera = '<camera id="2" startTime="500" endTime="700">' + engine.transform('200,100,-300') + '</camera>'
    yield 'the only camera also frames the time outside its clip', engine.scene(engine.rect(1, 100, 100, 15, 15, 0xffff0000) + camera), \
        [((42, 100), engine.RED), ((100, 100), engine.BLACK)]
    later = '<camera id="3" startTime="800" endTime="1000">' + engine.transform('0,100,-300') + '</camera>'
    yield 'with no camera at the time the lowest camera frames it', engine.scene(engine.rect(1, 100, 100, 15, 15, 0xffff0000) + camera + later), \
        [((42, 100), engine.RED), ((158, 100), engine.BLACK)]
    hidden = camera.replace('<camera id="2"', '<camera id="2" hidden="true"')
    yield 'a hidden camera frames nothing', engine.scene(engine.rect(1, 100, 100, 15, 15, 0xffff0000) + hidden), [((100, 100), engine.RED)]
    child = engine.rect(2, 100, 100, 40, 40, 0xffff0000, extra='<effect id="absent.invert"/>')
    group = '<embedScene id="1" startTime="0" endTime="1000">' + engine.transform('100,100,0') + '<scene width="200" height="200" fps="30" totalTime="1000" retime="off">' + child + '</scene></embedScene>'
    yield 'nested effects', engine.scene(group), [((100, 100), (0, 255, 255))]
    invert = '<effect id="absent.invert"/>'
    yield 'invert keeps the outside clear', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=invert)), [((100, 100), (0, 255, 255)), ((5, 5), engine.BLACK), ((50, 100), engine.BLACK)]
    keep = '<effect id="absent.invert"><property name="invert_alpha" type="float" value="0"/></effect>'
    yield 'invert without alpha keeps the outside clear', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=keep)), [((100, 100), (0, 255, 255)), ((5, 5), engine.BLACK)]
    split = '<effect id="absent.rgbsplit"><property name="strength" type="float" value="0"/></effect>'
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
    still = dict(rotX=0, rotY=0, shading=0, shine=0)
    yield 'a cube at rest shows the layer on its front', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=fx('cube', **still))), [((100, 100), engine.RED), ((5, 5), engine.BLACK), ((100, 160), engine.BLACK)]
    yield 'a raster extrude at rest shows the layer', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=fx('rasterextrude', **still))), [((100, 100), engine.RED), ((5, 5), engine.BLACK)]
    turned = fx('flip', rotX=0, rotY=180, shading=0, shine=0, back=2).replace('</effect>', '<property name="backColor" type="color" value="#ff0000ff"/></effect>')
    yield 'a flipped layer shows its back colour', engine.scene(engine.rect(1, 100, 100, 40, 40, 0xffff0000, extra=turned)), [((100, 100), engine.BLUE), ((5, 5), engine.BLACK)]
    yield 'a layer turned 60 degrees around Y shows perspective', engine.scene(tilted(1, 60, 0)), [((60, 100), engine.BLACK), ((75, 100), engine.RED), ((115, 100), engine.RED), ((126, 100), engine.BLACK), ((70, 40), engine.RED), ((118, 45), engine.BLACK)]
    yield 'a layer tilted 60 degrees around X leans back', engine.scene(tilted(1, 0, 60)), [((100, 60), engine.BLACK), ((100, 85), engine.RED), ((100, 128), engine.RED), ((100, 138), engine.BLACK), ((40, 130), engine.RED), ((45, 82), engine.BLACK)]
    parent = '<nullobj id="5" label="n" startTime="0" endTime="1000"><transform><location value="100,100,0"/><tilt value="0,60"/></transform></nullobj>'
    yield 'a child turns in 3D with its parent', engine.scene(parent + engine.rect(2, -40, 0, 20, 20, 0xffff0000, attrs=' parent="5"')), [((50, 100), engine.BLACK), ((75, 100), engine.RED), ((85, 100), engine.RED), ((95, 100), engine.BLACK)]
    title = "a missing picture draws nothing"
    yield 'placeholder opacity', *engine.CASES[title]


def models():
    box = ('mtllib box.mtl\n' + ''.join(f'v {x} {y} {z}\n' for x, y, z in [(-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1), (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)])
           + 'usemtl red\nf 5 6 7 8\nf 2 1 4 3\nf 4 8 7 3\nf 1 2 6 5\nf 2 3 7 6\nf 1 5 8 4\n')
    positions = struct.pack('<12f', -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
    indices = struct.pack('<6H', 0, 1, 2, 0, 2, 3)
    blob = positions + indices
    doc = json.dumps({'asset': {'version': '2.0'}, 'scenes': [{'nodes': [0]}], 'nodes': [{'mesh': 0}],
                      'meshes': [{'primitives': [{'attributes': {'POSITION': 0}, 'indices': 1, 'material': 0}]}],
                      'materials': [{'pbrMetallicRoughness': {'baseColorFactor': [0, 1, 0, 1]}}], 'buffers': [{'byteLength': len(blob)}],
                      'bufferViews': [{'buffer': 0, 'byteOffset': 0, 'byteLength': 48}, {'buffer': 0, 'byteOffset': 48, 'byteLength': 12}],
                      'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': 4, 'type': 'VEC3'},
                                    {'bufferView': 1, 'componentType': 5123, 'count': 6, 'type': 'SCALAR'}]}).encode()
    doc += b' ' * (-len(doc) % 4)
    glb = struct.pack('<5I', 0x46546C67, 2, 28 + len(doc) + len(blob), len(doc), 0x4E4F534A) + doc + struct.pack('<2I', len(blob), 0x004E4942) + blob

    def stack(reds):
        lines, n = ['mtllib layers.mtl', 'vt 0 0', 'vt 1 0', 'vt 1 1', 'vt 0 1'], 0
        for name, z in [('red', z) for z in reds] + [('green', 0)]:
            lines += [f'usemtl {name}'] + [f'v {x} {y} {z}' for x, y in [(-0.6, -0.6), (0.6, -0.6), (0.6, 0.6), (-0.6, 0.6)]]
            lines += [f'f {n + 1}/1 {n + 2}/2 {n + 3}/3 {n + 4}/4', f'f {n + 4}/4 {n + 3}/3 {n + 2}/2 {n + 1}/1']
            n += 4
        return ('\n'.join(lines) + '\n').encode()
    green = io.BytesIO()
    Image.new('RGB', (4, 4), engine.GREEN).save(green, 'PNG')
    return {'box.obj': box.encode(), 'box.mtl': b'newmtl red\nKd 1 0 0\n', 'card.glb': glb, 'layers.mtl': b'newmtl red\nKd 1 0 0\nnewmtl green\nKd 1 1 1\nmap_Kd green.png\n',
            'green.png': green.getvalue(), 'layers1.obj': stack([-0.5, 0.5, 0.45]), 'layers2.obj': stack([-0.5, 0.5, -0.45])}


def model(id, name, z=0, extra=''):
    return (f'<model id="{id}" label="m" startTime="0" endTime="1000" src="file://{engine.DEVICE_DIR}/{name}"><transform><location value="100,100,{z}"/>{extra}</transform>'
            '<property name="size" type="float" value="60"/><property name="ambient" type="float" value="1"/><property name="intensity" type="float" value="0"/></model>')


def model_cases():
    yield 'an OBJ model draws in its material colour', engine.scene(model(1, 'box.obj')), [((100, 100), engine.RED), ((10, 10), engine.BLACK), ((100, 185), engine.BLACK)]
    yield 'a glTF model draws in its base colour', engine.scene(model(1, 'card.glb')), [((100, 100), engine.GREEN), ((10, 10), engine.BLACK)]
    yield 'a glTF card turned away is culled', engine.scene(model(1, 'card.glb', extra='<tilt value="0,180"/>')), [((100, 100), engine.BLACK)]
    yield 'a layer in front of a model covers it', engine.scene(model(1, 'box.obj') + engine.rect(2, 100, 100, 10, 10, 0xff0000ff).replace('100,100,0', '100,100,-100')), [((100, 100), engine.BLUE), ((100, 70), engine.RED)]
    yield 'a layer behind a model is covered', engine.scene(engine.rect(2, 100, 100, 10, 10, 0xff0000ff).replace('100,100,0', '100,100,200') + model(1, 'box.obj')), [((100, 100), engine.RED)]
    for k in (1, 2):
        yield f'materials are layered by depth, not by material ({k})', engine.scene(model(1, f'layers{k}.obj')), [((100, 100), engine.RED)]


def tilted(id, y, x, extra=''):
    return (f'<shape id="{id}" label="t" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="100,100,0"/>'
            f'<tilt value="{x},{y}"/></transform><fillColor value="#ffff0000"/>' + (extra or '<property name="size" type="vec2" value="50,50"/>') + '</shape>')


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
    yield 'oscillate phase', engine.scene(dot(fx('oscillate', angle=0, frequency=0, magnitude=50, phase=1.5))), [((50, 100), red), ((100, 100), black)], 0
    yield 'spin clockwise', engine.scene(dot(fx('spin', rpm=15), 60, 8)), [((130, 130), red), ((130, 70), black), ((160, 100), black)], 15
    yield 'spin counterclockwise', engine.scene(dot(fx('spin', rpm=-15), 60, 8)), [((130, 70), red), ((130, 130), black)], 15
    yield 'swing', engine.scene(dot(fx('swing', angle1=-45, angle2=45, frequency=0.5), 60, 8)), [((130, 130), red), ((160, 100), black)], 15
    yield 'pulse size', engine.scene(dot(fx('pulsesize', shrink=0.5, grow=1.5, frequency=0.5), 40, 40)), [((155, 100), red), ((165, 100), black)], 15
    yield 'pulse opacity phase 0.5 starts opaque', engine.scene(dot(fx('pulseopacity', frequency=0.5, phase=0.5), argb=0xffffffff)), [((100, 100), (255, 255, 255))], 0
    yield 'pulse opacity half way', engine.scene(dot(fx('pulseopacity', frequency=0.5, phase=0.5), argb=0xffffffff)), [((100, 100), half)], 15
    yield 'pulse opacity starts half way and its strength sets the lowest opacity', engine.scene(dot(fx('pulseopacity', frequency=0.5, strength=0.5), argb=0xffffffff)), [((100, 100), (191, 191, 191))], 0
    yield 'pulse opacity reaches 1 - strength', engine.scene(dot(fx('pulseopacity', frequency=1.5, strength=0.5), argb=0xffffffff)), [((100, 100), half)], 15
    yield 'stretch axis', engine.scene(dot(fx('stretchaxis', angle=0, scale=2), 30, 30)), [((155, 100), red), ((100, 140), black)], 0
    yield 'stretch axis upright', engine.scene(dot(fx('stretchaxis', angle=90, scale=2), 30, 30)), [((100, 155), red), ((155, 100), black)], 0
    yield 'stretch axis masked to the layer', engine.scene(dot(fx('stretchaxis', angle=0, scale=2, mask=1), 30, 30)), [((115, 100), red), ((145, 100), black)], 0
    yield 'oscillate orbit starts along the angle', engine.scene(dot(fx('oscillate', direction=2, angle=0, frequency=0.5, magnitude=50))), [((150, 100), red), ((100, 100), black)], 0
    yield 'oscillate orbit turns clockwise', engine.scene(dot(fx('oscillate', direction=2, angle=0, frequency=0.5, magnitude=50))), [((100, 150), red), ((150, 100), black)], 15
    yield 'swing phase 0.5 starts at angle 2', engine.scene(dot(fx('swing', angle1=0, angle2=90, frequency=0, phase=0.5), 60, 8)), [((100, 150), red), ((150, 100), black)], 0
    yield 'pulse size phase 0.5 starts at grow', engine.scene(dot(fx('pulsesize', shrink=0.5, grow=1.5, frequency=0, phase=0.5), 40, 40)), [((155, 100), red), ((165, 100), black)], 0
    blue = engine.BLUE
    halves = lambda extra: engine.scene('<embedScene id="1" startTime="0" endTime="1000">' + engine.transform('100,100,0') + extra
                                        + '<scene width="200" height="200" fps="30" totalTime="1000" retime="off">' + engine.rect(2, 50, 100, 50, 100, 0xffff0000)
                                        + engine.rect(3, 150, 100, 50, 100, 0xff00ff00) + '</scene></embedScene>')
    shift = lambda value: f'<effect id="absent.offset"><property name="offset" type="vec2" value="{value}"/></effect>'
    yield 'offset wraps the picture round', halves(shift('100,0')), [((50, 100), (0, 255, 0)), ((150, 100), red)], 0
    yield 'offset wraps backwards too', halves(shift('-50,0')), [((25, 100), red), ((125, 100), (0, 255, 0)), ((175, 100), red)], 0
    guide = engine.rect(1, 100, 100, 50, 50, 0xff0000ff)
    rider = lambda extra, hw=6, hh=6: engine.rect(2, 0, 0, hw, hh, 0xffff0000, extra=extra)
    yield 'move along path follows the layer beneath', engine.scene(guide + rider(fx('movealongpath', progress=0.375))), [((150, 100), red), ((100, 100), blue), ((140, 60), blue)], 0
    yield 'move along path turns with the path', engine.scene(guide + rider(fx('movealongpath', progress=0.375), 20, 3)), [((150, 115), red), ((165, 100), black)], 0
    yield 'move along path keeps its angle when told', engine.scene(guide + rider(fx('movealongpath', progress=0.375, rotate=0), 20, 3)), [((165, 100), red), ((140, 115), blue)], 0
    yield 'move along path outset', engine.scene(guide + rider(fx('movealongpath', progress=0.375, inset=20))), [((170, 100), red), ((145, 100), blue)], 0
    yield 'move along path inset', engine.scene(guide + rider(fx('movealongpath', progress=0.375, inset=-20))), [((130, 100), red), ((155, 100), black)], 0
    yield 'move along path progress moves on', engine.scene(guide + rider(fx('movealongpath', progress=0.625))), [((100, 150), red), ((140, 100), blue)], 0
    parent = '<nullobj id="5" label="n" startTime="0" endTime="1000">' + engine.transform('100,100,0') + fx('oscillate', angle=0, frequency=0.5, magnitude=50) + '</nullobj>'
    yield 'a child follows its moving parent', engine.scene(parent + engine.rect(1, 0, 0, 10, 10, 0xffff0000, attrs=' parent="5"')), [((150, 100), red), ((100, 100), black)], 15
    deep = ('<shape id="2" label="d" startTime="0" endTime="1000" fillType="color" s=".rect">' + engine.transform('20,20,60')
            + '<fillColor value="#ff00ff00"/><property name="size" type="vec2" value="5,5"/></shape>')
    yield 'moves are not applied twice in depth', engine.scene(dot(fx('oscillate', angle=0, frequency=0.5, magnitude=50)) + deep), [((150, 100), red), ((100, 100), black)], 15
    yield 'echo behind', engine.scene(keyed(1, 50, 150, fx('echo', seconds=0.5, count=1, alpha=0.5))), [((130, 100), red), ((80, 100), (128, 0, 0)), ((105, 100), black)], 24
    echo = keyed(1, 50, 150, fx('echo', seconds=0.6, count=2, alpha=1))
    trail = [((130, 100), red), ((100, 100), red), ((70, 100), red), ((115, 100), black)]
    yield 'echo count', engine.scene(echo), trail, 24
    yield 'time quantization', engine.scene(keyed(1, 50, 150, fx('timequant', fps=2))), [((100, 100), red), ((117, 100), black)], 20
    cyan, green = (0, 255, 255), (0, 255, 0)
    family = lambda shared, own='': (engine.rect(5, 50, 100, 15, 15, 0xffff0000, extra=shared) + engine.rect(6, 100, 0, 15, 15, 0xffff0000, extra=own, attrs=' parent="5"')
                                     + engine.rect(7, 0, 50, 15, 15, 0xffff0000, attrs=' parent="6"'))
    yield 'a shared effect reaches children and grandchildren', engine.scene(family('<effect id="absent.invert" applyToChildren="true"/>')), [((50, 100), cyan), ((150, 100), cyan), ((150, 150), cyan)], 0
    yield 'an unshared effect stays on its layer', engine.scene(family('<effect id="absent.invert"/>')), [((50, 100), cyan), ((150, 100), red), ((150, 150), red)], 0
    solid = '<effect id="absent.solid" applyToChildren="true"><property name="color" type="color" value="#ff00ff00"/><property name="amount" type="float" value="1"/></effect>'
    yield 'a shared effect comes after the child\'s own effects', engine.scene(family(solid, '<effect id="absent.invert"/>')), [((150, 100), green)], 0
    yield 'echo keeps its trail under a camera', engine.scene(echo + EYE), trail, 24
    yield 'echo keeps its trail next to a layer in depth', engine.scene(echo + deep), trail, 24
    yield 'time quantization steps effect time too', engine.scene(dot(fx('timequant', fps=2) + fx('fade', fadeIn=1, fadeOut=0))), [((100, 100), (128, 0, 0))], 20


EYE = '<camera id="9" label="e" startTime="0" endTime="1000"><transform><location value="100,100,-173.2051"/></transform></camera>'


def shutter_scene(extra, size='10,10', moves=True, spins=False, camera=''):
    location = '<location><kf t="0" v="-500,100,0"/><kf t="1" v="700,100,0"/></location>' if moves else '<location value="100,100,0"/>'
    rotation = '<rotation><kf t="0" v="0"/><kf t="1" v="3600"/></rotation>' if spins else ''
    return engine.scene(f'<shape id="1" label="b" startTime="0" endTime="1000" fillType="color" s=".rect"><transform>{location}{rotation}</transform>'
                        f'<fillColor value="#ffff0000"/><property name="size" type="vec2" value="{size}"/>{extra}</shape>{camera}')


def shutter_cases():
    blur = fx('shutterblur', shutter=180, samples=8)
    hidden = blur.replace('<effect id="absent.shutterblur"', '<effect id="absent.shutterblur" hidden="true"')
    red = lambda image, x, y: int(image[y, x][0])
    even = lambda i: red(i, 100, 100) > 250 and 30 < red(i, 114, 100) < 100 and abs(red(i, 114, 100) - red(i, 85, 100)) < 8 and red(i, 121, 100) < 3
    yield 'true motion blur smears a moving layer evenly both ways', shutter_scene(blur), even
    yield 'a hidden true motion blur leaves the layer sharp', shutter_scene(hidden), lambda i: red(i, 105, 100) > 250 and red(i, 114, 100) < 3
    yield 'a closed shutter leaves the layer sharp', shutter_scene(fx('shutterblur', shutter=0, samples=8)), lambda i: red(i, 114, 100) < 3
    yield 'true motion blur follows rotation', shutter_scene(blur, '60,6', moves=False, spins=True), \
        lambda i: red(i, 100, 100) > 250 and 20 < red(i, 148, 113) < 130 and red(i, 135, 135) < 3
    yield 'a spinning layer without blur stays sharp', shutter_scene('', '60,6', moves=False, spins=True), lambda i: red(i, 148, 113) < 3
    camera = ('<camera id="2" label="c" startTime="0" endTime="1000"><transform><location><kf t="0" v="-500,100,-300"/><kf t="1" v="700,100,-300"/></location>'
              '</transform>{}</camera>')
    yield 'the camera motion blur still smears what it sees', shutter_scene('', '20,20', moves=False, camera=camera.format('<effect id="absent.motionblur"/>')), \
        lambda i: red(i, 100, 100) > 150 and 15 < red(i, 116, 100) < 200
    yield 'a camera without motion blur leaves layers sharp', shutter_scene('', '20,20', moves=False, camera=camera.format('')), lambda i: red(i, 116, 100) < 3
    yield 'true motion blur smears evenly under a camera', shutter_scene(blur, camera=EYE), even


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
    for k, (name, xml, ok) in enumerate(shutter_cases()):
        image = engine.still('shutter' + str(k), xml, work, 15)
        good = ok(image)
        print(('ok   ' if good else 'FAIL ') + name + ('' if good else ': ' + str([tuple(int(v) for v in image[100, x]) for x in (85, 100, 114, 116, 121)])), flush=True)
        failed += not good
    for effect, params in [('autoshake', {'magnitude': 40, 'slack': 0}), ('randomdisplace', {'magnitude': 60}), ('randomjitter', {'angle': 90, 'magnitude': 40})]:
        shots = [engine.still(f'{effect}{seed}{again}', engine.scene(engine.rect(1, 100, 100, 10, 10, 0xffff0000, extra=fx(effect, seed=seed, **params))), work, 10)
                 for seed, again in [(1, 'a'), (1, 'b'), (2, 'a')]]
        moved = [tuple(round(float(v), 1) for v in centre(shot)) for shot in shots]
        ok = np.array_equal(shots[0], shots[1]) and not np.array_equal(shots[0], shots[2]) and np.hypot(moved[0][0] - 99.5, moved[0][1] - 99.5) > 1.5
        if effect == 'randomjitter':
            ok = ok and abs(moved[0][0] - 99.5) < 1 and abs(moved[0][1] - 99.5) > 1
        if effect == 'autoshake':
            ok = ok and abs(moved[0][1] - moved[0][0]) < 1
        print(('ok   ' if ok else 'FAIL ') + effect + ' is steady for a seed and changes with it: ' + str(moved), flush=True)
        failed += not ok
    still = [engine.still(f'displace{frame}', engine.scene(engine.rect(1, 100, 100, 10, 10, 0xffff0000, extra=fx('randomdisplace', magnitude=60, evolution=0.4))), work, frame) for frame in (3, 27)]
    keyed = '<effect id="absent.randomdisplace"><property name="magnitude" type="float" value="60"/><property name="evolution" type="float"><kf t="0" v="0"/><kf t="1" v="3"/></property></effect>'
    drift = [engine.still(f'drift{frame}', engine.scene(engine.rect(1, 100, 100, 10, 10, 0xffff0000, extra=keyed)), work, frame) for frame in (3, 27)]
    ok = np.array_equal(still[0], still[1]) and not np.array_equal(drift[0], drift[1])
    print(('ok   ' if ok else 'FAIL ') + 'random displacement holds still until Evolution changes', flush=True)
    failed += not ok
    turned = lambda magnitude, scale, rotation: engine.scene(
        f'<shape id="1" label="t" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="100,100,0"/><scale value="{scale}"/>'
        f'<rotation value="{rotation}"/></transform><fillColor value="#ffff0000"/><property name="size" type="vec2" value="6,6"/>'
        + fx('randomdisplace', magnitude=magnitude, evolution=0.3) + '</shape>')
    shifts = [np.subtract(centre(engine.still(f'turned{k}', turned(*case), work)), 99.5) for k, case in enumerate([(20, '1,1', 0), (80, '1,1', 0), (80, '2.4,1.1', 30)])]
    ok = np.hypot(*shifts[0]) > 2 and np.allclose(shifts[1] / 4, shifts[0], atol=1) and np.allclose(shifts[2], shifts[1], atol=1.5)
    print(('ok   ' if ok else 'FAIL ') + 'random displacement grows with Magnitude in one direction and ignores the layer\'s own scale and rotation: ' + str([tuple(round(float(v), 1) for v in s) for s in shifts]), flush=True)
    failed += not ok
    sizes = [int((engine.still(f'depth{k}', engine.scene(engine.rect(1, 100, 100, 20, 20, 0xffff0000, extra=extra)), work)[:, :, 0] > 128).sum())
             for k, extra in enumerate(['', fx('oscillate', direction=1, magnitude=150, frequency=0, phase=0.5), fx('autoshake', magnitude=0, zshake=300, evolution=0.3)])]
    ok = sizes[1] != sizes[0] and sizes[2] != sizes[0]
    print(('ok   ' if ok else 'FAIL ') + 'depth from Oscillate and Z Shake shows in perspective without a camera: ' + str(sizes), flush=True)
    failed += not ok
    tape = engine.scene(engine.rect(1, 100, 100, 90, 90, 0xff3366cc, extra=fx('vhs')))
    shots = [engine.still(f'vhs{frame}{again}', tape, work, frame) for frame, again in [(10, 'a'), (10, 'b'), (20, 'a')]]
    ok = np.array_equal(shots[0], shots[1]) and not np.array_equal(shots[0], shots[2]) and np.abs(shots[0][100, 100] - (0x33, 0x66, 0xcc)).max() > 0
    print(('ok   ' if ok else 'FAIL ') + 'vhs is steady for a frame and moves with time', flush=True)
    failed += not ok
    bent = [engine.still(f'bend{k}', engine.scene(engine.rect(1, 100, 100, 8, 80, 0xffff0000, extra=fx('bend', angle=angle, start=start))), work)[:, :, 0] > 128
            for k, (angle, start) in enumerate([(80, 0), (-80, 0), (80, 0.4), (-80, 0.4)])]
    apart = [int((bent[k] ^ bent[k + 1][:, ::-1]).sum()) for k in (0, 2)]
    ok = min(bent[0].sum(), bent[2].sum()) > 2000 and bent[0][:, 120:].sum() > 300 and bent[2][:, 120:].sum() > 300 and max(apart) <= 0.01 * bent[0].sum()
    print(('ok   ' if ok else 'FAIL ') + 'bending left draws bending right mirrored: ' + str(apart) + ' pixels differ', flush=True)
    failed += not ok
    hits = engine.run('motionhit', engine.scene(engine.rect(7, 100, 100, 10, 10, 0xffff0000, extra=fx('oscillate', angle=0, frequency=0.5, magnitude=50))), work, [(150, 100), (100, 100)], 15)
    engine.adb('shell', 'rm', engine.DEVICE_DIR + '/motionhit.png')
    got = [line.split()[-1] for line in hits.splitlines() if ' hit ' in line]
    ok = got == ['7', '0']
    print(('ok   ' if ok else 'FAIL ') + 'tapping follows an oscillating layer: ' + str(got), flush=True)
    failed += not ok
    hits = engine.run('tilthit', engine.scene(tilted(7, 60, 0)), work, [(75, 100), (126, 100), (70, 40), (118, 45)])
    engine.adb('shell', 'rm', engine.DEVICE_DIR + '/tilthit.png')
    got = [line.split()[-1] for line in hits.splitlines() if ' hit ' in line]
    ok = got == ['7', '0', '7', '0']
    print(('ok   ' if ok else 'FAIL ') + 'tapping follows a layer turned in 3D: ' + str(got), flush=True)
    failed += not ok
    for name, data in models().items():
        (work / name).write_bytes(data)
        engine.adb('push', work / name, engine.DEVICE_DIR + '/' + name)
    for k, (name, xml, probes) in enumerate(model_cases()):
        image = engine.still('model' + str(k), xml, work)
        bad = [(xy, tuple(image[xy[1], xy[0]]), want) for xy, want in probes if np.abs(image[xy[1], xy[0]] - want).max() > engine.TOLERANCE]
        print(('FAIL ' if bad else 'ok   ') + name + (': ' + str(bad) if bad else ''), flush=True)
        failed += bool(bad)
    turned = lambda extra, effect='': engine.scene(model(1, 'box.obj', extra='<tilt value="0,45"/>' + extra).replace('</model>', effect + '</model>'))
    faded = engine.still('modelfaded', turned('<opacity value="0.5"/>'), work)
    copied = engine.still('modelcopied', turned('', fx('linearrepeat', count=1, rangeOpacity=0.5, rangeStart=-1, rangeEnd=0)), work)
    ok = faded.max() > 100 and np.abs(faded - copied).max() <= engine.TOLERANCE
    print(('ok   ' if ok else 'FAIL ') + 'a faded repeat copy of a turned model is not clipped: ' + str(int((np.abs(faded - copied).max(axis=2) > engine.TOLERANCE).sum())) + ' pixels differ', flush=True)
    failed += not ok
    hits = engine.run('modelhit', engine.scene(model(4, 'box.obj')), work, [(100, 100), (10, 10)])
    engine.adb('shell', 'rm', engine.DEVICE_DIR + '/modelhit.png')
    got = [line.split()[-1] for line in hits.splitlines() if ' hit ' in line]
    ok = got == ['4', '0']
    print(('ok   ' if ok else 'FAIL ') + 'tapping finds a 3D model: ' + str(got), flush=True)
    failed += not ok
    for name in models():
        engine.adb('shell', 'rm', engine.DEVICE_DIR + '/' + name)
    lf = engine.still('remaininglf', text('A&#10;B'), work)
    crlf = engine.still('remainingcrlf', text('A&#13;&#10;B'), work)
    if lf.max() < 100:
        sys.exit('text control did not draw any glyphs')
    same = np.array_equal(lf, crlf)
    print(('ok   ' if same else 'FAIL ') + 'CRLF matches LF', flush=True)
    failed += not same
    words = ('<text id="1" label="w" fillType="color" startTime="0" endTime="1000" size="90" wrapWidth="190" align="left">' + engine.transform('20,40,0')
             + '<content>O</content><fillColor value="#ff0000ff"/></text>')
    spots = [centre(engine.still(f'textpath{k}', engine.scene(words + engine.rect(2, 0, 0, 3, 3, 0xffff0000, extra=effect)), work))
             for k, effect in enumerate([fx('movealongpath', progress=0, rotate=0), fx('repeatalongpath', count=1)])]
    ok = None not in spots[0] + spots[1] and np.hypot(spots[0][0] - spots[1][0], spots[0][1] - spots[1][1]) < 1.5
    print(('ok   ' if ok else 'FAIL ') + 'move along path and repeat along path both start on the letter outline of text: ' + str(spots), flush=True)
    failed += not ok
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
