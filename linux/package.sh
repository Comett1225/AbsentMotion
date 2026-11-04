#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: linux/package.sh <version, e.g. 5.0.0-pre>}"
case "$(uname -m)" in
    aarch64|arm64) ARCH=arm64; APPIMAGE_ARCH=aarch64 ;;
    x86_64|amd64) ARCH=x86_64; APPIMAGE_ARCH=x86_64 ;;
    *) echo "linux/package.sh: unsupported machine $(uname -m)" >&2; exit 1 ;;
esac
DEPENDS="libc6 (>= 2.35), libstdc++6, libgcc-s1, libasound2, libfontconfig1, libfreetype6, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, zlib1g, xdg-utils"
RECOMMENDS="libvulkan1, libva2, libva-drm2, libva-x11-2, libdrm2, xdg-user-dirs"
BINARIES="$ROOT/pc/build/compose/binaries/main"
OUT="$ROOT/pc/build/linux-release"
WORK="$ROOT/pc/build/linux-package"
rm -rf "$WORK"
mkdir -p "$OUT" "$WORK"

DEB="$(ls "$BINARIES"/deb/*.deb)"
dpkg-deb -R "$DEB" "$WORK/deb"
sed -i -e "s/^Depends:.*/Depends: $DEPENDS\nRecommends: $RECOMMENDS/" "$WORK/deb/DEBIAN/control"
sed -i -e 's|^xdg-[a-z-]* .*|& \|\| true|' "$WORK/deb/DEBIAN/postinst" "$WORK/deb/DEBIAN/prerm"
sed -i -e 's|^Exec=.*|& %F|' "$WORK"/deb/opt/absentmotion/lib/*.desktop
sed -i -e '/<mime-type type="text\/xml">/,/<\/mime-type>/d' "$WORK"/deb/opt/absentmotion/lib/*-MimeInfo.xml
chmod 755 "$WORK/deb/opt/absentmotion/lib/app/resources/ffmpeg" "$WORK/deb/opt/absentmotion/lib/app/resources/ffprobe"
dpkg-deb --root-owner-group -Zxz -b "$WORK/deb" "$OUT/AbsentMotion-$VERSION-linux-$ARCH.deb"

APPDIR="$WORK/AbsentMotion.AppDir"
cp -a "$BINARIES/app/AbsentMotion" "$APPDIR"
chmod 755 "$APPDIR/lib/app/resources/ffmpeg" "$APPDIR/lib/app/resources/ffprobe"
printf '#!/bin/sh\nHERE="$(dirname "$(readlink -f "$0")")"\nexec "$HERE/bin/AbsentMotion" "$@"\n' > "$APPDIR/AppRun"
chmod 755 "$APPDIR/AppRun"
printf '[Desktop Entry]\nType=Application\nName=AbsentMotion\nComment=Video and motion graphics editor\nExec=AbsentMotion %%F\nIcon=absentmotion\nCategories=AudioVideo;Video;\nMimeType=application/x-absentmotion-project;\nTerminal=false\n' > "$APPDIR/absentmotion.desktop"
cp "$ROOT/linux/AbsentMotion.png" "$APPDIR/absentmotion.png"
cp "$ROOT/linux/AbsentMotion.png" "$APPDIR/.DirIcon"
ARCH="$APPIMAGE_ARCH" appimagetool --no-appstream --runtime-file "${APPIMAGE_RUNTIME:?set APPIMAGE_RUNTIME to the AppImage type 2 runtime}" "$APPDIR" "$OUT/AbsentMotion-$VERSION-linux-$ARCH.AppImage"
rm -rf "$WORK"
cd "$OUT" && sha256sum "AbsentMotion-$VERSION-linux-$ARCH.deb" "AbsentMotion-$VERSION-linux-$ARCH.AppImage"
