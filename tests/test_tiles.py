"""Map tile proxy: keeps CARTO's API key server-side and refuses to be a
general-purpose tile gateway."""

import pytest
import requests

from backend.app import RateLimits, create_app
from backend.tiles import TileProxy, in_region, tile_bounds

KEY = "secret-carto-key"
PNG = b"\x89PNG\r\n\x1a\nfake-tile"
# A tile over the Hauptbahnhof at z16: x/y from the standard slippy-map formula.
Z, X, Y = 16, 34322, 22949


class FakeUpstream:
    def __init__(self, status=200, body=PNG, error=None):
        self.status, self.body, self.error = status, body, error
        self.calls = []

    def get(self, url, params=None, timeout=None):
        self.calls.append((url, params))
        if self.error:
            raise self.error

        class Response:
            status_code = self.status
            content = self.body
            headers = {"Content-Type": "image/png"}

        return Response()


def make_client(upstream=None, key=KEY, rate_limits=None):
    upstream = upstream if upstream is not None else FakeUpstream()
    app = create_app(tiles=TileProxy(api_key=key, http=upstream), rate_limits=rate_limits)
    app.config.update(TESTING=True)
    return app.test_client(), upstream


def test_tile_geometry_matches_the_slippy_map_grid():
    south, north, west, east = tile_bounds(Z, X, Y)
    assert south < 47.3779 < north
    assert west < 8.5402 < east
    assert in_region(Z, X, Y)
    assert not in_region(16, 19294, 24640)  # New York


def test_tile_is_fetched_with_the_key_and_the_key_is_never_sent_to_the_client():
    client, upstream = make_client()

    response = client.get(f"/api/tiles/light_all/{Z}/{X}/{Y}.png")

    assert response.status_code == 200
    assert response.data == PNG
    assert response.mimetype == "image/png"
    assert "max-age" in response.headers["Cache-Control"]
    (url, params), = upstream.calls
    assert url.startswith("https://") and "/light_all/16/34322/22949.png" in url
    assert params == {"key": KEY}
    assert KEY.encode() not in response.data
    assert KEY not in str(dict(response.headers))


def test_retina_tiles_use_the_2x_suffix():
    client, upstream = make_client()
    assert client.get(f"/api/tiles/light_all/{Z}/{X}/{Y}@2x.png").status_code == 200
    assert upstream.calls[0][0].endswith(f"/{Y}@2x.png")


def test_repeated_tiles_come_from_the_cache():
    client, upstream = make_client()
    for _ in range(3):
        assert client.get(f"/api/tiles/light_all/{Z}/{X}/{Y}.png").data == PNG
    assert len(upstream.calls) == 1


def test_cache_is_bounded():
    upstream = FakeUpstream()
    proxy = TileProxy(api_key=KEY, http=upstream, cache_max=2)
    for dx in range(3):
        proxy.get("light_all", Z, X + dx, Y)
    proxy.get("light_all", Z, X, Y)  # evicted, fetched again
    assert len(upstream.calls) == 4


def test_without_a_key_the_tile_is_requested_unkeyed():
    client, upstream = make_client(key=None)
    assert client.get(f"/api/tiles/light_all/{Z}/{X}/{Y}.png").status_code == 200
    assert upstream.calls[0][1] is None


@pytest.mark.parametrize("path", [
    f"/api/tiles/dark_matter/{Z}/{X}/{Y}.png",        # style not allowed
    f"/api/tiles/..%2Fadmin/{Z}/{X}/{Y}.png",          # path trickery
    f"/api/tiles/light_all/20/{X}/{Y}.png",            # zoom too high
    f"/api/tiles/light_all/{Z}/99999999/{Y}.png",      # outside the grid
    "/api/tiles/light_all/16/19294/24640.png",         # New York
    f"/api/tiles/light_all/{Z}/-1/{Y}.png",            # negative
])
def test_requests_outside_the_allowed_tiles_are_refused_without_calling_upstream(path):
    client, upstream = make_client()
    assert client.get(path).status_code == 404
    assert upstream.calls == []


@pytest.mark.parametrize("upstream", [
    FakeUpstream(status=403),
    FakeUpstream(status=200, body=b""),
    FakeUpstream(error=requests.ConnectionError("boom")),
])
def test_upstream_failures_become_502_and_are_not_cached(upstream):
    client, _ = make_client(upstream)
    assert client.get(f"/api/tiles/light_all/{Z}/{X}/{Y}.png").status_code == 502
    assert client.get(f"/api/tiles/light_all/{Z}/{X}/{Y}.png").status_code == 502
    assert len(upstream.calls) == 2


def test_tiles_have_their_own_rate_limit():
    client, _ = make_client(rate_limits=RateLimits(tiles="3 per minute"))
    codes = [client.get(f"/api/tiles/light_all/{Z}/{X + i}/{Y}.png").status_code for i in range(4)]
    assert codes == [200, 200, 200, 429]
