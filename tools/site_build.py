#!/usr/bin/env python3
import argparse
import json
import re
import shutil
import struct
import subprocess
import sys
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
ASSETS = ROOT / "app/src/main/assets"
EFFECTS = ASSETS / "effects"
OUT = ROOT / "build/site"
HELP = ASSETS / "help"
REPO = "https://github.com/Comett1225/AbsentMotion"
OWNER = REPO.split("/")[3].lower()
SHORT = {
    "repo": REPO,
    "releases": REPO + "/releases",
    "issues": REPO + "/issues",
    "privacy-file": REPO + "/blob/main/PRIVACY.md",
    "license-file": REPO + "/blob/main/LICENSE",
    "notices-file": REPO + "/blob/main/THIRD_PARTY_NOTICES.md",
}
GROUPS = ["Start", "Interface", "Create", "Sound and AI", "Export and projects", "Help"]
CATEGORIES = [("color", "Color"), ("blur", "Blur"), ("distort", "Distort"), ("stylize", "Stylize"), ("noise", "Noise"),
              ("repeat", "Repeat"), ("key", "Key"), ("move", "Move"), ("3d", "3D"), ("text", "Text")]
TIERS = {"latest", "compatible", "unsupported"}
AI_CLASSES = {"great", "good", "slow"}
DEVICE_KEYS = ["brand", "name", "aliases", "modelCodes", "year", "launchAndroid", "maxAndroid", "chip", "ramGB", "arm64",
               "vulkan11", "tier", "aiClass", "notes", "confidence"]
OTHER_EDITORS = ("alight", "capcut", "kinemaster", "premiere pro", "davinci", "filmora", "inshot")


