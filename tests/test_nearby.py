"""The stateless "what is parked near here" view the web page opens with."""

from datetime import datetime

import pytest

from backend.app import RateLimits, create_app
from backend.blue_zone_rules import blue_zone_deadline, blue_zone_disc_mark
from backend.geo import offset_point
from backend.models import ParkingSegment, ZoneType
from backend.session import SessionStore

LAT, LON = 47.3769, 8.5417  # near Zurich HB


def seg(sid, north_m, east_m=0.0, zone=ZoneType.BLUE, capacity=1):
    lat, lon = offset_point(LAT, LON, north_m, east_m)
    return ParkingSegment(
        id=sid, lat=lat, lon=lon, zone_type=zone, address_label=sid,
        estimated_capacity=capacity, max_duration_minutes=120 if zone == ZoneType.WHITE else 60,
    )


def make_client(segments, rate_limits=None):
    store = SessionStore(segments)
    app = create_app(store=store, rate_limits=rate_limits)
    app.config.update(TESTING=True)
    return app.test_client(), store


def get(client, **params):
    params.setdefault("lat", LAT)
    params.setdefault("lon", LON)
    return client.get("/api/nearby", query_string=params)


# ------------------------------------------------------------ the disc mark

def test_disc_mark_is_the_next_half_hour_in_restricted_hours():
    monday_morning = datetime(2026, 9, 28, 9, 12)
    mark = blue_zone_disc_mark(monday_morning)
    assert (mark.hour, mark.minute) == (9, 30)
    # the deadline is the mark plus one hour
    assert blue_zone_deadline(monday_morning) == mark.replace(hour=10, minute=30)


@pytest.mark.parametrize("arrival", [
    datetime(2026, 9, 28, 12, 0),    # free lunch hour
    datetime(2026, 9, 28, 19, 0),    # evening
    datetime(2026, 9, 28, 6, 30),    # early morning
    datetime(2026, 10, 4, 10, 0),    # Sunday
])
def test_no_disc_is_needed_outside_the_restricted_windows(arrival):
    assert blue_zone_disc_mark(arrival) is None


# ---------------------------------------------------------------- the view

def test_nearby_returns_ranked_areas_and_keeps_no_session():
    client, store = make_client([seg("a", 40), seg("b", 45), seg("c", 150, zone=ZoneType.WHITE)])

    response = get(client)

    assert response.status_code == 200
    body = response.get_json()
    assert [a["rank"] for a in body["areas"]] == list(range(1, len(body["areas"]) + 1))
    assert {a["zone_type"] for a in body["areas"]} == {"blue", "white"}
    assert len(store) == 0  # nothing was stored
    blue = next(a for a in body["areas"] if a["zone_type"] == "blue")
    assert blue["spot_count"] == 2 and blue["capacity"] == 2
    assert len(blue["spots"]) == 2
    assert "disc_mark" in blue and "legal_until" in blue
    white = next(a for a in body["areas"] if a["zone_type"] == "white")
    assert white["estimated_fee_chf_per_hour"] > 0 and white["max_duration_minutes"] == 120


def test_a_cluster_mixing_zones_becomes_one_area_per_zone():
    # Blue and white spots a few metres apart cluster together but follow different rules.
    client, _ = make_client([seg("b", 40), seg("w", 44, zone=ZoneType.WHITE)])
    areas = get(client).get_json()["areas"]
    assert sorted(a["zone_type"] for a in areas) == ["blue", "white"]


def test_zone_filter_and_stay_are_applied():
    client, _ = make_client([seg("b", 40), seg("w", 200, zone=ZoneType.WHITE)])
    assert {a["zone_type"] for a in get(client, zone="blue").get_json()["areas"]} == {"blue"}
    assert {a["zone_type"] for a in get(client, zone="white").get_json()["areas"]} == {"white"}
    assert get(client, stay="30").get_json()["stay_minutes"] == 30


def test_the_search_widens_until_something_is_found():
    client, _ = make_client([seg("far", 700)])
    body = get(client).get_json()
    assert len(body["areas"]) == 1
    assert body["radius_m"] >= 700


def test_nothing_nearby_is_an_empty_list_not_an_error():
    client, _ = make_client([seg("far", 5000)])
    body = get(client).get_json()
    assert body["areas"] == []
    assert body["radius_m"] == 1000.0


@pytest.mark.parametrize("params", [
    {"lat": "abc"}, {"lat": "999"}, {"zone": "purple"}, {"stay": "0"}, {"stay": "x"}, {"stay": "5000"},
])
def test_bad_input_is_a_400(params):
    client, _ = make_client([seg("a", 40)])
    assert get(client, **params).status_code == 400


def test_missing_coordinates_is_a_400():
    client, _ = make_client([seg("a", 40)])
    assert client.get("/api/nearby").status_code == 400


def test_nearby_has_its_own_rate_limit():
    client, _ = make_client([seg("a", 40)], rate_limits=RateLimits(nearby="2 per minute"))
    assert [get(client).status_code for _ in range(3)] == [200, 200, 429]
