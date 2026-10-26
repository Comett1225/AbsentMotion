#!/usr/bin/env python3
import copy
import importlib.util
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("site_build", TOOLS / "site_build.py")
sb = importlib.util.module_from_spec(spec)
sys.modules["site_build"] = sb
spec.loader.exec_module(sb)

MARK_OPEN = chr(0xab)
MARK = "ع "


def mark(text):
    return re.sub(r"(?m)^(#{2,4}) ", lambda m: m.group(1) + " " + MARK, text)


def locale_data(english, tag):
    pages = {}
    for slug, page in english.pages.items():
        entry = {"title": MARK + page.title, "body": mark(page.body)}
        if page.summary:
            entry["summary"] = MARK + page.summary
        pages[slug] = entry
    effects = {e["id"]: mark((sb.SITE / "effects" / (e["id"] + ".md")).read_text()) for e in english.effects if (sb.SITE / "effects" / (e["id"] + ".md")).is_file()}
    chrome = {key: "«%s»" % key for key in sb.CHROME}
    chrome[", "] = "، "
    return {"pages": pages, "effects": effects, "chrome": chrome}


def make(english, tag, data):
    names = json.loads((sb.EFFECT_NAMES / (tag + ".json")).read_text(encoding="utf-8"))
    return sb.Locale(tag, copy.deepcopy(data), names)


def ids(files, path):
    return re.findall(r'\bid="([^"]+)"', files[path].decode())


class HelpLocaleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.english = sb.Site("app")
        cls.english_files = cls.english.build()
        cls.data = locale_data(cls.english, "ar")

    def build(self, tag="ar"):
        locale = make(self.english, tag, self.data)
        self.assertEqual(sb.validate_locale(locale, self.english), [])
        built = sb.Site("app", locale, self.english)
        files = dict(self.english_files)
        files.update(built.build())
        self.assertEqual(built.errors, [])
        return files, built

    def problems(self, edit, tag="ar"):
        data = copy.deepcopy(self.data)
        edit(data)
        locale = sb.Locale(tag, data, json.loads((sb.EFFECT_NAMES / (tag + ".json")).read_text(encoding="utf-8")))
        return sb.validate_locale(locale, self.english)

    def test_rtl_locale_sets_language_direction_and_shared_assets(self):
        files, built = self.build("ar")
        index = files["ar/index.html"].decode()
        self.assertIn('<html lang="ar" dir="rtl">', index)
        self.assertIn('href="../assets/site.css"', index)
        self.assertIn('href="../assets/rtl.css"', index)
        deeper = files["ar/effects/blur.html"].decode()
        self.assertIn('href="../../assets/rtl.css"', deeper)
        self.assertIn('src="../../../effects/thumb/blur.webp"', deeper)
        self.assertIn('rel="next" href="', deeper)
        self.assertTrue(built.rtl)
        self.assertIn("[dir=rtl]", sb.RTL_CSS)

    def test_ltr_locale_has_no_rtl_stylesheet(self):
        files, built = self.build("es")
        index = files["es/index.html"].decode()
        self.assertIn('<html lang="es" dir="ltr">', index)
        self.assertNotIn("rtl.css", index)
        self.assertFalse(built.rtl)

    def test_english_help_is_unchanged_and_has_no_locale_markup(self):
        text = self.english_files["index.html"].decode()
        self.assertIn('<html lang="en">', text)
        self.assertNotIn("dir=", text.split("<body")[0])

    def test_heading_anchors_stay_english_while_headings_translate(self):
        files, _ = self.build("ar")
        for slug in self.english.pages:
            path = sb.Site.out_path(self.english, slug)
            self.assertEqual(ids(files, "ar/" + path), ids(self.english_files, path), slug)
        page = files["ar/getting-started.html"].decode()
        self.assertIn(MARK, page)
        self.assertIn('id="install"', self.english_files["getting-started.html"].decode())
        self.assertIn('id="install"', page)
        toc = re.findall(r'<li><a href="#([^"]+)">([^<]*)</a></li>', page)
        self.assertTrue(all(label.startswith(MARK) for _, label in toc) and toc)
        self.assertNotIn("next", [anchor for anchor, _ in toc])

    def test_cross_page_links_and_navigation_are_valid(self):
        files, _ = self.build("ar")
        files["assets/rtl.css"] = sb.RTL_CSS.encode()
        self.assertEqual(sb.check_links(files, "app"), [])
        english = {p for p in self.english_files if p.endswith(".html")}
        self.assertEqual(sb.check_navigation(files, "ar", english), [])
        self.assertEqual(sb.check_app_html(files), [])
        self.assertIn('href="settings.html"', files["ar/getting-started.html"].decode())

    def test_translated_effect_pages_use_locale_names_and_custom_text(self):
        files, _ = self.build("ar")
        blur = files["ar/effects/blur.html"].decode()
        names = json.loads((sb.EFFECT_NAMES / "ar.json").read_text(encoding="utf-8"))["effects"]["blur"]
        self.assertIn("<h1>%s</h1>" % names["name"], blur)
        self.assertIn("«Settings»", blur)
        self.assertIn('href="../effects/motionblur.html"', blur)
        index = files["ar/effects/index.html"].decode()
        self.assertIn(names["name"], index)

    def test_missing_page_effect_or_chrome_data_is_rejected(self):
        self.assertTrue(any("no translation of page settings" in p for p in self.problems(lambda d: d["pages"].pop("settings"))))
        self.assertTrue(any("effects/blur.md" in p for p in self.problems(lambda d: d["effects"].pop("blur"))))
        self.assertTrue(any("no chrome label" in p for p in self.problems(lambda d: d["chrome"].pop("Settings"))))
        self.assertTrue(any("must keep the placeholders" in p for p in self.problems(lambda d: d["chrome"].update({"All {count} effects": "all effects"}))))
        self.assertTrue(any("unknown chrome label" in p for p in self.problems(lambda d: d["chrome"].update({"Nope": "x"}))))

    def test_altered_internal_references_are_rejected(self):
        def retarget(d):
            body = d["pages"]["getting-started"]["body"]
            d["pages"]["getting-started"]["body"] = body.replace("(@settings)", "(@home)", 1)

        def external(d):
            d["pages"]["getting-started"]["body"] += "\n\n[x](https://example.invalid/)"

        def effect_ref(d):
            d["effects"]["blur"] = d["effects"]["blur"].replace("(@effect/motionblur)", "(@effect/blur)")

        self.assertTrue(any("references differ" in p for p in self.problems(retarget)))
        self.assertTrue(any("references differ" in p for p in self.problems(external)))
        self.assertTrue(any("references differ" in p for p in self.problems(effect_ref)))

    def test_cross_page_anchors_follow_the_stable_english_ids(self):
        english = sb.Site("app")
        anchor = re.search(r'<h2 id="([^"]+)"', english.build()["settings.html"].decode()).group(1)
        english.pages["getting-started"].body += "\n\nSee [the settings](@settings#%s)." % anchor
        english_files = english.build()
        data = locale_data(english, "ar")
        self.assertEqual(sb.validate_locale(make(english, "ar", data), english), [])
        built = sb.Site("app", make(english, "ar", data), english)
        files = dict(english_files)
        files.update(built.build())
        files["assets/rtl.css"] = sb.RTL_CSS.encode()
        self.assertIn('href="settings.html#%s"' % anchor, files["ar/getting-started.html"].decode())
        self.assertIn('id="%s"' % anchor, files["ar/settings.html"].decode())
        self.assertEqual(sb.check_links(files, "app"), [])
        data["pages"]["getting-started"]["body"] = data["pages"]["getting-started"]["body"].replace("#" + anchor, "#" + anchor + "x")
        self.assertTrue(any("references differ" in p for p in sb.validate_locale(make(english, "ar", data), english)))
        files["ar/getting-started.html"] = files["ar/getting-started.html"].replace(("#" + anchor).encode(), ("#" + anchor + "x").encode())
        self.assertTrue(any("no anchor" in p for p in sb.check_links(files, "app")))

    def test_changed_structure_is_rejected(self):
        self.assertTrue(any("heading levels" in p for p in self.problems(lambda d: d["pages"]["settings"].update(body=d["pages"]["settings"]["body"].replace("## ", "### ", 1)))))
        self.assertTrue(any("blocks differ" in p for p in self.problems(lambda d: d["pages"]["getting-started"].update(body=d["pages"]["getting-started"]["body"].replace("::: app", "::: site", 1)))))

    def test_unsafe_markup_is_rejected(self):
        self.assertTrue(any("raw HTML" in p for p in self.problems(lambda d: d["pages"]["settings"].update(body=d["pages"]["settings"]["body"] + "\n\n<script>alert(1)</script>"))))
        self.assertTrue(any("raw HTML" in p for p in self.problems(lambda d: d["chrome"].update({"Presets": "<b>x</b>"}))))
        self.assertTrue(any("bidirectional" in p for p in self.problems(lambda d: d["pages"]["settings"].update(title="‮evil"))))
        self.assertTrue(any("references differ" in p for p in self.problems(lambda d: d["pages"]["settings"].update(body=d["pages"]["settings"]["body"] + "\n\n[x](javascript:alert(1))"))))

    def test_unlisted_locale_data_is_ignored_and_bad_tags_reported(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            original = sb.HELP_DATA
            sb.HELP_DATA = folder
            try:
                problems = []
                self.assertEqual(sb.load_locales(problems), [])
                (folder / "xx.json").write_text("{}")
                (folder / "es.json").write_text(json.dumps({"pages": {}, "effects": {}, "chrome": {}}))
                (folder / "tr.json").write_text("{")
                locales = sb.load_locales(problems)
                self.assertEqual([l.tag for l in locales], ["es"])
                self.assertTrue(any("xx is not an app language" in p for p in problems))
                self.assertTrue(any("tr.json" in p and "not valid JSON" in p for p in problems))
            finally:
                sb.HELP_DATA = original

    def test_supported_tags_match_the_app(self):
        tags = sb.supported_tags()
        self.assertEqual(tags[0], "en")
        self.assertEqual(len(tags), 14)
        self.assertTrue({"ar", "fa"} <= set(tags))
        self.assertEqual(sb.RTL, {"ar", "fa"})

    def test_chrome_keys_are_what_the_generator_uses(self):
        files, _ = self.build("ar")
        text = "".join(v.decode() for k, v in files.items() if k.startswith("ar/"))
        idle = {"This effect has no settings.", "(empty)", ", "}
        for key in sb.CHROME:
            if key not in idle:
                self.assertIn(MARK_OPEN + sb.escape(key.split("{")[0]).replace("\u2019", "&rsquo;"), text, key)


if __name__ == "__main__":
    unittest.main(verbosity=1)
