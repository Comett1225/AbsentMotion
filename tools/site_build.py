#!/usr/bin/env python3
import argparse
import json
import os
import re
from collections import Counter
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
HELP_DATA = SITE / "data/help-translations"
EFFECT_NAMES = EFFECTS / "i18n"
LANGUAGES_KT = ROOT / "app/src/main/kotlin/app/absentmotion/i18n/AppLanguage.kt"
RTL = {"ar", "fa"}
REPO = "https://github.com/Comett1225/AbsentMotion"
OWNER = REPO.split("/")[3].lower()
SHORT = {
    "repo": REPO,
    "releases": REPO + "/releases",
    "issues": REPO + "/issues",
    "report-form": "https://forms.gle/6bY8KBPMKc2iUypV6",
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
CHROME = [
    "Contents", "Effects", "Help", "On this page", "Next and previous page",
    "Start", "Interface", "Create", "Sound and AI", "Export and projects",
    "All {count} effects", "Every effect with its settings, ranges and defaults.", "Categories",
    "Added in version {version}", "Added in version 2.1.0 or earlier", "Preview of the {name} effect",
    "Presets", "Pick one from the effect\u2019s detail page in the Effects Library. Each one is a ready-made setting you can keep editing.",
    "Settings", "This effect has no settings.", "Setting", "Type", "Range", "Default", "More {category} effects",
    "Choice", "On or off", "On", "Off", "Color", "Text", "(empty)", "Point (X, Y)", "Angle", "Number",
    "{low} to {high}", "{range} (at most the layer\u2019s length)", "{color}, {percent}% opaque", ", ",
    "Palette", "{count} colors",
]
RTL_CSS = (
    "[dir=rtl] th,[dir=rtl] td{text-align:right}\n"
    "[dir=rtl] blockquote{border-left:0;border-right:4px solid var(--line)}\n"
    "[dir=rtl] .callout{border-left:0;border-right:4px solid var(--link);border-radius:.6rem 0 0 .6rem}\n"
    "[dir=rtl] .callout.warn{border-right-color:var(--bad)}\n"
    "[dir=rtl] .callout.tip{border-right-color:var(--ok)}\n"
    "[dir=rtl] .toc ul{padding-left:0;padding-right:1.1rem}\n"
    "[dir=rtl] .sw{margin-right:0;margin-left:.4em}\n"
    "[dir=rtl] pre,[dir=rtl] code{direction:ltr;unicode-bidi:isolate}\n"
)
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


class Locale:
    def __init__(self, tag, data, names):
        self.tag = tag
        self.pages = data.get("pages", {})
        self.effect_md = data.get("effects", {})
        self.chrome = data.get("chrome", {})
        self.fx = names.get("effects", {})
        self.categories = names.get("categories", {})


class Site:
    def __init__(self, target, locale=None, base=None):
        self.target = target
        self.locale = locale
        self.base = base
        self.up = 1 if locale else 0
        self.prefix = locale.tag + "/" if locale else ""
        self.rtl = bool(locale) and locale.tag in RTL
        self.chrome = locale.chrome if locale else {}
        self.version = version()
        self.errors = []
        self.files = {}
        self.images = set()
        self.pages = {}
        self.plan = None
        self.id_logs = {}
        for path in sorted((SITE / "pages").glob("*.md")):
            meta, body = front(path.read_text())
            page = Page(path.stem, meta, body)
            if target == "app" and page.site_only:
                continue
            if locale:
                text = locale.pages[page.slug]
                page.title, page.summary, page.body = text["title"], text.get("summary", ""), text["body"]
            self.pages[page.slug] = page
        self.effects = [self.localise(e) for e in load_effects()] if locale else load_effects()
        self.by_effect = {e["id"]: e for e in self.effects}
        self.devices = load_devices()

    def localise(self, effect):
        text = self.locale.fx[effect["id"]]
        out = dict(effect, name=text["name"], description=text["description"])
        params = []
        for p in effect["params"]:
            translated = text["params"][p["id"]]
            params.append(dict(p, label=translated["label"], **({"options": translated["options"]} if p["type"] == "choice" else {})))
        out["params"] = params
        out["presets"] = [dict(pr, slug=slugify(pr["name"]), name=text["presets"][slugify(pr["name"])]) for pr in effect.get("presets") or []]
        return out

    def raw(self, key):
        return self.chrome.get(key, key)

    def t(self, key, **values):
        text = escape(self.raw(key)).replace("\u2019", "&rsquo;")
        for name, value in values.items():
            text = text.replace("{%s}" % name, value)
        return text

    def category(self, key):
        return self.locale.categories[key] if self.locale else dict(CATEGORIES)[key]

    def heading_id(self, title, used, where):
        plan = self.plan
        if plan is not None and plan["queue"] is not None:
            if plan["queue"]:
                return plan["queue"].pop(0)
            self.fail(where, "more headings than the English page")
            return slugify(title), title
        ident = slugify(title)
        while ident in used:
            ident += "-2"
        used.add(ident)
        if plan is not None:
            plan["log"].append((ident, title))
        return ident, title

    def begin(self, key):
        self.plan = {"log": [], "queue": list(self.base.id_logs.get(key, [])) if self.locale else None}

    def end(self, key, where):
        plan, self.plan = self.plan, None
        if self.locale:
            if plan["queue"]:
                self.fail(where, "fewer headings than the English page")
        else:
            self.id_logs[key] = plan["log"]

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
        return "../" * (depth + 1 + self.up) + "effects/thumb/" + name

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
            return '<img src="%sassets/%s" width="%d" height="%d" alt="%s" loading="lazy">' % ("../" * (depth + self.up), url, width, height, alt)

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
                ident, english = self.heading_id(title, used, where)
                if heads is not None and level == 2 and english != "Next":
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
        prev = '<a rel="prev" href="%s%s">%s %s</a>' % (base, self.out_path(order[i - 1].slug), self.back(), escape(order[i - 1].title)) if i else "<span></span>"
        nxt = '<a rel="next" href="%s%s">%s %s</a>' % (base, self.out_path(order[i + 1].slug), escape(order[i + 1].title), self.forward()) if i + 1 < len(order) else "<span></span>"
        return '<nav class="pager" aria-label="%s">%s%s</nav>' % (self.t("Next and previous page"), prev, nxt)

    def back(self):
        return "&rarr;" if self.rtl else "&larr;"

    def forward(self):
        return "&larr;" if self.rtl else "&rarr;"

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
            top = '<header class="top appbar"><nav class="main" aria-label="%s"><a href="%sindex.html">%s</a><a href="%seffects/index.html">%s</a></nav></header>' % (
                self.t("Help"), base, self.t("Contents"), base, self.t("Effects"))
        side = ""
        if sidebar and site:
            side = '<input type="checkbox" id="menu" class="menu-box" aria-label="Show the page list"><label for="menu" class="menu-btn">Pages</label><nav class="side" aria-label="Pages">%s</nav>' % self.sidebar(current, depth)
        foot = (
            '<footer><p>AbsentMotion is free and open source under the Apache License 2.0. <a href="%s" rel="noopener">Source code</a> &middot; '
            '<a href="%s" rel="noopener">Privacy</a> &middot; <a href="%s" rel="noopener">Report a problem</a> &middot; '
            '<a href="%s" rel="noopener">Bug report form</a></p></footer>'
            % (REPO, SHORT["privacy-file"], SHORT["issues"], SHORT["report-form"])) if site else ""
        script = '<script src="%sassets/site.js" defer></script>' % base if site else ""
        shared = "../" * (depth + self.up)
        html = '<html lang="%s" dir="%s">' % (self.locale.tag, "rtl" if self.rtl else "ltr") if self.locale else '<html lang="en">'
        style = '<link rel="stylesheet" href="%sassets/site.css">' % shared + ('<link rel="stylesheet" href="%sassets/rtl.css">' % shared if self.rtl else "")
        return (
            '<!doctype html>\n%s<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>%s</title><meta name="description" content="%s"><meta name="color-scheme" content="light dark">'
            '%s%s%s</head>'
            '<body class="%s" data-page="%s">%s%s<div class="wrap">%s<main id="main">%s</main></div>%s</body></html>\n'
        ) % (html, escape(title), escape(description), '<link rel="icon" type="image/png" href="%sassets/logo.png">' % base if site else "", style, script, "site" if site else "app", kind, '<a class="skip" href="#main">Skip to the content</a>' if site else "", top, side, body, foot)

    def add(self, path, text):
        self.files[self.prefix + path] = text.encode()

    def build(self):
        for page in self.pages.values():
            self.render_page(page)
        self.render_effects()
        if self.target == "site":
            self.add_site_assets()
        elif not self.locale:
            self.add_app_assets()
        return self.files

    def render_page(self, page):
        path = self.out_path(page.slug)
        depth = path.count("/")
        where = "site/pages/%s.md" % page.slug
        heads = []
        self.begin(page.slug)
        if page.kind == "html" and self.target == "site":
            body = (SITE / "pages" / (page.slug + ".html")).read_text()
        else:
            body = self.markdown(page.body, where, depth, heads)
        self.end(page.slug, "locale %s, page %s" % (self.locale.tag, page.slug) if self.locale else where)
        parts = ["<h1>%s</h1>" % escape(page.title)]
        if page.summary and page.kind != "download":
            parts.append('<p class="lead">%s</p>' % self.inline(page.summary, where, depth))
        if len(heads) >= 3 and page.kind in ("guide", "effects"):
            parts.append('<details class="toc"><summary>%s</summary><ul>%s</ul></details>' % (self.t("On this page"), "".join('<li><a href="#%s">%s</a></li>' % (i, escape(t)) for i, t in heads)))
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
            out.append("<section><h2>%s</h2><ul>" % escape(self.raw(group)))
            for page in members:
                out.append('<li><a href="%s%s">%s</a><span>%s</span></li>' % ("../" * depth, self.out_path(page.slug), escape(page.title), self.inline(page.summary, "toc", depth)))
            out.append("</ul></section>")
        out.append('<section><h2>%s</h2><ul><li><a href="%seffects/index.html">%s</a><span>%s</span></li></ul></section></div>' % (
            self.t("Effects"), "../" * depth, self.t("All {count} effects", count=str(len(self.effects))), self.t("Every effect with its settings, ranges and defaults.")))
        return "".join(out)

    def download_links(self):
        v = self.version
        base = "%s/releases/download/v%s/AbsentMotion-%s-" % (REPO, v, v)
        return {"foss": base + "foss.apk", "play": base + "play.apk", "compatible": base + "compatible.apk", "compatible32": base + "compatible-32bit.apk", "sums": "%s/releases/download/v%s/SHA256SUMS.txt" % (REPO, v)}

    def download_top(self, page, depth):
        return (
            '<section class="hero"><img class="hero-logo" src="assets/logo.png" width="96" height="96" alt="">'
            '<h1>AbsentMotion</h1><p class="lead">%s</p></section>'
            '<section id="rec" class="rec" aria-live="polite" data-version="%s"><p class="rec-wait">Looking at your phone&hellip;</p>'
            '<noscript><p><strong>Choose the file that matches your Android version.</strong> Android 13 or newer: the first one. Android 8 to 12: the Compatible one. '
            'Phones with a 32-bit system, often sold as Android Go edition: the Compatible 32-bit one. To find your version open Settings, About phone, Android version.</p></noscript></section>'
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
        released = tuple(int(part) for part in v.split(".")[:3])
        if released < (3, 3, 0):
            return ('<li><span id="dl-compatible" class="btn alt" aria-disabled="true">Compatible</span><div><strong>Android 8 to 12.</strong> '
                    'Coming with the next update. <a href="compatible.html">What it is</a>.</div></li>')
        item = ('<li><a id="dl-compatible" class="btn alt" href="%s">Compatible</a><div><strong>Android 8 to 12.</strong> Some features are left out: '
                '<a href="compatible.html">see the list</a>. <code>AbsentMotion-%s-compatible.apk</code></div></li>') % (self.download_links()["compatible"], v)
        if released < (4, 3, 0):
            return item + ('<li><span id="dl-compatible32" class="btn alt" aria-disabled="true">Compatible 32-bit</span><div><strong>Phones with a 32-bit system.</strong> '
                           'Coming with the next update.</div></li>')
        return item + ('<li><a id="dl-compatible32" class="btn alt" href="%s">Compatible 32-bit</a><div><strong>Phones with a 32-bit system</strong>, often sold as Android Go edition, '
                       'Android 8 or newer. The Compatible version built for 32-bit ARM. <code>AbsentMotion-%s-compatible-32bit.apk</code></div></li>') % (self.download_links()["compatible32"], v)

    def checker_box(self):
        return (
            '<section class="checker"><form id="checker-form" role="search" autocomplete="off"><label for="q">Type your phone model</label>'
            '<input id="q" type="search" inputmode="search" placeholder="for example Galaxy S21 Ultra, Pixel 7a, Redmi Note 12 or SM-G998B" autocapitalize="off" spellcheck="false"></form>'
            '<div id="results" class="results" aria-live="polite"><p class="muted">The check needs JavaScript. Without it, find your phone in the list of brands below, or open Settings, About phone, Android version: '
            '13 or newer means Latest, 8 to 12 means Compatible.</p></div></section>'
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
        out.append('<nav class="jump" aria-label="%s">%s</nav>' % (self.t("Categories"), " ".join('<a href="#cat-%s">%s</a>' % (k, escape(self.category(k))) for k, n in CATEGORIES)))
        for key, _ in CATEGORIES:
            name = escape(self.category(key))
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
            extra = ""
            if extra_path.is_file():
                source = self.locale.effect_md[e["id"]] if self.locale else extra_path.read_text()
                self.begin("effect/" + e["id"])
                extra = self.markdown(source, "site/effects/%s.md" % e["id"], 1)
                self.end("effect/" + e["id"], "locale %s, effect %s" % (self.locale.tag, e["id"]) if self.locale else where)
            category = escape(self.category(e["category"]))
            since = e.get("since")
            added = self.t("Added in version {version}", version=escape(since)) if since else self.t("Added in version 2.1.0 or earlier")
            body = ['<h1>%s</h1><p class="meta"><a href="index.html#cat-%s">%s</a> &middot; %s</p>' % (escape(e["name"]), e["category"], category, added)]
            body.append('<img class="fx-thumb" src="%s" width="192" height="192" alt="%s">' % (self.thumb(e["id"] + ".webp", 1), self.t("Preview of the {name} effect", name=escape(e["name"]))))
            body.append("<p>%s</p>" % escape(e["description"]))
            body.append(extra)
            body.append(self.params_html(e))
            if e.get("presets"):
                body.append("<h2>%s</h2><p>%s</p><ul class=\"presets\">" % (self.t("Presets"), self.t("Pick one from the effect\u2019s detail page in the Effects Library. Each one is a ready-made setting you can keep editing.")))
                for p in e["presets"]:
                    slug = p.get("slug") or slugify(p["name"])
                    thumb = "%s.%s.webp" % (e["id"], slug)
                    image = '<img src="%s" width="96" height="96" alt="" loading="lazy">' % self.thumb(thumb, 1) if (EFFECTS / "thumb" / thumb).is_file() else ""
                    body.append("<li>%s<span>%s</span></li>" % (image, escape(p["name"])))
                body.append("</ul>")
            same = [x for x in self.effects if x["category"] == e["category"]]
            i = same.index(e)
            prev = '<a rel="prev" href="%s.html">%s %s</a>' % (same[i - 1]["id"], self.back(), escape(same[i - 1]["name"])) if i else "<span></span>"
            nxt = '<a rel="next" href="%s.html">%s %s</a>' % (same[i + 1]["id"], escape(same[i + 1]["name"]), self.forward()) if i + 1 < len(same) else "<span></span>"
            body.append('<nav class="pager" aria-label="%s">%s%s</nav>' % (self.t("More {category} effects", category=category), prev, nxt))
            self.add(path, self.shell(path, e["name"] + (" | AbsentMotion" if self.locale else " effect | AbsentMotion"), e["description"], "".join(body), "effects", "effect"))
            if not e["description"]:
                self.fail(where, "no description")

    def params_html(self, e):
        if not e["params"]:
            return "<h2>%s</h2><p>%s</p>" % (self.t("Settings"), self.t("This effect has no settings."))
        rows = []
        names = [self.t(n) for n in ("Setting", "Type", "Range", "Default")]
        for p in e["params"]:
            kind, rng, default = self.describe(p)
            rows.append('<tr><td data-label="%s"><strong>%s</strong></td><td data-label="%s">%s</td><td data-label="%s">%s</td><td data-label="%s">%s</td></tr>' % (
                names[0], escape(p["label"]), names[1], kind, names[2], rng, names[3], default))
        return '<h2>%s</h2><div class="table-wrap"><table class="params"><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>' % (
            self.t("Settings"), "".join("<th>%s</th>" % n for n in names), "".join(rows))

    def describe(self, p):
        t = self.t
        kind = p["type"]
        low, high, default = p.get("min"), p.get("max"), p["default"]
        if kind == "choice":
            options = p["options"]
            return t("Choice"), escape(self.raw(", ").join(options)), escape(options[int(default)])
        if kind == "switch":
            return t("On or off"), "&ndash;", t("On") if default in (True, 1) else t("Off")
        if kind == "color":
            value = default.lstrip("#")
            rgb = value[-6:]
            alpha = int(value[:2], 16) / 255 if len(value) == 8 else 1
            shown = "#" + rgb.upper() if alpha > 0.995 else t("{color}, {percent}% opaque", color="#" + rgb.upper(), percent=str(round(alpha * 100)))
            return t("Color"), "&ndash;", '<span><span class="sw" style="background:#%s"></span>%s</span>' % (rgb, shown)
        if kind == "text" and p.get("palette"):
            colors = re.findall(r"[0-9a-fA-F]{6}", str(default))
            return t("Palette"), "&ndash;", t("{count} colors", count=str(len(colors)))
        if kind == "text":
            return t("Text"), "&ndash;", escape(str(default)) or t("(empty)")
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
            label = t("Point (X, Y)")
            value = "%s, %s" % (fmt(default[0]), fmt(default[1]))
        else:
            label = t({"angle": "Angle", "float": "Number"}[kind])
            value = fmt(default)
        rng = t("{low} to {high}", low=fmt(low), high=fmt(high)) if low is not None and high is not None else "&ndash;"
        if p.get("layerTime"):
            rng = t("{range} (at most the layer\u2019s length)", range=rng)
        return label, rng, value

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
        expected = "unsupported" if newest < 8 else "latest" if newest >= 13 and d.get("arm64") is True else "compatible"
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
            if path == "roadmap.html" and word in ("alight", "capcut"):
                continue
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


def supported_tags():
    return re.findall(r'^\s+\w+\("([A-Za-z-]+)", "[^"]+"\)', LANGUAGES_KT.read_text(), re.M)


def load_locales(problems):
    out = []
    supported = set(supported_tags()) - {"en"}
    if not HELP_DATA.is_dir():
        return out
    for path in sorted(HELP_DATA.glob("*.json")):
        tag = path.stem
        where = "site/data/help-translations/" + path.name
        if tag not in supported:
            problems.append("%s: %s is not an app language" % (where, tag))
            continue
        names = EFFECT_NAMES / (tag + ".json")
        if not names.is_file():
            problems.append("%s: app/src/main/assets/effects/i18n/%s.json is missing" % (where, tag))
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            names = json.loads(names.read_text(encoding="utf-8"))
        except ValueError as error:
            problems.append("%s: not valid JSON (%s)" % (where, error))
            continue
        if not isinstance(data, dict) or set(data) != {"pages", "effects", "chrome"} or not all(isinstance(data[k], dict) for k in data):
            problems.append("%s: needs exactly the objects pages, effects and chrome" % where)
            continue
        out.append(Locale(tag, data, names))
    return out


def outline(text):
    heads, blocks, fences, tables, items = [], [], 0, 0, 0
    inside = False
    for line in text.split("\n"):
        if line.startswith("```"):
            fences += 1
            inside = not inside
            continue
        if inside:
            continue
        match = re.match(r"^(#{2,4}) ", line)
        if match:
            heads.append(len(match.group(1)))
        match = re.match(r"^::: (\w+)\s*$", line)
        if match:
            blocks.append(match.group(1))
        elif line.strip() == ":::":
            blocks.append(":::")
        tables += line.startswith("|")
        items += bool(re.match(r"^\s*([-*]|\d+\.)\s+", line))
    return {
        "heading levels": heads, "blocks": blocks, "code fences": fences, "table lines": tables, "list items": items,
        "backticks": text.count("`"), "references": Counter(re.findall(r"\]\(([^)\s]+)\)", text)),
        "placeholders": Counter(re.findall(r"\{\{\w+\}\}", text)),
    }


def unsafe(text, source=""):
    problems = []
    if len(re.findall(r"<[A-Za-z/!?]", text)) > len(re.findall(r"<[A-Za-z/!?]", source)):
        problems.append("raw HTML")
    if re.search("[‪-‮⁦-⁩]", text):
        problems.append("a bidirectional override character")
    if re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", text):
        problems.append("a control character")
    return problems


def compare(where, source, text):
    problems = []
    want, have = outline(source), outline(text)
    for key in want:
        if want[key] == have[key]:
            continue
        if isinstance(want[key], Counter):
            gone = sorted((want[key] - have[key]).elements())
            added = sorted((have[key] - want[key]).elements())
            problems.append("%s: %s differ from the English (missing %s; unexpected %s)" % (where, key, gone or "none", added or "none"))
        else:
            problems.append("%s: %s differ from the English (%s, expected %s)" % (where, key, have[key], want[key]))
    return problems + ["%s: %s" % (where, kind) for kind in unsafe(text, source)]


def one_line(where, value, source=""):
    if not isinstance(value, str) or not value.strip() or "\n" in value:
        return ["%s: must be one non-empty line" % where]
    return ["%s: %s" % (where, kind) for kind in unsafe(value, source)]


def validate_locale(locale, english):
    out = []
    where = "site/data/help-translations/%s.json" % locale.tag
    sources = english.pages
    for slug in sorted(set(sources) - set(locale.pages)):
        out.append("%s: no translation of page %s" % (where, slug))
    for slug in sorted(set(locale.pages) - set(sources)):
        out.append("%s: page %s does not exist in the app help" % (where, slug))
    for slug, page in sorted(locale.pages.items()):
        if slug not in sources:
            continue
        place = "%s: page %s" % (where, slug)
        source = sources[slug]
        if not isinstance(page, dict) or set(page) - {"title", "summary", "body"} or not {"title", "body"} <= set(page):
            out.append("%s: needs title, summary and body only" % place)
            continue
        out += one_line(place + " title", page["title"])
        if source.summary:
            out += one_line(place + " summary", page.get("summary"), source.summary)
            if isinstance(page.get("summary"), str):
                out += compare(place + " summary", source.summary, page["summary"])
        elif page.get("summary"):
            out.append("%s: has a summary but the English has none" % place)
        if not isinstance(page["body"], str) or not page["body"].strip():
            out.append("%s: empty body" % place)
        else:
            out += compare(place + " body", source.body, page["body"])
    wanted = {e["id"] for e in english.effects if (SITE / "effects" / (e["id"] + ".md")).is_file()}
    for key in sorted(wanted - set(locale.effect_md)):
        out.append("%s: no translation of effects/%s.md" % (where, key))
    for key in sorted(set(locale.effect_md) - wanted):
        out.append("%s: effects/%s.md does not exist" % (where, key))
    for key in sorted(wanted & set(locale.effect_md)):
        text = locale.effect_md[key]
        if not isinstance(text, str) or not text.strip():
            out.append("%s: effect %s is empty" % (where, key))
        else:
            out += compare("%s: effect %s" % (where, key), (SITE / "effects" / (key + ".md")).read_text(), text)
    for key in sorted(set(CHROME) - set(locale.chrome)):
        out.append("%s: no chrome label for %r" % (where, key))
    for key in sorted(set(locale.chrome) - set(CHROME)):
        out.append("%s: unknown chrome label %r" % (where, key))
    for key in CHROME:
        value = locale.chrome.get(key)
        if value is None:
            continue
        if key == ", ":
            if not isinstance(value, str) or not value or "\n" in value:
                out.append("%s: chrome label ', ' must be a non-empty separator" % where)
            continue
        out += one_line("%s: chrome label %r" % (where, key), value, key)
        if isinstance(value, str) and Counter(re.findall(r"\{\w+\}", value)) != Counter(re.findall(r"\{\w+\}", key)):
            out.append("%s: chrome label %r must keep the placeholders %s" % (where, key, re.findall(r"\{\w+\}", key)))
    return out + validate_names(locale, english, "app/src/main/assets/effects/i18n/%s.json" % locale.tag)


def validate_names(locale, english, where):
    out = []
    for key in sorted({k for k, _ in CATEGORIES} - set(locale.categories)):
        out.append("%s: no category %s" % (where, key))
    for e in english.effects:
        text = locale.fx.get(e["id"])
        if not isinstance(text, dict) or not text.get("name") or not text.get("description"):
            out.append("%s: effect %s has no name or description" % (where, e["id"]))
            continue
        for p in e["params"]:
            translated = (text.get("params") or {}).get(p["id"]) or {}
            if not translated.get("label"):
                out.append("%s: effect %s setting %s has no label" % (where, e["id"], p["id"]))
            if p["type"] == "choice" and len(translated.get("options") or []) != len(p["options"]):
                out.append("%s: effect %s setting %s needs %d options" % (where, e["id"], p["id"], len(p["options"])))
        for preset in e.get("presets") or []:
            if not (text.get("presets") or {}).get(slugify(preset["name"])):
                out.append("%s: effect %s has no name for the preset %s" % (where, e["id"], preset["name"]))
    return out


def normal(path):
    parts = []
    for part in path.parts:
        if part == "..":
            if parts:
                parts.pop()
        elif part != ".":
            parts.append(part)
    return "/".join(parts)


def check_navigation(files, tag, english_paths):
    prefix = tag + "/"
    mine = {p[len(prefix):] for p in files if p.startswith(prefix)}
    problems = ["%s: missing %s" % (tag, p) for p in sorted(english_paths - mine)]
    problems += ["%s: unexpected %s" % (tag, p) for p in sorted(mine - english_paths)]
    seen, queue = set(), ["index.html"]
    while queue:
        current = queue.pop()
        if current in seen or prefix + current not in files:
            continue
        seen.add(current)
        for ref in re.findall(r'\bhref="([^"#]*)', files[prefix + current].decode()):
            if ref and not re.match(r"^(https://|mailto:)", ref):
                queue.append(normal(Path(current).parent / ref))
    return problems + ["%s: %s cannot be reached from the contents page" % (tag, p) for p in sorted(english_paths - seen)]


def check_app_html(files):
    problems = []
    for path, data in files.items():
        if not path.endswith(".html"):
            continue
        text = data.decode()
        if re.search(r"<(script|iframe|object|embed|form|base)\b|\son[a-z]+\s*=|javascript:|http://", text, re.I):
            problems.append(path + ": script, active content or an unsafe address")
        if not re.search(r"<html lang=\"[A-Za-z-]+\"", text):
            problems.append(path + ": no lang attribute")
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
    parser.add_argument("--chrome-keys", action="store_true", help="print the English interface labels each help translation must translate, as JSON")
    args = parser.parse_args()
    if args.chrome_keys:
        print(json.dumps({key: key for key in CHROME}, ensure_ascii=False, indent=2))
        return 0
    site, app = Site("site"), Site("app")
    site_files, app_files = site.build(), app.build()
    problems = site.errors + app.errors + validate_devices(site.devices)
    english_paths = {p for p in app_files if p.endswith(".html")}
    for locale in load_locales(problems):
        found = validate_locale(locale, app)
        problems += found
        if found:
            continue
        built = Site("app", locale, app)
        app_files.update(built.build())
        problems += built.errors + check_navigation(app_files, locale.tag, english_paths)
        if built.rtl:
            app_files["assets/rtl.css"] = RTL_CSS.encode()
    problems += check_links(site_files, "site") + check_links(app_files, "app")
    problems += check_text(site_files) + check_text(app_files) + check_app_html(app_files)
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
        elif test.is_file() and os.environ.get("CI"):
            problems.append("node is not installed, so tools/site_test.js did not run")
        helptest = subprocess.run([sys.executable, "-I", str(ROOT / "tools/site_help_test.py")], capture_output=True, text=True)
        if helptest.returncode:
            problems.append("tools/site_help_test.py failed:\n" + (helptest.stdout + helptest.stderr).strip()[-3000:])
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
    print("site_build: %d site files in build/site, %d help files in app/src/main/assets/help (%d languages)" % (len(site_files), len(app_files), len({p.split("/")[0] for p in app_files if re.match(r"^[a-z]{2,3}(-[A-Z]{2})?/", p)})))
    return 0


if __name__ == "__main__":
    sys.exit(main())
