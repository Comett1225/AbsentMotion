import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/render_bench'))
from run import effect_xml, fx

WORK = ROOT / 'build/pieces3d_check'
BIN = ROOT / 'build/render_bench/current/render_bench'


DEPTH = ' depthOrder="true"'
PIECE = DEPTH + ' depthSort="piece"'
CAMERA = '<camera id="9" startTime="0" endTime="1000"><transform><location value="160,160,-277.13"/></transform></camera>'
RED, BLUE = '#ffff2020', '#ff2040ff'


def scene(body, extra=''):
    return '<scene title="pieces" width="320" height="320" fps="30" totalTime="1000" bgcolor="#ff101418"' + extra + '>' + body + '</scene>'


def shape(effect='', tilt='', parent='', location='160,160,0'):
    return ('<shape id="1" label="s" startTime="0" endTime="1000" fillType="media" fillImage="synthetic" mediaFillMode="fill" s=".rect"'
            + parent + '><transform><location value="' + location + '"/>' + tilt + '</transform>'
            + '<property name="size" type="vec2" value="90,70"/>' + effect + '</shape>')


def render(name, xml, frames=(0,), extra=''):
    path = WORK / name
    path.mkdir(parents=True, exist_ok=True)
    (path / 'scene.xml').write_text(scene(xml, extra))
    subprocess.run([str(BIN), str(WORK / 'effects.txt'), str(path / 'scene.xml'), '1', '0', str(path), *map(str, frames)],
                   check=True, stdout=subprocess.DEVNULL)
    return [(path / f'frame{i}.rgba').read_bytes() for i in range(len(frames))]


def different(a, b):
    return sum(x != y for x, y in zip(a, b))


def plate(i, color, effect='', z=0, extra=''):
    return ('<shape id="' + str(i) + '" label="p" startTime="0" endTime="1000" fillType="color" s=".rect"' + extra
            + '><transform><location value="160,160,' + str(z) + '"/></transform><fillColor value="' + color + '"/>'
            + '<property name="size" type="vec2" value="110,90"/>' + effect + '</shape>')


def shards(seed):
    return fx('fracture3d', progress=0.6, force=0, gravity=0, spin=0, depthPush=300, thickness=0, pieces=60, seed=seed)


