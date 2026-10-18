#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIA="$ROOT/third_party/skia"
BRANCH=chrome/m156
COMMIT=95ee33d7ec7791ab9c071675710a14fe6408ae4f
NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/30.0.16248370}"

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

python3 - <<'EOF'
skip = {'angle2', 'dawn', 'swiftshader', 'emsdk', 'd3d12allocator', 'vello', 'perfetto', 'partition_alloc',
        'libjxl', 'highway', 'libavif', 'libgav1', 'imgui', 'unicodetools', 'icu4x', 'oboe', 'vulkan-deps',
        'vulkan-tools', 'jinja2', 'markupsafe', 'egl-registry', 'opengl-registry', 'abseil-cpp'}
d = {}
exec('def Var(x): return vars[x]\n' + open('DEPS').read(), d)
deps = {k: v for k, v in d['deps'].items() if isinstance(v, str) and k.split('/')[-1] not in skip}
open('DEPS.filtered', 'w').write('deps = ' + repr(deps) + '\n')
EOF
GIT_SYNC_DEPS_PATH="$PWD/DEPS.filtered" GIT_SYNC_DEPS_SKIP_EMSDK=1 python3 tools/git-sync-deps

for p in "$ROOT"/tools/skia-patches/*.patch; do git apply --check "$p" 2>/dev/null && git apply "$p"; done

mkdir -p out/android-arm64
{ echo "ndk = \"$NDK\""; cat "$ROOT/tools/skia-args.gn"; } > out/android-arm64/args.gn
bin/gn gen out/android-arm64
ninja -C out/android-arm64 skia pathops skparagraph skshaper skunicode_core skunicode_icu
"$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar" rcs out/android-arm64/libpathops.a out/android-arm64/obj/src/pathops/*.o
ls -la out/android-arm64/libskia.a out/android-arm64/libpathops.a
