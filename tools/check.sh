#!/bin/sh
set -u
cd "$(dirname "$0")/.."
. tools/env.sh
dir="build/check"
mkdir -p "$dir"
find "$dir" -maxdepth 1 -name 'run.*' -mtime +1 -exec rm -rf {} + 2>/dev/null
status=$(mktemp -d "$dir/run.XXXXXX") || exit 1
held=""
trap 'rm -rf "$status"; [ -z "$held" ] || rm -f "$held"' EXIT
trap 'exit 130' INT TERM
mode=full
for arg in "$@"; do
    case "$arg" in
        --quick) mode=quick ;;
        --full) mode=full ;;
        *) echo "usage: tools/check.sh [--quick|--full]   (quick: one flavour, desktop compile; full: all flavours, lint, API floor, desktop tests)" >&2; exit 2 ;;
    esac
done
failed=0
skipped=""
fail() { printf 'check.sh: FAILED: %s\n' "$1" >&2; failed=$((failed + 1)); }
lock() {
    waited=""
    until ln -sn $$ "$dir/$1.lock" 2>/dev/null; do
        holder=$(readlink "$dir/$1.lock" 2>/dev/null)
        if kill -0 "$holder" 2>/dev/null && ! ps -o stat= -p "$holder" 2>/dev/null | grep -q '^Z'; then
            [ "$waited" = "$holder" ] || printf 'waiting for another check.sh (process %s) to finish its %s build in this folder\n' "$holder" "$1"
            waited=$holder
            sleep 3
        else
            rm -f "$dir/$1.lock"
        fi
    done
    held="$dir/$1.lock"
}
unlock() { rm -f "$held"; held=""; }
excerpt() {
    grep -E "$1" "$2" | sed 's|file://[^ ]*/shared/kotlin/app/absentmotion/||' | head -40 >&2
    sed -n '/What went wrong/,/^\* Try/p' "$2" | grep -v '^\* Try' | head -12 >&2
}
version_code=$(sed -n 's/^versionCode=//p' version.properties)
version_name=$(sed -n 's/^versionName=//p' version.properties)
[ -f "fastlane/metadata/android/en-US/changelogs/$version_code.txt" ] || fail "fastlane/metadata/android/en-US/changelogs/$version_code.txt is missing"
grep -q "^## \[$version_name\]" CHANGELOG.md || fail "CHANGELOG.md has no heading for $version_name"
native_libs() {
    newest=$(ls -t "android/build/intermediates/merged_native_libs/$1/"*/out/lib/"${3:-arm64-v8a}"/*.so 2>/dev/null | head -n 1)
    if [ -z "$newest" ]; then
        printf 'skipped: the %s native libraries are not built (./gradlew :app:%s builds them)\n' "$1" "$2"
        skipped="$skipped $1"
        return 1
    fi
    libs=$(dirname "$newest")
    for library in "$libs"/*.so; do
        case "${library##*/}" in
            libamengine.so)
                newer=$(find shared/cpp third_party/rnnoise -type f ! -name ai_jni.cpp ! -name whisper_jni.cpp ! -name lame_jni.cpp -newer "$library" 2>/dev/null | head -n 1)
                ;;
            libabsent_ai.so) sources="shared/cpp/ai_jni.cpp shared/cpp/cutout.cpp shared/cpp/cutout.h third_party/rife_warp third_party/ncnn/src third_party/ncnn/glslang" ;;
            libampy.so) sources="shared/cpp/py_bridge.c" ;;
            libwhisper_jni.so|libwhisper_jni_fast.so) sources="shared/cpp/whisper_jni.cpp third_party/whisper.cpp/src third_party/whisper.cpp/include third_party/whisper.cpp/ggml" ;;
            liblame_jni.so) sources="shared/cpp/lame_jni.cpp third_party/lame/include" ;;
            libmp3lame.so) sources="third_party/lame" ;;
            *) continue ;;
        esac
        if [ "${library##*/}" != libamengine.so ]; then
            newer=$(find shared/cpp/CMakeLists.txt $sources -type f -newer "$library" 2>/dev/null | head -n 1)
        fi
        if [ -n "$newer" ]; then
            fail "the $1 native libraries are older than the native code ($library), so their API floor would describe an old build (./gradlew :app:$2 rebuilds them)"
            return 1
        fi
    done
}
start=$(date +%s)

