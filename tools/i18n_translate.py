#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from i18n_extract import EFFECTS, FOLDERS, KOTLIN, PLACEHOLDER, RES, ROOT, xml_escape

LANGUAGES = {'id': 'Indonesian', 'hi': 'Hindi', 'pt-BR': 'Brazilian Portuguese', 'es': 'Latin American Spanish', 'fil': 'Filipino (Tagalog)',
             'vi': 'Vietnamese', 'th': 'Thai', 'ms': 'Malay (Malaysia)', 'ar': 'Arabic (Modern Standard)', 'tr': 'Turkish', 'ru': 'Russian'}
QUANTITIES = {'id': ['other'], 'vi': ['other'], 'th': ['other'], 'ms': ['other'], 'hi': ['one', 'other'], 'pt-BR': ['one', 'many', 'other'],
              'es': ['one', 'many', 'other'], 'fil': ['one', 'other'], 'tr': ['one', 'other'], 'ru': ['one', 'few', 'many', 'other'],
              'ar': ['zero', 'one', 'two', 'few', 'many', 'other']}
STATE = ROOT / 'tools/i18n_source.json'
GLOSSARY = ROOT / 'tools/i18n_glossary.json'
NOTES = ROOT / 'tools/i18n_notes.json'
RULES = {'ru': 'Write the count placeholder in every plural form, including "one" (it also covers 21, 31, 101 ...), unless the English text has none.',
         'hi': 'Write the count placeholder in every plural form, including "one" (it also covers 0), unless the English text has none.',
         'pt-BR': 'Write the count placeholder in every plural form, including "one" (it also covers 0), and give "many" the same wording as "other" (it covers millions).',
         'es': 'Give "many" the same wording as "other" (it covers millions).',
         'ar': 'In the Arabic plural forms "one" and "two" leave out the count placeholder when the singular or dual noun already says the number '
               '(write "طبقتان", not "%1$s طبقتين"); keep every other placeholder.',
         'fil': 'Filipino video apps keep many English editing terms (layer, keyframe, preset, export, template); use them as the glossary says. '
                'Write the count placeholder in every plural form, including "one" (it also covers 2, 3, 5, 7, 8, 10 ...), unless the English text has none.'}
EFFECT_I18N = EFFECTS / 'i18n'
CATALOG_KT = KOTLIN / 'app/absentmotion/effects/EffectCatalog.kt'
NOT_EFFECTS = {'index', 'releases', 'foreign'}
API = 'https://api.deepseek.com/chat/completions'
TERMS = ['layer', 'keyframe', 'effect', 'export', 'project', 'timeline', 'template', 'preset', 'clip', 'group', 'mask', 'blending mode',
         'easing', 'transform', 'opacity', 'duration', 'frame rate', 'resolution', 'bitrate', 'aspect ratio', 'trim', 'split', 'speed',
         'volume', 'audio', 'video', 'image', 'shape', 'text', 'font', 'border', 'shadow', 'color', 'fill', 'gradient', 'stroke', 'camera',
         'playhead', 'undo', 'redo', 'duplicate', 'media', 'asset', 'caption', 'voice-over', 'background', 'parent layer', 'null object',
         'motion tracking', 'motion blur', 'chroma key', 'settings', 'preview', 'render', 'thumbnail', 'bookmark', 'vector', 'element']
KEEP = ['AbsentMotion', 'Alight Motion', 'CapCut']
ARABIC = re.compile('[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]')
RLM = '‏'
SOURCE_REF = re.compile(r'R\.(string|plurals)\.([A-Za-z0-9_]+)')
lock = threading.Lock()
usage = {'calls': 0, 'prompt': 0, 'completion': 0, 'failed': 0}


def android_unescape(text):
    text = text or ''
    out, i = [], 0
    while i < len(text):
        if text[i] == '\\' and i + 1 < len(text):
            out.append({'n': '\n', 't': '\t'}.get(text[i + 1], text[i + 1]))
            i += 2
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def read_resources(path):
    if not path.is_file():
        return {}, {}
    root = ET.parse(path).getroot()
    entries, attrs = {}, {}
    for el in root:
        name = el.get('name')
        if el.tag == 'string':
            entries[name] = android_unescape(el.text)
        elif el.tag == 'plurals':
            entries[name] = {item.get('quantity'): android_unescape(item.text) for item in el if item.tag == 'item'}
        else:
            continue
        attrs[name] = {k: v for k, v in el.attrib.items() if k != 'name'}
        if attrs[name].get('translatable') == 'false':
            del entries[name], attrs[name]
    return entries, attrs


