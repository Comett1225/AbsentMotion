#!/usr/bin/env python3
import argparse
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

AUTHOR = "Toji Motion & Tanryu"
LICENCE = "GPL-3.0"
EXCLUDED = {"com.alightcreative.effects.deepglow", "com.alightcreative.effects.denoise",
            "com.alightcreative.effects.hsl", "com.alightcreative.fourcolorgradientnew"}
CATEGORIES = {"color", "blur", "distort", "stylize", "noise", "procedural", "repeat", "key", "opacity", "move", "beat", "3d", "text", "other"}
CATEGORY_MAP = {"drawing": "stylize", "matte": "opacity", "transform": "move", "generate": "procedural", "light": "stylize", "keying": "key"}
TAG_CATEGORIES = [("procedural", "procedural"), ("particles", "procedural"), ("blur", "blur"), ("distort", "distort"),
                  ("color", "color"), ("3d", "3d"), ("glitch", "stylize"), ("stylize", "stylize"), ("drawing", "stylize")]

SKSL_RESERVED = set("""saturate unpremul toLinearSrgb fromLinearSrgb half half2 half3 half4 half2x2 half3x3 half4x4 float2 float3 float4
float2x2 float3x3 float4x4 int2 int3 int4 bool2 bool3 bool4 short short2 short3 short4 shader colorFilter blender sample eval inline
noinline pure readonly writeonly buffer workgroup es3 layout input output filter sampler ushort byte ubyte long double fixed interface
namespace union class typedef template this packed goto external superp extern cast sizeof static enum hvec dvec fvec round roundEven
trunc inverse transpose determinant sinh cosh tanh asinh acosh atanh isinf isnan modf outerProduct floatBitsToInt floatBitsToUint
intBitsToFloat uintBitsToFloat packUnorm2x16 packSnorm2x16 packHalf2x16 unpackUnorm2x16 unpackSnorm2x16 unpackHalf2x16 uint uint2
uint3 uint4 image coord screen layer fromLayer toLayer time duration orient main switch case default lerp""".split())
ENGINE_NAMES = {"image", "size", "time", "layer", "fromLayer", "toLayer", "screen", "duration", "cycles", "orient", "paletteSize",
                "paletteSpread", "swatches", "glyphCount", "glyphColumns", "glyphCell", "visible", "pixel", "sourceInset", "beat",
                "beatTime", "beatIndex", "coord"}
TYPES = set("void bool int float vec2 vec3 vec4 bvec2 bvec3 bvec4 ivec2 ivec3 ivec4 mat2 mat3 mat4 float2 float3 float4 half half2 half3 half4 "
            "float2x2 float3x3 float4x4 int2 int3 int4 bool2 bool3 bool4".split())
QUALIFIERS = {"highp", "mediump", "lowp", "precise", "invariant", "flat", "smooth", "noperspective"}
NAME_FIXES = {"effect_vrcombined_name": "VR Combined"}
DESC_FIXES = {
    "effect_findedges_desc": "Finds the edges in the layer and draws them as lines.",
    "effect_gradientoverlay_desc": "Lays a color gradient over the layer.",
    "effect_longshadow_desc": "Casts a long shadow from the layer.",
    "effect_gradientmap_desc": "Maps the brightness of the layer to a color gradient.",
    "effect_solidcolor_desc": "Fills the layer with a solid color.",
    "effect_swirl_desc": "Swirls the layer around a point.",
    "effect_wipe_desc": "Wipes the layer in or out along a line.",
}

TOKEN = re.compile(r"""
    (?P<ws>\s+)
  | (?P<num>(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?[fFuU]?)
  | (?P<id>[A-Za-z_]\w*)
  | (?P<op>\+\+|--|\+=|-=|\*=|/=|%=|==|!=|<=|>=|&&|\|\||\^\^|<<=|>>=|<<|>>|&=|\|=|\^=|[-+*/%<>=!&|^~?:;,.(){}\[\]#])
""", re.X)


class Unsupported(Exception):
    pass


def tokenize(text):
    out, i = [], 0
    while i < len(text):
        m = TOKEN.match(text, i)
        if not m:
            raise Unsupported(f"cannot read shader near {text[i:i + 20]!r}")
        i = m.end()
        if m.lastgroup == "ws":
            if out and "\n" in m.group():
                out[-1] = (out[-1][0], True)
            continue
        tok = m.group()
        if m.lastgroup == "num":
            tok = tok.rstrip("fFuU")
            if tok.endswith("."):
                tok += "0"
            if tok.startswith("."):
                tok = "0" + tok
        out.append((tok, False))
    return [t for t, _ in out], [nl for _, nl in out]


def strip_comments(text):
    return re.sub(r"//[^\n]*|/\*.*?\*/", lambda m: "\n" * m.group().count("\n") or " ", text, flags=re.S)


def is_ident(t):
    return bool(re.fullmatch(r"[A-Za-z_]\w*", t))


def is_number(t):
    return bool(re.fullmatch(r"(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?", t))


