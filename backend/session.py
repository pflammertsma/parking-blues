"""The search/rejection state machine described in README section 4.

Kept as plain, storage-agnostic objects (no Flask, no persistence) so it
can be unit tested with synthetic position sequences without a server or
real GPS -- see tests/test_session.py.
"""

import uuid
from dataclasses import dataclass, field
from enum import Enum

from .clustering import build_candidate_order
from .geo import haversine_m
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

# "Reached" the spot: within this distance of the target.
APPROACH_THRESHOLD_M = 20.0
# "Passed without stopping": moved at least this much farther away *after*
# having gotten within APPROACH_THRESHOLD_M, without a confirm in between.
DEPART_MARGIN_M = 15.0


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
    candidates: list[ParkingSegment]
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

    @property
    def current(self) -> ParkingSegment | None:
        if self.state != SessionState.SEARCHING or not self.candidates:
            return None
        return self.candidates[0]

    @property
    def upcoming(self) -> list[ParkingSegment]:
        """Remaining candidates after the current one, for display."""
        return self.candidates[1:]

    def _reject(self, segment: ParkingSegment) -> None:
        self.rejected_ids.add(segment.id)
        self.rejected.append(segment)
        self.closest_approach_m.pop(segment.id, None)
        self.candidates.remove(segment)
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
        if self.current is None:
            return "exhausted"

        previous_current_id = self.candidates[0].id

        # Update every remaining candidate's closest-approach independently,
        # then reject any the driver got within APPROACH_THRESHOLD_M of and
        # has now pulled at least DEPART_MARGIN_M farther away from -- not
        # just whichever one happens to be ranked "current" right now.
        to_reject = []
        for seg in self.candidates:
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

        self.candidates.sort(key=lambda s: haversine_m(lat, lon, s.lat, s.lon))

        if to_reject:
            return "auto_rejected"
        if self.candidates[0].id != previous_current_id:
            return "retargeted"
        return "tracking"


class SessionStore:
    """In-memory session storage. An MVP-appropriate substitute for real
    persistence -- fine for a single-process dev server, not for production.
    """

    def __init__(self, all_segments: list[ParkingSegment]):
        self._all_segments = all_segments
        self._sessions: dict[str, ParkingSession] = {}

    def _candidates_for(
        self, zone_filter: set[ZoneType], radius_m: float, origin_lat: float,
        origin_lon: float, exclude_ids: set[str],
    ) -> list[ParkingSegment]:
        in_scope = []
        for seg in self._all_segments:
            if seg.id in exclude_ids or not seg.matches(zone_filter):
                continue
            distance_m = haversine_m(origin_lat, origin_lon, seg.lat, seg.lon)
            if distance_m <= radius_m:
                in_scope.append((distance_m, seg))

        in_scope.sort(key=lambda pair: pair[0])
        nearest = [seg for _, seg in in_scope[:MAX_CANDIDATES_PER_QUERY]]
        return build_candidate_order(nearest, origin_lat, origin_lon)

    def create(
        self,
        origin_lat: float,
        origin_lon: float,
        zone_filter: set[ZoneType],
        radius_m: float = DEFAULT_INITIAL_RADIUS_M,
    ) -> ParkingSession:
        candidates = self._candidates_for(
            zone_filter, radius_m, origin_lat, origin_lon, exclude_ids=set()
        )
        session = ParkingSession(
            id=str(uuid.uuid4()),
            origin_lat=origin_lat,
            origin_lon=origin_lon,
            zone_filter=zone_filter,
            radius_m=radius_m,
            candidates=candidates,
            state=SessionState.SEARCHING if candidates else SessionState.EXHAUSTED,
        )
        self._sessions[session.id] = session
        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session)
        return session

    def get(self, session_id: str) -> ParkingSession | None:
        return self._sessions.get(session_id)

    def expand_radius(self, session: ParkingSession) -> ParkingSession:
        session.radius_m += RADIUS_EXPANSION_STEP_M
        session.candidates = self._candidates_for(
            session.zone_filter,
            session.radius_m,
            session.origin_lat,
            session.origin_lon,
            exclude_ids=session.rejected_ids,
        )
        session.closest_approach_m = {}
        session.state = (
            SessionState.SEARCHING if session.candidates else SessionState.EXHAUSTED
        )
        return session

    def _auto_expand_while_exhausted(self, session: ParkingSession) -> None:
        """A driver in motion can't just be told "nothing found" and left
        there -- keep widening the radius, up to MAX_RADIUS_M, until there's
        something to suggest again (or the cap is reached, for a genuinely
        sparse/fully-rejected area).
        """
        while session.state == SessionState.EXHAUSTED and session.radius_m < MAX_RADIUS_M:
            self.expand_radius(session)

    def reject_current(self, session: ParkingSession) -> str:
        session.reject_current()
        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session)
            if session.state == SessionState.SEARCHING:
                return "expanded"
        return "rejected"

    def confirm_current(self, session: ParkingSession) -> str:
        session.confirm_current()
        return "parked"

    def update_position(self, session: ParkingSession, lat: float, lon: float) -> str:
        event = session.update_position(lat, lon)
        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session)
            if session.state == SessionState.SEARCHING:
                return "expanded"
        return event
