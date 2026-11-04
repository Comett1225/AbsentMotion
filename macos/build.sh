#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ARCH="${1:-arm64}"
case "$ARCH" in
    arm64) OUT="$ROOT/pc/build/native"; BUILD="$ROOT/pc/build/native-mac"; set -- -DCMAKE_OSX_ARCHITECTURES=arm64 ;;
    x64)
        OUT="$ROOT/pc/build/native-x64"; BUILD="$ROOT/pc/build/native-mac-x64"
        FFMPEG="${FFMPEG_PREFIX:?set FFMPEG_PREFIX to an x86_64 FFmpeg build}"
        set -- -DCMAKE_OSX_ARCHITECTURES=x86_64 -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 -DAM_ARCH=x64 -DFFMPEG_PREFIX="$FFMPEG" \
            "-DCMAKE_C_FLAGS=-ffile-prefix-map=$ROOT/=" "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=$ROOT/=" "-DCMAKE_OBJCXX_FLAGS=-ffile-prefix-map=$ROOT/=" ;;
    *) echo "usage: macos/build.sh [arm64|x64]" >&2; exit 2 ;;
esac
cmake -S "$ROOT/pc/native" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=None "$@" >/dev/null
cmake --build "$BUILD" --parallel 8
otool -L "$OUT/libamdesktop.dylib" | awk '/\/lib(avformat|avcodec|avutil|swscale|swresample)\./ {print $1}' | while read -r lib; do
    install_name_tool -change "$lib" "@rpath/$(basename "$lib")" "$OUT/libamdesktop.dylib"
done
if [ "$ARCH" = x64 ]; then
    for lib in avcodec avdevice avfilter avformat avutil swresample swscale; do
        cp -f "$FFMPEG/lib/$(basename "$(otool -D "$FFMPEG/lib/lib$lib.dylib" | tail -n 1)")" "$OUT/"
    done
    cp -f "$FFMPEG/bin/ffmpeg" "$FFMPEG/bin/ffprobe" "$OUT/"
    codesign -f -s - "$OUT"/*.dylib "$OUT/ffmpeg" "$OUT/ffprobe" 2>/dev/null || true
else
    codesign -f -s - "$OUT/libamdesktop.dylib" 2>/dev/null || true
fi
