#!/usr/bin/env python3
import argparse, hashlib, io, json, re, sys, urllib.error, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = "https://fonts.google.com/metadata/fonts"
RAW = "https://raw.githubusercontent.com/google/fonts/%s/%s"
COMMIT = "47d54ca1ebb14fd864695f1d39dc734f39017c33"
OUT = ROOT / "app/src/main/kotlin/app/absentmotion/fonts/FontCatalog.kt"
MANIFEST = ROOT / "tools/fonts_manifest.json"
ASSETS = ROOT / "app/src/main/assets/fonts"
SOURCES = ROOT / "build/fontsrc"
NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"
DOWNLOADABLE = 100
DOWNLOAD_QUOTA = {1: 24, 2: 20, 3: 20, 4: 18, 5: 10}
FORCED_DOWNLOADS = {"Noto Sans", "Noto Sans JP", "Noto Sans KR", "Noto Sans SC", "Noto Sans TC", "Hind Siliguri", "Heebo", "Mukta"}

CATEGORIES = {"Sans Serif": 1, "Serif": 2, "Display": 3, "Handwriting": 4, "Monospace": 5}

SCRIPTS = {
    "arabic": (1, "Arabic"), "armenian": (2, "Armenian"), "bengali": (3, "Bengali"),
    "chinese-hongkong": (4, "Chinese (Hong Kong)"), "chinese-simplified": (5, "Chinese (Simplified)"),
    "chinese-traditional": (6, "Chinese (Traditional)"), "cyrillic": (7, "Cyrillic"), "cyrillic-ext": (8, "Cyrillic Extended"),
    "devanagari": (9, "Devanagari"), "ethiopic": (10, "Ethiopic"), "georgian": (11, "Georgian"), "greek": (12, "Greek"),
    "greek-ext": (13, "Greek Extended"), "gujarati": (14, "Gujarati"), "gurmukhi": (15, "Gurmukhi"), "hebrew": (16, "Hebrew"),
    "japanese": (17, "Japanese"), "kannada": (18, "Kannada"), "khmer": (19, "Khmer"), "korean": (20, "Korean"),
    "lao": (21, "Lao"), "latin": (22, "Latin"), "latin-ext": (23, "Latin Extended"), "malayalam": (24, "Malayalam"),
    "myanmar": (25, "Myanmar"), "oriya": (26, "Oriya"), "sinhala": (27, "Sinhala"), "syriac": (28, "Syriac"),
    "tamil": (29, "Tamil"), "telugu": (30, "Telugu"), "thai": (31, "Thai"), "tibetan": (32, "Tibetan"),
    "vietnamese": (33, "Vietnamese"),
}

NOT_SERVED = {"Roboto Flex"}

PROBES = {
    22: "ABCabcxyz0123", 23: "ĄąĆćĘęŁłŃńŚśŹźŻżĞğŞşİıŐő", 33: "ẠạẢảẤấẦầẬậƠơƯưĐđỲỳẴẵ₫",
    7: "АБВЖЯабвжяЁёІіЇїЄєҐґ",
    9: "कखगहअआइउएओ्ािीुूं", 31: "กขคฮะาเแโใไ่้๊็ั", 1: "ابتثجحخدذرزسشصضطظعغفقكلمنهوي",
}

LATIN_RANGES = "0000-024F,0259,0300-036F,1E00-1EFF,2000-206F,20A0-20CF,2100-2122,2190-2193,2212,2215,25CC,FB01-FB02"
SCRIPT_RANGES = {
    "cyrillic": "0301,0400-045F,0490-0491,04B0-04B1,2116",
    "devanagari": "0900-097F,1CD0-1CFF,A8E0-A8FF,200C-200D", "thai": "0E00-0E7F",
    "arabic": "0600-06FF,0750-077F,08A0-08FF,FB50-FDFF,FE70-FEFF,200C-200F",
}
COMMON_SCRIPTS = ("cyrillic",)

def static(directory, stem):
    return {"dir": directory, "files": {(400, 0): stem + "-Regular.ttf", (700, 0): stem + "-Bold.ttf"}}

def variable(directory, file, italic=None):
    files = {(400, 0): file, (700, 0): file}
    if italic:
        files.update({(400, 1): italic, (700, 1): italic})
    return {"dir": directory, "files": files}