def version():
    for line in (ROOT / "version.properties").read_text().splitlines():
        if line.startswith("versionName="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("version.properties has no versionName")


def front(text):
    meta = {}
    if text.startswith("---\n"):
        head, _, body = text[4:].partition("\n---\n")
        for line in head.splitlines():
            key, _, value = line.partition(":")
            meta[key.strip()] = value.strip()
        return meta, body
    return meta, text


def image_size(path):
    data = path.read_bytes()[:64]
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        kind = data[12:16]
        if kind == b"VP8X":
            return 1 + int.from_bytes(data[24:27], "little"), 1 + int.from_bytes(data[27:30], "little")
        if kind == b"VP8L":
            bits = int.from_bytes(data[21:25], "little")
            return 1 + (bits & 0x3FFF), 1 + ((bits >> 14) & 0x3FFF)
        if kind == b"VP8 ":
            return struct.unpack("<HH", data[26:30])[0] & 0x3FFF, struct.unpack("<HH", data[26:30])[1] & 0x3FFF
    raise SystemExit("cannot read the size of " + str(path))


def slugify(text):
    return re.sub(r"[^a-z0-9]+", "-", re.sub(r"<[^>]+>", "", text).lower()).strip("-") or "section"


def number(value):
    text = ("%.4f" % value).rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


class Page:
    def __init__(self, slug, meta, body):
        self.slug = slug
        self.meta = meta
        self.body = body
        self.title = meta.get("title", slug)
        self.kind = meta.get("kind", "guide")
        self.site_only = meta.get("site_only") == "true"
        self.group = meta.get("group", "")
        self.order = int(meta.get("order", "0") or 0)
        self.summary = meta.get("summary", "")


class Site:
    def __init__(self, target):
        self.target = target
        self.version = version()
        self.errors = []
        self.files = {}
        self.images = set()
        self.pages = {}
        for path in sorted((SITE / "pages").glob("*.md")):
            meta, body = front(path.read_text())
            page = Page(path.stem, meta, body)
            if target == "app" and page.site_only:
                continue
            self.pages[page.slug] = page
        self.effects = load_effects()
        self.by_effect = {e["id"]: e for e in self.effects}
        self.devices = load_devices()

    def out_path(self, slug):
        if self.target == "site" and slug == "download":
            return "index.html"
        if self.target == "app" and slug == "guide":
            return "index.html"
        if slug == "effects":
            return "effects/index.html"
        return slug + ".html"

    def href(self, key, depth):
        base = "../" * depth
        if key in SHORT:
            return SHORT[key]
        if key in self.pages:
            return base + self.out_path(key)
        if key.startswith("effect/") and key[7:] in self.by_effect:
            return base + "effects/" + key[7:] + ".html"
        return None

    def thumb(self, name, depth):
        if self.target == "site":
            return "../" * depth + "effects/thumb/" + name
        return "../" * (depth + 1) + "effects/thumb/" + name

    def fail(self, where, message):
        self.errors.append("%s: %s" % (where, message))

    def inline(self, text, where, depth):
        codes = []

        def stash(match):
            codes.append(match.group(1))
            return "\x00%d\x00" % (len(codes) - 1)

        def link(match):
            label, url = match.group(1), match.group(2)
            if url.startswith("@"):
                key, _, frag = url[1:].partition("#")
                target = self.href(key, depth)
                if target is None:
                    if key in ("download", "phone-checker") and self.target == "app":
                        return label
                    self.fail(where, "unknown link @" + key)
                    return label
                return '<a href="%s%s">%s</a>' % (escape(target), "#" + frag if frag else "", label)
            if url.startswith("https://"):
                return '<a href="%s" rel="noopener">%s</a>' % (url, label)
            if url.startswith("#"):
                return '<a href="%s">%s</a>' % (url, label)
            self.fail(where, "bad link " + url)
            return label

        def image(match):
            alt, url = match.group(1), match.group(2)
            if not (SITE / "assets" / url).is_file():
                self.fail(where, "missing image " + url)
                return alt
            self.images.add(url)
            width, height = image_size(SITE / "assets" / url)
            return '<img src="%sassets/%s" width="%d" height="%d" alt="%s" loading="lazy">' % ("../" * depth, url, width, height, alt)

        text = re.sub(r"`([^`]+)`", stash, text)
        text = escape(text, quote=False)
        text = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)\)", image, text)
        text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, text)
        text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
        text = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", text)
        return re.sub("\x00(\\d+)\x00", lambda m: "<code>%s</code>" % escape(codes[int(m.group(1))], quote=False), text)

    def markdown(self, text, where, depth, heads=None):
        lines = text.replace("{{version}}", self.version if self.target == "site" else "").replace("{{effect_count}}", str(len(self.effects))).split("\n")
        if self.target == "app" and "{{version}}" in text:
            self.fail(where, "{{version}} in the in-app help")
        out = []
        used = set()
        i = 0
        while i < len(lines):
            line = lines[i]
            if not line.strip():
                i += 1
                continue
            if line.startswith("```"):
                end = i + 1
                while end < len(lines) and not lines[end].startswith("```"):
                    end += 1
                out.append("<pre><code>%s</code></pre>" % escape("\n".join(lines[i + 1:end]), quote=False))
                i = end + 1
                continue
            match = re.match(r"^::: (\w+)\s*$", line)
            if match:
                end, level = i + 1, 1
                while end < len(lines) and level:
                    level += 1 if re.match(r"^::: \w+\s*$", lines[end]) else -1 if lines[end].strip() == ":::" else 0
                    end += 1
                inner = "\n".join(lines[i + 1:end - 1])
                kind = match.group(1)
                if kind in ("site", "app"):
                    if kind == self.target:
                        out.append(self.markdown(inner, where, depth, heads))
                elif kind in ("note", "tip", "warn"):
                    out.append('<aside class="callout %s">%s</aside>' % (kind, self.markdown(inner, where, depth, heads)))
                else:
                    self.fail(where, "unknown block " + kind)
                i = end
                continue
            match = re.match(r"^(#{2,4}) (.*?)\s*$", line)
            if match:
                level, title = len(match.group(1)), match.group(2)
                ident = slugify(title)
                while ident in used:
                    ident += "-2"
                used.add(ident)
                if heads is not None and level == 2 and title != "Next":
                    heads.append((ident, re.sub(r"[`*]", "", title)))
                out.append('<h%d id="%s">%s</h%d>' % (level, ident, self.inline(title, where, depth), level))
                i += 1
                continue
            if line.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:|-]+\|\s*$", lines[i + 1]):
                end = i + 2
                while end < len(lines) and lines[end].startswith("|"):
                    end += 1
                out.append(self.table(lines[i], lines[i + 2:end], where, depth))
                i = end
                continue
            if re.match(r"^\s*([-*]|\d+\.)\s+", line):
                html, i = self.list(lines, i, len(line) - len(line.lstrip()), where, depth)
                out.append(html)
                continue
            if line.startswith("> "):
                end = i
                while end < len(lines) and lines[end].startswith(">"):
                    end += 1
                out.append("<blockquote>%s</blockquote>" % self.markdown("\n".join(l[1:].lstrip() for l in lines[i:end]), where, depth))
                i = end
                continue
            if line.strip() == "---":
                out.append("<hr>")
                i += 1
                continue
            end = i
            while end < len(lines) and lines[end].strip() and not re.match(r"^(#{2,4} |```|::: |\||> |\s*([-*]|\d+\.)\s+)", lines[end]):
                end += 1
            para = " ".join(l.strip() for l in lines[i:max(end, i + 1)])
            figure = re.fullmatch(r"!\[([^\]]*)\]\(([^)\s]+)\)", para)
            if figure:
                out.append("<figure>%s<figcaption>%s</figcaption></figure>" % (self.inline(para, where, depth), self.inline(figure.group(1), where, depth)))
            else:
                out.append("<p>%s</p>" % self.inline(para, where, depth))
            i = max(end, i + 1)
        return "\n".join(out)

    def table(self, head, rows, where, depth):
        def cells(row):
            return [c.strip() for c in row.strip().strip("|").split("|")]

        names = cells(head)
        html = ["<div class=\"table-wrap\"><table><thead><tr>%s</tr></thead><tbody>" % "".join("<th>%s</th>" % self.inline(n, where, depth) for n in names)]
        for row in rows:
            html.append("<tr>%s</tr>" % "".join('<td data-label="%s">%s</td>' % (escape(n), self.inline(c, where, depth)) for n, c in zip(names, cells(row))))
        html.append("</tbody></table></div>")
        return "".join(html)

    def list(self, lines, i, indent, where, depth):
        tag = "ol" if re.match(r"^\s*\d+\.", lines[i]) else "ul"
        items = []
        while i < len(lines):
            match = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", lines[i])
            if not match or len(match.group(1)) < indent:
                break
            if len(match.group(1)) > indent:
                sub, i = self.list(lines, i, len(match.group(1)), where, depth)
                items[-1] += sub
                continue
            text = match.group(3)
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r"^\s*([-*]|\d+\.)\s+", lines[i]) and lines[i].startswith(" " * (indent + 2)):
                text += " " + lines[i].strip()
                i += 1
            items.append(self.inline(text, where, depth))
        return "<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % item for item in items), tag), i

    def sidebar(self, current, depth):
        base = "../" * depth
        out = []
        for group in GROUPS:
            members = sorted((p for p in self.pages.values() if p.group == group), key=lambda p: p.order)
            if not members:
                continue
            out.append("<h2>%s</h2><ul>" % escape(group))
            for page in members:
                mark = ' aria-current="page"' if page.slug == current else ""
                out.append('<li><a href="%s%s"%s>%s</a></li>' % (base, self.out_path(page.slug), mark, escape(page.title)))
            out.append("</ul>")
        mark = ' aria-current="page"' if current == "effects" else ""
        out.append('<h2>Effects</h2><ul><li><a href="%seffects/index.html"%s>All %d effects</a></li></ul>' % (base, mark, len(self.effects)))
        return "".join(out)

    def sequence(self):
        return [p for g in GROUPS for p in sorted((p for p in self.pages.values() if p.group == g), key=lambda p: p.order)]

    def pager(self, slug, depth):
        order = self.sequence()
        names = [p.slug for p in order]
        if slug not in names:
            return ""
        i = names.index(slug)
        base = "../" * depth
        prev = '<a rel="prev" href="%s%s">&larr; %s</a>' % (base, self.out_path(order[i - 1].slug), escape(order[i - 1].title)) if i else "<span></span>"
        nxt = '<a rel="next" href="%s%s">%s &rarr;</a>' % (base, self.out_path(order[i + 1].slug), escape(order[i + 1].title)) if i + 1 < len(order) else "<span></span>"
        return '<nav class="pager" aria-label="Next and previous page">%s%s</nav>' % (prev, nxt)

    def shell(self, path, title, description, body, current, kind="guide", sidebar=True):
        depth = path.count("/")
        base = "../" * depth
        site = self.target == "site"
        if site:
            top = (
                '<header class="top"><a class="brand" href="%sindex.html"><img src="%sassets/logo.png" width="32" height="32" alt="">AbsentMotion</a>'
                '<nav class="main" aria-label="Main"><a href="%sindex.html"%s>Download</a><a href="%sguide.html"%s>Guide</a>'
                '<a href="%seffects/index.html"%s>Effects</a><a href="%sphone-checker.html"%s>Phone checker</a>'
                '<a href="%s" rel="noopener">Source</a></nav></header>'
            ) % (
                base, base, base, ' aria-current="page"' if current == "download" else "", base, ' aria-current="page"' if current == "guide" else "",
                base, ' aria-current="page"' if current == "effects" else "", base, ' aria-current="page"' if current == "phone-checker" else "", REPO)
        else:
            top = '<header class="top appbar"><nav class="main" aria-label="Help"><a href="%sindex.html">Contents</a><a href="%seffects/index.html">Effects</a></nav></header>' % (base, base)
        side = ""
        if sidebar and site:
            side = '<input type="checkbox" id="menu" class="menu-box" aria-label="Show the page list"><label for="menu" class="menu-btn">Pages</label><nav class="side" aria-label="Pages">%s</nav>' % self.sidebar(current, depth)
        foot = (
            '<footer><p>AbsentMotion is free and open source under the Apache License 2.0. <a href="%s" rel="noopener">Source code</a> &middot; '
            '<a href="%s" rel="noopener">Privacy</a> &middot; <a href="%s" rel="noopener">Report a problem</a></p></footer>'
            % (REPO, SHORT["privacy-file"], SHORT["issues"])) if site else ""
        script = '<script src="%sassets/site.js" defer></script>' % base if site else ""
        return (
            '<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>%s</title><meta name="description" content="%s"><meta name="color-scheme" content="light dark">'
            '%s<link rel="stylesheet" href="%sassets/site.css">%s</head>'
            '<body class="%s" data-page="%s">%s%s<div class="wrap">%s<main id="main">%s</main></div>%s</body></html>\n'
        ) % (escape(title), escape(description), '<link rel="icon" type="image/png" href="%sassets/logo.png">' % base if site else "", base, script, "site" if site else "app", kind, '<a class="skip" href="#main">Skip to the content</a>' if site else "", top, side, body, foot)

    def add(self, path, text):
        self.files[path] = text.encode()

    def build(self):
        for page in self.pages.values():
            self.render_page(page)
        self.render_effects()
        if self.target == "site":
            self.add_site_assets()
        else:
            self.add_app_assets()
        return self.files

    def render_page(self, page):
        path = self.out_path(page.slug)
        depth = path.count("/")
        where = "site/pages/%s.md" % page.slug
        heads = []
        body = self.markdown(page.body, where, depth, heads)
        parts = ["<h1>%s</h1>" % escape(page.title)]
        if page.summary and page.kind != "download":
            parts.append('<p class="lead">%s</p>' % self.inline(page.summary, where, depth))
        if len(heads) >= 3 and page.kind in ("guide", "effects"):
            parts.append('<details class="toc"><summary>On this page</summary><ul>%s</ul></details>' % "".join('<li><a href="#%s">%s</a></li>' % (i, escape(t)) for i, t in heads))
        if page.kind == "download":
            parts = [self.download_top(page, depth), self.download_all(depth), body]
        elif page.kind == "toc":
            parts.append(body)
            parts.append(self.toc(depth))
        elif page.kind == "effects":
            parts.append(body)
            parts.append(self.effects_index(depth))
        elif page.kind == "checker":
            parts.append(self.checker_box())
            parts.append(body)
            parts.append(self.brand_list())
        else:
            parts.append(body)
        parts.append(self.pager(page.slug, depth))
        title = "AbsentMotion: free video editor for Android" if page.kind == "download" else page.title + " | AbsentMotion"
        description = page.summary or "AbsentMotion, a free open-source motion graphics and video editor for Android."
        self.add(path, self.shell(path, title, re.sub(r"[`*]|\]\([^)]*\)|\[", "", description), "".join(parts), page.slug, page.kind, page.kind not in ("download",)))

    def toc(self, depth):
        out = ['<div class="toc-groups">']
        for group in GROUPS:
            members = sorted((p for p in self.pages.values() if p.group == group), key=lambda p: p.order)
            if not members:
                continue
            out.append("<section><h2>%s</h2><ul>" % escape(group))
            for page in members:
                out.append('<li><a href="%s%s">%s</a><span>%s</span></li>' % ("../" * depth, self.out_path(page.slug), escape(page.title), self.inline(page.summary, "toc", depth)))
            out.append("</ul></section>")
        out.append('<section><h2>Effects</h2><ul><li><a href="%seffects/index.html">All %d effects</a><span>Every effect with its settings, ranges and defaults.</span></li></ul></section></div>' % ("../" * depth, len(self.effects)))
        return "".join(out)

    def download_links(self):
        v = self.version
        base = "%s/releases/download/v%s/AbsentMotion-%s-" % (REPO, v, v)
        return {"foss": base + "foss.apk", "play": base + "play.apk", "compatible": base + "compatible.apk", "sums": "%s/releases/download/v%s/SHA256SUMS.txt" % (REPO, v)}

    def download_top(self, page, depth):
        return (
            '<section class="hero"><img class="hero-logo" src="assets/logo.png" width="96" height="96" alt="">'
            '<h1>AbsentMotion</h1><p class="lead">%s</p></section>'
            '<section id="rec" class="rec" aria-live="polite" data-version="%s"><p class="rec-wait">Looking at your phone&hellip;</p>'
            '<noscript><p><strong>Choose the file that matches your Android version.</strong> Android 13 or newer: the first one. Android 9 to 12: the Compatible one. '
            'To find your version open Settings, About phone, Android version.</p></noscript></section>'
        ) % (self.inline(page.summary, "download", depth), self.version)

    def download_all(self, depth):
        links = self.download_links()
        v = self.version
        return (
            '<section id="all"><h2>All downloads <span class="ver">version %s</span></h2><ul class="dl">'
            '<li><a id="dl-foss" class="btn" href="%s">Latest</a><div><strong>Android 13 or newer. Recommended.</strong> Every feature. '
            'Background removal uses its own offline model; this file contains no Google libraries. <code>AbsentMotion-%s-foss.apk</code></div></li>'
            '<li><a id="dl-play" class="btn alt" href="%s">Latest (Play services)</a><div><strong>Android 13 or newer.</strong> The same app, but background removal uses Google Play services, '
            'which downloads its own model and may send diagnostics to Google. <code>AbsentMotion-%s-play.apk</code></div></li>'
            '%s</ul>'
            '<p class="small"><a id="dl-sums" href="%s">SHA256SUMS.txt</a> has the checksum of every file. <a href="%s" rel="noopener">All releases and older versions</a> &middot; '
            '<a href="%s" rel="noopener">Source code</a></p></section>'
        ) % (v, links["foss"], v, links["play"], v, self.compatible_item(), links["sums"], SHORT["releases"], REPO)

    def compatible_item(self):
        v = self.version
        if tuple(int(part) for part in v.split(".")[:3]) < (3, 3, 0):
            return ('<li><span id="dl-compatible" class="btn alt" aria-disabled="true">Compatible</span><div><strong>Android 9 to 12.</strong> '
                    'Coming with the next update. <a href="compatible.html">What it is</a>.</div></li>')
        return ('<li><a id="dl-compatible" class="btn alt" href="%s">Compatible</a><div><strong>Android 9 to 12.</strong> Some features are left out: '
                '<a href="compatible.html">see the list</a>. <code>AbsentMotion-%s-compatible.apk</code></div></li>') % (self.download_links()["compatible"], v)

    def checker_box(self):
        return (
            '<section class="checker"><form id="checker-form" role="search" autocomplete="off"><label for="q">Type your phone model</label>'
            '<input id="q" type="search" inputmode="search" placeholder="for example Galaxy S21 Ultra, Pixel 7a, Redmi Note 12 or SM-G998B" autocapitalize="off" spellcheck="false"></form>'
            '<div id="results" class="results" aria-live="polite"><p class="muted">The check needs JavaScript. Without it, find your phone in the list of brands below, or open Settings, About phone, Android version: '
            '13 or newer means Latest, 9 to 12 means Compatible.</p></div></section>'
        )

    def brand_list(self):
        out = ['<section id="brands"><h2>Phones by brand</h2>']
        extra = SITE / "data/brand_ranges.md"
        if extra.is_file():
            out.append(self.markdown(re.sub(r"(?m)^## ", "### ", extra.read_text()), "site/data/brand_ranges.md", 0))
        names = {"latest": "Latest", "compatible": "Compatible", "unsupported": "Not supported"}
        out.append("<h3>Every phone in the list</h3><p>Open a brand to see each model with the Android version it came with and the newest one it can run.</p>")
        brands = {}
        for d in self.devices:
            brands.setdefault(d["brand"], []).append(d)
        for brand in sorted(brands):
            rows = sorted(brands[brand], key=lambda d: (-int(d.get("year") or 0), d["name"]))
            out.append('<details class="brand"><summary>%s <span>%d phones</span></summary><div class="table-wrap"><table><thead><tr><th>Phone</th><th>Year</th><th>Android</th><th>Version</th></tr></thead><tbody>' % (escape(brand), len(rows)))
            for d in rows:
                out.append('<tr><td data-label="Phone">%s</td><td data-label="Year">%s</td><td data-label="Android">%s to %s</td><td data-label="Version"><span class="tier %s">%s</span></td></tr>' % (
                    escape(d["name"]), d.get("year", ""), d.get("launchAndroid", ""), d.get("maxAndroid", ""), d["tier"], names[d["tier"]]))
            out.append("</tbody></table></div></details>")
        out.append("</section>")
        return "".join(out)

    def effects_index(self, depth):
        site = self.target == "site"
        out = []
        if site:
            out.append('<div id="fx-tools" class="fx-tools" hidden><input id="fxq" type="search" placeholder="Search effects" aria-label="Search effects" autocapitalize="off" spellcheck="false">'
                       '<div id="fx-chips" class="chips"></div><p id="fx-count" class="muted" aria-live="polite"></p></div>')
        out.append('<nav class="jump" aria-label="Categories">%s</nav>' % " ".join('<a href="#cat-%s">%s</a>' % (k, n) for k, n in CATEGORIES))
        for key, name in CATEGORIES:
            members = [e for e in self.effects if e["category"] == key]
            out.append('<section class="fx-cat" id="cat-%s" data-cat="%s"><h2>%s <span>%d</span></h2><ul class="%s">' % (key, key, name, len(members), "fx-grid" if site else "fx-list"))
            for e in members:
                text = escape((e["name"] + " " + e["description"]).lower())
                if site:
                    out.append('<li data-text="%s"><a href="%s.html"><img src="%s" width="96" height="96" alt="" loading="lazy"><span>%s</span></a></li>' % (
                        text, e["id"], "thumb/" + e["id"] + ".webp", escape(e["name"])))
                else:
                    out.append('<li><a href="%s.html">%s</a></li>' % (e["id"], escape(e["name"])))
            out.append("</ul></section>")
        return "".join(out)

    def render_effects(self):
        for n, e in enumerate(self.effects):
            path = "effects/%s.html" % e["id"]
            where = "app/src/main/assets/effects/%s.json" % e["id"]
            extra_path = SITE / "effects" / (e["id"] + ".md")
            extra = self.markdown(extra_path.read_text(), "site/effects/%s.md" % e["id"], 1) if extra_path.is_file() else ""
            category = dict(CATEGORIES)[e["category"]]
            since = e.get("since")
            added = "Added in version %s" % escape(since) if since else "Added in version 2.1.0 or earlier"
            body = ['<h1>%s</h1><p class="meta"><a href="index.html#cat-%s">%s</a> &middot; %s</p>' % (escape(e["name"]), e["category"], category, added)]
            body.append('<img class="fx-thumb" src="%s" width="192" height="192" alt="Preview of the %s effect">' % (self.thumb(e["id"] + ".webp", 1), escape(e["name"])))
            body.append("<p>%s</p>" % escape(e["description"]))
            body.append(extra)
            body.append(self.params_html(e))
            if e.get("presets"):
                body.append("<h2>Presets</h2><p>Pick one from the effect&rsquo;s detail page in the Effects Library. Each one is a ready-made setting you can keep editing.</p><ul class=\"presets\">")
                for p in e["presets"]:
                    slug = re.sub(r"[^a-z0-9]+", "-", p["name"].lower()).strip("-")
                    thumb = "%s.%s.webp" % (e["id"], slug)
                    image = '<img src="%s" width="96" height="96" alt="" loading="lazy">' % self.thumb(thumb, 1) if (EFFECTS / "thumb" / thumb).is_file() else ""
                    body.append("<li>%s<span>%s</span></li>" % (image, escape(p["name"])))
                body.append("</ul>")
            same = [x for x in self.effects if x["category"] == e["category"]]
            i = same.index(e)
            prev = '<a rel="prev" href="%s.html">&larr; %s</a>' % (same[i - 1]["id"], escape(same[i - 1]["name"])) if i else "<span></span>"
            nxt = '<a rel="next" href="%s.html">%s &rarr;</a>' % (same[i + 1]["id"], escape(same[i + 1]["name"])) if i + 1 < len(same) else "<span></span>"
            body.append('<nav class="pager" aria-label="More %s effects">%s%s</nav>' % (escape(category), prev, nxt))
            self.add(path, self.shell(path, e["name"] + " effect | AbsentMotion", e["description"], "".join(body), "effects", "effect"))
            if not e["description"]:
                self.fail(where, "no description")

    def params_html(self, e):
        if not e["params"]:
            return "<h2>Settings</h2><p>This effect has no settings.</p>"
        rows = []
        for p in e["params"]:
            kind, rng, default = describe(p)
            rows.append('<tr><td data-label="Setting"><strong>%s</strong></td><td data-label="Type">%s</td><td data-label="Range">%s</td><td data-label="Default">%s</td></tr>' % (escape(p["label"]), kind, rng, default))
        return '<h2>Settings</h2><div class="table-wrap"><table class="params"><thead><tr><th>Setting</th><th>Type</th><th>Range</th><th>Default</th></tr></thead><tbody>%s</tbody></table></div>' % "".join(rows)

    def add_site_assets(self):
        for name in ("site.css", "site.js", "logo.png"):
            self.files["assets/" + name] = (SITE / "assets" / name).read_bytes()
        for url in self.images:
            self.files["assets/" + url] = (SITE / "assets" / url).read_bytes()
        for thumb in sorted((EFFECTS / "thumb").glob("*.webp")):
            self.files["effects/thumb/" + thumb.name] = thumb.read_bytes()
        self.files["data/devices.json"] = (SITE / "data/devices.json").read_bytes()

    def add_app_assets(self):
        self.files["assets/site.css"] = (SITE / "assets/site.css").read_bytes()
        for url in self.images:
            self.files["assets/" + url] = (SITE / "assets" / url).read_bytes()


