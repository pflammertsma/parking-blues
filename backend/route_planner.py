"""Plans a drive that passes the parking areas most likely to yield a space near a destination.

EXPERIMENT. Road rules (one-way streets, banned turns, bus- and tram-only roads) come from a
public OpenStreetMap routing server, which is for light use and has no uptime promise. If the
experiment succeeds, move to a self-hosted OSRM (only ``ROUTING_BASE_URL`` changes); if the
routes turn out to be inaccurate, replace ``OsrmRouter`` with a Google Routes implementation of
the same ``Router`` interface. Nothing else in this module knows which engine answers.

What it decides
---------------
The city publishes where parking is *allowed*, not which spaces are free, so a stop is a
gamble. Each candidate area gets a chance of having a free space, assuming every space is free
with probability ``P_FREE`` independently (a guess, not data: tune it from real use). A plan is
an ordered list of stops; the driver parks at the first stop with a space. Its cost is the
expected time until parked, drive plus the walk to the destination, with a penalty if every
stop is full. Stops are added greedily wherever they lower that cost by more than
``MIN_GAIN_S``, which is what makes the plan

1. favour dense areas with many spaces, since their chance of a free space is high,
2. drop a small area whose detour costs more than its chance is worth, and
3. follow the road rules, because every drive time comes from the router, which is directional
   (a one-way street is cheap one way and a long loop the other).

Points that are not on a road
-----------------------------
A router snaps every point to the nearest road, with no distance limit: a point in the middle
of the lake gets a route that silently starts 700 m away. So the snap distance (the router
reports it for every point) is checked here:

* the **start** may be up to ``START_MAX_SNAP_M`` from a road; if it is more than
  ``SNAP_NOTE_M`` away the plan says where the road starts, so the app can draw the gap, and
  beyond the maximum there is no route ("you are not near a road");
* the **destination** may be off a road (a park, a pedestrian zone): the drive just ends at the
  nearest road, up to ``DEST_MAX_SNAP_M``, and the walk covers the rest;
* a **parking area** more than ``STOP_MAX_SNAP_M`` from a road is not offered as a stop (the
  centre of a kerb run should be on its street; if it is not, it cannot be driven to);
* if the start or destination snaps to a piece of road network that is not connected to the
  other (a private road, a closed area), the next-nearest roads are tried before giving up.

Limits: lane-level bus lanes inside an otherwise open street are not modelled by OSM routing,
nor are time-dependent restrictions, and a stop is the area's centre point, snapped to the
nearest road, which on a street with a parallel one-way can be the wrong side.
"""

from __future__ import annotations

import os
import re
import threading
import time
from dataclasses import dataclass
from typing import Protocol

import requests

from .geo import haversine_m
from .models import ParkingSegment, ZoneType

# Assumed chance that any one space is free when the driver arrives. No occupancy data exists
# for street parking in Zurich, so this is a guess that sets how much density is worth.
P_FREE = 0.10

WALK_SPEED_MPS = 1.3            # about 4.7 km/h
WALK_DETOUR_FACTOR = 1.3        # streets are longer than the straight line
FALLBACK_PENALTY_S = 600.0      # circling or a garage if every stop is full
MAX_CANDIDATES = 12             # areas considered (bounds the router's matrix call)
MAX_STOPS = 5
MIN_GAIN_S = 25.0               # a stop must save this much expected time to be worth adding
MAX_TRIP_M = 25_000.0           # start to destination, straight line
UNREACHABLE_S = 1.0e6

START_MAX_SNAP_M = 400.0        # farther from any road than this: no route
SNAP_NOTE_M = 40.0              # a start this far from its road gets the gap shown
DEST_MAX_SNAP_M = 800.0
STOP_MAX_SNAP_M = 40.0
ALTERNATIVE_ROADS = 2           # other roads tried when a point sits on a cut-off network

# Why a route could not be made. The code is what the app reacts to; the message for the user is
# fixed here so that nothing from the routing service (or an exception) is ever shown.
ROUTING_UNAVAILABLE = "routing_unavailable"
ROUTING_BUSY = "routing_busy"
NO_ROAD_NEAR_START = "no_road_near_start"
NO_ROAD_NEAR_DESTINATION = "no_road_near_destination"
NOT_CONNECTED = "not_connected"

PUBLIC_MESSAGES = {
    ROUTING_UNAVAILABLE: "Routing is unavailable right now.",
    ROUTING_BUSY: "Routing is busy right now. Try again in a moment.",
    NO_ROAD_NEAR_START: "You don't seem to be near a road.",
    NO_ROAD_NEAR_DESTINATION: "There is no road near that destination.",
    NOT_CONNECTED: "There is no drivable route from here to there.",
}


