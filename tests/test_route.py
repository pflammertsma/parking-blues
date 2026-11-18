"""The experimental parking route: the planning model and the /api/route endpoint.

The router is faked throughout, so these run offline and say nothing about how accurate the
real OpenStreetMap routes are; that is for a person driving them to judge.
"""

import itertools

import pytest
import requests

from backend.app import RateLimits, create_app
from backend.geo import haversine_m, offset_point
from backend.models import ParkingSegment, ZoneType
from backend.route_planner import (
    MAX_STOPS,
    NO_ROAD_NEAR_DESTINATION,
    NO_ROAD_NEAR_START,
    NOT_CONNECTED,
    ROUTING_BUSY,
    ROUTING_UNAVAILABLE,
    OsrmRouter,
    Route,
    RoutingError,
    Snap,
    Table,
    build_candidates,
    chance_of_space,
    choose_stops,
    expected_cost,
    plan_route,
)
from backend.session import SessionStore

LAT, LON = 47.3769, 8.5417  # the destination, near Zurich HB


def spot(sid, north_m, east_m=0.0, zone=ZoneType.BLUE, capacity=1):
    lat, lon = offset_point(LAT, LON, north_m, east_m)
    return ParkingSegment(
        id=sid, lat=lat, lon=lon, zone_type=zone, address_label=sid,
        estimated_capacity=capacity, max_duration_minutes=60,
    )


def run(north_m, count, zone=ZoneType.BLUE, prefix="r"):
    """A kerb run of `count` adjacent spots (5 m apart) starting `north_m` of the destination."""
    return [spot(f"{prefix}{i}", north_m + 5 * i, zone=zone) for i in range(count)]


class FakeRouter:
    """Drives at 10 m/s along straight lines.

    `one_way` makes some moves a long loop; `snap(point)` is how far a point is from a road;
    `cut_off(point)` puts a point on a road network that reaches nothing else; `alternatives(point)`
    are the other roads near a point (what `nearest` returns after the first).
    """

    def __init__(self, one_way=None, fail=None, snap=None, cut_off=None, alternatives=None):
        self.one_way = one_way or (lambda a, b: False)
        self.fail = fail
        self.snap = snap or (lambda p: 0.0)
        self.cut_off = cut_off or (lambda p: False)
        self.alternatives = alternatives or (lambda p: [])
        self.calls = []

    def _seconds(self, a, b):
        seconds = haversine_m(*a, *b) / 10.0
        return seconds + 300 if self.one_way(a, b) else seconds

    def table(self, points):
        self.calls.append(("table", list(points)))
        if self.fail:
            raise self.fail
        durations = [
            [
                0.0 if a == b else (None if self.cut_off(a) != self.cut_off(b) else self._seconds(a, b))
                for b in points
            ]
            for a in points
        ]
        return Table(durations=durations, snap_m=[self.snap(p) for p in points])

    def route(self, points):
        self.calls.append(("route", list(points)))
        if self.fail:
            raise self.fail
        total = sum(haversine_m(*a, *b) for a, b in zip(points, points[1:]))
        first = points[0]
        # The road the start snaps to: `snap(first)` metres north of it.
        on_road = offset_point(first[0], first[1], self.snap(first), 0.0)
        return Route(
            path=list(points), distance_m=total, duration_s=total / 10.0,
            start=Snap(on_road[0], on_road[1], self.snap(first), "Some Street"),
        )

    def nearest(self, point, count):
        self.calls.append(("nearest", point))
        first = Snap(point[0], point[1], self.snap(point), "Nearest")
        return ([first] + list(self.alternatives(point)))[:count]


def far_start(north_m=-1500):
    return offset_point(LAT, LON, north_m, 0.0)


# ----------------------------------------------------------------------- the model

