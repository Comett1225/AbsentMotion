#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
APP="${1:?usage: macos/bundle_check.sh <AbsentMotion.app> [gradle test arguments]}"
shift
RES="$APP/Contents/app/resources"
LIB="$RES/libamdesktop.dylib"
fail() { echo "$*" >&2; exit 1; }
for tool in ffmpeg ffprobe; do
    [ -x "$RES/$tool" ] || fail "$RES/$tool is missing or cannot run"
    "$RES/$tool" -hide_banner -version | head -n 1
done
[ -f "$RES/ffmpeg-LICENSE.txt" ] || fail "ffmpeg-LICENSE.txt is missing"
otool -L "$LIB" | grep -q '@rpath/libavcodec\.' || fail "libamdesktop.dylib does not link FFmpeg"
for lib in $(otool -L "$LIB" | sed -n -E 's#^[[:space:]]*@rpath/(lib(av|sw)[a-z]+\.[0-9]+\.dylib).*#\1#p'); do
    [ -f "$RES/$lib" ] || fail "$lib is linked but not in the app"
    echo "links $lib"
done
for symbol in media_FfampNative_probe pc_engine_DesktopNative_ffampVideoOpen pc_engine_DesktopNative_videoOpen; do
    nm -gU "$LIB" | grep -q "_Java_app_absentmotion_$symbol\$" || fail "libamdesktop.dylib has no $symbol"
done
echo "libamdesktop.dylib carries FFamp and the FFmpeg video path"
ARCH="$(lipo -archs "$LIB")"
[ "$ARCH" = x86_64 ] && ARCH=x64
cd "$ROOT/pc"
./gradlew -Ptarget=macos-$ARCH -PmediaReader=ffamp test -x nativeBuild --tests 'app.absentmotion.media.*' "$@"
