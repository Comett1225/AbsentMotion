#!/usr/bin/env bash
set -euo pipefail
serial=${1:?usage: tools/ab_bench.sh <serial> [<files folder>] [<results folder>]}
root=$(cd "$(dirname "$0")/.." && pwd)
files=${2:-$root/build/ab/files}
results=${3:-$root/build/ab}
export ANDROID_SERIAL=$serial
adb=(adb -s "$serial")
pkg=app.absentmotion
data=/sdcard/Android/data/$pkg/files
[ -d "$files" ] || { echo "no file set in $files: make it with tools/ab_files.sh $files <windows media samples>"; exit 1; }
mkdir -p "$results"
if [ -z "${AB_NO_BUILD:-}" ]; then
  [ -f "$root/build/ffamp-android/arm64-v8a/lib/libxvidcore.a" ] || "$root/third_party/FFamp/third_party/build_android.sh" "$root/build/ffamp-android"
  (cd "$root" && ./gradlew -q :app:assembleFossDebug)
  cp "$root/android/build/outputs/apk/foss/debug/app-foss-debug.apk" "$results/app.apk"
fi
"${adb[@]}" install -r -t "$results/app.apk" >/dev/null
launch() {
  "${adb[@]}" shell am force-stop $pkg
  "${adb[@]}" shell am start -W -n $pkg/.MainActivity "$@" >/dev/null
}
for mode in standard ffamp; do
  launch --ez mediaFfamp "$([ $mode = ffamp ] && echo true || echo false)"
  sleep 3
  "${adb[@]}" shell rm -f $data/ab_bench.json $data/ab_bench.json.done
  launch --es mediabench abfiles
  until "${adb[@]}" shell test -d $data/abfiles; do sleep 1; done
  "${adb[@]}" push --sync "$files/." $data/abfiles >/dev/null
  "${adb[@]}" shell touch $data/abfiles/.go
  waited=0
  until "${adb[@]}" shell test -f $data/ab_bench.json.done; do
    sleep 5
    waited=$((waited + 5))
    [ $waited -lt 3600 ] || { echo "$mode: no result after an hour"; break; }
  done
  "${adb[@]}" pull $data/ab_bench.json "$results/$mode.json" >/dev/null
  echo "$mode: $results/$mode.json ($(grep -o '"backend": "[a-z]*"' "$results/$mode.json"))"
done
launch --ez mediaFfamp false
sleep 3
"${adb[@]}" shell am force-stop $pkg
