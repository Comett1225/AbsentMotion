import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/render_bench'))
from run import effect_xml, fx

WORK = ROOT / 'build/pieces3d_check'
BIN = ROOT / 'build/render_bench/current/render_bench'


def scene(body):
    return '<scene title="pieces" width="320" height="320" fps="30" totalTime="1000" bgcolor="#ff101418">' + body + '</scene>'


def shape(effect='', tilt='', parent='', location='160,160,0'):
    return ('<shape id="1" label="s" startTime="0" endTime="1000" fillType="media" fillImage="synthetic" mediaFillMode="fill" s=".rect"'
            + parent + '><transform><location value="' + location + '"/>' + tilt + '</transform>'
            + '<property name="size" type="vec2" value="90,70"/>' + effect + '</shape>')


def render(name, xml, frames=(0,)):
    path = WORK / name
    path.mkdir(parents=True, exist_ok=True)
    (path / 'scene.xml').write_text(scene(xml))
    subprocess.run([str(BIN), str(WORK / 'effects.txt'), str(path / 'scene.xml'), '1', '0', str(path), *map(str, frames)],
                   check=True, stdout=subprocess.DEVNULL)
    return [(path / f'frame{i}.rgba').read_bytes() for i in range(len(frames))]


def different(a, b):
    return sum(x != y for x, y in zip(a, b))


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
    print('pieces3d_check: passed')


if __name__ == '__main__':
    main()
