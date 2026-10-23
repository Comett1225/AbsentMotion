#!/usr/bin/env python3
import argparse
import keyword
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / 'app/src/main'
KOTLIN = MAIN / 'kotlin'
RES = MAIN / 'res'
EFFECTS = MAIN / 'assets/effects'

NAMED = {'text', 'label', 'title', 'contentDescription', 'description', 'hint', 'placeholder', 'message', 'subtitle', 'caption', 'action', 'confirm', 'dismiss'}
SKIP_CALLS = {'Log', 'i', 'w', 'e', 'd', 'v', 'Regex', 'split', 'startsWith', 'endsWith', 'contains', 'equals', 'removePrefix', 'removeSuffix',
              'getString', 'putString', 'getInt', 'putInt', 'getBoolean', 'putBoolean', 'getLong', 'putLong', 'getFloat', 'putFloat', 'remove',
              'getStringExtra', 'getIntExtra', 'getBooleanExtra', 'getLongExtra', 'getFloatExtra', 'hasExtra', 'putExtra', 'XmlNode', 'attr', 'with',
              'withValue', 'format', 'File', 'resolve', 'substringAfter', 'substringBefore', 'substringAfterLast', 'substringBeforeLast', 'replace',
              'getColumnIndex', 'getColumnIndexOrThrow', 'parse', 'forLanguageTag', 'loadLibrary', 'getDeclaredMethod', 'getSystemService',
              'trace', 'label', 'TODO', 'MediaFormat', 'setString', 'setInteger', 'getInteger', 'containsKey', 'extractMetadata', 'createTempFile',
              'createTempDirectory', 'mutableStateOf', 'compositionLocalOf', 'staticCompositionLocalOf', 'ImageVector', 'icon', 'Builder',
              'addPathNodes', 'SimpleDateFormat', 'ofPattern', 'getAttribute', 'setAttribute', 'getQueryParameter', 'appendQueryParameter', 'Uri',
              'fromParts', 'toUri', 'animateFloatAsState', 'animateColorAsState', 'updateTransition', 'rememberInfiniteTransition', 'Animatable',
              'tween', 'key', 'node', 'child', 'children', 'property', 'prop', 'number', 'string', 'float', 'int', 'bool', 'value', 'values',
              'setNumber', 'setGain', 'edit', 'endGesture'}
MAPS = {'mapOf', 'linkedMapOf', 'hashMapOf', 'mutableMapOf'}
KEEP = {'AbsentMotion', 'Alight Motion', 'Roboto'}
REWRITE_IMPORTS = ['import androidx.compose.ui.res.stringResource', 'import app.absentmotion.R']
FOLDERS = {'en': 'values', 'id': 'values-in', 'hi': 'values-hi', 'pt-BR': 'values-pt-rBR', 'es': 'values-es', 'fil': 'values-fil',
           'vi': 'values-vi', 'th': 'values-th', 'ms': 'values-ms', 'ar': 'values-ar', 'tr': 'values-tr', 'ru': 'values-ru'}
JAVA_WORDS = {'new', 'default', 'switch', 'case', 'do', 'final', 'private', 'public', 'static', 'void', 'int', 'char', 'this', 'super', 'throw', 'goto', 'const', 'package', 'native', 'long', 'short', 'byte', 'float', 'double', 'boolean', 'catch', 'enum', 'extends', 'implements', 'interface', 'instanceof', 'protected', 'synchronized', 'throws', 'transient', 'volatile', 'abstract', 'strictfp', 'null', 'true', 'false'}
PLACEHOLDER = re.compile(r'%(\d+\$)?[sdf]')
SEVERAL = {'values-fil': {'one'}, 'values-hi': {'one'}, 'values-pt-rBR': {'one'}, 'values-ru': {'one', 'few', 'many'}, 'values-ar': {'few', 'many'}}
LETTER = re.compile('[A-Za-z\u00c0-\uffff]')


