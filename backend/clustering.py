"""Grouping nearby parking segments into walkable clusters, and ranking them.

Deliberately simple (single-linkage over a distance threshold via
union-find) rather than a full DBSCAN -- this is an MVP; see README
section 4 for the algorithm this approximates.
"""

import math

from .geo import haversine_m
from .models import ParkingSegment, ZoneType

# Chosen against real Zurich data, not the original fixture data this was
# tuned against (see README section 4/9): individual real segments are
# only a few meters apart along a curb, so any eps loose enough to bridge
# a street corner chains an entire connected neighborhood into one
# "cluster" -- at the old 80m default, the top-ranked cluster near a real
# test point spanned 235 segments over 300+ meters, nowhere near walkable.
# This stays tight enough to still merge one continuous curb run without
# bridging across intersections into the next block.
DEFAULT_CLUSTER_EPS_M = 12.0

# A cluster made up entirely of free (blue-zone) segments gets up to this
# fraction more score than an otherwise-identical all-paid (white-zone)
# one, scaled by what fraction of the cluster is free -- see cluster_score.
# A soft nudge (proximity/capacity can still outweigh it), not a hard
# preference, since a driver asking for "both" zones still wants the
# genuinely closer option to win when the difference is large.
FREE_ZONE_SCORE_BONUS = 0.3


class _UnionFind:
    def __init__(self, n: int):
        self.parent = list(range(n))

    def find(self, x: int) -> int:
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a: int, b: int) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[ra] = rb


def cluster_segments(
    segments: list[ParkingSegment], eps_m: float = DEFAULT_CLUSTER_EPS_M
) -> list[list[ParkingSegment]]:
    """Group segments that are within `eps_m` of at least one other member
    of the same group (single-linkage), using a naive O(n^2) pass -- fine
    for the segment counts an MVP or a single city district deals with.
    """
    n = len(segments)
    uf = _UnionFind(n)
    for i in range(n):
        for j in range(i + 1, n):
            d = haversine_m(
                segments[i].lat, segments[i].lon, segments[j].lat, segments[j].lon
            )
            if d <= eps_m:
                uf.union(i, j)

    groups: dict[int, list[ParkingSegment]] = {}
    for i, seg in enumerate(segments):
        groups.setdefault(uf.find(i), []).append(seg)
    return list(groups.values())


def cluster_centroid(cluster: list[ParkingSegment]) -> tuple[float, float]:
    lat = sum(s.lat for s in cluster) / len(cluster)
    lon = sum(s.lon for s in cluster) / len(cluster)
    return lat, lon


def cluster_score(cluster: list[ParkingSegment], origin_lat: float, origin_lon: float) -> float:
    """Higher is better: rewards more estimated capacity and a higher
    fraction of free (blue-zone) segments, penalizes distance from the
    origin. See README section 4, step 2.

    Capacity is log-scaled and distance penalized more steeply than the
    original (linear-capacity, /100) formula this replaced: against real
    Zurich density, that version let a cluster with several times the
    capacity win over one several times closer -- e.g. a 40-segment
    cluster 264m away outscoring a 9-segment one 69m away, even though a
    single driver just needs one spot and the closer cluster already
    offers several tries. Going from 5 to 9 candidates should matter more
    to the score than going from 20 to 40; log1p gives that diminishing
    return, and dividing distance by 50 instead of 100 makes proximity the
    dominant factor at realistic search radii.
    """
    centroid_lat, centroid_lon = cluster_centroid(cluster)
    distance_m = haversine_m(origin_lat, origin_lon, centroid_lat, centroid_lon)
    total_capacity = sum(s.estimated_capacity for s in cluster)
    free_fraction = sum(s.zone_type == ZoneType.BLUE for s in cluster) / len(cluster)
    base_score = math.log1p(total_capacity) / (1.0 + distance_m / 50.0)
    return base_score * (1.0 + FREE_ZONE_SCORE_BONUS * free_fraction)


def rank_clusters(
    clusters: list[list[ParkingSegment]], origin_lat: float, origin_lon: float
) -> list[list[ParkingSegment]]:
    """Rank clusters best-first, and order segments within each cluster by
    distance from the origin (nearest first).
    """

    def segment_distance(seg: ParkingSegment) -> float:
        return haversine_m(origin_lat, origin_lon, seg.lat, seg.lon)

    ranked = sorted(
        clusters,
        key=lambda c: cluster_score(c, origin_lat, origin_lon),
        reverse=True,
    )
    return [sorted(c, key=segment_distance) for c in ranked]


def build_ranked_clusters(
    segments: list[ParkingSegment],
    origin_lat: float,
    origin_lon: float,
    eps_m: float = DEFAULT_CLUSTER_EPS_M,
) -> list[list[ParkingSegment]]:
    """Rank clusters best-first, without flattening them -- callers that
    need to keep working through one cluster before moving to the next
    (see ParkingSession in session.py, and the "across the tracks" note in
    README section 4) need the grouping preserved.
    """
    if not segments:
        return []
    clusters = cluster_segments(segments, eps_m=eps_m)
    return rank_clusters(clusters, origin_lat, origin_lon)


def build_candidate_order(
    segments: list[ParkingSegment],
    origin_lat: float,
    origin_lon: float,
    eps_m: float = DEFAULT_CLUSTER_EPS_M,
) -> list[ParkingSegment]:
    """Flatten ranked clusters into the single visiting order a session
    works through: best cluster first, nearest segment within it first.
    """
    return [
        seg
        for cluster in build_ranked_clusters(segments, origin_lat, origin_lon, eps_m)
        for seg in cluster
    ]