def preprocess(text):
    text = strip_comments(text).replace("\\\n", " ")
    macros = {}
    out_lines, stack = [], []

    def active():
        return all(s[0] for s in stack)

    def eval_condition(expr):
        toks, _ = tokenize(expr)
        res = []
        i = 0
        while i < len(toks):
            t = toks[i]
            if t == "defined":
                j = i + 1
                if toks[j] == "(":
                    name = toks[j + 1]
                    i = j + 3
                else:
                    name = toks[j]
                    i = j + 1
                res.append("1" if name in macros else "0")
                continue
            res.append(t)
            i += 1
        res = expand(res, macros)
        py = []
        for t in res:
            if is_number(t):
                py.append(str(float(t)) if "." in t or "e" in t.lower() else t)
            elif t == "&&":
                py.append(" and ")
            elif t == "||":
                py.append(" or ")
            elif t == "!":
                py.append(" not ")
            elif t in {"(", ")", "+", "-", "*", "/", "<", ">", "<=", ">=", "==", "!="}:
                py.append(t)
            elif is_ident(t):
                py.append("0")
            else:
                raise Unsupported(f"#if expression {expr!r}")
        try:
            return bool(eval("".join(py), {"__builtins__": {}}, {}))
        except Exception:
            raise Unsupported(f"#if expression {expr!r}")

    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("#"):
            d = s[1:].strip()
            word = re.match(r"\w*", d).group()
            rest = d[len(word):].strip()
            if word in ("ifdef", "ifndef"):
                name = rest.split()[0]
                cond = (name in macros) == (word == "ifdef")
                stack.append([cond and active(), cond])
            elif word == "if":
                cond = active() and eval_condition(rest)
                stack.append([cond, cond])
            elif word == "elif":
                taken = stack[-1][1]
                parent = all(x[0] for x in stack[:-1])
                cond = not taken and parent and eval_condition(rest)
                stack[-1] = [cond, taken or cond]
            elif word == "else":
                taken = stack[-1][1]
                parent = all(x[0] for x in stack[:-1])
                stack[-1] = [parent and not taken, True]
            elif word == "endif":
                stack.pop()
            elif not active():
                pass
            elif word == "define":
                m = re.match(r"([A-Za-z_]\w*)(\(([^)]*)\))?\s*(.*)", rest)
                if not m:
                    raise Unsupported(f"#define {rest!r}")
                params = [p.strip() for p in m.group(3).split(",")] if m.group(2) is not None else None
                if params == [""]:
                    params = []
                macros[m.group(1)] = (params, tokenize(m.group(4))[0])
            elif word == "undef":
                macros.pop(rest.split()[0], None)
            elif word in ("version", "extension", "pragma", "line", ""):
                pass
            else:
                raise Unsupported(f"#{word}")
            out_lines.append("")
            continue
        out_lines.append(line if active() else "")
    toks, nls = tokenize("\n".join(out_lines))
    return expand(toks, macros, nls)


def expand(toks, macros, nls=None, hide=frozenset()):
    out, out_nl = [], []
    i = 0
    while i < len(toks):
        t = toks[i]
        nl = nls[i] if nls else False
        if t in macros and t not in hide:
            params, body = macros[t]
            if params is None:
                rep = expand(body, macros, None, hide | {t})
                out.extend(rep)
                out_nl.extend([False] * len(rep))
                if out_nl:
                    out_nl[-1] = out_nl[-1] or nl
                i += 1
                continue
            if i + 1 < len(toks) and toks[i + 1] == "(":
                args, cur, depth, j = [], [], 0, i + 2
                while j < len(toks):
                    a = toks[j]
                    if a in "([" :
                        depth += 1
                    elif a in ")]":
                        if depth == 0:
                            break
                        depth -= 1
                    if a == "," and depth == 0:
                        args.append(cur)
                        cur = []
                    else:
                        cur.append(a)
                    j += 1
                args.append(cur)
                if params == [] and args == [[]]:
                    args = []
                if len(args) != len(params):
                    raise Unsupported(f"macro {t} called with {len(args)} arguments")
                args = [expand(a, macros, None, hide) for a in args]
                sub = []
                for b in body:
                    if b in params:
                        sub.extend(["("] + args[params.index(b)] + [")"])
                    else:
                        sub.append(b)
                rep = expand(sub, macros, None, hide | {t})
                out.extend(rep)
                out_nl.extend([False] * len(rep))
                i = j + 1
                continue
        out.append(t)
        out_nl.append(nl)
        i += 1
    return (out, out_nl) if nls is not None else out


def match_close(toks, i):
    pairs = {"(": ")", "[": "]", "{": "}"}
    open_, close = toks[i], pairs[toks[i]]
    depth = 0
    for j in range(i, len(toks)):
        if toks[j] == open_:
            depth += 1
        elif toks[j] == close:
            depth -= 1
            if depth == 0:
                return j
    raise Unsupported(f"unbalanced {open_}")


def split_top(toks, sep):
    parts, cur, depth = [], [], 0
    for t in toks:
        if t in "([{":
            depth += 1
        elif t in ")]}":
            depth -= 1
        if t == sep and depth == 0:
            parts.append(cur)
            cur = []
        else:
            cur.append(t)
    parts.append(cur)
    return parts


def stmt_end(toks, i):
    t = toks[i]
    if t == "{":
        return match_close(toks, i) + 1
    if t in ("for", "while"):
        return stmt_end(toks, match_close(toks, i + 1) + 1)
    if t == "if":
        j = stmt_end(toks, match_close(toks, i + 1) + 1)
        if j < len(toks) and toks[j] == "else":
            j = stmt_end(toks, j + 1)
        return j
    if t == "do":
        j = stmt_end(toks, i + 1)
        j = match_close(toks, j + 1) + 1
        return j + 1
    depth = 0
    for j in range(i, len(toks)):
        if toks[j] in "([{":
            depth += 1
        elif toks[j] in ")]}":
            depth -= 1
        elif toks[j] == ";" and depth == 0:
            return j + 1
    raise Unsupported("statement without end")


