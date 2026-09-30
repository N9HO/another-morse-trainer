#!/usr/bin/env python3
"""Derive the Mac icon slots of AppIcon.appiconset from the iOS master
(icon_1024.png), for the Mac Catalyst build (#264).

iOS masks and shadows an app icon itself, so the master is a full-bleed opaque
square. macOS does not: a Mac icon is drawn pre-shaped, a rounded rectangle
824 px wide on a transparent 1024 px canvas (Apple's macOS icon grid) with a
soft drop shadow. Without the mac slots, actool derives an .icns that stops at
256 px, and the Mac App Store asks for 512 and 512@2x.

Re-run after changing icon_1024.png:
    python3 tools/gen_mac_icon.py
Writes icon_mac_<size>.png next to the master; Contents.json already lists them.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SET = os.path.join(HERE, "..", "MorseTrainerApp", "Assets.xcassets", "AppIcon.appiconset")

CANVAS = 1024
BODY = 824            # the rounded rectangle's side on the 1024 grid
RADIUS = 185          # its corner radius
SHADOW_DY, SHADOW_BLUR, SHADOW_ALPHA = 10, 14, 90
SS = 4                # supersampling for a clean mask edge


def rounded_mask(side: int, radius: int) -> Image.Image:
    big = Image.new("L", (side * SS, side * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        [0, 0, side * SS - 1, side * SS - 1], radius=radius * SS, fill=255)
    return big.resize((side, side), Image.LANCZOS)


def main():
    master = Image.open(os.path.join(SET, "icon_1024.png")).convert("RGBA")
    body = master.resize((BODY, BODY), Image.LANCZOS)
    mask = rounded_mask(BODY, RADIUS)
    body.putalpha(mask)

    off = (CANVAS - BODY) // 2
    shadow = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    shade = Image.new("RGBA", (BODY, BODY), (0, 0, 0, SHADOW_ALPHA))
    shadow.paste(shade, (off, off + SHADOW_DY), mask)
    icon = shadow.filter(ImageFilter.GaussianBlur(SHADOW_BLUR))
    icon.alpha_composite(body, (off, off))

    for size in (16, 32, 64, 128, 256, 512, 1024):
        out = os.path.join(SET, f"icon_mac_{size}.png")
        icon.resize((size, size), Image.LANCZOS).save(out, "PNG")
        print(f"wrote {os.path.relpath(out)}")


if __name__ == "__main__":
    main()
