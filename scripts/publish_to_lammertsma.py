#!/usr/bin/env python3
"""Copy web/ (page, privacy policy, terms) into a lammertsma-dev checkout's public/projects/parking-blues/,
applying the small portfolio-site-specific tweaks (noindex, favicon, title
byline) that web/index.html itself doesn't carry -- see README section 11.

Always starts from the pristine web/index.html rather than patching the
destination in place, so it's safe to re-run on every publish (CI or
manual) without the tweaks stacking up.

Usage: scripts/publish_to_lammertsma.py <path to lammertsma-dev checkout>
"""

import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
WEB_DIR = REPO_ROOT / "web"

HEAD_INSERT = (
    '  <meta name="robots" content="noindex, nofollow" />\n'
    '  <link rel="icon" type="image/png" href="/favicon.png" />\n'
)


def patch_site_html(html: str) -> str:
    html = html.replace(
        '  <meta name="viewport" content="width=device-width, initial-scale=1" />\n',
        '  <meta name="viewport" content="width=device-width, initial-scale=1" />\n'
        + HEAD_INSERT,
        1,
    )
    html = re.sub(
        r"<title>(.*?)</title>",
        r"<title>\1 — Paul Lammertsma</title>",
        html,
        count=1,
    )
    return html


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        raise SystemExit(1)

    dest_dir = Path(sys.argv[1]) / "public" / "projects" / "parking-blues"
    dest_dir.mkdir(parents=True, exist_ok=True)

    for name in ("app.js", "style.css"):
        shutil.copyfile(WEB_DIR / name, dest_dir / name)

    # index.html plus the privacy policy and terms (linked from the Play listing,
    # the app and the footer). All get the same portfolio-site tweaks.
    for name in ("index.html", "privacy.html", "terms.html"):
        html = (WEB_DIR / name).read_text(encoding="utf-8")
        (dest_dir / name).write_text(patch_site_html(html), encoding="utf-8")

    print(f"Published web/ -> {dest_dir}")


if __name__ == "__main__":
    main()
