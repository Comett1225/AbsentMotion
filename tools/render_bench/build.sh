#!/bin/sh
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${1:-$ROOT/shared/cpp}"
OUT="${2:-$ROOT/build/render_bench/current}"
EXTRA=""
grep -q "steady" "$SRC/scene.h" && EXTRA="-DRENDER_BENCH_STEADY"
grep -q "SceneLights" "$SRC/scene.h" && EXTRA="$EXTRA -DRENDER_BENCH_DEPTH3D"
grep -q "pieceOrder" "$SRC/scene.h" && EXTRA="$EXTRA -DRENDER_BENCH_PIECES"
grep -q "SplitSeen" "$SRC/scene.h" && EXTRA="$EXTRA -DRENDER_BENCH_SPLIT"
SKIA="$ROOT/third_party/skia"
LIBS="$SKIA/out/mac-v2-2-arm64"
mkdir -p "$OUT/obj"
FLAGS="-std=c++20 -O2 -fno-rtti -fno-exceptions -w -DNDEBUG -DSK_DISABLE_TRACING -DXML_STATIC -DU_DISABLE_RENAMING=1 -DU_STATIC_IMPLEMENTATION -DU_USING_ICU_NAMESPACE=0 $EXTRA
 -I$SRC -I$SKIA -I$SKIA/third_party/externals/expat/expat/lib -I$SKIA/third_party/externals/icu/source/common"
[ "$(cat "$OUT/flags" 2>/dev/null)" = "$FLAGS" ] || { rm -f "$OUT"/obj/*.o "$OUT"/*.o; printf '%s\n' "$FLAGS" > "$OUT/flags"; }
SELF="$0"
export FLAGS SRC OUT SELF
stale() { [ ! -f "$1" ] || [ "$2" -nt "$1" ] || [ "$SELF" -nt "$1" ] || [ -n "$(find "$SRC" -name "*.h" -newer "$1" | head -n 1)" ]; }
for f in camera depth drawing easing effects model motion particles pieces3d scene shapes styles text textanim warp xml; do [ -f "$SRC/$f.cpp" ] && echo "$f"; done | xargs -P 8 -n 1 sh -c '
  src="$SRC/$0.cpp"; obj="$OUT/obj/$0.o"
  if [ ! -f "$obj" ] || [ "$src" -nt "$obj" ] || [ "$SELF" -nt "$obj" ] || [ -n "$(find "$SRC" -name "*.h" -newer "$obj" | head -n 1)" ]; then clang++ $FLAGS -c "$src" -o "$obj"; fi'
link() {
  [ -f "$1" ] && [ -z "$(find "$OUT/obj" "$2" -newer "$1" | head -n 1)" ] && return 0
  clang++ -o "$1.$$" "$2" "$OUT"/obj/*.o \
    "$LIBS/libskparagraph.a" "$LIBS/libskshaper.a" "$LIBS/libskunicode_icu.a" "$LIBS/libskunicode_core.a" "$LIBS/libskia.a" \
    "$LIBS/libpathops.a" "$LIBS/libskcms.a" "$LIBS/libharfbuzz.a" "$LIBS/libicu.a" "$LIBS/libpng.a" "$LIBS/libjpeg.a" \
    "$LIBS/libwebp.a" "$LIBS/libwuffs.a" "$LIBS/libzlib.a" "$LIBS/libexpat.a" \
    -framework CoreText -framework CoreGraphics -framework CoreFoundation -framework Foundation -framework Metal \
    -framework QuartzCore -framework ApplicationServices
  mv "$1.$$" "$1"
}
program() {
  if stale "$OUT/$1.o" "$ROOT/tools/render_bench/$1.cpp"; then clang++ $FLAGS -c "$ROOT/tools/render_bench/$1.cpp" -o "$OUT/$1.o"; fi
  link "$OUT/$1" "$OUT/$1.o"
}
program render_bench
[ -z "$EXTRA" ] || program steady_test
if grep -q "struct ValueLink" "$SRC/scene.h"; then program link_test; fi
echo "built $OUT/render_bench"