class Loops:
    def __init__(self, consts, bounds):
        self.consts = consts
        self.bounds = bounds
        self.count = 0
        self.rewritten = 0

    def constant(self, toks):
        if not toks:
            return False
        for t in toks:
            if is_number(t) or t in "()+-*/" or t in ("float", "int"):
                continue
            if is_ident(t) and t in self.consts:
                continue
            return False
        return True

    def value(self, toks):
        py = []
        for t in toks:
            if is_number(t):
                py.append(t)
            elif t in "()+-*/":
                py.append(t)
            elif t in ("float", "int"):
                py.append("")
            elif t in self.bounds:
                py.append(repr(self.bounds[t]))
            elif t in self.consts and self.consts[t] is not None:
                py.append(repr(self.consts[t]))
            else:
                return None
        try:
            return float(eval("".join(py), {"__builtins__": {}}, {}))
        except Exception:
            return None

    def valid(self, init, cond, inc, body):
        if len(init) < 4 or init[0] not in ("int", "float") or not is_ident(init[1]) or init[2] != "=":
            return False
        name = init[1]
        if not self.constant(init[3:]):
            return False
        if len(cond) < 3 or cond[0] != name or cond[1] not in ("<", "<=", ">", ">=", "==", "!=") or not self.constant(cond[2:]):
            return False
        if inc not in ([name, "++"], ["++", name], [name, "--"], ["--", name]):
            if not (len(inc) >= 3 and inc[0] == name and inc[1] in ("+=", "-=") and self.constant(inc[2:])):
                return False
        for k, t in enumerate(body):
            if t == name:
                nxt = body[k + 1] if k + 1 < len(body) else ""
                prv = body[k - 1] if k else ""
                if nxt in ("=", "+=", "-=", "*=", "/=", "++", "--") or prv in ("++", "--"):
                    return False
        return True

    def cap(self, init, cond, inc):
        if len(init) >= 4 and init[2] == "=" and len(cond) >= 3 and cond[0] == init[1] and cond[1] in ("<", "<="):
            start = self.value(init[3:])
            end = self.value(cond[2:])
            step = 1.0 if inc in ([init[1], "++"], ["++", init[1]]) else (self.value(inc[2:]) if len(inc) >= 3 and inc[1] == "+=" else None)
            if start is not None and end is not None and step and step > 0:
                return max(1, min(4096, int(math.ceil((end - start) / step)) + 2))
        return 1024

    def rewrite(self, toks):
        out = []
        i = 0
        while i < len(toks):
            t = toks[i]
            if t == "while":
                close = match_close(toks, i + 1)
                cond = toks[i + 2:close]
                end = stmt_end(toks, close + 1)
                body = self.rewrite(toks[close + 1:end])
                out += self.generic([], cond, [], body)
                i = end
                continue
            if t == "for":
                close = match_close(toks, i + 1)
                header = split_top(toks[i + 2:close], ";")
                if len(header) != 3:
                    raise Unsupported("for loop header")
                init, cond, inc = header
                end = stmt_end(toks, close + 1)
                body = self.rewrite(toks[close + 1:end])
                if self.valid(init, cond, inc, body):
                    out += ["for", "("] + init + [";"] + cond + [";"] + inc + [")"] + body
                else:
                    out += self.generic(init, cond, inc, body)
                i = end
                continue
            out.append(t)
            i += 1
        return out

    def generic(self, init, cond, inc, body):
        self.rewritten += 1
        self.count += 1
        k = f"_ac_l{self.count}"
        if body and body[0] != "{":
            body = ["{"] + body + ["}"]
        cap = self.cap(init, cond, inc)
        res = ["{"]
        if init:
            res += init + [";"]
        res += ["for", "(", "int", k, "=", "0", ";", k, "<", str(cap), ";", k, "++", ")", "{"]
        if inc:
            res += ["if", "(", k, ">", "0", ")", "{"] + inc + [";", "}"]
        if cond:
            res += ["if", "(", "!", "("] + cond + [")", ")", "break", ";"]
        res += body + ["}", "}"]
        return res


def array_constructors(toks):
    out = []
    i = 0
    while i < len(toks):
        if (i + 7 < len(toks) and toks[i] in TYPES and is_ident(toks[i + 1]) and toks[i + 2] == "[" and toks[i + 4] == "]"
                and toks[i + 5] == "=" and toks[i + 6] == toks[i] and toks[i + 7] == "["):
            name = toks[i + 1]
            close = match_close(toks, i + 7)
            if toks[close + 1] != "(":
                raise Unsupported("array constructor")
            end = match_close(toks, close + 1)
            items = split_top(toks[close + 2:end], ",")
            out += toks[i:i + 5] + [";"]
            for n, item in enumerate(items):
                out += [name, "[", str(n), "]", "="] + item + [";"]
            i = end + 2
            continue
        out.append(toks[i])
        i += 1
    return out


