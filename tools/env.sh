am_jbr21() { [ -x "$1/bin/java" ] && grep -q '^JAVA_VERSION="21' "$1/release" 2>/dev/null && grep -q '^IMPLEMENTOR="JetBrains' "$1/release" 2>/dev/null; }
am_find_jbr21() {
    [ -n "${ZSH_VERSION:-}" ] && setopt local_options null_glob
    for root in "$HOME/Library/Java/JavaVirtualMachines" /Library/Java/JavaVirtualMachines "$HOME/.jdks" /usr/lib/jvm /opt; do
        [ -d "$root" ] || continue
        for dir in "$root"/*; do
            [ -d "$dir" ] || continue
            for candidate in "$dir/Contents/Home" "$dir"; do
                if am_jbr21 "$candidate"; then export JAVA_HOME="$candidate"; return 0; fi
            done
        done
    done
    return 1
}
if [ -z "${JAVA_HOME:-}" ] || ! am_jbr21 "$JAVA_HOME"; then
    am_find_jbr21 || true
fi
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
