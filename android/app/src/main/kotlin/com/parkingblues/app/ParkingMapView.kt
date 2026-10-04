package com.parkingblues.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.hypot
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parkingblues.car.car.MapClustering
import com.parkingblues.car.car.MapIcons
import com.parkingblues.car.car.positronTileSource
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.SessionSnapshot
import org.osmdroid.util.GeoPoint
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
}

@Composable
fun ParkingMapView(
    repository: ParkingSessionRepository,
    userLocation: Pair<Double, Double>?,
    mapState: MapUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session by repository.session.collectAsStateWithLifecycle()
    val bearing by repository.bearing.collectAsStateWithLifecycle()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(positronTileSource)
            setMultiTouchControls(true)
            minZoomLevel = 13.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)

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
                        if (!multiTouch && event.pointerCount == 1 &&
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

    // Follow mode: keep the camera on the user until they drag the map away.
    LaunchedEffect(mapState.following, userLocation) {
        val user = userLocation ?: return@LaunchedEffect
        if (mapState.following) mapView.controller.animateTo(GeoPoint(user.first, user.second))
    }

    LaunchedEffect(session, bearing, userLocation) {
        val snapshot = session ?: return@LaunchedEffect
        // The "you" marker comes from the device's own position, not the
        // session's: when browsing a searched area the session's `you` is
        // just the search point.
        val you = userLocation?.let { GeoPoint(it.first, it.second) }
            ?: GeoPoint(snapshot.you.lat, snapshot.you.lon)
        renderParkingOverlays(context, mapView, snapshot, you, bearing ?: 0f, markers, polygons)
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
    snapshot: SessionSnapshot,
    you: GeoPoint,
    bearing: Float,
    markers: MutableList<Marker>,
    polygons: MutableList<Polygon>,
) {
    map.overlays.removeAll(markers)
    markers.clear()
    map.overlays.removeAll(polygons)
    polygons.clear()

    MapClustering.clusterByZone(snapshot.rejected).forEach { cluster ->
        polygons += Polygon(map).apply {
            points = MapClustering.orientedBoxCorners(cluster.segments)
            fillColor = MapIcons.COLOR_REJECTED_ZONE_FILL
            strokeColor = MapIcons.COLOR_REJECTED_ZONE_STROKE
            strokeWidth = 2.5f
        }.also { map.overlays.add(it) }
    }

    val activeClusters = MapClustering.clusterByZone(MapClustering.activeCandidates(snapshot))
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
            icon = MapIcons.zoneIcon(context, cluster.zoneType)
            setAnchor(0.5f, 0.5f)
            setInfoWindow(null)
        }.also { map.overlays.add(it) }
    }

    markers += Marker(map).apply {
        position = GeoPoint(snapshot.origin.lat, snapshot.origin.lon)
        title = "Destination"
        icon = MapIcons.destinationMarkerIcon(context)
        setAnchor(MapIcons.DESTINATION_ANCHOR_X, MapIcons.DESTINATION_ANCHOR_Y)
        setInfoWindow(null)
    }.also { map.overlays.add(it) }

    markers += Marker(map).apply {
        position = you
        title = "You"
        icon = MapIcons.youMarkerIcon(context)
        setAnchor(0.5f, 0.5f)
        rotation = -bearing
        setFlat(true)
        setInfoWindow(null)
    }.also { map.overlays.add(it) }

    map.invalidate()
}