def declared_names(toks):
    names = set()
    for i in range(1, len(toks)):
        if is_ident(toks[i]) and toks[i - 1] in TYPES | {"const", "in", "out", "inout"} and toks[i] not in TYPES:
            if i + 1 < len(toks) and toks[i + 1] in ("(", "=", ";", ",", "[", ")"):
                names.add(toks[i])
    for i in range(len(toks) - 2):
        if toks[i] == "," and is_ident(toks[i + 1]) and toks[i + 2] in ("=", ";", ",", "["):
            names.add(toks[i + 1])
    return names


def global_consts(toks):
    consts = {}
    for i in range(len(toks) - 3):
        if toks[i] == "const" and toks[i + 1] in ("int", "float") and is_ident(toks[i + 2]) and toks[i + 3] == "=":
            j = i + 4
            expr = []
            while j < len(toks) and toks[j] not in (";", ","):
                expr.append(toks[j])
                j += 1
            val = None
            if all(is_number(t) or t in "()+-*/" for t in expr) and expr:
                try:
                    val = float(eval("".join(expr), {"__builtins__": {}}, {}))
                except Exception:
                    val = None
            consts[toks[i + 2]] = val
    return consts


def format_tokens(toks):
    out = []
    prev = ""
    indent = 0
    for t in toks:
        if t == "}":
            indent = max(0, indent - 1)
        if out and out[-1].endswith("\n"):
            out.append("    " * indent)
        elif prev and (is_ident(prev) or is_number(prev)) and (is_ident(t) or is_number(t)):
            out.append(" ")
        elif prev in ("return", "else") and t not in (";",):
            out.append(" ")
        out.append(t)
        if t == "{":
            indent += 1
            out.append("\n")
        elif t == "}" or t == ";":
            out.append("\n")
        prev = t
    return "".join(out)


def fix_for_headers(text):
    lines = text.split("\n")
    out = []
    buf = None
    for line in lines:
        if buf is not None:
            buf += " " + line.strip()
            if buf.count("(") <= buf.count(")"):
                out.append(buf)
                buf = None
            continue
        if re.match(r"\s*for\s*\(", line) and line.count("(") > line.count(")"):
            buf = line
            continue
        out.append(line)
    if buf is not None:
        out.append(buf)
    return "\n".join(out)


def text_of(ref, strings, fixes=None, fallback=""):
    if ref is None:
        return fallback
    if ref.startswith("@string/"):
        return strings.get(ref[len("@string/"):], fallback)
    if ref.startswith("@am:string/"):
        key = ref[len("@am:string/"):]
        if fixes and key in fixes:
            return fixes[key]
        m = re.match(r"effect_param_(\w+)", key)
        if m:
            return m.group(1).replace("_", " ").title()
        return fallback
    return ref


def readable(ident):
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", ident).replace("_", " ").strip()
    return s[:1].upper() + s[1:].lower() if s.isupper() else s[:1].upper() + s[1:]


def scope_problems(root):
    why = []
    for tag in ("passes", "iterations", "script"):
        if root.find(f".//{tag}") is not None:
            why.append({"passes": "needs several passes", "iterations": "needs repeated passes", "script": "needs a script"}[tag])
    shaders = root.findall(".//shader")
    if any(s.get("type") == "vertex" for s in shaders):
        why.append("needs a vertex shader")
    for tex in root.iter("texture"):
        kind = tex.get("srcType")
        if kind in ("buffer", "image", "comp"):
            why.append({"buffer": "needs an extra pass buffer", "image": "needs an extra image", "comp": "needs another composition"}[kind])
    frags = [s for s in shaders if s.get("type") != "vertex"]
    if not frags:
        why.append("has no shader")
    elif len(frags) > 1:
        why.append("has several shaders")
    out = []
    for w in why:
        if w not in out:
            out.append(w)
    return out


def num(v, default=0.0):
    try:
        f = float(v)
        return f if math.isfinite(f) else default
    except (TypeError, ValueError):
        return default


def clean(f):
    return int(f) if float(f).is_integer() and abs(f) < 1e15 else round(f, 6)


