#!/usr/bin/env python3
import os, signal, subprocess, sys, time
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERIAL = os.environ.get("ANDROID_SERIAL") or "emulator-5554"
ADB = [os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "-s", SERIAL]
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
OUT = os.path.join(ROOT, "build/sound-check")
RATE = 48000

def adb(*a):
    return subprocess.run([*ADB, *a], capture_output=True).stdout.decode("utf-8", "replace")

def samples(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vn", "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"], capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32)

def analyse(path, spacing=1.0):
    x = samples(path)
    if len(x) < RATE // 2:
        print(f"FAIL  {path}: no sound track or under half a second of sound")
        return False
    hop = RATE // 1000
    rms = np.sqrt(np.convolve(x ** 2, np.ones(RATE // 200) / (RATE // 200), "same"))[::hop]
    loud = rms.max()
    if loud < 1e-4:
        print(f"FAIL  {path}: silent (peak level {loud:.6f})")
        return False
    above = rms > loud * 0.5
    onsets = [i * hop / RATE for i in range(1, len(above)) if above[i] and not above[i - 1]]
    onsets = [t for k, t in enumerate(onsets) if k == 0 or t - onsets[k - 1] > 0.2]
    gaps = np.diff(onsets)
    a, b = (int(onsets[0] * RATE), int(onsets[-1] * RATE)) if len(onsets) > 1 else (0, len(x))
    body = x[a:b] if b - a > RATE // 2 else x
    spectrum = np.abs(np.fft.rfft(body * np.hanning(len(body))))
    freq = np.argmax(spectrum) * RATE / len(body)
    inner = rms[a // hop:b // hop]
    usual = np.median(inner) if len(inner) else 0
    holes = int(np.sum(inner < usual * 0.25)) if usual > 0 else 0
    worst = float(np.abs(gaps[1:] - spacing).max() * 1000) if len(gaps) > 1 else 0.0
    drift = float((onsets[-1] - onsets[0] - spacing * (len(onsets) - 1)) * 1000) if len(onsets) > 1 else 0.0
    ok = len(onsets) > 2 and worst < 4 and holes == 0
    print(f"{'PASS' if ok else 'FAIL'}  {os.path.basename(path)}: {len(x) / RATE:.2f} s, tone {freq:.1f} Hz, {len(onsets)} beeps, first at {onsets[0] if onsets else -1:.3f} s, "
          f"spacing off by at most {worst:.1f} ms, drift {drift:+.1f} ms over the run, {holes} dropouts (ms), peak {loud:.3f}")
    return ok

def open_scene(xml):
    name = os.path.basename(xml)
    adb("push", xml, f"{DEVICE_DIR}/{name}")
    adb("shell", "am", "force-stop", PKG)
    return f"{DEVICE_DIR}/{name}"

def export(xml, spacing):
    remote = open_scene(xml)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", remote, "--es", "export", "video", "--ei", "short", "360")
    for _ in range(120):
        time.sleep(1)
        log = adb("logcat", "-d", "-s", "AMExport")
        if "failed" in log:
            sys.exit("export failed:\n" + log[-800:])
        if "done" in log:
            break
    else:
        sys.exit("export did not finish in two minutes")
    local = os.path.join(OUT, "sound-" + os.path.basename(xml)[:-4] + ".mp4")
    adb("pull", f"{DEVICE_DIR}/export.mp4", local)
    return analyse(local, spacing)

def play(xml, seconds, spacing):
    remote = open_scene(xml)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", remote)
    time.sleep(5)
    local = os.path.join(OUT, "sound-" + os.path.basename(xml)[:-4] + "-play.flac")
    if os.path.exists(local):
        os.remove(local)
    rec = subprocess.Popen(["scrcpy", f"--serial={SERIAL}", "--no-video", "--no-window", "--no-playback", "--audio-codec=flac", f"--record={local}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(4)
    adb("shell", "input", "tap", "540", "1445")
    time.sleep(seconds + 1.5)
    rec.send_signal(signal.SIGINT)
    rec.wait(timeout=15)
    return analyse(local, spacing)

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    mode, args = sys.argv[1], sys.argv[2:]
    if mode == "file":
        ok = analyse(args[0], float(args[1]) if len(args) > 1 else 1.0)
    elif mode == "export":
        ok = export(args[0], float(args[1]) if len(args) > 1 else 1.0)
    else:
        ok = play(args[0], float(args[1]), float(args[2]) if len(args) > 2 else 1.0)
    sys.exit(0 if ok else 1)
