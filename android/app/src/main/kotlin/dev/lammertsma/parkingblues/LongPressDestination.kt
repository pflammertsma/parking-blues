package dev.lammertsma.parkingblues

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Long-press the map to make that exact spot the destination: the search moves there and the
 * pin with it, the same as "Search here" but at the pressed point instead of the map centre.
 *
 * [busy] says another gesture owns the touch. A double-tap-and-drag zoom is held for as long as
 * the finger is down, which is longer than the long-press timeout, and must not also drop a pin.
 */
class LongPressDestination(
    private val busy: () -> Boolean,
    private val onPick: (GeoPoint) -> Unit,
) : Overlay() {
    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        if (busy()) return false
        val point = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onPick(point)
        return true
    }
}