def write_resources(path, english, attrs, values, quantities):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for key, en in english.items():
        if key not in values:
            continue
        extra = ''.join(f' {k}="{v}"' for k, v in attrs.get(key, {}).items())
        value = values[key]
        if isinstance(en, dict):
            lines.append(f'    <plurals name="{key}"{extra}>')
            for q in quantities:
                lines.append(f'        <item quantity="{q}">{xml_escape(value.get(q) or value["other"])}</item>')
            lines.append('    </plurals>')
        else:
            lines.append(f'    <string name="{key}"{extra}>{xml_escape(value)}</string>')
    lines.append('</resources>')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')


def effect_units():
    units = {}
    for path in sorted(EFFECTS.glob('*.json')):
        if path.stem in NOT_EFFECTS:
            continue
        data = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(data, dict) or 'id' not in data:
            continue
        key = data['id']
        units[f'fx:{key}:name'] = (data.get('name', key), 'effect name (short, like an Alight Motion effect name)')
        if data.get('description'):
            units[f'fx:{key}:description'] = (data['description'], f'description of the effect "{data.get("name", key)}" (one short sentence)')
        for p in data.get('params', []):
            if p.get('label'):
                units[f'fx:{key}:{p["id"]}:label'] = (p['label'], f'parameter slider/switch label of the effect "{data.get("name", key)}"')
            for i, option in enumerate(p.get('options') or []):
                units[f'fx:{key}:{p["id"]}:option{i}'] = (option, f'choice option of the parameter "{p.get("label", p["id"])}" of the effect "{data.get("name", key)}"')
        for preset in data.get('presets', []):
            units[f'fxpreset:{key}:{slug(preset["name"])}'] = (preset['name'], f'name of a one-tap animation preset of the effect "{data.get("name", key)}" (short, shown under an animated tile)')
    text = CATALOG_KT.read_text(encoding='utf-8')
    for cat, name in re.findall(r'"([a-z0-9]+)" to "([^"]+)"', text):
        units[f'fxcat:{cat}'] = (name, 'effect category name (one or two words)')
    return units


def effect_assets(units, translations):
    out = {'categories': {}, 'effects': {}}
    for key in units:
        if key not in translations:
            continue
        value = translations[key]
        parts = key.split(':')
        if parts[0] == 'fxcat':
            out['categories'][parts[1]] = value
            continue
        effect = out['effects'].setdefault(parts[1], {})
        if parts[0] == 'fxpreset':
            effect.setdefault('presets', {})[parts[2]] = value
            continue
        if len(parts) == 3:
            effect[parts[2]] = value
            continue
        param = effect.setdefault('params', {}).setdefault(parts[2], {})
        if parts[3] == 'label':
            param['label'] = value
        else:
            param.setdefault('options', []).append((int(parts[3][6:]), value))
    for effect in out['effects'].values():
        for param in effect.get('params', {}).values():
            if 'options' in param:
                param['options'] = [v for _, v in sorted(param['options'])]
    return out


def flatten_effect_asset(data, units):
    flat = {}
    for cat, name in data.get('categories', {}).items():
        flat[f'fxcat:{cat}'] = name
    for key, effect in data.get('effects', {}).items():
        for field in ('name', 'description'):
            if effect.get(field):
                flat[f'fx:{key}:{field}'] = effect[field]
        for preset, name in effect.get('presets', {}).items():
            flat[f'fxpreset:{key}:{preset}'] = name
        for pid, param in effect.get('params', {}).items():
            if param.get('label'):
                flat[f'fx:{key}:{pid}:label'] = param['label']
            options = param.get('options') or []
            expected = sum(1 for k in units if k.startswith(f'fx:{key}:{pid}:option'))
            if len(options) == expected:
                for i, option in enumerate(options):
                    flat[f'fx:{key}:{pid}:option{i}'] = option
    return flat


