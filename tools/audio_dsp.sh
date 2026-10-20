#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
cpp=app/src/main/cpp
out=app/build/hostfx
mkdir -p "$out"
clang++ -std=c++17 -O2 -Wall -Wextra -Werror -I"$cpp" "$cpp/audio_dsp.cpp" tools/audio_dsp/test_dsp.cpp -o "$out/test_dsp"
"$out/test_dsp"
