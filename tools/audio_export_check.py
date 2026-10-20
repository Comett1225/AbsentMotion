#!/usr/bin/env python3
import json
import subprocess
import sys
import time
import wave
import numpy as np
import engine_check as engine

FORMATS = {'wav16': ('wav', 'pcm_s16le'), 'wav24': ('wav', 'pcm_s24le'), 'flac': ('flac', 'flac'), 'm4a': ('m4a', 'aac'),
           'ogg': ('ogg', 'opus'), 'mp3': ('mp3', 'mp3')}
CLICKS = (0.5, 1.5)


def export(work, name, extras):
    engine.adb('logcat', '-c')
    engine.adb('shell', 'am', 'start', '-n', engine.PKG + '/.MainActivity', '--es', 'xml', engine.DEVICE_DIR + '/audiocheck.xml',
               '--es', 'export', 'audio', *extras)
    deadline = time.monotonic() + 120
    while True:
        log = engine.adb('logcat', '-d', '-s', 'AMExport')
        if 'AMExport: done' in log:
            break
        if 'AMExport: failed' in log or time.monotonic() >= deadline:
            return None, log
        time.sleep(0.3)
    path = log.split('AMExport: done ')[1].split()[0]
    local = work / (name + '.' + path.rsplit('.', 1)[1])
    engine.adb('pull', path, local)
    engine.adb('shell', 'rm', path)
    return local, log


def check(path, codec, rate, channels):
    problems = []
    info = json.loads(subprocess.run(['ffprobe', '-v', 'quiet', '-show_format', '-show_streams', '-of', 'json', str(path)],
                                     capture_output=True, text=True).stdout or '{}')
    streams = info.get('streams', [])
    if len(streams) != 1 or streams[0].get('codec_name') != codec:
        return ['streams ' + ','.join(s.get('codec_name', '?') for s in streams)]
    stream = streams[0]
    if int(stream.get('sample_rate', 0)) != rate or int(stream.get('channels', 0)) != channels:
        problems.append('%s Hz %s ch' % (stream.get('sample_rate'), stream.get('channels')))
    duration = float(info.get('format', {}).get('duration', 0))
    if abs(duration - 2.0) > 0.03:
        problems.append('duration %.4f' % duration)
    errors = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'null', '-'], capture_output=True, text=True).stderr.strip()
    if errors:
        problems.append('decode: ' + errors.splitlines()[0][:80])
    out = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-af', 'aresample=async=1:first_pts=0', '-ac', '1', '-ar', '48000',
                          '-f', 's16le', '-'], capture_output=True).stdout
    level = np.abs(np.frombuffer(out, dtype='<i2').astype(float))
    onsets, i = [], 0
    while i < len(level):
        if level[i] > 4000:
            onsets.append(i / 48000.0)
            i += 4800
        else:
            i += 1
    if len(onsets) != len(CLICKS) or max(abs(a - b) for a, b in zip(onsets, CLICKS)) > 0.001:
        problems.append('clicks at ' + ', '.join('%.4f' % o for o in onsets))
    return problems


def main():
    work = engine.ROOT / 'build/audio-export-check'
    work.mkdir(parents=True, exist_ok=True)
    rate = 44100
    pcm = np.zeros(rate * 2, dtype='<i2')
    for t in CLICKS:
        pcm[int(t * rate):int(t * rate) + 88] = 20000
    with wave.open(str(work / 'click.wav'), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    click = engine.DEVICE_DIR + '/audioclick.wav'
    engine.adb('push', work / 'click.wav', click)
    body = engine.rect(1, 128, 128, 128, 128, 0xffff0000) + '<audio id="2" startTime="0" endTime="1999" src="file://' + click + '"/>'
    (work / 'audiocheck.xml').write_text(engine.scene(body, 256, 256).replace('totalTime="1000"', 'totalTime="1999"', 1))
    engine.adb('push', work / 'audiocheck.xml', engine.DEVICE_DIR + '/audiocheck.xml')
    only = sys.argv[1:]
    failed = 0
    for fmt, (ext, codec) in FORMATS.items():
        for rate in ([48000] if fmt == 'ogg' else [44100, 48000]):
            for channels in (2, 1):
                for kbps in ([128] if fmt in ('wav16', 'wav24', 'flac') else [64, 320] if channels == 2 else [96]):
                    name = '%s-%d-%d-%d' % (fmt, rate, channels, kbps)
                    if only and not any(o in name for o in only):
                        continue
                    path, log = export(work, name, ['--es', 'aformat', fmt, '--ei', 'arate', str(rate), '--ei', 'channels', str(channels),
                                                    '--ei', 'abitrate', str(kbps)])
                    reason = [line.split('AMExport: ', 1)[1] for line in log.splitlines() if 'Exception' in line][:1]
                    problems = check(path, codec, rate, channels) if path else ['export failed: ' + ''.join(reason)[:160]]
                    failed += bool(problems)
                    print(('ok   ' if not problems else 'FAIL ') + name + ('' if not problems else ': ' + '; '.join(problems)), flush=True)
    engine.adb('shell', 'rm', click, engine.DEVICE_DIR + '/audiocheck.xml')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
