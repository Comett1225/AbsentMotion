#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
cpp=shared/cpp
out=android/build/hostwarp
mkdir -p "$out"
clang++ -std=c++20 -O2 -Wall -Wextra -Werror -I"$cpp" "$cpp/warp.cpp" tools/warp/test_warp.cpp -o "$out/test_warp"
"$out/test_warp"
