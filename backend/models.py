from dataclasses import dataclass
from enum import Enum


class ZoneType(str, Enum):
    BLUE = "blue"
    WHITE = "white"


@dataclass(frozen=True)
class ParkingSegment:
    """A single legal on-street parking spot/segment.

    Simplified to a point for this MVP -- the real source data (see README
    section 2) models curb segments as lines, but a point target is enough
    to drive a "go here" / "did we pass it" workflow.
    """

    id: str
    lat: float
    lon: float
    zone_type: ZoneType
    address_label: str
    estimated_capacity: int
    max_duration_minutes: int | None = None

    def matches(self, zone_filter: set[ZoneType]) -> bool:
        return self.zone_type in zone_filter
