import pytest

from backend.app import create_app
from backend.geo import offset_point
from backend.models import ParkingSegment, ZoneType
from backend.session import APPROACH_THRESHOLD_M, DEPART_MARGIN_M, SessionStore

ORIGIN_LAT, ORIGIN_LON = 47.3703, 8.5386


def make_segment(id_, north_m, east_m=0, zone_type=ZoneType.BLUE):
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, east_m)
    return ParkingSegment(
        id=id_, lat=lat, lon=lon, zone_type=zone_type,
        address_label=id_, estimated_capacity=1,
    )


@pytest.fixture
def client():
    segments = [make_segment("A", 100), make_segment("B", 250)]
    app = create_app(store=SessionStore(segments))
    app.config.update(TESTING=True)
    return app.test_client()


def test_index_serves_the_web_ui(client):
    response = client.get("/")
    assert response.status_code == 200
    assert b"Parking Blues" in response.data


def test_create_session_requires_lat_lon(client):
    response = client.post("/api/session", json={"zone": "blue"})
    assert response.status_code == 400


def test_create_session_rejects_unknown_zone(client):
    response = client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON, "zone": "purple"}
    )
    assert response.status_code == 400


def test_create_session_returns_nearest_candidate(client):
    response = client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON, "zone": "both"}
    )
    assert response.status_code == 201
    body = response.get_json()
    assert body["state"] == "searching"
    assert body["current"]["id"] == "A"
    assert body["session_id"]


def test_unknown_session_id_returns_404(client):
    response = client.get("/api/session/does-not-exist")
    assert response.status_code == 404


def test_full_drive_by_flow_via_the_api(client):
    created = client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON, "zone": "both"}
    ).get_json()
    session_id = created["session_id"]
    assert created["current"]["id"] == "A"

    def send(north_m):
        lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, 0)
        return client.post(
            f"/api/session/{session_id}/position", json={"lat": lat, "lon": lon}
        ).get_json()

    body = send(100 - (APPROACH_THRESHOLD_M - 1))
    assert body["event"] == "tracking"

    body = send(100 + APPROACH_THRESHOLD_M + DEPART_MARGIN_M)
    assert body["event"] == "auto_rejected"
    assert body["current"]["id"] == "B"
    assert body["rejected_count"] == 1


def test_blue_zone_segments_carry_a_legal_until_field_white_zone_does_not(client):
    segments = [
        make_segment("blue-spot", 50, zone_type=ZoneType.BLUE),
        make_segment("white-spot", 60, zone_type=ZoneType.WHITE),
    ]
    app = create_app(store=SessionStore(segments))
    app.config.update(TESTING=True)
    local_client = app.test_client()

    created = local_client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON, "zone": "both"}
    ).get_json()
    assert created["current"]["id"] == "blue-spot"
    assert "legal_until" in created["current"]

    white = created["upcoming"][0]
    assert white["id"] == "white-spot"
    assert "legal_until" not in white


def test_confirm_marks_session_parked(client):
    created = client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON, "zone": "both"}
    ).get_json()
    session_id = created["session_id"]
    body = client.post(f"/api/session/{session_id}/confirm").get_json()
    assert body["state"] == "parked"
    assert body["current"] is None


def test_reject_until_exhausted_then_expand(client):
    created = client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON, "zone": "both", },
    ).get_json()
    session_id = created["session_id"]

    body = client.post(f"/api/session/{session_id}/reject").get_json()
    assert body["current"]["id"] == "B"

    body = client.post(f"/api/session/{session_id}/reject").get_json()
    assert body["state"] == "exhausted"
    assert body["current"] is None

    body = client.post(f"/api/session/{session_id}/expand").get_json()
    # Both candidates were rejected, so expanding the radius still leaves
    # nothing to offer -- state should remain exhausted, not error out.
    assert body["state"] == "exhausted"
