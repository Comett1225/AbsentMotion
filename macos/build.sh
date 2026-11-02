#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/pc/build/native"
cmake -S "$ROOT/pc/native" -B "$ROOT/pc/build/native-mac" -G Ninja -DCMAKE_BUILD_TYPE=None -DCMAKE_OSX_ARCHITECTURES=arm64 >/dev/null
cmake --build "$ROOT/pc/build/native-mac" --parallel 8
otool -L "$OUT/libamdesktop.dylib" | awk '/\/lib(avformat|avcodec|avutil|swscale|swresample)\./ {print $1}' | while read -r lib; do
    install_name_tool -change "$lib" "@rpath/$(basename "$lib")" "$OUT/libamdesktop.dylib"
done
codesign -f -s - "$OUT/libamdesktop.dylib" 2>/dev/null || true
