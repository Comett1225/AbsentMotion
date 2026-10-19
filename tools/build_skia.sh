#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIA="$ROOT/third_party/skia"
BRANCH=chrome/m156
COMMIT=95ee33d7ec7791ab9c071675710a14fe6408ae4f
case "$(uname -s)" in
    Darwin) HOST=darwin-x86_64; SDK_DEFAULT="$HOME/Library/Android/sdk" ;;
    Linux) HOST=linux-x86_64; SDK_DEFAULT="$HOME/Android/Sdk" ;;
    *) echo "build_skia.sh: only macOS and Linux are supported" >&2; exit 1 ;;
esac
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$SDK_DEFAULT}}"
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-$SDK/ndk/30.0.16248370}}"
if [ ! -d "$NDK/toolchains/llvm/prebuilt/$HOST" ]; then
    echo "build_skia.sh: no Android NDK at $NDK (set ANDROID_NDK)" >&2
    exit 1
fi

if [ ! -d "$SKIA" ]; then
    if ! { git init -q "$SKIA" && git -C "$SKIA" remote add origin https://skia.googlesource.com/skia \
           && git -C "$SKIA" fetch -q --depth 1 origin "$COMMIT" && git -C "$SKIA" checkout -q FETCH_HEAD; }; then
        rm -rf "$SKIA"
        git clone --depth 1 --branch "$BRANCH" https://skia.googlesource.com/skia "$SKIA"
    fi
fi
cd "$SKIA"
if [ "$(git rev-parse HEAD)" != "$COMMIT" ]; then
    echo "build_skia.sh: third_party/skia is at $(git rev-parse HEAD), this project is pinned to $COMMIT" >&2
    exit 1
fi

python3 "$ROOT/tools/skia_sync.py" "$SKIA"

for p in "$ROOT"/tools/skia-patches/*.patch; do
    if git apply --check "$p" 2>/dev/null; then
        git apply "$p"
    elif git apply --reverse --check "$p" 2>/dev/null; then
        :
    else
        echo "build_skia.sh: cannot apply patch $(basename "$p")" >&2
        exit 1
    fi
done

GN="$("$ROOT/tools/build_gn.sh" | tail -n 1)"
mkdir -p out/android-arm64
{ echo "ndk = \"$NDK\""; cat "$ROOT/tools/skia-args.gn"; } > out/android-arm64/args.gn
"$GN" gen out/android-arm64
ninja -C out/android-arm64 skia pathops skparagraph skshaper skunicode_core skunicode_icu
"$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-ar" rcs out/android-arm64/libpathops.a out/android-arm64/obj/src/pathops/*.o
ls -la out/android-arm64/libskia.a out/android-arm64/libpathops.a
