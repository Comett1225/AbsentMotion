#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
case "$(uname -m)" in
    aarch64 | arm64) ARCH=arm64 ;;
    x86_64 | amd64) ARCH=x86_64 ;;
    *) echo "linux/distro_check.sh: unsupported machine $(uname -m)" >&2; exit 1 ;;
esac
TARBALL=""
case "${1:-}" in
    *.tar.gz) TARBALL="$1"; shift ;;
esac
if [ -z "$TARBALL" ]; then
    for candidate in "$ROOT"/pc/build/linux-release/AbsentMotion-*-linux-"$ARCH".tar.gz; do
        if [ -f "$candidate" ]; then TARBALL="$candidate"; fi
    done
fi
if [ ! -f "$TARBALL" ]; then
    echo "usage: linux/distro_check.sh [AbsentMotion-<version>-linux-<arch>.tar.gz] [distro ...]" >&2
    echo "distros: ubuntu-22.04 ubuntu-24.04 debian-12 mint-22 fedora arch opensuse" >&2
    exit 2
fi
TARBALL="$(CDPATH= cd -- "$(dirname "$TARBALL")" && pwd)/$(basename "$TARBALL")"
command -v docker >/dev/null 2>&1 || { echo "linux/distro_check.sh needs docker" >&2; exit 1; }

APT="libasound2 libfontconfig1 libfreetype6 libgl1 libx11-6 libxext6 libxi6 libxrender1 libxtst6 zlib1g"
DNF="alsa-lib fontconfig freetype libglvnd-glx libX11 libXext libXi libXrender libXtst zlib"
PACMAN="alsa-lib fontconfig freetype2 libglvnd libx11 libxext libxi libxrender libxtst zlib"
ZYPPER="libasound2 fontconfig libfreetype6 libGL1 libX11-6 libXext6 libXi6 libXrender1 libXtst6 libz1"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM
cat > "$WORK/inside.sh" <<'EOF'
set -eu
FAMILY="$1"
DEPS="$2"
step() { printf '%s\n' "STEP $1"; }
step "install dependencies"
case "$FAMILY" in
    apt)
        apt-get update -qq >/dev/null
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends $DEPS xvfb fonts-dejavu-core >/dev/null
        ;;
    dnf) dnf install -y -q $DEPS tar gzip xorg-x11-server-Xvfb dejavu-sans-fonts >/dev/null ;;
    pacman) pacman -Sy --noconfirm --needed $DEPS tar gzip xorg-server-xvfb ttf-dejavu >/dev/null ;;
    zypper) zypper -n -q install --no-recommends $DEPS tar gzip xorg-x11-server-Xvfb dejavu-fonts >/dev/null ;;
esac
step "unpack and install.sh"
mkdir -p /tmp/unpack
tar -xzf /am.tar.gz -C /tmp/unpack
/tmp/unpack/AbsentMotion/install.sh
APP="$HOME/.local/bin/absentmotion"
FF="$HOME/.local/opt/absentmotion/lib/app/resources/ffmpeg"
FP="$HOME/.local/opt/absentmotion/lib/app/resources/ffprobe"
test -x "$APP"
test -f "${XDG_DATA_HOME:-$HOME/.local/share}/applications/absentmotion.desktop"
step "render without a display"
mkdir -p /tmp/render
for scene in media text shapes; do
    cp "/testdata/scenes/$scene.xml" /tmp/render/
done
ln -s /testdata /tmp/render/testdata
for scene in media text shapes; do
    env -u DISPLAY timeout 300 "$APP" --render "/tmp/render/$scene.xml" "/tmp/render/$scene.png" 1000
    test -s "/tmp/render/$scene.png"
done
step "export with the bundled ffmpeg"
SIZE="$("$FP" -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x /tmp/render/media.png)"
"$FF" -v error -i /tmp/render/media.png -frames:v 1 -f rawvideo -pix_fmt rgb0 -y /tmp/render/frame.raw
"$FF" -v error -f rawvideo -pix_fmt rgb0 -s "$SIZE" -r 30 -stream_loop 59 -i /tmp/render/frame.raw \
    -vf "crop=trunc(iw/2)*2:trunc(ih/2)*2,scale=out_color_matrix=bt709:out_range=tv" \
    -c:v libx264 -preset veryfast -b:v 4M -g 30 -pix_fmt yuv420p -f mp4 -movflags +faststart -y /tmp/render/export.mp4
FRAMES="$("$FP" -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 /tmp/render/export.mp4)"
test "$FRAMES" = 60
printf 'VA-API encoders in the bundled ffmpeg: %s\n' "$("$FF" -hide_banner -encoders 2>/dev/null | grep -c _vaapi || true)"
step "start the app on a virtual display"
Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >/dev/null 2>&1 &
XVFB=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if [ -e /tmp/.X11-unix/X99 ]; then break; fi
    sleep 1
done
DISPLAY=:99 timeout 300 "$APP" --warmup
kill "$XVFB" 2>/dev/null || true
step "uninstall"
/tmp/unpack/AbsentMotion/install.sh --uninstall
test ! -e "$HOME/.local/opt/absentmotion"
test ! -e "$APP"
printf '%s\n' "ALL STEPS PASSED"
EOF

run() {
    name="$1" image="$2" family="$3" deps="$4"
    log="$WORK/$name.log"
    if docker run --rm --platform "linux/$([ "$ARCH" = arm64 ] && echo arm64 || echo amd64)" \
        -v "$TARBALL:/am.tar.gz:ro" -v "$ROOT/testdata:/testdata:ro" -v "$WORK/inside.sh:/inside.sh:ro" \
        "$image" sh /inside.sh "$family" "$deps" >"$log" 2>&1 && grep -q "ALL STEPS PASSED" "$log"; then
        printf '%-14s PASS\n' "$name"
    else
        printf '%-14s FAIL at "%s"\n' "$name" "$(grep '^STEP ' "$log" | tail -n 1 | cut -c6-)"
        tail -n 15 "$log" | sed 's/^/    /'
        FAILED=1
    fi
}

skip() { printf '%-14s SKIP (%s)\n' "$1" "$2"; }

FAILED=0
WANTED="${*:-ubuntu-22.04 ubuntu-24.04 debian-12 mint-22 fedora arch opensuse}"
echo "Checking $(basename "$TARBALL") on: $WANTED"
for distro in $WANTED; do
    case "$distro" in
        ubuntu-22.04) run "$distro" ubuntu:22.04 apt "$APT" ;;
        ubuntu-24.04) run "$distro" ubuntu:24.04 apt "$APT" ;;
        debian-12) run "$distro" debian:12 apt "$APT" ;;
        mint-22) if [ "$ARCH" = x86_64 ]; then run "$distro" linuxmintd/mint22-amd64 apt "$APT"; else run "mint-22-base" ubuntu:24.04 apt "$APT"; fi ;;
        fedora) run "$distro" fedora:latest dnf "$DNF" ;;
        arch) if [ "$ARCH" = x86_64 ]; then run "$distro" archlinux:latest pacman "$PACMAN"; else skip "$distro" "no official arm64 image"; fi ;;
        opensuse) run "$distro" opensuse/tumbleweed zypper "$ZYPPER" ;;
        *) echo "unknown distro $distro" >&2; FAILED=1 ;;
    esac
done
exit "$FAILED"
