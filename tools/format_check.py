#!/usr/bin/env python3
import json
import subprocess
import sys
import time
import numpy as np
import engine_check as engine

VIDEO = {'mp4': ['h264', 'hevc', 'av1', 'apv'], 'mov': ['h264', 'hevc'], 'webm': ['vp9', 'av1'], 'mkv': ['h264', 'hevc', 'av1', 'vp9']}
AUDIO = {'mp4': ['aac'], 'mov': ['aac'], 'webm': ['opus'], 'mkv': ['aac', 'opus']}
FFCODEC = {'h264': 'h264', 'hevc': 'hevc', 'av1': 'av1', 'vp9': 'vp9', 'apv': 'apv', 'aac': 'aac', 'opus': 'opus'}
FPS = 30
COLORS = [(0, 0, 0), (64, 64, 64), (128, 128, 128), (192, 192, 192), (255, 255, 255), (255, 0, 0), (0, 255, 0), (0, 0, 255)]
SECONDS = 2


def export(work, name, extras):
    remote = engine.DEVICE_DIR + '/formatcheck.xml'
    engine.adb('logcat', '-c')
    engine.adb('shell', 'am', 'start', '-n', engine.PKG + '/app.absentmotion.MainActivity', '--es', 'xml', remote, '--es', 'export', 'video',
               '--ei', 'fps', str(FPS), '--ei', 'short', '256', *extras)
    deadline = time.monotonic() + 180
    while True:
        log = engine.adb('logcat', '-d', '-s', 'AMExport')
        if 'AMExport: done' in log:
            break
        if 'AMExport: failed' in log or time.monotonic() >= deadline:
            return None, log[-1500:]
        time.sleep(0.3)
    path = log.split('AMExport: done ')[1].split()[0]
    local = work / (name + '.' + path.rsplit('.', 1)[1])
    engine.adb('pull', path, local)
    engine.adb('shell', 'rm', path)
    return local, log


def probe(path):
    out = subprocess.run(['ffprobe', '-v', 'quiet', '-show_format', '-show_streams', '-of', 'json', str(path)], capture_output=True, text=True)
    return json.loads(out.stdout or '{}')


def check(path, container, vcodec, acodec, rate, channels):
    problems = []
    info = probe(path)
    streams = {s['codec_type']: s for s in info.get('streams', [])}
    fmt = info.get('format', {})
    names = fmt.get('format_name', '')
    if container in ('mp4', 'mov') and 'mp4' not in names:
        problems.append('format ' + names)
    if container in ('webm', 'mkv') and 'matroska' not in names:
        problems.append('format ' + names)
    brand = fmt.get('tags', {}).get('major_brand', '')
    if container == 'mov' and brand.strip() != 'qt':
        problems.append('brand ' + repr(brand))
    video = streams.get('video')
    if not video or video.get('codec_name') != FFCODEC[vcodec]:
        problems.append('video ' + str(video and video.get('codec_name')))
    audio = streams.get('audio')
    if not audio or audio.get('codec_name') != FFCODEC[acodec]:
        problems.append('audio ' + str(audio and audio.get('codec_name')))
    elif int(audio.get('sample_rate', 0)) != rate or int(audio.get('channels', 0)) != channels:
        problems.append('audio %s Hz %s ch' % (audio.get('sample_rate'), audio.get('channels')))
    pts = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'frame=pts_time', '-of', 'csv=p=0', str(path)],
                         capture_output=True, text=True).stdout.split()
    times = [float(t) for t in pts if t not in ('', 'N/A')]
    if len(times) != FPS * SECONDS:
        problems.append('%d frames' % len(times))
    elif max(abs(t - k / FPS) for k, t in enumerate(times)) > 0.0011:
        problems.append('frame times off by %.4f s' % max(abs(t - k / FPS) for k, t in enumerate(times)))
    duration = float(fmt.get('duration', 0))
    if abs(duration - SECONDS) > 1.0 / FPS + 0.05:
        problems.append('duration %.3f' % duration)
    errors = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'null', '-'], capture_output=True, text=True).stderr.strip()
    if errors:
        problems.append('decode: ' + errors.splitlines()[0][:80])
    samples = np.frombuffer(subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's16le', '-ac', '1', '-ar', '48000', '-'],
                                           capture_output=True).stdout, dtype='<i2').astype(float)
    for start in (0.1, 0.9, 1.7):
        chunk = samples[int(start * 48000):int((start + 0.1) * 48000)]
        if len(chunk) < 2000 or np.sqrt(np.mean(chunk * chunk)) < 300:
            problems.append('silent at %.1f s' % start)
            break
    frame = np.frombuffer(subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                                         capture_output=True).stdout, dtype=np.uint8)
    if frame.size != 256 * 256 * 3:
        problems.append('no first frame')
    else:
        image = frame.reshape(256, 256, 3).astype(int)
        worst = max(int(np.abs(image[128, 16 + 32 * i] - np.array(color)).max()) for i, color in enumerate(COLORS))
        if worst > 8:
            problems.append('colours off by %d' % worst)
    return problems


