#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
case "$(uname -m)" in
    aarch64|arm64) CPU=arm64 ;;
    x86_64|amd64) CPU=x64 ;;
    *) echo "linux/skia.sh: unsupported machine $(uname -m)" >&2; exit 1 ;;
esac
SKIA="${SKIA_DIR:-$ROOT/third_party/skia}"
OUT="${SKIA_OUT:-$SKIA/out/linux-$CPU}"
GN="${GN:-$("$ROOT/tools/build_gn.sh" | tail -n 1)}"
mkdir -p "$OUT"
{ printf 'target_os = "linux"\ntarget_cpu = "%s"\ncc = "clang"\ncxx = "clang++"\n' "$CPU"; cat "$ROOT/linux/skia-args.gn"; } > "$OUT/args.gn"
"$GN" gen "$OUT" --root="$SKIA"
ninja -C "$OUT" skia pathops skparagraph skshaper skunicode_core skunicode_icu
ar rcs "$OUT/libpathops.a" "$OUT"/obj/src/pathops/*.o
ls -la "$OUT/libskia.a" "$OUT/libpathops.a"
