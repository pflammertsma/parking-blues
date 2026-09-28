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


def test_create_session_orders_nearest_candidate_first():
    segments = [make_segment("far", 500), make_segment("near", 100)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.state == SessionState.SEARCHING
    assert session.current.id == "near"


def test_zone_filter_excludes_non_matching_segments():
    segments = [make_segment("blue-spot", 50, zone_type=ZoneType.BLUE),
                make_segment("white-spot", 60, zone_type=ZoneType.WHITE)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "blue-spot"
    assert all(c.id != "white-spot" for c in session.candidates)


def test_radius_excludes_far_segments():
    segments = [make_segment("in-range", 50), make_segment("out-of-range", 5000)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=200)
    assert [c.id for c in session.candidates] == ["in-range"]


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


def test_top_suggestion_switches_to_a_closer_candidate_as_driver_approaches_it():
    # "A" starts closest to the search origin (so it's the initial pick),
    # but the driver heads straight for "B" instead without ever getting
    # near "A" -- the top suggestion should follow, not stay stuck on "A".
    segments = [make_segment("A", 100), make_segment("B", 50, east_m=300)]
    store = SessionStore(segments)
    session = store.create(ORIGIN_LAT, ORIGIN_LON, {ZoneType.BLUE}, radius_m=1000)
    assert session.current.id == "A"

    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 50, 300)
    event = session.update_position(lat, lon)

    assert event == "retargeted"
    assert session.current.id == "B"
    assert "A" not in session.rejected_ids
    assert any(c.id == "A" for c in session.upcoming)


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
    assert [c.id for c in session.candidates] == ["near"]

    session.reject_current()
    assert session.state == SessionState.EXHAUSTED

    store.expand_radius(session)
    assert session.radius_m == 400  # 200 + the 200m expansion step
    assert session.state == SessionState.SEARCHING
    assert [c.id for c in session.candidates] == ["far"]


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
    assert len(session.candidates) == MAX_CANDIDATES_PER_QUERY
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
    assert [c.id for c in session.candidates] == ["long-enough"]


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
    assert [c.id for c in session.candidates] == ["far-long-enough"]
