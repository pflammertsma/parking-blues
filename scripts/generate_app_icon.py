#!/usr/bin/env python3
"""Regenerate the app icon from its sources in `assets/`.

Run from the repo root after editing an SVG:

    python scripts/generate_app_icon.py

Sources (474x474 canvas, Inkscape layer labels, paths sharing one uniform
`matrix(s,0,0,s,tx,ty)` transform):

  assets/app icon.svg             groups labelled `background` and `foreground`
  assets/app icon monochrome.svg  a group labelled `monochrome`: the same mark as a single
                                  shape, which Android 13+ tints for "themed icons"

Writes, under android/car/src/main/res/:
  drawable/ic_launcher_{background,foreground,monochrome}.xml   the adaptive icon layers
  mipmap-anydpi-v26/ic_launcher{,_round}.xml                    the wrappers the manifest names
The layers are vector drawables (lint flags a <vector> in a mipmap folder); only the wrappers live
in mipmap. It also copies `app icon.svg` to web/icon.svg (the web page's header icon).

The 512 px Play Store icon is not generated here; render it with Inkscape:

    inkscape "assets/app icon.svg" --export-type=png \\
      --export-filename=assets/play-store-icon-512.png -w 512 -h 512
"""
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(".")
SRC = ROOT / "assets" / "app icon.svg"
SRC_MONO = ROOT / "assets" / "app icon monochrome.svg"
RES = ROOT / "android" / "car" / "src" / "main" / "res"
SVG = "{http://www.w3.org/2000/svg}"
INK = "{http://www.inkscape.org/namespaces/inkscape}"


def load(path: Path):
    root = ET.parse(path).getroot()
    assert root.get("width") == "474" and root.get("height") == "474", f"{path.name}: unexpected canvas size"
    groups = {g.get(INK + "label"): g for g in root.iter(SVG + "g") if g.get(INK + "label")}
    return groups


def layer_paths(group, source, strict=True):
    """The (fill, pathData) of every path in a group, plus their shared (sx, sy, tx, ty).

    A path without a transform is taken to be in canvas coordinates already (identity). With
    strict=False non-path children (defs, namedview...) are skipped instead of rejected, which is
    what a bare top-level path in the <svg> needs."""
    paths, transform = [], None
    for el in group:
        if el.tag != SVG + "path" and not strict:
            continue
        assert el.tag == SVG + "path", f"{source}: unexpected element {el.tag}"
        t = el.get("transform", "").strip()
        if t == "":
            t = "matrix(1,0,0,1,0,0)"
        m = re.fullmatch(r"matrix\(([-\d.]+),0,0,([-\d.]+),([-\d.]+),([-\d.]+)\)", t)
        assert m, f"{source}: unsupported transform {t!r}"
        a, d, e, f = m.groups()
        # Inkscape can leave scale factors a hair apart (1.673575 vs 1.6735595); Android takes
        # scaleX and scaleY separately, so a tiny difference is carried through, not rejected.
        assert abs(float(a) - float(d)) < 0.01, f"{source}: scale is not (nearly) uniform: {a} vs {d}"
        current = (a, d, e, f)
        assert transform in (None, current), f"{source}: paths use different transforms"
        transform = current
        found = re.search(r"fill:(#[0-9a-fA-F]{6})", el.get("style", ""))
        fill = found.group(1) if found else (el.get("fill") or "#000000")
        paths.append((fill, " ".join(el.get("d").split())))
    assert paths, f"{source}: no paths found"
    return paths, transform


def vector(body: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated from assets/app icon*.svg (474x474 viewBox). Edit those files and run\n'
        '     scripts/generate_app_icon.py rather than editing these paths. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="474"\n'
        '    android:viewportHeight="474">\n' + body + "</vector>\n"
    )


# The launcher masks the adaptive layers, so the P has to sit well inside the circle. That scaling
# now lives in the SVGs themselves (the mark was reduced to 80% there), so no extra shrink is
# applied here. If you ever need a quick adjustment without touching the SVGs, set this below 1.0:
# the foreground and monochrome layers are then shrunk together about the canvas centre.
ADAPTIVE_FOREGROUND_SCALE = 1.0
CANVAS_CENTER = 474 / 2