def slug(name):
    return re.sub('[^a-z0-9]+', '-', name.lower()).strip('-')


def source_context():
    found = {}
    for path in sorted(KOTLIN.rglob('*.kt')) + sorted((ROOT / 'app/src').glob('*/kotlin/**/*.kt')):
        for line in path.read_text(encoding='utf-8').splitlines():
            for m in SOURCE_REF.finditer(line):
                found.setdefault(m.group(2), (path.stem, line.strip()[:140]))
    return found


def placeholders(text):
    return sorted(m.group(0) for m in PLACEHOLDER.finditer(text))


def problem(en, value, language):
    if isinstance(en, dict):
        if not isinstance(value, dict):
            return 'expected an object with plural forms'
        if 'other' not in value:
            return 'missing "other" form'
        for q, v in value.items():
            if not isinstance(v, str) or not v.strip():
                return f'empty {q} form'
            if not set(placeholders(v)) <= set(placeholders(en['other'])):
                return f'placeholders in {q} form do not match'
        if placeholders(value['other']) != placeholders(en['other']):
            return 'placeholders in "other" form do not match the English'
        return None
    if not isinstance(value, str) or not value.strip():
        return 'empty'
    if placeholders(value) != placeholders(en):
        return f'placeholders {placeholders(en)} expected, got {placeholders(value)}'
    for word in KEEP:
        if word in en and word not in value:
            return f'must keep "{word}" unchanged'
    if en.count('\n') and not value.count('\n'):
        return 'line breaks (\\n) were lost'
    plain = re.sub(r'%\d+\$s', '', en)
    limit = max(len(plain) * (2.6 if language in ('th', 'hi') else 2.2), len(plain) + 18)
    if len(re.sub(r'%\d+\$s', '', value)) > limit:
        return f'too long ({len(value)} characters for {len(en)} in English): use a shorter wording'
    return None


def fix_direction(value, language):
    if language != 'ar':
        return value
    def one(v):
        v = v.lstrip(RLM)
        return RLM + v if ARABIC.search(v) and not ARABIC.match(v.lstrip()) else v
    return {q: one(v) for q, v in value.items()} if isinstance(value, dict) else one(value)


def api_key():
    key = os.environ.get('DEEPSEEK_API_KEY')
    if not key:
        sys.exit('DEEPSEEK_API_KEY is not set')
    return key


def call(system, payload, attempts=4):
    body = {'model': 'deepseek-flash', 'reasoning_effort': 'low', 'response_format': {'type': 'json_object'}, 'max_tokens': 24000,
            'temperature': 0.2, 'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': json.dumps(payload, ensure_ascii=False)}]}
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(API, data=json.dumps(body).encode(), headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + api_key()})
            with urllib.request.urlopen(req, timeout=300) as r:
                reply = json.load(r)
            with lock:
                usage['calls'] += 1
                usage['prompt'] += reply.get('usage', {}).get('prompt_tokens', 0)
                usage['completion'] += reply.get('usage', {}).get('completion_tokens', 0)
            content = reply['choices'][0]['message'].get('content') or ''
            if content.strip():
                return json.loads(content)
            error = 'empty answer'
        except urllib.error.HTTPError as e:
            if e.code in (401, 402, 403):
                sys.exit(f'DeepSeek answered {e.code}: the API key is invalid or has no credit (check DEEPSEEK_API_KEY)')
            error = e.code
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, ConnectionError, OSError) as e:
            error = getattr(e, 'code', None) or type(e).__name__
        print(f'  retry {attempt + 1}: {error}', file=sys.stderr)
        time.sleep(3 * (attempt + 1))
    with lock:
        usage['failed'] += 1
    return {}


