#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import numpy as np
from PIL import Image
import engine_check as engine

COLORS = [(0, 0, 0), (64, 64, 64), (128, 128, 128), (192, 192, 192), (255, 255, 255), (255, 0, 0), (0, 255, 0), (0, 0, 255)]


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else engine.ROOT / 'build/video-color-check'
    work.mkdir(parents=True, exist_ok=True)
    image = Image.new('RGB', (256, 256))
    for i, color in enumerate(COLORS):
        image.paste(color, (i * 32, 0, (i + 1) * 32, 256))
    image.save(work / 'bars.png')
    failed = 0
    for standard, matrix in [('smpte170m', 'bt601'), ('bt709', 'bt709')]:
        for color_range, out_range in [('tv', 'limited'), ('pc', 'full')]:
            name = f'{matrix}-{out_range}'
            clip = work / (name + '.mp4')
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-loop', '1', '-i', str(work / 'bars.png'), '-t', '1', '-r', '10', '-vf',
                            f'scale=out_color_matrix={matrix}:out_range={out_range}', '-c:v', 'libx264', '-crf', '1', '-g', '1', '-pix_fmt', 'yuv420p',
                            '-x264-params', f'colorprim={standard}:transfer={standard}:colormatrix={standard}:fullrange={"on" if color_range == "pc" else "off"}', str(clip)], check=True)
            remote = engine.DEVICE_DIR + '/' + name + '.mp4'
            engine.adb('push', clip, remote)
            xml = engine.scene('<shape id="1" startTime="0" endTime="999" s=".rect" fillType="media" fillVideo="file://' + remote + '" outTime="1000" mediaFillMode="stretch">' + engine.transform('128,128,0') + '<property name="size" type="vec2" value="128,128"/></shape>', 256, 256)
            got = engine.still(name, xml, work)
            (work / (name + '.log')).write_text(engine.adb('logcat', '-d', '-s', 'AMVideo', 'AMEngine'))
            probes = [tuple(int(c) for c in got[128, i * 32 + 16]) for i in range(len(COLORS))]
            error = int(np.abs(np.asarray(probes) - COLORS).max())
            print(('ok   ' if error <= 6 else 'FAIL ') + name + f' max error={error}: {probes}', flush=True)
            failed += error > 6
            engine.adb('shell', 'rm', remote)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