def describe(p):
    kind = p["type"]
    low, high, default = p.get("min"), p.get("max"), p["default"]
    if kind == "choice":
        options = p["options"]
        return "Choice", escape(", ".join(options)), escape(options[int(default)])
    if kind == "switch":
        return "On or off", "&ndash;", "On" if default in (True, 1) else "Off"
    if kind == "color":
        value = default.lstrip("#")
        rgb = value[-6:]
        alpha = int(value[:2], 16) / 255 if len(value) == 8 else 1
        note = "" if alpha > 0.995 else ", %d%% opaque" % round(alpha * 100)
        return "Color", "&ndash;", '<span><span class="sw" style="background:#%s"></span>#%s%s</span>' % (rgb, rgb.upper(), note)
    if kind == "text":
        return "Text", "&ndash;", escape(str(default)) or "(empty)"
    unit = ""
    scale = 1
    if p.get("percent"):
        unit, scale = "%", 100
    elif p.get("pixels"):
        unit = " px"
    elif p.get("unit"):
        unit = " " + p["unit"]
    elif kind == "angle":
        unit = "&deg;"

    def fmt(v):
        return number(v * scale) + unit

    if kind == "point":
        label = "Point (X, Y)"
        value = "%s, %s" % (fmt(default[0]), fmt(default[1]))
    else:
        label = {"angle": "Angle", "float": "Number"}[kind]
        value = fmt(default)
    rng = "%s to %s" % (fmt(low), fmt(high)) if low is not None and high is not None else "&ndash;"
    if p.get("layerTime"):
        rng += " (at most the layer&rsquo;s length)"
    return label, rng, value