def literals(src):
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            i = src.find('\n', i)
            i = n if i < 0 else i
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif src[i] == "'":
            i = skip_char(src, i)
        elif src[i] == '"':
            i = read_string(src, i, out)
        else:
            i += 1
    return sorted(out, key=lambda x: x['start'])


def skip_char(src, i):
    j = i + 1
    while j < len(src) and src[j] != "'":
        j += 2 if src[j] == '\\' else 1
    return j + 1


def read_string(src, i, out):
    n = len(src)
    raw = src.startswith('"""', i)
    j, template = i + (3 if raw else 1), False
    while j < n:
        if raw and src.startswith('"""', j):
            while src.startswith('"', j + 3):
                j += 1
            break
        if not raw and src[j] == '"':
            break
        if not raw and src[j] == '\\':
            j += 2
            continue
        if src.startswith('${', j):
            template, depth, k = True, 1, j + 2
            while k < n and depth:
                if src[k] == '"':
                    k = read_string(src, k, out)
                    continue
                if src[k] == "'":
                    k = skip_char(src, k)
                    continue
                depth += {'{': 1, '}': -1}.get(src[k], 0)
                k += 1
            j = k
            continue
        if src[j] == '$' and j + 1 < n and (src[j + 1].isalpha() or src[j + 1] == '_'):
            template = True
        j += 1
    quote = 3 if raw else 1
    out.append({'start': i, 'end': j + quote, 'raw': src[i + quote:j], 'template': template, 'multiline': raw})
    return j + quote


