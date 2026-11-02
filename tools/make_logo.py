"""Builds the ASANNI logo (wordmark "ASANN" + hourglass as the final "I") as vector paths.

Font: Archivo (SIL Open Font License 1.1) variable font, instanced at a heavy, wide setting to
match the wordmark in the reference intro video. Letters are converted to outlines, so the app
needs no font file and the logo is sharp at every size.

Usage: python3 make_logo.py FONT.woff2 OUT_DIR [wght] [wdth]
Writes: logo.svg (preview), intro_logo_wordmark.xml / asanni_logo.xml (VectorDrawables),
        intro_hourglass.xml (large hourglass for the intro), and prints the geometry.
"""
import os
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

FONT, OUT = sys.argv[1], sys.argv[2]
WGHT = float(sys.argv[3]) if len(sys.argv) > 3 else 900
WDTH = float(sys.argv[4]) if len(sys.argv) > 4 else 125
os.makedirs(OUT, exist_ok=True)

font = TTFont(FONT)
if "fvar" in font:
    axes = {a.axisTag: a for a in font["fvar"].axes}
    loc = {}
    if "wght" in axes:
        loc["wght"] = max(axes["wght"].minValue, min(axes["wght"].maxValue, WGHT))
    if "wdth" in axes:
        loc["wdth"] = max(axes["wdth"].minValue, min(axes["wdth"].maxValue, WDTH))
    print("instancing at", loc)
    font = instancer.instantiateVariableFont(font, loc)

gs = font.getGlyphSet()
cmap = font.getBestCmap()
upm = font["head"].unitsPerEm
cap = getattr(font["OS/2"], "sCapHeight", 0) or int(upm * 0.7)

# ---- geometry, in "video pixels" (video is 390 px wide; we treat 1 px = 1 dp) ----
CAP_PX = 32.0          # ASANN cap height measured in the video (y395-427)
HG_W, HG_H = 40.0, 55.0  # small hourglass box (x301-341, y386-441)
GAP_PX = 5.0           # gap between last N and hourglass (296 -> 301)
TRACK_PX = 0.0         # extra letter spacing
TARGET_TEXT_W = 238.0  # ASANN ink width in the video (x58-296)
scale = CAP_PX / cap
XS = 1.0  # horizontal stretch, computed below to match the video width

from fontTools.pens.boundsPen import BoundsPen as _BP
_x = 0.0; _min, _max = 1e9, -1e9
for ch in "ASANN":
    g = gs[cmap[ord(ch)]]; b = _BP(gs); g.draw(b)
    if b.bounds:
        _min = min(_min, _x + b.bounds[0] * scale); _max = max(_max, _x + b.bounds[2] * scale)
    _x += g.width * scale
XS = TARGET_TEXT_W / (_max - _min)
print(f"horizontal stretch {XS:.3f}")
paths = []
x = 0.0
for ch in "ASANN":
    gname = cmap[ord(ch)]
    pen = SVGPathPen(gs)
    # font y-up -> svg y-down; baseline at y = CAP_PX (so cap top is y=0)
    tpen = TransformPen(pen, (scale * XS, 0, 0, -scale, x, CAP_PX))
    gs[gname].draw(tpen)
    d = pen.getCommands()
    if d:
        paths.append(d)
    x += gs[gname].width * scale * XS + TRACK_PX
# the glyph advance includes right side bearing; measure true ink width via bounds
from fontTools.pens.boundsPen import BoundsPen
bp = BoundsPen(gs)
xx = 0.0
minx, maxx = 1e9, -1e9
for ch in "ASANN":
    g = gs[cmap[ord(ch)]]
    b = BoundsPen(gs)
    g.draw(b)
    if b.bounds:
        minx = min(minx, xx + b.bounds[0] * scale * XS)
        maxx = max(maxx, xx + b.bounds[2] * scale * XS)
    xx += g.width * scale * XS + TRACK_PX
text_w = maxx - minx
print(f"wordmark ink width {text_w:.1f}px (video: 238px), cap {CAP_PX}px")

# shift text so ink starts at x=0
shift = -minx


