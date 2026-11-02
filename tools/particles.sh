#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
cpp=shared/cpp
out=android/build/hostparticles
mkdir -p "$out"
clang++ -std=c++20 -O2 -Wall -Wextra -Werror -I"$cpp" "$cpp/particles.cpp" tools/particles/test_particles.cpp -o "$out/test_particles"
"$out/test_particles"