def test_a_bigger_area_has_a_better_chance_with_diminishing_returns():
    assert chance_of_space(1) < chance_of_space(5) < chance_of_space(20) < 1.0
    assert chance_of_space(20) - chance_of_space(10) < chance_of_space(5) - chance_of_space(1)


def test_a_dense_area_is_planned_and_a_tiny_one_off_the_way_is_not():
    dense = run(150, 12)                           # 12 spaces, 150 m from the destination
    tiny = [spot("tiny", 20, east_m=400)]          # 1 space, a 400 m detour east
    plan = plan_route(FakeRouter(), far_start(), (LAT, LON), [dense, tiny])

    stopped_at = {s.candidate.spots[0].id for s in plan.stops}
    assert "r0" in stopped_at
    assert "tiny" not in stopped_at


def test_a_small_area_on_the_way_is_worth_it_when_it_costs_nothing():
    on_the_way = [spot("on-way", -200, capacity=3)]
    plan = plan_route(FakeRouter(), far_start(), (LAT, LON), [on_the_way])
    assert [s.candidate.spots[0].id for s in plan.stops] == ["on-way"]


def test_the_stop_order_is_the_cheapest_order_not_the_input_or_ranking_order():
    # Trying the better area first can beat passing the nearer one first when the backtrack is
    # cheap; what matters is that no other order of the same stops is expected to be quicker.
    areas = [run(60, 6, prefix="b"), run(-250, 6, prefix="a"), run(-600, 6, prefix="c")]
    plan = plan_route(FakeRouter(), far_start(), (LAT, LON), areas)
    assert len(plan.stops) >= 2

    chosen = [s.candidate for s in plan.stops]
    points = [far_start(), (LAT, LON)] + [(c.lat, c.lon) for c in chosen]
    t = FakeRouter().table(points).durations
    best = expected_cost(list(range(len(chosen))), chosen, t)
    for order in itertools.permutations(range(len(chosen))):
        assert best <= expected_cost(list(order), chosen, t) + 1e-6
    assert [s.eta_s for s in plan.stops] == sorted(s.eta_s for s in plan.stops)


def test_one_way_streets_change_the_order_because_drive_times_are_directional():
    a = run(-400, 6, prefix="a")
    b = run(-100, 6, prefix="b")
    a_pt = (sum(s.lat for s in a) / 6, sum(s.lon for s in a) / 6)
    b_pt = (sum(s.lat for s in b) / 6, sum(s.lon for s in b) / 6)

    def near(p, q):
        return haversine_m(*p, *q) < 30

    # Going from A to B is a long loop round the one-way system; B to A is direct.
    router = FakeRouter(one_way=lambda x, y: near(x, a_pt) and near(y, b_pt))
    plan = plan_route(router, far_start(), (LAT, LON), [a, b])
    ids = [s.candidate.spots[0].id for s in plan.stops]
    assert "a0" not in ids or ids.index("b0") < ids.index("a0")


def test_the_plan_never_exceeds_the_stop_limit():
    areas = [run(40 + 60 * i, 8, prefix=f"s{i}-") for i in range(10)]
    plan = plan_route(FakeRouter(), far_start(), (LAT, LON), areas)
    assert len(plan.stops) <= MAX_STOPS


def test_greedy_choice_never_makes_the_expected_cost_worse():
    cands = build_candidates([run(100, 5), run(300, 9), [spot("z", 500)]], (LAT, LON))
    points = [far_start(), (LAT, LON)] + [(c.lat, c.lon) for c in cands]
    t = FakeRouter().table(points).durations
    order = choose_stops(cands, t)
    assert expected_cost(order, cands, t) <= expected_cost([], cands, t)


def test_no_areas_means_no_plan():
    assert plan_route(FakeRouter(), far_start(), (LAT, LON), []) is None


# --------------------------------------------------- points that are not on a road

def test_a_start_on_a_road_has_no_gap_to_show():
    plan = plan_route(FakeRouter(), far_start(), (LAT, LON), [run(100, 5)])
    assert plan.start_gap is None


