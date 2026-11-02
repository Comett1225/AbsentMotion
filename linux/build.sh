#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cmake -S "$ROOT/pc/native" -B "$ROOT/pc/build/native-linux" -G Ninja -DCMAKE_BUILD_TYPE=None >/dev/null
cmake --build "$ROOT/pc/build/native-linux" --parallel "$(nproc)"
