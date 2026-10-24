#!/usr/bin/env python3
import json
import re
import source_policy
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
C_LIKE = {".kt", ".kts", ".java", ".cpp", ".cc", ".c", ".h", ".hpp", ".gradle", ".js", ".css"}
HASH = {".sh", ".yml"}
SKIP_DIRS = ("third_party/", "gradlew", "gradle/wrapper/", "app/src/main/assets/licenses.txt")


def listed(*args):
    run = subprocess.run(["git", "-C", str(ROOT), "ls-files", *args], capture_output=True, text=True)
    if run.returncode:
        sys.exit("comment_check: git ls-files " + " ".join(args) + " failed, so no file was checked: " + run.stderr.strip())
    return run.stdout.split("\n")


def tracked():
    found = sorted({n for n in listed() + listed("--others", "--exclude-standard") if n and not n.startswith(SKIP_DIRS)})
    if not found:
        sys.exit("comment_check: git listed no files, so nothing was checked")
    return found


def c_comments(text):
    found = []
    i, n, line = 0, len(text), 1
    while i < n:
        ch = text[i]
        two = text[i:i + 2]
        if ch == "\n":
            line += 1
            i += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            line += text.count("\n", i, end)
            i = end
        elif ch == '"' or ch == "'":
            j = i + 1
            while j < n and text[j] != ch and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            i = j + 1
        elif ch == "R" and text.startswith('R"', i):
            m = re.match(r'R"([^(\s]{0,16})\(', text[i:])
            if m:
                end = text.find(")" + m.group(1) + '"', i)
                end = n if end < 0 else end + len(m.group(1)) + 2
                line += text.count("\n", i, end)
                i = end
            else:
                i += 1
        elif two == "//":
            found.append(line)
            i = text.find("\n", i)
            i = n if i < 0 else i
        elif two == "/*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            found.append(line)
            line += text.count("\n", i, end)
            i = end
        else:
            i += 1
    return found


def python_comments(text):
    return [line for line, _ in source_policy.violations(text)]


def shell_comments(text):
    found = []
    for number, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("#") and not (number == 1 and stripped.startswith("#!")):
            found.append(number)
    return found


def xml_comments(text):
    return [text.count("\n", 0, m.start()) + 1 for m in re.finditer(r"<!--", text)]


def json_shader_comments(text):
    found = []
    try:
        data = json.loads(text)
    except ValueError:
        return found
    shader = data.get("sksl") if isinstance(data, dict) else None
    if isinstance(shader, str) and (re.search(r"//", shader) or "/*" in shader):
        found.append(1)
    return found


def main():
    problems = []
    for name in tracked():
        path = ROOT / name
        suffix = path.suffix
        if not path.is_file():
            continue
        try:
            text = path.read_text()
        except UnicodeDecodeError:
            continue
        if suffix in C_LIKE:
            lines = c_comments(text)
        elif suffix == ".py":
            lines = python_comments(text)
        elif suffix in HASH or path.name in ("gradlew",):
            lines = shell_comments(text)
        elif suffix in (".xml", ".html"):
            lines = xml_comments(text)
        elif suffix == ".json" and "assets/effects" in name:
            lines = json_shader_comments(text)
        else:
            continue
        for line in lines:
            problems.append((name, line))
    for name, line in problems[:200]:
        print(f"{name}:{line}: comment in tracked source")
    print(f"comment_check: {len(problems)} comments")
    sys.exit(1 if problems else 0)


main()
