"""Map tile proxy.

CARTO requires an API key on every tile request. A key shipped in the web page's
JavaScript or the Android app is public by definition, so the clients ask this
server for tiles instead and the key stays in an environment variable (a Secret
Manager secret on Cloud Run). The proxy is deliberately narrow so it is not a
free general-purpose tile gateway: a fixed style allow-list, a bounding box
around Zurich, a small in-memory cache and its own per-IP rate limit (see
RateLimits.tiles in app.py).
"""

import math
import os
import threading
from collections import OrderedDict
from dataclasses import dataclass

import requests

# Styles clients may ask for; anything else is a 404 (also keeps the style
# segment from being used to reach other paths on the upstream host).
STYLES = frozenset({"light_all"})
MAX_ZOOM = 19
# Generous box around the canton of Zurich: the data covers the city only, so
# tiles elsewhere are not worth paying for.
REGION = (47.15, 47.70, 8.35, 9.00)  # south, north, west, east
UPSTREAM = "https://{sub}.basemaps.cartocdn.com/{style}/{z}/{x}/{y}{retina}.png"
SUBDOMAINS = "abcd"
CACHE_MAX_TILES = 1500
UPSTREAM_TIMEOUT_S = 5


class TileError(Exception):
    def __init__(self, status: int):
        super().__init__(status)
        self.status = status


@dataclass(frozen=True)
class Tile:
    body: bytes
    content_type: str


def tile_bounds(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    """(south, north, west, east) in degrees for a web-mercator tile."""
    n = 2 ** z

    def lat(row: int) -> float:
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * row / n))))

    return lat(y + 1), lat(y), x / n * 360 - 180, (x + 1) / n * 360 - 180


def in_region(z: int, x: int, y: int) -> bool:
    south, north, west, east = tile_bounds(z, x, y)
    r_south, r_north, r_west, r_east = REGION
    return south < r_north and north > r_south and west < r_east and east > r_west


class TileProxy:
    def __init__(self, api_key: str | None = None, http=None, cache_max: int = CACHE_MAX_TILES):
        # Without a key CARTO answers with watermarked tiles, which is the
        # right failure for a local checkout that has no key.
        self.api_key = api_key
        self.http = http if http is not None else requests.Session()
        self._cache: OrderedDict[tuple, Tile] = OrderedDict()
        self._cache_max = cache_max
        self._lock = threading.Lock()

    @classmethod
    def from_env(cls) -> "TileProxy":
        return cls(api_key=os.environ.get("CARTO_API_KEY") or None)

    def get(self, style: str, z: int, x: int, y: int, retina: bool = False) -> Tile:
        if style not in STYLES or not 0 <= z <= MAX_ZOOM:
            raise TileError(404)
        if not (0 <= x < 2 ** z and 0 <= y < 2 ** z) or not in_region(z, x, y):
            raise TileError(404)

        cache_key = (style, z, x, y, retina)
        with self._lock:
            tile = self._cache.get(cache_key)
            if tile is not None:
                self._cache.move_to_end(cache_key)
                return tile

        url = UPSTREAM.format(
            sub=SUBDOMAINS[(x + y) % len(SUBDOMAINS)],
            style=style, z=z, x=x, y=y, retina="@2x" if retina else "",
        )
        try:
            response = self.http.get(
                url,
                params={"key": self.api_key} if self.api_key else None,
                timeout=UPSTREAM_TIMEOUT_S,
            )
        except requests.RequestException:
            raise TileError(502) from None
        if response.status_code != 200 or not response.content:
            raise TileError(502)

        tile = Tile(response.content, response.headers.get("Content-Type", "image/png"))
        with self._lock:
            self._cache[cache_key] = tile
            while len(self._cache) > self._cache_max:
                self._cache.popitem(last=False)
        return tile