def single(directory, file, **extra):
    return {"dir": directory, "files": {(400, 0): file}, **extra}

BUILTIN = {
    "Roboto": variable("ofl/roboto", "Roboto[wdth,wght].ttf"),
    "Open Sans": variable("ofl/opensans", "OpenSans[wdth,wght].ttf"),
    "Inter": variable("ofl/inter", "Inter[opsz,wght].ttf"),
    "Montserrat": variable("ofl/montserrat", "Montserrat[wght].ttf"),
    "Poppins": {**static("ofl/poppins", "Poppins"), "scripts": ("devanagari",)},
    "Mulish": variable("ofl/mulish", "Mulish[wght].ttf"),
    "Oswald": variable("ofl/oswald", "Oswald[wght].ttf"),
    "Raleway": variable("ofl/raleway", "Raleway[wght].ttf"),
    "Nunito": variable("ofl/nunito", "Nunito[wght].ttf"),
    "Rubik": variable("ofl/rubik", "Rubik[wght].ttf"),
    "Work Sans": variable("ofl/worksans", "WorkSans[wght].ttf"),
    "DM Sans": variable("ofl/dmsans", "DMSans[opsz,wght].ttf"),
    "Manrope": variable("ofl/manrope", "Manrope[wght].ttf"),
    "Quicksand": variable("ofl/quicksand", "Quicksand[wght].ttf"),
    "Kanit": {**static("ofl/kanit", "Kanit"), "scripts": ("thai",)},
    "Prompt": {**static("ofl/prompt", "Prompt"), "scripts": ("thai",)},
    "Cairo": {**variable("ofl/cairo", "Cairo[slnt,wght].ttf"), "scripts": ("arabic",)},
    "Tajawal": {**static("ofl/tajawal", "Tajawal"), "scripts": ("arabic",)},
    "Hind": {**static("ofl/hind", "Hind"), "scripts": ("devanagari",)},
    "Fredoka": variable("ofl/fredoka", "Fredoka[wdth,wght].ttf"),
    "Bebas Neue": single("ofl/bebasneue", "BebasNeue-Regular.ttf", category=3),
    "Anton": single("ofl/anton", "Anton-Regular.ttf", category=3),
    "Lobster": single("ofl/lobster", "Lobster-Regular.ttf"),
    "Bangers": single("ofl/bangers", "Bangers-Regular.ttf"),
    "Press Start 2P": single("ofl/pressstart2p", "PressStart2P-Regular.ttf"),
    "Righteous": single("ofl/righteous", "Righteous-Regular.ttf"),
    "Abril Fatface": single("ofl/abrilfatface", "AbrilFatface-Regular.ttf", category=3),
    "Alfa Slab One": single("ofl/alfaslabone", "AlfaSlabOne-Regular.ttf"),
    "Bungee": single("ofl/bungee", "Bungee-Regular.ttf"),
    "Orbitron": variable("ofl/orbitron", "Orbitron[wght].ttf"),
    "Black Ops One": single("ofl/blackopsone", "BlackOpsOne-Regular.ttf"),
    "Comfortaa": variable("ofl/comfortaa", "Comfortaa[wght].ttf"),
    "Playfair Display": variable("ofl/playfairdisplay", "PlayfairDisplay[wght].ttf"),
    "Noto Serif": variable("ofl/notoserif", "NotoSerif[wdth,wght].ttf"),
    "Lora": variable("ofl/lora", "Lora[wght].ttf"),
    "Roboto Slab": variable("apache/robotoslab", "RobotoSlab[wght].ttf"),
    "DM Serif Display": single("ofl/dmserifdisplay", "DMSerifDisplay-Regular.ttf"),
    "Cinzel": variable("ofl/cinzel", "Cinzel[wght].ttf"),
    "Bitter": variable("ofl/bitter", "Bitter[wght].ttf"),
    "Pacifico": single("ofl/pacifico", "Pacifico-Regular.ttf"),
    "Dancing Script": variable("ofl/dancingscript", "DancingScript[wght].ttf"),
    "Caveat": variable("ofl/caveat", "Caveat[wght].ttf"),
    "Permanent Marker": single("apache/permanentmarker", "PermanentMarker-Regular.ttf"),
    "Satisfy": single("apache/satisfy", "Satisfy-Regular.ttf"),
    "Great Vibes": single("ofl/greatvibes", "GreatVibes-Regular.ttf"),
    "Kaushan Script": single("ofl/kaushanscript", "KaushanScript-Regular.ttf"),
    "Amatic SC": static("ofl/amaticsc", "AmaticSC"),
    "Roboto Mono": variable("ofl/robotomono", "RobotoMono[wght].ttf"),
    "JetBrains Mono": variable("ofl/jetbrainsmono", "JetBrainsMono[wght].ttf"),
    "Space Mono": static("ofl/spacemono", "SpaceMono"),
}

