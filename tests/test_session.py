from backend.geo import offset_point
from backend.models import ParkingSegment, ZoneType
from backend.session import (
    APPROACH_THRESHOLD_M,
    DEPART_MARGIN_M,
    MAX_CANDIDATES_PER_QUERY,
    MAX_RADIUS_M,
    SessionState,
    SessionStore,
)

ORIGIN_LAT, ORIGIN_LON = 47.3703, 8.5386


def make_segment(id_, north_m, east_m=0, zone_type=ZoneType.BLUE):
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, east_m)
    return ParkingSegment(
        id=id_, lat=lat, lon=lon, zone_type=zone_type,
        address_label=id_, estimated_capacity=1,
    )


def drive_position(north_m):
    return offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, 0)


def candidate_ids(session):
    """Flatten session.candidates (ranked clusters of segments) into ids,
    in visiting order, for tests that don't care about cluster grouping."""
    return [s.id for cluster in session.candidates for s in cluster]


def test_create_session_orders_nearest_candidate_first():
    segments = [make_segment("far", 500), make_segment("near", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "near"


def test_last_position_starts_at_the_destination_and_tracks_updates():
    segments = [make_segment("only", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert (session.last_lat, session.last_lon) == (ORIGIN_LAT, ORIGIN_LON)

    lat, lon = drive_position(40)
    session.update_position(lat, lon)
    assert (session.last_lat, session.last_lon) == (lat, lon)


def test_zone_filter_excludes_non_matching_segments():
    segments = [make_segment("blue-spot", 50, zone_type=ZoneType.BLUE),
                make_segment("white-spot", 60, zone_type=ZoneType.WHITE)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "blue-spot"
    assert "white-spot" not in candidate_ids(session)


def test_radius_excludes_far_segments():
    segments = [make_segment("in-range", 50), make_segment("out-of-range", 5000)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert candidate_ids(session) == ["in-range"]


def test_manual_reject_advances_to_next_candidate():
    segments = [make_segment("first", 100), make_segment("second", 500)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    session.reject_current()
    assert session.current.id == "second"
    assert "first" in session.rejected_ids
    assert [s.id for s in session.rejected] == ["first"]


def test_rejected_segments_stay_visible_across_a_radius_expansion():
    # The client still needs to show where the driver already looked and
    # found nothing, even after expand_radius rebuilds `candidates` from a
    # wider query that excludes already-rejected segments by design.
    segments = [make_segment("near", 100), make_segment("far", 350)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    store.reject_current(session)
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "far"
    assert [s.id for s in session.rejected] == ["near"]


def test_confirm_marks_session_parked():
    segments = [make_segment("only", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    session.confirm_current()
    assert session.state == SessionState.PARKED
    assert session.current is None


def test_rejecting_last_candidate_exhausts_the_session():
    segments = [make_segment("only", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    session.reject_current()
    assert session.state == SessionState.EXHAUSTED
    assert session.current is None


def test_approaching_and_stopping_does_not_trigger_rejection():
    segments = [make_segment("target", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)

    # Approach the spot (at 100m) steadily and stop right on top of it.
    for north_m in (0, 50, 80, 95, 100, 100):
        event = session.update_position(*drive_position(north_m))
    assert event == "tracking"
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "target"


def test_driving_past_a_spot_without_stopping_triggers_auto_rejection():
    segments = [make_segment("A", 100), make_segment("B", 500)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "A"

    # 40m short: not close enough yet to count as "reached".
    event = session.update_position(*drive_position(60))
    assert event == "tracking"

    # Within the approach threshold.
    event = session.update_position(*drive_position(100 - (APPROACH_THRESHOLD_M - 1)))
    assert event == "tracking"
    assert session.closest_approach_m["A"] <= APPROACH_THRESHOLD_M

    # Drove past it by more than the depart margin without stopping.
    event = session.update_position(
        *drive_position(100 + APPROACH_THRESHOLD_M + DEPART_MARGIN_M)
    )
    assert event == "auto_rejected"
    assert "A" in session.rejected_ids
    assert session.current.id == "B"


def test_top_suggestion_switches_to_a_closer_candidate_in_the_same_cluster():
    # "P" and "Q" are 60m apart (within the clustering eps) so they're one
    # cluster; "P" starts closest to the origin (so it's the initial pick),
    # but the driver heads for "Q" instead without ever getting near "P" --
    # the top suggestion should follow within the shared cluster.
    segments = [make_segment("P", 100), make_segment("Q", 100, east_m=60)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "P"

    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 100, 60)
    event = session.update_position(lat, lon)

    assert event == "retargeted"
    assert session.current.id == "Q"
    assert "P" not in session.rejected_ids
    assert any(c.id == "P" for c in session.upcoming)


def test_top_suggestion_does_not_jump_to_a_different_distant_cluster():
    # "A" and "B" are 304m apart -- well past the clustering eps, so they
    # land in separate clusters. The driver heads straight for "B" without
    # ever approaching "A", but since "B" is a whole separate cluster (the
    # "across the tracks" case from the README), the top suggestion should
    # stay on "A"'s cluster until it's actually exhausted, not jump just
    # because "B" is momentarily closer as the crow flies.
    segments = [make_segment("A", 100), make_segment("B", 50, east_m=300)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "A"

    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 50, 300)
    event = session.update_position(lat, lon)

    assert event == "tracking"
    assert session.current.id == "A"
    assert any(c.id == "B" for c in session.upcoming)


def test_driving_past_a_dense_row_of_spots_rejects_each_one():
    # Real Zurich data packs many segments just a few meters apart along a
    # single street. Regression test for a bug where "current" reshuffled
    # to whichever neighbor was nearest on every tick, resetting the
    # approach tracking before any single spot's approach/depart cycle
    # could ever complete -- so driving straight down a dense row rejected
    # nothing at all.
    segments = [make_segment(f"s{i}", 10 + i * 5) for i in range(5)]  # 10, 15, 20, 25, 30
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)

    for north_m in range(0, 61, 3):
        session.update_position(*drive_position(north_m))

    assert session.rejected_ids == {f"s{i}" for i in range(5)}
    assert session.state == SessionState.EXHAUSTED


def test_cluster_exhaustion_pulls_in_a_new_local_cluster_before_falling_back():
    # "near" and "far" are both known from the start (separate clusters,
    # both within the initial radius); "local" is outside that initial
    # radius, so it's undiscovered until the driver actually gets near it.
    # Once "near" is exhausted, the driver ends up much closer to "local"
    # than to "far" -- the store should notice and pull "local" in ahead of
    # falling back to "far", instead of just handing over whatever was next
    # in the original destination-ranked order (see README section 4).
    segments = [
        make_segment("near", 100),
        make_segment("far", 100, east_m=100),
        make_segment("local", 100, east_m=-200),
    ]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=150)
    assert session.current.id == "near"
    assert candidate_ids(session) == ["near", "far"]  # "local" not yet known

    store.update_position(session, *drive_position(100 - (APPROACH_THRESHOLD_M - 1)))
    event = store.update_position(
        session, *drive_position(100 + APPROACH_THRESHOLD_M + DEPART_MARGIN_M)
    )

    assert event == "retargeted"
    assert session.current.id == "local"
    assert any(c.id == "far" for c in session.upcoming)


def test_auto_rejecting_the_last_candidate_reports_exhausted():
    segments = [make_segment("only", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    session.update_position(*drive_position(100 - (APPROACH_THRESHOLD_M - 1)))
    event = session.update_position(
        *drive_position(100 + APPROACH_THRESHOLD_M + DEPART_MARGIN_M)
    )
    assert event == "exhausted"
    assert session.state == SessionState.EXHAUSTED


def test_update_position_on_exhausted_session_is_a_noop():
    segments = [make_segment("only", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    session.reject_current()
    assert session.state == SessionState.EXHAUSTED
    event = session.update_position(*drive_position(100))
    assert event == "exhausted"


def test_expand_radius_reveals_previously_out_of_range_segments_and_keeps_rejections():
    segments = [make_segment("near", 100), make_segment("far", 350)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert candidate_ids(session) == ["near"]

    session.reject_current()
    assert session.state == SessionState.EXHAUSTED

    store.expand_radius(session)
    assert session.radius_m == 400  # 200 + the 200m expansion step
    assert session.state == SessionState.SEARCHING
    assert candidate_ids(session) == ["far"]


# The store-level wrappers below are what the API actually calls (see
# backend/app.py): a driver in motion can't be left with a dead end just
# because the current search radius has nothing left, so these auto-expand
# instead of requiring a separate manual "expand" call.


def test_create_auto_expands_when_starting_radius_has_nothing():
    segments = [make_segment("far", 350)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "far"
    assert session.radius_m == 400


def test_store_reject_current_auto_expands_instead_of_stopping():
    segments = [make_segment("near", 100), make_segment("far", 350)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert session.current.id == "near"

    event = store.reject_current(session)
    assert event == "expanded"
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "far"


def test_store_update_position_auto_expands_instead_of_stopping():
    segments = [make_segment("near", 100), make_segment("far", 350)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)

    store.update_position(session, *drive_position(100 - (APPROACH_THRESHOLD_M - 1)))
    event = store.update_position(
        session, *drive_position(100 + APPROACH_THRESHOLD_M + DEPART_MARGIN_M)
    )
    assert event == "expanded"
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "far"


def test_store_reject_current_reports_plain_rejected_when_more_candidates_remain():
    segments = [make_segment("first", 100), make_segment("second", 150)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    event = store.reject_current(session)
    assert event == "rejected"
    assert session.current.id == "second"


def test_auto_expand_gives_up_at_max_radius_for_a_genuinely_empty_area():
    segments = [make_segment("only", 100, zone_type=ZoneType.WHITE)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert session.state == SessionState.EXHAUSTED
    assert session.radius_m >= MAX_RADIUS_M


def test_candidate_count_is_capped_in_dense_areas():
    # Real Zurich data can put thousands of segments within one radius;
    # clustering is O(n^2), so _candidates_for must bound how many it
    # passes in regardless of how many actually match (see MAX_CANDIDATES_
    # PER_QUERY in backend/session.py).
    segments = [make_segment(f"s{i}", 10 + i) for i in range(MAX_CANDIDATES_PER_QUERY + 50)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=10_000)
    assert len(candidate_ids(session)) == MAX_CANDIDATES_PER_QUERY
    # And it's still the *nearest* ones that get kept, not an arbitrary subset.
    assert session.current.id == "s0"
