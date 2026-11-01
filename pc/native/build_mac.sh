#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/app/src/main/cpp"
SKIA="$ROOT/third_party/skia"
LIBS="$SKIA/out/mac-v2-2-arm64"
OUT="$ROOT/pc/build/native"
mkdir -p "$OUT/obj"
FLAGS="-std=c++20 -O2 -fno-rtti -fno-exceptions -w -DNDEBUG -DSK_DISABLE_TRACING -DXML_STATIC -DU_DISABLE_RENAMING=1 -DU_STATIC_IMPLEMENTATION -DU_USING_ICU_NAMESPACE=0 -I$SRC -I$SKIA -I$SKIA/third_party/externals/expat/expat/lib -I$SKIA/third_party/externals/icu/source/common -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin"
export FLAGS SRC OUT
for f in camera drawing easing effects model motion particles pieces3d scene shapes styles text textanim warp xml; do echo "$f"; done | xargs -P 6 -n 1 sh -c '
  if [ ! -f "$OUT/obj/$0.o" ] || [ "$SRC/$0.cpp" -nt "$OUT/obj/$0.o" ] || [ -n "$(find "$SRC" -name "*.h" -newer "$OUT/obj/$0.o" -print -quit)" ]; then clang++ $FLAGS -c "$SRC/$0.cpp" -o "$OUT/obj/$0.o"; fi'
clang++ $FLAGS -c "$ROOT/pc/native/desktop_jni.cpp" -o "$OUT/desktop_jni.o"
clang++ $FLAGS -fobjc-arc -c "$ROOT/pc/native/gpu_metal.mm" -o "$OUT/gpu_metal.o"
clang++ -dynamiclib -o "$OUT/libamdesktop.dylib" "$OUT/desktop_jni.o" "$OUT/gpu_metal.o" "$OUT"/obj/*.o \
    "$LIBS/libskparagraph.a" "$LIBS/libskshaper.a" "$LIBS/libskunicode_icu.a" "$LIBS/libskunicode_core.a" "$LIBS/libskia.a" \
    "$LIBS/libpathops.a" "$LIBS/libskcms.a" "$LIBS/libharfbuzz.a" "$LIBS/libicu.a" "$LIBS/libpng.a" "$LIBS/libjpeg.a" \
    "$LIBS/libwebp.a" "$LIBS/libwuffs.a" "$LIBS/libzlib.a" "$LIBS/libexpat.a" \
    -framework CoreText -framework CoreGraphics -framework CoreFoundation -framework Foundation -framework Metal \
    -framework QuartzCore -framework ApplicationServices