class RoutingError(Exception):
    """The routing service failed or could not route between the points.

    ``code`` is one of the constants above; ``str(error)`` is for the log, not for the user.
    """

    def __init__(self, message: str, code: str = ROUTING_UNAVAILABLE) -> None:
        super().__init__(message)
        self.code = code

    @property
    def public_message(self) -> str:
        return PUBLIC_MESSAGES.get(self.code, PUBLIC_MESSAGES[ROUTING_UNAVAILABLE])


@dataclass(frozen=True)
class Snap:
    """Where the router put a point: on a road, this far away."""

    lat: float
    lon: float
    distance_m: float
    name: str = ""


@dataclass(frozen=True)
class Table:
    durations: list[list[float | None]]   # seconds between every ordered pair; None = unreachable
    snap_m: list[float]                   # how far each input point is from its road


@dataclass(frozen=True)
class Route:
    path: list[tuple[float, float]]   # (lat, lon)
    distance_m: float
    duration_s: float
    start: Snap | None = None         # where the route really starts (on a road)


class Router(Protocol):
    def table(self, points: list[tuple[float, float]]) -> Table:
        """Driving seconds between every ordered pair of (lat, lon) points, and the snap distances."""

    def route(self, points: list[tuple[float, float]]) -> Route:
        """The drive through the points in order."""

    def nearest(self, point: tuple[float, float], count: int) -> list[Snap]:
        """The roads nearest to a point, nearest first."""


@dataclass(frozen=True)
class Candidate:
    spots: list[ParkingSegment]
    zone_type: ZoneType
    lat: float
    lon: float
    capacity: int
    chance: float
    walk_s: float


@dataclass(frozen=True)
class Stop:
    candidate: Candidate
    eta_s: float


@dataclass(frozen=True)
class Plan:
    stops: list[Stop]
    route: Route
    chance_of_parking: float       # of finding a space at one of the stops
    expected_minutes: float        # until parked, drive plus walk, fallback included
    start_gap: Snap | None = None  # set when the route starts on a road away from the driver


def chance_of_space(capacity: int) -> float:
    return 1.0 - (1.0 - P_FREE) ** max(1, capacity)


def build_candidates(
    areas: list[list[ParkingSegment]], dest: tuple[float, float]
) -> list[Candidate]:
    """One candidate per same-zone run of spots, best prospects first, at most MAX_CANDIDATES."""
    candidates = []
    for spots in areas:
        if not spots:
            continue
        lat = sum(s.lat for s in spots) / len(spots)
        lon = sum(s.lon for s in spots) / len(spots)
        capacity = sum(max(1, s.estimated_capacity) for s in spots)
        walk_m = haversine_m(lat, lon, dest[0], dest[1]) * WALK_DETOUR_FACTOR
        candidates.append(Candidate(
            spots=spots, zone_type=spots[0].zone_type, lat=lat, lon=lon, capacity=capacity,
            chance=chance_of_space(capacity), walk_s=walk_m / WALK_SPEED_MPS,
        ))
    candidates.sort(key=lambda c: c.chance / (1.0 + c.walk_s / 300.0), reverse=True)
    return candidates[:MAX_CANDIDATES]


def expected_cost(order: list[int], cands: list[Candidate], t: list[list[float]]) -> float:
    """Expected seconds until parked. Matrix index 0 = start, 1 = destination, 2+k = cands[k]."""
    elapsed = 0.0
    prev = 0
    p_reach = 1.0
    total = 0.0
    for k in order:
        elapsed += t[prev][2 + k]
        prev = 2 + k
        c = cands[k]
        total += p_reach * c.chance * (elapsed + c.walk_s)
        p_reach *= 1.0 - c.chance
    elapsed += t[prev][1]
    return total + p_reach * (elapsed + FALLBACK_PENALTY_S)


def choose_stops(cands: list[Candidate], t: list[list[float]]) -> list[int]:
    """Greedy insertion: keep adding the stop (at its best position) that lowers the expected
    cost the most, until none lowers it by MIN_GAIN_S."""
    order: list[int] = []
    best_cost = expected_cost(order, cands, t)
    while len(order) < MAX_STOPS:
        move = None
        gain = MIN_GAIN_S
        for k in range(len(cands)):
            if k in order:
                continue
            for pos in range(len(order) + 1):
                trial = order[:pos] + [k] + order[pos:]
                cost = expected_cost(trial, cands, t)
                if best_cost - cost > gain:
                    gain, move = best_cost - cost, trial
        if move is None:
            break
        order = move
        best_cost -= gain
    return order


def _matrix(table: Table) -> list[list[float]]:
    return [[UNREACHABLE_S if v is None else float(v) for v in row] for row in table.durations]