def system_prompt(language, glossary):
    name = LANGUAGES[language]
    forms = ', '.join(QUANTITIES[language])
    terms = '; '.join(f'{k} = {v}' for k, v in glossary.items()) if glossary else ''
    return (f'You translate the user interface of AbsentMotion, an Android video editing app like Alight Motion and CapCut, from English into {name}.\n'
            f'Rules:\n'
            f'- Natural, short UI wording that popular video-editing apps use in {name}. Buttons, tabs and labels stay concise; sentences sound native, not literal.\n'
            f'- Use the normal capitalisation of {name} UI text (English Title Case does not carry over).\n'
            f'- Keep product names exactly: AbsentMotion, Alight Motion, CapCut.\n'
            f'- Keep units, format and codec names unchanged: MP4, MOV, WebM, MKV, GIF, PNG, JPEG, WebP, BMP, SVG, H.264, H.265, HEVC, AVC, AV1, VP9, APV, '
            f'AAC, Opus, FLAC, WAV, MP3, OGG, M4A, fps, kbps, Mbps, MB, GB, dB, Hz, kHz, ms, px, 4K, 1080p, 720p, HDR, SDR, RGB, HSL, Q, AI, LUT.\n'
            f'- Placeholders such as %1$s and %2$s must appear exactly as written, once each; the order may change to fit the grammar. Keep \\n line breaks.\n'
            f'- A word in a placeholder sentence may be replaced by a number or a name at run time; write the sentence so it reads well either way.\n'
            f'- Use consistent terminology. Glossary: {terms}\n'
            + (f'- {RULES[language]}\n' if language in RULES else '') +
            f'- Input: a JSON object "items" whose keys are string ids; each value has "en" (the English text), "where" (the source file and code line that shows it) '
            f'and sometimes "note". When "en" is an object it is a plural: answer with an object holding exactly these {name} plural forms: {forms}.\n'
            f'- Output: one flat JSON object mapping every input id to its {name} translation (a string, or an object for plurals), '
            f'for example {{"some_id": "...", "other_id": "..."}}. Do not wrap it in "items". Nothing else.')


def glossary_for(language):
    data = json.loads(GLOSSARY.read_text(encoding='utf-8')) if GLOSSARY.is_file() else {}
    if language in data and set(TERMS) <= set(data[language]):
        return data[language]
    name = LANGUAGES[language]
    system = (f'You are a localisation lead for a mobile video editing app (like Alight Motion and CapCut). Give the standard {name} UI term for each '
              f'English video-editing term, the word that popular editing apps in {name} use (keep an English loanword if that is what those apps use). '
              f'Answer with a JSON object mapping each English term to the {name} term.')
    terms = call(system, {'terms': TERMS})
    terms = {k: v for k, v in terms.items() if k in TERMS and isinstance(v, str) and v.strip()}
    with lock:
        data = json.loads(GLOSSARY.read_text(encoding='utf-8')) if GLOSSARY.is_file() else {}
        data[language] = {t: terms.get(t, t) for t in TERMS}
        GLOSSARY.write_text(json.dumps(data, ensure_ascii=False, indent=1, sort_keys=True) + '\n', encoding='utf-8')
    return data[language]


