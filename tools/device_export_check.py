#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import time
import numpy as np
from PIL import Image
import engine_check as engine


def export(xml, work, name):
    local = work / (name + '.xml')
    local.write_text(xml)
    remote = engine.DEVICE_DIR + '/' + name + '.xml'
    engine.adb('push', local, remote)
    engine.adb('logcat', '-c')
    engine.adb('shell', 'am', 'start', '-n', engine.PKG + '/.MainActivity', '--es', 'xml', remote, '--es', 'export', 'video', '--ei', 'fps', '30', '--ei', 'short', '256')
    deadline = time.monotonic() + 120
    while True:
        log = engine.adb('logcat', '-d', '-s', 'AMExport', 'AMVideo')
        if 'AMExport: done' in log:
            break
        if 'AMExport: failed' in log or time.monotonic() >= deadline:
            sys.exit(name + ' failed: ' + log[-2000:])
        time.sleep(0.3)
    engine.adb('pull', engine.DEVICE_DIR + '/export.mp4', work / (name + '.mp4'))
    for path in [remote, engine.DEVICE_DIR + '/export.mp4']:
        engine.adb('shell', 'rm', path)
    return work / (name + '.mp4')


def main():
    work = engine.ROOT / 'build/device-export-check'
    work.mkdir(parents=True, exist_ok=True)
    audio = work / 'loop.wav'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=0.4', str(audio)], check=True)
    remote = engine.DEVICE_DIR + '/loopcheck.wav'
    engine.adb('push', audio, remote)
    layer = '<audio id="2" startTime="0" endTime="2999" src="file://' + remote + '" loop="true"/>'
    bg = engine.rect(1, 128, 128, 128, 128, 0xffff0000)
    for nested in ([] if '--tiny-only' in sys.argv else [False, True]):
        body = bg + (('<embedScene id="3" startTime="0" endTime="2999">' + engine.transform('128,128,0') + '<scene width="256" height="256" fps="30" totalTime="2999" retime="off">' + layer + '</scene></embedScene>') if nested else layer)
        xml = engine.scene(body, 256, 256).replace('totalTime="1000"', 'totalTime="2999"', 1)
        name = 'nested-audio' if nested else 'loop-no-out'
        result = export(xml, work, name)
        samples = np.frombuffer(subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(result), '-f', 's16le', '-ac', '1', '-ar', '44100', '-']), dtype='<i2').astype(float)
        for start in [0.05, 0.45, 0.85, 1.65, 2.55]:
            chunk = samples[int(start * 44100):int((start + 0.1) * 44100)]
            if len(chunk) < 4000 or np.sqrt(np.mean(chunk * chunk)) < 300:
                sys.exit(name + ' is silent at ' + str(start))
        frame = np.frombuffer(subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(result), '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']), dtype=np.uint8).reshape(256, 256, 3)
        if not (frame[128, 128, 0] > 240 and frame[128, 128, 1:].max() < 10):
            sys.exit(name + ' has incorrect video pixels')
        print('ok MP4 ' + name + ': visible video and audible audio through 2.65 seconds', flush=True)
    for duration in ([] if '--tiny-only' in sys.argv else [1, 32, 100]):
        audio_layer = '<audio id="2" startTime="0" endTime="' + str(duration - 1) + '" src="file://' + remote + '" loop="true"/>'
        xml = engine.scene(bg + audio_layer, 256, 256).replace('totalTime="1000"', 'totalTime="' + str(duration - 1) + '"', 1)
        result = export(xml, work, 'short-' + str(duration))
        samples = np.frombuffer(subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(result), '-f', 's16le', '-ac', '1', '-ar', '44100', '-']), dtype='<i2').astype(float)
        if not len(samples) or np.abs(samples).max() < 50:
            sys.exit(str(duration) + ' ms MP4 lost its audio')
        frames = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-count_frames', '-show_entries', 'stream=nb_read_frames', '-of', 'csv=p=0', str(result)], text=True).strip()
        if not frames or int(frames) < 1:
            sys.exit(str(duration) + ' ms MP4 lost its video')
        print('ok MP4 ' + str(duration) + ' ms: decoded audio and video', flush=True)
    engine.adb('shell', 'rm', remote)
    image = Image.new('RGB', (64, 64), engine.RED)
    image.paste(engine.BLUE, (32, 0, 64, 64))
    image.save(work / 'tiny.png')
    clip = work / 'tiny.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-loop', '1', '-i', str(work / 'tiny.png'), '-t', '1', '-r', '5', '-c:v', 'libx264', '-g', '1', '-pix_fmt', 'yuv420p', str(clip)], check=True)
    remote = engine.DEVICE_DIR + '/tinycheck.mp4'
    engine.adb('push', clip, remote)
    body = '<shape id="1" startTime="0" endTime="999" s=".rect" fillType="media" mediaFillMode="stretch" fillVideo="file://' + remote + '" outTime="1000">' + engine.transform('128,128,0') + '<property name="size" type="vec2" value="128,128"/></shape>'
    pixels = engine.still('tinycheck', engine.scene(body, 256, 256), work)
    if any(np.abs(pixels[128, x] - color).max() > 6 for x, color in [(64, engine.RED), (192, engine.BLUE)]):
        sys.exit('64-pixel-wide video has incorrect pixels')
    engine.adb('shell', 'rm', remote)
    print('ok 64-pixel-wide video decodes correctly on both halves', flush=True)
    engine.adb('logcat', '-c')
    engine.adb('shell', 'am', 'start', '-n', engine.PKG + '/.MainActivity', '--ez', 'devicecheck', 'true')
    deadline = time.monotonic() + 45
    while True:
        log = engine.adb('logcat', '-d', '-s', 'AMDevice')
        if 'AMDevice: done' in log:
            break
        if 'AMDevice: failed' in log or time.monotonic() >= deadline:
            sys.exit('runtime device checks failed: ' + log[-2000:])
        time.sleep(0.3)
    if '--play' in sys.argv and any('input EXIF ' + str(tag) not in log for tag in [2, 5]):
        sys.exit('the play-flavour background-removal checks did not run')
    print(log.strip(), flush=True)


if __name__ == '__main__':
    main()
