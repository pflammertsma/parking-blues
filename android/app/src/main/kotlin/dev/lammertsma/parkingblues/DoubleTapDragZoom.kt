package dev.lammertsma.parkingblues

import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Double-tap-and-drag zoom, as in Google Maps: tap, then tap again and keep the finger down.
 *
 *  - Release without moving: a plain double tap, zoom in one level.
 *  - Drag down: zoom in; drag up: zoom out. It zooms continuously for as long as the finger moves,
 *    around the point that was tapped (or around the map centre while the map follows the user).
 *
 * osmdroid's own double-tap handling zooms in the instant the second finger lands, which would
 * leave nothing to drag, so this overlay claims `onDoubleTap` and does both jobs itself.
 *
 * The drag is followed in [onTouchEvent], not in `onDoubleTapEvent`: Android's GestureDetector
 * stops reporting a double tap's moves once its long-press timeout (about half a second) has
 * passed, so a slow drag would freeze the zoom part-way. The events still reach the detector,
 * which does not treat a double tap's moves as a pan, so nothing else moves the map meanwhile.
 *
 * Only the phone gets this: the car map is driven through the host's SurfaceCallback, which has
 * no double-tap-and-drag gesture (only scroll, scale and fling).
 */
class DoubleTapDragZoom(
    private val aroundCenter: () -> Boolean,
) : Overlay() {

    /** True from the second tap going down until it lifts; the map must not treat the drag as a pan. */
    var active = false
        private set

    private var downY = 0f
    private var pivotX = 0
    private var pivotY = 0
    private var startZoom = 0.0
    private var pivot: GeoPoint? = null
    private var dragged = false

    // Claim the gesture so osmdroid does not zoom in immediately; the work happens below.
    override fun onDoubleTap(e: MotionEvent, mapView: MapView): Boolean = true

    // The second tap going down: this is where a drag-zoom starts.
    override fun onDoubleTapEvent(e: MotionEvent, mapView: MapView): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) begin(e, mapView)
        return true
    }

    override fun onTouchEvent(e: MotionEvent, mapView: MapView): Boolean {
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag(e, mapView)
            MotionEvent.ACTION_UP -> {
                if (!dragged) mapView.controller.zoomInFixing(pivotX, pivotY)
                end()
            }
            // A second finger (a pinch) or a cancelled gesture ends the drag-zoom.
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> end()
        }
        // Let the events through: the gesture detector needs to see the finger lift.
        return false
    }

    private fun begin(e: MotionEvent, map: MapView) {
        map.controller.stopAnimation(false)
        active = true
        dragged = false
        downY = e.y
        if (aroundCenter()) {
            pivotX = map.width / 2
            pivotY = map.height / 2
        } else {
            pivotX = e.x.toInt()
            pivotY = e.y.toInt()
        }
        startZoom = map.zoomLevelDouble
        pivot = map.projection.fromPixels(pivotX, pivotY) as GeoPoint
    }

    private fun drag(e: MotionEvent, map: MapView) {
        val dy = e.y - downY
        if (!dragged) {
            if (abs(dy) <= ViewConfiguration.get(map.context).scaledTouchSlop) return
            dragged = true
        }
        val anchor = pivot ?: return
        map.setZoomLevel(zoomForDrag(startZoom, dy, map.height))
        // Keep the tapped spot under the finger: setZoomLevel scales around the centre.
        val now = map.projection.toPixels(anchor, null)
        map.scrollBy(now.x - pivotX, now.y - pivotY)
    }

    private fun end() {
        active = false
        pivot = null
    }

    companion object {
        /** A drag of a quarter of the map's height is one zoom level. */
        private const val LEVELS_PER_HEIGHT = 4.0

        /** Dragging down zooms in, up zooms out. Pure, so it is unit-tested. */
        fun zoomForDrag(startZoom: Double, dragDownPx: Float, mapHeightPx: Int): Double {
            if (mapHeightPx <= 0) return startZoom
            return startZoom + dragDownPx / (mapHeightPx / LEVELS_PER_HEIGHT)
        }
    }
}
