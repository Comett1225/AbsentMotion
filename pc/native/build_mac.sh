#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/app/src/main/cpp"
SKIA="$ROOT/third_party/skia"
LIBS="$SKIA/out/mac-v2-2-arm64"
OUT="$ROOT/pc/build/native"
mkdir -p "$OUT/obj"
FLAGS="-std=c++20 -O2 -fno-rtti -fno-exceptions -w -DNDEBUG -DSK_DISABLE_TRACING -DXML_STATIC -DU_DISABLE_RENAMING=1 -DU_STATIC_IMPLEMENTATION -DU_USING_ICU_NAMESPACE=0 -I$SRC -I$SKIA -I$SKIA/third_party/externals/expat/expat/lib -I$SKIA/third_party/externals/icu/source/common -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin"
AV_MODULES="libavformat libavcodec libavutil libswscale"
if command -v pkg-config >/dev/null 2>&1 && pkg-config --exists $AV_MODULES; then
    AV_CFLAGS="$(pkg-config --cflags $AV_MODULES)"
    AV_LIBS="$(pkg-config --libs $AV_MODULES)"
    AV_LIBDIR="$(pkg-config --variable=libdir libavcodec)"
else
    AV_PREFIX="${FFMPEG_PREFIX:-$(brew --prefix ffmpeg 2>/dev/null || echo /opt/homebrew)}"
    AV_CFLAGS="-I$AV_PREFIX/include"
    AV_LIBS="-L$AV_PREFIX/lib -lavformat -lavcodec -lavutil -lswscale"
    AV_LIBDIR="$AV_PREFIX/lib"
fi
export FLAGS SRC OUT
for f in audio_dsp audio_fx_jni camera drawing easing effects model motion particles pieces3d preview scene shapes styles text textanim warp xml; do echo "$f"; done | xargs -P 6 -n 1 sh -c '
  if [ ! -f "$OUT/obj/$0.o" ] || [ "$SRC/$0.cpp" -nt "$OUT/obj/$0.o" ] || [ -n "$(find "$SRC" -name "*.h" -newer "$OUT/obj/$0.o" -print -quit)" ]; then clang++ $FLAGS -c "$SRC/$0.cpp" -o "$OUT/obj/$0.o"; fi'
clang++ $FLAGS -c "$ROOT/pc/native/desktop_jni.cpp" -o "$OUT/desktop_jni.o"
clang++ $FLAGS -fobjc-arc -c "$ROOT/pc/native/gpu_metal.mm" -o "$OUT/gpu_metal.o"
clang++ $FLAGS -c "$ROOT/pc/native/audio_mac.cpp" -o "$OUT/audio_mac.o"
clang++ $FLAGS $AV_CFLAGS -c "$ROOT/pc/native/video.cpp" -o "$OUT/video.o"
clang++ $FLAGS $AV_CFLAGS -fobjc-arc -c "$ROOT/pc/native/video_mac.mm" -o "$OUT/video_mac.o"
clang++ -dynamiclib -o "$OUT/libamdesktop.dylib" "$OUT/desktop_jni.o" "$OUT/gpu_metal.o" "$OUT/audio_mac.o" "$OUT/video.o" "$OUT/video_mac.o" "$OUT"/obj/*.o \
    "$LIBS/libskparagraph.a" "$LIBS/libskshaper.a" "$LIBS/libskunicode_icu.a" "$LIBS/libskunicode_core.a" "$LIBS/libskia.a" \
    "$LIBS/libpathops.a" "$LIBS/libskcms.a" "$LIBS/libharfbuzz.a" "$LIBS/libicu.a" "$LIBS/libpng.a" "$LIBS/libjpeg.a" \
    "$LIBS/libwebp.a" "$LIBS/libwuffs.a" "$LIBS/libzlib.a" "$LIBS/libexpat.a" \
    -framework CoreText -framework CoreGraphics -framework CoreFoundation -framework Foundation -framework Metal \
    -framework QuartzCore -framework ApplicationServices -framework CoreAudio -framework CoreVideo $AV_LIBS \
    -Wl,-rpath,@loader_path -Wl,-rpath,"$AV_LIBDIR"
otool -L "$OUT/libamdesktop.dylib" | awk '/\/lib(avformat|avcodec|avutil|swscale|swresample)\./ {print $1}' | while read -r lib; do
    install_name_tool -change "$lib" "@rpath/$(basename "$lib")" "$OUT/libamdesktop.dylib"
done
codesign -f -s - "$OUT/libamdesktop.dylib" 2>/dev/null || true