def load_effects():
    index = json.loads((EFFECTS / "index.json").read_text())
    out = []
    for key in index:
        data = json.loads((EFFECTS / (key + ".json")).read_text())
        if data.get("hidden") or data.get("internal"):
            continue
        out.append({k: data.get(k) for k in ("id", "name", "category", "description", "params", "presets", "since")})
        out[-1]["params"] = data.get("params", [])
    return out


def load_devices():
    path = SITE / "data/devices.json"
    return json.loads(path.read_text()) if path.is_file() else []


def validate_devices(devices):
    problems = []
    for d in devices:
        name = "%s %s" % (d.get("brand"), d.get("name"))
        for key in DEVICE_KEYS:
            if key not in d:
                problems.append("devices.json: %s has no %s" % (name, key))
        if d.get("tier") not in TIERS:
            problems.append("devices.json: %s has a bad tier" % name)
        if d.get("aiClass") not in AI_CLASSES:
            problems.append("devices.json: %s has a bad aiClass" % name)
        newest = d.get("maxAndroid")
        if not isinstance(newest, (int, float)):
            problems.append("devices.json: %s has no numeric maxAndroid" % name)
            continue
        capable = d.get("arm64") is True and d.get("vulkan11") is True
        expected = "unsupported" if not capable or newest < 9 else "latest" if newest >= 13 else "compatible"
        if d.get("tier") != expected:
            problems.append("devices.json: %s is %s but its data says %s" % (name, d.get("tier"), expected))
    return problems


