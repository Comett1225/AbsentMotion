#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. tools/env.sh
BUMP=""
for arg in "$@"; do
    case "$arg" in
        patch|minor|major) BUMP="$arg" ;;
        *) echo "usage: tools/release.sh [patch|minor|major]" >&2; exit 2 ;;
    esac
done

if [ -n "$BUMP" ]; then
    python3 - "$BUMP" <<'BUMP'
import re, sys
from pathlib import Path
path = Path("version.properties")
text = path.read_text()
major, minor, patch = map(int, re.search(r"^versionName=(\d+)\.(\d+)\.(\d+)$", text, re.M).groups())
major, minor, patch = {"major": (major + 1, 0, 0), "minor": (major, minor + 1, 0), "patch": (major, minor, patch + 1)}[sys.argv[1]]
code = int(re.search(r"^versionCode=(\d+)$", text, re.M).group(1)) + 1
text = re.sub(r"^versionName=.*$", f"versionName={major}.{minor}.{patch}", text, flags=re.M)
path.write_text(re.sub(r"^versionCode=.*$", f"versionCode={code}", text, flags=re.M))
BUMP
fi
version=$(sed -n 's/^versionName=//p' version.properties)
echo "AbsentMotion $version (versionCode $(sed -n 's/^versionCode=//p' version.properties))"

tools/check.sh

printf '\n== Android: release APKs (play, the free build without Google libraries, and the Compatible builds for Android 8 to 12, 64-bit and 32-bit)\n'
[ -f third_party/skia/out/android-arm64-compat/libskia.a ] || sh tools/build_skia.sh compat
[ -f third_party/skia/out/android-arm-compat/libskia.a ] || sh tools/build_skia.sh compat32
./gradlew :app:assemblePlayRelease :app:assembleFossRelease :app:assembleCompatRelease :app:assembleCompat32Release -q
mkdir -p build/release
mv app/build/outputs/apk/play/release/app-play-release.apk "build/release/AbsentMotion-$version-play.apk"
mv app/build/outputs/apk/foss/release/app-foss-release.apk "build/release/AbsentMotion-$version-foss.apk"
mv app/build/outputs/apk/compat/release/app-compat-release.apk "build/release/AbsentMotion-$version-compatible.apk"
mv app/build/outputs/apk/compat32/release/app-compat32-release.apk "build/release/AbsentMotion-$version-compatible-32bit.apk"
python3 tools/api_floor.py "build/release/AbsentMotion-$version-compatible.apk" --min 26
python3 tools/api_floor.py "build/release/AbsentMotion-$version-compatible-32bit.apk" --abi armeabi-v7a --min 26
python3 tools/apk_check.py "build/release/AbsentMotion-$version-play.apk" "build/release/AbsentMotion-$version-foss.apk" "build/release/AbsentMotion-$version-compatible.apk" \
    "build/release/AbsentMotion-$version-compatible-32bit.apk"

printf '\nReleased %s:\n  %s\n  %s\n  %s\n  %s\n' "$version" "$PWD/build/release/AbsentMotion-$version-play.apk" "$PWD/build/release/AbsentMotion-$version-foss.apk" \
    "$PWD/build/release/AbsentMotion-$version-compatible.apk" "$PWD/build/release/AbsentMotion-$version-compatible-32bit.apk"
