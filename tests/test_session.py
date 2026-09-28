from datetime import datetime

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


def make_segment(id_, north_m, east_m=0, zone_type=ZoneType.BLUE, max_duration_minutes=None):
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, east_m)
    return ParkingSegment(
        id=id_, lat=lat, lon=lon, zone_type=zone_type,
        address_label=id_, estimated_capacity=1,
        max_duration_minutes=max_duration_minutes,
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
    # "P" and "Q" are 10m apart (within the clustering eps) so they're one
    # cluster; "P" starts closest to the origin (so it's the initial pick),
    # but the driver heads for "Q" instead without ever getting near "P" --
    # the top suggestion should follow within the shared cluster.
    segments = [make_segment("P", 100), make_segment("Q", 100, east_m=10)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "P"

    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 100, 10)
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
    # It's placed so that once "near" is rejected, it's genuinely closer to
    # the driver than "far" (next in the original destination-ranked
    # order) by more than LOCAL_PULL_IN_MARGIN_M -- a merely-new-but-not-
    # actually-closer candidate should *not* win (see the margin test
    # below); this one legitimately should.
    segments = [
        make_segment("near", 100),
        make_segment("far", 100, east_m=100),
        make_segment("local", 135, east_m=70),
    ]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=150)
    assert session.current.id == "near"
    assert candidate_ids(session) == ["near", "far"]  # "local" not yet known

    store.update_position(session, *drive_position(100 - (APPROACH_THRESHOLD_M - 1)))
    event = store.update_position(
        session, *drive_position(100 + APPROACH_THRESHOLD_M + DEPART_MARGIN_M)
    )

    # Both an auto-rejection and a pull-in happen on this same tick; only
    # one event name can be reported, and "retargeted" is what the caller
    # actually needs to know (the target changed) -- the rejection itself
    # is still fully reflected in the state below.
    assert event == "retargeted"
    assert "near" in session.rejected_ids
    assert session.current.id == "local"
    assert any(c.id == "far" for c in session.upcoming)


def test_local_pull_in_ignores_a_merely_new_candidate_that_is_not_closer():
    # This is the actual bug report this margin exists to fix: driving down
    # a street continuously surfaces new-to-the-session candidates on
    # almost every position tick just because the search window moved with
    # the driver -- not because they're any better than the current
    # target. Without a margin, every one of those would win by virtue of
    # being new, making the target flicker constantly while driving.
    # "far" is known from the start; "elsewhere" only becomes discoverable
    # once the driver is close enough, but it's farther from the driver
    # than "far" already is, so it should be ignored.
    segments = [
        make_segment("far", 100, east_m=100),
        make_segment("elsewhere", 100, east_m=-160),
    ]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=150)
    assert candidate_ids(session) == ["far"]  # "elsewhere" not yet known

    lat, lon = drive_position(100)
    event = store.update_position(session, lat, lon)

    assert event == "tracking"
    assert session.current.id == "far"


def test_drifting_far_from_the_target_pulls_in_a_local_cluster_without_exhausting_it():
    # The driver never gets close enough to "target" to trigger an
    # approach/depart cycle -- they just drive straight out of the whole
    # search area in one move. Even though "target"'s cluster still has it
    # as an untouched, un-rejected member, the app shouldn't keep pointing
    # back at it once the driver is clearly nowhere near it anymore -- it
    # should notice "local", right where the driver actually ended up.
    segments = [
        make_segment("target", 100),
        make_segment("local", 100, east_m=-500),
    ]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert session.current.id == "target"

    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 100, -500)
    event = store.update_position(session, lat, lon)

    assert event == "retargeted"
    assert session.current.id == "local"
    assert "target" not in session.rejected_ids
    assert any(c.id == "target" for c in session.upcoming)


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
    # "far" needs to be out of reach of the per-tick local search too (not
    # just the original radius), otherwise it'd get discovered on approach
    # before "near" is ever rejected, and the exhaustion/expansion path
    # this test targets would never actually run.
    segments = [make_segment("near", 100), make_segment("far", 800)]
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


# A Monday morning where a blue-zone arrival right now has exactly 90
# minutes available (next disc mark 09:30, +60 min = 10:30). See
# tests/test_duration_filter.py for the rule this exercises.
WEEKDAY_MORNING = datetime(2026, 9, 28, 9, 0)


def test_preferred_duration_excludes_white_zone_spots_that_are_too_short():
    segments = [
        make_segment("too-short", 50, zone_type=ZoneType.WHITE, max_duration_minutes=30),
        make_segment("long-enough", 60, zone_type=ZoneType.WHITE, max_duration_minutes=120),
    ]
    store = SessionStore(segments)
    session = store.create(
        ORIGIN_LAT, ORIGIN_LON, {ZoneType.WHITE}, radius_m=1000,
        preferred_duration_minutes=60, now=WEEKDAY_MORNING,
    )
    assert candidate_ids(session) == ["long-enough"]


def test_preferred_duration_respects_blue_zone_time_of_day():
    segments = [make_segment("blue-spot", 50, zone_type=ZoneType.BLUE)]
    store = SessionStore(segments)

    fits = store.create(
        ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000,
        preferred_duration_minutes=90, now=WEEKDAY_MORNING,
    )
    assert fits.state == SessionState.SEARCHING

    too_long = store.create(
        ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000,
        preferred_duration_minutes=91, now=WEEKDAY_MORNING,
    )
    assert too_long.state == SessionState.EXHAUSTED


def test_auto_expansion_keeps_reapplying_the_duration_filter():
    segments = [
        make_segment("near-too-short", 50, zone_type=ZoneType.WHITE, max_duration_minutes=30),
        make_segment("far-long-enough", 350, zone_type=ZoneType.WHITE, max_duration_minutes=120),
    ]
    store = SessionStore(segments)
    # Starting radius (200) only reaches "near-too-short", which the
    # duration filter rejects -- this forces auto-expansion (see
    # SessionStore._auto_expand_while_exhausted), which must keep applying
    # the same filter rather than only filtering on the first attempt.
    session = store.create(
        ORIGIN_LAT, ORIGIN_LON, {ZoneType.WHITE}, radius_m=200,
        preferred_duration_minutes=60, now=WEEKDAY_MORNING,
    )
    assert session.state == SessionState.SEARCHING
    assert candidate_ids(session) == ["far-long-enough"]
