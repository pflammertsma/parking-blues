"""Sanity checks on the ingested real Zurich data (backend/data/zurich_
parking.json via backend/parking_data.py) -- not testing the algorithm,
just that the snapshot loaded sanely: right shape, plausible zone split,
inside Zurich's bounding box. See scripts/ingest_zurich_parking.py.
"""

from backend.models import ZoneType
from backend.parking_data import ALL_SEGMENTS

# Generous bounding box around the City of Zurich (WGS84).
ZURICH_LAT_RANGE = (47.30, 47.45)
ZURICH_LON_RANGE = (8.40, 8.65)


def test_loads_a_realistic_number_of_segments():
    assert len(ALL_SEGMENTS) > 10_000


def test_all_segments_have_both_zone_types_represented():
    zone_types = {seg.zone_type for seg in ALL_SEGMENTS}
    assert zone_types == {ZoneType.BLUE, ZoneType.WHITE}


def test_all_segments_are_within_zurich():
    for seg in ALL_SEGMENTS:
        assert ZURICH_LAT_RANGE[0] <= seg.lat <= ZURICH_LAT_RANGE[1]
        assert ZURICH_LON_RANGE[0] <= seg.lon <= ZURICH_LON_RANGE[1]


def test_segment_ids_are_unique():
    ids = [seg.id for seg in ALL_SEGMENTS]
    assert len(ids) == len(set(ids))


def test_capacity_and_duration_are_sane():
    for seg in ALL_SEGMENTS:
        assert seg.estimated_capacity >= 1
        assert seg.max_duration_minutes is None or seg.max_duration_minutes > 0
