#!/usr/bin/env python3
"""Generate a temporary 1024x1024 app icon in the brand colors (navy + teal +
white). No alpha channel (App Store requires opaque icons). Swapped for the
real logo later."""
import sys
from PIL import Image, ImageDraw

SIZE = 1024
NAVY = (11, 26, 45)        # #0B1A2D
TEAL = (44, 192, 209)      # #2CC0D1
WHITE = (245, 248, 250)

img = Image.new("RGB", (SIZE, SIZE), NAVY)
d = ImageDraw.Draw(img)

# Teal ring
margin = 150
ring_w = 46
d.ellipse([margin, margin, SIZE - margin, SIZE - margin],
          outline=TEAL, width=ring_w)

# Morse "A" = dit dah, centered: a white dot then a white dash.
cx, cy = SIZE // 2, SIZE // 2
dot_r = 60
dash_w, dash_h = 300, 120
gap = 70
# total width of dot(2r) + gap + dash
total = dot_r * 2 + gap + dash_w
start_x = cx - total // 2
# dot
d.ellipse([start_x, cy - dot_r, start_x + dot_r * 2, cy + dot_r], fill=WHITE)
# dash (rounded rectangle)
dash_x0 = start_x + dot_r * 2 + gap
d.rounded_rectangle([dash_x0, cy - dash_h // 2, dash_x0 + dash_w, cy + dash_h // 2],
                    radius=dash_h // 2, fill=WHITE)

out = sys.argv[1]
img.save(out, "PNG")
print(f"OK wrote {out} ({img.size[0]}x{img.size[1]}, mode={img.mode})")
