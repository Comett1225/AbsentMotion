#!/usr/bin/env python3
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from sdk import adb_command

ROOT = Path(__file__).resolve().parents[1]
ADB = adb_command()
PKG = "app.absentmotion"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
WORDS = ("This is a long sentence so that the voice takes a while to make and the preview button can be pressed "
         "while it is still being made by the speech engine of this phone")


def adb(*args):
    return subprocess.run(ADB + [str(a) for a in args], capture_output=True, text=True).stdout


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/voicecheck-ui.xml")
    ui = adb("shell", "cat", "/sdcard/voicecheck-ui.xml")
    adb("shell", "rm", "/sdcard/voicecheck-ui.xml")
    return ui


def centre(ui, label):
    found = re.search(rf'(?:text|content-desc)="{re.escape(label)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)
    if not found:
        return None
    x0, y0, x1, y1 = map(int, found.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2


def tap(label, wait=1.5):
    ui = screen()
    if label == "Audio" and centre(ui, "Don’t allow"):
        adb("shell", "input", "tap", *centre(ui, "Don’t allow"))
        ui = screen()
    at = centre(ui, label)
    if not at:
        sys.exit(f"no '{label}' on screen")
    adb("shell", "input", "tap", *at)
    time.sleep(wait)
    return at


def main():
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/voice-check"
    work.mkdir(parents=True, exist_ok=True)
    (work / "voice.xml").write_text('<scene title="voicecheck" width="360" height="360" fps="30" totalTime="999" bgcolor="#ff000000"></scene>')
    adb("push", work / "voice.xml", f"{DEVICE_DIR}/voice.xml")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "xml", f"{DEVICE_DIR}/voice.xml")
    time.sleep(4)
    tap("Add layer")
    tap("Audio")
    tap("Audio")
    tap("Text to Speech", 3)
    if "No offline voices" in screen():
        sys.exit("the emulator has no offline text to speech voice")
    tap("Type what the voice says", 1)
    adb("shell", "input", "text", WORDS.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    ui = screen()
    add, preview = centre(ui, "Add"), centre(ui, "Preview")
    adb("shell", f"input tap {add[0]} {add[1]}; input tap {preview[0]} {preview[1]}")
    deadline = time.time() + 60
    while "Making the voice" in (ui := screen()) and time.time() < deadline:
        time.sleep(1)
    adb("shell", "rm", f"{DEVICE_DIR}/voice.xml")
    time.sleep(1)
    added = "<audio" in adb("shell", "run-as", PKG, "cat", "files/projects/test-voice.xml")
    print(f"{'ok  ' if added else 'FAIL'} pressing Preview while the voice is being made still adds the voice"
          + ("" if added else "\n       " + ("the sheet says the voice could not be made" if "could not be made" in ui else "no audio layer in the project")))
    sys.exit(0 if added else 1)


if __name__ == "__main__":
    main()
