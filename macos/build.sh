#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ARCH="${1:-}"
case "$ARCH" in
    "") OUT="$ROOT/pc/build/native"; BUILD="$ROOT/pc/build/native-mac"; set -- -DCMAKE_OSX_ARCHITECTURES=arm64 ;;
    arm64|x64)
        CPU="$ARCH"; [ "$ARCH" = x64 ] && CPU=x86_64
        OUT="$ROOT/pc/build/native-$ARCH"; BUILD="$ROOT/pc/build/native-mac-$ARCH"
        FFMPEG="${FFMPEG_PREFIX:?set FFMPEG_PREFIX to an $CPU FFmpeg build}"
        set -- -DCMAKE_OSX_ARCHITECTURES=$CPU -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 -DAM_ARCH=$ARCH "-DFFMPEG_PREFIX=$FFMPEG" ;;
    *) echo "usage: macos/build.sh [arm64|x64]" >&2; exit 2 ;;
esac
cmake -S "$ROOT/pc/native" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=None "$@" >/dev/null
cmake --build "$BUILD" --parallel 8
otool -L "$OUT/libamdesktop.dylib" | sed -n -E 's#^[[:space:]]*(.*/lib(avformat|avcodec|avutil|swscale|swresample)\..*) \(compatibility version.*#\1#p' | while IFS= read -r lib; do
    install_name_tool -change "$lib" "@rpath/$(basename "$lib")" "$OUT/libamdesktop.dylib"
done
if [ -n "$ARCH" ]; then
    for lib in avcodec avdevice avfilter avformat avutil swresample swscale; do
        cp -f "$FFMPEG/lib/$(basename "$(otool -D "$FFMPEG/lib/lib$lib.dylib" | tail -n 1)")" "$OUT/"
    done
    cp -f "$FFMPEG/bin/ffmpeg" "$FFMPEG/bin/ffprobe" "$FFMPEG/ffmpeg-LICENSE.txt" "$OUT/"
    codesign -f -s - "$OUT"/*.dylib "$OUT/ffmpeg" "$OUT/ffprobe" 2>/dev/null || true
else
    codesign -f -s - "$OUT/libamdesktop.dylib" 2>/dev/null || true
fi
