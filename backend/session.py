"""The search/rejection state machine described in README section 4.

Kept as plain, storage-agnostic objects (no Flask, no persistence) so it
can be unit tested with synthetic position sequences without a server or
real GPS -- see tests/test_session.py.
"""

import uuid
from dataclasses import dataclass, field
from enum import Enum

from .clustering import build_ranked_clusters
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

# If the driver ends up farther from their current target than this, they've
# clearly left the area it was chosen for -- e.g. drove straight past the
# whole search region rather than working through it -- so it's worth
# checking what's actually near them now rather than continuing to point
# back at a target this far away, even if that target's cluster still has
# other untried members. Same distance as a fresh local search, since
# that's what re-anchors on the driver -- see _pull_in_local_cluster.
LOCAL_SEARCH_TRIGGER_M = DEFAULT_INITIAL_RADIUS_M

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
        self.last_lat, self.last_lon = lat, lon

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
    ) -> list[list[ParkingSegment]]:
        in_scope = []
        for seg in self._all_segments:
            if seg.id in exclude_ids or not seg.matches(zone_filter):
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
            last_lat=origin_lat,
            last_lon=origin_lon,
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

    def _pull_in_local_cluster(self, session: ParkingSession, lat: float, lon: float) -> bool:
        """Called when the driver's current cluster just ran out, or when
        they've simply drifted far from their current target (e.g. drove
        straight out of the whole search area). Either way, the fallback
        would otherwise be whatever's next in the fixed, destination-ranked
        order -- which could mean sending them clear across the map (or a
        barrier), or back toward a region they've already left, rather than
        widening the search right around where they actually are. Look for
        anything new near the driver first; only fall back to the existing,
        possibly-distant queue if nothing turns up locally. Returns True if
        a local cluster was found and prepended.
        """
        known_ids = session.rejected_ids | {
            seg.id for cluster in session.candidates for seg in cluster
        }
        local_clusters = self._candidates_for(
            session.zone_filter, DEFAULT_INITIAL_RADIUS_M, lat, lon, exclude_ids=known_ids
        )
        if not local_clusters:
            return False
        local_clusters.sort(
            key=lambda cluster: min(haversine_m(lat, lon, s.lat, s.lon) for s in cluster)
        )
        session.candidates = local_clusters + session.candidates
        session.closest_approach_m = {}
        return True

    def update_position(self, session: ParkingSession, lat: float, lon: float) -> str:
        cluster_before = (
            {s.id for s in session.candidates[0]} if session.candidates else set()
        )

        event = session.update_position(lat, lon)

        if session.state == SessionState.SEARCHING:
            cluster_after = {s.id for s in session.candidates[0]}
            moved_to_new_cluster = cluster_before and cluster_before.isdisjoint(cluster_after)
            drifted_far_from_target = (
                haversine_m(lat, lon, session.current.lat, session.current.lon)
                > LOCAL_SEARCH_TRIGGER_M
            )
            if (
                (moved_to_new_cluster or drifted_far_from_target)
                and self._pull_in_local_cluster(session, lat, lon)
            ):
                event = "retargeted"

        if session.state == SessionState.EXHAUSTED:
            self._auto_expand_while_exhausted(session)
            if session.state == SessionState.SEARCHING:
                return "expanded"
        return event
