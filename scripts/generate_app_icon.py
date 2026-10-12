#!/usr/bin/env python3
"""Regenerate the app icon from its one source, `assets/app icon.svg`.

Run from the repo root after editing the SVG:

    python scripts/generate_app_icon.py

The SVG needs two top-level groups labelled `background` and `foreground` (Inkscape
labels), a 474x474 canvas, and foreground paths that share one uniform
`matrix(s,0,0,s,tx,ty)` transform. Writes the Android adaptive icon layers
(android/car/src/main/res/mipmap-anydpi-v26/ic_launcher_{background,foreground}.xml) and
copies the SVG to web/icon.svg (the web page's header icon).

The 512 px Play Store icon is not generated here; render it with Inkscape:

    inkscape "assets/app icon.svg" --export-type=png       --export-filename=assets/play-store-icon-512.png -w 512 -h 512
"""
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(".")
SRC = ROOT / "assets" / "app icon.svg"
RES = ROOT / "android" / "car" / "src" / "main" / "res"
SVG = "{http://www.w3.org/2000/svg}"
INK = "{http://www.inkscape.org/namespaces/inkscape}"

tree = ET.parse(SRC)
root = tree.getroot()
assert root.get("width") == "474" and root.get("height") == "474", "unexpected canvas size"

groups = {g.get(INK + "label"): g for g in root.iter(SVG + "g") if g.get(INK + "label")}
assert {"background", "foreground"} <= set(groups), f"need background and foreground groups, got {set(groups)}"

# ---------------------------------------------------------------- background
bg = groups["background"]
bg_shapes = []
for el in bg:
    tag = el.tag.replace(SVG, "")
    style = el.get("style", "")
    if tag == "rect":
        # the full 474x474 square (its transform nets out to the origin)
        assert (el.get("width"), el.get("height")) == ("474", "474")
        fill = re.search(r"fill:(#[0-9a-fA-F]{6})", style).group(1)
        bg_shapes.append((fill, "M 0,0 H 474 V 474 H 0 Z"))
    elif tag == "path":
        bg_shapes.append((el.get("fill"), el.get("d").strip() + " Z"))
assert len(bg_shapes) == 2, bg_shapes

# ---------------------------------------------------------------- foreground
fg = groups["foreground"]
fg_paths, transform = [], None
for el in fg:
    assert el.tag == SVG + "path", el.tag
    t = el.get("transform", "")
    m = re.fullmatch(r"matrix\(([-\d.]+),0,0,([-\d.]+),([-\d.]+),([-\d.]+)\)", t)
    assert m, f"unsupported transform {t!r}"
    a, d, e, f = m.groups()
    assert a == d, "non-uniform scale"
    current = (a, e, f)
    assert transform in (None, current), "paths use different transforms"
    transform = current
    fill = re.search(r"fill:(#[0-9a-fA-F]{6})", el.get("style", "")).group(1)
    fg_paths.append((fill, " ".join(el.get("d").split())))
scale, tx, ty = transform


def vector(body: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated from assets/app icon.svg (474x474 viewBox). Edit that file and run\n'
        '     scripts/generate_app_icon.py rather than editing these paths. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="474"\n'
        '    android:viewportHeight="474">\n' + body + "</vector>\n"
    )


bg_xml = vector("".join(
    f'  <path\n      android:fillColor="{fill.upper()}"\n      android:pathData="{d}" />\n'
    for fill, d in bg_shapes
))
fg_xml = vector(
    f'  <group\n      android:scaleX="{scale}"\n      android:scaleY="{scale}"\n'
    f'      android:translateX="{tx}"\n      android:translateY="{ty}">\n'
    + "".join(
        f'    <path\n        android:fillColor="{fill.upper()}"\n        android:pathData="{d}" />\n'
        for fill, d in fg_paths
    )
    + "  </group>\n"
)

mip = RES / "mipmap-anydpi-v26"
(mip / "ic_launcher_background.xml").write_text(bg_xml, encoding="utf-8", newline="\n")
(mip / "ic_launcher_foreground.xml").write_text(fg_xml, encoding="utf-8", newline="\n")

adaptive = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '  <background android:drawable="@mipmap/ic_launcher_background" />\n'
    '  <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
    '</adaptive-icon>\n'
)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    (mip / name).write_text(adaptive, encoding="utf-8", newline="\n")

# One source, kept in step: the web page's copy.
shutil.copyfile(SRC, ROOT / "web" / "icon.svg")

print("background shapes:", bg_shapes)
print("foreground:", [(f, d[:40] + "...") for f, d in fg_paths], "transform", transform)