def convert_params(root, strings):
    params, uniforms, repl, textures, setup = [], [], {}, set(), []
    param_root = root.find("params")
    nodes = []
    if param_root is not None:
        for node in param_root:
            if node.tag == "section" and len(node):
                nodes.append(node)
                nodes.extend(list(node))
            else:
                nodes.append(node)
    else:
        for node in root:
            if node.tag == "section":
                nodes.append(node)
                nodes.extend(list(node))
    section = None
    used_ids = set()

    def pid(raw):
        new = raw + "_" if raw in SKSL_RESERVED or raw in ENGINE_NAMES or raw.startswith("ac") and raw[2:3].isupper() else raw
        if new != raw:
            repl[raw] = [new]
        used_ids.add(new)
        return new

    for node in nodes:
        tag = node.tag
        label_ref = node.get("label") or node.get("name")
        ident = node.get("id")
        if tag == "section":
            section = text_of(node.get("text") or node.get("label"), strings) or None
            continue
        if tag in ("tip",) or ident is None:
            continue
        label = text_of(label_ref, strings, fallback=readable(ident)) or readable(ident)
        label = label[:60]
        if tag == "texture":
            if node.get("srcType") != "content":
                raise Unsupported(f"texture {node.get('srcType')}")
            textures.add(ident)
            continue
        if tag == "param":
            tag = node.get("type") or "slider"
        p = None
        if tag in ("slider", "spinner", "float"):
            kind = node.get("type")
            name = pid(ident)
            lo, hi = num(node.get("min"), -1e6), num(node.get("max"), 1e6)
            if lo > hi:
                lo, hi = hi, lo
            d = num(node.get("default"), lo if lo > -1e6 else 0.0)
            step = num(node.get("step"), 1.0 if kind == "integer" else 0.01) or 0.01
            if kind == "integer":
                step = max(1.0, step)
            p = {"id": name, "label": label, "type": "angle" if kind == "angle" else "float", "default": clean(d), "min": clean(lo), "max": clean(hi), "step": clean(abs(step))}
            if kind == "percent":
                p["percent"] = True
            if tag == "slider":
                p["slider"] = True
            uniforms.append(f"uniform float {name};")
        elif tag == "color":
            name = pid(ident)
            d = (node.get("default") or "#FFFFFFFF").strip()
            if not re.fullmatch(r"#[0-9A-Fa-f]{8}|#[0-9A-Fa-f]{6}", d):
                d = "#FFFFFFFF"
            p = {"id": name, "label": label, "type": "color", "default": d.lower()}
            uniforms.append(f"layout(color) uniform float4 {name};")
        elif tag == "point":
            name = pid(ident)
            d = [num(x) for x in (node.get("default") or "0,0").split(",")][:2]
            while len(d) < 2:
                d.append(0.0)
            p = {"id": name, "label": label, "type": "point", "default": [clean(d[0]), clean(d[1])], "step": clean(num(node.get("step"), 1.0) or 1.0)}
            if node.get("min") is not None and node.get("max") is not None:
                p["min"], p["max"] = clean(num(node.get("min"))), clean(num(node.get("max")))
            uniforms.append(f"uniform float2 {name};")
        elif tag == "switch":
            name = ident + "_on"
            used_ids.add(name)
            p = {"id": name, "label": label, "type": "switch", "default": (node.get("default") or "false").strip().lower() == "true"}
            uniforms.append(f"uniform float {name};")
            repl[ident] = ["(", name, ">", "0.5", ")"]
        elif tag == "selector":
            choices = [c for c in node if c.tag == "choice"]
            if not choices:
                continue
            values = [int(num(c.get("value"), k)) for k, c in enumerate(choices)]
            options = [(text_of(c.get("label"), strings, fallback=str(v)) or str(v))[:40] for c, v in zip(choices, values)]
            dv = int(num(node.get("default"), values[0]))
            index = values.index(dv) if dv in values else 0
            if values == list(range(len(values))):
                name = pid(ident)
                uniforms.append(f"uniform int {name};")
            else:
                name = ident + "_ix"
                used_ids.add(name)
                uniforms.append(f"uniform int {name};")
                cases = " ".join(f"if (k == {k}) return {v};" for k, v in enumerate(values[:-1]))
                uniforms.append(f"int {ident}_v;")
                uniforms.append(f"int _ac_{ident}(int k) {{ {cases} return {values[-1]}; }}")
                setup.append(f"{ident}_v = _ac_{ident}({name});")
                repl[ident] = [ident + "_v"]
            p = {"id": name, "label": label, "type": "choice", "default": index, "options": options}
        elif tag == "xyz":
            d = [num(x) for x in (node.get("default") or "0,0,0").split(",")][:3]
            while len(d) < 3:
                d.append(0.0)
            angle = node.get("type") == "rotate"
            names = []
            for axis, v in zip("xyz", d):
                n = f"{ident}_{axis}"
                names.append(n)
                used_ids.add(n)
                q = {"id": n, "label": f"{label} {axis.upper()}", "type": "angle" if angle else "float", "default": clean(v), "step": clean(num(node.get("step"), 1.0) or 1.0)}
                if section:
                    q["section"] = section
                params.append(q)
                uniforms.append(f"uniform float {n};")
            repl[ident] = ["float3", "(", names[0], ",", names[1], ",", names[2], ")"]
            continue
        elif tag == "orient":
            repl[ident] = ["_ac_orient"]
            uniforms.append("uniform float3x3 orient;")
            setup.append("_ac_orient = float4x4(float4(orient[0], 0.0), float4(orient[1], 0.0), float4(orient[2], 0.0), float4(0.0, 0.0, 0.0, 1.0));")
            if node.get("invert") == "true":
                setup.append("_ac_orient = transpose4(_ac_orient);")
            uniforms.append("float4x4 _ac_orient;")
            continue
        else:
            raise Unsupported(f"parameter <{tag}>")
        if section:
            p["section"] = section
        params.append(p)
    if len(params) > 32:
        raise Unsupported(f"{len(params)} settings (the limit is 32)")
    return params, uniforms, repl, textures, setup


AUTO = {
    "acScreenSize": "float2 acScreenSize;",
    "acScreenNorm": "float2 acScreenNorm;",
    "acPreviewSize": "float2 acPreviewSize;",
    "acTime": "float acTime;",
    "acLayerSize": "float2 acLayerSize;",
    "acLayerCenter": "float2 acLayerCenter;",
    "acLayerCenterNorm": "float2 acLayerCenterNorm;",
    "acLayerNorm": "float2 acLayerNorm;",
    "acLayerPivot": "float2 acLayerPivot;",
    "acLayerToScreen": "float4x4 acLayerToScreen;",
    "_ac_fc": "float4 _ac_fc;",
}


