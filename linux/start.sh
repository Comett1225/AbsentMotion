#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
OPTIONS=""
CACHE="${XDG_CACHE_HOME:-${HOME:+$HOME/.cache}}/absentmotion"
case "${APPIMAGE:-}:$CACHE" in
    ?*:* | :/absentmotion | *[[:space:]]* | *\'* | *\"* | *\\*) ;;
    *)
        KEY="$(stat -c '%n %s %Y' "$(dirname "$HERE")"/lib/app/*.jar 2>/dev/null | cksum | cut -d ' ' -f 1)"
        if [ -n "$KEY" ] && mkdir -p "$CACHE" 2>/dev/null && [ -w "$CACHE" ]; then
            for OLD in "$CACHE"/app-*.jsa; do
                if [ "$OLD" != "$CACHE/app-$KEY.jsa" ]; then rm -f "$OLD"; fi
            done
            OPTIONS="-XX:SharedArchiveFile=$CACHE/app-$KEY.jsa -XX:+AutoCreateSharedArchive"
        fi
        ;;
esac
case "${JAVA_TOOL_OPTIONS:-}" in
    *sun.java2d.uiScale*) ;;
    *)
        if [ -z "${GDK_SCALE:-}" ] && [ -n "${DISPLAY:-}" ] && command -v xrdb >/dev/null 2>&1; then
            SCALE="$(xrdb -query 2>/dev/null | LC_ALL=C awk '/^Xft\.dpi:/ { s = int($2 / 96 * 4 + 0.5) / 4; if (s > 1) print s; exit }')"
            if [ -n "$SCALE" ]; then OPTIONS="$OPTIONS -Dsun.java2d.uiScale=$SCALE"; fi
        fi
        ;;
esac
case "$(locale charmap 2>/dev/null)" in
    UTF-8 | utf8) ;;
    *) if [ -n "${LC_ALL:-}" ]; then LC_ALL=C.UTF-8; export LC_ALL; else LC_CTYPE=C.UTF-8; export LC_CTYPE; fi ;;
esac
case "${XDG_CURRENT_DESKTOP:-}:${DESKTOP_SESSION:-}" in
    *sway* | *Hyprland* | *hyprland* | *i3* | *river* | *niri* | *bspwm* | *dwm* | *awesome* | *qtile* | *xmonad* | *herbstluftwm*)
        _JAVA_AWT_WM_NONREPARENTING="${_JAVA_AWT_WM_NONREPARENTING:-1}"
        export _JAVA_AWT_WM_NONREPARENTING
        ;;
esac
if [ -n "$OPTIONS" ]; then
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$OPTIONS"
    export JAVA_TOOL_OPTIONS
fi
exec "$HERE/AbsentMotion" "$@"
