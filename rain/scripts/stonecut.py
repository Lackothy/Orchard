"""Resolve Stonecutter comments in Particle Rain's sources for one target (26.3, fabric).

Usage: python3 -I stonecut.py <src-root> <out-root>
Writes every .java file under src-root to out-root with only the target's code left.
"""
import re
import sys
from pathlib import Path

TARGET = (26, 3)
LOADER = "fabric"

def ver(s):
    return tuple(int(p) for p in s.strip().split("."))

def cmp(a, b):
    n = max(len(a), len(b))
    a = a + (0,) * (n - len(a))
    b = b + (0,) * (n - len(b))
    return (a > b) - (a < b)

def atom(token):
    token = token.strip()
    if token.startswith("!"):
        return not atom(token[1:])
    if token in ("fabric", "forge", "neoforge"):
        return token == LOADER
    m = re.fullmatch(r"(>=|<=|>|<|=|!=)?\s*([0-9][0-9.]*)", token)
    if not m:
        raise ValueError(f"unknown condition {token!r}")
    op, v = m.group(1) or "=", ver(m.group(2))
    c = cmp(TARGET, v)
    return {">=": c >= 0, "<=": c <= 0, ">": c > 0, "<": c < 0, "=": c == 0, "!=": c != 0}[op]

def cond(expr):
    expr = expr.strip()
    if expr.startswith("if "):
        expr = expr[3:]
    expr = re.sub(r"(>=|<=|!=|>|<|=)\s+", r"\1", expr)

    return any(all(atom(a) for a in part.replace("&&", " ").split()) for part in expr.split("||"))

def uncomment(content):
    """An inactive branch is wrapped in /* */ with its own comments escaped as /^ ^/."""
    stripped = content.strip()
    if stripped.startswith("/*") and stripped.endswith("*/"):
        start = content.index("/*")
        end = content.rindex("*/")
        inner = content[:start] + content[start + 2:end] + content[end + 2:]
        return inner.replace("/^", "/*").replace("^/", "*/")
    return content

LINE = re.compile(r"//\?(.*)$", re.M)

def lines_pass(text):
    """Resolve the outermost //? blocks, then recurse into the chosen branch."""
    out = []
    pos = 0
    while True:
        m = LINE.search(text, pos)
        if not m:
            out.append(text[pos:])
            return "".join(out)
        body = m.group(1).strip()
        if body.startswith("}"):
            raise ValueError(f"unopened block at {text[m.start():m.start() + 80]!r}")
        if body.startswith("else"):
            raise ValueError(f"stray else at {text[m.start():m.start() + 80]!r}")

        out.append(text[pos:m.start()])
        branches = []
        current = body[:-1] if body.endswith("{") else body
        content_start = m.end() + 1
        depth = 0
        scan = content_start
        while True:
            n = LINE.search(text, scan)
            if not n:
                raise ValueError("unclosed block")
            inner = n.group(1).strip()
            if inner.startswith("else") and depth == 0:
                inner = "}" + inner
            if inner.startswith("}"):
                rest = inner[1:].strip()
                if depth > 0:
                    if rest == "":
                        depth -= 1
                    scan = n.end()
                    continue
                branches.append((current, text[content_start:n.start()]))
                if rest == "":
                    pos = n.end() + 1
                    break
                rest = rest[:-1].strip() if rest.endswith("{") else rest
                current = "else" if rest == "else" else re.sub(r"^else\s+", "", rest)
                content_start = n.end() + 1
                scan = content_start
            else:
                if inner.endswith("{"):
                    depth += 1
                scan = n.end()
        chosen = ""
        for c, content in branches:
            if c == "else" or cond(c):
                chosen = content
                break
        chosen = uncomment(chosen)

        if chosen and not chosen.endswith("\n"):
            chosen = chosen.rstrip(" ") + "\n"
        out.append(lines_pass(chosen))

INLINE = re.compile(r"/\*\?(.*?)\*/")

def inline_pass(text):
    while True:
        m = INLINE.search(text)
        if not m:
            return text
        body = m.group(1).strip()
        if body.startswith("}"):
            raise ValueError(f"unopened inline at {text[m.start():m.start() + 80]!r}")
        branches = []
        current = body[:-1].strip() if body.endswith("{") else body
        content_start = m.end()
        scan = content_start
        depth = 0
        while True:
            n = INLINE.search(text, scan)
            if not n:
                raise ValueError(f"unclosed inline at {text[m.start():m.start() + 80]!r}")
            inner = n.group(1).strip()
            if inner.startswith("}"):
                rest = inner[1:].strip()
                if depth > 0:
                    if rest == "":
                        depth -= 1
                    scan = n.end()
                    continue
                branches.append((current, text[content_start:n.start()]))
                if rest == "":
                    end = n.end()
                    break
                rest = rest[:-1].strip() if rest.endswith("{") else rest
                current = "else" if rest == "else" else re.sub(r"^else\s*", "", rest)
                content_start = n.end()
                scan = content_start
            else:
                depth += 1
                scan = n.end()
        chosen = ""
        for c, content in branches:
            if c == "else" or cond(c):
                chosen = content
                break
        text = text[:m.start()] + uncomment(chosen) + text[end:]

SWAP = re.compile(r"^[ \t]*//~\s*(.*)$", re.M)

def parse_swap(spec):
    m = re.fullmatch(r"(?:if\s+)?(.+?)\s+('([^']*)'|(\S+))\s*->\s*('([^']*)'|(\S+))\s*(\{)?", spec.strip())
    if not m:
        raise ValueError(f"bad swap {spec!r}")
    c = m.group(1)
    a = m.group(3) if m.group(3) is not None else m.group(4)
    b = m.group(6) if m.group(6) is not None else m.group(7)
    return c, a, b, m.group(8) is not None

def swaps_pass(text):
    lines = text.split("\n")
    out = []
    i = 0
    while i < len(lines):
        m = SWAP.match(lines[i])
        if not m:
            out.append(lines[i])
            i += 1
            continue
        spec = m.group(1).strip()
        if spec in ("}", ""):
            i += 1
            continue
        c, a, b, block = parse_swap(spec)
        on = cond(c)
        i += 1
        if block:
            while i < len(lines) and not re.match(r"^[ \t]*//~\s*\}\s*$", lines[i]):
                out.append(lines[i].replace(a, b) if on else lines[i])
                i += 1
            i += 1
        elif i < len(lines):
            out.append(lines[i].replace(a, b) if on else lines[i])
            i += 1
    return "\n".join(out)

GLOBAL = [
    ("#c:in_overworld", "#c:is_overworld"),
    ('"c","in_overworld"', '"c","is_overworld"'),
    ("ResourceLocation", "Identifier"),
    (".getBlockHolder()", ".typeHolder()"),
    ("setScreen", "gui.setScreen"),
    ("getMainCamera()", "mainCamera()"),
]

def resolve(text):
    text = lines_pass(text)
    text = inline_pass(text)
    text = swaps_pass(text)
    for a, b in GLOBAL:
        text = text.replace(a, b)
    return text

def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    for path in sorted(src.rglob("*.java")):
        rel = path.relative_to(src)
        try:
            text = resolve(path.read_text())
        except Exception as e:
            print(f"FAILED {rel}: {e}")
            continue
        if not text.strip():
            print(f"empty   {rel}")
            continue
        dest = out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text)
        print(f"ok      {rel}")

if __name__ == "__main__":
    main()
