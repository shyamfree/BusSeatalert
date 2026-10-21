"""Draws a PNG preview of the launcher icon using the same geometry as icon.py (108-unit canvas)."""
import sys
from PIL import Image, ImageDraw
S = 8  # pixels per unit
out = sys.argv[1]
W = 108 * S
img = Image.new("RGBA", (W, W))
# Gradient background #EF3B36 -> #B0121A along the diagonal.
px = img.load()
a, b = (0xEF, 0x3B, 0x36), (0xB0, 0x12, 0x1A)
for y in range(W):
    for x in range(W):
        t = (x + y) / (2 * W)
        px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
d = ImageDraw.Draw(img, "RGBA")
K, TX = 0.8, -2.4  # same scale/offset as the vector's <group>
def T(v): return (54 + (v - 54) * K) * S
def T_x(v): return (54 + (v - 54) * K + TX) * S
def rr(x1, y1, x2, y2, r, fill): d.rounded_rectangle([T_x(x1), T(y1), T_x(x2), T(y2)], r * K * S, fill=fill)
def c(cx, cy, r, fill): d.ellipse([T_x(cx) - r*K*S, T(cy) - r*K*S, T_x(cx) + r*K*S, T(cy) + r*K*S], fill=fill)
WHITE, DARK, GLASS, AMBER = "#FFFFFF", "#7A0A0E", "#C8161D", "#FFB300"
rr(35.5, 35, 76.5, 79, 8, "#9E0F16")
rr(37, 75.5, 44, 83, 2, DARK); rr(64, 75.5, 71, 83, 2, DARK)
rr(34, 32, 74, 77, 8, WHITE)
rr(30, 41, 33.5, 50, 1.5, WHITE); rr(74.5, 41, 78, 50, 1.5, WHITE)
rr(40, 35, 68, 39, 2, GLASS); rr(38, 42, 70, 59, 3.5, GLASS); rr(52.6, 42, 55.4, 59, 0.5, WHITE)
c(42.5, 67.5, 3.2, DARK); c(65.5, 67.5, 3.2, DARK); rr(48, 65.5, 60, 69.5, 1.5, DARK)
c(75, 33, 9.5, WHITE); c(75, 33, 7.8, AMBER)
d.line([(T_x(71.2), T(33.2)), (T_x(74.0), T(36.0)), (T_x(79.2), T(30.4))], fill=WHITE, width=int(2.4*K*S), joint="curve")
# Safe-zone circle a round launcher mask keeps (radius 33 around the centre), drawn faintly for checking.

# Crop to the visible 72-unit area and mask to a launcher squircle.
crop = img.crop((18*S, 18*S, 90*S, 90*S))
mask = Image.new("L", crop.size, 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, crop.size[0]-1, crop.size[1]-1], 22*S, fill=255)
icon = Image.new("RGBA", crop.size, (0, 0, 0, 0)); icon.paste(crop, mask=mask)
sheet = Image.new("RGBA", (crop.size[0] * 2 + 60, crop.size[1] + 40), "#F2F2F2")
sheet.paste(icon, (20, 20), icon)
small = icon.resize((144, 144), Image.LANCZOS)  # roughly home-screen size
sheet.paste(small, (crop.size[0] + 60, 20), small)
sheet.save(out)
