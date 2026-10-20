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

printf '== Tracker: host JNI library and the Kotlin end-to-end test\n'
"$cxx" -std=c++17 -O2 -Wall -Werror -shared -fPIC -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/darwin" -I "$JAVA_HOME/include/linux" \
    -I app/src/main/cpp "$source/tracker.cpp" app/src/main/cpp/tracker_jni.cpp -o "$out/libtracker_host.so"
./gradlew :app:testFossDebugUnitTest --tests 'app.absentmotion.media.MotionTrackerJniTest' -q
grep -q 'skipped="0"' app/build/test-results/testFossDebugUnitTest/TEST-app.absentmotion.media.MotionTrackerJniTest.xml

printf '\ntracker_check.sh: the tracker passes its host tests and the Kotlin JNI path.\n'
