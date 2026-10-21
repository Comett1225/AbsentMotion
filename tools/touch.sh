#!/bin/sh
set -e
cd "$(dirname "$0")"
. ./env.sh
SDK="$SDK_ROOT"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
adb() { "$ADB" -s "${ANDROID_SERIAL:-emulator-5554}" "$@"; }
OUT=../app/build/touch
if [ ! -f $OUT/classes.dex ] || [ Touch.java -nt $OUT/classes.dex ]; then
    mkdir -p $OUT
    $JDK/bin/javac -nowarn -source 17 -target 17 -cp "$SDK/platforms/android-37.0/android.jar" -d $OUT Touch.java 2>/dev/null
    JAVA_HOME=$JDK PATH="$JDK/bin:$PATH" "$SDK/build-tools/37.0.0/d8" --output $OUT $OUT/Touch.class
fi
adb push $OUT/classes.dex /data/local/tmp/touch.dex >/dev/null
adb shell CLASSPATH=/data/local/tmp/touch.dex app_process / Touch "$@"
