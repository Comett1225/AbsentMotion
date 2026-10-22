#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin"
TIER = KOTLIN / "app/absentmotion/Tier.kt"
ENTRY = re.compile(r"^\s*([A-Z][A-Za-z0-9]*)\(R\.string\.([a-z0-9_]+), inCompatible = (true|false)\),?\s*$", re.M)


def entries(text):
    body = text[text.index("enum class Feature"):]
    body = body[:body.index("}")]
    found = ENTRY.findall(body)
    names = re.findall(r"^\s*([A-Z][A-Za-z0-9]*)\(", body, re.M)
    if len(found) != len(names):
        sys.exit("tier_check: every Feature entry must be written as Name(R.string.label, inCompatible = true|false)")
    return [(name, label, flag == "true") for name, label, flag in found]


def main():
    features = entries(TIER.read_text())
    strings = (ROOT / "app/src/main/res/values/strings.xml").read_text()
    sources = {path: path.read_text() for path in KOTLIN.rglob("*.kt") if path != TIER}
    problems = []
    for name, label, kept in features:
        if f'name="{label}"' not in strings:
            problems.append(f"{name}: string {label} is missing")
        if kept:
            continue
        users = sorted(str(path.relative_to(KOTLIN)) for path, text in sources.items() if re.search(rf"\bFeature\.{name}\b", text))
        if users:
            print(f"{name}: left out of Compatible, gated in {', '.join(users)}")
        else:
            problems.append(f"{name}: left out of Compatible but nothing checks Tier.has(Feature.{name}) or Tier.choice(..., Feature.{name})")
    for problem in problems:
        print("tier_check: " + problem)
    print(f"tier_check: {len(features)} feature(s), {sum(not kept for _, _, kept in features)} left out of Compatible, "
          + ("all gated" if not problems else f"{len(problems)} problem(s)"))
    sys.exit(1 if problems else 0)


main()
