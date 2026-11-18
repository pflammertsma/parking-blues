package dev.lammertsma.parkingblues

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import dev.lammertsma.parkingblues.car.car.TileMath
import dev.lammertsma.parkingblues.car.car.CoverageArea
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import dev.lammertsma.parkingblues.car.location.calculateDistanceMeters
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lammertsma.parkingblues.car.car.MapClustering
import dev.lammertsma.parkingblues.car.car.MapIcons
import dev.lammertsma.parkingblues.car.car.positronTileSource
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import dev.lammertsma.parkingblues.shared.model.ParkedSpot
import dev.lammertsma.parkingblues.shared.model.ParkingRoute
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import dev.lammertsma.parkingblues.car.car.ViewportFit
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * The phone's own live map, not just a "connect to the car" gate screen --
 * reuses MapSearchScreen's clustering/icon logic (MapClustering/MapIcons,
 * extracted out of :car's car-only Screen code specifically so there'd be
 * one implementation, not two that drift) and the same CartoDB Positron
 * tile source.
 *
 * Simpler than the car screen in one real way: this MapView sits in a
 * normal Android view hierarchy and gets real touch events directly, so
 * osmdroid's own built-in pan/pinch-zoom gesture handling
 * (setMultiTouchControls(true)) just works -- none of MapSearchScreen's
 * manual SurfaceCallback onScroll/onScale translation is needed here, that
 * machinery exists only because the car surface is a VirtualDisplay with
 * no real touch delivery of its own.
 *
 * Frames the camera to each new session's search radius, then either
 * follows the user's position (MapUiState.following, the default) or, once
 * the user has dragged the map away, leaves the camera alone so it doesn't
 * fight their pan/zoom on every ~2s position update.
 */
class MapUiState {
    /** Camera tracks the user's position; a deliberate one-finger drag turns it off. */
    var following by mutableStateOf(true)

    /** Current map center, kept up to date as the map moves. */
    var center by mutableStateOf<GeoPoint?>(null)

    /** The device is outside the coverage area: no arrow, nothing to follow. */
    var outsideCoverage by mutableStateOf(false)

    /** True once the map has drawn a tile at its centre, i.e. it is no longer an empty placeholder. */
    var tilesLoaded by mutableStateOf(false)

    /** Set to have the map fit these points (the driver and the car); the map clears it. */
    var fit by mutableStateOf<List<GeoPoint>?>(null)

    /** Set to have the map fly to a place (a list entry was tapped); the map clears it. */
    var focus by mutableStateOf<GeoPoint?>(null)
}

