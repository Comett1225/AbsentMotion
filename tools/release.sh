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

printf '\n== Android: release APKs (play, and the free build without Google libraries)\n'
./gradlew :app:assemblePlayRelease :app:assembleFossRelease -q
mkdir -p build/release
mv app/build/outputs/apk/play/release/app-play-release.apk "build/release/AbsentMotion-$version.apk"
mv app/build/outputs/apk/foss/release/app-foss-release.apk "build/release/AbsentMotion-$version-foss.apk"

printf '\nReleased %s:\n  %s\n  %s\n' "$version" "$PWD/build/release/AbsentMotion-$version.apk" "$PWD/build/release/AbsentMotion-$version-foss.apk"
