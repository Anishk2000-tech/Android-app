#!/usr/bin/env python3
"""Minimal uiautomator helpers for the CI emulator test.

  ui.py texts                 print every visible text / content-description
  ui.py tap "Label" [scrolls] tap the first node whose text or description contains Label,
                              scrolling down up to N times to find it
  ui.py has "Label"           exit 0 if a node with that text/description is on screen
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        start = xml.find("<?xml")
        if start >= 0:
            try:
                return ET.fromstring(xml[start:])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def labels(node):
    return [(node.get("text") or "").strip(), (node.get("content-desc") or "").strip()]


def find(root, label):
    if root is None:
        return None
    for n in root.iter("node"):
        if any(label in l for l in labels(n) if l):
            return n
    return None


def center(node):
    m = re.findall(r"\d+", node.get("bounds", ""))
    if len(m) != 4:
        return None
    x1, y1, x2, y2 = map(int, m)
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    cmd = sys.argv[1]
    if cmd == "texts":
        root = dump()
        seen = set()
        for n in (root.iter("node") if root is not None else []):
            for l in labels(n):
                if l and l not in seen:
                    seen.add(l)
                    print(l)
        return 0
    label = sys.argv[2]
    if cmd == "has":
        return 0 if find(dump(), label) is not None else 1
    if cmd == "tap":
        scrolls = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        for i in range(scrolls + 1):
            node = find(dump(), label)
            c = center(node) if node is not None else None
            if c:
                adb("shell", "input", "tap", str(c[0]), str(c[1]))
                print("tapped %r at %s" % (label, c))
                return 0
            adb("shell", "input", "swipe", "540", "1700", "540", "500", "250")
            time.sleep(1.2)
        print("could not find %r" % label)
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
