#!/bin/sh
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$(dirname "$0")/env.sh"
adb() { "$ADB" -s "${ANDROID_SERIAL:-emulator-5554}" "$@"; }
OUT="$ROOT/build/shots/lm-$1.png"
mkdir -p "$(dirname "$OUT")"
adb exec-out screencap -p > "$OUT.full.png"
sips -Z 1170 "$OUT.full.png" --out "$OUT" >/dev/null 2>&1
rm -f "$OUT.full.png"
adb shell dumpsys window | grep mCurrentFocus | tail -1 | sed 's/.* u0 //; s/}//'
if [ "$2" = tree ]; then
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    python3 - "$(adb exec-out cat /sdcard/ui.xml)" <<'PY'
import sys
import xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.argv[1])
except ET.ParseError:
    sys.exit("(no view tree)")
for n in root.iter("node"):
    a = n.attrib
    t, d, rid = a.get("text", ""), a.get("content-desc", ""), a.get("resource-id", "").split("/")[-1]
    flags = "".join(k[0].upper() for k in ("clickable", "long-clickable", "checkable", "selected", "scrollable") if a.get(k) == "true") + ("*" if a.get("checked") == "true" else "")
    if t or d or rid or flags:
        print(f'{a.get("bounds")} {a.get("class", "").split(".")[-1]} {rid} {t!r} {d!r} {flags}'.replace(" '' ''", "").replace(" ''", ""))
PY
fi
echo "$OUT"
