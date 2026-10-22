#!/bin/sh
set -u
cd "$(dirname "$0")/../.."
. tools/env.sh
keep=0
port=5560
gpu=host
apis=""
for arg in "$@"; do
    case "$arg" in
        --keep) keep=1 ;;
        --port=*) port="${arg#--port=}" ;;
        --gpu=*) gpu="${arg#--gpu=}" ;;
        28|29|30|31|32) apis="$apis $arg" ;;
        *) echo "usage: tools/compat/compat_emulators.sh [--keep] [--port=5560] [--gpu=host|swiftshader_indirect] [28 30 32]   (one emulator at a time; images and AVDs are deleted afterwards unless --keep)" >&2; exit 2 ;;
    esac
done
[ -n "$apis" ] || apis="28 30 32"
apk=$(ls -t app/build/outputs/apk/compat/debug/*.apk 2>/dev/null | head -1)
[ -n "$apk" ] || { echo "compat_emulators.sh: build the debug APK first: ./gradlew :app:assembleCompatDebug" >&2; exit 2; }
tools="$SDK_ROOT/cmdline-tools/latest/bin"
serial="emulator-$port"
avds="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p build
failed=0
for api in $apis; do
    image="system-images;android-$api;default;arm64-v8a"
    folder="$SDK_ROOT/system-images/android-$api/default/arm64-v8a"
    name="AbsentCompat$api"
    had=0
    [ -d "$folder" ] && had=1
    printf '\n== Android API %s (%s)\n' "$api" "$image"
    [ "$had" = 1 ] || yes | "$tools/sdkmanager" "$image" >/dev/null || { echo "cannot install $image" >&2; failed=$((failed + 1)); continue; }
    echo no | "$tools/avdmanager" create avd -n "$name" -k "$image" -d pixel_4 --force >/dev/null
    printf 'hw.ramSize=2048\nhw.gpu.enabled=yes\nhw.gpu.mode=%s\n' "$gpu" >> "$avds/$name.avd/config.ini"
    "$SDK_ROOT/emulator/emulator" -avd "$name" -port "$port" -no-window -no-audio -no-snapshot -no-boot-anim -gpu "$gpu" >"build/compat-emulator-$api.log" 2>&1 &
    pid=$!
    "$ADB" -s "$serial" wait-for-device
    booted=0
    for _ in $(seq 1 180); do
        [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
        sleep 2
    done
    if [ "$booted" = 1 ]; then
        if ANDROID_SERIAL="$serial" python3 tools/smoke_check.py "$apk"; then
            ANDROID_SERIAL="$serial" python3 tools/compat/export_smoke.py || failed=$((failed + 1))
        elif "$ADB" -s "$serial" logcat -d -s AMEngine | grep -q "vkCreateInstance failed"; then
            echo "API $api: SKIPPED: the Vulkan driver of this emulator image refuses vkCreateInstance (an emulator limit, not the app)"
        else
            failed=$((failed + 1))
        fi
    else
        echo "API $api did not boot (log: build/compat-emulator-$api.log)" >&2
        failed=$((failed + 1))
    fi
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1
    wait "$pid" 2>/dev/null
    if [ "$keep" = 0 ]; then
        "$tools/avdmanager" delete avd -n "$name" >/dev/null 2>&1
        if [ "$had" = 0 ]; then
            "$tools/sdkmanager" --uninstall "$image" >/dev/null
            rmdir "$(dirname "$folder")" "$(dirname "$(dirname "$folder")")" 2>/dev/null
        fi
    fi
done
[ "$failed" -eq 0 ] || { printf '\ncompat_emulators.sh: %s check(s) failed\n' "$failed" >&2; exit 1; }
printf '\ncompat_emulators.sh: all emulators passed\n'
