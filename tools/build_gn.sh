#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REV=b2afae122eeb6ce09c52d63f67dc53fc517dbdc8
DIR="$ROOT/third_party/gn-src"
if [ ! -x "$DIR/out/gn" ]; then
    rm -rf "$DIR"
    mkdir -p "$DIR"
    git -C "$DIR" init -q
    git -C "$DIR" remote add origin https://gn.googlesource.com/gn
    git -C "$DIR" fetch -q --depth 1 origin "$REV"
    git -C "$DIR" checkout -q FETCH_HEAD
    (cd "$DIR" && python3 build/gen.py --no-last-commit-position)
    printf '#ifndef OUT_LAST_COMMIT_POSITION_H_\n#define OUT_LAST_COMMIT_POSITION_H_\n#define LAST_COMMIT_POSITION_NUM 0\n#define LAST_COMMIT_POSITION "0 (%s)"\n#endif\n' "$(printf '%s' "$REV" | cut -c1-12)" > "$DIR/out/last_commit_position.h"
    ninja -C "$DIR/out" gn
fi
echo "$DIR/out/gn"
