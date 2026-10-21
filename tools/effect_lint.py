#!/usr/bin/env python3
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "app/src/main/assets/effects"
SKIP = {"index.json", "releases.json", "foreign.json"}
CATEGORIES = {"color", "blur", "distort", "stylize", "noise", "repeat", "key", "move", "3d", "text"}
KINDS = {"float", "angle", "point", "color", "switch", "choice", "text"}
UNIFORM = re.compile(r"^\s*(?:layout\([^)]*\)\s*)?uniform\s+(\w+)\s+(\w+)\s*;", re.M)
UNIFORM_TYPES = {"float": {"float"}, "angle": {"float"}, "choice": {"float"}, "switch": {"float"}, "point": {"float2"}, "color": {"float4", "half4"}, "text": set()}
RESERVED = {"image", "size", "time", "screen", "layer", "fromLayer", "toLayer", "duration"}


def lint(path, problems, standard):
    effect = json.loads(path.read_text())
    key = path.stem
    here = lambda message, severity="error": problems.append((severity, key, message))
    if effect.get("id") != key:
        here(f"id {effect.get('id')!r} differs from the file name")
    if effect.get("category") not in CATEGORIES:
        here(f"unknown category {effect.get('category')!r}")
    if not str(effect.get("name", "")).strip():
        here("no name")
    description = str(effect.get("description", "")).strip()
    if not description:
        here("no description")
    elif not description.endswith((".", "!", "?")):
        here("description does not end with a full stop", "warning")
    params = effect.get("params", [])
    ids = [p.get("id") for p in params]
    for name, count in Counter(ids).items():
        if count > 1:
            here(f"parameter {name} is declared {count} times")
    for p in params:
        pid, kind = p.get("id"), p.get("type")
        if kind not in KINDS:
            here(f"{pid}: unknown type {kind}")
            continue
        if not str(p.get("label", "")).strip():
            here(f"{pid}: no label", "warning")
        low, high, default = p.get("min"), p.get("max"), p.get("default")
        if kind in ("float", "angle"):
            if not isinstance(default, (int, float)) or isinstance(default, bool):
                here(f"{pid}: default {default!r} is not a number")
                continue
            if low is not None and high is not None and low > high:
                here(f"{pid}: min {low} is above max {high}")
            if low is not None and default < low or high is not None and default > high:
                here(f"{pid}: default {default} is outside {low}..{high}")
            if p.get("step", 0.01) <= 0:
                here(f"{pid}: step is not positive")
            if p.get("percent") and high is not None and high > 100:
                here(f"{pid}: percent with max {high}", "warning")
        elif kind == "choice":
            options = p.get("options") or []
            if not options or any(not str(o).strip() for o in options):
                here(f"{pid}: empty or blank choice options")
            elif not isinstance(default, int) or not 0 <= default < len(options):
                here(f"{pid}: default {default!r} is not an option index")
            elif len(set(options)) != len(options):
                here(f"{pid}: duplicate option names", "warning")
        elif kind == "point":
            if not (isinstance(default, list) and len(default) == 2):
                here(f"{pid}: point default {default!r} is not two numbers")
        elif kind == "color":
            if not re.fullmatch(r"#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?", str(default)):
                here(f"{pid}: colour default {default!r} is not #rrggbb or #aarrggbb")
        legacy = p.get("legacyDefault")
        if legacy is not None and legacy == default:
            here(f"{pid}: legacyDefault equals the default", "warning")
    sksl = effect.get("sksl")
    if effect.get("native"):
        if sksl:
            here("native effect that also has a shader", "warning")
    elif not sksl:
        here("no shader and not native")
    else:
        uniforms = {name: kind for kind, name in UNIFORM.findall(sksl)}
        for p in params:
            pid, kind = p.get("id"), p.get("type")
            if kind == "text":
                continue
            if pid not in uniforms:
                here(f"{pid}: parameter with no uniform in the shader (it does nothing)")
            elif kind in UNIFORM_TYPES and uniforms[pid] not in UNIFORM_TYPES[kind]:
                here(f"{pid}: {kind} parameter but the uniform is {uniforms[pid]}")
        for name, kind in uniforms.items():
            if kind != "shader" and name not in ids and name not in standard:
                here(f"uniform {name} has no parameter and is not a standard input (always 0)")
    for preset in effect.get("presets", []):
        for pid, value in preset.get("params", {}).items():
            if pid not in ids:
                here(f"preset {preset.get('name')!r}: unknown parameter {pid}")


def main():
    files = [p for p in sorted(PACK.glob("*.json")) if p.name not in SKIP]
    counts = Counter()
    for path in files:
        effect = json.loads(path.read_text())
        if not effect.get("native"):
            for kind, name in UNIFORM.findall(effect.get("sksl", "")):
                counts[name] += 1
    standard = RESERVED | {name for name, n in counts.items() if n >= 20}
    problems = []
    for path in files:
        lint(path, problems, standard)
    names = Counter(json.loads(p.read_text()).get("name") for p in files)
    for name, count in names.items():
        if count > 1:
            problems.append(("error", name, f"{count} effects share this name"))
    errors = [p for p in problems if p[0] == "error"]
    for severity, key, message in sorted(problems):
        print(f"{severity:7s} {key}: {message}")
    print(f"effect_lint: {len(files)} effects, {len(errors)} errors, {len(problems) - len(errors)} warnings")
    sys.exit(1 if errors else 0)


main()
