#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
. tools/env.sh
[ -f third_party/skia/out/android-arm64-compat/libskia.a ] || sh tools/build_skia.sh compat
./gradlew :app:assembleCompatRelease -q
version=$(sed -n 's/^versionName=//p' version.properties)
mkdir -p build/release
cp "$(ls -t app/build/outputs/apk/compat/release/*.apk | head -1)" "build/release/AbsentMotion-$version-compatible.apk"
echo "build_compat.sh: $PWD/build/release/AbsentMotion-$version-compatible.apk"
