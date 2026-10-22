#!/bin/sh
set -u
cd "$(dirname "$0")/../.."
. tools/env.sh
failed=0
fail() { printf 'compat_check.sh: FAILED: %s\n' "$1" >&2; failed=$((failed + 1)); }
apk=$(ls -t app/build/outputs/apk/compat/release/*.apk 2>/dev/null | head -1)
[ -n "$apk" ] || { echo "compat_check.sh: build it first: tools/compat/build_compat.sh" >&2; exit 2; }

printf '== Native libraries: lowest Android version (must be 28)\n'
python3 tools/api_floor.py "$apk" --min 28 || fail "a native library needs a newer Android than 9"

printf '\n== Lint (NewApi and the rest) at minSdk 28\n'
./gradlew :app:lintCompatDebug -q >build/compat-lint.log 2>&1 || { grep -E 'Error:' app/build/reports/lint-results-compatDebug.txt | head -40 >&2; fail "lint on the compat build (report: app/build/reports/lint-results-compatDebug.html)"; }

printf '\n== Features left out of Compatible are gated\n'
python3 tools/compat/tier_check.py || fail "a left-out feature is not gated"

printf '\n== Publish check on the APK\n'
if [ -f tools/publish_forbidden.txt ]; then sh tools/publish_check.sh --apk "$apk" || fail "publish_check on $apk"
else echo "skipped: tools/publish_forbidden.txt is private and not in this checkout"; fi

[ "$failed" -eq 0 ] || { printf '\ncompat_check.sh: %s check(s) failed\n' "$failed" >&2; exit 1; }
printf '\ncompat_check.sh: all checks passed\n'
