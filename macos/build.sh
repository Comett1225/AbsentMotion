#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ARCH="${1:-}"
case "$ARCH" in
    "") OUT="$ROOT/pc/build/native"; BUILD="$ROOT/pc/build/native-mac"; set -- -DCMAKE_OSX_ARCHITECTURES=arm64 ;;
    arm64|x64)
        CPU="$ARCH"; [ "$ARCH" = x64 ] && CPU=x86_64
        OUT="$ROOT/pc/build/native-$ARCH"; BUILD="$ROOT/pc/build/native-mac-$ARCH"
        DEPS="${FFAMP_DEPS:?set FFAMP_DEPS to the folder third_party/FFamp/third_party/build_mac.sh made for $CPU}"
        set -- -DCMAKE_OSX_ARCHITECTURES=$CPU -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 -DAM_ARCH=$ARCH "-DFFAMP_DEPS=$DEPS" ;;
    *) echo "usage: macos/build.sh [arm64|x64]" >&2; exit 2 ;;
esac
cmake -S "$ROOT/pc/native" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=None "$@" >/dev/null
cmake --build "$BUILD" --parallel "${AM_JOBS:-8}"
if [ -n "$ARCH" ]; then
    linked="$(otool -L "$OUT/libamdesktop.dylib" | sed 1,2d | grep -v -E '^[[:space:]]*(/System/Library/|/usr/lib/)' || true)"
    [ -z "$linked" ] || { echo "libamdesktop.dylib links outside the system:" >&2; echo "$linked" >&2; exit 1; }
fi
codesign -f -s - "$OUT/libamdesktop.dylib" 2>/dev/null || true