def drop_sampler_params(toks, textures):
    out, i = [], 0
    while i < len(toks):
        if toks[i] in ("sampler2D", "samplerExternalOES") and i + 1 < len(toks) and is_ident(toks[i + 1]) and out and out[-1] in ("(", ","):
            textures.add(toks[i + 1])
            i += 2
            if out[-1] == ",":
                out.pop()
            elif i < len(toks) and toks[i] == ",":
                i += 1
            continue
        out.append(toks[i])
        i += 1
    res, i = [], 0
    while i < len(out):
        t = out[i]
        if t == "(" and res and is_ident(res[-1]) and res[-1] not in ("texture2DCv", "texture2D", "texture", "texture2DLod", "textureLod"):
            close = match_close(out, i)
            args = split_top(out[i + 1:close], ",")
            kept = [a for a in args if not any(a in ([x, ".", "texture"], [x]) for x in textures)]
            if len(kept) != len(args):
                inner = []
                for k, a in enumerate(kept):
                    inner += ([","] if k else []) + a
                res += ["("] + drop_sampler_params(inner, textures) + [")"]
                i = close + 1
                continue
        res.append(t)
        i += 1
    return res


def translate(src, params_repl, textures, setup, param_bounds):
    toks, nls = preprocess(src)
    out = []
    i = 0
    while i < len(toks):
        t = toks[i]
        if t == "precision":
            while toks[i] != ";":
                i += 1
            i += 1
            continue
        if t in QUALIFIERS:
            i += 1
            continue
        out.append(t)
        i += 1
    toks = out
    es3_in, es3_out = set(), set()
    out, i, depth = [], 0, 0
    while i < len(toks):
        t = toks[i]
        if t in "{(":
            depth += 1
        elif t in "})":
            depth -= 1
        if depth == 0 and t in ("in", "out", "varying", "attribute", "uniform") and i + 2 < len(toks) and (toks[i + 1] in TYPES or toks[i + 1] in ("sampler2D", "samplerExternalOES")):
            end = toks.index(";", i)
            names = [x for x in toks[i + 2:end] if is_ident(x)]
            if t in ("in", "varying"):
                es3_in.update(names)
            elif t == "out":
                es3_out.update(names)
            elif toks[i + 1].startswith("sampler"):
                textures.update(names)
            i = end + 1
            continue
        out.append(t)
        i += 1
    toks = out
    toks = drop_sampler_params(toks, textures)
    out, i = [], 0
    reads = False
    while i < len(toks):
        t = toks[i]
        if t in ("texture2DCv", "texture2D", "texture", "texture2DLod", "textureLod") and i + 1 < len(toks) and toks[i + 1] == "(":
            close = match_close(toks, i + 1)
            args = split_top(toks[i + 2:close], ",")
            first = args[0]
            if not any(first in ([x, ".", "texture"], [x]) for x in textures):
                raise Unsupported(f"{t} reads {''.join(first)}")
            if len(args) < 2:
                raise Unsupported(f"{t} without coordinates")
            out += ["_ac_tex", "("] + args[1] + [")"]
            reads = True
            i = close + 1
            continue
        if t in textures and i + 2 < len(toks) and toks[i + 1] == "." and toks[i + 2] == "size":
            out += ["acScreenSize"]
            i += 3
            continue
        if t == "getTexSize" and i + 1 < len(toks) and toks[i + 1] == "(":
            i += 1
            continue
        if t in textures:
            raise Unsupported(f"uses the texture {t} in an unsupported way")
        out.append(t)
        i += 1
    toks = out
    if any(t in ("sampler2D", "samplerExternalOES") for t in toks):
        raise Unsupported("uses a sampler in an unsupported way")
    declared = declared_names(toks)
    renames = {n: [n + "_"] for n in declared if n in SKSL_RESERVED and n != "main"}
    out = []
    for k, t in enumerate(toks):
        prev = toks[k - 1] if k else ""
        if prev == ".":
            out.append(t)
            continue
        if t in params_repl and t not in declared:
            out += params_repl[t]
        elif t in renames:
            out += renames[t]
        elif t in es3_out or t == "gl_FragColor":
            out.append("_ac_out")
        elif t in es3_in:
            out.append("acScreenNorm")
        elif t == "gl_FragCoord":
            out.append("_ac_fc")
        elif t == "main":
            out.append("_ac_main")
        elif t == "iTime" and "iTime" not in declared:
            out.append("acTime")
        else:
            out.append(t)
    toks = out
    if "_ac_main" not in toks:
        raise Unsupported("no main function")
    toks = array_constructors(toks)
    consts = global_consts(toks)
    loops = Loops(consts, param_bounds)
    toks = loops.rewrite(toks)
    return toks, reads, loops.rewritten