CHECKS="licences|python3 tools/gen_licenses.py --check|python3 tools/gen_licenses.py
fonts|python3 tools/gen_fonts.py --check|see tools/gen_fonts.py
site-help|python3 tools/site_build.py --check|python3 tools/site_build.py
alight-compat|python3 tools/alight_compat.py --check|python3 tools/alight_compat.py
3d-effects|python3 tools/gen_3d_effects.py --check|python3 tools/gen_3d_effects.py
test-scenes|python3 tools/gen_testscenes.py --check|python3 tools/gen_testscenes.py
translations|python3 tools/i18n_extract.py selftest && python3 tools/i18n_extract.py check|python3 tools/i18n_translate.py
effect-lint|python3 tools/effect_lint.py|fix the effect JSON it lists
shaders|if [ -x build/sksl_check/sksl_check ]; then sh tools/effectcheck.sh; else echo 'skipped: the shader checker is not built (tools/sksl_check/build.sh)'; fi|fix the effect shader it lists
comments|python3 tools/comment_check.py|the project ships without comments
publish|if [ -f tools/publish_forbidden.txt ]; then sh tools/publish_check.sh; else echo 'skipped: tools/publish_forbidden.txt is private and not in this checkout'; fi|fix what it lists, never loosen it
ui-contract|python3 tools/ui_contract_check.py|update the contract or the UI
remaining-contract|python3 tools/remaining_contract_check.py|update the contract or the code
tool-regressions|python3 tools/bug_regression_check.py|the tool scripts regressed
tier|python3 tools/compat/tier_check.py|gate the feature with Tier.has or Tier.choice (shared/kotlin/app/absentmotion/Tier.kt)
parity|python3 tools/parity_check.py|list the desktop gap in tools/parity.json, or build the feature for the desktop
cutout-clean|sh tools/cutout_clean.sh|the cut-out clean-up host test
textanim|sh tools/textanim.sh|the text animation host tests
particles|sh tools/particles.sh|the particle host tests
warp|sh tools/warp.sh|the path and puppet warp host tests
easing|python3 tools/easing_check.py|the easing curves against their reference samples
host-engine|if [ -f third_party/skia/out/mac-v2-2-arm64/libskia.a ]; then sh tools/render_bench/build.sh >/dev/null && python3 tools/pieces3d_check.py && python3 tools/crossing_check.py && python3 tools/puppet_check.py && python3 tools/crop_check.py && python3 tools/rays_check.py; else echo 'skipped: the host Skia build (third_party/skia/out/mac-v2-2-arm64) is missing'; fi|the 3D pieces, crossing layers, Puppet Pin, Crop and Rays checks against the host engine"

printf '== Static checks and host tests (in the background)\n'
while IFS='|' read -r name command hint; do
    ( sh -c "$command" >"$status/$name.log" 2>&1; echo $? >"$status/$name" ) &
done <<EOF
$CHECKS
EOF

printf '\n== Host libraries the unit tests load: audio effects, pivot engine, motion tracker\n'
lock android
sh tools/audio_dsp.sh --lib >"$status/audio-dsp.log" 2>&1 &
audio=$!
sh tools/tracker_check.sh --lib >"$status/tracker.log" 2>&1 &
tracker=$!
sh tools/pivot.sh --lib >"$status/pivot.log" 2>&1 || { tail -n 15 "$status/pivot.log" >&2; fail "the host engine library for the pivot tests"; }
wait "$audio" || { tail -n 15 "$status/audio-dsp.log" >&2; fail "the audio DSP host tests"; }
wait "$tracker" || { tail -n 15 "$status/tracker.log" >&2; fail "the motion tracker host tests"; }

if [ "$mode" = quick ]; then
    printf '\n== Android: compile and unit tests (foss)\n'
    ./gradlew :app:compileFossDebugKotlin :app:testFossDebugUnitTest -q >"$status/gradle.log" 2>&1 || { excerpt '^e: |FAILED|Test.*>' "$status/gradle.log"; fail "Android compile or unit tests (full output: $dir/gradle.log)"; }
    unlock
