#!/bin/sh
set -u
caller_dir=$PWD
cd "$(dirname "$0")/.."
words=tools/publish_forbidden.txt
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
: > "$tmp/bad"
[ -f "$words" ] || { echo "publish_check: create $words first (one regex per line: the owner's names, e-mail addresses and home paths)." >&2; exit 2; }
problem() { printf 'PROBLEM: %s\n' "$1" >&2; echo x >> "$tmp/bad"; }

if git rev-parse --git-dir >/dev/null 2>&1; then
    git ls-files > "$tmp/all"
    git ls-files --others --exclude-standard >> "$tmp/all"
    [ -n "$(git status --porcelain --ignored=no 2>/dev/null | grep '^ M\|^M' )" ] && echo "note: uncommitted changes are checked as they are on disk."
else
    git init --bare -q "$tmp/g"
    git --git-dir="$tmp/g" --work-tree="$PWD" ls-files --others --exclude-standard > "$tmp/all"
fi
sort -u "$tmp/all" | grep -v '^third_party/' > "$tmp/files"
n=$(wc -l < "$tmp/files" | tr -d ' ')
echo "publish_check: $n files would be published."

echo "-- file names"
grep -E '(^|/)(CLAUDE\.md|\.env[^/]*|signing\.properties|local\.properties|\.DS_Store|am-private|PUBLISH_AUDIT\.md)$|\.(jks|keystore|p12|pem|pfx|apk|aab|dmg|log|orig|rej|bak|swp|pyc)$|(^|/)(\.claude|\.idea|\.vscode|\.gradle|\.gradle-user|\.kotlin|build|desktop)/' "$tmp/files" | while read -r f; do problem "file that must not be published: $f"; done
grep -E -i -f "$words" "$tmp/files" | while read -r f; do problem "file name contains a forbidden word: $f"; done

echo "-- large files (over 3 MB)"
while read -r f; do
    [ -f "$f" ] && [ "$(wc -c < "$f")" -gt 3145728 ] && problem "large file: $f ($(wc -c < "$f") bytes): is it generated, or a download?"
done < "$tmp/files"

echo "-- forbidden words inside the files"
python3 tools/publish_scan.py "$words" tools/publish_allowed.txt "" < "$tmp/files" | while read -r l; do problem "${l#PROBLEM: }"; done

echo "-- comments in the source (the project ships without code comments)"
python3 tools/comment_check.py | grep -v '^comment_check:' | while read -r l; do problem "$l"; done

if git rev-parse --git-dir >/dev/null 2>&1 && git rev-parse HEAD >/dev/null 2>&1; then
    echo "-- authors, committers, tags and messages in the history"
    git log --all --format='%an <%ae>%n%cn <%ce>%n%s%n%b' > "$tmp/history"
    python3 tools/publish_scan.py "$words" tools/publish_allowed.txt "history " <<EOL | while read -r l; do problem "${l#PROBLEM: }"; done
$tmp/history
EOL
    git log --all --format='%an <%ae>' | sort -u | sed 's/^/  author: /'
fi

while [ $# -gt 0 ]; do
    if [ "$1" = "--apk" ] && [ $# -ge 2 ]; then
        case "$2" in
            /*) apk=$2 ;;
            *) apk=$caller_dir/$2 ;;
        esac
        [ -f "$apk" ] || { echo "publish_check: APK not found: $2" >&2; exit 2; }
        echo "-- inside $2"
        rm -rf "$tmp/apk"; mkdir -p "$tmp/apk"
        (cd "$tmp/apk" && unzip -q -o "$apk") || problem "cannot unpack $2"
        (cd "$tmp/apk" && find . -type f | sed 's|^\./||') > "$tmp/apkfiles"
        (cd "$tmp/apk" && python3 "$OLDPWD/tools/publish_scan.py" "$OLDPWD/$words" "$OLDPWD/tools/publish_allowed.txt" "$2: " < "$tmp/apkfiles") | while read -r l; do problem "${l#PROBLEM: }"; done
        shift 2
    else
        echo "usage: tools/publish_check.sh [--apk file]..." >&2; exit 2
    fi
done

[ ! -s "$tmp/bad" ] && echo "publish_check: nothing found." || { echo "publish_check: FAILED. Fix every PROBLEM above before pushing or releasing." >&2; exit 1; }
