from backend.geo import haversine_m, offset_point

ZURICH_HB_LAT = 47.3779
ZURICH_HB_LON = 8.5403


def test_distance_to_self_is_zero():
    assert haversine_m(ZURICH_HB_LAT, ZURICH_HB_LON, ZURICH_HB_LAT, ZURICH_HB_LON) == 0


def test_distance_is_symmetric():
    a = (47.370, 8.538)
    b = (47.372, 8.541)
    assert haversine_m(*a, *b) == haversine_m(*b, *a)


def test_one_degree_of_latitude_is_about_111km():
    d = haversine_m(0.0, 0.0, 1.0, 0.0)
    assert 110_000 < d < 112_000


def test_offset_point_round_trips_distance():
    lat, lon = offset_point(ZURICH_HB_LAT, ZURICH_HB_LON, north_m=100, east_m=0)
    d = haversine_m(ZURICH_HB_LAT, ZURICH_HB_LON, lat, lon)
    assert abs(d - 100) < 1.0


def test_offset_point_east_and_north_are_independent_axes():
    north_only = offset_point(ZURICH_HB_LAT, ZURICH_HB_LON, north_m=50, east_m=0)
    east_only = offset_point(ZURICH_HB_LAT, ZURICH_HB_LON, north_m=0, east_m=50)
    assert north_only[0] > ZURICH_HB_LAT
    assert abs(north_only[1] - ZURICH_HB_LON) < 1e-9
    assert east_only[0] == ZURICH_HB_LAT
    assert east_only[1] > ZURICH_HB_LON