def pixels(frame, test):
    return {i // 4 for i in range(0, len(frame), 4) if test(frame[i], frame[i + 1], frame[i + 2])}


def reds(frame):
    return pixels(frame, lambda r, g, b: r > 120 and r > b + 60)


def blues(frame):
    return pixels(frame, lambda r, g, b: b > 120 and b > r + 60)


def lit(frame):
    return pixels(frame, lambda r, g, b: r + g + b > 120)


def white(frame):
    return pixels(frame, lambda r, g, b: min(r, g, b) > 200)


def share(part, whole):
    return len(part & whole) / max(1, len(whole))


def mixing():
    alone = render('lone_layer', CAMERA + plate(1, RED, shards(1)), extra=DEPTH)[0]
    assert alone == render('lone_piece', CAMERA + plate(1, RED, shards(1)), extra=PIECE)[0], 'a lone fracture must look the same by piece'
    unbroken = CAMERA + plate(1, RED, fx('invert') + fx('fracture3d', progress=0))
    assert render('unbroken_layer', unbroken, extra=DEPTH)[0] == render('unbroken_piece', unbroken, extra=PIECE)[0], 'an unbroken fracture keeps the effects before it by piece'
    flat = CAMERA + plate(1, RED) + plate(2, BLUE, z=150, extra=' threeD="true"')
    assert render('flat_layer', flat, extra=DEPTH)[0] == render('flat_piece', flat, extra=PIECE)[0], 'whole layers must not change by piece'
    pair = CAMERA + plate(1, RED, shards(1)) + plate(2, BLUE, shards(2))
    overlap = reds(alone) & blues(render('pair_b', CAMERA + plate(2, BLUE, shards(2)), extra=PIECE)[0])
    assert len(overlap) > 2000, 'the two fractures must overlap'
    whole = render('pair_layer', pair, extra=DEPTH)[0]
    mixed = render('pair_piece', pair, extra=PIECE)[0]
    assert share(reds(whole), overlap) < 0.05, 'by layer, the top fracture covers the other'
    assert 0.2 < share(reds(mixed), overlap) < 0.8 and share(blues(mixed), overlap) > 0.2, 'by piece, two fractures interleave'
    turned = render('pair_off', CAMERA + plate(1, RED, shards(1), extra=' mixPieces="false"') + plate(2, BLUE, shards(2)), extra=PIECE)[0]
    assert share(reds(turned), overlap) > 0.95, 'a layer with mixing off is one item at its own depth, in front of the pushed shards'
    wall = plate(2, BLUE, z=180, extra=' threeD="true"')
    overlap = reds(alone) & blues(render('wall', CAMERA + wall, extra=PIECE)[0])
    through = CAMERA + plate(1, RED, shards(1)) + wall
    assert share(reds(render('wall_layer', through, extra=DEPTH)[0]), overlap) > 0.95, 'by layer, the nearer fracture covers the wall'
    assert 0.1 < share(reds(render('wall_piece', through, extra=PIECE)[0]), overlap) < 0.9, 'by piece, shards pass through the wall'
    cube = '<model id="4" label="m" startTime="0" endTime="1000" src="synthetic"><transform><location value="160,160,0"/><tilt value="20,30"/></transform><property name="size" type="float" value="150"/></model>'
    plane = plate(2, BLUE, extra=' threeD="true"')
    overlap = lit(render('cube', CAMERA + cube, extra=PIECE)[0]) & blues(render('plane', CAMERA + plane, extra=PIECE)[0])
    assert share(blues(render('cube_layer', CAMERA + cube + plane, extra=DEPTH)[0]), overlap) > 0.95, 'by layer, the top plane hides the cube'
    assert share(blues(render('cube_piece', CAMERA + cube + plane, extra=PIECE)[0]), overlap) < 0.7, 'by piece, the cube pokes through the plane'
    cloud = ('<shape id="5" label="c" startTime="0" endTime="1000" fillType="color" s=".ellipse"><transform><location value="160,160,0"/></transform>'
             '<fillColor value="#ffffffff"/><property name="size" type="vec2" value="100,80"/>'
             + fx('particles', depth=1, perspective=0, zSpread=900, emitter=2, emitterSize=1, layer=2, speed=0, gravity=0, swirl=0, wind=0, rate=60,
                  lifetime=10, fadeIn=0, fadeOut=0, size=1.5, sizeVariation=0)
             + '</shape>')
    overlap = white(render('cloud', CAMERA + cloud, (900,), PIECE)[0]) & blues(render('cloud_plane', CAMERA + plane, (900,), PIECE)[0])
    assert len(overlap) > 300, 'the particles must cover the plane'
    assert share(blues(render('cloud_layer', CAMERA + plane + cloud, (900,), DEPTH)[0]), overlap) < 0.05, 'by layer, the top particles stay in front'
    assert share(blues(render('cloud_piece', CAMERA + plane + cloud, (900,), PIECE)[0]), overlap) > 0.2, 'by piece, far particles go behind the plane'


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    subprocess.run(['sh', str(ROOT / 'tools/render_bench/build.sh')], check=True, stdout=subprocess.DEVNULL)
    (WORK / 'effects.txt').write_text('\n'.join(effect_xml(key) for key in ['scatter3d', 'fracture3d', 'invert']) + '\n')
    base = render('base', shape())[0]
    whole = render('whole', shape(fx('fracture3d', progress=0)))[0]
    assert base == whole, 'zero progress must retain the original'
    broken = render('broken', shape(fx('fracture3d', progress=0.4)))[0]
    assert different(base, broken) > 1000, 'progress must move the shards'
    same = render('same', shape(fx('fracture3d', progress=0.4)))[0]
    assert broken == same, 'fracture must be deterministic'
    seeded = render('seeded', shape(fx('fracture3d', progress=0.4, seed=7)))[0]
    assert different(broken, seeded) > 1000, 'seed must change the fracture'
    animated = '<effect id="absent.fracture3d"><property name="progress" type="float"><kf t="0" v="0"/><kf t="1" v="0.7"/></property></effect>'
    frames = render('animated', shape(animated), (0, 500, 999))
    assert different(frames[0], frames[1]) > 1000 and different(frames[1], frames[2]) > 1000, 'progress must animate'
    scatter = render('scatter', shape(fx('scatter3d', spacing=15)))[0]
    assert different(base, scatter) > 1000, 'scatter must replace the original'
    for kind in range(1, 4):
        other = render('shape' + str(kind), shape(fx('scatter3d', spacing=15, shape=kind)))[0]
        assert different(scatter, other) > 500, 'piece shapes must differ'
    dense = render('dense', shape(fx('scatter3d', spacing=1, depth=16, depthSpacing=0, shape=1)))[0]
    assert different(dense, render('empty', '')[0]) > 100, 'dense grids must remain renderable'
    tilted = render('tilted', shape(fx('scatter3d', spacing=15), '<tilt value="25,35"/>'))[0]
    assert different(scatter, tilted) > 1000, 'layer tilt must change the grid'
    parent = '<nullobj id="2" startTime="0" endTime="1000"><transform><location value="160,160,0"/><tilt value="25,35"/></transform></nullobj>'
    child = render('parent', parent + shape(fx('scatter3d', spacing=15), parent=' parent="2"', location='0,0,0'))[0]
    assert different(scatter, child) > 1000, 'parent tilt must change the grid'
    camera = '<camera id="3" startTime="0" endTime="1000"><transform><location value="140,160,-277.13"/></transform></camera>'
    seen = render('camera', camera + parent + shape(fx('scatter3d', spacing=15), parent=' parent="2"', location='0,0,0'))[0]
    assert different(child, seen) > 1000, 'camera must affect the result'
    stacked = render('stacked', shape(fx('invert') + fx('fracture3d', progress=0.4) + fx('invert')))[0]
    assert different(stacked, broken) > 500, 'effects around fracture must run'
    text = ('<text id="1" label="t" fillType="color" startTime="0" endTime="1000" size="70" wrapWidth="300" align="center">'
            '<transform><location value="160,160,0"/></transform><content>ABC</content><fillColor value="#ffffffff"/>'
            + fx('fracture3d', progress=0.3) + '</text>')
    words = render('text', text)[0]
    assert different(words, render('empty', '')[0]) > 100, 'text must produce visible shards'
    mixing()
    print('pieces3d_check: passed')


if __name__ == '__main__':
    main()
