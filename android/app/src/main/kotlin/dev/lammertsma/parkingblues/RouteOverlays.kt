package dev.lammertsma.parkingblues

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import dev.lammertsma.parkingblues.car.car.RouteProgress
import dev.lammertsma.parkingblues.shared.model.ParkingRoute
import dev.lammertsma.parkingblues.shared.model.RouteStop
import dev.lammertsma.parkingblues.shared.model.ZoneType
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/**
 * The planned parking route on the map. It is two lines with different jobs and different places
 * in the draw order:
 *
 *  - the part **already driven** is dark and lies *beneath* the parking boxes and icons;
 *  - the part **still ahead** is bright purple and lies *on top of* them, with the numbered stops,
 *    but under the two things that must never be hidden: the destination pin and the driver's own
 *    arrow.
 *
 * [addBelowSpots] and [addAboveSpots] are called from the one place that rebuilds the map's
 * overlays (renderParkingOverlays), at the matching points of its sequence, so the order holds
 * whenever the parking overlays are redrawn. [update] moves the cut as the driver moves.
 */
class RouteOverlays {
    private var route: ParkingRoute? = null
    private var path: List<GeoPoint> = emptyList()
    private var progressIndex = 0

    private val driven = Polyline()
    private val ahead = Polyline()
    private val aheadCasing = Polyline()

    // From where the driver is to where the road starts, when those are apart: dashed, because
    // it is not a drive, it is the gap the driver has to close first.
    private val gap = Polyline()
    private var stopMarkers: List<Marker> = emptyList()

    /** True when [setRoute] was given a different route than before, so the camera should fit it. */
    fun setRoute(context: Context, map: MapView, newRoute: ParkingRoute?): Boolean {
        if (newRoute === route) return false
        removeFrom(map)
        route = newRoute
        path = newRoute?.route?.path.orEmpty().map { GeoPoint(it[0], it[1]) }
        progressIndex = 0
        styleLines(context)
        stopMarkers = newRoute?.stops.orEmpty().map { stopMarker(context, map, it) }
        val snap = newRoute?.startSnap
        gap.setPoints(
            if (newRoute != null && snap != null) {
                listOf(GeoPoint(newRoute.start.lat, newRoute.start.lon), GeoPoint(snap.lat, snap.lon))
            } else {
                emptyList()
            }
        )
        update(map, null)
        return newRoute != null
    }

    val bounds get() = path

    fun addBelowSpots(map: MapView) {
        if (route == null) return
        map.overlays.add(driven)
    }

    fun addAboveSpots(map: MapView) {
        if (route == null) return
        map.overlays.add(aheadCasing)
        map.overlays.add(ahead)
        map.overlays.add(gap)
        stopMarkers.forEach { map.overlays.add(it) }
    }

    fun removeFrom(map: MapView) {
        map.overlays.remove(driven)
        map.overlays.remove(aheadCasing)
        map.overlays.remove(ahead)
        map.overlays.remove(gap)
        map.overlays.removeAll(stopMarkers.toSet())
    }

    /** Moves the cut between dark and bright to the driver's [position] (null: keep it where it was). */
    fun update(map: MapView, position: GeoPoint?) {
        if (route == null) return
        val split = RouteProgress.split(path, position, progressIndex)
        progressIndex = split.index
        driven.setPoints(split.traveled)
        ahead.setPoints(split.remaining)
        aheadCasing.setPoints(split.remaining)
        map.invalidate()
    }

    private fun styleLines(context: Context) {
        val density = context.resources.displayMetrics.density
        fun Polyline.style(color: Int, widthDp: Float) {
            outlinePaint.color = color
            outlinePaint.strokeWidth = widthDp * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            isGeodesic = false
            setInfoWindow(null)
        }
        driven.style(DRIVEN_COLOR, LINE_WIDTH_DP)
        gap.style(AHEAD_COLOR, GAP_WIDTH_DP)
        gap.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
        aheadCasing.style(CASING_COLOR, LINE_WIDTH_DP + 3f)
        ahead.style(AHEAD_COLOR, LINE_WIDTH_DP)
    }

    private fun stopMarker(context: Context, map: MapView, stop: RouteStop) = Marker(map).apply {
        position = GeoPoint(stop.lat, stop.lon)
        val zone = if (stop.zoneType == ZoneType.BLUE) "Blue zone" else "White zone"
        title = "Stop ${stop.rank} · $zone · ~${stop.capacity} spaces · " +
            "${(stop.chance * 100).roundToInt()}% chance"
        icon = BitmapDrawable(context.resources, stopIcon(context, stop.rank))
        setAnchor(0.5f, 0.5f)
    }

    private fun stopIcon(context: Context, number: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (STOP_SIZE_DP * density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, center, paint)
        paint.color = AHEAD_COLOR
        canvas.drawCircle(center, center, center - 2f * density, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.55f
        canvas.drawText(number.toString(), center, center - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }

    private companion object {
        /** Bright purple: the stretch still to drive. */
        val AHEAD_COLOR = Color.parseColor("#B52BFF")

        /** The same purple, dark: the stretch already driven. */
        val DRIVEN_COLOR = Color.parseColor("#4B2A6B")

        val CASING_COLOR = Color.WHITE
        const val LINE_WIDTH_DP = 5f
        const val GAP_WIDTH_DP = 3f
        const val STOP_SIZE_DP = 26
    }
}
