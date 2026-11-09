"""Write an adaptive-icon foreground vector drawable that shows one word (e.g. TEAMS).

usage: word_icon.py FONT WORD OUT.xml [radius]
The word is centred in the 108x108 adaptive-icon viewport and sized so its bounding
box stays inside the safe-zone circle (radius ~31 of the 33 allowed).
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

font_path, word, out = sys.argv[1], sys.argv[2], sys.argv[3]
radius = float(sys.argv[4]) if len(sys.argv) > 4 else 31.0
font = TTFont(font_path)
gs = font.getGlyphSet()
cmap = font.getBestCmap()
upem = font['head'].unitsPerEm
track = (0.01 if len(word) > 5 else 0.03) * upem

# Layout in font units.
x = 0
placed = []
for ch in word:
    g = cmap[ord(ch)]
    placed.append((g, x))
    x += gs[g].width + track
bp = BoundsPen(gs)
for g, gx in placed:
    TransformPen(bp, (1, 0, 0, 1, gx, 0)) if False else None
# measure the real ink box
xmin = ymin = 1e9
xmax = ymax = -1e9
for g, gx in placed:
    b = BoundsPen(gs)
    gs[g].draw(b)
    if b.bounds:
        x0, y0, x1, y1 = b.bounds
        xmin, ymin = min(xmin, x0 + gx), min(ymin, y0)
        xmax, ymax = max(xmax, x1 + gx), max(ymax, y1)
w, h = xmax - xmin, ymax - ymin
# Scale so the box corners sit on a circle of the given radius.
s = radius / (((w / 2) ** 2 + (h / 2) ** 2) ** 0.5)
cx, cy = (xmin + xmax) / 2, (ymin + ymax) / 2
paths = []
for g, gx in placed:
    pen = SVGPathPen(gs, ntos=lambda v: ('%.2f' % v).rstrip('0').rstrip('.'))
    # font y-up -> drawable y-down, centred at (54, 54)
    t = TransformPen(pen, (s, 0, 0, -s, 54 + (gx - cx) * s, 54 + cy * s))
    gs[g].draw(t)
    d = pen.getCommands()
    if d:
        paths.append(d)
data = ' '.join(paths)
with open(out, 'w') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n')
    f.write('<!-- The word "%s" (generated from %s by solo/tools/word_icon.py). -->\n' % (word, font_path.split('/')[-1]))
    f.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
    f.write('    android:width="108dp" android:height="108dp"\n')
    f.write('    android:viewportWidth="108" android:viewportHeight="108">\n')
    f.write('    <path android:fillColor="#FFFFFF" android:pathData="%s" />\n' % data)
    f.write('</vector>\n')
print(word, 'box %.1f x %.1f units' % (w * s, h * s), 'scale', round(s, 4), len(data), 'chars')
