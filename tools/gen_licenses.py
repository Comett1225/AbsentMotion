#!/usr/bin/env python3
"""Writes app/src/main/assets/licenses.txt, the text of the app's "Open source licences" page.

  tools/gen_licenses.py            rewrite the asset
  tools/gen_licenses.py --check    exit 1 (and change nothing) if the asset is out of date

It joins four repository files into one plain-text file that both apps ship (Android: assets, Mac: classpath resource):
LICENSE and NOTICE as they are, then THIRD_PARTY_NOTICES.md and docs/ICON_SOURCES.md turned from Markdown into plain text.
Headings become capitals and are underlined with dashes ("=" for the four parts), `code`, **bold** and links lose their
markup, a wrapped paragraph or list item becomes one line, tables become lines "- Name (source) - licence - where used"
(URLs and notes are left out; they stay in the .md file), and everything inside a code fence (the licence texts) is copied
unchanged, line by line. No timestamp, so the same sources always give the same file.
Run it again whenever one of the four source files changes, and keep the result with them: --check is for scripts.
Needs only the Python standard library (3.9 or newer).
"""
import argparse, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/licenses.txt"

TITLE = "AbsentMotion: licences and notices"
INTRO = (
    "AbsentMotion is free and open source, licensed under the Apache License, Version 2.0 (the first part below). "
    "It includes and uses third-party software and icons under their own licences, which follow. "
    "This page is generated from the files LICENSE, NOTICE, THIRD_PARTY_NOTICES.md and docs/ICON_SOURCES.md of the source code, "
    "https://github.com/Comett1225/AbsentMotion."
)

FENCE = re.compile(r"^(`{3,}|~{3,})")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*$")
RULE = re.compile(r"^\s*(-{3,}|\*{3,}|_{3,})\s*$")
BULLET = re.compile(r"^(\s*)[-*+]\s+(.*)$")
NUMBERED = re.compile(r"^(\s*)(\d+[.)])\s+(.*)$")
LIST_LINE = re.compile(r"^(- |\d+[.)] )")

def inline(s):
    """Plain text for a line of Markdown prose: no links, bold, code spans or <br>."""
    s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: m.group(1) if m.group(1) == m.group(2) else "%s (%s)" % (m.group(1), m.group(2)), s)
    s = s.replace("<br>", ", ").replace("\\|", "|")
    s = re.sub(r"\*\*(.+?)\*\*", r"\1", s)
    return s.replace("`", "")

def heading(level, text):
    """The heading's two lines: the text (in capitals for levels 1 and 2) and its underline, '=' for level 1, else '-'."""
    text = inline(text)
    if level <= 2:
        text = text.upper()
    return [text, ("=" if level == 1 else "-") * max(len(text), 3)]

def cells(row):
    return [c.strip() for c in row.strip().strip("|").split("|")]

def table_lines(rows):
    """A Markdown table (header, separator, rows) as lines 'Name (source) - licence - where used'; a table without such columns, every cell."""
    header = [h.lower() for h in cells(rows[0])]
    out = []
    for row in rows[2:]:
        cell = dict(zip(header, [inline(c) for c in cells(row)]))
        name = cell.get("icon") or cell.get("what")
        if name is None or "licence" not in cell:
            out.append("- " + " - ".join(inline(c) for c in cells(row)))
            continue
        source = " ".join(cell[k] for k in ("source", "upstream icon") if cell.get(k, "-") != "-")
        out.append("- %s (%s) - %s - %s" % (name, source, cell["licence"], cell.get("file", "")))
    return out

def markdown_to_text(md, skip_h1=False):
    """The lines of a Markdown document as plain text (rules in the module comment). Ends without a blank line."""
    out = []
    para = []
    item = []

    def blank():
        if out and out[-1] != "":
            out.append("")

    def flush():
        if item:
            if not (out and LIST_LINE.match(out[-1])):
                blank()
            out.append(inline(" ".join(i.strip() for i in item)))
            item.clear()
        if para:
            blank()
            out.append(inline(" ".join(p.strip() for p in para)))
            para.clear()

    lines = md.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        i += 1
        fence = FENCE.match(line)
        if fence:
            flush(); blank()
            while i < len(lines) and not lines[i].startswith(fence.group(1)):
                out.append(lines[i]); i += 1
            i += 1
            blank()
        elif line.startswith("|"):
            flush(); blank()
            rows = [line]
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(lines[i]); i += 1
            out.extend(table_lines(rows)); blank()
        elif HEADING.match(line):
            flush()
            level, text = HEADING.match(line).groups()
            if not (skip_h1 and len(level) == 1):
                blank(); out.extend(heading(len(level), text))
        elif RULE.match(line) or not line.strip():
            flush()
        elif BULLET.match(line) or NUMBERED.match(line):
            flush()
            m = BULLET.match(line)
            item.append("- " + m.group(2) if m else NUMBERED.match(line).group(2) + " " + NUMBERED.match(line).group(3))
        elif item and line.startswith(" "):
            item.append(line)
        else:
            if item:
                flush()
            para.append(line)
    flush()
    while out and out[-1] == "":
        out.pop()
    return out

def build():
    read = lambda name: (ROOT / name).read_text(encoding="utf-8").replace("\r\n", "\n").strip("\n").split("\n")
    lines = heading(1, TITLE) + ["", INTRO, ""]
    lines += heading(1, "Apache License 2.0") + [""] + read("LICENSE") + [""]
    lines += heading(1, "Project notice") + [""] + read("NOTICE") + [""]
    for title, name in (("Third-party notices", "THIRD_PARTY_NOTICES.md"), ("Icon sources", "docs/ICON_SOURCES.md")):
        lines += heading(1, title) + [""] + markdown_to_text("\n".join(read(name)), skip_h1=True) + [""]
    return "\n".join(lines).rstrip("\n") + "\n"

def main():
    parser = argparse.ArgumentParser(description="Writes %s from LICENSE, NOTICE, THIRD_PARTY_NOTICES.md and docs/ICON_SOURCES.md." % OUT.relative_to(ROOT))
    parser.add_argument("--check", action="store_true", help="exit 1, changing nothing, if the file is out of date")
    args = parser.parse_args()
    text = build().encode("utf-8")
    if args.check:
        if not OUT.exists() or OUT.read_bytes() != text:
            print("%s is out of date: run tools/gen_licenses.py" % OUT.relative_to(ROOT), file=sys.stderr)
            return 1
        print("%s is up to date" % OUT.relative_to(ROOT))
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(text)
    print("wrote %s (%d bytes, %d lines)" % (OUT.relative_to(ROOT), len(text), text.count(b"\n")))
    return 0

if __name__ == "__main__":
    sys.exit(main())
