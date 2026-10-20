#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. tools/env.sh
out=build/tracker-host
mkdir -p "$out"
cxx=${CXX:-clang++}
source=app/src/main/cpp/tracker

printf '== Tracker: C++20 build as in the app\n'
"$cxx" -std=c++20 -O2 -Wall -Wextra -Werror -fsyntax-only "$source/tracker.cpp"

printf '== Tracker: host tests\n'
"$cxx" -std=c++17 -O2 -Wall -Wextra -Werror -I "$source" "$source/tracker.cpp" tools/tracker_test.cpp -o "$out/tracker_test"
"$out/tracker_test"

printf '\ntracker_check.sh: the tracker passes its host tests.\n'
