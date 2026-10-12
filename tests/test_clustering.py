import pytest

from backend.clustering import (
    NEIGHBORHOOD_CAPACITY_WEIGHT,
    NEIGHBORHOOD_RADIUS_M,
    _neighborhood_capacity,
    build_candidate_order,
    cluster_segments,
    rank_clusters,
)
from backend.geo import offset_point
from backend.models import ParkingSegment, ZoneType

ORIGIN_LAT, ORIGIN_LON = 47.3703, 8.5386


def make_segment(id_, north_m, east_m, capacity=1, zone_type=ZoneType.BLUE):
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, north_m, east_m)
    return ParkingSegment(
        id=id_,
        lat=lat,
        lon=lon,
        zone_type=zone_type,
        address_label=id_,
        estimated_capacity=capacity,
    )


def test_nearby_segments_form_one_cluster():
    segments = [
        make_segment("a", 0, 0),
        make_segment("b", 10, 10),
        make_segment("c", -15, 5),
    ]
    clusters = cluster_segments(segments, eps_m=80)
    assert len(clusters) == 1
    assert {s.id for s in clusters[0]} == {"a", "b", "c"}


def test_distant_segments_form_separate_clusters():
    near = make_segment("near", 0, 0)
    far = make_segment("far", 2000, 2000)
    clusters = cluster_segments([near, far], eps_m=80)
    assert len(clusters) == 2
    assert {frozenset(s.id for s in c) for c in clusters} == {
        frozenset({"near"}),
        frozenset({"far"}),
    }


def test_transitive_chain_merges_into_one_cluster():
    # a-b and b-c are each within eps, but a-c alone would not be.
    # Single-linkage clustering should still merge all three via b.
    segments = [
        make_segment("a", 0, 0),
        make_segment("b", 0, 70),
        make_segment("c", 0, 140),
    ]
    clusters = cluster_segments(segments, eps_m=80)
    assert len(clusters) == 1
    assert {s.id for s in clusters[0]} == {"a", "b", "c"}


def test_rank_clusters_prefers_closer_and_bigger_clusters():
    close_small = [make_segment("close", 50, 0, capacity=1)]
    far_big = [
        make_segment("far1", 5000, 0, capacity=10),
        make_segment("far2", 5010, 5, capacity=10),
    ]
    ranked = rank_clusters([far_big, close_small], ORIGIN_LAT, ORIGIN_LON)
    # The nearby cluster should win despite the distant one having more
    # aggregate capacity, because it is two orders of magnitude closer.
    assert {s.id for s in ranked[0]} == {"close"}


def test_build_candidate_order_orders_within_cluster_by_distance():
    segments = [
        make_segment("far-in-cluster", 60, 60),
        make_segment("near-in-cluster", 10, 10),
    ]
    order = build_candidate_order(segments, ORIGIN_LAT, ORIGIN_LON)
    assert [s.id for s in order] == ["near-in-cluster", "far-in-cluster"]


def test_build_candidate_order_empty_input():
    assert build_candidate_order([], ORIGIN_LAT, ORIGIN_LON) == []


def test_default_eps_does_not_chain_across_a_typical_intersection_gap():
    # Real Zurich data is dense enough along one curb that any generous eps
    # chains far past a single walkable block -- at the old 80m default, a
    # real-data query saw a 235-segment cluster spanning 300+ meters. This
    # pins the default to something that still separates two distinct
    # clusters ~20m apart (roughly a street-corner gap) rather than merging
    # them into one "cluster" a driver couldn't reasonably treat as local.
    segments = [
        make_segment("block-a-1", 0, 0),
        make_segment("block-a-2", 0, 5),
        make_segment("block-b-1", 0, 25),
        make_segment("block-b-2", 0, 30),
    ]
    clusters = cluster_segments(segments)  # default eps, not 80
    assert len(clusters) == 2
    assert {frozenset(s.id for s in c) for c in clusters} == {
        frozenset({"block-a-1", "block-a-2"}),
        frozenset({"block-b-1", "block-b-2"}),
    }


