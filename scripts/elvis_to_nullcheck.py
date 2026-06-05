"""
Phase B Elvis -> explicit null check converter.

Conservative scope:
- Match single-line `(indent)(val|var) NAME = EXPR ?: ACTION` where
  ACTION is one of: return [...], continue, break, throw [...], return@LABEL [...]
- Skip lines where EXPR ends with `as? Type` (would change semantics, smart-cast
  vs explicit type check needs human judgment).
- Skip lines where the line ends mid-call (open paren count != close paren count).
- Skip lines inside multiline comments and KDoc blocks.

Rewrite:
    val NAME = EXPR ?: ACTION
becomes:
    val NAME = EXPR
    if (NAME == null) ACTION
"""
import re
import sys
from pathlib import Path

PATTERN = re.compile(
    r'^(?P<indent>[ \t]*)'
    r'(?P<keyword>val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)'
    r'\s*=\s*'
    r'(?P<expr>.+?)'
    r'\s*\?:\s*'
    r'(?P<action>(?:return(?:@[A-Za-z_][A-Za-z0-9_]*)?(?:\s+.+)?|continue|break|throw\s+.+))'
    r'\s*$'
)

def is_balanced(s: str) -> bool:
    p = b = c = 0
    in_str = False
    in_char = False
    escape = False
    for ch in s:
        if escape:
            escape = False
            continue
        if ch == '\\':
            escape = True
            continue
        if in_str:
            if ch == '"':
                in_str = False
            continue
        if in_char:
            if ch == "'":
                in_char = False
            continue
        if ch == '"':
            in_str = True
            continue
        if ch == "'":
            in_char = True
            continue
        if ch == '(':
            p += 1
        elif ch == ')':
            p -= 1
        elif ch == '[':
            b += 1
        elif ch == ']':
            b -= 1
        elif ch == '{':
            c += 1
        elif ch == '}':
            c -= 1
    return p == 0 and b == 0 and c == 0

def process_file(path: Path) -> int:
    lines = path.read_text(encoding='utf-8').splitlines(keepends=True)
    out = []
    changes = 0
    in_block_comment = False
    for raw in lines:
        line = raw.rstrip('\n').rstrip('\r')
        nl = raw[len(line):]
        stripped = line.strip()

        # Track block comments / KDoc roughly. Don't transform inside them.
        if in_block_comment:
            out.append(raw)
            if '*/' in line:
                in_block_comment = False
            continue
        if stripped.startswith('/*') and '*/' not in stripped:
            in_block_comment = True
            out.append(raw)
            continue
        # Skip single-line comments
        if stripped.startswith('//'):
            out.append(raw)
            continue

        m = PATTERN.match(line)
        if not m:
            out.append(raw)
            continue

        expr = m.group('expr')
        # Skip if expr ends with `as?` (semantic difference)
        if re.search(r'\bas\?\s+[A-Za-z_][A-Za-z0-9_.<>]*\s*$', expr):
            out.append(raw)
            continue
        # Skip if the line up through the assignment isn't bracket-balanced
        # (multi-line continuation or unmatched parens).
        prefix = f"{m.group('keyword')} {m.group('name')} = {expr}"
        if not is_balanced(prefix):
            out.append(raw)
            continue
        # Skip if expr ends with a trailing comma (suggests this was a call arg)
        if expr.rstrip().endswith(','):
            out.append(raw)
            continue

        indent = m.group('indent')
        name = m.group('name')
        keyword = m.group('keyword')
        action = m.group('action')

        out.append(f"{indent}{keyword} {name} = {expr}{nl}")
        out.append(f"{indent}if ({name} == null) {action}{nl}")
        changes += 1

    if changes:
        path.write_text(''.join(out), encoding='utf-8')
    return changes

def main():
    if len(sys.argv) < 2:
        print("usage: elvis_to_nullcheck.py <root1> [<root2> ...]")
        sys.exit(2)
    total = 0
    files_changed = 0
    for root in sys.argv[1:]:
        for p in Path(root).rglob('*.kt'):
            n = process_file(p)
            if n:
                files_changed += 1
                total += n
                print(f"{p}: {n}")
    print(f"\nTotal: {total} conversions across {files_changed} files")

if __name__ == '__main__':
    main()
