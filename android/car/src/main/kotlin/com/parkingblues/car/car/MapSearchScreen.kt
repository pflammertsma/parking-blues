package com.parkingblues.car.car

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.OptIn
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.MapStyleOptions
import com.parkingblues.car.R
import com.parkingblues.car.location.locationUpdates
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.ParkingSegment
import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.SessionState
import com.parkingblues.shared.model.ZoneFilter
import com.parkingblues.shared.model.ZoneType
import kotlinx.coroutines.launch

/**
 * Prototype: a self-drawn map (Maps SDK for Android, via AppManager's
 * SurfaceCallback + a VirtualDisplay/Presentation hosting a MapView)
 * instead of PlaceListMapTemplate's host-rendered one. See android/README.md
 * for why this is a separate module concern (ACCESS_SURFACE permission,
 * MapWithContentTemplate living in androidx.car.app.navigation.model despite
 * being POI-app-eligible) and android/local.properties for the API key.
 *
 * The payoff over the host map: we can draw things PlaceListMapTemplate's
 * Place/PlaceMarker model can't -- a distinct "you" marker, the destination
 * pin, and dimmed rejected spots, matching web/app.js's map exactly (see
 * SessionSnapshot.you / .origin / .rejected).
 *
 * No special "current target" treatment (no highlighted marker, no rank
 * numbers, no arrow prefix) -- the driver looks at the map and picks
 * whichever cluster they want, same as the web MVP's map view. The
 * backend still tracks one segment as `current` for its own approach/
 * depart auto-rejection bookkeeping (see backend/session.py), but that's
 * an internal detail now, not something the UI calls out.
 */
