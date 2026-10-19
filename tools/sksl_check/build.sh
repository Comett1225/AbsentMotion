#!/bin/sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SKIA="$ROOT/third_party/skia"
OUT="$ROOT/build/sksl_check"
mkdir -p "$OUT/obj"
SRC=$(cd "$SKIA" && ls src/sksl/*.cpp src/sksl/analysis/*.cpp src/sksl/ir/*.cpp src/sksl/transform/*.cpp \
  src/sksl/tracing/SkSLDebugTracePriv.cpp src/sksl/tracing/SkSLTraceHook.cpp \
  | grep -v 'ModuleDataDefault\|GraphiteModules\|SkSLFileOutputStream')
SRC="$SRC src/core/SkArenaAlloc.cpp src/core/SkBlockAllocator.cpp src/core/SkChecksum.cpp src/core/SkContainers.cpp src/core/SkCpu.cpp
 src/core/SkData.cpp src/core/SkHalf.cpp src/core/SkLog.cpp src/core/SkMalloc.cpp src/core/SkMathPriv.cpp src/core/SkMatrixInvert.cpp
 src/core/SkSafeMath.cpp src/core/SkSemaphore.cpp src/core/SkStream.cpp src/core/SkString.cpp src/core/SkStringUtils.cpp
 src/core/SkThreadID.cpp src/core/SkUTF.cpp src/core/SkUtils.cpp src/ports/SkLog_stdio.cpp src/ports/SkMemory_malloc.cpp
 src/ports/SkOSFile_stdio.cpp src/ports/SkOSFile_posix.cpp src/utils/SkLogHandler.cpp src/utils/SkOSPath.cpp src/utils/SkParse.cpp
 src/utils/SkShaderUtils.cpp src/utils/SkGetExecutablePath_mac.cpp src/gpu/ganesh/GrMemoryPool.cpp"
FLAGS="-std=c++20 -O1 -w -fno-exceptions -fno-rtti -DSKSL_STANDALONE -DSK_DISABLE_TRACING -DSK_COMPILE_WITH_GN -DSK_RELEASE -I$SKIA -I$SKIA/include/config"
export SKIA OUT FLAGS
for f in $SRC; do echo "$f"; done | xargs -P 10 -n 1 sh -c '
  o="$OUT/obj/$(echo "$0" | tr / _).o"
  [ "$o" -nt "$SKIA/$0" ] || clang++ $FLAGS -c "$SKIA/$0" -o "$o"'
clang++ $FLAGS -c "$HERE/sksl_check.cpp" -o "$OUT/obj/main.o"
clang++ -o "$OUT/sksl_check" "$OUT"/obj/*.o
cp "$SKIA"/src/sksl/sksl_*.sksl "$OUT/"
echo "built $OUT/sksl_check"
