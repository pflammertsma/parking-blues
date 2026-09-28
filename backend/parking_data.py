"""Real Zurich on-street parking data, loaded from the snapshot in
backend/data/zurich_parking.json (produced by
scripts/ingest_zurich_parking.py from the city's official CC0 dataset --
see that script's docstring, and README section 2.4/6.1/9, for where this
comes from and its known freshness caveat).
"""

import json
from pathlib import Path

from .models import ParkingSegment, ZoneType

DATA_PATH = Path(__file__).resolve().parent / "data" / "zurich_parking.json"


def _load_segments() -> list[ParkingSegment]:
    with open(DATA_PATH) as f:
        records = json.load(f)
    return [
        ParkingSegment(
            id=r["id"],
            lat=r["lat"],
            lon=r["lon"],
            zone_type=ZoneType(r["zone_type"]),
            address_label=r["address_label"],
            estimated_capacity=r["estimated_capacity"],
            max_duration_minutes=r["max_duration_minutes"],
        )
        for r in records
    ]


ALL_SEGMENTS: list[ParkingSegment] = _load_segments()
