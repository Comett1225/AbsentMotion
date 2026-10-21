#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
cpp=app/src/main/cpp
out=app/build/hostfx
mkdir -p "$out"
clang++ -std=c++17 -O2 -Wall -Wextra -Werror -I"$cpp" "$cpp/audio_dsp.cpp" tools/audio_dsp/test_dsp.cpp -o "$out/test_dsp"
"$out/test_dsp"
case "${1:-}" in --jvm|--lib) ;; *) exit 0 ;; esac
. tools/env.sh
case "$(uname -s)" in Darwin) os=darwin; lib=libamfx.dylib ;; *) os=linux; lib=libamfx.so ;; esac
clang++ -std=c++17 -O2 -Wall -Wextra -Werror -shared -fPIC -I"$cpp" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$os" \
    "$cpp/audio_dsp.cpp" "$cpp/audio_fx_jni.cpp" -o "$out/$lib"
[ "${1:-}" = "--lib" ] && exit 0
./gradlew :app:testFossDebugUnitTest --tests 'app.absentmotion.audio.NativeFxTest' --rerun -q
echo "JVM tests against the host DSP library passed"