def hourglass(ox, oy, w, h):
    """Hourglass drawn to match the reference: two bars, curved glass outline, sand, dots.
    Designed on a 90 x 130 grid and scaled to (w, h). Returns (filled_paths, stroked_paths, stroke)."""
    sx, sy = w / 90.0, h / 130.0
    def P(px, py):
        return f"{ox + px * sx:.2f},{oy + py * sy:.2f}"
    fills = [
        # top and bottom bars
        f"M{P(0,0)}L{P(90,0)}L{P(90,12)}L{P(0,12)}Z",
        f"M{P(0,118)}L{P(90,118)}L{P(90,130)}L{P(0,130)}Z",
        # sand in the top bulb (wavy surface, funnel to the neck)
        f"M{P(21,36)}C{P(30,30)} {P(39,37)} {P(50,33)}C{P(58,30)} {P(64,30)} {P(70,28)}"
        f"C{P(66,44)} {P(52,52)} {P(48,62)}L{P(42,62)}C{P(38,52)} {P(25,46)} {P(21,36)}Z",
        # falling grains
        f"M{P(45,66)}m{-2.2*sx:.2f},0a{2.2*sx:.2f},{2.2*sy:.2f} 0,1 1,{4.4*sx:.2f},0a{2.2*sx:.2f},{2.2*sy:.2f} 0,1 1,{-4.4*sx:.2f},0Z",
        f"M{P(45,74)}m{-2.2*sx:.2f},0a{2.2*sx:.2f},{2.2*sy:.2f} 0,1 1,{4.4*sx:.2f},0a{2.2*sx:.2f},{2.2*sy:.2f} 0,1 1,{-4.4*sx:.2f},0Z",
        # sand mound in the bottom bulb
        f"M{P(20,112)}C{P(20,94)} {P(32,85)} {P(45,85)}C{P(58,85)} {P(70,94)} {P(70,112)}Z",
    ]
    strokes = [
        f"M{P(13,12)}L{P(13,31)}C{P(14,47)} {P(39,55)} {P(39,65)}C{P(39,75)} {P(13,83)} {P(13,99)}L{P(13,118)}",
        f"M{P(77,12)}L{P(77,31)}C{P(76,47)} {P(51,55)} {P(51,65)}C{P(51,75)} {P(77,83)} {P(77,99)}L{P(77,118)}",
    ]
    return fills, strokes, 7.0 * (sx + sy) / 2


hg_x = text_w + GAP_PX
hg_y = -9.0  # hourglass top sits 9 px above the cap top (386 vs 395)
hg_fills, hg_strokes, hg_stroke = hourglass(hg_x, hg_y, HG_W, HG_H)
total_w = hg_x + HG_W
top = hg_y
height = HG_H

def translate_d(d, dx, dy):
    # cheap: wrap via group transform in SVG; for vector XML use a <group translateX/Y>
    return d

# ---------- SVG preview ----------
def svg(text_fill, hg_fill, bg=None, scale_out=3):
    w, h = total_w, height
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w*scale_out:.0f}" height="{h*scale_out:.0f}" '
           f'viewBox="0 {top:.2f} {w:.2f} {h:.2f}">']
    if bg:
        out.append(f'<rect x="0" y="{top}" width="{w}" height="{h}" fill="{bg}"/>')
    out.append(f'<g transform="translate({shift:.2f},0)">')
    for d in paths:
        out.append(f'<path fill="{text_fill}" d="{d}"/>')
    out.append('</g>')
    for d in hg_fills:
        out.append(f'<path fill="{hg_fill}" d="{d}"/>')
    for d in hg_strokes:
        out.append(f'<path fill="none" stroke="{hg_fill}" stroke-width="{hg_stroke:.2f}" d="{d}"/>')
    out.append('</svg>')
    return "\n".join(out)

open(os.path.join(OUT, "logo_intro.svg"), "w").write(svg("#FFFBF0", "#000000", "#9C1227"))