@Composable
fun ParkingMapView(
    repository: ParkingSessionRepository,
    userLocation: Pair<Double, Double>?,
    heading: Float?,
    mapState: MapUiState,
    route: ParkingRoute?,
    parked: ParkedSpot?,
    onDestinationTapped: () -> Unit,
    onDestinationPicked: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinationTapped by rememberUpdatedState(onDestinationTapped)
    val routeOverlays = remember { RouteOverlays() }
    val pickDestination by rememberUpdatedState(onDestinationPicked)
    val context = LocalContext.current
    val session by repository.session.collectAsStateWithLifecycle()
    val currentHeading by rememberUpdatedState(heading)
    val youMarker = remember { arrayOfNulls<Marker>(1) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(positronTileSource)
            setMultiTouchControls(true)
            // 14 rather than 13: at 13 a phone shows more than the whole coverage area is wide.
            minZoomLevel = 14.0
            // Parking exists only in the city: do not let the map be panned away from it.
            setScrollableAreaLimitDouble(
                BoundingBox(CoverageArea.NORTH, CoverageArea.EAST, CoverageArea.SOUTH, CoverageArea.WEST)
            )
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            // Start on Zurich, not on the map's default (0, 0) clamped into the coverage area, which
            // is an arbitrary corner: what a driver sees while there is no location yet.
            controller.setCenter(GeoPoint(CoverageArea.CENTER_LAT, CoverageArea.CENTER_LON))
            // Pinch to zoom; the stock square -/+ buttons clash with the Material UI.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // While a tile has not arrived (or never will: the tile server only covers Zurich),
            // a flat map-coloured background instead of osmdroid's grid of lines.
            overlayManager.tilesOverlay.loadingBackgroundColor = PLACEHOLDER_COLOR
            overlayManager.tilesOverlay.loadingLineColor = PLACEHOLDER_COLOR

            // Double tap zooms in; double-tap-and-drag zooms continuously (see DoubleTapDragZoom).
            val doubleTapZoom = DoubleTapDragZoom(aroundCenter = { mapState.following })
            overlays.add(doubleTapZoom)
            val wheelZoom = MouseWheelZoom()
            overlays.add(wheelZoom)
            setOnGenericMotionListener { _, event -> wheelZoom.handle(event, this) }
            // Long press sets the destination (not while a drag-zoom holds the touch).
            overlays.add(
                LongPressDestination(
                    busy = { doubleTapZoom.active },
                    onPick = { pickDestination(it.latitude, it.longitude) },
                )
            )

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    mapState.center = mapCenter as? GeoPoint
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    mapState.center = mapCenter as? GeoPoint
                    return false
                }
            })

            // Only a single-finger drag counts as "deliberately moved away":
            // programmatic follow-animation also fires onScroll, and pinch-
            // zooming shouldn't drop out of follow mode. Returns false so
            // osmdroid still handles the gesture itself.
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var multiTouch = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        multiTouch = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
                    MotionEvent.ACTION_MOVE ->
                        if (!multiTouch && event.pointerCount == 1 && !doubleTapZoom.active &&
                            hypot(event.x - downX, event.y - downY) > slop
                        ) {
                            mapState.following = false
                        }
                }
                false
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Keyed on sessionId, not a one-shot boolean: switching zones or
    // toggling test location both start a brand new session (no "change
    // zone of an existing session" endpoint -- see MapSearchScreen's doc),
    // which jumps "you" to a wherever the new search actually is. A
    // one-shot flag left the camera stuck on the *first* session's
    // location forever after that -- confirmed live (toggling test
    // location moved the data to Zurich but left the view on the old
    // position, with "you" rendered off-screen).
    var framedForSessionId by remember { mutableStateOf<String?>(null) }
    val markers = remember { mutableListOf<Marker>() }
    val polygons = remember { mutableListOf<Polygon>() }

    // Where the arrow is drawn right now (lat, lon); NaN until the first fix.
    val shown = remember { doubleArrayOf(Double.NaN, Double.NaN) }

    // Glide the arrow (and, in follow mode, the camera) from where it is to
    // each new filtered fix over about one fix interval, frame by frame,
    // instead of hopping once a second.
    LaunchedEffect(userLocation) {
        val target = userLocation ?: return@LaunchedEffect
        var fromLat = if (shown[0].isNaN()) target.first else shown[0]
        var fromLon = if (shown[1].isNaN()) target.second else shown[1]
        if (calculateDistanceMeters(fromLat, fromLon, target.first, target.second) > SNAP_DISTANCE_M) {
            // A big change (test location toggled, long background gap) is a
            // teleport, not a drive.
            fromLat = target.first
            fromLon = target.second
        }
        val startNanos = withFrameNanos { it }
        while (true) {
            val nanos = withFrameNanos { it }
            val t = ((nanos - startNanos) / 1_000_000f / SLIDE_MS).coerceAtMost(1f)
            val lat = fromLat + (target.first - fromLat) * t
            val lon = fromLon + (target.second - fromLon) * t
            shown[0] = lat
            shown[1] = lon
            youMarker[0]?.position = GeoPoint(lat, lon)
            if (mapState.following) mapView.controller.setCenter(GeoPoint(lat, lon))
            mapView.invalidate()
            if (t >= 1f) break
        }
    }

    // Recenter pressed (or following resumed): ease the camera back to the user.
    LaunchedEffect(mapState.following) {
        if (!mapState.following || shown[0].isNaN()) return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(shown[0], shown[1]))
    }

    // Watch for the first real tile so the screen can say "loading" until then.
    LaunchedEffect(mapView) {
        while (!mapState.tilesLoaded) {
            if (centerTileLoaded(mapView)) mapState.tilesLoaded = true else delay(TILE_POLL_MS)
        }
    }

    // The driver and the car, both in view.
    LaunchedEffect(mapState.fit) {
        val points = mapState.fit ?: return@LaunchedEffect
        mapState.fit = null
        mapState.following = false
        // Frame the points in the part of the map nobody covers: the parked bar and "Search here"
        // are over the top, the sheet over the bottom (see ViewportFit).
        mapView.post {
            val density = context.resources.displayMetrics.density
            val result = ViewportFit.fit(
                points = points.map { it.latitude to it.longitude },
                viewWidthPx = mapView.width,
                viewHeightPx = mapView.height,
                padding = ViewportFit.Padding(
                    top = FIT_TOP_DP * density,
                    bottom = FIT_BOTTOM_DP * density,
                    sides = FIT_SIDES_DP * density,
                ),
                minZoom = mapView.minZoomLevel,
                maxZoom = FIT_MAX_ZOOM,
                mapSize = { zoom -> TileSystem.MapSize(zoom) },
            )
            mapView.controller.setZoom(result.zoom)
            mapView.controller.setCenter(GeoPoint(result.centerLat, result.centerLon))
            mapView.scrollBy(0, -result.scrollDownPx.roundToInt())
        }
    }

    // A tapped list entry: show that area, and stop following so the next fix does not undo it.
    LaunchedEffect(mapState.focus) {
        val target = mapState.focus ?: return@LaunchedEffect
        mapState.following = false
        mapView.controller.animateTo(target, FOCUS_ZOOM, FOCUS_ANIMATION_MS)
        mapState.focus = null
    }

    // Turning the arrow must not rebuild every overlay (the compass fires many
    // times a second): just rotate the existing marker.
    LaunchedEffect(heading) {
        youMarker[0]?.rotation = -(heading ?: 0f)
        mapView.invalidate()
    }

    // The cut between the driven (dark) and the remaining (bright) part follows the driver.
    LaunchedEffect(userLocation, route) {
        routeOverlays.update(mapView, userLocation?.let { GeoPoint(it.first, it.second) })
    }

    LaunchedEffect(session, mapState.outsideCoverage, route, parked) {
        // No session yet (waiting for a location, offline) must not hide the car: the parked pin is
        // drawn from the saved spot alone.
        val snapshot = session
        val newRoute = routeOverlays.setRoute(context, mapView, route)
        // The "you" marker comes from the device's own position, not the
        // session's: when browsing a searched area the session's `you` is
        // just the search point.
        val you = when {
            !shown[0].isNaN() -> GeoPoint(shown[0], shown[1])
            userLocation != null -> GeoPoint(userLocation.first, userLocation.second)
            // No fix, no arrow: the session's own `you` is only where its search started, and drawing
            // it would claim a position the app does not have (location denied, or not found yet).
            else -> null
        }
        youMarker[0] = renderParkingOverlays(
            context, mapView, snapshot, you, currentHeading ?: 0f, markers, polygons, routeOverlays,
            parked, { destinationTapped() },
        )
        if (newRoute) {
            // A new plan: show all of it (the driver can press Recenter to follow it instead).
            mapState.following = false
            val box = BoundingBox.fromGeoPoints(routeOverlays.bounds)
            mapView.post { mapView.zoomToBoundingBox(box, false, ROUTE_FIT_BORDER_PX) }
        }
        // The arrow is the device. Outside the coverage area the session is about Zurich, and an
        // arrow there would claim the device is in Zurich.
        youMarker[0]?.setEnabled(!mapState.outsideCoverage)
        if (snapshot == null) return@LaunchedEffect
        if (framedForSessionId != snapshot.sessionId) {
            val searchCenter = GeoPoint(snapshot.origin.lat, snapshot.origin.lon)
            val frame = {
                mapView.zoomToBoundingBox(MapClustering.boundsForRadius(searchCenter, snapshot.radiusM), false)
                mapState.center = mapView.mapCenter as? GeoPoint
                framedForSessionId = snapshot.sessionId
            }
            // Same layout-timing gotcha as MapSearchScreen: zoomToBoundingBox
            // needs a completed layout pass, which may not exist yet on the
            // very first frame.
            if (mapView.width > 0 && mapView.height > 0) {
                mapView.post { frame() }
            } else {
                mapView.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: android.view.View?,
                        left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        if ((right - left) > 0 && (bottom - top) > 0) {
                            mapView.removeOnLayoutChangeListener(this)
                            mapView.post { frame() }
                        }
                    }
                })
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}