def _table_for(
    router: Router, start: tuple[float, float], dest: tuple[float, float], cands: list[Candidate]
) -> tuple[Table, list[list[float]]]:
    points = [start, dest] + [(c.lat, c.lon) for c in cands]
    table = router.table(points)
    return table, _matrix(table)


def _connected_table(
    router: Router, start: tuple[float, float], dest: tuple[float, float], cands: list[Candidate]
) -> tuple[tuple[float, float], tuple[float, float], Table, list[list[float]], float, float]:
    """The matrix for a start and destination that can reach each other, trying the next-nearest
    roads of the start and then of the destination if the nearest ones are cut off.

    Returns the start and destination actually used (an alternative road's point where one was
    needed), the table, and how far the *driver's* start and the real destination are from the
    roads used (for an alternative road, the distance to it from where they really are).
    """
    table, t = _table_for(router, start, dest, cands)
    if t[0][1] < UNREACHABLE_S:
        return start, dest, table, t, table.snap_m[0], table.snap_m[1]
    for which in ("start", "destination"):
        original = start if which == "start" else dest
        for alternative in router.nearest(original, ALTERNATIVE_ROADS + 1)[1:]:
            point = (alternative.lat, alternative.lon)
            used_start, used_dest = (point, dest) if which == "start" else (start, point)
            table, t = _table_for(router, used_start, used_dest, cands)
            if t[0][1] < UNREACHABLE_S:
                start_m = alternative.distance_m if which == "start" else table.snap_m[0]
                dest_m = alternative.distance_m if which == "destination" else table.snap_m[1]
                return used_start, used_dest, table, t, start_m, dest_m
    raise RoutingError("start and destination are on disconnected roads", NOT_CONNECTED)


def plan_route(
    router: Router,
    start: tuple[float, float],
    dest: tuple[float, float],
    areas: list[list[ParkingSegment]],
) -> Plan | None:
    """The plan, or None if there is no area to plan around. Raises RoutingError if the router fails."""
    cands = build_candidates(areas, dest)
    if not cands:
        return None

    used_start, used_dest, table, t, start_snap_m, dest_snap_m = _connected_table(router, start, dest, cands)

    # A point far from any road: say so rather than route from somewhere it is not.
    if start_snap_m > START_MAX_SNAP_M:
        raise RoutingError(f"start is {start_snap_m:.0f} m from a road", NO_ROAD_NEAR_START)
    if dest_snap_m > DEST_MAX_SNAP_M:
        raise RoutingError(f"destination is {dest_snap_m:.0f} m from a road", NO_ROAD_NEAR_DESTINATION)

    # A parking area that is not on a road cannot be driven to: leave it out.
    keep = [k for k in range(len(cands)) if table.snap_m[2 + k] <= STOP_MAX_SNAP_M]
    if not keep:
        return None
    cands = [cands[k] for k in keep]
    indices = [0, 1] + [2 + k for k in keep]
    t = [[t[i][j] for j in indices] for i in indices]

    order = choose_stops(cands, t)
    waypoints = [used_start] + [(cands[k].lat, cands[k].lon) for k in order] + [used_dest]
    route = router.route(waypoints)

    stops = []
    elapsed = 0.0
    prev = 0
    p_none = 1.0
    for k in order:
        elapsed += t[prev][2 + k]
        prev = 2 + k
        stops.append(Stop(candidate=cands[k], eta_s=elapsed))
        p_none *= 1.0 - cands[k].chance

    # Where the road starts, if that is away from the driver (off a road, or on the next road
    # because the nearest was cut off): the app draws the gap.
    start_gap = None
    if route.start is not None:
        gap_m = haversine_m(start[0], start[1], route.start.lat, route.start.lon)
        if gap_m > SNAP_NOTE_M:
            start_gap = Snap(route.start.lat, route.start.lon, gap_m, route.start.name)

    return Plan(
        stops=stops,
        route=route,
        chance_of_parking=1.0 - p_none,
        expected_minutes=expected_cost(order, cands, t) / 60.0,
        start_gap=start_gap,
    )


# ------------------------------------------------------------------ the public OSM router

DEFAULT_ROUTING_BASE_URL = "https://router.project-osrm.org"  # the OSRM demo: car profile, ~1 req/s, no uptime promise
USER_AGENT = "ParkingBlues/0.1 (+https://lammertsma.dev/projects/parking-blues)"


def _snap(waypoint: dict) -> Snap:
    lon, lat = waypoint["location"]
    return Snap(lat=lat, lon=lon, distance_m=float(waypoint.get("distance", 0.0)), name=waypoint.get("name") or "")


