#!/bin/sh
set -u
cd "$(dirname "$0")/.."
. tools/env.sh
log="build/check.log"
mkdir -p build

step() { printf '\n== %s\n' "$1"; }
fail() { printf '\ncheck.sh: FAILED: %s (full output: %s)\n' "$1" "$log" >&2; exit 1; }

step "Android: compile (play and foss flavours)"
./gradlew :app:compilePlayDebugKotlin :app:compileFossDebugKotlin -q >"$log" 2>&1 || { grep -E '^e: ' "$log" | sed 's|file://[^ ]*/app/src/main/kotlin/app/absentmotion/||' >&2; fail "the Android build does not compile"; }

step "Licence asset up to date"
python3 tools/gen_licenses.py --check >"$log" 2>&1 || { cat "$log" >&2; fail "app/src/main/assets/licenses.txt is out of date: run python3 tools/gen_licenses.py"; }

step "Built-in fonts and catalogue up to date"
python3 tools/gen_fonts.py --check >"$log" 2>&1 || { cat "$log" >&2; fail "the built-in fonts or the font catalogue are out of date: see tools/gen_fonts.py"; }

step "Android: unit tests"
./gradlew :app:testPlayDebugUnitTest :app:testFossDebugUnitTest -q >"$log" 2>&1 || { grep -E 'FAILED|Test.*>' "$log" | head -40 >&2; fail "Android unit tests"; }

printf '\ncheck.sh: the app compiles in both flavours and the tests pass.\n'
