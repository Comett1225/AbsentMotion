#!/bin/sh
set -eu
SOURCE="$(dirname "$(readlink -f "$0")")"
: "${HOME:?HOME is not set}"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
TARGET="$HOME/.local/opt/absentmotion"
BIN="$HOME/.local/bin"
DESKTOP="$DATA/applications/absentmotion.desktop"
ICON="$DATA/icons/hicolor/256x256/apps/absentmotion.png"
MIME="$DATA/mime/packages/absentmotion.xml"

refresh() {
    if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database -q "$DATA/applications" 2>/dev/null || true; fi
    if command -v update-mime-database >/dev/null 2>&1; then update-mime-database "$DATA/mime" >/dev/null 2>&1 || true; fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then gtk-update-icon-cache -q -t "$DATA/icons/hicolor" >/dev/null 2>&1 || true; fi
}

case "${1:-}" in
    --uninstall)
        rm -rf "$TARGET" "${XDG_CACHE_HOME:-$HOME/.cache}/absentmotion"
        rm -f "$DESKTOP" "$ICON" "$MIME"
        if [ -L "$BIN/absentmotion" ]; then rm -f "$BIN/absentmotion"; fi
        refresh
        echo "AbsentMotion was removed. Your projects stay in ${XDG_DATA_HOME:-$HOME/.local/share}/absentmotion."
        exit 0
        ;;
    "") ;;
    *)
        echo "usage: $0 [--uninstall]" >&2
        exit 2
        ;;
esac

if [ ! -x "$SOURCE/bin/AbsentMotion" ]; then
    echo "Run install.sh from the unpacked AbsentMotion folder." >&2
    exit 1
fi
case "$TARGET" in
    *[\"\`\$\\%]*)
        echo "Your home folder path has a character a menu entry cannot hold. Start AbsentMotion with $SOURCE/bin/absentmotion.sh instead." >&2
        exit 1
        ;;
esac

if [ "$SOURCE" != "$TARGET" ]; then
    mkdir -p "$(dirname "$TARGET")"
    rm -rf "$TARGET.new"
    cp -a "$SOURCE" "$TARGET.new"
    rm -rf "$TARGET"
    mv "$TARGET.new" "$TARGET"
fi
chmod 755 "$TARGET/bin/AbsentMotion" "$TARGET/bin/absentmotion.sh" "$TARGET/install.sh" "$TARGET/lib/app/resources/ffmpeg" "$TARGET/lib/app/resources/ffprobe"

mkdir -p "$(dirname "$DESKTOP")" "$(dirname "$ICON")" "$(dirname "$MIME")" "$BIN"
cp "$TARGET/lib/AbsentMotion.png" "$ICON"
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=AbsentMotion
Comment=Video and motion graphics editor
Exec="$TARGET/bin/absentmotion.sh" %F
Icon=$ICON
Categories=AudioVideo;Video;
MimeType=application/x-absentmotion-project;
StartupWMClass=app-absentmotion-pc-MainKt
Terminal=false
EOF
cat > "$MIME" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-absentmotion-project">
    <comment>AbsentMotion project</comment>
    <glob pattern="*.absproj"/>
    <glob pattern="*.amproj"/>
  </mime-type>
</mime-info>
EOF
ln -sf "$TARGET/bin/absentmotion.sh" "$BIN/absentmotion"
refresh

echo "AbsentMotion is installed in $TARGET."
echo "Start it from your app menu, or run: $BIN/absentmotion"
echo "Remove it with: $TARGET/install.sh --uninstall"