private fun renderParkingOverlays(
    context: Context,
    map: MapView,
    snapshot: SessionSnapshot?,
    you: GeoPoint?,
    heading: Float,
    markers: MutableList<Marker>,
    polygons: MutableList<Polygon>,
    route: RouteOverlays,
    parked: ParkedSpot?,
    onDestinationTapped: () -> Unit,
): Marker? {
    map.overlays.removeAll(markers)
    markers.clear()
    map.overlays.removeAll(polygons)
    polygons.clear()
    route.removeFrom(map)

    // Draw order, bottom to top: the stretch already driven, the parking boxes and icons, the
    // stretch still ahead with its stops, the destination pin, and last the driver's arrow.
    route.addBelowSpots(map)

    MapClustering.clusterByZone(snapshot?.rejected.orEmpty()).forEach { cluster ->
        polygons += Polygon(map).apply {
            points = MapClustering.orientedBoxCorners(cluster.segments)
            fillColor = MapIcons.COLOR_REJECTED_ZONE_FILL
            strokeColor = MapIcons.COLOR_REJECTED_ZONE_STROKE
            strokeWidth = 2.5f
        }.also { map.overlays.add(it) }
    }

    val activeClusters = MapClustering.clusterByZone(snapshot?.let { MapClustering.activeCandidates(it) }.orEmpty())
    activeClusters.forEach { cluster ->
        polygons += Polygon(map).apply {
            points = MapClustering.orientedBoxCorners(cluster.segments)
            fillColor = MapIcons.zoneFillColor(cluster.zoneType)
            strokeColor = MapIcons.ZONE_ACCENT
            strokeWidth = 3f
        }.also { map.overlays.add(it) }
    }
    activeClusters.forEach { cluster ->
        markers += Marker(map).apply {
            position = MapClustering.centroidLatLng(cluster.segments)
            title = MapClustering.clusterTitle(cluster)
            icon = MapIcons.zoneIcon(context, cluster.zoneType, MapIcons.PHONE_ICON_SCALE)
            setAnchor(0.5f, 0.5f)
            setInfoWindow(null)
        }.also { map.overlays.add(it) }
    }

    route.addAboveSpots(map)

    // The car's pin replaces the destination pin when the destination is where the car is, so
    // there is never a destination pin and a parked pin on the same spot.
    val origin = snapshot?.origin
    val destinationIsTheCar = parked != null && origin != null &&
        calculateDistanceMeters(parked.lat, parked.lon, origin.lat, origin.lon) < SAME_PLACE_M
    if (origin != null && !destinationIsTheCar) {
        markers += Marker(map).apply {
            position = GeoPoint(origin.lat, origin.lon)
            title = "Destination"
            icon = MapIcons.destinationMarkerIcon(context, MapIcons.PHONE_ICON_SCALE)
            setAnchor(MapIcons.DESTINATION_ANCHOR_X, MapIcons.DESTINATION_ANCHOR_Y)
            setInfoWindow(null)
            // Tapping the pin offers to make it the parking spot.
            setOnMarkerClickListener { _, _ -> onDestinationTapped(); true }
        }.also { map.overlays.add(it) }
    }
    // Where the driver was heading, once the car is parked somewhere else: the destination pin stays
    // on it (the live destination, if there is one, already has its own pin above).
    val remembered = parked?.destination
    if (parked != null && remembered != null && !destinationIsTheCar &&
        (origin == null || calculateDistanceMeters(remembered.lat, remembered.lon, origin.lat, origin.lon) > SAME_PLACE_M) &&
        calculateDistanceMeters(remembered.lat, remembered.lon, parked.lat, parked.lon) > SAME_PLACE_M
    ) {
        markers += Marker(map).apply {
            position = GeoPoint(remembered.lat, remembered.lon)
            title = "Destination"
            icon = MapIcons.destinationMarkerIcon(context, MapIcons.PHONE_ICON_SCALE)
            setAnchor(MapIcons.DESTINATION_ANCHOR_X, MapIcons.DESTINATION_ANCHOR_Y)
            setInfoWindow(null)
        }.also { map.overlays.add(it) }
    }
    if (parked != null) {
        markers += Marker(map).apply {
            position = GeoPoint(parked.lat, parked.lon)
            title = if (parked.assumed) "Assuming you parked here" else "You parked here"
            icon = MapIcons.parkedPinIcon(context, MapIcons.PHONE_ICON_SCALE)
            setAnchor(MapIcons.PARKED_PIN_ANCHOR_X, MapIcons.PARKED_PIN_ANCHOR_Y)
            setInfoWindow(null)
        }.also { map.overlays.add(it) }
    }

    // The driver's own arrow goes last, so nothing covers it; there is none until a position is known.
    val youMarker = you?.let {
        Marker(map).apply {
            position = it
            title = "You"
            icon = MapIcons.youMarkerIcon(context, MapIcons.PHONE_ICON_SCALE)
            setAnchor(0.5f, 0.5f)
            rotation = -heading
            setFlat(true)
            setInfoWindow(null)
        }.also {
            markers += it
            map.overlays.add(it)
        }
    }

    map.invalidate()
    return youMarker
}