def decode(raw):
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c == '\\' and i + 1 < len(raw):
            nxt = raw[i + 1]
            if nxt == 'u' and i + 5 < len(raw):
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({'n': '\n', 't': '\t', 'r': '\r', 'b': '\b'}.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def enclosing_call(src, pos):
    depth = 0
    i = pos - 1
    while i >= 0:
        c = src[i]
        if c in ')]}':
            depth += 1
        elif c in '([{':
            if depth == 0:
                if c != '(':
                    return None, i
                m = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*$', src[max(0, i - 80):i])
                return (m.group(1) if m else None), i
            depth -= 1
        i -= 1
    return None, -1


def classify(src, lit):
    before = src[max(0, lit['start'] - 200):lit['start']]
    line = src[src.rfind('\n', 0, lit['start']) + 1:lit['start']]
    if re.search(r'\bLog\.[a-z]\(', line):
        return None
    if re.search(r'\bText\(\s*(text\s*=\s*)?$', before):
        return 'Text'
    m = re.search(r'\b([a-zA-Z]+)\s*=\s*$', before)
    if m and m.group(1) in NAMED:
        return m.group(1)
    if re.search(r'\bIcon\([^,()]+(\([^()]*\))?[^,()]*,\s*$', before):
        return 'contentDescription'
    if re.search(r'\bconst\s+val\s+[A-Z][A-Z0-9_]*\s*=\s*$', before):
        return 'const'
    callee, at = enclosing_call(src, lit['start'])
    if re.match(r'\s*to\b', src[lit['end']:lit['end'] + 6]):
        return None if callee in SKIP_CALLS or callee in MAPS else 'pair'
    if callee is None and at >= 0 and src[at] == '{' and re.search(r'\b(require|check|requireNotNull|checkNotNull)\([^{}]*\)\s*$', src[max(0, at - 200):at]):
        return 'exception'
    if callee is None or callee in SKIP_CALLS:
        return None
    if callee == 'makeText':
        return 'Toast'
    if callee == 'error' or callee.endswith('Exception'):
        return 'exception'
    if callee[0].isupper():
        return 'arg:' + callee
    return None


def visible(text):
    plain = re.sub(r'\$\{[^}]*\}|\$[A-Za-z_]\w*', '', text).strip()
    letters = re.sub(r'%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]', '', plain)
    if not LETTER.search(letters) or plain in KEEP:
        return False
    if '://' in text or re.search(r'<[a-zA-Z/]', text):
        return False
    if re.fullmatch(r'[a-z0-9_.\-/:#%?=&+*\[\]\\^$|(){}]+', plain):
        return False
    if re.fullmatch(r'[a-z]+[A-Z][A-Za-z0-9]*', plain):
        return False
    return ' ' in plain or plain[:1].isupper()


def scan_source(src):
    rows = []
    for lit in literals(src):
        text = decode(lit['raw']) if not lit['multiline'] else lit['raw']
        kind = classify(src, lit)
        if kind is None or not visible(text):
            continue
        rows.append({'line': src.count('\n', 0, lit['start']) + 1, 'kind': kind, 'text': text, 'start': lit['start'], 'end': lit['end'],
                     'rewrite': kind == 'Text' and not lit['template'] and not lit['multiline']})
    return rows


def scan_kotlin(paths):
    found = {}
    for path in paths:
        rows = scan_source(path.read_text(encoding='utf-8'))
        if rows:
            found[path] = rows
    return found


def scan_effects():
    found = {}
    for path in sorted(EFFECTS.glob('*.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(data, dict):
            continue
        rows = [{'line': 0, 'kind': 'effect.' + k, 'text': data[k], 'rewrite': False} for k in ('name', 'description') if isinstance(data.get(k), str)]
        for p in data.get('params', []):
            if isinstance(p.get('label'), str):
                rows.append({'line': 0, 'kind': 'param.label', 'text': p['label'], 'rewrite': False})
            for o in p.get('options') if isinstance(p.get('options'), list) else []:
                if isinstance(o, str):
                    rows.append({'line': 0, 'kind': 'param.option', 'text': o, 'rewrite': False})
        rows += [{'line': 0, 'kind': 'preset.name', 'text': p['name'], 'rewrite': False} for p in data.get('presets', []) if isinstance(p.get('name'), str)]
        if rows:
            found[path] = rows
    return found


def kotlin_files(filters):
    files = sorted(f for f in KOTLIN.rglob('*.kt') if 'i18n' not in f.parts)
    return [f for f in files if any(s in str(f) for s in filters)] if filters else files


def rel(path):
    return str(Path(path).relative_to(ROOT))


def strings_file(folder):
    return RES / folder / 'strings.xml'


def read_strings(path):
    if not path.is_file():
        return {}
    text = path.read_text(encoding='utf-8')
    found = {m.group(1): m.group(3) for m in re.finditer(r'<string name="([A-Za-z0-9_]+)"([^>]*)>(.*?)</string>', text, re.S) if 'translatable="false"' not in m.group(2)}
    for m in re.finditer(r'<plurals name="([A-Za-z0-9_]+)"[^>]*>(.*?)</plurals>', text, re.S):
        for q, v in re.findall(r'<item quantity="([a-z]+)">(.*?)</item>', m.group(2), re.S):
            found[f'{m.group(1)}#{q}'] = v
    return found


def xml_escape(text):
    s = text.replace('\\', '\\\\').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n').replace('\t', '\\t')
    return '\\' + s if s[:1] in '@?' else s


def xml_unescape(value):
    s = value.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)


def make_key(text, taken):
    words = re.findall(r'[a-z0-9]+', text.lower().encode('ascii', 'ignore').decode())
    base = '_'.join(words[:6])[:40].strip('_') or 'text'
    if base[0].isdigit() or keyword.iskeyword(base) or base in JAVA_WORDS:
        base = 't_' + base
    key, n = base, 2
    while key in taken:
        key, n = f'{base}_{n}', n + 1
    return key


def add_imports(src):
    package = re.search(r'^package\s+([\w.]+)', src, re.M)
    needed = [i for i in REWRITE_IMPORTS if not re.search('^' + re.escape(i) + '$', src, re.M)]
    if package and package.group(1) == 'app.absentmotion':
        needed = [i for i in needed if not i.endswith('.R')]
    if not needed:
        return src
    imports = list(re.finditer(r'^import .*$', src, re.M))
    at = imports[-1].end() if imports else (package.end() if package else 0)
    return src[:at] + '\n' + '\n'.join(needed) + src[at:]


def rewrite(found, write):
    english_path = strings_file('values')
    existing = read_strings(english_path)
    by_text = {xml_unescape(v): k for k, v in existing.items() if '#' not in k}
    added = {}
    changed = 0
    for path, rows in found.items():
        src = path.read_text(encoding='utf-8')
        todo = [r for r in rows if r['rewrite']]
        for row in sorted(todo, key=lambda r: r['start'], reverse=True):
            key = by_text.get(row['text'])
            if key is None:
                key = make_key(row['text'], set(existing) | set(added))
                added[key] = row['text']
                by_text[row['text']] = key
            src = src[:row['start']] + f'stringResource(R.string.{key})' + src[row['end']:]
            print(f'{rel(path)}:{row["line"]}: {row["text"]!r} -> R.string.{key}')
            changed += 1
        if todo and write:
            path.write_text(add_imports(src), encoding='utf-8')
    if added and write:
        xml = english_path.read_text(encoding='utf-8') if english_path.is_file() else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
        unformatted = ' formatted="false"'
        lines = ''.join(f'    <string name="{k}"{unformatted if "%" in v else ""}>{xml_escape(v)}</string>\n' for k, v in added.items())
        at = xml.rindex('</resources>')
        english_path.parent.mkdir(parents=True, exist_ok=True)
        english_path.write_text(xml[:at] + lines + xml[at:], encoding='utf-8')
    print(f'{changed} literal(s) {"rewritten" if write else "would be rewritten"}, {len(added)} new key(s) for {rel(english_path)}.')
    if not write:
        print('Dry run: nothing was changed. Run again with --write to apply, then translate the new keys and run check.')


def check():
    english = read_strings(strings_file('values'))
    problems = []
    config = RES / 'xml/locales_config.xml'
    tags = re.findall(r'android:name="([^"]+)"', config.read_text(encoding='utf-8')) if config.is_file() else list(FOLDERS)
    for tag in tags:
        folder = FOLDERS.get(tag)
        if folder is None:
            problems.append(f'{tag}: no resource folder is known for this language')
            continue
        if folder == 'values':
            continue
        local = read_strings(strings_file(folder))
        if not local:
            problems.append(f'{folder}: strings.xml is missing or empty')
            continue
        names, local_names = {k.split('#')[0] for k in english}, {k.split('#')[0] for k in local}
        missing = sorted(names - local_names)
        extra = sorted(local_names - names)
        if missing:
            problems.append(f'{folder}: {len(missing)} missing: ' + ', '.join(missing))
        if extra:
            problems.append(f'{folder}: {len(extra)} not in English: ' + ', '.join(extra))
        for key in sorted(k for k in local if k.split('#')[0] in names):
            base = key.split('#')[0]
            reference = english.get(key) if '#' not in key else english.get(base + '#other', '')
            a = sorted(m.group(0) for m in PLACEHOLDER.finditer(reference or ''))
            b = sorted(m.group(0) for m in PLACEHOLDER.finditer(local[key]))
            several = key.split('#')[-1] in SEVERAL.get(folder, ())
            if a != b and not ('#' in key and not key.endswith('#other') and not several and set(b) <= set(a)):
                problems.append(f'{folder}: {key}: placeholders {a} in English but {b} here')
            if '#' in key and base + '#other' not in local:
                problems.append(f'{folder}: {base}: no "other" plural form')
            if re.search(r"(?<!\\)'", local[key]):
                problems.append(f'{folder}: {key}: unescaped apostrophe')
            if not local[key].strip():
                problems.append(f'{folder}: {key}: empty')
    for line in problems:
        print(line)
    print(f'{len({k.split("#")[0] for k in english})} keys in English, {len(tags)} languages: ' + ('all present.' if not problems else f'{len(problems)} problem(s).'))
    return 1 if problems else 0


def report(found, as_json):
    if as_json:
        print(json.dumps({rel(p): [{k: r[k] for k in ('line', 'kind', 'text', 'rewrite')} for r in rows] for p, rows in found.items()}, ensure_ascii=False, indent=1))
        return
    total = auto = 0
    for path, rows in found.items():
        print(f'\n{rel(path)} ({len(rows)})')
        for r in rows:
            print(f'  {r["line"] or "":>5}  {"*" if r["rewrite"] else " "} {r["kind"]:<22} {r["text"]!r}')
        total += len(rows)
        auto += sum(r['rewrite'] for r in rows)
    print(f'\n{total} user-visible strings in {len(found)} files; {auto} marked * can be rewritten automatically (rewrite --write).')


def selftest():
    src = ('fun A() { Text("Hello world"); Text(text = "Hi ${name}"); Log.i("Tag", "not shown"); prefs.getString("np.aspect", "1:1")\n'
           '  Toast.makeText(context, e.message ?: "Could not save", Toast.LENGTH_LONG); Icon(HomeIcons.Close, "Cancel", m); MenuRow("Media Info") { }\n'
           '  require(ok) { "This is not a theme file" }; throw IllegalStateException("Engine gone"); Text("${n}s"); Text("$p%")\n'
           '  val t = "${if (a) "Yes" else "No"}"; "Frame Rate" to x; s.setNumber(ref, 1f, "opacity"); val c = \'"\'; Text("After \\"quote\\"") }\n'
           'private const val NOTE = "Free and open"\n')
    got = [(r['kind'], r['text'], r['rewrite']) for r in scan_source(src)]
    for row in [('Text', 'Hello world', True), ('Text', 'Hi ${name}', False), ('Toast', 'Could not save', False), ('contentDescription', 'Cancel', False),
                ('arg:MenuRow', 'Media Info', False), ('pair', 'Frame Rate', False), ('const', 'Free and open', False),
                ('exception', 'This is not a theme file', False), ('exception', 'Engine gone', False), ('Text', 'After "quote"', True)]:
        assert row in got, (row, got)
    texts = [t for _, t, _ in got]
    for hidden in ('Tag', 'not shown', 'np.aspect', '1:1', 'opacity', '${n}s', '$p%'):
        assert hidden not in texts, (hidden, got)
    assert decode('Import theme file\\u2026\\n\\"x\\"') == 'Import theme file\u2026\n"x"'
    assert make_key('Draft Quality Preview', set()) == 'draft_quality_preview'
    assert make_key('Draft Quality Preview', {'draft_quality_preview'}) == 'draft_quality_preview_2'
    assert make_key('4K export', set()) == 't_4k_export'
    assert make_key('NEW', set()) == 't_new'
    assert xml_escape('Don\'t @ "x" & <b>') == 'Don\\\'t @ \\"x\\" &amp; &lt;b&gt;'
    assert xml_escape('@home') == '\\@home'
    assert xml_unescape(xml_escape("It's 100% <ok>\nnext")) == "It's 100% <ok>\nnext"
    assert add_imports('package app.absentmotion.ui\n\nimport a.B\n\nfun x() {}') == \
        'package app.absentmotion.ui\n\nimport a.B\n' + '\n'.join(REWRITE_IMPORTS) + '\n\nfun x() {}'
    print('selftest ok')


def main():
    parser = argparse.ArgumentParser(description='Find user-visible text for translation, move simple Compose text into strings.xml, check translations.')
    parser.add_argument('mode', nargs='?', default='report', choices=['report', 'rewrite', 'check', 'selftest'])
    parser.add_argument('filters', nargs='*', help='only Kotlin files whose path contains one of these')
    parser.add_argument('--write', action='store_true', help='rewrite: change the files (the default is a dry run)')
    parser.add_argument('--json', action='store_true', help='report: print JSON')
    parser.add_argument('--no-effects', action='store_true', help='report: leave out assets/effects')
    args = parser.parse_args()
    if args.mode == 'selftest':
        selftest()
        return 0
    if args.mode == 'check':
        return check()
    found = scan_kotlin(kotlin_files(args.filters))
    if args.mode == 'rewrite':
        rewrite(found, args.write)
        return 0
    if not args.no_effects and not args.filters:
        found.update(scan_effects())
    report(found, args.json)
    return 0


if __name__ == '__main__':
    sys.exit(main())
