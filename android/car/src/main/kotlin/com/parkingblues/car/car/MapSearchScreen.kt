package com.parkingblues.car.car

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
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
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.lifecycleScope
import com.parkingblues.car.R
import com.parkingblues.car.location.locationUpdates
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.ParkingSegment
import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.SessionState
import com.parkingblues.shared.model.ZoneFilter
import com.parkingblues.shared.model.ZoneType
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prototype: a self-drawn map (osmdroid, via AppManager's SurfaceCallback +
 * a VirtualDisplay/Presentation hosting a MapView) instead of
 * PlaceListMapTemplate's host-rendered one. See android/README.md for why
 * this is a separate module concern (ACCESS_SURFACE permission,
 * MapWithContentTemplate living in androidx.car.app.navigation.model despite
 * being POI-app-eligible).
 *
 * osmdroid, not Google's Maps SDK for Android: the latter renders through
 * Play Services' "Dynamite" module loader (maps_dynamite), and confirmed via
 * live logcat on both the Play-Store and Google-APIs Android Automotive OS
 * emulator tiers -- signed into a real Google account on the former -- that
 * Play's backend serves other dynamite modules fine (googlecertificates
 * loads successfully) but returns "Unknown dynamite feature" specifically
 * for maps_dynamite/maps_core_dynamite. That's a server-side eligibility
 * gap for this device class, not a local config problem, so it can't be
 * fixed from here. osmdroid fetches raster tiles over plain HTTP and draws
 * them with Android's own Canvas -- no Play Services/Dynamite involved at
 * all, so it renders identically on every emulator tier and real hardware.
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
    private var hasFramedInitialCamera = false

    // Set in onVisibleAreaChanged: the host's own rect for what part of the
    // surface isn't covered by the content pane/status bar. osmdroid has no
    // built-in "camera padding" concept the way GoogleMap did (and that one
    // didn't even work reliably -- newLatLng() ignored it), so this is used
    // purely by our own recenterTarget() below to manually compensate.
    private var visibleArea = Rect(0, 0, 0, 0)

    // The map follows the driver's live position by default (this is meant
    // to be glanced at while driving, not manually wrangled) but a manual
    // pan/zoom pauses that until the driver taps Recenter -- otherwise
    // every ~2s position update would yank any manual look-around back.
    private var isFollowingUser = true

    // True only while the host says the driver is actively panning the map
    // (PanModeListener, wired up via MapController below) -- see onScroll's
    // doc for why this check exists at all.
    private var isInPanMode = false

    // Heading for the "you" marker's direction indicator. This is a pure
    // rendering concern, not something the backend needs (SessionSnapshot
    // has no bearing field), so it's tracked from a separate, local GPS
    // subscription rather than plumbed through ParkingSessionRepository.
    // Null when unavailable (device stationary, or the fixed test-location
    // point, which has no meaningful heading) -- shown as a plain dot then.
  private var lastBearing: Float? = null
  private var lastSummaryTitle: String? = null
  private var lastState: SessionState? = null

  init {
    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    lifecycleScope.launch {
      repository.session.collect { snapshot ->
        if (snapshot != null) {
          val title = computeSummaryTitle(snapshot)
          if (title != lastSummaryTitle || snapshot.state != lastState) {
            lastSummaryTitle = title
            lastState = snapshot.state
            invalidate()
          }
          renderMarkers(snapshot)
        } else {
          invalidate()
        }
      }
    }
    lifecycleScope.launch {
      repository.error.collect { invalidate() }
    }
    lifecycleScope.launch {
      repository.bearing.collect { bearing ->
        if (bearing != null) {
          lastBearing = bearing
          repository.session.value?.let { renderMarkers(it) }
        }
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
            // The Google Maps SDK version of this screen floored this at a
            // higher value (MAP_RENDER_DPI) because Desktop Head Unit
            // hardcodes "dpi = 160" regardless of resolution and Maps' own
            // density-aware rendering went soft at that reported density.
            // osmdroid's tiles are plain fixed-size raster bitmaps with no
            // such density-aware rendering step -- confirmed live that
            // flooring this AND setting isTilesScaledToDpi (below) actively
            // caused blur instead of fixing it: that flag stretches each
            // 256px tile bitmap to match a higher reported density instead
            // of fetching sharper source tiles, so a floored/inflated
            // density just blows the same pixels up bigger. Passing the
            // host's own reported density straight through avoids that.
            surfaceContainer.dpi,
            surfaceContainer.surface,
            0,
        )
        virtualDisplay = display

        val newPresentation = Presentation(carContext, display.display)
        val initialCenter = this@MapSearchScreen.repository.session.value?.you
        val centerLat = retryLat ?: initialCenter?.lat ?: 47.379198
        val centerLon = retryLon ?: initialCenter?.lon ?: 8.531307
        val newMapView = MapView(newPresentation.context).apply {
            setTileSource(positronTileSource)
            setMultiTouchControls(false)
            setBuiltInZoomControls(false)
            minZoomLevel = 13.0
            maxZoomLevel = 20.0
            controller.setZoom(17.5)
            controller.setCenter(GeoPoint(centerLat, centerLon))
        }
        newPresentation.setContentView(newMapView)
        mapView = newMapView
        presentation = newPresentation
        newMapView.onResume()
        newPresentation.show()

        repository.session.value?.let { renderMarkers(it) }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        if (isFollowingUser) {
            repository.session.value?.let { snapshot ->
                val you = GeoPoint(snapshot.you.lat, snapshot.you.lon)
                mapView?.controller?.setCenter(recenterTarget(you))
            }
        }
    }

    /**
     * Where to tell the camera to center so "you" actually lands in the
     * middle of the *visible* area (not obscured by the content pane).
     * Computed via a screen <-> GeoPoint round-trip at the current zoom
     * rather than a fixed camera update type, so it doesn't fight ongoing
     * follow-mode panning by also forcing a zoom change.
     */
    private fun recenterTarget(you: GeoPoint): GeoPoint {
        val map = mapView ?: return you
        if (map.width == 0 || map.height == 0) return you
        if (visibleArea.width() <= 0 || visibleArea.height() <= 0) return you
        val projection = map.projection
        val youPx = projection.toPixels(you, null)
        val fullCenterX = map.width / 2
        val fullCenterY = map.height / 2
        val visibleCenterX = (visibleArea.left + visibleArea.right) / 2
        val visibleCenterY = (visibleArea.top + visibleArea.bottom) / 2
        val targetX = youPx.x + (fullCenterX - visibleCenterX)
        val targetY = youPx.y + (fullCenterY - visibleCenterY)
        val target = projection.fromPixels(targetX, targetY)
        return GeoPoint(target.latitude, target.longitude)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        // NOT gated on isInPanMode -- tried that (see git history) to stop
        // list-drags from also dragging the map underneath, but Desktop
        // Head Unit's mouse-drag simulation apparently never triggers the
        // host's real pan-mode signal, which made the map undraggable
        // entirely -- a worse regression than the bug it was meant to fix.
        // Reverted until this can be confirmed on real touchscreen hardware
        // (where the host may gate this correctly on its own, since DHU's
        // mouse input may simply not be representative here).
        android.util.Log.d(
            "MapSearchScreen",
            "onScroll distanceX=$distanceX distanceY=$distanceY isInPanMode=$isInPanMode"
        )
        isFollowingUser = false
        // FIXME if this is caused by scroll wheel up/down, this should zoom in the map, not scroll it
        mapView?.controller?.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        isFollowingUser = false
        val map = mapView ?: return
        // Focus-anchored zoom: osmdroid's setZoom() keeps the map's current
        // center fixed, not the pinch/scroll focus point, so compensate by
        // measuring how far the geo-point under the focus drifted on screen
        // after the zoom change and scrolling that back out -- the same
        // screen<->geo round-trip technique as recenterTarget above.
        val before = map.projection.fromPixels(focusX.toInt(), focusY.toInt())
        val newZoom = map.zoomLevelDouble + ln(scaleFactor.toDouble()) / ln(2.0)
        map.controller.setZoom(newZoom)
        val afterPx = map.projection.toPixels(GeoPoint(before.latitude, before.longitude), null)
        map.controller.scrollBy(afterPx.x - focusX.toInt(), afterPx.y - focusY.toInt())
    }

    // Temporary diagnostics: confirming whether the AAOS emulator host
    // calls any of these at all during a drag (the user reports dragging
    // doesn't move the map there) -- remove once that's established.
    override fun onFling(velocityX: Float, velocityY: Float) {
        android.util.Log.d("MapSearchScreen", "onFling velocityX=$velocityX velocityY=$velocityY")
    }

    override fun onClick(x: Float, y: Float) {
        android.util.Log.d("MapSearchScreen", "onClick x=$x y=$y")
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mapView?.onPause()
        mapView?.onDetach()
        mapView = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // -- Marker rendering, mirroring web/app.js's map: you, destination,
    //    active candidates (current + upcoming, no distinction drawn
    //    between them -- see the class doc), dimmed rejected spots --

    private val markers = mutableListOf<Marker>()
    private val polygons = mutableListOf<Polygon>()

    private fun renderMarkers(snapshot: SessionSnapshot) {
        val map = mapView ?: return
        map.overlays.removeAll(markers)
        markers.clear()
        map.overlays.removeAll(polygons)
        polygons.clear()


    // Visited/checked spots grouped into greyed-out zone outlines.
    // Parking icons are removed for visited zones; icons only show for unvisited zones.
    clusterByZone(snapshot.rejected).forEach { cluster ->
      polygons += Polygon(map).apply {
        points = orientedBoxCorners(cluster.segments)
        fillColor = COLOR_REJECTED_ZONE_FILL
        strokeColor = COLOR_REJECTED_ZONE_STROKE
        strokeWidth = 2.5f
      }.also { map.overlays.add(it) }
    }

    // Every active candidate, not a capped "nearest N" -- tried
    // capping this (see git history) when a dense result set first
    // looked like an overwhelming pile of overlapping pins, but that
    // was actually the oversized-marker bug (fixed separately), not a
    // real problem with showing the full set; capping was never asked
    // for and just hid real data. Nearby same-zone spots are grouped
    // into one box+icon instead of one pin per spot (see clusterByZone).
    val activeClusters = clusterByZone(activeCandidates(snapshot))
    activeClusters.forEach { cluster ->
      polygons += Polygon(map).apply {
        points = orientedBoxCorners(cluster.segments)
        fillColor = zoneFillColor(cluster.zoneType)
        strokeColor = ZONE_ACCENT
        strokeWidth = 3f
      }.also { map.overlays.add(it) }
    }

    activeClusters.forEach { cluster ->
      markers += Marker(map).apply {
        position = centroidLatLng(cluster.segments)
        title = clusterTitle(cluster)
        icon = zoneIcon(cluster.zoneType)
        setAnchor(0.5f, 0.5f)
        setInfoWindow(null)
      }.also { map.overlays.add(it) }
    }

    // Destination marker drawn above parking zones so it is never obscured
    markers += Marker(map).apply {
      position = GeoPoint(snapshot.origin.lat, snapshot.origin.lon)
      title = "Destination"
      icon = destinationMarkerIcon
      setAnchor(DESTINATION_ANCHOR_X, DESTINATION_ANCHOR_Y) // the pin's tip, not its bounding-box center
      setInfoWindow(null)
    }.also { map.overlays.add(it) }

    // "You" marker drawn LAST so it is always on top of all other markers and zones
    val currentBearing = repository.bearing.value ?: lastBearing ?: 0f
    markers += Marker(map).apply {
      position = GeoPoint(snapshot.you.lat, snapshot.you.lon)
      title = "You"
      icon = youMarkerIcon
      setAnchor(0.5f, 0.5f)
      rotation = -currentBearing // osmdroid Marker.draw applies -mBearing to canvas rotate
      setFlat(true) // stays map-relative, not screen-relative
      setInfoWindow(null)
    }.also { map.overlays.add(it) }

    if (isFollowingUser) {
      val you = GeoPoint(snapshot.you.lat, snapshot.you.lon)
      if (!hasFramedInitialCamera) {
        val frameCamera = {
          if (map.width > 0 && map.height > 0) {
            hasFramedInitialCamera = true
            map.zoomToBoundingBox(boundsForRadius(you, snapshot.radiusM), false)
            map.controller.setCenter(recenterTarget(you))
          }
        }
        if (map.width > 0 && map.height > 0) {
          map.post { frameCamera() }
        } else {
          map.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(
              v: android.view.View?,
              left: Int, top: Int, right: Int, bottom: Int,
              oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
              if ((right - left) > 0 && (bottom - top) > 0) {
                map.removeOnLayoutChangeListener(this)
                map.post { frameCamera() }
              }
            }
          })
        }
      } else {
        map.controller.animateTo(recenterTarget(you))
      }
    }
        map.invalidate()
    }

    private fun boundsForRadius(center: GeoPoint, radiusM: Double): BoundingBox {
        val latDeltaDeg = radiusM / METERS_PER_DEGREE_LAT
        val lonDeltaDeg = radiusM / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
        return BoundingBox(
            center.latitude + latDeltaDeg, // north
            center.longitude + lonDeltaDeg, // east
            center.latitude - latDeltaDeg, // south
            center.longitude - lonDeltaDeg, // west
        )
    }

  private fun activeCandidates(snapshot: SessionSnapshot): List<ParkingSegment> {
    val rejectedIds = snapshot.rejected.map { it.id }.toSet()
    return (listOfNotNull(snapshot.current) + snapshot.upcoming).filter { it.id !in rejectedIds }
  }

    // -- Clustering: nearby same-zone spots become one box+icon instead of
    //    one pin per spot (per direct feedback that individual circles,
    //    even at a sane size and count, don't read well at a glance -- the
    //    actual real-Zurich-street-block shape reads better as one
    //    grouped box than as N separate dots along a curb). --

    private data class SpotCluster(val zoneType: ZoneType, val segments: List<ParkingSegment>)

    private fun clusterByZone(candidates: List<ParkingSegment>): List<SpotCluster> =
        candidates.groupBy { it.zoneType }.flatMap { (zone, segments) ->
            clusterByProximity(segments).map { SpotCluster(zone, it) }
        }

    /**
     * Single-linkage grouping (union-find) of segments within
     * CLUSTER_EPS_M of each other -- chains of nearby segments merge
     * transitively, mirroring how backend/clustering.py groups segments
     * server-side, just applied here purely for how spots are drawn, not
     * for ranking/selection (that stays entirely server-side, see
     * android/README.md's §5.2 architecture note).
     */
    private fun clusterByProximity(segments: List<ParkingSegment>): List<List<ParkingSegment>> {
        if (segments.size <= 1) return listOf(segments)
        val parent = IntArray(segments.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                if (haversineMeters(segments[i], segments[j]) <= CLUSTER_EPS_M) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }
        return segments.indices.groupBy(::find).values.map { idxs -> idxs.map { segments[it] } }
    }

    private fun haversineMeters(a: ParkingSegment, b: ParkingSegment): Double {
        val earthRadiusM = 6_371_000.0
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earthRadiusM * asin(sqrt(h))
    }

    private fun centroidLatLng(segments: List<ParkingSegment>): GeoPoint =
        GeoPoint(segments.map { it.lat }.average(), segments.map { it.lon }.average())

    private fun clusterTitle(cluster: SpotCluster): String {
        val zoneLabel = if (cluster.zoneType == ZoneType.BLUE) "Blue zone" else "White zone"
        val count = cluster.segments.size
        return "$zoneLabel · $count spot${if (count == 1) "" else "s"}"
    }

    /**
     * A rectangle aligned with the cluster's own spread direction (the two
     * most-distant segments in it), not a plain north-aligned box --
     * parking segments run along streets, which are rarely north-south/
     * east-west, so a plain axis-aligned box would either clip a diagonal
     * row of spots or be far larger than the actual curb it represents.
     * Projected onto the map this renders as a parallelogram whenever the
     * street itself isn't axis-aligned, which is most of the time.
     *
     * Uses a flat local-meters approximation around the cluster's own
     * centroid; fine at the scale these clusters actually span (tens of
     * meters), not meant for anything larger.
     */
    private fun orientedBoxCorners(segments: List<ParkingSegment>): List<GeoPoint> {
        val center = centroidLatLng(segments)
        val metersPerDegLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)

        fun toLocal(segment: ParkingSegment): Pair<Double, Double> =
            (segment.lon - center.longitude) * metersPerDegLon to (segment.lat - center.latitude) * METERS_PER_DEGREE_LAT

        val points = segments.map(::toLocal)

        var axisX = 0.0
        var axisY = 1.0
        if (points.size > 1) {
            var maxDist = -1.0
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    val dx = points[j].first - points[i].first
                    val dy = points[j].second - points[i].second
                    val dist = hypot(dx, dy)
                    if (dist > maxDist) {
                        maxDist = dist
                        if (dist > 0) {
                            axisX = dx / dist
                            axisY = dy / dist
                        }
                    }
                }
            }
        }
        val perpX = -axisY
        val perpY = axisX

        var minAlong = 0.0
        var maxAlong = 0.0
        var minAcross = 0.0
        var maxAcross = 0.0
        points.forEachIndexed { i, (x, y) ->
            val along = x * axisX + y * axisY
            val across = x * perpX + y * perpY
            if (i == 0) {
                minAlong = along; maxAlong = along
                minAcross = across; maxAcross = across
            } else {
                minAlong = minOf(minAlong, along); maxAlong = maxOf(maxAlong, along)
                minAcross = minOf(minAcross, across); maxAcross = maxOf(maxAcross, across)
            }
        }
        minAlong -= BOX_PAD_ALONG_M; maxAlong += BOX_PAD_ALONG_M
        minAcross -= BOX_PAD_ACROSS_M; maxAcross += BOX_PAD_ACROSS_M

        fun corner(along: Double, across: Double): GeoPoint {
            val localX = axisX * along + perpX * across
            val localY = axisY * along + perpY * across
            return GeoPoint(
                center.latitude + localY / METERS_PER_DEGREE_LAT,
                center.longitude + localX / metersPerDegLon,
            )
        }
        return listOf(
            corner(minAlong, minAcross),
            corner(maxAlong, minAcross),
            corner(maxAlong, maxAcross),
            corner(minAlong, maxAcross),
        )
    }

    // osmdroid markers take a plain Drawable -- no BitmapDescriptorFactory-
    // style rasterization step needed (unlike the Google Maps SDK version
    // of this file, which had to manually render vector drawables to a
    // Bitmap first since BitmapDescriptorFactory.fromResource() silently
    // doesn't actually do that). Just bound it to the size we want.
    private val blueZoneIcon by lazy { vectorDrawableIcon(R.drawable.ic_zone_blue, ZONE_ICON_DP) }
    private val whiteZoneIcon by lazy { vectorDrawableIcon(R.drawable.ic_zone_white, ZONE_ICON_DP) }

    private val rejectedBlueZoneIcon by lazy {
        vectorDrawableIcon(R.drawable.ic_zone_blue, ZONE_ICON_DP).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(COLOR_REJECTED, android.graphics.PorterDuff.Mode.SRC_IN)
        }
    }
    private val rejectedWhiteZoneIcon by lazy {
        vectorDrawableIcon(R.drawable.ic_zone_white, ZONE_ICON_DP).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(COLOR_REJECTED, android.graphics.PorterDuff.Mode.SRC_IN)
        }
    }

    private fun vectorDrawableIcon(@DrawableRes resId: Int, sizeDp: Int): Drawable {
        val drawable = ContextCompat.getDrawable(carContext, resId)!!.mutate()
        val sizePx = dpToPx(sizeDp)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return BitmapDrawable(carContext.resources, bitmap).apply {
            setBounds(0, 0, sizePx, sizePx)
        }
    }

    private fun zoneIcon(zoneType: ZoneType) =
        if (zoneType == ZoneType.BLUE) blueZoneIcon else whiteZoneIcon

    private fun rejectedZoneIcon(zoneType: ZoneType) =
        if (zoneType == ZoneType.BLUE) rejectedBlueZoneIcon else rejectedWhiteZoneIcon

    private fun zoneFillColor(zoneType: ZoneType) =
        if (zoneType == ZoneType.BLUE) {
            Color.argb(90, Color.red(ZONE_ACCENT), Color.green(ZONE_ACCENT), Color.blue(ZONE_ACCENT))
        } else {
            Color.argb(90, 255, 255, 255)
        }

  // Purple triangle with rounded corners rotated via Marker.rotation to indicate position and heading
  private val youMarkerIcon by lazy { vectorDrawableIcon(R.drawable.ic_location_triangle, CAR_ICON_DP) }

    // assets/destination.svg's pin (see ic_destination.xml's own comment
    // on why only the pin, not the "Destination" word also in that file).
    private val destinationMarkerIcon by lazy { vectorDrawableIcon(R.drawable.ic_destination, DESTINATION_ICON_DP) }

    private val rejectedMarkerIcon by lazy {
        circleMarkerIcon(
            colorInt = COLOR_REJECTED,
            diameterDp = CANDIDATE_DIAMETER_DP
        )
    }

    private fun circleMarkerIcon(
        @ColorInt colorInt: Int,
        diameterDp: Int,
    ): Drawable {
        val diameterPx = dpToPx(diameterDp)
        val strokePx = diameterPx * STROKE_RATIO
        val bitmap = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = diameterPx / 2f
        canvas.drawCircle(radius, radius, radius - strokePx / 2, fillPaint(colorInt))
        canvas.drawCircle(radius, radius, radius - strokePx / 2, strokePaint(strokePx))
        return BitmapDrawable(carContext.resources, bitmap).apply { setBounds(0, 0, diameterPx, diameterPx) }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * carContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun fillPaint(@ColorInt colorInt: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }

    private fun strokePaint(strokePx: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

    private companion object {
        // Rough meters-per-degree-latitude at any latitude (longitude's
        // equivalent varies with latitude -- see boundsForRadius, which
        // accounts for that via cos(latitude)).
        const val METERS_PER_DEGREE_LAT = 111_320.0

        // A restrained, modern palette instead of a default hue wheel.
        @ColorInt
        val COLOR_YOU = Color.parseColor("#9C27B0")
        @ColorInt
        val COLOR_REJECTED = Color.parseColor("#9E9E9E")
        @ColorInt
        val COLOR_REJECTED_ZONE_FILL = Color.argb(45, 150, 150, 150)
        @ColorInt
        val COLOR_REJECTED_ZONE_STROKE = Color.parseColor("#9E9E9E")

        // The blue used by both assets/blue-zone.svg and assets/white-zone.svg
        // (as its fill and border respectively) -- reused for the cluster
        // box stroke/fill so the boxes visually match their icon rather
        // than introducing a separate, unrelated color scheme.
        @ColorInt
        val ZONE_ACCENT = Color.parseColor("#268BCC")

        const val CAR_ICON_DP = 84
        const val CANDIDATE_DIAMETER_DP = 48 // 12dp original prototype x4
        const val ZONE_ICON_DP = 48 // Keep parking icons at their original 48dp size
        const val DESTINATION_ICON_DP = 80 // Doubled from original 40dp XML intrinsic size

        // The pin's tip in assets/destination.svg, as a fraction of its
        // 474x474 viewBox (computed directly from the source path's own
        // coordinates + translate, not eyeballed) -- so the marker's
        // anchor is the actual pin tip, not its bounding-box center,
        // matching how a map pin is meant to indicate a precise point.
        const val DESTINATION_ANCHOR_X = 0.5f
        const val DESTINATION_ANCHOR_Y = 0.966f

        // Was 0.18 (an 18%-of-diameter solid white ring) -- thinned out
        // alongside the color change above; that combination is what was
        // washing the dots out, not just the color alone.
        const val STROKE_RATIO = 0.06f

        // Single-linkage clustering distance for grouping nearby same-zone
        // spots into one box (see clusterByProximity) -- deliberately
        // tight (only genuinely adjacent stalls merge), not a "loosely on
        // the same street" threshold. Chains still let a dense unbroken
        // run form one large box; a single >EPS gap splits it. 3m was
        // tried first and never merged anything at all -- individual
        // stall records here are apparently spaced ~5-6m center-to-center
        // (roughly one car length), so 3m couldn't bridge even genuinely
        // adjacent ones. Tuned up from there by direct feedback (8 -> 10m).
        const val CLUSTER_EPS_M = 10.0

        // Padding beyond the outermost segment centers, so a box reads as
        // a real curb strip rather than a line drawn exactly through the
        // dots -- more padding along the row's own direction than across
        // it, since real curb segments are long and narrow.
        const val BOX_PAD_ALONG_M = 6.0
        const val BOX_PAD_ACROSS_M = 4.0
        const val NEARBY_RADIUS_M = 300.0
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
            // A content template isn't optional here -- tried omitting it
            // (see git history), which throws IllegalStateException:
            // "Template is in a loading state but content is set, or vice
            // versa" at build() time. MapWithContentTemplate requires
            // either real content or an explicit setLoading(true); there's
            // no bare "just the map" mode.
            .setContentTemplate(summaryContentTemplate(snapshot))
            .setMapController(
                MapController.Builder()
                    .setPanModeListener { inPanMode ->
                        android.util.Log.d("MapSearchScreen", "panModeListener inPanMode=$inPanMode")
                        isInPanMode = inPanMode
                    }
                    // Required for the host to deliver ANY SurfaceCallback
                    // touch input at all (onClick/onScroll/onScale/onFling
                    // all stayed silent without this, confirmed live) --
                    // undocumented in-code, but per
                    // https://developer.android.com/training/cars/apps/library/interact-map:
                    // omitting Action.PAN from the map's own action strip
                    // means "the host closes any previously activated pan
                    // mode" and the app gets no surface input at all. This
                    // is a SEPARATE action strip from setActionStrip below
                    // (that one is the map's overlay buttons like Recenter;
                    // this one specifically un-gates touch delivery).
                    .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
                    .build()
            )
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Recenter")
                            .setOnClickListener {
                                isFollowingUser = true
                                repository.session.value?.let { snap ->
                                    val you = GeoPoint(snap.you.lat, snap.you.lon)
                                    mapView?.controller?.animateTo(recenterTarget(you))
                                }
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * Experiment: a one-row PaneTemplate instead of the full ListTemplate,
     * to test whether MapWithContentTemplate's content pane is actually
     * sized to its content or is a fixed-width region regardless of what
     * template occupies it (undocumented; PlaceListMapTemplate's split
     * looked fixed too). If the pane doesn't shrink, listContentTemplate()
     * below is the fallback.
     */
  private fun computeSummaryTitle(snapshot: SessionSnapshot): String {
    val active = activeCandidates(snapshot)
    val nearby = active.filter { it.distanceFromYouM <= NEARBY_RADIUS_M }
    val spotCount = nearby.sumOf { it.estimatedCapacity.coerceAtLeast(1) }
    val radiusInt = NEARBY_RADIUS_M.toInt()

    return when (spotCount) {
      0 -> "No parking spaces within ${radiusInt}m"
      1 -> "1 parking space within ${radiusInt}m"
      else -> "$spotCount parking spaces within ${radiusInt}m"
    }
  }

  private fun summaryContentTemplate(snapshot: SessionSnapshot): Template {
    val title = computeSummaryTitle(snapshot)
    val pane = Pane.Builder()
      .addRow(
        Row.Builder()
          .setTitle(title)
          .build()
      )
      .build()
    return PaneTemplate.Builder(pane)
      .setTitle("Parking Blues")
      .setHeaderAction(Action.BACK)
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
