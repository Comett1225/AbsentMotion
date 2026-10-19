#!/bin/sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK="${SKSL_CHECK:-$ROOT/build/sksl_check/sksl_check}"
[ -x "$CHECK" ] || { echo "effectcheck: no SkSL checker at $CHECK (run tools/sksl_check/build.sh)" >&2; exit 2; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
python3 - "$ROOT/app/src/main/assets/effects" "$OUT" <<'EOF'
import json, sys
from pathlib import Path
pack, out = Path(sys.argv[1]), Path(sys.argv[2])
for path in sorted(pack.glob("*.json")):
    if path.name in ("index.json", "foreign.json"): continue
    effect = json.loads(path.read_text())
    if not effect.get("native"): (out / f"{path.stem}.sksl").write_text(effect["sksl"])
EOF
cd "$OUT"
"$CHECK" *.sksl
