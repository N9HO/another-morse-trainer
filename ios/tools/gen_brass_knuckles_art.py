#!/usr/bin/env python3
"""Turn the Brass Knuckles Knock Out sprite data into per-app asset files.

Input:  firmware/src/sprites.rs from the game's repository (GPL-3.0-or-later,
        Derick KO6PBZ; see docs/brass-knuckles-design.md).
Output: one generated source file per tree (Swift for ios/, Kotlin for android/
        and desktop/), each holding the same decoded art:
          - every sprite as w, h, pivot and one hex digit per pixel (palette index),
          - every palette quantised through RGB565 exactly as the device does,
          - the LOOKS table (which torso/legs/kneel/emblem each opponent wears).

Usage: gen_brass_knuckles_art.py PATH/TO/sprites.rs [--check]
  --check  verify the committed files match what would be generated.

The three outputs are parallel ports, not shared code (see CLAUDE.md); this tool
is the only thing that touches all three, and only to write data.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SWIFT_OUT = ROOT / "ios/Sources/MorseKit/BrassKnucklesArt.swift"
KOTLIN_OUTS = [
    ROOT / "android/app/src/main/java/app/anothermorsetrainer/morsekit/BrassKnucklesArt.kt",
    ROOT / "desktop/app/src/main/kotlin/app/anothermorsetrainer/morsekit/BrassKnucklesArt.kt",
]

HEADER = (
    "Brass Knuckles Knock Out pixel art, decoded from the game's sprites.rs by\n"
    "ios/tools/gen_brass_knuckles_art.py. Hand edits are lost; regenerate.\n"
    "Art and game created by Derick (KO6PBZ), GPL-3.0-or-later, used with permission.\n"
)

SPRITE_TABLES = ["TORSO", "LEGS", "ARM", "HEAD", "GEAR", "FACE", "EXTRA",
                 "HERO_BODY", "HERO_ARM", "FX", "STAGE_TILE", "LOGO", "GLYPH"]
PALETTE_TABLES = ["FIGHTER_PALETTES", "STAGE_PALETTES"]
PALETTE_SINGLES = ["PLAYER_PALETTE", "FX_PALETTE", "SWEAT_PALETTE", "LOGO_PALETTE"]


def num_list(s):
    return [int(x, 16) for x in re.findall(r"0x[0-9A-Fa-f]+", s)]


def decode(w, h, data, marks):
    rows = []
    if not marks:
        stride = (w + 1) // 2
        for y in range(h):
            row = data[y * stride:(y + 1) * stride]
            rows.append([(row[x // 2] >> (4 if x % 2 == 0 else 0)) & 15 for x in range(w)])
        return rows
    i = 0
    for y in range(h):
        if y % 8 == 0:
            assert marks[y // 8] == i, f"mark {y // 8} is {marks[y // 8]}, run offset {i}"
        row = []
        while len(row) < w:
            run = data[i]
            i += 1
            row += [run & 15] * ((run >> 4) + 1)
        assert len(row) == w, "a run crossed a row"
        rows.append(row)
    assert i == len(data), "trailing bytes after the last row"
    return rows


def parse_sprites(src, name):
    m = re.search(r"pub static %s: \[Sprite; (\d+)\] = \[(.*?)\n\];" % name, src, re.S)
    assert m, name
    out = []
    for s in re.finditer(
            r"Sprite \{ w: (\d+), h: (\d+), ox: (-?\d+), oy: (-?\d+), marks: &\[(.*?)\], data: &\[(.*?)\] \}",
            m.group(2), re.S):
        w, h, ox, oy = (int(s.group(i)) for i in range(1, 5))
        marks = [int(x) for x in re.findall(r"\d+", s.group(5))]
        rows = decode(w, h, num_list(s.group(6)), marks)
        out.append((w, h, ox, oy, "".join("%x" % p for r in rows for p in r)))
    assert len(out) == int(m.group(1)), (name, len(out))
    return out


def q565(hex6):
    v = int(hex6, 16)
    r, g, b = (v >> 19) & 31, (v >> 10) & 63, (v >> 3) & 31
    return (r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2)


def pal(text):
    cols = re.findall(r"rgb\(0x([0-9A-Fa-f]{6})\)", text)
    assert len(cols) == 16
    return [q565(c) for c in cols]


def parse_palettes(src, name):
    m = re.search(r"pub static %s: \[Palette; (\d+)\] = \[(.*?)\n\];" % name, src, re.S)
    assert m, name
    blocks = re.findall(r"\[\s*(rgb\(.*?)\s*\],", m.group(2), re.S)
    out = [pal(b) for b in blocks]
    assert len(out) == int(m.group(1)), (name, len(out))
    return out


def parse_single(src, name):
    m = re.search(r"pub static %s: Palette = \[(.*?)\];" % name, src, re.S)
    assert m, name
    return pal(m.group(1))


def parse_looks(src):
    m = re.search(r"pub static LOOKS: \[Look; (\d+)\] = \[(.*?)\n\];", src, re.S)
    out = [(int(a), int(b), int(c), d == "true") for a, b, c, d in re.findall(
        r"Look \{ torso: (\d+), legs: (\d+), kneel: (\d+), emblem: (true|false) \}", m.group(2))]
    assert len(out) == int(m.group(1))
    return out


def argb(c):
    return 0xFF000000 | c[0] << 16 | c[1] << 8 | c[2]


def kotlin(art):
    L = ["// " + l for l in HEADER.strip().split("\n")]
    L += ["", "package app.anothermorsetrainer.morsekit", "",
          "/** One sprite: [pixels] holds one hex digit per pixel, row by row; digit 0 is clear. */",
          "class BkSprite(val w: Int, val h: Int, val ox: Int, val oy: Int, val pixels: String)", "",
          "/** Opponent look: indexes into the torso and legs tables. */",
          "class BkLook(val torso: Int, val legs: Int, val kneel: Int, val emblem: Boolean)", "",
          "object BrassKnucklesArt {"]
    for name, items in art["sprites"]:
        L.append(f"    val {name}: List<BkSprite> = listOf(")
        for (w, h, ox, oy, px) in items:
            L.append(f'        BkSprite({w}, {h}, {ox}, {oy}, "{px}"),')
        L.append("    )")
    for name, pals in art["palettes"]:
        L.append(f"    val {name}: List<IntArray> = listOf(")
        for p in pals:
            L.append("        intArrayOf(" + ", ".join("0x%08X.toInt()" % argb(c) for c in p) + "),")
        L.append("    )")
    for name, p in art["singles"]:
        L.append(f"    val {name}: IntArray = intArrayOf(" + ", ".join("0x%08X.toInt()" % argb(c) for c in p) + ")")
    L.append("    val LOOKS: List<BkLook> = listOf(")
    for t, l, k, e in art["looks"]:
        L.append(f"        BkLook({t}, {l}, {k}, {str(e).lower()}),")
    L += ["    )", "}", ""]
    return "\n".join(L)


def swift(art):
    L = ["// " + l for l in HEADER.strip().split("\n")]
    L += ["", "import Foundation", "",
          "/// One sprite: `pixels` holds one hex digit per pixel, row by row; digit 0 is clear.",
          "struct BkSprite { let w: Int; let h: Int; let ox: Int; let oy: Int; let pixels: String }", "",
          "/// Opponent look: indexes into the torso and legs tables.",
          "struct BkLook { let torso: Int; let legs: Int; let kneel: Int; let emblem: Bool }", "",
          "enum BrassKnucklesArt {"]
    for name, items in art["sprites"]:
        L.append(f"    static let {name.lower()}: [BkSprite] = [")
        for (w, h, ox, oy, px) in items:
            L.append(f'        BkSprite(w: {w}, h: {h}, ox: {ox}, oy: {oy}, pixels: "{px}"),')
        L.append("    ]")
    for name, pals in art["palettes"]:
        L.append(f"    static let {name.lower()}: [[UInt32]] = [")
        for p in pals:
            L.append("        [" + ", ".join("0x%08X" % argb(c) for c in p) + "],")
        L.append("    ]")
    for name, p in art["singles"]:
        L.append(f"    static let {name.lower()}: [UInt32] = [" + ", ".join("0x%08X" % argb(c) for c in p) + "]")
    L.append("    static let looks: [BkLook] = [")
    for t, l, k, e in art["looks"]:
        L.append(f"        BkLook(torso: {t}, legs: {l}, kneel: {k}, emblem: {str(e).lower()}),")
    L += ["    ]", "}", ""]
    return "\n".join(L)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src = Path(args[0]).read_text()
    art = {
        "sprites": [(n, parse_sprites(src, n)) for n in SPRITE_TABLES],
        "palettes": [(n, parse_palettes(src, n)) for n in PALETTE_TABLES],
        "singles": [(n, parse_single(src, n)) for n in PALETTE_SINGLES],
        "looks": parse_looks(src),
    }
    outs = [(SWIFT_OUT, swift(art))] + [(p, kotlin(art)) for p in KOTLIN_OUTS]
    if "--check" in sys.argv:
        bad = [str(p) for p, t in outs if not p.exists() or p.read_text() != t]
        if bad:
            sys.exit("out of date: " + ", ".join(bad))
        print("art files match")
        return
    for p, t in outs:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(t)
        print("wrote", p.relative_to(ROOT), len(t), "bytes")


if __name__ == "__main__":
    main()
