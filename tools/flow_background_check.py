#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import time
import engine_check as engine


def main():
    work = engine.ROOT / 'build/flow-background-check'
    work.mkdir(parents=True, exist_ok=True)
    clip = work / 'flow.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=256x256:rate=5:duration=4', '-c:v', 'libx264', '-g', '1', '-pix_fmt', 'yuv420p', str(clip)], check=True)
    remote = engine.DEVICE_DIR + '/flowcheck.mp4'
    engine.adb('push', clip, remote)
    body = '<shape id="1" startTime="0" endTime="3999" fillType="media" mediaFillMode="stretch" fillVideo="file://' + remote + '" outTime="4000" s=".rect">' + engine.transform('128,128,0') + '<property name="size" type="vec2" value="128,128"/></shape>'
    xml = engine.scene(body, 256, 256).replace('totalTime="1000"', 'totalTime="3999"', 1)
    local = work / 'flowcheck.xml'
    local.write_text(xml)
    engine.adb('push', local, engine.DEVICE_DIR + '/flowcheck.xml')
    engine.adb('logcat', '-c')
    engine.adb('shell', 'am', 'start', '-n', engine.PKG + '/app.absentmotion.MainActivity', '--es', 'xml', engine.DEVICE_DIR + '/flowcheck.xml', '--es', 'export', 'gif', '--es', 'interp', 'flow', '--ei', 'fps', '15', '--ei', 'short', '256')
    deadline = time.monotonic() + 240
    backgrounded = False
    try:
        while True:
            log = engine.adb('logcat', '-d', '-s', 'AMExport', 'AMInterp', 'AMVideo', 'AMEngine')
            if not backgrounded and 'flow a=' in log:
                engine.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                engine.adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
                backgrounded = True
            if 'AMExport: done' in log or 'AMExport: failed' in log:
                break
            if time.monotonic() >= deadline:
                sys.exit('flow export timed out')
            time.sleep(0.4)
    finally:
        engine.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        engine.adb('shell', 'wm', 'dismiss-keyguard')
    (work / 'export.log').write_text(log)
    if not backgrounded:
        sys.exit('no optical flow pair was rendered')
    if 'mode=blend' in log or 'AMExport: done' not in log:
        sys.exit('flow export failed or used a blend: ' + log[-2500:])
    engine.adb('pull', engine.DEVICE_DIR + '/export.gif', work / 'export.gif')
    frames = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-count_frames', '-show_entries', 'stream=nb_read_frames', '-of', 'csv=p=0', str(work / 'export.gif')], text=True).strip()
    if frames != '60':
        sys.exit('flow export has ' + frames + ' frames; expected 60')
    for name in ['flowcheck.xml', 'flowcheck.mp4', 'export.gif']:
        engine.adb('shell', 'rm', engine.DEVICE_DIR + '/' + name)
    print('ok optical flow exported all 60 GIF frames while backgrounded and screen locked')


if __name__ == '__main__':
    main()
