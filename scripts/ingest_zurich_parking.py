#!/usr/bin/env python3
"""Fetches the City of Zurich's official on-street parking dataset and
writes it to backend/data/zurich_parking.json for backend/parking_data.py
to load.

Source: "Oeffentlich zugaengliche Strassenparkplaetze OGD", City of
Zurich, CC0 (public domain equivalent) --
https://data.stadt-zuerich.ch/dataset/geo_oeffentlich_zugaengliche_strassenparkplaetze_ogd

IMPORTANT: the WFS endpoint below is live and always answers with
whatever it currently holds, but the dataset's own published metadata
says its *content* has been frozen since 2021-12-31 ("Datenstand per Ende
2021 und wird nicht mehr aktualisiert") -- re-running this script queries
a live service, but is not expected to surface new real-world data until
the city updates the source. See README section 2.4/6.1.

Usage: python3 scripts/ingest_zurich_parking.py
"""

import json
import sys
import urllib.request
from pathlib import Path

WFS_URL = (
    "https://www.ogd.stadt-zuerich.ch/wfs/geoportal/"
    "Oeffentlich_zugaengliche_Strassenparkplaetze_OGD"
    "?SERVICE=WFS&VERSION=1.1.0&REQUEST=GetFeature"
    "&TYPENAME=view_pp_ogd&OUTPUTFORMAT=application/vnd.geo+json"
)

# Only these two "art" values are what the app models as a zone type (see
# backend/models.ZoneType). The dataset also has a handful of disabled/taxi/
# coach/loading/EV-only bays that don't fit that blue/white model -- excluded
# rather than mis-tagged.
ART_TO_ZONE_TYPE = {
    "Blaue Zone": "blue",
    "Weiss markiert": "white",
}

OUTPUT_PATH = Path(__file__).resolve().parent.parent / "backend" / "data" / "zurich_parking.json"


def fetch_features() -> list[dict]:
    print(f"Fetching {WFS_URL}", file=sys.stderr)
    with urllib.request.urlopen(WFS_URL, timeout=60) as response:
        data = json.load(response)
    features = data["features"]
    print(f"Fetched {len(features)} raw features", file=sys.stderr)
    return features


def normalize(features: list[dict]) -> list[dict]:
    records = []
    skipped_art = {}
    for feature in features:
        props = feature["properties"]
        art = props.get("art")
        zone_type = ART_TO_ZONE_TYPE.get(art)
        if zone_type is None:
            skipped_art[art] = skipped_art.get(art, 0) + 1
            continue

        lon, lat = feature["geometry"]["coordinates"]
        object_id = int(props.get("id1") or props["objectid"])
        duration = props.get("parkdauer")
        duration_minutes = int(duration) if duration not in (None, "") else None

        records.append({
            "id": f"zh-{object_id}",
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "zone_type": zone_type,
            # The dataset has no street name/address field -- just an
            # internal ID. Good enough for an MVP; reverse-geocoding real
            # addresses is a possible future improvement (see README).
            "address_label": f"Street parking spot #{object_id}",
            "estimated_capacity": 1,  # each record is a single stall/point.
            "max_duration_minutes": duration_minutes,
        })

    print(f"Kept {len(records)} blue/white-zone records", file=sys.stderr)
    if skipped_art:
        print(f"Skipped (not blue/white zone): {skipped_art}", file=sys.stderr)
    return records


def main() -> None:
    features = fetch_features()
    records = normalize(features)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump(records, f, separators=(",", ":"))
    print(f"Wrote {len(records)} records to {OUTPUT_PATH}", file=sys.stderr)


if __name__ == "__main__":
    main()