def main():
    work = engine.ROOT / 'build/format-check'
    work.mkdir(parents=True, exist_ok=True)
    wav = work / 'tone.wav'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=0.5', str(wav)], check=True)
    tone = engine.DEVICE_DIR + '/formattone.wav'
    engine.adb('push', wav, tone)
    bars = ''.join(engine.rect(i + 1, 16 + 32 * i, 128, 16, 128, 0xff000000 | (r << 16) | (g << 8) | b) for i, (r, g, b) in enumerate(COLORS))
    body = bars + '<audio id="20" startTime="0" endTime="1999" src="file://' + tone + '" loop="true"/>'
    xml = engine.scene(body, 256, 256).replace('totalTime="1000"', 'totalTime="1999"', 1)
    (work / 'formatcheck.xml').write_text(xml)
    engine.adb('push', work / 'formatcheck.xml', engine.DEVICE_DIR + '/formatcheck.xml')
    only = sys.argv[1:]
    cases = []
    for container, vcodecs in VIDEO.items():
        for vcodec in vcodecs:
            for acodec in AUDIO[container]:
                cases.append((container, vcodec, acodec, 48000 if acodec == 'opus' else 44100, 2, 128))
    cases += [('mp4', 'h264', 'aac', 48000, 1, 64), ('mp4', 'h264', 'aac', 48000, 2, 320), ('mkv', 'h264', 'aac', 48000, 1, 96),
              ('mkv', 'vp9', 'opus', 48000, 1, 64), ('webm', 'vp9', 'opus', 48000, 2, 320)]
    failed = 0
    for container, vcodec, acodec, rate, channels, kbps in cases:
        name = '%s-%s-%s-%d-%d-%d' % (container, vcodec, acodec, rate, channels, kbps)
        if only and not any(o in name for o in only):
            continue
        extras = ['--es', 'container', container, '--es', 'codec', vcodec, '--es', 'acodec', acodec, '--ei', 'arate', str(rate),
                  '--ei', 'channels', str(channels), '--ei', 'abitrate', str(kbps)]
        path, log = export(work, name, extras)
        reason = [line.split('AMExport: ', 1)[1] for line in log.splitlines() if 'Exception' in line][:1]
        problems = check(path, container, vcodec, acodec, rate, channels) if path else ['export failed: ' + ''.join(reason)[:160]]
        verified = [line.split('AMExport: verify ', 1)[1] for line in log.splitlines() if 'AMExport: verify ' in line]
        if path and not (verified and verified[0].startswith('verified')):
            problems.append('device verification: ' + (verified[0] if verified else 'missing'))
        failed += bool(problems)
        print(('ok   ' if not problems else 'FAIL ') + name + ('' if not problems else ': ' + '; '.join(problems)), flush=True)
    engine.adb('shell', 'rm', tone, engine.DEVICE_DIR + '/formatcheck.xml')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
