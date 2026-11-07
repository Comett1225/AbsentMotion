#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: linux/package.sh <version, e.g. 5.0.0>}"
case "$(uname -m)" in
    aarch64|arm64) ARCH=arm64; APPIMAGE_ARCH=aarch64 ;;
    x86_64|amd64) ARCH=x86_64; APPIMAGE_ARCH=x86_64 ;;
    *) echo "linux/package.sh: unsupported machine $(uname -m)" >&2; exit 1 ;;
esac
DEPENDS="libc6 (>= 2.35), libstdc++6, libgcc-s1, libasound2, libfontconfig1, libfreetype6, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, zlib1g, xdg-utils"
RECOMMENDS="libvulkan1, libva2, libva-drm2, libva-x11-2, libdrm2, xdg-user-dirs, fonts-noto-cjk, x11-xserver-utils, zenity | kdialog | xdg-desktop-portal"
BINARIES="$ROOT/pc/build/compose/binaries/main"
OUT="$ROOT/pc/build/linux-release"
WORK="$ROOT/pc/build/linux-package"
rm -rf "$WORK"
mkdir -p "$OUT" "$WORK"
APP="$WORK/AbsentMotion"
cp -a "$BINARIES/app/AbsentMotion" "$APP"
install -m 755 "$ROOT/linux/start.sh" "$APP/bin/absentmotion.sh"
chmod 755 "$APP/lib/app/resources/ffmpeg" "$APP/lib/app/resources/ffprobe"

DEB="$(ls "$BINARIES"/deb/*.deb)"
dpkg-deb -R "$DEB" "$WORK/deb"
sed -i -e "s/^Depends:.*/Depends: $DEPENDS\nRecommends: $RECOMMENDS/" "$WORK/deb/DEBIAN/control"
install -m 755 "$ROOT/linux/start.sh" "$WORK/deb/opt/absentmotion/bin/absentmotion.sh"
sed -i -e 's|^xdg-[a-z-]* .*|& \|\| true|' "$WORK/deb/DEBIAN/postinst" "$WORK/deb/DEBIAN/prerm"
DESKTOP="$(basename "$WORK"/deb/opt/absentmotion/lib/*.desktop)"
MENU="/usr/share/applications/$DESKTOP"
sed -i -e "/^xdg-desktop-menu install /a [ -e $MENU ] || { install -D -m 644 /opt/absentmotion/lib/$DESKTOP $MENU \&\& { update-desktop-database -q /usr/share/applications || true; }; }" "$WORK/deb/DEBIAN/postinst"
sed -i -e "/^xdg-desktop-menu uninstall /a rm -f $MENU" "$WORK/deb/DEBIAN/prerm"
sed -i -e 's|^Exec=.*|Exec=/opt/absentmotion/bin/absentmotion.sh %F|' -e 's|^MimeType=.*|MimeType=application/x-absentmotion-project;\nStartupWMClass=app-absentmotion-pc-MainKt|' "$WORK"/deb/opt/absentmotion/lib/*.desktop
sed -i -e '/<mime-type type="text\/xml">/,/<\/mime-type>/d' "$WORK"/deb/opt/absentmotion/lib/*-MimeInfo.xml
chmod 755 "$WORK/deb/opt/absentmotion/lib/app/resources/ffmpeg" "$WORK/deb/opt/absentmotion/lib/app/resources/ffprobe"
dpkg-deb --root-owner-group -Zxz -b "$WORK/deb" "$OUT/AbsentMotion-$VERSION-linux-$ARCH.deb"

APPDIR="$WORK/AbsentMotion.AppDir"
cp -a "$APP" "$APPDIR"
printf '#!/bin/sh\nHERE="$(dirname "$(readlink -f "$0")")"\nexec "$HERE/bin/absentmotion.sh" "$@"\n' > "$APPDIR/AppRun"
chmod 755 "$APPDIR/AppRun"
printf '[Desktop Entry]\nType=Application\nName=AbsentMotion\nComment=Video and motion graphics editor\nExec=AbsentMotion %%F\nIcon=absentmotion\nCategories=AudioVideo;Video;\nMimeType=application/x-absentmotion-project;\nStartupWMClass=app-absentmotion-pc-MainKt\nTerminal=false\n' > "$APPDIR/absentmotion.desktop"
cp "$ROOT/linux/AbsentMotion.png" "$APPDIR/absentmotion.png"
cp "$ROOT/linux/AbsentMotion.png" "$APPDIR/.DirIcon"
ARCH="$APPIMAGE_ARCH" appimagetool --no-appstream --runtime-file "${APPIMAGE_RUNTIME:?set APPIMAGE_RUNTIME to the AppImage type 2 runtime}" "$APPDIR" "$OUT/AbsentMotion-$VERSION-linux-$ARCH.AppImage"
install -m 755 "$ROOT/linux/install.sh" "$APP/install.sh"
tar -C "$WORK" --owner=0 --group=0 --numeric-owner -czf "$OUT/AbsentMotion-$VERSION-linux-$ARCH.tar.gz" AbsentMotion
rm -rf "$WORK"
cd "$OUT" && sha256sum "AbsentMotion-$VERSION-linux-$ARCH.deb" "AbsentMotion-$VERSION-linux-$ARCH.AppImage" "AbsentMotion-$VERSION-linux-$ARCH.tar.gz"
