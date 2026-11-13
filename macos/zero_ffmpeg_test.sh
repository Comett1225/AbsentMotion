#!/bin/bash
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:?usage: macos/zero_ffmpeg_test.sh <work folder> [gradle test arguments]}"
shift
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd -P)"
hidden=()
for tool in ffmpeg ffprobe; do
    for found in $(which -a $tool 2> /dev/null || true) /opt/homebrew/bin/$tool /usr/local/bin/$tool; do
        [ -e "$found" ] || continue
        real=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$found")
        hidden+=("$(dirname "$(dirname "$real")")")
    done
done
for folder in "$ROOT"/pc/build/native*; do
    [ -d "$folder" ] && ls "$folder" | grep -q -E '^(ffmpeg|ffprobe|libav.*|libsw.*)$' && hidden+=("$(cd "$folder" && pwd -P)")
done
PROFILE="$WORK/zero.sb"
{
    echo "(version 1)"
    echo "(allow default)"
    [ ${#hidden[@]} -eq 0 ] || printf '%s\n' "${hidden[@]}" | sort -u | while read -r folder; do echo "(deny file-read* process-exec* file-map-executable (subpath \"$folder\"))"; done
} > "$PROFILE"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}/usr/bin:/bin:/usr/sbin:/sbin"
EVIDENCE="$WORK/blocked.txt"
{
    echo "PATH inside the run: $PATH"
    echo "ffmpeg on that PATH: $(command -v ffmpeg || echo none)"
    echo "ffprobe on that PATH: $(command -v ffprobe || echo none)"
    echo "folders blocked for reading, running and loading:"
    grep 'deny file-read' "$PROFILE" | sed 's/.*subpath "\(.*\)").*/  \1/' || true
    echo "each real FFmpeg program and library, tried from inside the blocked run:"
    [ ${#hidden[@]} -eq 0 ] || printf '%s\n' "${hidden[@]}" | sort -u | while read -r folder; do
        find "$folder" -maxdepth 2 -type f \( -name ffmpeg -o -name ffprobe -o -name 'libav*.dylib' -o -name 'libsw*.dylib' \) 2> /dev/null | while read -r file; do
            case "$file" in
                *.dylib) sandbox-exec -f "$PROFILE" /usr/bin/python3 -c 'import ctypes,sys; ctypes.CDLL(sys.argv[1])' "$file" > /dev/null 2>&1 && state=USABLE || state=blocked ;;
                *) sandbox-exec -f "$PROFILE" "$file" -version > /dev/null 2>&1 && state=USABLE || state=blocked ;;
            esac
            echo "  $state: $file"
        done
    done
    echo "library the tests load: $ROOT/pc/build/native/libamdesktop.dylib"
    otool -L "$ROOT/pc/build/native/libamdesktop.dylib" | grep -i -E 'libav|libsw|ffmpeg' || echo "  links no FFmpeg library"
} > "$EVIDENCE"
cat "$EVIDENCE"
if grep -q USABLE "$EVIDENCE"; then echo "a real FFmpeg file is still reachable, stopping"; exit 1; fi
cd "$ROOT/pc"
sandbox-exec -f "$PROFILE" ./gradlew --no-daemon test -x nativeBuild "$@"
