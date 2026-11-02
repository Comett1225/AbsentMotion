#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import i18n_translate as tr
import site_build as sb
from i18n_extract import FOLDERS

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "site/data/help-translations"
STATE = ROOT / "tools/help_i18n_source.json"
RES = ROOT / "shared/res"


PICTURE = re.compile(r"^!\[[^\]]*\]\(img/[^)\s]+\)\s*$", re.M)


def plain(text):
    return re.sub(r"\n{3,}", "\n\n", PICTURE.sub("", text)).strip()


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:16]


def ui_strings(language):
    def read(folder):
        path = RES / folder / "strings.xml"
        if not path.is_file():
            return {}
        out = {}
        for node in ET.parse(path).getroot().findall("string"):
            text = "".join(node.itertext()).replace("\\'", "'").replace('\\"', '"').strip()
            if text:
                out[node.get("name")] = text
        return out
    english, local = read("values"), read(FOLDERS[language])
    pairs = {}
    for key, text in english.items():
        if key in local and len(text) <= 40:
            pairs.setdefault(text, local[key])
    return pairs


def labels_in(text, pairs):
    found = {}
    for term in re.findall(r"\*\*(.+?)\*\*", text):
        term = term.strip()
        if term in pairs:
            found[term] = pairs[term]
    return found


def system(language, glossary):
    name = tr.LANGUAGES[language]
    terms = "; ".join(f"{k} = {v}" for k, v in glossary.items())
    return (f"You translate the help pages of AbsentMotion, an Android video editing app, from English Markdown into {name}.\n"
            "Rules:\n"
            "- Natural, friendly help text for everyday users; short sentences; not literal.\n"
            "- Keep the Markdown structure exactly: the same headings (#), the same number of list items, the same table lines with the same number of cells, "
            "the same ::: block lines, and the same number of backticks. Text inside backticks stays unchanged.\n"
            "- Keep every link target and image path in parentheses exactly as written; translate only the link text and the image alt text.\n"
            "- Keep {{placeholders}} and {placeholders} exactly.\n"
            "- Names of buttons and menus are in **bold**. When the input gives \"ui\" (English label = app label), use exactly the app label.\n"
            "- Keep product names: AbsentMotion, Alight Motion, CapCut, Android, GitHub, F-Droid.\n"
            f"- Use consistent terminology. Glossary: {terms}\n"
            + (f"- {tr.RULES[language]}\n" if language in tr.RULES else "") +
            "- Input: a JSON object with \"en\" (an object of English fields), optional \"ui\" and optional \"problems\" (what was wrong with a previous answer).\n"
            "- Output: a JSON object with exactly the same keys as \"en\", each translated. Nothing else.")


def check(kind, english, answer):
    if not isinstance(answer, dict) or set(answer) != set(english):
        return ["answer must have exactly the keys " + ", ".join(sorted(english))]
    problems = []
    for key, source in english.items():
        text = answer[key]
        if not isinstance(text, str) or not text.strip():
            problems.append(f"{key} is empty")
            continue
        if kind == "page" and key in ("title", "summary"):
            problems += sb.one_line(key, text, source)
        elif kind == "chrome":
            if sorted(re.findall(r"\{\w+\}", source)) != sorted(re.findall(r"\{\w+\}", text)):
                problems.append(f"{key}: placeholders differ")
        else:
            problems += sb.compare(key, source, text)
    return problems


def translate_one(language, glossary, pairs, kind, english):
    prompt = system(language, glossary)
    note = []
    for _ in range(4):
        payload = {"en": english}
        ui = labels_in(" ".join(english.values()), pairs)
        if ui:
            payload["ui"] = ui
        if note:
            payload["problems"] = note
        answer = tr.call(prompt, payload)
        problems = check(kind, english, answer)
        if not problems:
            return {k: v if k == "body" else tr.fix_direction(v, language) for k, v in answer.items()}
        note = problems[:12]
    print(f"  {language} {kind}: gave up ({'; '.join(note[:3])})", file=sys.stderr)
    return None


def sources():
    english = sb.Site("app")
    pages = {slug: {k: v for k, v in (("title", p.title), ("summary", p.summary), ("body", plain(p.body))) if v} for slug, p in english.pages.items()}
    wanted = {e["id"] for e in english.effects if (sb.SITE / "effects" / (e["id"] + ".md")).is_file()}
    effects = {key: plain((sb.SITE / "effects" / (key + ".md")).read_text()) for key in sorted(wanted)}
    return pages, effects, list(sb.CHROME)


def main():
    parser = argparse.ArgumentParser(description="Translates the help pages, effect help and help labels into the app languages with DeepSeek; only missing or changed texts are sent.")
    parser.add_argument("--languages", default=",".join(tr.LANGUAGES))
    parser.add_argument("--seed", action="store_true", help="record the current English as already translated for languages that have a translation file")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()
    pages, effects, chrome = sources()
    state = json.loads(STATE.read_text()) if STATE.is_file() else {}
    items = {f"page:{s}": ("page", v) for s, v in pages.items()}
    items.update({f"effect:{k}": ("effect", {"text": v}) for k, v in effects.items()})
    for language in args.languages.split(","):
        path = DATA / f"{language}.json"
        data = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {"chrome": {}, "effects": {}, "pages": {}}
        done = state.setdefault(language, {})
        if args.seed:
            if path.is_file():
                for key, (_, english) in items.items():
                    done[key] = digest(english)
                done["chrome"] = digest(chrome)
            continue
        todo = [key for key, (_, english) in items.items() if done.get(key) != digest(english)
                or (key.startswith("page:") and key[5:] not in data["pages"]) or (key.startswith("effect:") and key[7:] not in data["effects"])]
        chrome_todo = done.get("chrome") != digest(chrome) or set(chrome) - set(data["chrome"])
        print(f"{language}: {len(todo)} text(s) to translate{' and the labels' if chrome_todo else ''}", flush=True)
        if args.dry_run:
            continue
        glossary = tr.glossary_for(language)
        pairs = ui_strings(language)
        if chrome_todo:
            answer = translate_one(language, glossary, pairs, "chrome", {k: k for k in chrome})
            if answer:
                data["chrome"] = answer
                done["chrome"] = digest(chrome)
        with ThreadPoolExecutor(args.workers) as pool:
            results = dict(zip(todo, pool.map(lambda key: translate_one(language, glossary, pairs, items[key][0], items[key][1]), todo)))
        for key, answer in results.items():
            if not answer:
                continue
            kind, english = items[key]
            if kind == "page":
                data["pages"][key[5:]] = answer
            else:
                data["effects"][key[7:]] = answer["text"]
            done[key] = digest(english)
        data["pages"] = {s: data["pages"][s] for s in sorted(data["pages"]) if s in pages}
        data["effects"] = {k: data["effects"][k] for k in sorted(data["effects"]) if k in effects}
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{language}: {sum(1 for a in results.values() if a)} of {len(todo)} translated", flush=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1, sort_keys=True) + "\n")
    print(f"{tr.usage['calls']} request(s), {tr.usage['prompt']} prompt + {tr.usage['completion']} completion tokens, {tr.usage['failed']} failed request(s)")


if __name__ == "__main__":
    main()
