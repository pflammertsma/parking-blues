"""Anonymous-abuse protection: rate limits, bounded sessions, input validation."""

import pytest

from backend.app import RateLimits, create_app
from backend.geo import offset_point
from backend.models import ParkingSegment, ZoneType
from backend.session import SessionStore

ORIGIN_LAT, ORIGIN_LON = 47.3703, 8.5386


def make_store(**kwargs):
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 100, 0)
    segment = ParkingSegment(
        id="A", lat=lat, lon=lon, zone_type=ZoneType.BLUE,
        address_label="A", estimated_capacity=1, max_duration_minutes=None,
    )
    return SessionStore([segment], **kwargs)


def make_client(rate_limits=None, store=None):
    # `is None`: an empty SessionStore has len() == 0 and is falsy.
    app = create_app(store=store if store is not None else make_store(), rate_limits=rate_limits)
    app.config.update(TESTING=True)
    return app.test_client()


def create_session(client, headers=None):
    return client.post(
        "/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON}, headers=headers or {}
    )


def post_position(client, session_id, headers=None):
    return client.post(
        f"/api/session/{session_id}/position",
        json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON},
        headers=headers or {},
    )


# ------------------------------------------------------------ rate limits

def test_creating_sessions_is_limited_per_client_and_answers_429_with_retry_after():
    client = make_client(RateLimits(create_session="3 per minute"))
    assert [create_session(client).status_code for _ in range(3)] == [201, 201, 201]

    blocked = create_session(client)
    assert blocked.status_code == 429
    assert blocked.get_json()["error"] == "rate limit exceeded"
    assert int(blocked.headers["Retry-After"]) > 0


def test_position_updates_are_limited_per_session_not_per_client():
    client = make_client(RateLimits(position_per_session="2 per minute"))
    first = create_session(client).get_json()["session_id"]
    second = create_session(client).get_json()["session_id"]

    assert post_position(client, first).status_code == 200
    assert post_position(client, first).status_code == 200
    assert post_position(client, first).status_code == 429
    # A different session from the same client is unaffected.
    assert post_position(client, second).status_code == 200


def test_clients_behind_different_addresses_are_counted_separately():
    client = make_client(RateLimits(create_session="2 per minute"))
    a = {"X-Forwarded-For": "203.0.113.1"}
    b = {"X-Forwarded-For": "203.0.113.2"}

    assert create_session(client, a).status_code == 201
    assert create_session(client, a).status_code == 201
    assert create_session(client, a).status_code == 429
    assert create_session(client, b).status_code == 201


def test_a_client_cannot_dodge_the_limit_by_sending_its_own_forwarded_header():
    # Cloud Run appends the real client address as the LAST entry; anything the
    # client sent before it is untrusted and must not change the identity.
    client = make_client(RateLimits(create_session="2 per minute"))
    results = [
        create_session(client, {"X-Forwarded-For": f"198.51.100.{i}, 203.0.113.9"}).status_code
        for i in range(4)
    ]
    assert results == [201, 201, 429, 429]


def test_429_responses_are_readable_cross_origin():
    client = make_client(RateLimits(create_session="1 per minute"))
    origin = {"Origin": "https://lammertsma.dev"}
    create_session(client, origin)
    blocked = create_session(client, origin)
    assert blocked.status_code == 429
    assert blocked.headers["Access-Control-Allow-Origin"] == "https://lammertsma.dev"
    assert "Retry-After" in blocked.headers["Access-Control-Expose-Headers"]


def test_limits_can_be_switched_off():
    client = make_client(RateLimits(create_session="1 per minute", enabled=False))
    assert all(create_session(client).status_code == 201 for _ in range(5))


def test_default_limits_leave_a_normal_drive_unthrottled():
    client = make_client()
    session_id = create_session(client).get_json()["session_id"]
    # One position update per second for a minute.
    assert all(post_position(client, session_id).status_code == 200 for _ in range(60))


# ------------------------------------------------------ bounded sessions

def test_idle_sessions_expire_and_activity_keeps_them_alive():
    now = [0.0]
    store = make_store(clock=lambda: now[0], idle_ttl_s=100)
    client = make_client(RateLimits(enabled=False), store=store)

    active = create_session(client).get_json()["session_id"]
    abandoned = create_session(client).get_json()["session_id"]

    now[0] = 90
    assert client.get(f"/api/session/{active}").status_code == 200  # touched at t=90
    now[0] = 150
    assert client.get(f"/api/session/{abandoned}").status_code == 404  # idle for 150 s
    assert client.get(f"/api/session/{active}").status_code == 200  # idle for only 60 s


def test_session_count_is_capped_dropping_the_longest_idle_first():
    now = [0.0]
    store = make_store(clock=lambda: now[0], max_sessions=3)
    client = make_client(RateLimits(enabled=False), store=store)

    ids = []
    for t in (1, 2, 3, 4):
        now[0] = t
        ids.append(create_session(client).get_json()["session_id"])

    assert len(store) == 3
    assert client.get(f"/api/session/{ids[0]}").status_code == 404  # oldest evicted
    assert all(client.get(f"/api/session/{i}").status_code == 200 for i in ids[1:])


# -------------------------------------------------------- input hygiene

@pytest.mark.parametrize(
    "payload",
    [
        {"lat": "NaN", "lon": 8.5},
        {"lat": "inf", "lon": 8.5},
        {"lat": 91, "lon": 8.5},
        {"lat": 47.3, "lon": -181},
        {"lat": 47.3},
        {"lat": "abc", "lon": "def"},
    ],
)
def test_invalid_coordinates_are_rejected(payload):
    client = make_client(RateLimits(enabled=False))
    response = client.post("/api/session", json=payload)
    assert response.status_code == 400


def test_oversized_request_bodies_are_rejected():
    client = make_client(RateLimits(enabled=False))
    response = client.post(
        "/api/session",
        data=b'{"lat": 47.3, "lon": 8.5, "pad": "' + b"x" * 20_000 + b'"}',
        content_type="application/json",
    )
    assert response.status_code == 413