def check_links(files, target):
    problems = []
    ids = {}
    for path, data in files.items():
        if path.endswith(".html"):
            ids[path] = set(re.findall(r'\bid="([^"]+)"', data.decode()))
    for path, data in files.items():
        if not path.endswith(".html"):
            continue
        text = data.decode()
        for attr, ref in re.findall(r'\b(href|src)="([^"]*)"', text):
            if re.match(r"^(https://|mailto:)", ref):
                continue
            name, _, frag = ref.partition("#")
            if not name:
                if frag and frag not in ids[path]:
                    problems.append("%s: no anchor #%s" % (path, frag))
                continue
            resolved = (Path(path).parent / name)
            parts = []
            for part in resolved.parts:
                if part == "..":
                    if parts:
                        parts.pop()
                    else:
                        parts.append("..")
                elif part != ".":
                    parts.append(part)
            key = "/".join(parts)
            if key in files:
                if frag and key.endswith(".html") and frag not in ids[key]:
                    problems.append("%s: %s has no anchor #%s" % (path, key, frag))
                continue
            if target == "app" and key.startswith("../effects/thumb/") and (EFFECTS / "thumb" / key.split("/")[-1]).is_file():
                continue
            problems.append("%s: broken %s %s" % (path, attr, ref))
    return problems