class MapSearchScreen(
    carContext: CarContext,
    private val repository: ParkingSessionRepository,
    private val retryZone: ZoneFilter? = null,
    private val retryLat: Double? = null,
    private val retryLon: Double? = null,
) : Screen(carContext), SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var hasFramedInitialCamera = false

    // The map follows the driver's live position by default (this is meant
    // to be glanced at while driving, not manually wrangled) but a manual
    // pan/zoom pauses that until the driver taps Recenter -- otherwise
    // every ~2s position update would yank any manual look-around back.
    private var isFollowingUser = true

    // Heading for the "you" marker's direction indicator. This is a pure
    // rendering concern, not something the backend needs (SessionSnapshot
    // has no bearing field), so it's tracked from a separate, local GPS
    // subscription rather than plumbed through ParkingSessionRepository.
    // Null when unavailable (device stationary, or the fixed test-location
    // point, which has no meaningful heading) -- shown as a plain dot then.
    private var lastBearing: Float? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycleScope.launch {
            repository.session.collect {
                invalidate()
                it?.let { snapshot -> renderMarkers(snapshot) }
            }
        }
        lifecycleScope.launch {
            repository.error.collect { invalidate() }
        }
        lifecycleScope.launch {
            locationUpdates(carContext).collect { fix ->
                lastBearing = fix.bearingDegrees
                repository.session.value?.let { renderMarkers(it) }
            }
        }
    }

    // -- SurfaceCallback: draw our own map onto the host-provided Surface --

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val displayManager = carContext.getSystemService(DisplayManager::class.java)
        val display = displayManager.createVirtualDisplay(
            "parking-blues-map",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surfaceContainer.surface,
            0,
        )
        virtualDisplay = display

        val newPresentation = Presentation(carContext, display.display)
        val newMapView = MapView(newPresentation.context)
        newPresentation.setContentView(newMapView)
        mapView = newMapView
        presentation = newPresentation

        newMapView.onCreate(Bundle())
        newMapView.onResume()
        newPresentation.show()

        newMapView.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isMapToolbarEnabled = false
            // Declutters the default style: hides restaurant/shop/transit
            // icons that otherwise bury the parking pins -- see
            // res/raw/map_style.json. Keeps street labels and park/water
            // fill for orientation.
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(carContext, R.raw.map_style))
            repository.session.value?.let { renderMarkers(it) }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Keep pins clear of whatever the host draws on top of the map
        // (e.g. the list pane, status bar) -- without this, markers under
        // the content template would be there but unreachable/obscured.
        googleMap?.setPadding(
            visibleArea.left,
            visibleArea.top,
            mapView?.width?.minus(visibleArea.right) ?: 0,
            mapView?.height?.minus(visibleArea.bottom) ?: 0,
        )
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        isFollowingUser = false
        googleMap?.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        isFollowingUser = false
        googleMap?.moveCamera(CameraUpdateFactory.zoomBy(scaleFactor - 1f, android.graphics.Point(focusX.toInt(), focusY.toInt())))
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mapView?.onPause()
        mapView?.onDestroy()
        mapView = null
        googleMap = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // -- Marker rendering, mirroring web/app.js's map: you, destination,
    //    active candidates (current + upcoming, no distinction drawn
    //    between them -- see the class doc), dimmed rejected spots --

    private val markers = mutableListOf<Marker>()

    private fun renderMarkers(snapshot: SessionSnapshot) {
        val map = googleMap ?: return
        markers.forEach { it.remove() }
        markers.clear()

        val bearing = lastBearing
        markers += map.addMarker(
            MarkerOptions()
                .position(LatLng(snapshot.you.lat, snapshot.you.lon))
                .title("You")
                .apply {
                    if (bearing != null) {
                        icon(youMarkerIconWithHeading)
                        anchor(youWithHeadingAnchorX, youWithHeadingAnchorY)
                        rotation(bearing)
                        flat(true) // heading indicator stays map-relative, not screen-relative
                    } else {
                        icon(youMarkerIcon) // no known heading (stationary, or the fixed test point) -- plain dot
                    }
                }
                .zIndex(10f) // stays visibly on top of candidate dots
        )!!
        markers += map.addMarker(
            MarkerOptions()
                .position(LatLng(snapshot.origin.lat, snapshot.origin.lon))
                .title("Destination")
                .icon(destinationMarkerIcon)
                .zIndex(10f)
        )!!

        snapshot.rejected.forEach { segment ->
            markers += map.addMarker(
                MarkerOptions()
                    .position(LatLng(segment.lat, segment.lon))
                    .title(segment.addressLabel)
                    .alpha(0.4f) // dimmed -- "checked, not available" (web MVP parity)
                    .icon(rejectedMarkerIcon)
            )!!
        }
        activeCandidates(snapshot).forEach { segment ->
            markers += map.addMarker(
                MarkerOptions()
                    .position(LatLng(segment.lat, segment.lon))
                    .title(segment.addressLabel)
                    .icon(zoneMarker(segment))
            )!!
        }

        if (isFollowingUser) {
            val you = LatLng(snapshot.you.lat, snapshot.you.lon)
            val update = if (!hasFramedInitialCamera) {
                // Fit you + every active candidate in view on the first
                // frame, rather than guessing a fixed zoom level -- a
                // hardcoded zoom either clips a wide cluster or, per direct
                // feedback, leaves everything zoomed in too tight to see.
                val bounds = LatLngBounds.Builder().include(you).apply {
                    activeCandidates(snapshot).forEach { include(LatLng(it.lat, it.lon)) }
                }.build()
                CameraUpdateFactory.newLatLngBounds(bounds, dpToPx(24))
            } else {
                CameraUpdateFactory.newLatLng(you)
            }
            hasFramedInitialCamera = true
            map.animateCamera(update)
        }
    }

    /** current + upcoming as one undistinguished list -- see the class doc
     *  on why the UI no longer calls out a single "target" spot. */
    private fun activeCandidates(snapshot: SessionSnapshot): List<ParkingSegment> =
        listOfNotNull(snapshot.current) + snapshot.upcoming

    private fun zoneMarker(segment: ParkingSegment) =
        if (segment.zoneType == ZoneType.BLUE) blueZoneMarkerIcon else whiteZoneMarkerIcon

    // -- Marker icons: small solid circles with a white ring, not the
    //    stock balloon-shaped default pins (Google Maps' oldest, least
    //    modern-looking asset). Built once and cached -- there are only a
    //    handful of distinct looks needed, not one per marker instance.
    //    Sized 4x the original prototype size per direct feedback that the
    //    first pass was too small to read at a glance while driving.

    private val youMarkerIcon by lazy { circleMarkerIcon(colorInt = COLOR_YOU, diameterDp = YOU_DIAMETER_DP) }
    private val youMarkerIconWithHeading by lazy {
        circleWithHeadingIcon(colorInt = COLOR_YOU, diameterDp = YOU_DIAMETER_DP)
    }
    private val destinationMarkerIcon by lazy {
        circleMarkerIcon(colorInt = COLOR_DESTINATION, diameterDp = YOU_DIAMETER_DP)
    }
    private val blueZoneMarkerIcon by lazy { circleMarkerIcon(colorInt = COLOR_BLUE_ZONE, diameterDp = CANDIDATE_DIAMETER_DP) }
    private val whiteZoneMarkerIcon by lazy { circleMarkerIcon(colorInt = COLOR_WHITE_ZONE, diameterDp = CANDIDATE_DIAMETER_DP) }
    private val rejectedMarkerIcon by lazy { circleMarkerIcon(colorInt = COLOR_REJECTED, diameterDp = CANDIDATE_DIAMETER_DP) }

    // Where the actual GPS point sits within youMarkerIconWithHeading's
    // taller canvas (circle at the bottom, chevron above it) -- computed
    // from the same ratio circleWithHeadingIcon() draws with, so Maps
    // rotates the bitmap around the driver's real position, not its own
    // geometric center. See MarkerOptions.anchor() usage in renderMarkers.
    private val youWithHeadingAnchorX = 0.5f
    private val youWithHeadingAnchorY =
        (0.5f + HEADING_CHEVRON_HEIGHT_RATIO) / (1f + HEADING_CHEVRON_HEIGHT_RATIO)

    private fun circleMarkerIcon(
        @ColorInt colorInt: Int,
        diameterDp: Int,
    ): BitmapDescriptor {
        val diameterPx = dpToPx(diameterDp)
        val strokePx = diameterPx * STROKE_RATIO
        val bitmap = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = diameterPx / 2f
        canvas.drawCircle(radius, radius, radius - strokePx / 2, fillPaint(colorInt))
        canvas.drawCircle(radius, radius, radius - strokePx / 2, strokePaint(strokePx))
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /** Same circle, plus a chevron above it pointing "up" -- MarkerOptions.rotation()
     *  then turns the whole bitmap to match the driver's actual heading. */
    private fun circleWithHeadingIcon(
        @ColorInt colorInt: Int,
        diameterDp: Int,
    ): BitmapDescriptor {
        val diameterPx = dpToPx(diameterDp)
        val chevronHeightPx = diameterPx * HEADING_CHEVRON_HEIGHT_RATIO
        val strokePx = diameterPx * STROKE_RATIO
        val width = diameterPx
        val height = diameterPx + chevronHeightPx
        val bitmap = Bitmap.createBitmap(width, height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = diameterPx / 2f
        val circleCenterY = height - radius

        val chevron = android.graphics.Path().apply {
            moveTo(width / 2f, 0f)
            lineTo(width * 0.82f, chevronHeightPx * 0.75f)
            lineTo(width * 0.18f, chevronHeightPx * 0.75f)
            close()
        }
        canvas.drawPath(chevron, fillPaint(colorInt))
        canvas.drawCircle(radius, circleCenterY, radius - strokePx / 2, fillPaint(colorInt))
        canvas.drawCircle(radius, circleCenterY, radius - strokePx / 2, strokePaint(strokePx))
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * carContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun fillPaint(@ColorInt colorInt: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }

    // A thin, dark, semi-transparent ring rather than the original solid
    // white one -- against this light/decluttered basemap a white ring
    // washed the dots out (direct feedback) instead of adding contrast.
    private fun strokePaint(strokePx: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

    private companion object {
        // A restrained, modern palette instead of Maps' default hue wheel --
        // "you" and "blue zone" both read as blue-ish by nature (the zone
        // name isn't arbitrary), so they're kept visually distinct by
        // saturation/darkness rather than a completely different hue.
        @ColorInt val COLOR_YOU = Color.parseColor("#00B8D4")
        @ColorInt val COLOR_DESTINATION = Color.parseColor("#7C4DFF")
        @ColorInt val COLOR_BLUE_ZONE = Color.parseColor("#1E88E5")
        @ColorInt val COLOR_WHITE_ZONE = Color.parseColor("#37474F")
        @ColorInt val COLOR_REJECTED = Color.parseColor("#9E9E9E")

        const val YOU_DIAMETER_DP = 64 // 16dp original prototype x4
        const val CANDIDATE_DIAMETER_DP = 48 // 12dp original prototype x4
        const val HEADING_CHEVRON_HEIGHT_RATIO = 0.5f
        // Was 0.18 (an 18%-of-diameter solid white ring) -- thinned out
        // alongside the color change above; that combination is what was
        // washing the dots out, not just the color alone.
        const val STROKE_RATIO = 0.06f
    }

    // -- Template: our map as the background, the ranked list as content --

    // MapWithContentTemplate/MapController (androidx.car.app.navigation.model)
    // are @ExperimentalCarApi in Car App Library 1.4.0. Opted in deliberately
    // as a prototype (see android/README.md); re-check this API's status
    // before relying on it in anything shipped.
    @OptIn(markerClass = [ExperimentalCarApi::class])
    override fun onGetTemplate(): Template {
        val snapshot = repository.session.value ?: return errorOrEmptyTemplate()

        if (snapshot.state == SessionState.EXHAUSTED) {
            return MessageTemplate.Builder(
                "No more candidates nearby, even after widening the search radius."
            ).setHeaderAction(Action.BACK).build()
        }

        return MapWithContentTemplate.Builder()
            .setContentTemplate(listContentTemplate(snapshot))
            .setMapController(MapController.Builder().build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Recenter")
                            .setOnClickListener {
                                isFollowingUser = true
                                repository.session.value?.let { snap ->
                                    googleMap?.animateCamera(
                                        CameraUpdateFactory.newLatLng(LatLng(snap.you.lat, snap.you.lon))
                                    )
                                }
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun listContentTemplate(snapshot: SessionSnapshot): Template {
        val items = ItemList.Builder()
        for (segment in activeCandidates(snapshot)) {
            items.addItem(compactRow(segment))
        }
        return ListTemplate.Builder()
            .setTitle("Parking Blues")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    /**
     * One line per candidate -- no spot ID (the ingested data's "address"
     * is just an internal database number, meaningless to a driver -- see
     * android/README.md) and no rank/target distinction (see class doc).
     * Just enough to glance at and compare against what's on the map.
     */
    private fun compactRow(segment: ParkingSegment): Row {
        val zoneLabel = if (segment.zoneType == ZoneType.BLUE) "Blue" else "White"
        val distanceM = segment.distanceFromYouM.toInt()
        return Row.Builder()
            .setTitle("$zoneLabel · $distanceM m${detailText(segment)}")
            .build()
    }

    private fun detailText(segment: ParkingSegment): String {
        if (segment.zoneType == ZoneType.BLUE) {
            val clockTime = segment.legalUntil?.let { it.substring(11, 16) }
            return clockTime?.let { " · until ~$it" } ?: " · no time limit"
        }
        val cap = segment.maxDurationMinutes?.let { "$it min" } ?: "no fixed limit"
        val rate = segment.estimatedFeeChfPerHour
        return if (rate != null) " · $cap · ~CHF ${"%.2f".format(rate)}/h" else " · $cap"
    }

    private fun errorOrEmptyTemplate(): Template {
        val message = repository.error.value ?: "No active search."
        val builder = MessageTemplate.Builder(message).setHeaderAction(Action.BACK)
        if (repository.error.value != null && retryZone != null && retryLat != null && retryLon != null) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener {
                        lifecycleScope.launch { repository.startSearch(retryLat, retryLon, retryZone) }
                    }
                    .build()
            )
        }
        return builder.build()
    }
}
