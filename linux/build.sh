#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/pc/build/native"
FFMPEG="${FFMPEG_PREFIX:-}"
[ -z "$FFMPEG" ] || export PKG_CONFIG_PATH="$FFMPEG/lib/pkgconfig"
cmake -S "$ROOT/pc/native" -B "$ROOT/pc/build/native-linux" -G Ninja -DCMAKE_BUILD_TYPE=None ${SKIA_LIBS:+"-DSKIA_LIBS=$SKIA_LIBS"} >/dev/null
cmake --build "$ROOT/pc/build/native-linux" --parallel "$(nproc)"
if [ -n "$FFMPEG" ]; then
    for lib in avcodec avdevice avfilter avformat avutil swresample swscale; do
        name="$(objdump -p "$FFMPEG/lib/lib$lib.so" | awk '/SONAME/ {print $2}')"
        cp -fL "$FFMPEG/lib/$name" "$OUT/$name"
    done
    cp -f "$FFMPEG/bin/ffmpeg" "$FFMPEG/bin/ffprobe" "$OUT/"
    cp -f "$FFMPEG/LICENSE.txt" "$OUT/ffmpeg-LICENSE.txt"
    for file in "$OUT"/lib*.so.* "$OUT/ffmpeg" "$OUT/ffprobe"; do patchelf --set-rpath '$ORIGIN' "$file"; done
fi