def build_sksl(toks, uniforms, setup, reads, y_up):
    used = set(toks) | {w for line in setup for w in re.findall(r"\w+", line)}
    needs_layer = bool(used & {"acLayerSize", "acLayerCenter", "acLayerCenterNorm", "acLayerNorm", "acLayerPivot", "acLayerToScreen"})
    head = ["uniform shader image;", "uniform float4 screen;"]
    if "acTime" in used:
        head.append("uniform float time;")
    if needs_layer:
        head += ["uniform float2 layer;", "uniform float3x3 fromLayer;", "uniform float3x3 toLayer;"]
    head += uniforms
    head.append("float4 _ac_out;")
    autos = len(head)
    if "transpose4" in used:
        head.append("float4x4 transpose4(float4x4 m) { return float4x4(m[0][0], m[1][0], m[2][0], m[3][0], m[0][1], m[1][1], m[2][1], m[3][1], "
                    "m[0][2], m[1][2], m[2][2], m[3][2], m[0][3], m[1][3], m[2][3], m[3][3]); }")
    fy = "1.0 - " if y_up else ""
    head.append("float2 _ac_norm(float2 p) { return float2(p.x / screen.z, %s(p.y / screen.w)); }" % fy)
    head.append("float2 _ac_pixel(float2 uv) { return float2(uv.x, %suv.y) * screen.zw; }" % fy)
    if reads:
        head.append("float4 _ac_tex(float2 uv) { return float4(image.eval(screen.xy + clamp(_ac_pixel(uv), float2(0.5), screen.zw - 0.5))); }")
    body = fix_for_headers(format_tokens(toks))
    main = ["half4 main(float2 coord) {",
            "    float2 _ac_p = coord - screen.xy;",
            "    acScreenSize = screen.zw;"]
    if "acScreenNorm" in used:
        main.append("    acScreenNorm = _ac_norm(_ac_p);")
    if "acPreviewSize" in used:
        main.append("    acPreviewSize = screen.zw;")
    if "_ac_fc" in used:
        main.append("    _ac_fc = float4(_ac_p.x, %s, 0.5, 1.0);" % ("screen.w - _ac_p.y" if y_up else "_ac_p.y"))
    if "acTime" in used:
        main.append("    acTime = time;")
    if needs_layer:
        main += ["    float3 _ac_c = fromLayer * float3(layer * 0.5, 1.0);",
                 "    float2 _ac_scale = float2(length(fromLayer[0].xy), length(fromLayer[1].xy));",
                 "    acLayerSize = layer * _ac_scale;"]
        if used & {"acLayerCenter", "acLayerCenterNorm", "acLayerPivot"}:
            main.append("    acLayerCenter = _ac_c.xy / _ac_c.z - screen.xy;")
            main.append("    acLayerPivot = acLayerCenter;")
            main.append("    acLayerCenterNorm = _ac_norm(acLayerCenter);")
        if "acLayerNorm" in used:
            main += ["    float3 _ac_w = toLayer * float3(coord, 1.0);",
                     "    acLayerNorm = (_ac_w.xy / _ac_w.z) / max(layer, float2(1.0));"]
            if y_up:
                main.append("    acLayerNorm.y = 1.0 - acLayerNorm.y;")
        if "acLayerToScreen" in used:
            main += ["    float2 _ac_o = _ac_layerToNorm(float2(0.0));",
                     "    float2 _ac_ex = _ac_layerToNorm(float2(1.0, 0.0)) - _ac_o;",
                     "    float2 _ac_ey = _ac_layerToNorm(float2(0.0, 1.0)) - _ac_o;",
                     "    acLayerToScreen = float4x4(_ac_ex.x, _ac_ey.x, 0.0, _ac_o.x, _ac_ex.y, _ac_ey.y, 0.0, _ac_o.y, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0);"]
            head.append("float2 _ac_layerToNorm(float2 uv) { float3 a = fromLayer * float3(float2(uv.x, %suv.y) * layer, 1.0); return _ac_norm(a.xy / a.z - screen.xy); }" % fy)
    main += ["    " + s for s in setup]
    main += ["    _ac_out = float4(0.0);",
             "    _ac_main();",
             "    return half4(clamp(_ac_out, 0.0, 1.0));",
             "}"]
    assigned = set(re.findall(r"\w+", "\n".join(main)))
    head[autos:autos] = [decl for name, decl in AUTO.items() if name in used or name in assigned]
    return "\n".join(head) + "\n" + body.strip() + "\n" + "\n".join(main) + "\n"


def make_id(name, taken):
    base = re.sub(r"^\[x\]\s*", "", name.lower())
    base = re.sub(r"[^a-z0-9]+", "-", base).strip("-")[:40].strip("-") or "effect"
    key, n = base, 2
    while key in taken:
        suffix = f"-{n}"
        key = base[:40 - len(suffix)].strip("-") + suffix
        n += 1
    taken.add(key)
    return key


def decode_id(raw):
    if raw and re.fullmatch(r"(?:[0-9a-fA-F]{2})+", raw) and len(raw) >= 8:
        try:
            return bytes.fromhex(raw).decode("utf-8")
        except ValueError:
            return raw
    return raw


def category_of(root, real_id, name):
    raw = (root.get("category") or "").strip().lower()
    if raw in CATEGORIES:
        return raw
    if raw in CATEGORY_MAP:
        return CATEGORY_MAP[raw]
    tags = [t.strip().lower() for t in (root.get("tags") or "").split(",")]
    words = tags + re.findall(r"[a-z0-9]+", (name + " " + real_id).lower())
    for word, cat in TAG_CATEGORIES:
        if word in words:
            return cat
    return "other"


