"""The search/rejection state machine described in README section 4.

Kept as plain, storage-agnostic objects (no Flask, no persistence) so it
can be unit tested with synthetic position sequences without a server or
real GPS -- see tests/test_session.py.
"""

import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

import math

from .blue_zone_rules import ZURICH_TZ
from .clustering import build_ranked_clusters
from .duration_filter import segment_supports_duration
from .geo import bearing_deg, haversine_m
from .models import ParkingSegment, ZoneType

DEFAULT_INITIAL_RADIUS_M = 300.0
RADIUS_EXPANSION_STEP_M = 200.0
# A driver can't just give up when suggestions run out -- widen the net
# automatically instead of stopping and waiting for a manual click. Capped
# so a fully-rejected, genuinely sparse area still terminates instead of
# growing forever.
MAX_RADIUS_M = 2000.0

# Real Zurich data is dense enough that a wide radius in the city center can
# match thousands of segments (see README section 9), and clustering is
# O(n^2) -- bound it to the nearest N so a session can never trigger an
# unbounded/slow clustering pass regardless of how dense or wide the search
# gets. The nearest ones are what would get suggested first anyway.
MAX_CANDIDATES_PER_QUERY = 300

# How far the stateless nearby query is allowed to widen its search.
NEARBY_MAX_RADIUS_M = 1000.0

# "Reached" the spot: within this distance of the target.
APPROACH_THRESHOLD_M = 20.0
# "Passed without stopping": moved at least this much farther away *after*
# having gotten within APPROACH_THRESHOLD_M, without a confirm in between.
DEPART_MARGIN_M = 15.0

# _pull_in_local_cluster only retargets when the best newly-found local
# candidate beats the current one by at least this much -- without a
# margin (or any comparison at all), calling it every position-update tick
# surfaces some "new" candidate almost every tick just because the search
# window moved with the driver, regardless of whether it's actually any
# better than what's already targeted, which made the target flicker
# constantly while driving instead of only changing when something
# meaningfully closer shows up.
LOCAL_PULL_IN_MARGIN_M = 15.0

# A movement shorter than this doesn't update the tracked driving heading
# -- GPS/drag jitter over a couple of meters gives a near-random bearing,
# and recomputing it from that noise on every tick would make the
# direction weighting below just as unstable as the flickering it's meant
# to fix. Below this, the previous heading (if any) is kept as-is.
MIN_HEADING_UPDATE_DISTANCE_M = 5.0

# How much a candidate's effective distance (used by _pull_in_local_cluster)
# is discounted when it's straight ahead of the driving heading, or
# inflated when it's straight behind -- e.g. at 0.25, a spot dead ahead
# counts as 25% closer than it actually is, one dead behind as 25%
# farther, tapering to no adjustment for a spot directly to the side. This
# is what lets "the driver is heading toward a better cluster" win a
# reconsideration despite LOCAL_PULL_IN_MARGIN_M, without weakening that
# margin's job of stopping merely-new-but-not-actually-better candidates
# from winning on every tick.
DIRECTION_WEIGHT = 0.25


def _directional_distance(
    from_lat: float, from_lon: float, heading_deg: float | None,
    to_lat: float, to_lon: float, actual_distance_m: float,
) -> float:
    """`actual_distance_m`, adjusted by how well a straight line from
    (from_lat, from_lon) to (to_lat, to_lon) lines up with heading_deg --
    see DIRECTION_WEIGHT. Returns actual_distance_m unchanged if there's no
    known heading yet (too early in the session, or not enough movement
    has happened -- see MIN_HEADING_UPDATE_DISTANCE_M).
    """
    if heading_deg is None:
        return actual_distance_m
    bearing_to_target = bearing_deg(from_lat, from_lon, to_lat, to_lon)
    angle_diff = abs(((bearing_to_target - heading_deg + 180) % 360) - 180)
    alignment = math.cos(math.radians(angle_diff))  # +1 ahead, -1 behind
    return actual_distance_m * (1.0 - DIRECTION_WEIGHT * alignment)


