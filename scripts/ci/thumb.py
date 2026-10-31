#!/usr/bin/env python3
"""Prints a small JPEG thumbnail of a screenshot as base64 lines, so screenshots can be
read straight from the CI log:  THUMB <name> <index> <base64-chunk>"""
import base64, io, sys
from PIL import Image

path, name = sys.argv[1], sys.argv[2]
img = Image.open(path).convert("RGB")
w = 300
img = img.resize((w, int(img.height * w / img.width)))
buf = io.BytesIO()
img.save(buf, "JPEG", quality=55)
data = base64.b64encode(buf.getvalue()).decode()
for i in range(0, len(data), 900):
    print("THUMB %s %04d %s" % (name, i // 900, data[i:i + 900]))
