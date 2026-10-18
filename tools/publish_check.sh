#!/bin/sh
set -u
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
grep -E '\.(kt|kts|java|cpp|h|mm|gradle)$' "$tmp/files" | while read -r f; do
    [ -f "$f" ] || continue
    c=$(grep -n -E '^[[:space:]]*(//|/\*|\*([[:space:]]|/|$))' "$f" | head -3)
    [ -n "$c" ] && problem "comment in $f: $(printf '%s' "$c" | cut -c1-80 | tr '\n' '|')"
done
grep -E '\.py$' "$tmp/files" | while read -r f; do
    [ -f "$f" ] || continue
    c=$(python3 - "$f" <<'PY'
import sys, tokenize
for t in tokenize.generate_tokens(open(sys.argv[1], encoding="utf-8").readline):
    if t.type == tokenize.COMMENT and not (t.start[0] <= 2 and (t.string.startswith("#!") or "coding" in t.string)):
        print("%d:%s" % (t.start[0], t.string[:60]))
        break
PY
)
    [ -n "$c" ] && problem "comment in $f: $c"
done
grep -E '\.(sh|gn|pro|toml)$|CMakeLists\.txt$' "$tmp/files" | while read -r f; do
    [ -f "$f" ] || continue
    c=$(grep -n -E '^[[:space:]]*#' "$f" | grep -v -E '^[0-9]+:#!' | head -3)
    [ -n "$c" ] && problem "comment in $f: $(printf '%s' "$c" | cut -c1-80 | tr '\n' '|')"
done
grep -E '\.xml$' "$tmp/files" | while read -r f; do
    [ -f "$f" ] && grep -q '<!--' "$f" && ! echo "$f" | grep -q '^testdata/' && problem "XML comment in $f"
done

if git rev-parse --git-dir >/dev/null 2>&1 && git rev-parse HEAD >/dev/null 2>&1; then
    echo "-- authors, committers, tags and messages in the history"
    git log --all --format='%an <%ae>%n%cn <%ce>%n%s%n%b' > "$tmp/history"
    python3 tools/publish_scan.py "$words" tools/publish_allowed.txt "history " <<EOL | while read -r l; do problem "${l#PROBLEM: }"; done
$tmp/history
EOL
    git log --all --format='%an <%ae>' | sort -u | sed 's/^/  author: /'
fi

while [ $# -gt 0 ]; do
    if [ "$1" = "--apk" ] && [ -f "${2:-}" ]; then
        echo "-- inside $2"
        apk="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
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