class SessionState(str, Enum):
    SEARCHING = "searching"
    EXHAUSTED = "exhausted"
    PARKED = "parked"


@dataclass
class ParkingSession:
    id: str
    origin_lat: float
    origin_lon: float
    zone_filter: set[ZoneType]
    radius_m: float
    # Ranked clusters (best cluster first, nearest-first within each),
    # never an empty cluster -- see _reject. Kept grouped rather than
    # flattened so retargeting can stay within the current cluster instead
    # of jumping to whatever's literally nearest across the whole map,
    # which could mean a barrier (rail lines, a river) the driver would
    # actually have to go around -- see README section 4.
    candidates: list[list[ParkingSegment]]
    # The driver's last known position -- starts at the destination (before
    # a session gets any live position updates, "where the driver is" and
    # "where they're headed" are the same point) and is updated on every
    # update_position call. Lets the client show distance from the driver,
    # not just from the destination, which is what actually matters once
    # you're moving -- see the segment_json "distance_from_you_m" field.
    last_lat: float
    last_lon: float
    # The driver's current direction of travel, as a compass bearing --
    # None until enough movement has happened to compute one (see
    # MIN_HEADING_UPDATE_DISTANCE_M). Used to prefer candidates the driver
    # is actually heading toward over ones that are merely closer as the
    # crow flies -- see _directional_distance and _pull_in_local_cluster.
    heading_deg: float | None = None
    # None means no preference -- every candidate query re-applies this
    # (see SessionStore._candidates_for), since whether a spot satisfies a
    # given duration is time-of-day dependent for blue zone and can change
    # between requests (see backend/duration_filter.py).
    preferred_duration_minutes: int | None = None
    rejected_ids: set[str] = field(default_factory=set)
    # Kept alongside rejected_ids (which is also used to exclude these from
    # future candidate queries) so the client can still show where the
    # driver already looked and found nothing -- radius expansion rebuilds
    # `candidates` from scratch and would otherwise drop them.
    rejected: list[ParkingSegment] = field(default_factory=list)
    # Closest distance ever recorded to each candidate, keyed by segment id
    # -- tracked per-segment (not just for whichever one is "current") so
    # that in a dense cluster of real-world segments a few meters apart,
    # passing several of them in a row still rejects each one individually
    # instead of only ever tracking whichever one the ranking currently
    # favors (which reshuffles too fast for any single approach/depart
    # cycle to complete against real, tightly-packed data).
    closest_approach_m: dict[str, float] = field(default_factory=dict)
    state: SessionState = SessionState.SEARCHING
    # Last time any request touched this session, on the store's clock; used
    # only to expire abandoned sessions (see SessionStore).
    touched_at: float = 0.0

    @property
    def current(self) -> ParkingSegment | None:
        if self.state != SessionState.SEARCHING or not self.candidates:
            return None
        return self.candidates[0][0]

    @property
    def upcoming(self) -> list[ParkingSegment]:
        """Remaining candidates after the current one, for display."""
        if not self.candidates:
            return []
        rest = list(self.candidates[0][1:])
        for cluster in self.candidates[1:]:
            rest.extend(cluster)
        return rest

    def _reject(self, segment: ParkingSegment) -> None:
        self.rejected_ids.add(segment.id)
        self.rejected.append(segment)
        self.closest_approach_m.pop(segment.id, None)
        self.candidates = [
            filtered
            for cluster in self.candidates
            if (filtered := [s for s in cluster if s.id != segment.id])
        ]
        if not self.candidates:
            self.state = SessionState.EXHAUSTED

    def reject_current(self) -> None:
        current = self.current
        if current is None:
            return
        self._reject(current)

    def confirm_current(self) -> None:
        if self.current is not None:
            self.state = SessionState.PARKED

    def update_position(self, lat: float, lon: float) -> str:
        """Feed a live position update. Returns one of:
        "tracking", "retargeted", "auto_rejected", "exhausted" -- see
        README section 4, steps 4-5, for the reasoning behind the
        approach/depart thresholds. Called continuously while the driver is
        moving (not just once they stop), so both auto-rejection and
        retargeting react smoothly instead of needing a discrete drop.
        """
        previous_lat, previous_lon = self.last_lat, self.last_lon
        self.last_lat, self.last_lon = lat, lon
        moved_m = haversine_m(previous_lat, previous_lon, lat, lon)
        if moved_m >= MIN_HEADING_UPDATE_DISTANCE_M:
            self.heading_deg = bearing_deg(previous_lat, previous_lon, lat, lon)

        if self.current is None:
            return "exhausted"

        previous_current_id = self.current.id

        # Update every remaining candidate's closest-approach independently,
        # then reject any the driver got within APPROACH_THRESHOLD_M of and
        # has now pulled at least DEPART_MARGIN_M farther away from -- not
        # just whichever one happens to be ranked "current" right now.
        to_reject = []
        for cluster in self.candidates:
            for seg in cluster:
                distance_m = haversine_m(lat, lon, seg.lat, seg.lon)
                closest = self.closest_approach_m.get(seg.id)
                if closest is None or distance_m < closest:
                    closest = distance_m
                    self.closest_approach_m[seg.id] = closest
                if closest <= APPROACH_THRESHOLD_M and distance_m >= closest + DEPART_MARGIN_M:
                    to_reject.append(seg)

        for seg in to_reject:
            self._reject(seg)

        if self.state != SessionState.SEARCHING:
            return "exhausted"

        # Re-rank by live distance *within* the current cluster only --
        # cluster order itself was ranked once, from the origin, and stays
        # fixed until the current cluster is fully exhausted. Re-sorting
        # across all remaining clusters here is exactly what would let a
        # cluster on the other side of a barrier outrank one nearby just
        # for being marginally closer as the crow flies.
        self.candidates[0].sort(key=lambda s: haversine_m(lat, lon, s.lat, s.lon))

        if to_reject:
            return "auto_rejected"
        if self.current.id != previous_current_id:
            return "retargeted"
        return "tracking"


