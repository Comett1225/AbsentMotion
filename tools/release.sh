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
[ -f signing.properties ] || { echo "release.sh: signing.properties is missing, so the APKs would be left unsigned (docs/BUILD.md, section 6)" >&2; exit 1; }

SAVED=""
restore() {
    status=$?
    if [ -n "$SAVED" ]; then
        if [ "$status" -ne 0 ]; then cp "$SAVED" version.properties; echo "release failed: version.properties is back to its old value" >&2; fi
        rm -f "$SAVED"
    fi
}
trap restore EXIT
if [ -n "$BUMP" ]; then
    SAVED="$(mktemp)"
    cp version.properties "$SAVED"
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

printf '\n== Quick check before the long builds\n'
tools/check.sh --quick

printf '\n== Android: release APKs (the standard build and the Compatible builds for Android 8 to 12, 64-bit and 32-bit, for ARM and for x86_64)\n'
[ -f third_party/skia/out/android-arm64-compat/libskia.a ] || sh tools/build_skia.sh compat
[ -f third_party/skia/out/android-arm-compat/libskia.a ] || sh tools/build_skia.sh compat32
[ -f third_party/skia/out/android-x64/libskia.a ] || sh tools/build_skia.sh x64
[ -f third_party/skia/out/android-x64-compat/libskia.a ] || sh tools/build_skia.sh compat-x64
out=build/release/AbsentMotion-$version
mkdir -p build/release
./gradlew -PamAbi=x86_64 :app:assembleFossRelease :app:assembleCompatRelease -q
mv android/build/outputs/apk/foss/release/app-foss-release.apk "$out-foss-x86_64.apk"
mv android/build/outputs/apk/compat/release/app-compat-release.apk "$out-compatible-x86_64.apk"
./gradlew :app:assembleFossRelease :app:assembleCompatRelease :app:assembleCompat32Release -q
mv android/build/outputs/apk/foss/release/app-foss-release.apk "$out-foss.apk"
mv android/build/outputs/apk/compat/release/app-compat-release.apk "$out-compatible.apk"
mv android/build/outputs/apk/compat32/release/app-compat32-release.apk "$out-compatible-32bit.apk"

printf '\n== Full check against the release builds\n'
tools/check.sh
python3 tools/api_floor.py "$out-compatible.apk" --min 26
python3 tools/api_floor.py "$out-compatible-32bit.apk" --abi armeabi-v7a --min 26
python3 tools/api_floor.py "$out-foss-x86_64.apk" --abi x86_64
python3 tools/api_floor.py "$out-compatible-x86_64.apk" --abi x86_64 --min 26
python3 tools/apk_check.py "$out-foss.apk" "$out-compatible.apk" "$out-compatible-32bit.apk" "$out-foss-x86_64.apk" "$out-compatible-x86_64.apk"
if [ -f tools/publish_forbidden.txt ]; then
    sh tools/publish_check.sh --apk "$out-foss.apk" --apk "$out-compatible.apk" --apk "$out-compatible-32bit.apk" --apk "$out-foss-x86_64.apk" --apk "$out-compatible-x86_64.apk"
else
    echo "skipped: tools/publish_forbidden.txt is private and not in this checkout, so the APKs were not searched for personal data"
fi

printf '\nReleased %s:\n' "$version"
for kind in foss compatible compatible-32bit foss-x86_64 compatible-x86_64; do printf '  %s\n' "$PWD/$out-$kind.apk"; done
