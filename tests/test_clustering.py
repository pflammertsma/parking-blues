from backend.clustering import (
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