def test_a_start_a_little_off_the_road_shows_where_the_road_starts():
    start = far_start()
    router = FakeRouter(snap=lambda p: 120.0 if p == start else 0.0)
    plan = plan_route(router, start, (LAT, LON), [run(100, 5)])

    assert plan.start_gap is not None
    assert 100 < plan.start_gap.distance_m < 140
    assert plan.start_gap.name == "Some Street"


def test_a_start_too_far_from_any_road_is_refused_not_routed_from_somewhere_else():
    start = far_start()
    router = FakeRouter(snap=lambda p: 700.0 if p == start else 0.0)
    with pytest.raises(RoutingError) as caught:
        plan_route(router, start, (LAT, LON), [run(100, 5)])
    assert caught.value.code == NO_ROAD_NEAR_START
    assert not any(call[0] == "route" for call in router.calls)


def test_a_destination_off_a_road_is_fine_up_to_a_point():
    dest = (LAT, LON)
    router = FakeRouter(snap=lambda p: 300.0 if p == dest else 0.0)
    assert plan_route(router, far_start(), dest, [run(100, 5)]) is not None


def test_a_destination_far_from_any_road_is_refused():
    dest = (LAT, LON)
    router = FakeRouter(snap=lambda p: 900.0 if p == dest else 0.0)
    with pytest.raises(RoutingError) as caught:
        plan_route(router, far_start(), dest, [run(100, 5)])
    assert caught.value.code == NO_ROAD_NEAR_DESTINATION


def test_a_parking_area_that_is_not_on_a_road_is_not_offered_as_a_stop():
    off_road = run(150, 12, prefix="off")
    on_road = run(180, 12, prefix="on")
    off_pt = (sum(s.lat for s in off_road) / 12, sum(s.lon for s in off_road) / 12)
    router = FakeRouter(snap=lambda p: 100.0 if haversine_m(*p, *off_pt) < 1 else 0.0)

    plan = plan_route(router, far_start(), (LAT, LON), [off_road, on_road])

    ids = {s.candidate.spots[0].id for s in plan.stops}
    assert "off0" not in ids
    assert "on0" in ids


def test_with_no_area_on_a_road_there_is_no_plan():
    router = FakeRouter(snap=lambda p: 100.0 if p not in (far_start(), (LAT, LON)) else 0.0)
    assert plan_route(router, far_start(), (LAT, LON), [run(100, 5)]) is None


def test_a_start_on_a_cut_off_road_uses_the_next_road():
    start = far_start()
    next_road = Snap(*offset_point(start[0], start[1], 0.0, 60.0), distance_m=60.0, name="Next")
    router = FakeRouter(
        cut_off=lambda p: p == start,
        alternatives=lambda p: [next_road] if p == start else [],
    )
    plan = plan_route(router, start, (LAT, LON), [run(100, 5)])

    assert plan is not None
    first_route_waypoint = [c for c in router.calls if c[0] == "route"][0][1][0]
    assert first_route_waypoint == (next_road.lat, next_road.lon)
    # The driver is 60 m from where the road starts: that is shown.
    assert plan.start_gap is not None and 40 < plan.start_gap.distance_m < 80


def test_a_cut_off_start_whose_other_road_is_too_far_is_refused():
    start = far_start()
    far_road = Snap(*offset_point(start[0], start[1], 0.0, 900.0), distance_m=900.0, name="Far")
    router = FakeRouter(
        cut_off=lambda p: p == start,
        alternatives=lambda p: [far_road] if p == start else [],
    )
    with pytest.raises(RoutingError) as caught:
        plan_route(router, start, (LAT, LON), [run(100, 5)])
    assert caught.value.code == NO_ROAD_NEAR_START