class OsrmRouter:
    """OSRM's HTTP API: the public OpenStreetMap server now, a self-hosted one later.

    The public servers ask for about one request a second at most and no heavy use, so calls
    are spaced ``min_interval_s`` apart process-wide; set ROUTING_MIN_INTERVAL_S=0 once the
    server is your own.
    """

    def __init__(
        self,
        base_url: str = DEFAULT_ROUTING_BASE_URL,
        min_interval_s: float = 1.0,
        timeout_s: float = 8.0,
        http=None,
        clock=time.monotonic,
        sleep=time.sleep,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.min_interval_s = min_interval_s
        self.timeout_s = timeout_s
        self.http = http or requests.Session()
        self._clock = clock
        self._sleep = sleep
        self._lock = threading.Lock()
        self._last_call = None

    @classmethod
    def from_env(cls) -> "OsrmRouter":
        return cls(
            base_url=os.environ.get("ROUTING_BASE_URL") or DEFAULT_ROUTING_BASE_URL,
            min_interval_s=float(os.environ.get("ROUTING_MIN_INTERVAL_S", "1.0")),
        )

    @staticmethod
    def _coords(points: list[tuple[float, float]]) -> str:
        return ";".join(f"{lon:.6f},{lat:.6f}" for lat, lon in points)

    def _get(self, path: str, params: dict) -> dict:
        with self._lock:  # one upstream call at a time, spaced out
            if self._last_call is not None:
                wait = self.min_interval_s - (self._clock() - self._last_call)
                if wait > 0:
                    self._sleep(wait)
            try:
                response = self.http.get(
                    f"{self.base_url}{path}", params=params, timeout=self.timeout_s,
                    headers={"User-Agent": USER_AGENT},
                )
            except requests.Timeout as exc:
                raise RoutingError(f"routing service timed out: {exc}", ROUTING_BUSY) from exc
            except requests.RequestException as exc:
                raise RoutingError(f"routing service unreachable: {exc}") from exc
            finally:
                self._last_call = self._clock()
        if response.status_code == 429:
            raise RoutingError("routing service answered 429", ROUTING_BUSY)
        if response.status_code != 200:
            # OSRM answers 400 with {"code": "NoSegment" | "NoRoute" | ...}; anything else is a failure.
            body = self._json(response, tolerate=True)
            if body is not None and body.get("code") in ("NoSegment", "NoRoute", "NoTable"):
                return body
            raise RoutingError(f"routing service answered {response.status_code}")
        return self._json(response)

    @staticmethod
    def _json(response, tolerate: bool = False):
        try:
            return response.json()
        except ValueError as exc:
            if tolerate:
                return None
            raise RoutingError("routing service sent something that is not JSON") from exc

    @staticmethod
    def _checked(body: dict) -> dict:
        code = body.get("code")
        if code == "Ok":
            return body
        if code == "NoSegment":
            # "Could not find a matching segment for coordinate N": N says which point.
            match = re.search(r"coordinate (\d+)", str(body.get("message", "")))
            index = int(match.group(1)) if match else None
            error_code = NO_ROAD_NEAR_START if index in (None, 0) else (
                NO_ROAD_NEAR_DESTINATION if index == 1 else ROUTING_UNAVAILABLE
            )
            raise RoutingError(f"no road found for coordinate {index}", error_code)
        if code in ("NoRoute", "NoTable"):
            raise RoutingError(f"routing service: {code}", NOT_CONNECTED)
        raise RoutingError(f"routing service: {code}")

    def table(self, points: list[tuple[float, float]]) -> Table:
        body = self._checked(self._get(f"/table/v1/driving/{self._coords(points)}", {"annotations": "duration"}))
        # sources[i] describes input point i, including how far it is from the road it snapped to.
        snaps = [float(waypoint.get("distance", 0.0)) for waypoint in body.get("sources", [])]
        snaps += [0.0] * (len(points) - len(snaps))
        return Table(durations=body["durations"], snap_m=snaps)

    def route(self, points: list[tuple[float, float]]) -> Route:
        body = self._checked(self._get(
            f"/route/v1/driving/{self._coords(points)}",
            # continue_straight: no U-turn at a stop, which would be the way to reverse into
            # a one-way street the wrong way round.
            {"overview": "full", "geometries": "geojson", "steps": "false", "continue_straight": "true"},
        ))
        route = body["routes"][0]
        waypoints = body.get("waypoints") or []
        return Route(
            path=[(lat, lon) for lon, lat in route["geometry"]["coordinates"]],
            distance_m=float(route["distance"]),
            duration_s=float(route["duration"]),
            start=_snap(waypoints[0]) if waypoints else None,
        )

    def nearest(self, point: tuple[float, float], count: int) -> list[Snap]:
        body = self._checked(self._get(f"/nearest/v1/driving/{self._coords([point])}", {"number": count}))
        return [_snap(waypoint) for waypoint in body.get("waypoints", [])]