def test_free_zone_cluster_is_preferred_over_an_equally_placed_paid_one():
    # Same distance, same capacity -- only the zone type differs. A driver
    # asking for "both" zones should still see the free option ranked
    # first when there's nothing else to prefer one over the other.
    blue_cluster = [make_segment("blue", 100, 0, zone_type=ZoneType.BLUE)]
    white_cluster = [make_segment("white", 100, 5, zone_type=ZoneType.WHITE)]
    ranked = rank_clusters([white_cluster, blue_cluster], ORIGIN_LAT, ORIGIN_LON)
    assert {s.id for s in ranked[0]} == {"blue"}


def test_free_zone_bonus_does_not_override_a_large_proximity_difference():
    # The bonus is a nudge, not a hard preference -- a much closer paid
    # cluster should still beat a distant free one.
    close_white = [make_segment("close-white", 50, 0, zone_type=ZoneType.WHITE)]
    far_blue = [make_segment("far-blue", 5000, 0, zone_type=ZoneType.BLUE)]
    ranked = rank_clusters([far_blue, close_white], ORIGIN_LAT, ORIGIN_LON)
    assert {s.id for s in ranked[0]} == {"close-white"}


def test_fragmented_nearby_blue_clusters_outrank_one_bigger_distant_white_one():
    # Reported live: a real search where one 100-space white cluster (100m
    # closer than any single blue one) outranked *every* individual blue
    # cluster, even though eight separate blue clusters sat within a tight
    # band a short walk past it, totalling well over double its capacity --
    # a driver would clearly be better off trying that blue stretch first.
    # Each blue cluster alone is too small to win on its own; only
    # recognizing them as one walkable opportunity (see
    # NEIGHBORHOOD_RADIUS_M) fixes this without loosening clustering itself
    # (which would risk chaining clusters across intersections again -- see
    # test_default_eps_does_not_chain_across_a_typical_intersection_gap).
    white = [make_segment("white", 583, 0, capacity=100, zone_type=ZoneType.WHITE)]
    blue_clusters = [
        [make_segment(f"blue-{i}", 658 + i, 0, capacity=cap, zone_type=ZoneType.BLUE)]
        for i, cap in enumerate([7, 4, 4, 2, 2, 1, 1, 1])
    ]
    ranked = rank_clusters([white, *blue_clusters], ORIGIN_LAT, ORIGIN_LON)
    assert ranked[0][0].zone_type == ZoneType.BLUE


def test_neighborhood_capacity_only_counts_other_clusters_within_radius():
    # Same-zone capacity just beyond NEIGHBORHOOD_RADIUS_M shouldn't count
    # at all -- it's genuinely a separate, farther trip, not a short extra
    # stroll from this cluster.
    near = [make_segment("near", 100, 0, capacity=1, zone_type=ZoneType.BLUE)]
    just_inside = [
        make_segment("inside", 100, NEIGHBORHOOD_RADIUS_M - 1, capacity=20, zone_type=ZoneType.BLUE)
    ]
    just_outside = [
        make_segment("outside", 100, NEIGHBORHOOD_RADIUS_M + 1, capacity=20, zone_type=ZoneType.BLUE)
    ]
    capacity_with_near_neighbor = _neighborhood_capacity(near, near + just_inside)
    capacity_with_far_neighbor = _neighborhood_capacity(near, near + just_outside)
    assert capacity_with_near_neighbor[ZoneType.BLUE] > 1  # boosted by "inside"
    assert capacity_with_far_neighbor[ZoneType.BLUE] == 1  # "outside" doesn't count


def test_neighborhood_capacity_keeps_zone_types_separate():
    # A nearby white cluster shouldn't inflate a blue cluster's *free*
    # capacity (and vice versa) -- only same-zone-type capacity counts
    # toward the free_fraction cluster_score uses for the bonus.
    blue = [make_segment("blue", 100, 0, capacity=1, zone_type=ZoneType.BLUE)]
    nearby_white = [make_segment("white-neighbor", 100, 10, capacity=20, zone_type=ZoneType.WHITE)]
    capacity = _neighborhood_capacity(blue, blue + nearby_white)
    assert capacity[ZoneType.BLUE] == 1
    assert capacity[ZoneType.WHITE] == pytest.approx(20 * NEIGHBORHOOD_CAPACITY_WEIGHT)
