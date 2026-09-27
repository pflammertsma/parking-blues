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
    index: int = 0
    rejected_ids: set[str] = field(default_factory=set)
    closest_approach_m: float | None = None
    state: SessionState = SessionState.SEARCHING

    @property
    def current(self) -> ParkingSegment | None:
        if self.state != SessionState.SEARCHING or self.index >= len(self.candidates):
            return None
        return self.candidates[self.index]

    @property
    def upcoming(self) -> list[ParkingSegment]:
        """Remaining candidates after the current one, for display."""
        return self.candidates[self.index + 1 :]

    def _advance(self) -> None:
        self.index += 1
        self.closest_approach_m = None
        if self.index >= len(self.candidates):
            self.state = SessionState.EXHAUSTED

    def reject_current(self) -> None:
        current = self.current
        if current is None:
            return
        self.rejected_ids.add(current.id)
        self._advance()

    def confirm_current(self) -> None:
        if self.current is not None:
            self.state = SessionState.PARKED

    def update_position(self, lat: float, lon: float) -> str:
        """Feed a live position update. Returns one of:
        "tracking", "auto_rejected", "exhausted" -- see README section 4,
        steps 4-5, for the reasoning behind the approach/depart thresholds.
        """
        current = self.current
        if current is None:
            return "exhausted"

        distance_m = haversine_m(lat, lon, current.lat, current.lon)
        if self.closest_approach_m is None or distance_m < self.closest_approach_m:
            self.closest_approach_m = distance_m

        got_close_enough = self.closest_approach_m <= APPROACH_THRESHOLD_M
        moved_away_again = distance_m >= self.closest_approach_m + DEPART_MARGIN_M
        if got_close_enough and moved_away_again:
            self.rejected_ids.add(current.id)
            self._advance()
            return "exhausted" if self.state == SessionState.EXHAUSTED else "auto_rejected"

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
        in_scope = [
            seg
            for seg in self._all_segments
            if seg.matches(zone_filter)
            and seg.id not in exclude_ids
            and haversine_m(origin_lat, origin_lon, seg.lat, seg.lon) <= radius_m
        ]
        return build_candidate_order(in_scope, origin_lat, origin_lon)

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
        session.index = 0
        session.closest_approach_m = None
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