def translate(language, items, glossary, batch):
    system = system_prompt(language, glossary)
    done, todo = {}, list(items)
    for round_ in range(3):
        if not todo:
            break
        retry = []
        chunks = [todo[i:i + batch] for i in range(0, len(todo), batch)]
        for chunk in chunks:
            payload = {'items': {k: items[k] for k in chunk}}
            answer = call(system, payload)
            for wrapper in ('items', 'translations', 'output'):
                if isinstance(answer.get(wrapper), dict) and not any(k in answer for k in chunk):
                    answer = answer[wrapper]
            for k in chunk:
                value = answer.get(k)
                why = problem(items[k]['en'], value, language)
                if why is None:
                    done[k] = fix_direction(value, language)
                else:
                    if round_ < 2:
                        items[k] = dict(items[k], note=(items[k].get('note', '') + f' Previous answer was rejected: {why}.').strip())
                    retry.append(k)
                    if round_ == 2:
                        print(f'  {language} {k}: gave up ({why})', file=sys.stderr)
        todo = retry
        batch = max(5, batch // 3)
    return done


def balance():
    try:
        req = urllib.request.Request('https://api.deepseek.com/user/balance', headers={'Authorization': 'Bearer ' + api_key()})
        with urllib.request.urlopen(req, timeout=30) as r:
            info = json.load(r).get('balance_infos', [{}])[0]
        return float(info.get('total_balance', 'nan')), info.get('currency', '')
    except Exception:
        return float('nan'), ''


def main():
    parser = argparse.ArgumentParser(description='Translate strings.xml and the effect texts into the 11 other app languages with DeepSeek; only missing or changed texts are sent.')
    parser.add_argument('--languages', default=','.join(LANGUAGES), help='comma-separated tags, e.g. id,ar')
    parser.add_argument('--only', choices=['strings', 'effects'], help='translate only app strings or only effect texts')
    parser.add_argument('--retranslate', default='', help='comma-separated keys to translate again even if present')
    parser.add_argument('--batch', type=int, default=60)
    parser.add_argument('--limit', type=int, default=0, help='translate at most this many texts per language (for a trial run)')
    parser.add_argument('--dry-run', action='store_true', help='only count what would be translated')
    parser.add_argument('--workers', type=int, default=6)
    args = parser.parse_args()
    languages = [l for l in args.languages.split(',') if l]
    unknown = [l for l in languages if l not in LANGUAGES]
    if unknown:
        sys.exit(f'unknown language(s): {unknown}')
    forced = {k for k in args.retranslate.split(',') if k}

    english, attrs = read_resources(RES / 'values/strings.xml')
    units = effect_units()
    state = json.loads(STATE.read_text(encoding='utf-8')) if STATE.is_file() else {}
    context = source_context()
    notes = json.loads(NOTES.read_text(encoding='utf-8')) if NOTES.is_file() else {}
    sources = {}
    if args.only != 'effects':
        sources.update({k: {'en': v, 'where': ' | '.join(context.get(k, ('', ''))).strip(' |') or 'not found in code'} for k, v in english.items()})
    if args.only != 'strings':
        sources.update({k: {'en': v[0], 'where': 'assets/effects', 'note': v[1]} for k, v in units.items()})

    for key, note in notes.items():
        if key in sources:
            sources[key]['note'] = (sources[key].get('note', '') + ' ' + note).strip()
    current, plans = {}, {}
    for language in languages:
        values, _ = read_resources(RES / FOLDERS[language] / 'strings.xml')
        asset = EFFECT_I18N / f'{language}.json'
        values.update(flatten_effect_asset(json.loads(asset.read_text(encoding='utf-8')), units) if asset.is_file() else {})
        current[language] = values
        need = [k for k, s in sources.items() if k not in values or state.get(k, s['en']) != s['en'] or k in forced
                or isinstance(s['en'], dict) and not set(QUANTITIES[language]) <= set(values[k])]
        plans[language] = need[:args.limit] if args.limit else need
        print(f'{language}: {len(plans[language])} text(s) to translate')
    if args.dry_run:
        return 0

    start, (before, currency) = time.time(), balance()
    if before != before:
        sys.exit('could not read the DeepSeek balance: the API key is invalid or the network is down (check DEEPSEEK_API_KEY)')
    results = {}

    def work(language):
        if not plans[language]:
            return language, {}
        glossary = glossary_for(language)
        items = {k: dict(sources[k]) for k in plans[language]}
        got = translate(language, items, glossary, args.batch)
        print(f'{language}: {len(got)} of {len(items)} translated')
        return language, got

    with ThreadPoolExecutor(args.workers) as pool:
        for language, got in pool.map(work, languages):
            results[language] = got

    failed = set()
    for language in languages:
        values = current[language]
        values.update(results.get(language, {}))
        failed |= set(plans[language]) - set(results.get(language, {}))
        quantities = QUANTITIES[language]
        write_resources(RES / FOLDERS[language] / 'strings.xml', english, attrs, values, quantities)
        EFFECT_I18N.mkdir(parents=True, exist_ok=True)
        asset = effect_assets(units, values)
        (EFFECT_I18N / f'{language}.json').write_text(json.dumps(asset, ensure_ascii=False, indent=1, sort_keys=True) + '\n', encoding='utf-8')

    known = {k: s['en'] for k, s in sources.items() if k not in failed}
    state = {k: v for k, v in state.items() if k in english or k in units}
    state.update(known)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1, sort_keys=True) + '\n', encoding='utf-8')
    after, _ = balance()
    print(f'{usage["calls"]} request(s), {usage["prompt"]} prompt + {usage["completion"]} completion tokens, {usage["failed"]} failed request(s), '
          f'{time.time() - start:.0f} s, cost {before - after:.4f} {currency}')
    if failed:
        print(f'{len(failed)} text(s) still need a translation: run again', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
