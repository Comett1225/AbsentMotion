#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UNSIGNED="$1"
OUT="$2"
if [ ! -f "$UNSIGNED" ] || [ -z "$OUT" ]; then
    echo "usage: tools/sign_apk.sh unsigned.apk signed.apk" >&2
    exit 2
fi
KEYS="$ROOT/signing.properties"
prop() { sed -n "s/^$1=//p" "$KEYS"; }
. "$ROOT/tools/env.sh"
SDK="${SDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}}"
BT="$SDK/build-tools/37.0.0"
[ -x "$BT/apksigner" ] || { echo "sign_apk: Android build-tools 37.0.0 is required" >&2; exit 1; }
PW="$(mktemp)"
trap 'rm -f "$PW"' EXIT
chmod 600 "$PW"
{ prop storePassword; prop keyPassword; } > "$PW"
"$BT/apksigner" sign --ks "$ROOT/$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
    --ks-pass "file:$PW" --key-pass "file:$PW" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --alignment-preserved true --out "$OUT" "$UNSIGNED"
python3 "$ROOT/tools/apk_check.py" "$OUT"
