"""Hand-crafted sample parking data for the MVP.

This stands in for the real Zurich feed until the data-freshness question
in README section 2.4 is resolved (the public OGD download is frozen at
2021; the live map's backend is fresher but not an intended integration
point). Swapping this module out for a real ingestion pipeline should not
require touching anything else -- callers only depend on `ALL_SEGMENTS`.

Labels are generated from each segment's offset rather than hand-picked
real street names on purpose: a fixed label like "Storchengasse 3" looks
like a real address, but nothing ties it to wherever `ORIGIN_LAT`/`ORIGIN_LON`
happens to point -- move the origin (as happened when the default start
point changed) and the label silently stops matching reality. A
direction-derived label can't drift out of sync like that.
"""

import math

from .geo import offset_point
from .models import ParkingSegment, ZoneType

# Default start location (central Zurich).
ORIGIN_LAT = 47.379198
ORIGIN_LON = 8.531307

_COMPASS_POINTS = [
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
]


def _compass_direction(north_m: float, east_m: float) -> str:
    angle = math.degrees(math.atan2(east_m, north_m)) % 360
    return _COMPASS_POINTS[round(angle / 22.5) % 16]


def _segment(
    id_: str,
    north_m: float,
    east_m: float,
    zone_type: ZoneType,
    estimated_capacity: int,
    max_duration_minutes: int | None = None,
) -> ParkingSegment:
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, east_m)
    direction = _compass_direction(north_m, east_m)
    return ParkingSegment(
        id=id_,
        lat=lat,
        lon=lon,
        zone_type=zone_type,
        address_label=f"Sample spot {id_} ({direction} of start)",
        estimated_capacity=estimated_capacity,
        max_duration_minutes=max_duration_minutes,
    )


# Three loose clusters at increasing distance from ORIGIN, mixing zone types,
# so the clustering/ranking and radius-expansion logic both have something
# to chew on.
ALL_SEGMENTS: list[ParkingSegment] = [
    # Cluster A: ~60-90m away, mostly blue zone.
    _segment("A1", 55, 20, ZoneType.BLUE, 2, 90),
    _segment("A2", 70, 15, ZoneType.BLUE, 3, 90),
    _segment("A3", 60, -10, ZoneType.WHITE, 1, 180),
    # Cluster B: ~180-230m away, mixed.
    _segment("B1", -150, 130, ZoneType.WHITE, 4, 120),
    _segment("B2", -170, 150, ZoneType.WHITE, 2, 120),
    _segment("B3", -160, 110, ZoneType.BLUE, 2, 90),
    _segment("B4", -190, 160, ZoneType.WHITE, 3, 120),
    # Cluster C: ~350-420m away, larger, mostly blue.
    _segment("C1", 300, -220, ZoneType.BLUE, 3, 90),
    _segment("C2", 320, -240, ZoneType.BLUE, 2, 90),
    _segment("C3", 310, -260, ZoneType.BLUE, 3, 90),
    _segment("C4", 340, -230, ZoneType.WHITE, 1, 60),
    # A lone far-out segment, only reachable after a radius expansion.
    _segment("D1", 600, 500, ZoneType.BLUE, 2, 90),
]