else
    printf '\n== Android: compile (foss, compat and compat32), unit tests, lint\n'
    tasks=":app:compileFossDebugKotlin :app:compileCompatDebugKotlin :app:compileCompat32DebugKotlin :app:testFossDebugUnitTest :app:testCompatDebugUnitTest"
    [ -f android/lint-baseline.xml ] && tasks="$tasks :app:lintFossDebug :app:lintCompatDebug"
    ./gradlew $tasks -q >"$status/gradle.log" 2>&1 || { excerpt '^e: |FAILED|Test.*>|Error:|error:' "$status/gradle.log"; fail "Android compile, unit tests or lint (full output: $dir/gradle.log)"; }
    unlock
    printf '\n== Native libraries: lowest Android version they need, 16 KB page alignment\n'
    if native_libs fossRelease mergeFossReleaseNativeLibs; then
        python3 tools/api_floor.py "$libs" >"$dir/api-floor.log" 2>&1 || { cat "$dir/api-floor.log" >&2; fail "a native library needs a newer Android than minSdk"; }
        tail -n 2 "$dir/api-floor.log"
        python3 tools/apk_check.py --libraries "$libs" >"$dir/page-size.log" 2>&1 || { cat "$dir/page-size.log" >&2; fail "a native library is not aligned for 16 KB memory pages"; }
        cat "$dir/page-size.log"
    fi
    printf '\n== Native libraries of the compat build: they must load on Android 8\n'
    if native_libs compatRelease mergeCompatReleaseNativeLibs; then
        python3 tools/api_floor.py "$libs" --min 26 >"$dir/api-floor-compat.log" 2>&1 || { cat "$dir/api-floor-compat.log" >&2; fail "a compat native library needs a newer Android than 8"; }
        tail -n 2 "$dir/api-floor-compat.log"
        python3 tools/apk_check.py --libraries "$libs" >"$dir/page-size-compat.log" 2>&1 || { cat "$dir/page-size-compat.log" >&2; fail "a compat native library is not aligned for 16 KB memory pages"; }
        cat "$dir/page-size-compat.log"
    fi
    printf '\n== Native libraries of the 32-bit compat build: they must load on Android 8\n'
    if native_libs compat32Release mergeCompat32ReleaseNativeLibs armeabi-v7a; then
        python3 tools/api_floor.py "$libs" --abi armeabi-v7a --min 26 >"$dir/api-floor-compat32.log" 2>&1 || { cat "$dir/api-floor-compat32.log" >&2; fail "a 32-bit compat native library needs a newer Android than 8"; }
        tail -n 2 "$dir/api-floor-compat32.log"
        python3 tools/apk_check.py --libraries "$libs" >"$dir/page-size-compat32.log" 2>&1 || { cat "$dir/page-size-compat32.log" >&2; fail "a 32-bit compat native library is not aligned for its memory pages"; }
        cat "$dir/page-size-compat32.log"
    fi
fi

lock desktop
if [ "$mode" = quick ]; then
    printf '\n== Desktop: compile\n'
    (cd pc && ./gradlew compileKotlin compileTestKotlin -q) >"$status/desktop.log" 2>&1 || { excerpt '^e: |error:' "$status/desktop.log"; fail "desktop compile (full output: $dir/desktop.log)"; }
else
    printf '\n== Desktop: compile and tests\n'
    (cd pc && ./gradlew test -q) >"$status/desktop.log" 2>&1 || { excerpt '^e: |error:|FAILED|Test.*>' "$status/desktop.log"; fail "desktop compile or tests (full output: $dir/desktop.log)"; }
fi
unlock

wait
static_failed=0
while IFS='|' read -r name command hint; do
    if [ "$(cat "$status/$name" 2>/dev/null || echo 1)" != 0 ]; then
        printf '\n-- %s failed (%s): %s\n' "$name" "$hint" "$command" >&2
        tail -n 15 "$status/$name.log" >&2
        static_failed=1
    fi
    grep -h '^skipped:' "$status/$name.log" 2>/dev/null
done <<EOF
$CHECKS
EOF
cp "$status"/*.log "$dir"/ 2>/dev/null
[ "$static_failed" = 1 ] && fail "static checks or host tests (logs in $dir)"

elapsed=$(( $(date +%s) - start ))
if [ "$failed" -gt 0 ]; then
    printf '\ncheck.sh: %s check(s) failed after %ss.\n' "$failed" "$elapsed" >&2
    exit 1
fi
if [ -n "$skipped" ]; then
    printf '\ncheck.sh: the full check is INCOMPLETE after %ss: the native API floor of%s was not checked, so it is not a release check (./gradlew :app:mergeFossReleaseNativeLibs :app:mergeCompatReleaseNativeLibs, then run it again).\n' "$elapsed" "$skipped" >&2
    exit 3
fi
printf '\ncheck.sh: all %s checks passed in %ss; the app compiles and the tests pass.\n' "$mode" "$elapsed"
