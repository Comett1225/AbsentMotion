#!/usr/bin/env python3
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

def main():
    started = finished = None
    failed = False
    for line in sys.stdin:
        stamp = re.match(r'\s*(\d+\.\d+)', line)
        if stamp is None:
            continue
        if re.search(r'AMEngine: attached.*color space [1-9]|AMExport: rendering', line):
            started = float(stamp[1])
        if re.search(r'AMExport:\s+failed', line):
            failed = True
        if re.search(r'AMExport:\s+done', line):
            finished = float(stamp[1])
    if failed or started is None or finished is None:
        sys.exit('export failed or timed out')
    elapsed = finished - started
    if elapsed <= 0:
        sys.exit('export timing has no positive elapsed time')
    scene = ET.parse(sys.argv[1]).getroot()
    fps = int(scene.get('fps', '30'))
    frames = round((int(scene.get('totalTime', '0')) + 1) * fps / 1000)
    print(Path(sys.argv[1]).name + ': %d frames in %.1f s = %.0f frames per second (%.1fx real time)' %
          (frames, elapsed, frames / elapsed, frames / elapsed / fps))

if __name__ == '__main__':
    main()