def test_a_destination_on_a_cut_off_road_uses_the_next_road():
    dest = (LAT, LON)
    next_road = Snap(*offset_point(LAT, LON, 0.0, 50.0), distance_m=50.0, name="Next")
    router = FakeRouter(
        cut_off=lambda p: p == dest,
        alternatives=lambda p: [next_road] if p == dest else [],
    )
    assert plan_route(router, far_start(), dest, [run(100, 5)]) is not None
    last_route_waypoint = [c for c in router.calls if c[0] == "route"][0][1][-1]
    assert last_route_waypoint == (next_road.lat, next_road.lon)


def test_when_no_road_connects_the_points_it_says_so():
    start = far_start()
    router = FakeRouter(cut_off=lambda p: p == start)
    with pytest.raises(RoutingError) as caught:
        plan_route(router, start, (LAT, LON), [run(100, 5)])
    assert caught.value.code == NOT_CONNECTED


def test_the_alternatives_are_limited_so_one_request_cannot_cost_many_calls():
    start = far_start()
    # Every road near the start (same latitude) is as cut off as the start itself.
    router = FakeRouter(
        cut_off=lambda p: p[0] == start[0],
        alternatives=lambda p: [Snap(p[0], p[1] + i * 1e-4, 10.0 * i) for i in range(1, 20)],
    )
    with pytest.raises(RoutingError):
        plan_route(router, start, (LAT, LON), [run(100, 5)])
    assert len([c for c in router.calls if c[0] == "table"]) <= 1 + 2 * 2


# ---------------------------------------------------------------------- the endpoint

def make_client(segments, router=None, rate_limits=None):
    app = create_app(
        store=SessionStore(segments), router=router or FakeRouter(), rate_limits=rate_limits
    )
    app.config.update(TESTING=True)
    return app.test_client()


def body(**extra):
    start_lat, start_lon = far_start()
    payload = {"from": {"lat": start_lat, "lon": start_lon}, "to": {"lat": LAT, "lon": LON}}
    payload.update(extra)
    return payload


def test_route_returns_stops_and_a_path():
    client = make_client(run(150, 12) + run(-600, 8, prefix="q"))
    response = client.post("/api/route", json=body())
    assert response.status_code == 200
    data = response.get_json()
    assert data["experimental"] is True
    assert data["stops"] and all(0 < s["chance"] < 1 for s in data["stops"])
    assert [s["rank"] for s in data["stops"]] == list(range(1, len(data["stops"]) + 1))
    assert "spots" not in data["stops"][0]
    assert len(data["route"]["path"]) >= 2
    assert data["route"]["path"][-1] == [round(LAT, 5), round(LON, 5)]
    assert 0 < data["chance_of_parking"] <= 1
    assert data["start_snap"] is None
    assert response.headers["Cache-Control"] == "no-store"


def test_the_response_says_where_the_road_starts_when_the_driver_is_off_it():
    start = far_start()
    router = FakeRouter(snap=lambda p: 150.0 if p == start else 0.0)
    data = make_client(run(150, 12), router=router).post("/api/route", json=body()).get_json()

    assert data["start_snap"]["name"] == "Some Street"
    assert 120 < data["start_snap"]["distance_m"] < 180


def test_spots_already_driven_past_are_left_out():
    segments = run(150, 12)
    client = make_client(segments)
    everything = client.post("/api/route", json=body()).get_json()
    assert everything["stops"]
    gone = client.post("/api/route", json=body(exclude=[s.id for s in segments]))
    assert gone.status_code == 404
    assert gone.get_json()["code"] == "no_parking_nearby"


def test_nothing_near_the_destination_is_a_404():
    far_away = [ParkingSegment("x", 47.40, 8.60, ZoneType.BLUE, "x", 3, 60)]
    assert make_client(far_away).post("/api/route", json=body()).status_code == 404


