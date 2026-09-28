from datetime import datetime

from backend.duration_filter import segment_supports_duration
from backend.models import ParkingSegment, ZoneType

# Monday 2026-09-28 09:00 -> next disc mark 09:30, deadline 10:30 (90 min away).
WEEKDAY_MORNING = datetime(2026, 9, 28, 9, 0)
SUNDAY = datetime(2026, 10, 4, 15, 0)


def blue_segment(max_duration_minutes=60):
    return ParkingSegment(
        id="b1", lat=47.0, lon=8.0, zone_type=ZoneType.BLUE,
        address_label="b1", estimated_capacity=1,
        max_duration_minutes=max_duration_minutes,
    )


def white_segment(max_duration_minutes):
    return ParkingSegment(
        id="w1", lat=47.0, lon=8.0, zone_type=ZoneType.WHITE,
        address_label="w1", estimated_capacity=1,
        max_duration_minutes=max_duration_minutes,
    )


def test_blue_zone_on_a_weekday_morning_has_90_minutes_available():
    assert segment_supports_duration(blue_segment(), 90, WEEKDAY_MORNING)
    assert not segment_supports_duration(blue_segment(), 91, WEEKDAY_MORNING)


def test_blue_zone_on_sunday_supports_any_requested_duration():
    assert segment_supports_duration(blue_segment(), 10_000, SUNDAY)


def test_white_zone_with_no_recorded_cap_supports_any_requested_duration():
    assert segment_supports_duration(white_segment(None), 10_000, WEEKDAY_MORNING)


def test_white_zone_respects_its_flat_cap():
    seg = white_segment(60)
    assert segment_supports_duration(seg, 60, WEEKDAY_MORNING)
    assert segment_supports_duration(seg, 30, WEEKDAY_MORNING)
    assert not segment_supports_duration(seg, 61, WEEKDAY_MORNING)
