package dev.lammertsma.parkingblues.car.car

import kotlin.math.roundToInt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.lammertsma.parkingblues.car.R
import dev.lammertsma.parkingblues.shared.model.ZoneType

/**
 * Marker/polygon colors and icon factories, extracted out of MapSearchScreen
 * (car-only) so the phone's own map view (ParkingMapView, in :app) renders
 * with the exact same look instead of a second, potentially-drifting copy.
 * Takes a plain Context (not CarContext) throughout -- CarContext is a
 * subclass, so car code keeps working unchanged, and a plain Activity
 * Context works equally well for the phone's AndroidView-hosted MapView.
 *
 * osmdroid markers take a plain Drawable -- no BitmapDescriptorFactory-style
 * rasterization step needed the way the old Google Maps SDK version of this
 * code did. Vector icons still get rasterized to a Bitmap here regardless,
 * because osmdroid's Marker.draw() resets icon bounds to the Drawable's own
 * intrinsicWidth/Height on every frame (confirmed live -- a vector drawable
 * with setBounds() alone kept snapping back to its XML-declared size), so a
 * BitmapDrawable sized to what we actually want is required, not optional.
 */
object MapIcons {
    const val CAR_ICON_DP = 84
    const val CANDIDATE_DIAMETER_DP = 48 // 12dp original prototype x4
    const val ZONE_ICON_DP = 48
    const val DESTINATION_ICON_DP = 80 // doubled from the original 40dp XML intrinsic size

    // The pin's tip in assets/destination.svg, as a fraction of its 474x474
    // viewBox (computed directly from the source path's own coordinates +
    // translate, not eyeballed) -- so the marker's anchor is the actual pin
    // tip, not its bounding-box center, matching how a map pin is meant to
    // indicate a precise point.
    const val DESTINATION_ANCHOR_X = 0.5f
    const val DESTINATION_ANCHOR_Y = 0.966f

    // Was 0.18 (an 18%-of-diameter solid white ring) -- thinned out
    // alongside the color change below; that combination is what was
    // washing the dots out, not just the color alone.
    private const val STROKE_RATIO = 0.06f

    // A restrained, modern palette instead of a default hue wheel.
    @ColorInt val COLOR_YOU = Color.parseColor("#9C27B0")
    @ColorInt val COLOR_REJECTED = Color.parseColor("#9E9E9E")
    @ColorInt val COLOR_REJECTED_ZONE_FILL = Color.argb(45, 150, 150, 150)
    @ColorInt val COLOR_REJECTED_ZONE_STROKE = Color.parseColor("#9E9E9E")

    // The blue used by both assets/blue-zone.svg and assets/white-zone.svg
    // (as its fill and border respectively) -- reused for the cluster box
    // stroke/fill so the boxes visually match their icon rather than
    // introducing a separate, unrelated color scheme.
    @ColorInt val ZONE_ACCENT = Color.parseColor("#268BCC")

    fun zoneFillColor(zoneType: ZoneType): Int =
        if (zoneType == ZoneType.BLUE) {
            Color.argb(90, Color.red(ZONE_ACCENT), Color.green(ZONE_ACCENT), Color.blue(ZONE_ACCENT))
        } else {
            Color.argb(90, 255, 255, 255)
        }

    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun vectorDrawableIcon(context: Context, @DrawableRes resId: Int, sizeDp: Int): Drawable {
        val drawable = ContextCompat.getDrawable(context, resId)!!.mutate()
        val sizePx = dpToPx(context, sizeDp)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return BitmapDrawable(context.resources, bitmap).apply {
            setBounds(0, 0, sizePx, sizePx)
        }
    }

    fun circleMarkerIcon(context: Context, @ColorInt colorInt: Int, diameterDp: Int): Drawable {
        val diameterPx = dpToPx(context, diameterDp)
        val strokePx = diameterPx * STROKE_RATIO
        val bitmap = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = diameterPx / 2f
        canvas.drawCircle(radius, radius, radius - strokePx / 2, fillPaint(colorInt))
        canvas.drawCircle(radius, radius, radius - strokePx / 2, strokePaint(strokePx))
        return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, diameterPx, diameterPx) }
    }

    /** The sizes above are tuned for the projected car map, which is viewed
     *  from a distance; a handheld phone map wants noticeably smaller markers. */
    const val PHONE_ICON_SCALE = 0.5f

    private fun scaledDp(dp: Int, scale: Float): Int = (dp * scale).roundToInt()

    fun blueZoneIcon(context: Context, scale: Float = 1f): Drawable =
        vectorDrawableIcon(context, R.drawable.ic_zone_blue, scaledDp(ZONE_ICON_DP, scale))

    fun whiteZoneIcon(context: Context, scale: Float = 1f): Drawable =
        vectorDrawableIcon(context, R.drawable.ic_zone_white, scaledDp(ZONE_ICON_DP, scale))

    fun zoneIcon(context: Context, zoneType: ZoneType, scale: Float = 1f): Drawable =
        if (zoneType == ZoneType.BLUE) blueZoneIcon(context, scale) else whiteZoneIcon(context, scale)

    /** Same zone glyph, greyed out -- used for already-checked/rejected
     *  clusters instead of a second, unrelated "rejected" icon. */
    fun rejectedZoneIcon(context: Context, zoneType: ZoneType): Drawable =
        vectorDrawableIcon(context, if (zoneType == ZoneType.BLUE) R.drawable.ic_zone_blue else R.drawable.ic_zone_white, ZONE_ICON_DP).apply {
            colorFilter = PorterDuffColorFilter(COLOR_REJECTED, PorterDuff.Mode.SRC_IN)
        }

    /** Purple triangle with rounded corners, rotated via Marker.rotation to
     *  indicate position and heading. */
    fun youMarkerIcon(context: Context, scale: Float = 1f): Drawable =
        vectorDrawableIcon(context, R.drawable.ic_location_triangle, scaledDp(CAR_ICON_DP, scale))

    /** assets/destination.svg's pin (just the pin shape, not the spelled-out
     *  "Destination" word also in that source file). */
    fun destinationMarkerIcon(context: Context, scale: Float = 1f): Drawable =
        vectorDrawableIcon(context, R.drawable.ic_destination, scaledDp(DESTINATION_ICON_DP, scale))

    fun rejectedMarkerIcon(context: Context): Drawable = circleMarkerIcon(context, COLOR_REJECTED, CANDIDATE_DIAMETER_DP)

    private fun fillPaint(@ColorInt colorInt: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }

    // Solid, not semi-transparent -- a translucent stroke was part of what
    // made these look blurry/soft-edged rather than just being a smaller
    // ring (direct feedback).
    private fun strokePaint(strokePx: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
}
