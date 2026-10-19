#!/usr/bin/env python3
import ast
import io
import sys
import tokenize
from pathlib import Path

def violations(source):
    found = []
    for token in tokenize.generate_tokens(io.StringIO(source).readline):
        if token.type == tokenize.COMMENT and not (token.start[0] <= 2 and (token.string.startswith('#!') or 'coding' in token.string)):
            found.append((token.start[0], 'comment'))
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            found.append((node.lineno, 'docstring'))
    return sorted(found)

if __name__ == '__main__':
    for line, kind in violations(Path(sys.argv[1]).read_text(encoding='utf-8')):
        print(f'{line}:{kind}')