private const val SLIDE_MS = 1000f
private const val SNAP_DISTANCE_M = 300.0

/** Zoom and glide time when the map is sent to a listed area. */
private const val FOCUS_ZOOM = 18.0
private const val FOCUS_ANIMATION_MS = 600L

/** Tiles are polled for rather than listened to: osmdroid has no "first tile drawn" callback. */
private const val TILE_POLL_MS = 150L

/** Two points this close are the same place. */
private const val SAME_PLACE_M = 5.0

/** Room left round a planned route when the map fits it (the sheet and the summary card cover some). */
private const val ROUTE_FIT_BORDER_PX = 160

/**
 * Room kept free when the map frames the car and the driver: the parked bar and the "Search here"
 * button cover the top, the bottom sheet the bottom, and the pin is about 50 dp tall above its tip.
 */
private const val FIT_TOP_DP = 190.0
private const val FIT_BOTTOM_DP = 150.0
private const val FIT_SIDES_DP = 40.0
private const val FIT_MAX_ZOOM = 18.0

/** The CARTO Positron land colour, so an empty map reads as map rather than as a grid. */
private val PLACEHOLDER_COLOR = android.graphics.Color.parseColor("#F2F2EF")

/** Whether the tile under the map's centre is in osmdroid's cache (at the zoom it is drawn at). */
private fun centerTileLoaded(map: MapView): Boolean {
    val center = map.mapCenter
    val zoom = map.zoomLevelDouble
    return setOf(floor(zoom).toInt(), zoom.roundToInt()).any { z ->
        val (x, y) = TileMath.tileXY(center.latitude, center.longitude, z)
        map.tileProvider.tileCache.containsTile(MapTileIndex.getTileIndex(z, x, y))
    }
}