# Sessions nobody has touched for this long are dropped. A live drive sends a
# position update every second or so, so only abandoned sessions ever hit it.
# Clients treat the resulting 404 as "start a fresh session" (see the Android
# repository), so expiry is invisible to a returning user.
SESSION_IDLE_TTL_S = 2 * 60 * 60
# Hard ceiling so anonymous traffic can never grow memory without bound; when
# exceeded, the longest-idle sessions go first.
MAX_SESSIONS = 5000


class SessionStore:
    """In-memory session storage. An MVP-appropriate substitute for real
    persistence -- fine for a single-process dev server, not for production.

    Sessions expire after [idle_ttl_s] of inactivity and the total is capped
    at [max_sessions]: with no accounts yet, anyone can create sessions, so
    the store must bound itself.
    """

    def __init__(
        self,
        all_segments: list[ParkingSegment],
        *,
        clock: Callable[[], float] = time.monotonic,
        idle_ttl_s: float = SESSION_IDLE_TTL_S,
        max_sessions: int = MAX_SESSIONS,
    ):
        self._all_segments = all_segments
        self._sessions: dict[str, ParkingSession] = {}
        self._clock = clock
        self._idle_ttl_s = idle_ttl_s
        self._max_sessions = max_sessions

    def __len__(self) -> int:
        return len(self._sessions)

    def _evict(self) -> None:
        now = self._clock()
        expired = [sid for sid, s in self._sessions.items() if now - s.touched_at > self._idle_ttl_s]
        for sid in expired:
            del self._sessions[sid]
        overflow = len(self._sessions) - self._max_sessions + 1  # room for the one being added
        if overflow > 0:
            oldest = sorted(self._sessions.values(), key=lambda s: s.touched_at)[:overflow]
            for s in oldest:
                del self._sessions[s.id]

    def _candidates_for(
        self, zone_filter: set[ZoneType], radius_m: float, origin_lat: float,
        origin_lon: float, exclude_ids: set[str],
        preferred_duration_minutes: int | None, now: datetime,
    ) -> list[list[ParkingSegment]]:
        in_scope = []
        for seg in self._all_segments:
            if seg.id in exclude_ids or not seg.matches(zone_filter):
                continue
            if preferred_duration_minutes is not None and not segment_supports_duration(
                seg, preferred_duration_minutes, now
            ):
                continue
            distance_m = haversine_m(origin_lat, origin_lon, seg.lat, seg.lon)
            if distance_m <= radius_m:
                in_scope.append((distance_m, seg))

        in_scope.sort(key=lambda pair: pair[0])
        nearest = [seg for _, seg in in_scope[:MAX_CANDIDATES_PER_QUERY]]
        return build_ranked_clusters(nearest, origin_lat, origin_lon)

    def create(
        self,
        origin_lat: float,
        origin_lon: float,
        zone_filter: set[ZoneType],
        radius_m: float = DEFAULT_INITIAL_RADIUS_M,
        preferred_duration_minutes: int | None = None,
        now: datetime | None = None,
    ) -> ParkingSession:
        now = now or datetime.now(ZURICH_TZ)
        candidates = self._candidates_for(
            zone_filter, radius_m, origin_lat, origin_lon, exclude_ids=set(),
            preferred_duration_minutes=preferred_duration_minutes, now=now,
        )
        session = ParkingSession(
            id=str(uuid.uuid4()),
            origin_lat=origin_lat,
            origin_lon=origin_lon,
            zone_filter=zone_filter,
            radius_m=radius_m,
            candidates=candidates,
            last_lat=origin_lat,
            last_lon=origin_lon,
            preferred_duration_minutes=preferred_duration_minutes,
            state=SessionState.SEARCHING if candidates else SessionState.EXHAUSTED,
        )
        self._evict()
        session.touched_at = self._clock()
        self._sessions[session.id] = session
        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session, now)
        return session

    def nearby(
        self,
        zone_filter: set[ZoneType],
        lat: float,
        lon: float,
        preferred_duration_minutes: int | None = None,
        now: datetime | None = None,
        radius_m: float = DEFAULT_INITIAL_RADIUS_M,
        max_radius_m: float = NEARBY_MAX_RADIUS_M,
    ) -> tuple[list[list[ParkingSegment]], float]:
        """"What is parked near here?" without keeping a session: the same
        ranked clusters create() starts from, widening the search in steps
        until something turns up or `max_radius_m` is reached. Returns the
        clusters and the radius that produced them. Nothing is stored, so a
        page view costs no session slot.
        """
        now = now or datetime.now(ZURICH_TZ)
        while True:
            clusters = self._candidates_for(
                zone_filter, radius_m, lat, lon, set(), preferred_duration_minutes, now
            )
            if clusters or radius_m >= max_radius_m:
                return clusters, radius_m
            radius_m = min(radius_m + RADIUS_EXPANSION_STEP_M, max_radius_m)

    def get(self, session_id: str) -> ParkingSession | None:
        session = self._sessions.get(session_id)
        if session is None:
            return None
        now = self._clock()
        if now - session.touched_at > self._idle_ttl_s:
            del self._sessions[session_id]
            return None
        session.touched_at = now
        return session

    def expand_radius(self, session: ParkingSession, now: datetime | None = None) -> ParkingSession:
        now = now or datetime.now(ZURICH_TZ)
        session.radius_m += RADIUS_EXPANSION_STEP_M
        session.candidates = self._candidates_for(
            session.zone_filter,
            session.radius_m,
            session.origin_lat,
            session.origin_lon,
            exclude_ids=session.rejected_ids,
            preferred_duration_minutes=session.preferred_duration_minutes,
            now=now,
        )
        session.closest_approach_m = {}
        session.state = (
            SessionState.SEARCHING if session.candidates else SessionState.EXHAUSTED
        )
        return session

    def _auto_expand_while_exhausted(self, session: ParkingSession, now: datetime) -> None:
        """A driver in motion can't just be told "nothing found" and left
        there -- keep widening the radius, up to MAX_RADIUS_M, until there's
        something to suggest again (or the cap is reached, for a genuinely
        sparse/fully-rejected area).
        """
        while session.state == SessionState.EXHAUSTED and session.radius_m < MAX_RADIUS_M:
            self.expand_radius(session, now=now)

    def reject_current(self, session: ParkingSession, now: datetime | None = None) -> str:
        now = now or datetime.now(ZURICH_TZ)
        session.reject_current()
        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session, now)
            if session.state == SessionState.SEARCHING:
                return "expanded"
        return "rejected"

    def confirm_current(self, session: ParkingSession) -> str:
        session.confirm_current()
        return "parked"

    def _pull_in_local_cluster(
        self, session: ParkingSession, lat: float, lon: float, now: datetime
    ) -> bool:
        """Called on every position update (see update_position) so a
        driver who's drifted away from their current target -- or straight
        out of the whole search area -- gets offered whatever's actually
        near them, instead of whatever's next in the fixed, destination-
        ranked order (which could mean sending them clear across the map,
        over a barrier, or back toward a region they've already left).
        Look for anything new near the driver first; only fall back to the
        existing, possibly-distant queue if nothing turns up locally.

        Distances are direction-weighted (_directional_distance): a find
        roughly ahead of the driver's current heading counts as closer than
        it actually is, one roughly behind as farther, so a driver heading
        toward a genuinely better cluster gets offered it sooner than pure
        distance would justify. Still gated by LOCAL_PULL_IN_MARGIN_M on
        top of that weighting -- calling this every tick means it turns up
        some "new" (not-yet-seen) candidate almost constantly just because
        the search window moved with the driver; without a margin, every
        one of those would win by virtue of being new, not by being any
        closer (or better-aligned with where they're headed). Returns True
        if it retargeted.
        """
        known_ids = session.rejected_ids | {
            seg.id for cluster in session.candidates for seg in cluster
        }
        local_clusters = self._candidates_for(
            session.zone_filter, DEFAULT_INITIAL_RADIUS_M, lat, lon, exclude_ids=known_ids,
            preferred_duration_minutes=session.preferred_duration_minutes, now=now,
        )
        if not local_clusters:
            return False

        def effective_distance(seg: ParkingSegment) -> float:
            actual = haversine_m(lat, lon, seg.lat, seg.lon)
            return _directional_distance(lat, lon, session.heading_deg, seg.lat, seg.lon, actual)

        local_clusters.sort(key=lambda cluster: min(effective_distance(s) for s in cluster))
        best_local_effective = min(effective_distance(s) for s in local_clusters[0])

        current_target = session.candidates[0][0]
        current_effective = effective_distance(current_target)
        if best_local_effective >= current_effective - LOCAL_PULL_IN_MARGIN_M:
            return False

        session.candidates = local_clusters + session.candidates
        return True

    def update_position(
        self, session: ParkingSession, lat: float, lon: float, now: datetime | None = None
    ) -> str:
        now = now or datetime.now(ZURICH_TZ)
        event = session.update_position(lat, lon)

        if session.state == SessionState.SEARCHING:
            if self._pull_in_local_cluster(session, lat, lon, now):
                event = "retargeted"

        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session, now)
            if session.state == SessionState.SEARCHING:
                return "expanded"
        return event
