#!/bin/sh
. "$(dirname "$0")/env.sh"
adb() { "$ADB" -s "${ANDROID_SERIAL:-emulator-5554}" "$@"; }
D=/sdcard/Android/data/app.absentmotion/files
SCENE="$1"; shift
adb push "$SCENE" $D/perf.xml >/dev/null 2>&1
adb shell am force-stop app.absentmotion
adb logcat -c
adb shell am start -n app.absentmotion/.MainActivity --es xml $D/perf.xml --es export video "$@" >/dev/null
for i in $(seq 1 600); do sleep 0.5; adb logcat -d -s AMExport | grep -q -E "done|failed" && break; done
adb logcat -d -v epoch | python3 "$(dirname "$0")/export_timing.py" "$SCENE"
