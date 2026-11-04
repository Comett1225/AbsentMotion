import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from pieces3d_check import eye, seen

WORK = ROOT / 'build/crossing_check'
BIN = ROOT / 'build/render_bench/current'
DEPTH = ' depthOrder="true"'
PIECE = DEPTH + ' depthSort="piece"'
RED, BLUE = '#ffff2020', '#ff2040ff'
SIZE = 320


def card(i, color, turn):
    return ('<shape id="%d" label="c" startTime="0" endTime="1000" fillType="color" s=".rect" threeD="true"><transform><location value="160,160,0"/>'
            '<tilt value="0,%d"/></transform><fillColor value="%s"/><property name="size" type="vec2" value="70,55"/></shape>') % (i, turn, color)


def render(name, body, extra='', background='#ff101418'):
    path = WORK / name
    path.mkdir(parents=True, exist_ok=True)
    (path / 'scene.xml').write_text('<scene title="x" width="%d" height="%d" fps="30" totalTime="1000" bgcolor="%s"%s>%s</scene>'
                                    % (SIZE, SIZE, background, extra, body))
    subprocess.run([str(BIN / 'render_bench'), str(WORK / 'effects.txt'), str(path / 'scene.xml'), '1', '0', str(path), '0'],
                   check=True, stdout=subprocess.DEVNULL)
    return (path / 'frame0.rgba').read_bytes()


def pixels(frame, test):
    return {i // 4 for i in range(0, len(frame), 4) if test(frame[i], frame[i + 1], frame[i + 2])}


def reds(frame):
    return pixels(frame, lambda r, g, b: r > 120 and r > b + 60)


def blues(frame):
    return pixels(frame, lambda r, g, b: b > 120 and b > r + 60)


def crossing(name, tilt, extra):
    cam = eye(tilt)
    red, blue = card(1, RED, 40), card(2, BLUE, -40)
    overlap = reds(render(name + '_red', cam + red, extra)) & blues(render(name + '_blue', cam + blue, extra))
    both = render(name, cam + red + blue, extra)
    top, bottom = seen(tilt, point=(160, 110, 0)), seen(tilt, point=(160, 210, 0))
    dx, dy = bottom[0] - top[0], bottom[1] - top[1]
    length = (dx * dx + dy * dy) ** 0.5
    distance = {i: ((i % SIZE - top[0]) * dy - (i // SIZE - top[1]) * dx) / length for i in overlap}
    halves = [{i for i in overlap if distance[i] < -3}, {i for i in overlap if distance[i] > 3}]
    assert all(len(h) > 300 for h in halves), '%s: the cards must overlap on both sides of the crossing line' % name
    found, made = reds(both), blues(both)
    winners = []
    for half in halves:
        r, b = len(half & found) / len(half), len(half & made) / len(half)
        assert max(r, b) > 0.97, '%s: one card must be in front on each side of the crossing line (%.3f red, %.3f blue)' % (name, r, b)
        winners.append(r > b)
    assert winners[0] != winners[1], '%s: each card must show on its own side of the crossing line' % name


def flat():
    cam = eye()
    clear = '#00000000'
    red = render('flat_red', cam + card(1, RED, 40), background=clear)
    blue = render('flat_blue', cam + card(2, BLUE, -40), background=clear)
    both = render('flat_both', cam + card(1, RED, 40) + card(2, BLUE, -40), background=clear)
    covered = opaque = 0
    for i in range(0, len(both), 4):
        alpha = blue[i + 3]
        if alpha == 255:
            assert both[i:i + 4] == blue[i:i + 4], 'without depth order the top card must cover the other, pixel %d' % (i // 4)
            covered += red[i + 3] == 255
        elif alpha == 0:
            assert both[i:i + 4] == red[i:i + 4], 'without depth order the card below must stay as it was, pixel %d' % (i // 4)
            opaque += red[i + 3] == 255
    assert covered > 2000 and opaque > 1000, 'the flat scene must overlap'


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    subprocess.run(['sh', str(ROOT / 'tools/render_bench/build.sh')], check=True, stdout=subprocess.DEVNULL)
    (WORK / 'effects.txt').write_text('\n')
    for mode, extra in (('layer', DEPTH), ('piece', PIECE)):
        for k, tilt in enumerate(((0, 0), (15, 25), (-20, -20), (30, 10))):
            crossing('%s%d' % (mode, k), tilt, extra)
    flat()
    subprocess.run([str(BIN / 'steady_test')], check=True, stdout=subprocess.DEVNULL)
    print('crossing_check: passed')


if __name__ == '__main__':
    main()
