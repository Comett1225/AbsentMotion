import re
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
for path in sys.stdin.read().split("\n"):
    if not path:
        continue
    try:
        data = open(path, "rb").read()
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
