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
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n 1)"
PW="$(mktemp)"
trap 'rm -f "$PW"' EXIT
chmod 600 "$PW"
{ prop storePassword; prop keyPassword; } > "$PW"
"$BT/apksigner" sign --ks "$ROOT/$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
    --ks-pass "file:$PW" --key-pass "file:$PW" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version 34 --alignment-preserved true --out "$OUT" "$UNSIGNED"
"$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null | grep -m 1 'SHA-256 digest'
