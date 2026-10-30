"""Static extraction helpers shared by the three per-tree extractors.

Python 3 standard library only, no network.  These helpers read *source text*
and never run or import app code, so the three trees stay independent: the only
thing they share is this parser, and it lives outside every tree.

Design rule: a pattern that stops matching is an error, never an empty result.
Every helper raises ExtractionError when the thing it was told to find is not
there or comes back empty, so the guard cannot silently pass on nothing.
"""

import os
import re


class ExtractionError(Exception):
    """A registration the sources file points at could not be found or parsed."""


# --------------------------------------------------------------------------
# Source scanning
# --------------------------------------------------------------------------

def read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError as exc:
        raise ExtractionError("cannot read %s: %s" % (path, exc))


def mask(src, lang):
    """Return (masked, no_comments).

    `masked` has the same length and line structure as `src`, with comments
    blanked and string-literal *contents* replaced by 'x', so brace and comma
    matching cannot be fooled by text.  `no_comments` is `src` with comments
    blanked but strings intact, for pulling literals out at the same offsets.
    """
    n = len(src)
    masked = list(src)
    plain = list(src)
    i = 0

    def blank(a, b, both=True, keep_nl=True):
        for k in range(a, b):
            if src[k] == "\n" and keep_nl:
                continue
            masked[k] = " "
            if both:
                plain[k] = " "

    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if src[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif src[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, max(i + 3, j - 3)):
                if src[k] != "\n":
                    masked[k] = "x"
            i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            for k in range(i + 1, min(j, n)):
                masked[k] = "x"
            i = j + 1
        elif c == "'" and lang == "kotlin":
            m = re.match(r"'(?:\\.[^']*|[^'\\])'", src[i:i + 12])
            if m:
                for k in range(i + 1, i + len(m.group(0)) - 1):
                    masked[k] = "x"
                i += len(m.group(0))
            else:
                i += 1
        else:
            i += 1
    return "".join(masked), "".join(plain)


def match_brace(masked, open_idx, o="{", c="}"):
    """Index of the bracket closing the one at open_idx (in masked text)."""
    depth = 0
    for k in range(open_idx, len(masked)):
        if masked[k] == o:
            depth += 1
        elif masked[k] == c:
            depth -= 1
            if depth == 0:
                return k
    raise ExtractionError("unbalanced %s at offset %d" % (o, open_idx))


def blank_nested(masked, start, end):
    """Text of masked[start:end] with everything inside nested braces blanked
    (depth-1 view of a body), newlines kept."""
    out, depth = [], 0
    for ch in masked[start:end]:
        if ch == "{":
            depth += 1
            out.append(" ")
        elif ch == "}":
            depth -= 1
            out.append(" ")
        elif depth > 0 and ch != "\n":
            out.append(" ")
        else:
            out.append(ch)
    return "".join(out)


def norm(name):
    """Canonical id: lowercase, letters and digits only.  HEAD_COPY, headCopy
    and HeadCopy all become 'headcopy'."""
    return re.sub(r"[^a-z0-9]", "", name.lower())


def _find_decl(masked, pattern, what, path):
    m = re.search(pattern, masked)
    if not m:
        raise ExtractionError("%s: %s not found (pattern %r)" % (path, what, pattern))
    return m


# --------------------------------------------------------------------------
# Swift
# --------------------------------------------------------------------------

def swift_enum(path, name, label_prop=None):
    """Ordered cases of `enum <name>` and, optionally, their labels from the
    string-returning computed property `label_prop`.  Returns
    {"ids": [...], "labels": [...] or None}.  Ids are normalised."""
    src = read(path)
    masked, plain = mask(src, "swift")
    m = _find_decl(masked, r"\benum\s+%s\b[^{]*\{" % re.escape(name),
                   "enum " + name, path)
    start = m.end() - 1
    end = match_brace(masked, start)
    flat = blank_nested(masked, start + 1, end)
    ids = []
    for line in flat.splitlines():
        cm = re.match(r"\s*(?:indirect\s+)?case\s+(.+)$", line)
        if not cm:
            continue
        for part in re.split(r",", cm.group(1)):
            part = part.strip()
            nm = re.match(r"(\w+)", part)
            if nm:
                ids.append(nm.group(1))
    if not ids:
        raise ExtractionError("%s: enum %s has no cases" % (path, name))
    labels = None
    if label_prop:
        body_plain = plain[start:end]
        body_mask = masked[start:end]
        pm = re.search(r"\bvar\s+%s\b[^{=]*\{" % re.escape(label_prop), body_mask)
        if not pm:
            raise ExtractionError("%s: enum %s has no property %s" % (path, name, label_prop))
        ps = pm.end() - 1
        pe = match_brace(body_mask, ps)
        block = body_plain[ps:pe]
        table = {}
        for lm in re.finditer(
                r'case\s+((?:\.\w+\s*,\s*)*\.\w+)\s*:\s*(?:return\s+)?"((?:[^"\\]|\\.)*)"', block):
            for cid in re.findall(r"\.(\w+)", lm.group(1)):
                table[cid] = lm.group(2)
        missing = [i for i in ids if i not in table]
        if missing:
            raise ExtractionError("%s: enum %s property %s has no label for %s"
                                  % (path, name, label_prop, ", ".join(missing)))
        labels = [table[i] for i in ids]
    return {"ids": [norm(i) for i in ids], "labels": labels}


def swift_settings_catalog(path):
    """[(id, title)] from `SettingsCatalog.entries` (`.init("id", "Title", ...)`)."""
    src = read(path)
    masked, plain = mask(src, "swift")
    m = _find_decl(masked, r"\bstatic\s+let\s+entries\b[^=]*=\s*\[", "SettingsCatalog.entries", path)
    start = m.end() - 1
    end = match_brace(masked, start, "[", "]")
    region = plain[start:end]
    found = re.findall(r'\.init\(\s*"(\w+)"\s*,\s*"((?:[^"\\]|\\.)*)"', region)
    expected = len(re.findall(r"\.init\(", masked[start:end]))
    if not found or len(found) != expected:
        raise ExtractionError(
            "%s: SettingsCatalog.entries: matched %d of %d `.init(` entries; the entry "
            "format changed, update parity/_extract.py" % (path, len(found), expected))
    return found


def swift_view_files(dirpath):
    return sorted(f for f in os.listdir(dirpath) if f.endswith("View.swift"))


# --------------------------------------------------------------------------
# Kotlin
# --------------------------------------------------------------------------

def _split_top(masked, plain, a, b):
    """Split masked[a:b] at depth-0 commas; return list of (masked, plain)."""
    parts, depth, last = [], 0, a
    for k in range(a, b):
        ch = masked[k]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append((masked[last:k], plain[last:k]))
            last = k + 1
    parts.append((masked[last:b], plain[last:b]))
    return parts


def kotlin_enum(path, name, label_arg=None):
    """Ordered entries of `enum class <name>`; labels are the `label_arg`-th
    string literal of each entry's constructor call.  Ids are normalised."""
    src = read(path)
    masked, plain = mask(src, "kotlin")
    m = _find_decl(masked, r"\benum\s+class\s+%s\b" % re.escape(name), "enum class " + name, path)
    k = m.end()
    # optional primary constructor
    while k < len(masked) and masked[k] in " \t\n":
        k += 1
    if masked[k] == "(":
        k = match_brace(masked, k, "(", ")") + 1
    ob = masked.find("{", k)
    if ob < 0:
        raise ExtractionError("%s: enum class %s has no body" % (path, name))
    cb = match_brace(masked, ob)
    # entries end at the first top-level ';' (members follow) or at the brace
    depth, semi = 0, cb
    for j in range(ob + 1, cb):
        ch = masked[j]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        elif ch == ";" and depth == 0:
            semi = j
            break
    ids, labels = [], []
    for pm, pp in _split_top(masked, plain, ob + 1, semi):
        if not pm.strip():
            continue
        em = re.match(r"\s*(?:@\w+\s+)*(\w+)", pm)
        if not em:
            raise ExtractionError("%s: cannot read an entry of enum class %s: %r" % (path, name, pm.strip()[:40]))
        ids.append(em.group(1))
        if label_arg is not None:
            lits = re.findall(r'"((?:[^"\\]|\\.)*)"', pp[em.end():])
            if len(lits) <= label_arg:
                raise ExtractionError("%s: entry %s of %s has no string literal #%d"
                                      % (path, em.group(1), name, label_arg))
            labels.append(lits[label_arg])
    if not ids:
        raise ExtractionError("%s: enum class %s has no entries" % (path, name))
    return {"ids": [norm(i) for i in ids], "labels": labels if label_arg is not None else None}


def kotlin_sealed_members(path, name):
    """Names of the objects/classes declared inside `sealed interface <name>`."""
    src = read(path)
    masked, _ = mask(src, "kotlin")
    m = _find_decl(masked, r"\bsealed\s+(?:interface|class)\s+%s\b[^{]*\{" % re.escape(name),
                   "sealed type " + name, path)
    start = m.end() - 1
    end = match_brace(masked, start)
    flat = blank_nested(masked, start + 1, end)
    out = re.findall(r"^\s*(?:data\s+)?(?:object|class)\s+(\w+)", flat, re.M)
    if not out:
        raise ExtractionError("%s: sealed type %s has no members" % (path, name))
    return out


def kotlin_settings_catalog(path):
    """[(id, title)] from `SettingsCatalog.entries` (`e("id", "Title", ...)`)."""
    src = read(path)
    masked, plain = mask(src, "kotlin")
    m = _find_decl(masked, r"\bval\s+entries\b[^=]*=\s*listOf\(", "SettingsCatalog.entries", path)
    start = m.end() - 1
    end = match_brace(masked, start, "(", ")")
    region = plain[start:end]
    found = re.findall(r'\be\(\s*"(\w+)"\s*,\s*"((?:[^"\\]|\\.)*)"', region)
    expected = len(re.findall(r"\be\(", masked[start:end]))
    if not found or len(found) != expected:
        raise ExtractionError(
            "%s: SettingsCatalog.entries: matched %d of %d `e(` entries; the entry "
            "format changed, update parity/_extract.py" % (path, len(found), expected))
    return found


def kotlin_screen_files(dirpath):
    return sorted(f for f in os.listdir(dirpath) if f.endswith("Screen.kt"))


# --------------------------------------------------------------------------
# README feature headings
# --------------------------------------------------------------------------

def readme_features(path):
    """Bold lead-ins of the top-level bullets under `## Features`, in order,
    duplicates removed.  `- **Head Copy**, **Type It**: ...` gives two."""
    text = read(path)
    m = re.search(r"^## Features\s*$", text, re.M)
    if not m:
        raise ExtractionError("%s: no '## Features' heading" % path)
    rest = text[m.end():]
    n = re.search(r"^## ", rest, re.M)
    section = rest[:n.start()] if n else rest
    heads = []
    for line in section.splitlines():
        lm = re.match(r"^- ((?:\*\*[^*]+\*\*(?:\s*,\s*|\s+and\s+|\s*:)?\s*)+)", line)
        if not lm:
            continue
        for h in re.findall(r"\*\*([^*]+)\*\*", lm.group(1)):
            h = h.strip().rstrip(":")
            if h not in heads:
                heads.append(h)
    if len(heads) < 10:
        raise ExtractionError("%s: only %d feature headings matched under '## Features'; "
                              "the bullet format changed" % (path, len(heads)))
    return heads


# --------------------------------------------------------------------------
# Probes
# --------------------------------------------------------------------------

def probe(root, rel, pattern):
    """True when `pattern` (regex) matches the comment-stripped file."""
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        raise ExtractionError("probe file missing: %s" % path)
    lang = "swift" if path.endswith(".swift") else "kotlin"
    _, plain = mask(read(path), lang)
    return re.search(pattern, plain) is not None
