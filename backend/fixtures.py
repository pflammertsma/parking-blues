"""Hand-crafted sample parking data for the MVP.

This stands in for the real Zurich feed until the data-freshness question
in README section 2.4 is resolved (the public OGD download is frozen at
2021; the live map's backend is fresher but not an intended integration
point). Swapping this module out for a real ingestion pipeline should not
require touching anything else -- callers only depend on `ALL_SEGMENTS`.
"""

from .geo import offset_point
from .models import ParkingSegment, ZoneType

# Roughly Paradeplatz, central Zurich.
ORIGIN_LAT = 47.3703
ORIGIN_LON = 8.5386


def _segment(
    id_: str,
    north_m: float,
    east_m: float,
    zone_type: ZoneType,
    address_label: str,
    estimated_capacity: int,
    max_duration_minutes: int | None = None,
) -> ParkingSegment:
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, east_m)
    return ParkingSegment(
        id=id_,
        lat=lat,
        lon=lon,
        zone_type=zone_type,
        address_label=address_label,
        estimated_capacity=estimated_capacity,
        max_duration_minutes=max_duration_minutes,
    )


# Three loose clusters at increasing distance from ORIGIN, mixing zone types,
# so the clustering/ranking and radius-expansion logic both have something
# to chew on.
ALL_SEGMENTS: list[ParkingSegment] = [
    # Cluster A: ~60-90m away, mostly blue zone.
    _segment("A1", 55, 20, ZoneType.BLUE, "Storchengasse 3", 2, 90),
    _segment("A2", 70, 15, ZoneType.BLUE, "Storchengasse 5", 3, 90),
    _segment("A3", 60, -10, ZoneType.WHITE, "Storchengasse 8", 1, 180),
    # Cluster B: ~180-230m away, mixed.
    _segment("B1", -150, 130, ZoneType.WHITE, "Rennweg 12", 4, 120),
    _segment("B2", -170, 150, ZoneType.WHITE, "Rennweg 14", 2, 120),
    _segment("B3", -160, 110, ZoneType.BLUE, "Rennweg 20", 2, 90),
    _segment("B4", -190, 160, ZoneType.WHITE, "Rennweg 22", 3, 120),
    # Cluster C: ~350-420m away, larger, mostly blue.
    _segment("C1", 300, -220, ZoneType.BLUE, "Lindenhofstrasse 2", 3, 90),
    _segment("C2", 320, -240, ZoneType.BLUE, "Lindenhofstrasse 4", 2, 90),
    _segment("C3", 310, -260, ZoneType.BLUE, "Lindenhofstrasse 6", 3, 90),
    _segment("C4", 340, -230, ZoneType.WHITE, "Lindenhofstrasse 9", 1, 60),
    # A lone far-out segment, only reachable after a radius expansion.
    _segment("D1", 600, 500, ZoneType.BLUE, "Uraniastrasse 1", 2, 90),
]
