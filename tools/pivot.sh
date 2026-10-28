#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. tools/env.sh
cpp=app/src/main/cpp
out=app/build/hostpivot
skia=third_party/skia
libs=$skia/out/mac-v2-2-arm64
if [ ! -f "$libs/libskia.a" ]; then
    echo "skipped: the host Skia build ($libs) is missing, so the pivot engine test cannot run"
    exit 0
fi
mkdir -p "$out/obj"
FLAGS="-std=c++20 -O1 -fPIC -fno-rtti -fno-exceptions -w -DNDEBUG -DSK_DISABLE_TRACING -DXML_STATIC -DU_DISABLE_RENAMING=1 -DU_STATIC_IMPLEMENTATION -DU_USING_ICU_NAMESPACE=0
 -I$cpp -I$skia -I$skia/third_party/externals/expat/expat/lib -I$skia/third_party/externals/icu/source/common"
export FLAGS cpp out
for f in camera drawing easing effects model motion scene shapes styles text textanim warp xml; do echo "$f"; done | xargs -P 8 -n 1 sh -c '
  src="$cpp/$0.cpp"; obj="$out/obj/$0.o"
  if [ ! -f "$obj" ] || [ -n "$(find "$cpp" -name "*.h" -newer "$obj" | head -n 1)" ] || [ "$src" -nt "$obj" ]; then clang++ $FLAGS -c "$src" -o "$obj"; fi'
clang++ $FLAGS -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -c tools/pivot/pivot_jni.cpp -o "$out/pivot_jni.o"
clang++ -shared -o "$out/libampivot.dylib" "$out/pivot_jni.o" "$out"/obj/*.o \
    "$libs/libskparagraph.a" "$libs/libskshaper.a" "$libs/libskunicode_icu.a" "$libs/libskunicode_core.a" "$libs/libskia.a" \
    "$libs/libpathops.a" "$libs/libskcms.a" "$libs/libharfbuzz.a" "$libs/libicu.a" "$libs/libpng.a" "$libs/libjpeg.a" \
    "$libs/libwebp.a" "$libs/libwuffs.a" "$libs/libzlib.a" "$libs/libexpat.a" \
    -framework CoreText -framework CoreGraphics -framework CoreFoundation -framework Foundation -framework Metal \
    -framework QuartzCore -framework ApplicationServices
[ "${1:-}" = "--lib" ] && exit 0
./gradlew :app:testFossDebugUnitTest --tests 'app.absentmotion.editor.PivotEngineTest' --rerun -q
echo "pivot tests against the host engine passed"