def check_text(files):
    problems = []
    for path, data in files.items():
        if not path.endswith((".html", ".css", ".js", ".json")):
            continue
        text = data.decode()
        lower = text.lower()
        for word in OTHER_EDITORS:
            if word in lower and not path.startswith("effects/thumb"):
                problems.append("%s: names another editor (%s)" % (path, word))
        if "<!--" in text:
            problems.append(path + ": HTML comment")
        if re.search(r"github\.io", lower):
            problems.append(path + ": a Pages address")
        if re.search(r"/(Users|home)/\w|@[a-z0-9-]+\.(com|net|org)\b", text):
            problems.append(path + ": a path or e-mail address")
        if OWNER in text.replace(REPO, "").lower():
            problems.append(path + ": the repository owner name outside the repository address")
    return problems


def write(files, out):
    if out.exists():
        shutil.rmtree(out)
    for path, data in files.items():
        target = out / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def disk_files(folder):
    return {str(p.relative_to(folder)): p.read_bytes() for p in sorted(folder.rglob("*")) if p.is_file()} if folder.is_dir() else {}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="exit 1, changing nothing, if the in-app help is out of date or a link, a device entry or a rule is broken")
    args = parser.parse_args()
    site, app = Site("site"), Site("app")
    site_files, app_files = site.build(), app.build()
    problems = site.errors + app.errors + validate_devices(site.devices)
    problems += check_links(site_files, "site") + check_links(app_files, "app")
    problems += check_text(site_files) + check_text(app_files)
    if args.check:
        have = disk_files(HELP)
        for path in sorted(set(have) | set(app_files)):
            if have.get(path) != app_files.get(path):
                problems.append("app/src/main/assets/help/%s is out of date: run python3 tools/site_build.py" % path)
        node = shutil.which("node")
        test = ROOT / "tools/site_test.js"
        if node and test.is_file():
            run = subprocess.run([node, str(test)], capture_output=True, text=True)
            if run.returncode:
                problems.append("tools/site_test.js failed:\n" + (run.stdout + run.stderr).strip())
        for line in problems[:60]:
            print(line, file=sys.stderr)
        print("site_build: %d problem(s); %d site files, %d help files" % (len(problems), len(site_files), len(app_files)))
        return 1 if problems else 0
    if problems:
        for line in problems[:60]:
            print(line, file=sys.stderr)
        print("site_build: %d problem(s); nothing written" % len(problems), file=sys.stderr)
        return 1
    write(site_files, OUT)
    write(app_files, HELP)
    print("site_build: %d site files in build/site, %d help files in app/src/main/assets/help" % (len(site_files), len(app_files)))
    return 0


sys.exit(main())
