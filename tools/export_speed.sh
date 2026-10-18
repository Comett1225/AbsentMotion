#!/bin/sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
D=/sdcard/Android/data/app.absentmotion/files
SCENE="$1"; shift
"$ADB" push "$SCENE" $D/perf.xml >/dev/null 2>&1
"$ADB" shell am force-stop app.absentmotion
"$ADB" logcat -c
"$ADB" shell am start -n app.absentmotion/.MainActivity --es xml $D/perf.xml --es export video "$@" >/dev/null
for i in $(seq 1 600); do sleep 0.5; "$ADB" logcat -d -s AMExport | grep -q -E "done|failed" && break; done
"$ADB" logcat -d -v epoch | grep -E "AMEngine: attached.*color space [1-9]|I AMExport: " | tail -2 | python3 -c "
import sys,re
t=[float(re.search(r'^\s*(\d+\.\d+)',l).group(1)) for l in sys.stdin]
fps=int(re.search(r'fps=\"(\d+)\"',open('$SCENE').read()).group(1)); total=int(re.search(r'totalTime=\"(\d+)\"',open('$SCENE').read()).group(1))
frames=round((total+1)*fps/1000)
print('$SCENE'.split('/')[-1]+': %d frames in %.1f s = %.0f frames per second (%.1fx real time)'%(frames,t[1]-t[0],frames/(t[1]-t[0]),frames/(t[1]-t[0])/fps))"
