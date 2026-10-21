"""Generates the launcher icon (Android adaptive vector XML + an SVG preview) from one shape list."""
import os, sys
res = sys.argv[1]; preview = sys.argv[2]

def rrect(x1, y1, x2, y2, r):
    return (f"M{x1+r},{y1} H{x2-r} A{r},{r} 0 0 1 {x2},{y1+r} V{y2-r} A{r},{r} 0 0 1 {x2-r},{y2} "
            f"H{x1+r} A{r},{r} 0 0 1 {x1},{y2-r} V{y1+r} A{r},{r} 0 0 1 {x1+r},{y1} Z")
def circle(cx, cy, r):
    return f"M{cx-r},{cy} A{r},{r} 0 1 0 {cx+r},{cy} A{r},{r} 0 1 0 {cx-r},{cy} Z"

WHITE, DARK, GLASS, AMBER, SHADOW = "#FFFFFF", "#7A0A0E", "#C8161D", "#FFB300", "#9E0F16"
# 108x108 canvas; keep content inside the 66dp safe circle (21..87).
fg = [
    (rrect(35.5, 35, 76.5, 79, 8), SHADOW),            # soft drop shadow
    (rrect(37, 82 - 6.5, 44, 83, 2), DARK),            # wheels
    (rrect(64, 82 - 6.5, 71, 83, 2), DARK),
    (rrect(34, 32, 74, 77, 8), WHITE),                 # bus body (front view)
    (rrect(30, 41, 33.5, 50, 1.5), WHITE),             # mirrors
    (rrect(74.5, 41, 78, 50, 1.5), WHITE),
    (rrect(40, 35, 68, 39, 2), GLASS),                 # destination board
    (rrect(38, 42, 70, 59, 3.5), GLASS),               # windscreen
    (rrect(52.6, 42, 55.4, 59, 0.5), WHITE),           # windscreen divider
    (circle(42.5, 67.5, 3.2), DARK),                   # headlights
    (circle(65.5, 67.5, 3.2), DARK),
    (rrect(48, 65.5, 60, 69.5, 1.5), DARK),            # grille
    (circle(75, 33, 9.5), WHITE),                      # badge ring
    (circle(75, 33, 7.8), AMBER),                      # amber "seat found" badge
]
check = ("M71.2,33.2 L74.0,36.0 L79.2,30.4", WHITE)    # tick in the badge (stroke)

def vector(paths, stroke=None, mono=False):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    # Shrink and centre so the badge and mirrors stay inside the 66dp safe circle on round masks.
    out.append('    <group android:pivotX="54" android:pivotY="54" android:scaleX="0.8" android:scaleY="0.8" android:translateX="-2.4">')
    for d, c in paths:
        if mono and c in (SHADOW,): continue
        col = "#FFFFFFFF" if mono else (c if len(c) == 9 else "#FF" + c[1:])
        if mono and c in (GLASS, DARK, AMBER): col = "#00000000" if c != AMBER else "#FFFFFFFF"
        out.append(f'    <path android:fillColor="{col}" android:pathData="{d}"/>')
    if stroke:
        d, c = stroke
        col = "#FF000000" if mono else "#FFFFFFFF"
        out.append(f'    <path android:strokeColor="{col}" android:strokeWidth="2.4" android:strokeLineCap="round" '
                   f'android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="{d}"/>')
    out.append('    </group>')
    out.append('</vector>')
    return "\n".join(out) + "\n"

background = '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FFEF3B36"/>
                <item android:offset="1" android:color="#FFB0121A"/>
            </gradient>
        </aapt:attr>
    </path>
    <path android:fillColor="#14FFFFFF" android:pathData="M0,0h108v52c-30,10 -70,10 -108,-6z"/>
</vector>
'''
os.makedirs(f"{res}/drawable", exist_ok=True); os.makedirs(f"{res}/mipmap-anydpi-v26", exist_ok=True)
open(f"{res}/drawable/ic_launcher_background.xml", "w").write(background)
open(f"{res}/drawable/ic_launcher_foreground.xml", "w").write(vector(fg, check))
# Monochrome (Android 13 themed icons): bus outline shapes only.
mono = [(d, c) for d, c in fg if c in (WHITE, DARK)]
mono_paths = [(d, WHITE) for d, c in fg if c == WHITE and "A9.5" not in d] + [(d, AMBER) for d, c in fg if c == AMBER]
open(f"{res}/drawable/ic_launcher_monochrome.xml", "w").write(vector([(d, "#FFFFFF") for d, c in fg if c == WHITE and "A9.5" not in d]))
adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
open(f"{res}/mipmap-anydpi-v26/ic_launcher.xml", "w").write(adaptive)
open(f"{res}/mipmap-anydpi-v26/ic_launcher_round.xml", "w").write(adaptive)

# SVG preview, masked to a squircle like a launcher would.
svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="18 18 72 72">',
       '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#EF3B36"/>'
       '<stop offset="1" stop-color="#B0121A"/></linearGradient>'
       f'<clipPath id="m"><path d="{rrect(18, 18, 90, 90, 22)}"/></clipPath></defs>',
       '<g clip-path="url(#m)"><rect x="0" y="0" width="108" height="108" fill="url(#g)"/>',
       '<path d="M0,0h108v52c-30,10 -70,10 -108,-6z" fill="#FFFFFF" fill-opacity="0.08"/>']
for d, c in fg:
    svg.append(f'<path d="{d}" fill="{c}"/>')
svg.append(f'<path d="{check[0]}" stroke="#FFFFFF" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
svg.append('</g></svg>')
open(preview, "w").write("\n".join(svg))
