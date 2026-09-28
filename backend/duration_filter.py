"""Whether a parking spot can accommodate how long the driver wants to
stay, given the current Zurich time -- see README section 9.2.
"""

from datetime import datetime

from .blue_zone_rules import blue_zone_deadline
from .models import ParkingSegment, ZoneType


def segment_supports_duration(
    segment: ParkingSegment, requested_minutes: int, now: datetime
) -> bool:
    """`now` must be Zurich-local (`blue_zone_rules.ZURICH_TZ`) for a
    blue-zone segment to be judged against the right rule window.
    """
    if segment.zone_type == ZoneType.BLUE:
        deadline = blue_zone_deadline(now)
        if deadline is None:
            return True  # unrestricted right now (e.g. Sunday)
        available_minutes = (deadline - now).total_seconds() / 60
        return available_minutes >= requested_minutes

    # White zone: no time-of-day rule is modeled here (see README section
    # 2.3/9) -- just the flat per-spot cap from the source data. A missing
    # cap is treated as "no recorded limit", i.e. it satisfies any
    # requested duration.
    if segment.max_duration_minutes is None:
        return True
    return segment.max_duration_minutes >= requested_minutes
