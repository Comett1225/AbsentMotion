import re
import struct
import sys

words_file, allowed_file, label = sys.argv[1], sys.argv[2], sys.argv[3]
words = [l.strip() for l in open(words_file, encoding="utf-8") if l.strip()]
allowed = []
try:
    allowed = [l.strip() for l in open(allowed_file, encoding="utf-8") if l.strip()]
except OSError:
    pass
patterns = [re.compile(w.encode(), re.I) for w in words]
allow = [re.compile(re.escape(a.encode()), re.I) for a in allowed]


def dex_strings(data):
    size, start = struct.unpack_from("<II", data, 0x38)
    parts = []
    for i in range(size):
        at = struct.unpack_from("<I", data, start + 4 * i)[0]
        while data[at] & 0x80:
            at += 1
        at += 1
        parts.append(data[at:data.index(b"\0", at)])
    return b"\n".join(parts)


def elf_sections(data):
    wide = data[4] == 2
    table = struct.unpack_from("<Q" if wide else "<I", data, 0x28 if wide else 0x20)[0]
    size, count = struct.unpack_from("<HH", data, 0x3A if wide else 0x2E)
    parts = []
    for i in range(count):
        at = table + size * i
        if wide:
            kind, flags = struct.unpack_from("<IQ", data, at + 4)
            offset, length = struct.unpack_from("<QQ", data, at + 24)
        else:
            kind, flags = struct.unpack_from("<II", data, at + 4)
            offset, length = struct.unpack_from("<II", data, at + 16)
        if kind != 8 and not flags & 4:
            parts.append(data[offset:offset + length])
    if not parts:
        raise ValueError("no sections")
    return b"\n".join(parts)


def content(path):
    data = open(path, "rb").read()
    if path.endswith(".dex") and data[:4] == b"dex\n":
        try:
            return dex_strings(data)
        except (struct.error, IndexError, ValueError):
            return data
    if data[:4] == b"\x7fELF" and len(data) > 0x34 and data[4] in (1, 2):
        try:
            return elf_sections(data)
        except (struct.error, IndexError, ValueError):
            return data
    return data


for path in sys.stdin.read().split("\n"):
    if not path:
        continue
    try:
        data = content(path)
    except OSError:
        continue
    for a in allow:
        data = a.sub(b"", data)
    hits = []
    for p in patterns:
        for m in p.finditer(data):
            s = max(0, m.start() - 40)
            snippet = re.sub(rb"[^ -~]", b".", data[s:m.end() + 40]).decode()
            hits.append(snippet)
            if len(hits) >= 3:
                break
        if len(hits) >= 3:
            break
    if hits:
        print("PROBLEM: forbidden word in %s%s: %s" % (label, path, " | ".join(hits)))
