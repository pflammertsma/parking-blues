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
FREE_ZONE_SCORE_BONUS = 0.8

# How far from a cluster's centroid (in meters) same-zone-type capacity
# still counts toward its score, at a discount -- see
# _neighborhood_capacity. Looser than DEFAULT_CLUSTER_EPS_M on purpose:
# that threshold decides whether segments are the SAME walkable spot (tight,
# so a cluster never chains across a street corner); this decides whether
# a *different*, separately-ranked cluster is still an easy extra stroll
# away, which real Zurich zone boundaries make a wider, looser question --
# a continuous curb run of blue-zone parking often gets fragmented into
# several tiny clusters a few car-lengths apart (driveways, a bus stop, a
# tree pit), each too small on its own to rank well against one big
# adjacent lot, even though together they're the better bet. Reported live:
# a Play Console-grade example where 8 blue clusters (1-7 spaces each, 658-
# 685m away, ~22 spaces total) individually all lost to one 100-space white
# lot 583m away, despite the blue zone being the obviously better place for
# a driver to actually go first.
NEIGHBORHOOD_RADIUS_M = 60.0

# Nearby capacity (within NEIGHBORHOOD_RADIUS_M, from a *different* cluster)
# counts at this fraction of capacity that's actually in this cluster --
# it's a real extra option, but still costs a short extra walk/drive between
# micro-clusters that in-cluster capacity doesn't.
NEIGHBORHOOD_CAPACITY_WEIGHT = 0.8


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


def _neighborhood_capacity(
    cluster: list[ParkingSegment], all_segments: list[ParkingSegment] | None
) -> dict[ZoneType, float]:
    """Each zone type's capacity "reachable" from this cluster: its own
    segments, plus a discounted share of same-zone-type capacity from
    *other* clusters within NEIGHBORHOOD_RADIUS_M of this one's centroid --
    see that constant's doc for why. `all_segments` is the full candidate
    pool a cluster was cut from (None when the caller never had it, e.g. a
    cluster list built and scored directly in a test -- then there's
    nothing to look beyond this cluster's own segments).
    """
    centroid_lat, centroid_lon = cluster_centroid(cluster)
    capacity = {ZoneType.BLUE: 0.0, ZoneType.WHITE: 0.0}
    for s in cluster:
        capacity[s.zone_type] += s.estimated_capacity

    if all_segments:
        own_ids = {s.id for s in cluster}
        for s in all_segments:
            if s.id in own_ids:
                continue
            if haversine_m(centroid_lat, centroid_lon, s.lat, s.lon) <= NEIGHBORHOOD_RADIUS_M:
                capacity[s.zone_type] += NEIGHBORHOOD_CAPACITY_WEIGHT * s.estimated_capacity

    return capacity


def cluster_score(
    cluster: list[ParkingSegment],
    origin_lat: float,
    origin_lon: float,
    all_segments: list[ParkingSegment] | None = None,
) -> float:
    """Higher is better: rewards more estimated capacity (this cluster's
    own, plus nearby same-zone-type capacity -- see
    NEIGHBORHOOD_CAPACITY_WEIGHT) and a higher fraction of that capacity
    being free (blue-zone), penalizes distance from the origin. See README
    section 4, step 2.

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
    capacity = _neighborhood_capacity(cluster, all_segments)
    total_capacity = capacity[ZoneType.BLUE] + capacity[ZoneType.WHITE]
    free_fraction = capacity[ZoneType.BLUE] / total_capacity if total_capacity else 0.0
    base_score = math.log1p(total_capacity) / (1.0 + distance_m / 50.0)
    return base_score * (1.0 + FREE_ZONE_SCORE_BONUS * free_fraction)


def rank_clusters(
    clusters: list[list[ParkingSegment]],
    origin_lat: float,
    origin_lon: float,
    all_segments: list[ParkingSegment] | None = None,
) -> list[list[ParkingSegment]]:
    """Rank clusters best-first, and order segments within each cluster by
    distance from the origin (nearest first). `all_segments` is the full
    pool `clusters` was cut from, for the neighborhood-capacity term in
    cluster_score -- defaults to clusters' own union when not given (e.g. a
    test building a cluster list directly, where that union already is
    everything there is to know about).
    """
    if all_segments is None:
        all_segments = [s for c in clusters for s in c]

    def segment_distance(seg: ParkingSegment) -> float:
        return haversine_m(origin_lat, origin_lon, seg.lat, seg.lon)

    ranked = sorted(
        clusters,
        key=lambda c: cluster_score(c, origin_lat, origin_lon, all_segments),
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
    return rank_clusters(clusters, origin_lat, origin_lon, segments)


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