@pytest.mark.parametrize("error,status", [
    (RoutingError("INTERNAL-down"), 502),
    (RoutingError("INTERNAL-slow", ROUTING_BUSY), 503),
    (RoutingError("INTERNAL-no-road", NO_ROAD_NEAR_START), 422),
    (RoutingError("INTERNAL-no-road", NO_ROAD_NEAR_DESTINATION), 422),
    (RoutingError("INTERNAL-islands", NOT_CONNECTED), 422),
])
def test_a_routing_failure_has_a_status_a_code_and_a_fixed_message(error, status):
    client = make_client(run(150, 12), router=FakeRouter(fail=error))
    response = client.post("/api/route", json=body())

    assert response.status_code == status
    data = response.get_json()
    assert data["code"] == error.code
    assert data["error"] == error.public_message
    # What went wrong inside never reaches the caller.
    assert str(error) not in response.get_data(as_text=True)


def test_a_start_far_from_any_road_is_a_422_with_its_code():
    start = far_start()
    router = FakeRouter(snap=lambda p: 700.0 if p == start else 0.0)
    response = make_client(run(150, 12), router=router).post("/api/route", json=body())
    assert response.status_code == 422
    assert response.get_json()["code"] == NO_ROAD_NEAR_START


@pytest.mark.parametrize("payload", [
    {}, {"from": {"lat": 1}, "to": {"lat": LAT, "lon": LON}},
    {"from": "x", "to": {"lat": LAT, "lon": LON}},
    {"from": {"lat": "a", "lon": 2}, "to": {"lat": LAT, "lon": LON}},
])
def test_bad_coordinates_are_a_400(payload):
    assert make_client(run(150, 5)).post("/api/route", json=payload).status_code == 400


@pytest.mark.parametrize("extra", [
    {"zone": "green"}, {"stay_minutes": 0}, {"stay_minutes": "90"}, {"stay_minutes": True},
    {"exclude": "a"}, {"exclude": [1]}, {"exclude": ["x"] * 501},
])
def test_bad_options_are_a_400(extra):
    assert make_client(run(150, 5)).post("/api/route", json=body(**extra)).status_code == 400


def test_a_start_far_away_is_a_400_so_it_cannot_be_used_to_route_anywhere():
    payload = body()
    payload["from"] = {"lat": 52.52, "lon": 13.405}
    assert make_client(run(150, 5)).post("/api/route", json=payload).status_code == 400


def test_the_route_endpoint_is_rate_limited():
    client = make_client(run(150, 5), rate_limits=RateLimits(route="2 per minute"))
    codes = [client.post("/api/route", json=body()).status_code for _ in range(4)]
    assert codes[:2] == [200, 200] and 429 in codes[2:]


# ------------------------------------------------------------------- the OSRM client

class FakeHttp:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def get(self, url, params=None, timeout=None, headers=None):
        self.requests.append((url, params, headers))
        item = self.responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class Reply:
    def __init__(self, status=200, payload=None, bad_json=False):
        self.status_code = status
        self._payload = payload
        self._bad_json = bad_json

    def json(self):
        if self._bad_json:
            raise ValueError("no json")
        return self._payload


def osrm(responses, **kw):
    sleeps = []
    clock = iter(range(0, 1000, 1))
    router = OsrmRouter(
        "https://example.test/car", http=FakeHttp(responses), sleep=sleeps.append,
        clock=lambda: next(clock) * 0.1, **kw,
    )
    return router, sleeps


def test_osrm_table_sends_lon_lat_and_reads_durations_and_snap_distances():
    router, _ = osrm([Reply(payload={
        "code": "Ok", "durations": [[0, 5], [6, 0]],
        "sources": [{"distance": 3.2}, {"distance": 710.0}],
    })])
    table = router.table([(47.0, 8.0), (47.1, 8.1)])

    assert table.durations == [[0, 5], [6, 0]]
    assert table.snap_m == [3.2, 710.0]
    url, params, headers = router.http.requests[0]
    assert url == "https://example.test/car/table/v1/driving/8.000000,47.000000;8.100000,47.100000"
    assert params == {"annotations": "duration"}
    assert "ParkingBlues" in headers["User-Agent"]


