package dev.lammertsma.parkingblues.car.car

/**
 * Where the app has anything to show: the City of Zurich. The box is the bounding box of the
 * parking data (`backend/data/zurich_parking.json`: 47.324-47.432 N, 8.466-8.606 E) plus about
 * 3 km on each side, so the map cannot be panned to places with no parking, and a device outside
 * it is shown Zurich instead of a blank map.
 *
 * Not the same as the tile server's area (`REGION` in `backend/tiles.py`), which is larger and
 * only decides where tiles exist. If the data is ever extended beyond the city, widen this.
 */
object CoverageArea {
    const val SOUTH = 47.29
    const val NORTH = 47.47
    const val WEST = 8.415
    const val EAST = 8.655

    /** Zurich main station: where the map starts and what "Show Zurich" looks at. */
    const val CENTER_LAT = 47.3779
    const val CENTER_LON = 8.5403

    fun contains(lat: Double, lon: Double): Boolean = lat in SOUTH..NORTH && lon in WEST..EAST
}
