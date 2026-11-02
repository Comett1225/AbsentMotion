#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
cpp=shared/cpp
out=android/build/hosttext
mkdir -p "$out"
clang++ -std=c++20 -O2 -Wall -Wextra -Werror -I"$cpp" "$cpp/textanim.cpp" tools/textanim/test_textanim.cpp -o "$out/test_textanim"
"$out/test_textanim"
