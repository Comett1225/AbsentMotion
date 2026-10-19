#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import time
import numpy as np
import engine_check as engine


def main():
    work = engine.ROOT / 'build/aac-check'
    work.mkdir(parents=True, exist_ok=True)
    for us in [1000, 32000, 100000, 1000000]:
        engine.adb('logcat', '-c')
        engine.adb('shell', 'am', 'start', '-n', engine.PKG + '/.MainActivity', '--el', 'aaccheck', us)
        deadline = time.monotonic() + 30
        while True:
            log = engine.adb('logcat', '-d', '-s', 'AMAac')
            if 'done' in log:
                break
            if 'failed' in log or time.monotonic() >= deadline:
                sys.exit(f'AAC check failed for {us} us: {log}')
            time.sleep(0.2)
        path = work / f'{us}.m4a'
        engine.adb('pull', engine.DEVICE_DIR + '/aaccheck.m4a', path)
        result = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's16le', '-ac', '1', '-ar', '44100', '-'], capture_output=True, check=True)
        samples = np.frombuffer(result.stdout, dtype='<i2').astype(int)
        nonzero = np.flatnonzero(np.abs(samples) > 200)
        if not len(nonzero) or nonzero[0] > 441:
            sys.exit(f'AAC onset was lost or delayed for {us} us: {nonzero[:1]}')
        end = us * 44100 // 1000000
        tail = samples[max(0, end - 220):end]
        if not len(tail) or np.abs(tail).max() < 200:
            sys.exit(f'AAC tail was truncated for {us} us')
        print(f'ok AAC {us} us: {len(samples)} decoded samples, onset {nonzero[0] / 44.1:.2f} ms, final click retained', flush=True)
    engine.adb('shell', 'rm', engine.DEVICE_DIR + '/aaccheck.m4a')


if __name__ == '__main__':
    main()
