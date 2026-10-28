#!/bin/sh
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${1:-$ROOT/app/src/main/cpp}"
OUT="${2:-$ROOT/build/render_bench/current}"
EXTRA=""
grep -q "steady" "$SRC/scene.h" && EXTRA="-DRENDER_BENCH_STEADY"
SKIA="$ROOT/third_party/skia"
LIBS="$SKIA/out/mac-v2-2-arm64"
mkdir -p "$OUT/obj"
FLAGS="-std=c++20 -O2 -fno-rtti -fno-exceptions -w -DNDEBUG -DSK_DISABLE_TRACING -DXML_STATIC -DU_DISABLE_RENAMING=1 -DU_STATIC_IMPLEMENTATION -DU_USING_ICU_NAMESPACE=0 $EXTRA
 -I$SRC -I$SKIA -I$SKIA/third_party/externals/expat/expat/lib -I$SKIA/third_party/externals/icu/source/common"
export FLAGS SRC OUT
for f in camera drawing easing effects model motion scene shapes styles text textanim warp xml; do echo "$f"; done | xargs -P 8 -n 1 sh -c '
  clang++ $FLAGS -c "$SRC/$0.cpp" -o "$OUT/obj/$0.o"'
link() {
  clang++ -o "$1" "$2" "$OUT"/obj/*.o \
    "$LIBS/libskparagraph.a" "$LIBS/libskshaper.a" "$LIBS/libskunicode_icu.a" "$LIBS/libskunicode_core.a" "$LIBS/libskia.a" \
    "$LIBS/libpathops.a" "$LIBS/libskcms.a" "$LIBS/libharfbuzz.a" "$LIBS/libicu.a" "$LIBS/libpng.a" "$LIBS/libjpeg.a" \
    "$LIBS/libwebp.a" "$LIBS/libwuffs.a" "$LIBS/libzlib.a" "$LIBS/libexpat.a" \
    -framework CoreText -framework CoreGraphics -framework CoreFoundation -framework Foundation -framework Metal \
    -framework QuartzCore -framework ApplicationServices
}
clang++ $FLAGS -c "$ROOT/tools/render_bench/render_bench.cpp" -o "$OUT/render_bench.o"
link "$OUT/render_bench" "$OUT/render_bench.o"
if [ -n "$EXTRA" ]; then
  clang++ $FLAGS -c "$ROOT/tools/render_bench/steady_test.cpp" -o "$OUT/steady_test.o"
  link "$OUT/steady_test" "$OUT/steady_test.o"
fi
echo "built $OUT/render_bench"