STYLE = {(400, 0): "Regular", (700, 0): "Bold", (400, 1): "Italic", (700, 1): "BoldItalic"}

def slug(family):
    return re.sub(r"[^A-Za-z0-9]", "", family)

def asset_name(family, weight, italic):
    return "%s/%s-%s.ttf" % (slug(family), slug(family), STYLE[(weight, italic)])

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def fetch_url(url):
    request = urllib.request.Request(url, headers={"User-Agent": "AbsentMotion-gen_fonts/1.0"})
    return urllib.request.urlopen(request, timeout=120).read()

def load(source):
    text = Path(source).read_text("utf-8") if source else fetch_url(URL).decode("utf-8")
    if text.startswith(")]}'"):
        text = text.split("\n", 1)[1]
    return json.loads(text)["familyMetadataList"]

def variant_codes(font):
    codes = set()
    for key in font["fonts"]:
        weight = int(key.rstrip("i"))
        if weight % 100 == 0 and 100 <= weight <= 900:
            codes.add(weight // 100 + (10 if key.endswith("i") else 0))
    return sorted(codes, key=lambda code: (code % 10, code))

def usable(font):
    name = font["family"]
    scripts = sorted(SCRIPTS[s][0] for s in font["subsets"] if s in SCRIPTS)
    codes = variant_codes(font)
    ok = (name not in NOT_SERVED and font["category"] in CATEGORIES and font.get("isOpenSource", True) and not font.get("colorCapabilities")
          and "Symbols" not in font.get("classifications", []) and scripts and codes)
    if ok:
        assert re.fullmatch(r'[^|$"\n\\]+', name), name
    return (name, CATEGORIES[font["category"]], scripts, codes) if ok else None

def downloadable_variants(codes):
    upright = [c for c in codes if c < 10]
    wanted = [c for c in (4, 7) if c in upright]
    return wanted or [min(upright, key=lambda c: abs(c - 4))] if upright else []

def select(families, builtin):
    rows = []
    quota = dict(DOWNLOAD_QUOTA)
    for font in sorted(families, key=lambda f: f["popularity"]):
        row = usable(font)
        if not row or row[0] in builtin or not downloadable_variants(row[3]) or row[0].startswith("Google Sans"):
            continue
        forced = row[0] in FORCED_DOWNLOADS
        if forced or quota[row[1]] > 0:
            quota[row[1]] -= 0 if forced else 1
            rows.append({"name": row[0], "category": row[1], "languages": row[2], "variants": downloadable_variants(row[3]), "popularity": font["popularity"]})
    assert len(rows) == DOWNLOADABLE, len(rows)
    return rows

def load_manifest():
    return json.loads(MANIFEST.read_text("utf-8"))

def save_manifest(manifest):
    MANIFEST.write_text(json.dumps(manifest, indent=1, ensure_ascii=False, sort_keys=True) + "\n", "utf-8")

def covered(font, probe):
    cmap = font.getBestCmap()
    return all(ord(ch) in cmap for ch in probe)

def claimed_scripts(font):
    return sorted(code for code, probe in PROBES.items() if covered(font, probe))

def build_font(data, weight, italic, scripts):
    from fontTools import subset
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
    font = TTFont(io.BytesIO(data))
    if "fvar" in font:
        limits = {}
        for axis in font["fvar"].axes:
            tag = axis.axisTag
            if tag == "wght":
                limits[tag] = min(max(weight, axis.minValue), axis.maxValue)
            elif tag == "wdth":
                limits[tag] = min(max(100, axis.minValue), axis.maxValue)
            elif tag == "slnt":
                limits[tag] = 0
            else:
                limits[tag] = axis.defaultValue
        try:
            font = instancer.instantiateVariableFont(font, limits, inplace=False, updateFontNames=True)
        except Exception:
            font = instancer.instantiateVariableFont(TTFont(io.BytesIO(data)), limits, inplace=False)
    options = subset.Options()
    options.layout_features = ["*"]
    options.hinting = False
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.drop_tables += ["DSIG"]
    subsetter = subset.Subsetter(options)
    ranges = ",".join([LATIN_RANGES] + [SCRIPT_RANGES[s] for s in COMMON_SCRIPTS + tuple(scripts)])
    unicodes = []
    for part in ranges.split(","):
        low, _, high = part.partition("-")
        unicodes.extend(range(int(low, 16), int(high or low, 16) + 1))
    subsetter.populate(unicodes=unicodes)
    subsetter.subset(font)
    out = io.BytesIO()
    font.save(out)
    return out.getvalue()

def cached(directory, file, expected):
    path = SOURCES / directory / file
    if path.is_file() and (expected is None or sha256(path.read_bytes()) == expected):
        return path.read_bytes()
    data = fetch_url(RAW % (COMMIT, urllib.parse.quote(directory + "/" + file)))
    if expected is not None and sha256(data) != expected:
        sys.exit("%s/%s differs from the pinned file (expected %s, got %s)" % (directory, file, expected, sha256(data)))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return data

def fetch():
    from fontTools import version
    from fontTools.ttLib import TTFont
    old = load_manifest().get("builtin", {}) if MANIFEST.exists() else {}
    manifest = {"commit": COMMIT, "fonttools": version, "builtin": {}}
    for family, spec in BUILTIN.items():
        previous = old.get(family, {})
        known = {f["source"]: f["sourceSha256"] for f in previous.get("files", {}).values()}
        licence = None
        for name in ("OFL.txt", "LICENSE.txt", "UFL.txt"):
            try:
                licence = (name, cached(spec["dir"], name, previous.get("licenceSha256") if previous.get("licence") == name else None))
                break
            except urllib.error.HTTPError:
                continue
        if licence is None:
            sys.exit("no licence file for " + family)
        text = licence[1].decode("utf-8", "replace")
        unmodified = "Reserved Font Name" in text.split("This Font Software is licensed")[0]
        target = ASSETS / slug(family)
        target.mkdir(parents=True, exist_ok=True)
        (target / licence[0]).write_bytes(licence[1])
        files = {}
        claims = None
        groups = {}
        for (weight, italic), file in spec["files"].items():
            groups.setdefault((file, italic), []).append(weight)
        for (file, italic), weights in groups.items():
            source = cached(spec["dir"], file, known.get(file))
            variable = "fvar" in TTFont(io.BytesIO(source))
            if unmodified and variable:
                outputs = [("%s/%s-Variable%s.ttf" % (slug(family), slug(family), "Italic" if italic else ""), weights, source)]
            elif unmodified:
                outputs = [(asset_name(family, w, italic), [w], source) for w in weights]
            else:
                outputs = [(asset_name(family, w, italic), [w], build_font(source, w, italic, spec.get("scripts", ()))) for w in weights]
            for name, covers, data in outputs:
                (ASSETS / name).write_bytes(data)
                found = claimed_scripts(TTFont(io.BytesIO(data)))
                claims = found if claims is None else sorted(set(claims) & set(found))
                files[name] = {"weights": covers, "italic": italic, "source": file, "sourceSha256": sha256(source), "sha256": sha256(data),
                               "bytes": len(data), "modified": not unmodified, "variable": unmodified and variable}
        manifest["builtin"][family] = {"dir": spec["dir"], "licence": licence[0], "licenceSha256": sha256(licence[1]),
                                       "category": spec.get("category", 0), "languages": claims, "files": files}
        print("%-18s %2d files %7d bytes  %s  scripts %s" % (family, len(files), sum(f["bytes"] for f in files.values()), "as published" if unmodified else "subset", claims))
    return manifest

def apply_categories(manifest, families):
    by_name = {f["family"]: f for f in families}
    for family, entry in manifest["builtin"].items():
        entry["category"] = BUILTIN[family].get("category") or CATEGORIES[by_name[family]["category"]]
        entry["popularity"] = by_name[family]["popularity"]

HEADER = '''package app.absentmotion.fonts

data class FontEntry(val name: FontName, val category: Int, val languages: Set<Int>, val bundled: Boolean = false)
object FontCatalog {
    val categories = linkedMapOf(%(category_map)s)
    val languages = linkedMapOf(
%(script_map)s)
    val entries: List<FontEntry> by lazy {
        families.trim().lineSequence().flatMap { line ->
            val fields = line.split('|')
            val languages = fields[2].split(',').map { it.toInt() }.toSet()
            fields[3].split(',').map { it.toInt() }.map { variant ->
                FontEntry(FontName("googlefonts", fields[0], variant %% 10 * 100, variant > 9), fields[1].toInt(), languages, fields[4] == "1")
            }
        }.toList()
    }
    private val families = """
%(data)s
"""
}
'''

def wrap(items, indent):
    lines = [indent]
    for item in items:
        if len(lines[-1] + item) > 118 and lines[-1] != indent:
            lines.append(indent)
        lines[-1] += item + ", "
    return "\n".join(line.rstrip().removesuffix(",") if i == len(lines) - 1 else line.rstrip() for i, line in enumerate(lines))

def catalogue_rows(manifest):
    rows = []
    for family, entry in sorted(manifest["builtin"].items(), key=lambda item: item[1]["popularity"]):
        codes = sorted({w // 100 + 10 * int(f["italic"]) for f in entry["files"].values() for w in f["weights"]}, key=lambda c: (c % 10, c))
        rows.append((family, entry["category"], entry["languages"], codes, 1))
    for entry in manifest["downloadable"]:
        rows.append((entry["name"], entry["category"], entry["languages"], entry["variants"], 0))
    return rows

def kotlin(manifest):
    rows = catalogue_rows(manifest)
    used = {s for _, _, scripts, _, _ in rows for s in scripts}
    shown = {name: (i, label) for name, (i, label) in SCRIPTS.items() if i in used}
    categories = {v: k for k, v in CATEGORIES.items()}
    return HEADER % {
        "category_map": ", ".join(f'{i} to "{n}"' for i, n in categories.items()),
        "script_map": wrap((f'{i} to "{label}"' for i, label in sorted(shown.values())), "        ") + "\n    ",
        "data": "\n".join(f"{n}|{c}|{','.join(map(str, s))}|{','.join(map(str, v))}|{b}" for n, c, s, v, b in rows),
    }

def problems(manifest):
    found = []
    if manifest["commit"] != COMMIT:
        found.append("manifest commit differs from the pinned commit")
    if set(manifest["builtin"]) != set(BUILTIN):
        found.append("manifest families differ from BUILTIN")
    text, start, end = notices_current()
    if text[start:end] != notices_section(manifest):
        found.append("THIRD_PARTY_NOTICES.md font section is out of date: run python3 tools/gen_fonts.py --notices")
    for family, entry in manifest["builtin"].items():
        folder = ASSETS / slug(family)
        licence = folder / entry["licence"]
        if not licence.is_file() or sha256(licence.read_bytes()) != entry["licenceSha256"]:
            found.append(family + ": licence file missing or changed")
        for name, info in entry["files"].items():
            path = ASSETS / name
            if not path.is_file() or sha256(path.read_bytes()) != info["sha256"]:
                found.append(name + ": missing or changed")
    listed = {p.name for p in ASSETS.iterdir() if p.is_dir()} if ASSETS.is_dir() else set()
    if listed != {slug(f) for f in manifest["builtin"]}:
        found.append("assets/fonts folders differ from the manifest")
    return found

def notices_section(manifest):
    from fontTools.ttLib import TTFont
    lines = ["| What | Source | Licence | File |", "|---|---|---|---|"]
    for family, entry in sorted(manifest["builtin"].items()):
        first = next(iter(entry["files"]))
        name = TTFont(str(ASSETS / first))["name"].getName(0, 3, 1, 0x409)
        copyright = " ".join(str(name).replace("|", "/").split())
        licence = {"OFL.txt": "SIL Open Font License 1.1", "LICENSE.txt": "Apache License 2.0", "UFL.txt": "Ubuntu Font Licence 1.0"}[entry["licence"]]
        lines.append("| %s | %s | %s | `assets/fonts/%s/%s` |" % (family, copyright, licence, slug(family), entry["licence"]))
    published = sorted(f for f, e in manifest["builtin"].items() if not any(x["modified"] for x in e["files"].values()))
    modified = len(manifest["builtin"]) - len(published)
    return SECTION % {"built": len(manifest["builtin"]), "more": len(manifest["downloadable"]), "commit": manifest["commit"],
                      "modified": modified, "published_count": len(published), "published": ", ".join(published), "table": "\n".join(lines)}

def notices_current():
    text = NOTICES.read_text("utf-8")
    start = text.index("### Google Fonts")
    return text, start, text.index("### Icons")

def write_notices(manifest):
    text, start, end = notices_current()
    NOTICES.write_text(text[:start] + notices_section(manifest) + text[end:], "utf-8")

SECTION = """### Google Fonts

AbsentMotion contains %(built)d font families that work without a network connection, and offers %(more)d more that the
app downloads from Google's font servers (`fonts.googleapis.com`, `fonts.gstatic.com`) when a user picks one. A downloaded
family keeps its own licence: the SIL Open Font License 1.1, the Apache License 2.0 or the Ubuntu Font Licence 1.0, as stated
for that family at https://fonts.google.com/. Text a user sets in any of these fonts belongs to the user.

The built-in fonts are copied from the repository https://github.com/google/fonts at commit
`%(commit)s`. The table lists, for each family, the copyright line from the font, the licence and the licence file that
ships next to the font files in the app. %(modified)d families are modified: variable fonts were turned into static Regular and Bold
instances, and every font was cut down to the Latin, Cyrillic and Vietnamese letters and (for Hindi, Thai and Arabic fonts)
the writing system of that font, with `tools/gen_fonts.py` and fontTools. The %(published_count)d families whose copyright holders reserve
the font name (%(published)s) are included exactly as published, because the SIL Open Font License
does not allow a modified version to keep a reserved name. The licence text of the SIL Open Font License 1.1 is in
appendix A.13; the Apache License 2.0 text is the file `LICENSE`.

%(table)s

"""

def main():
    arg = argparse.ArgumentParser(description="Maintain the font catalogue and the fonts built into the app.")
    arg.add_argument("--fetch", action="store_true", help="download the pinned font sources, build the bundled fonts and rewrite the manifest")
    arg.add_argument("--select", action="store_true", help="choose the downloadable families from current popularity and rewrite the manifest")
    arg.add_argument("--notices", action="store_true", help="rewrite the font section of THIRD_PARTY_NOTICES.md")
    arg.add_argument("--input", help="a saved copy of the metadata JSON instead of downloading it")
    arg.add_argument("--out", default=str(OUT))
    arg.add_argument("--check", action="store_true", help="exit 1, changing nothing, if bundled fonts, licences or the catalogue are out of date")
    args = arg.parse_args()
    if args.fetch or args.select:
        families = load(args.input)
        old = load_manifest() if MANIFEST.exists() else {}
        if args.fetch:
            manifest = fetch()
            manifest["downloadable"] = old.get("downloadable", [])
            apply_categories(manifest, families)
        else:
            manifest = old
        if args.select or not manifest["downloadable"]:
            manifest["downloadable"] = select(families, set(BUILTIN))
        save_manifest(manifest)
    manifest = load_manifest()
    if args.notices:
        write_notices(manifest)
        return
    text = kotlin(manifest)
    if args.check:
        found = problems(manifest)
        if not Path(args.out).exists() or Path(args.out).read_text("utf-8") != text:
            found.append("font catalogue is out of date: run python3 tools/gen_fonts.py")
        if found:
            sys.exit("\n".join(found))
    else:
        Path(args.out).write_text(text, "utf-8")
    rows = catalogue_rows(manifest)
    print(f"{args.out}: {len(rows)} families ({len(manifest['builtin'])} built in), {sum(len(r[3]) for r in rows)} variants")

if __name__ == "__main__":
    main()