def test_osrm_route_returns_lat_lon_pairs_and_where_it_starts():
    payload = {"code": "Ok", "routes": [{
        "distance": 1234.0, "duration": 99.0,
        "geometry": {"coordinates": [[8.0, 47.0], [8.1, 47.1]]},
    }], "waypoints": [
        {"location": [8.0005, 47.0004], "distance": 62.5, "name": "Seestrasse"},
        {"location": [8.1, 47.1], "distance": 1.0, "name": ""},
    ]}
    router, _ = osrm([Reply(payload=payload)])
    route = router.route([(47.0, 8.0), (47.1, 8.1)])

    assert route.path == [(47.0, 8.0), (47.1, 8.1)]
    assert (route.distance_m, route.duration_s) == (1234.0, 99.0)
    assert route.start == Snap(47.0004, 8.0005, 62.5, "Seestrasse")
    assert router.http.requests[0][1]["continue_straight"] == "true"


def test_osrm_nearest_lists_the_roads_nearest_first():
    router, _ = osrm([Reply(payload={"code": "Ok", "waypoints": [
        {"location": [8.0, 47.0], "distance": 5.0, "name": "A"},
        {"location": [8.001, 47.0], "distance": 70.0, "name": "B"},
    ]})])
    roads = router.nearest((47.0, 8.0), 2)

    assert [r.name for r in roads] == ["A", "B"]
    assert router.http.requests[0][1] == {"number": 2}
    assert router.http.requests[0][0].endswith("/nearest/v1/driving/8.000000,47.000000")


@pytest.mark.parametrize("reply,code", [
    (Reply(status=429), ROUTING_BUSY),
    (requests.Timeout("slow"), ROUTING_BUSY),
    (Reply(status=500), ROUTING_UNAVAILABLE),
    (Reply(bad_json=True), ROUTING_UNAVAILABLE),
    (Reply(payload={"code": "InvalidQuery"}), ROUTING_UNAVAILABLE),
    (requests.ConnectionError("boom"), ROUTING_UNAVAILABLE),
    (Reply(payload={"code": "NoRoute"}), NOT_CONNECTED),
    (Reply(payload={"code": "NoTable"}), NOT_CONNECTED),
])
def test_osrm_failures_become_coded_routing_errors(reply, code):
    router, _ = osrm([reply])
    with pytest.raises(RoutingError) as caught:
        router.table([(47.0, 8.0), (47.1, 8.1)])
    assert caught.value.code == code


@pytest.mark.parametrize("index,code", [
    (0, NO_ROAD_NEAR_START), (1, NO_ROAD_NEAR_DESTINATION), (4, ROUTING_UNAVAILABLE),
])
def test_osrm_says_which_point_had_no_road(index, code):
    message = f"Could not find a matching segment for coordinate {index}"
    # OSRM answers a point with no road with HTTP 400 and the reason in the body.
    router, _ = osrm([Reply(status=400, payload={"code": "NoSegment", "message": message})])
    with pytest.raises(RoutingError) as caught:
        router.table([(47.0, 8.0), (47.1, 8.1)])
    assert caught.value.code == code


def test_osrm_calls_are_spaced_out_for_the_public_servers():
    ok = Reply(payload={"code": "Ok", "durations": [[0]]})
    router, sleeps = osrm([ok, ok], min_interval_s=1.0)
    router.table([(47.0, 8.0)])
    router.table([(47.0, 8.0)])
    assert sleeps and 0 < sleeps[0] <= 1.0


def test_no_spacing_when_the_server_is_your_own():
    ok = Reply(payload={"code": "Ok", "durations": [[0]]})
    router, sleeps = osrm([ok, ok], min_interval_s=0)
    router.table([(47.0, 8.0)])
    router.table([(47.0, 8.0)])
    assert sleeps == []
