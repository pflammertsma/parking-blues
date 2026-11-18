package dev.lammertsma.parkingblues

import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Sets the zoom to [zoom] while keeping the map point under pixel ([x], [y]) where it is.
 * `setZoomLevel` scales around the map centre, so the point is put back by scrolling.
 */
fun MapView.zoomKeepingPoint(x: Int, y: Int, zoom: Double) {
    val anchor = projection.fromPixels(x, y) as GeoPoint
    setZoomLevel(zoom)
    val now = projection.toPixels(anchor, null)
    scrollBy(now.x - x, now.y - y)
}

/**
 * The mouse wheel zooms around the pointer (wheel up zooms in), as on any map. Without this the
 * wheel falls through to the map's touch handling and scrolls it north and south, which is what
 * an emulator with a mouse (or a tablet with one) showed.
 *
 * Compose hands a wheel to its embedded view as a touch event, and a plain Android view receives
 * it as a generic motion event, so [handle] is wired to both (see ParkingMapView).
 */
class MouseWheelZoom : Overlay() {
    override fun onTouchEvent(e: MotionEvent, mapView: MapView): Boolean = handle(e, mapView)

    fun handle(e: MotionEvent, map: MapView): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val notches = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (notches != 0f) {
            map.controller.stopAnimation(false)
            map.zoomKeepingPoint(e.x.toInt(), e.y.toInt(), map.zoomLevelDouble + notches * LEVELS_PER_NOTCH)
        }
        return true
    }

    private companion object {
        /** One wheel notch is half a zoom level: two notches double the scale. */
        const val LEVELS_PER_NOTCH = 0.5
    }
}
