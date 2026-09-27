"""Small geo helpers. No external dependencies on purpose -- this is an MVP."""

import math

EARTH_RADIUS_M = 6371000.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance between two WGS84 points, in meters."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = (
        math.sin(dphi / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    )
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


_M_PER_DEG_LAT = 111_320.0


def offset_point(lat: float, lon: float, north_m: float, east_m: float) -> tuple[float, float]:
    """Move a WGS84 point by a given number of meters north/east.

    Flat-earth approximation -- accurate enough at city scale, not meant
    for long distances.
    """
    m_per_deg_lon = _M_PER_DEG_LAT * math.cos(math.radians(lat))
    return (lat + north_m / _M_PER_DEG_LAT, lon + east_m / m_per_deg_lon)
