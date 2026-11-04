#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PARITY = ROOT / "tools" / "parity.json"
STATUSES = ("works", "missing", "partial", "untested")
PLATFORMS = ("android", "macos", "windows", "linux")
FIELDS = ("id", "name", "where", "note") + PLATFORMS
ID = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$")
FLAG = re.compile(r"const val (\w+) = (true|false)")
GATE = re.compile(r"PlatformFeatures\.(\w+)")
THROWS = re.compile(r"UnsupportedOperationException|NotImplementedError|\bTODO\(|[Nn]ot available|[Nn]ot supported")
DESKTOP_PLATFORM = "pc/src/standins/kotlin/app/absentmotion/platform/DesktopPlatform.kt"
ANDROID_KOTLIN = "android/src/main/kotlin"
SHARED_KOTLIN = "shared/kotlin"
STANDINS = "pc/src/standins"


def kotlin(folder):
    return sorted((ROOT / folder).rglob("*.kt"))


def rel(path):
    return path.relative_to(ROOT).as_posix()


def load(errors):
    try:
        return json.loads(PARITY.read_text(encoding="utf-8"))
    except (OSError, ValueError) as problem:
        errors.append(f"tools/parity.json cannot be read: {problem}")
        return None


def check_features(document, errors):
    features = document.get("features")
    if not isinstance(features, list) or not features:
        errors.append("parity.json needs a non-empty \"features\" list")
        return {}
    by_id = {}
    for index, entry in enumerate(features):
        label = entry.get("id", f"#{index}") if isinstance(entry, dict) else f"#{index}"
        if not isinstance(entry, dict):
            errors.append(f"feature {label} is not an object")
            continue
        for field in FIELDS:
            if not isinstance(entry.get(field), str) or (field != "note" and not entry[field].strip()):
                errors.append(f"feature {label}: field \"{field}\" must be a text" + ("" if field == "note" else " that is not empty"))
        if isinstance(entry.get("id"), str):
            if not ID.match(entry["id"]):
                errors.append(f"feature {label}: id must look like area.name (lower case, digits, underscores)")
            if entry["id"] in by_id:
                errors.append(f"feature {label}: id is used twice")
            by_id[entry["id"]] = entry
        statuses = [entry.get(p) for p in PLATFORMS]
        for platform, status in zip(PLATFORMS, statuses):
            if status not in STATUSES:
                errors.append(f"feature {label}: {platform} is \"{status}\", it must be one of {', '.join(STATUSES)}")
        if any(s in ("missing", "partial") for s in statuses) and not str(entry.get("note", "")).strip():
            errors.append(f"feature {label}: a missing or partial status needs a note that says what is missing")
        for key in ("files", "standins"):
            for name in entry.get(key, []):
                if not isinstance(name, str) or not (ROOT / name).is_file():
                    errors.append(f"feature {label}: {key} lists {name}, which is not a file")
        extra = set(entry) - set(FIELDS) - {"gate", "files", "standins"}
        if extra:
            errors.append(f"feature {label}: unknown field {', '.join(sorted(extra))}")
    return by_id


def desktop_flags():
    return dict(FLAG.findall((ROOT / DESKTOP_PLATFORM).read_text(encoding="utf-8").split("object PlatformFeatures", 1)[1].split("}", 1)[0]))


def check_gates(by_id, errors):
    flags = desktop_flags()
    off = {name for name, value in flags.items() if value == "false"}
    listed = {}
    for entry in by_id.values():
        gate = entry.get("gate")
        if gate is None:
            continue
        if gate not in off:
            errors.append(f"feature {entry['id']}: gate \"{gate}\" is not a PlatformFeatures flag that is false on desktop")
        if any(entry.get(p) == "works" for p in PLATFORMS[1:]):
            errors.append(f"feature {entry['id']}: it sits behind PlatformFeatures.{gate}, which is false on desktop, so no desktop platform can be \"works\"")
        for name in entry.get("files", []):
            listed.setdefault(gate, set()).add(name)
    for path in kotlin(SHARED_KOTLIN):
        for flag in sorted(set(GATE.findall(path.read_text(encoding="utf-8"))) & off):
            if rel(path) not in listed.get(flag, set()):
                errors.append(f"{rel(path)} uses PlatformFeatures.{flag}, which is false on desktop: add a feature with \"gate\": \"{flag}\" and this file in \"files\" to tools/parity.json")


def check_standins(by_id, errors, notes):
    listed = {name for entry in by_id.values() for name in entry.get("standins", [])}
    for path in kotlin(STANDINS):
        if THROWS.search(path.read_text(encoding="utf-8")) and rel(path) not in listed:
            errors.append(f"{rel(path)} has a desktop stand-in that is not available: list it in \"standins\" of a feature in tools/parity.json")
    for entry in by_id.values():
        for name in entry.get("standins", []):
            if (ROOT / name).is_file() and not THROWS.search((ROOT / name).read_text(encoding="utf-8")):
                notes.append(f"{entry['id']}: {name} no longer has a not-available stand-in, so its status may be out of date")


def check_android_only(document, by_id, errors):
    mapping = document.get("android_only")
    if not isinstance(mapping, dict):
        errors.append("parity.json needs an \"android_only\" object")
        return
    base = ROOT / ANDROID_KOTLIN
    present = {path.relative_to(base).as_posix() for path in kotlin(ANDROID_KOTLIN)}
    for name in sorted(present - set(mapping)):
        errors.append(f"{ANDROID_KOTLIN}/{name} has no entry in \"android_only\" of tools/parity.json (name its desktop counterpart or say why it has none)")
    for name, entry in mapping.items():
        if name not in present:
            errors.append(f"android_only lists {name}, which does not exist under {ANDROID_KOTLIN}")
        if not isinstance(entry, dict) or not str(entry.get("reason", "")).strip():
            errors.append(f"android_only {name}: needs a \"reason\"")
            continue
        counterpart = entry.get("desktop")
        if counterpart is not None and not (ROOT / counterpart).is_file():
            errors.append(f"android_only {name}: desktop counterpart {counterpart} is not a file")
        if "desktop" not in entry:
            errors.append(f"android_only {name}: needs \"desktop\" (a path or null)")
        if "feature" in entry and entry["feature"] not in by_id:
            errors.append(f"android_only {name}: feature {entry['feature']} is not in the features list")


def summary(by_id):
    lines = ["parity: %d features" % len(by_id)]
    for platform in PLATFORMS:
        counts = {s: sum(1 for e in by_id.values() if e.get(platform) == s) for s in STATUSES}
        lines.append("  %-8s" % platform + "  ".join("%s %d" % (s, counts[s]) for s in STATUSES))
    gaps = sorted(e["id"] for e in by_id.values() if e.get("macos") in ("missing", "partial"))
    lines.append("  desktop gaps (missing or partial on macOS): %d" % len(gaps))
    return lines


def main():
    errors, notes = [], []
    document = load(errors)
    by_id = {}
    if document is not None:
        by_id = check_features(document, errors)
        check_gates(by_id, errors)
        check_standins(by_id, errors, notes)
        check_android_only(document, by_id, errors)
    for line in summary(by_id):
        print(line)
    for note in notes:
        print("note: " + note)
    if errors:
        print("\nparity_check: %d problem(s)" % len(errors), file=sys.stderr)
        for error in errors:
            print("  " + error, file=sys.stderr)
        sys.exit(1)
    print("parity_check: ok")


if __name__ == "__main__":
    main()