def pick_change(params):
    skip = ("alpha", "opacity", "position", "offset", "mix", "blend")
    floats = [p for p in params if p["type"] in ("float", "angle") and "min" in p]
    ordered = [p for p in floats if not any(s in p["id"].lower() for s in skip)] + floats
    for p in ordered:
        d, lo, hi = p["default"], p["min"], p["max"]
        if p["type"] == "angle" and hi - lo > 360:
            return p["id"], str(clean(d + 45))
        if hi > d:
            return p["id"], str(clean(d + (hi - d) * 0.5 if hi - d < 1e5 else d * 2 + 1))
        if d > lo:
            return p["id"], str(clean(d - (d - lo) * 0.5))
    for p in params:
        if p["type"] == "choice" and len(p["options"]) > 1:
            return p["id"], str((p["default"] + 1) % len(p["options"]))
        if p["type"] == "switch":
            return p["id"], str(not p["default"]).lower()
    for p in params:
        if p["type"] == "color":
            return p["id"], "#ff20e0a0"
    return None, None


def convert_file(path, taken, patches=()):
    root = ET.fromstring(path.read_bytes())
    if root.tag != "effect":
        raise Unsupported("not an effect file")
    real_id = decode_id(root.get("id") or path.stem)
    strings = {}
    for loc in root.iter("locale"):
        if loc.get("lang") in ("en", None):
            for s in loc.iter("string"):
                strings.setdefault(s.get("name"), (s.text or "").strip())
    name = text_of(root.get("name"), strings, NAME_FIXES, fallback="") or readable(real_id.split(".")[-1])
    desc = text_of(root.get("desc"), strings, DESC_FIXES, fallback="")
    info = {"file": path.name, "source_id": real_id, "name": name, "thumb": root.get("thumb")}
    if real_id in EXCLUDED or (root.get("id") in EXCLUDED):
        info["status"] = "excluded"
        info["reason"] = "Alight Motion built-in effect"
        return info, None, None
    problems = scope_problems(root)
    info["category"] = category_of(root, real_id, name)
    if problems:
        info["status"] = "out of scope"
        info["reason"] = "; ".join(problems)
        return info, None, None
    try:
        params, uniforms, repl, textures, setup = convert_params(root, strings)
        shader = [s for s in root.iter("shader") if s.get("type") != "vertex"][0]
        bounds = {p["id"]: p["max"] for p in params if "max" in p and p["type"] in ("float", "angle")}
        for raw, new in repl.items():
            if len(new) == 1 and new[0] in bounds:
                bounds[raw] = bounds[new[0]]
        source = shader.text or ""
        for before, after in patches:
            if before not in source:
                raise Unsupported(f"source patch does not apply: {before[:40]!r}")
            source = source.replace(before, after)
        toks, reads, loops = translate(source, repl, textures, setup, bounds)
        sksl = build_sksl(toks, uniforms, setup, reads, y_up=not real_id.startswith("com.alightcreative.effects.extra"))
    except Unsupported as e:
        info["status"] = "dropped"
        info["reason"] = f"cannot convert: {e}"
        return info, None, None
    if len(sksl.encode()) > 64 * 1024:
        info["status"] = "dropped"
        info["reason"] = "shader is larger than 64 KB"
        return info, None, None
    key = make_id(name, taken)
    info["key"] = key
    info["status"] = "converted"
    info["reads"] = reads
    effect = {"id": key, "name": name[:80], "description": desc, "category": info["category"], "author": AUTHOR,
              "licence": LICENCE, "version": 1, "params": params, "sksl": sksl}
    change, value = pick_change(params)
    meta = {}
    if change:
        meta["change"], meta["changeValue"] = change, value
    if not reads:
        meta["generator"] = True
    return info, effect, meta


def main():
    ap = argparse.ArgumentParser(description='Converts Alight-Motion-format effect XML files (GLSL fragment shaders) into AbsentMotion added-effect JSON files (SkSL). Only single fragment shaders that read the layer picture are converted; files that need passes, iterations, scripts, vertex shaders or extra textures are listed as out of scope. Output is deterministic.')
    ap.add_argument("source", type=Path, help="folder of Alight effect .xml files")
    ap.add_argument("out", type=Path, help="folder for <key>.json, meta.json and convert.json")
    ap.add_argument("--overrides", type=Path, help="JSON with per-file fixes: {file: {patch: [[before, after], ...], meta: {...}, drop: reason}}")
    opts = ap.parse_args()
    overrides = json.loads(opts.overrides.read_text()) if opts.overrides else {}
    opts.out.mkdir(parents=True, exist_ok=True)
    for old in opts.out.glob("*.json"):
        old.unlink()
    taken, rows, metas = set(), [], {}
    for path in sorted(opts.source.glob("*.xml")):
        try:
            info, effect, meta = convert_file(path, taken, overrides.get(path.name, {}).get("patch", []))
        except ET.ParseError as e:
            info, effect, meta = {"file": path.name, "status": "dropped", "reason": f"XML error: {e}"}, None, None
        o = overrides.get(path.name, {})
        if effect and o.get("drop"):
            info["status"], info["reason"] = "dropped", o["drop"]
            effect = None
        if effect:
            meta.update(o.get("meta", {}))
            (opts.out / f"{effect['id']}.json").write_text(json.dumps(effect, indent=1, ensure_ascii=False) + "\n")
            metas[effect["id"]] = meta
        rows.append(info)
    (opts.out / "meta.json").write_text(json.dumps(metas, indent=1, sort_keys=True) + "\n")
    (opts.out.parent / "convert.json").write_text(json.dumps(rows, indent=1, ensure_ascii=False) + "\n")
    counts = {}
    for r in rows:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    print(counts)
    for r in rows:
        if r["status"] == "dropped":
            print(f"  {r['file']}: {r['reason']}")


if __name__ == "__main__":
    sys.exit(main())