# ---------- VectorDrawables ----------
def vector(text_color, hg_color, comment):
    out = ['<?xml version="1.0" encoding="utf-8"?>', comment,
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{total_w:.1f}dp"', f'    android:height="{height:.1f}dp"',
           f'    android:viewportWidth="{total_w:.2f}"', f'    android:viewportHeight="{height:.2f}">',
           f'    <group android:translateY="{-top:.2f}">',
           f'        <group android:name="wordmark" android:translateX="{shift:.2f}">']
    for d in paths:
        out.append(f'            <path android:fillColor="{text_color}" android:pathData="{d}" />')
    out.append('        </group>')
    out.append('        <group android:name="hourglass">')
    for d in hg_fills:
        out.append(f'            <path android:fillColor="{hg_color}" android:pathData="{d}" />')
    for d in hg_strokes:
        out.append(f'            <path android:strokeColor="{hg_color}" android:strokeWidth="{hg_stroke:.2f}" '
                   f'android:pathData="{d}" />')
    out.append('        </group>')
    out.append('    </group>')
    out.append('</vector>')
    return "\n".join(out) + "\n"

def wordmark_only(text_color, comment):
    """ASANN without the hourglass (the intro animates the hourglass separately)."""
    out = ['<?xml version="1.0" encoding="utf-8"?>', comment,
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{text_w:.1f}dp"', f'    android:height="{CAP_PX:.1f}dp"',
           f'    android:viewportWidth="{text_w:.2f}"', f'    android:viewportHeight="{CAP_PX:.2f}">',
           f'    <group android:translateX="{shift:.2f}">']
    for d in paths:
        out.append(f'        <path android:fillColor="{text_color}" android:pathData="{d}" />')
    out.append('    </group>')
    out.append('</vector>')
    return "\n".join(out) + "\n"

def hourglass_only(color, w, h, comment):
    f, s, sw = hourglass(0, 0, w, h)
    out = ['<?xml version="1.0" encoding="utf-8"?>', comment,
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{w:.1f}dp"', f'    android:height="{h:.1f}dp"',
           f'    android:viewportWidth="{w:.2f}"', f'    android:viewportHeight="{h:.2f}">']
    for d in f:
        out.append(f'    <path android:fillColor="{color}" android:pathData="{d}" />')
    for d in s:
        out.append(f'    <path android:strokeColor="{color}" android:strokeWidth="{sw:.2f}" android:pathData="{d}" />')
    out.append('</vector>')
    return "\n".join(out) + "\n"

LIC = "     Letters: Archivo (SIL Open Font License 1.1) converted to outlines. Generated by tools/make_logo.py."
open(os.path.join(OUT, "asanni_logo.xml"), "w").write(vector(
    "@color/asanni_welcome_ink", "@color/asanni_welcome_ink",
    "<!-- [UI ASSET] Replace with the ASANNI logo from the ui folder.\n"
    "     ASANNI logo: \"ASANN\" + hourglass as the final I (as in the intro video and Get Started\n"
    "     reference). Uses the ink color, so it is dark on the light theme and light on the dark theme.\n"
    "     Get Started shows it automatically when this file exists (see GetStartedActivity.setUpLogo).\n"
    + LIC + " -->"))
open(os.path.join(OUT, "intro_logo_wordmark.xml"), "w").write(wordmark_only(
    "@color/asanni_intro_wordmark",
    "<!-- [UI ASSET] Replace with the ASANNI logo from the ui folder.\n"
    "     Intro screen: the \"ASANN\" letters (the hourglass \"I\" is a separate view so it can fade in).\n"
    + LIC + " -->"))
open(os.path.join(OUT, "intro_hourglass.xml"), "w").write(hourglass_only(
    "@color/asanni_intro_hourglass", 90.0, 130.0,
    "<!-- [UI ASSET] Replace with the intro illustration from the ui folder.\n"
    "     Hourglass used twice in the intro: large (rises in, then out) and small (the final I).\n"
    "     Original drawing made to match the reference video. Generated by tools/make_logo.py. -->"))

print(f"logo box {total_w:.1f} x {height:.1f}px; text {text_w:.1f}px; hourglass at x={hg_x:.1f}")