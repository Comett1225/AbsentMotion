#!/bin/sh
set -u
cd "$(dirname "$0")/.."
. tools/env.sh
dir="build/check"
mkdir -p "$dir"
mode=full
for arg in "$@"; do
    case "$arg" in
        --quick) mode=quick ;;
        --full) mode=full ;;
        *) echo "usage: tools/check.sh [--quick|--full]   (quick: one flavour; full: all flavours, lint, API floor)" >&2; exit 2 ;;
    esac
done
failed=0
skipped=""
fail() { printf 'check.sh: FAILED: %s\n' "$1" >&2; failed=$((failed + 1)); }
native_libs() {
    newest=$(ls -t "app/build/intermediates/merged_native_libs/$1/"*/out/lib/arm64-v8a/*.so 2>/dev/null | head -n 1)
    if [ -z "$newest" ]; then
        printf 'skipped: the %s native libraries are not built (./gradlew :app:%s builds them)\n' "$1" "$2"
        skipped="$skipped $1"
        return 1
    fi
    libs=$(dirname "$newest")
    for library in "$libs"/*.so; do
        case "${library##*/}" in
            libamengine.so)
                newer=$(find app/src/main/cpp third_party/rnnoise -type f ! -name ai_jni.cpp ! -name whisper_jni.cpp ! -name lame_jni.cpp -newer "$library" 2>/dev/null | head -n 1)
                ;;
            libabsent_ai.so) sources="app/src/main/cpp/ai_jni.cpp third_party/rife_warp third_party/ncnn/src third_party/ncnn/glslang" ;;
            libwhisper_jni.so|libwhisper_jni_fast.so) sources="app/src/main/cpp/whisper_jni.cpp third_party/whisper.cpp/src third_party/whisper.cpp/include third_party/whisper.cpp/ggml" ;;
            liblame_jni.so) sources="app/src/main/cpp/lame_jni.cpp third_party/lame/include" ;;
            libmp3lame.so) sources="third_party/lame" ;;
            *) continue ;;
        esac
        if [ "${library##*/}" != libamengine.so ]; then
            newer=$(find app/src/main/cpp/CMakeLists.txt $sources -type f -newer "$library" 2>/dev/null | head -n 1)
        fi
        if [ -n "$newer" ]; then
            fail "the $1 native libraries are older than the native code ($library), so their API floor would describe an old build (./gradlew :app:$2 rebuilds them)"
            return 1
        fi
    done
}
start=$(date +%s)

STATIC="licences|python3 tools/gen_licenses.py --check|python3 tools/gen_licenses.py
fonts|python3 tools/gen_fonts.py --check|see tools/gen_fonts.py
site-help|python3 tools/site_build.py --check|python3 tools/site_build.py
3d-effects|python3 tools/gen_3d_effects.py --check|python3 tools/gen_3d_effects.py
translations|python3 tools/i18n_extract.py check|python3 tools/i18n_translate.py
effect-lint|python3 tools/effect_lint.py|fix the effect JSON it lists
comments|python3 tools/comment_check.py|the project ships without comments
publish|if [ -f tools/publish_forbidden.txt ]; then sh tools/publish_check.sh; else echo 'skipped: tools/publish_forbidden.txt is private and not in this checkout'; fi|fix what it lists, never loosen it
ui-contract|python3 tools/ui_contract_check.py|update the contract or the UI
remaining-contract|python3 tools/remaining_contract_check.py|update the contract or the code
tool-regressions|python3 tools/bug_regression_check.py|the tool scripts regressed
tier|python3 tools/compat/tier_check.py|gate the feature with Tier.has or Tier.choice (app/src/main/kotlin/app/absentmotion/Tier.kt)"

printf '== Native audio effects and text animation: host build and tests\n'
sh tools/audio_dsp.sh --lib >"$dir/audio-dsp.log" 2>&1 || { tail -n 15 "$dir/audio-dsp.log" >&2; fail "the audio DSP host tests"; }
sh tools/textanim.sh >"$dir/textanim.log" 2>&1 || { tail -n 15 "$dir/textanim.log" >&2; fail "the text animation host tests"; }

printf '\n== Static checks (in parallel)\n'
while IFS='|' read -r name command hint; do
    ( sh -c "$command" >"$dir/$name.log" 2>&1; echo $? >"$dir/$name.status" ) &
done <<EOF
$STATIC
EOF

if [ "$mode" = quick ]; then
    printf '\n== Android: compile and unit tests (foss)\n'
    ./gradlew :app:compileFossDebugKotlin :app:testFossDebugUnitTest -q >"$dir/gradle.log" 2>&1 || { grep -E '^e: |FAILED|Test.*>' "$dir/gradle.log" | sed 's|file://[^ ]*/app/src/main/kotlin/app/absentmotion/||' | head -40 >&2; fail "Android compile or unit tests (full output: $dir/gradle.log)"; }
else
    printf '\n== Android: compile (play, foss and compat), unit tests, lint\n'
    tasks=":app:compilePlayDebugKotlin :app:compileFossDebugKotlin :app:compileCompatDebugKotlin :app:testFossDebugUnitTest :app:testCompatDebugUnitTest"
    [ -f app/lint-baseline.xml ] && tasks="$tasks :app:lintFossDebug :app:lintCompatDebug"
    ./gradlew $tasks -q >"$dir/gradle.log" 2>&1 || { grep -E '^e: |FAILED|Test.*>|Error:|error:' "$dir/gradle.log" | sed 's|file://[^ ]*/app/src/main/kotlin/app/absentmotion/||' | head -40 >&2; fail "Android compile, unit tests or lint (full output: $dir/gradle.log)"; }
    printf '\n== Native libraries: lowest Android version they need\n'
    if native_libs fossRelease mergeFossReleaseNativeLibs; then
        python3 tools/api_floor.py "$libs" >"$dir/api-floor.log" 2>&1 || { cat "$dir/api-floor.log" >&2; fail "a native library needs a newer Android than minSdk"; }
        tail -n 2 "$dir/api-floor.log"
    fi
    printf '\n== Native libraries of the compat build: they must load on Android 9\n'
    if native_libs compatRelease mergeCompatReleaseNativeLibs; then
        python3 tools/api_floor.py "$libs" --min 28 >"$dir/api-floor-compat.log" 2>&1 || { cat "$dir/api-floor-compat.log" >&2; fail "a compat native library needs a newer Android than 9"; }
        tail -n 2 "$dir/api-floor-compat.log"
    fi
fi

wait
static_failed=0
while IFS='|' read -r name command hint; do
    status=$(cat "$dir/$name.status" 2>/dev/null || echo 1)
    if [ "$status" != 0 ]; then
        printf '\n-- %s failed (%s): %s\n' "$name" "$hint" "$command" >&2
        tail -n 15 "$dir/$name.log" >&2
        static_failed=1
    fi
done <<EOF
$STATIC
EOF
[ "$static_failed" = 1 ] && fail "static checks (logs in $dir)"
rm -f "$dir"/*.status

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