def shrink_about_center(transform, factor):
    """matrix(sx,0,0,sy,tx,ty) scaled by `factor` about the canvas centre c:
    p' = c + factor * (s*p + t - c) = (factor*s)*p + (factor*t + c*(1 - factor))."""
    sx, sy, tx, ty = transform
    if factor == 1.0:
        return sx, sy, tx, ty  # the SVG's own numbers, untouched
    shift = CANVAS_CENTER * (1 - factor)
    fmt = lambda v: f"{v:.7f}".rstrip("0").rstrip(".")
    return (
        fmt(factor * float(sx)), fmt(factor * float(sy)),
        fmt(factor * float(tx) + shift), fmt(factor * float(ty) + shift),
    )


def transformed(paths, transform, indent="    "):
    sx, sy, tx, ty = shrink_about_center(transform, ADAPTIVE_FOREGROUND_SCALE)
    return (
        f'  <group\n      android:scaleX="{sx}"\n      android:scaleY="{sy}"\n'
        f'      android:translateX="{tx}"\n      android:translateY="{ty}">\n'
        + "".join(
            f'{indent}<path\n{indent}    android:fillColor="{fill.upper()}"\n{indent}    android:pathData="{d}" />\n'
            for fill, d in paths
        )
        + "  </group>\n"
    )


groups = load(SRC)
assert {"background", "foreground"} <= set(groups), f"need background and foreground groups, got {set(groups)}"

# ---------------------------------------------------------------- background
bg_shapes = []
for el in groups["background"]:
    tag = el.tag.replace(SVG, "")
    if tag == "rect":
        # the full 474x474 square (its transform nets out to the origin)
        assert (el.get("width"), el.get("height")) == ("474", "474")
        fill = re.search(r"fill:(#[0-9a-fA-F]{6})", el.get("style", "")).group(1)
        bg_shapes.append((fill, "M 0,0 H 474 V 474 H 0 Z"))
    elif tag == "path":
        bg_shapes.append((el.get("fill"), el.get("d").strip() + " Z"))
assert len(bg_shapes) == 2, bg_shapes
bg_xml = vector("".join(
    f'  <path\n      android:fillColor="{fill.upper()}"\n      android:pathData="{d}" />\n'
    for fill, d in bg_shapes
))

# ---------------------------------------------------------------- foreground
fg_paths, fg_transform = layer_paths(groups["foreground"], SRC.name)
fg_xml = vector(transformed(fg_paths, fg_transform))

# --------------------------------------------------------------- monochrome
mono_xml = None
if SRC_MONO.exists():
    mono_groups = load(SRC_MONO)
    if "monochrome" in mono_groups:
        mono_paths, mono_transform = layer_paths(mono_groups["monochrome"], SRC_MONO.name)
    else:
        # No group at all is fine: a monochrome layer is one shape (the system tints it), so a
        # bare top-level path in canvas coordinates is read as is.
        mono_paths, mono_transform = layer_paths(ET.parse(SRC_MONO).getroot(), SRC_MONO.name, strict=False)
    # Only the alpha matters: the system tints this layer to the wallpaper colours.
    mono_xml = vector(transformed([("#000000", d) for _, d in mono_paths], mono_transform))

mip = RES / "mipmap-anydpi-v26"   # the wrappers the manifest names (@mipmap/ic_launcher)
drw = RES / "drawable"            # the layers: lint flags a <vector> in a mipmap folder
drw.mkdir(parents=True, exist_ok=True)
(drw / "ic_launcher_background.xml").write_text(bg_xml, encoding="utf-8", newline="\n")
(drw / "ic_launcher_foreground.xml").write_text(fg_xml, encoding="utf-8", newline="\n")
mono_file = drw / "ic_launcher_monochrome.xml"
if mono_xml:
    mono_file.write_text(mono_xml, encoding="utf-8", newline="\n")
elif mono_file.exists():
    mono_file.unlink()
# Earlier versions kept the layers in the mipmap folder; remove those copies.
for stale in ("ic_launcher_background.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
    if (mip / stale).exists():
        (mip / stale).unlink()

adaptive = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '  <background android:drawable="@drawable/ic_launcher_background" />\n'
    '  <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
    + ('  <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n' if mono_xml else "")
    + '</adaptive-icon>\n'
)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    (mip / name).write_text(adaptive, encoding="utf-8", newline="\n")

# One source, kept in step: the web page's copy.
shutil.copyfile(SRC, ROOT / "web" / "icon.svg")

print("background shapes:", len(bg_shapes))
print("foreground paths:", len(fg_paths), "transform", fg_transform)
print("monochrome:", "written" if mono_xml else "none (no assets/app icon monochrome.svg)")
