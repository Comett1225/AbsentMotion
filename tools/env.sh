if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$(/usr/libexec/java_home -v 21 2>/dev/null)" \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-21-openjdk-amd64; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
    done
fi
if [ -z "${SDK_ROOT:-}" ]; then
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate" ]; then SDK_ROOT="$candidate"; break; fi
    done
fi
if [ -z "${ADB:-}" ]; then
    if [ -n "${SDK_ROOT:-}" ] && [ -x "$SDK_ROOT/platform-tools/adb" ]; then ADB="$SDK_ROOT/platform-tools/adb"; else ADB="$(command -v adb || echo adb)"; fi
fi
export SDK_ROOT ADB
