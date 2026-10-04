package dev.lammertsma.parkingblues.car.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import dev.lammertsma.parkingblues.car.location.getLastZone
import dev.lammertsma.parkingblues.car.location.getSimulatedRouteInitialBearing
import dev.lammertsma.parkingblues.car.location.isTestLocationEnabled
import dev.lammertsma.parkingblues.car.location.lastKnownLocation
import dev.lammertsma.parkingblues.car.location.setLastZone
import dev.lammertsma.parkingblues.car.location.setTestLocationEnabled
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import dev.lammertsma.parkingblues.shared.model.ParkedSpot
import dev.lammertsma.parkingblues.shared.model.ParkingSegment
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.SessionState
import dev.lammertsma.parkingblues.shared.model.ZoneFilter
import dev.lammertsma.parkingblues.shared.model.ZoneType
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * The car app's one screen: osmdroid map (via AppManager's SurfaceCallback +
 * a VirtualDisplay/Presentation hosting a MapView) with a small content pane
 * on top -- a live summary plus zone-switching rows, no separate zone-pick
 * screen and no "Find parking" button. Auto-starts a search on whatever
 * zone was last selected (getLastZone, defaulting to BOTH) as soon as this
 * screen is created, so opening the car app goes straight to a live map;
 * switching zones here just restarts the search against the new zone
 * (there's no "change zone of an existing session" endpoint -- see
 * backend/session.py) rather than navigating to a different screen.
 *
 * See android/README.md for why this is a separate module concern
 * (ACCESS_SURFACE permission, MapWithContentTemplate living in
 * androidx.car.app.navigation.model despite being POI-app-eligible).
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
 * SessionSnapshot.you / .origin / .rejected). Clustering/box/icon math is
 * shared with the phone's own map view (ParkingMapView, in :app) via
 * MapClustering/MapIcons, not duplicated here.
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

    // Keyed on sessionId, not a one-shot boolean: switching zones (the
    // Pane rows in summaryContentTemplate) starts a brand new session via
    // startSearch(), which can jump "you" to wherever that new search
    // actually started from. A one-shot flag would leave the camera stuck
    // on the *first* session's location forever after that -- see the
    // identical fix in :app's ParkingMapView, found live there first.
    private var framedForSessionId: String? = null

    // The zone this screen is currently searching, independent of whatever
    // segment the backend happens to be tracking as `current` -- shown as
    // the checked row in the content pane (see summaryContentTemplate) and
    // persisted via setLastZone so the next launch defaults to it.
    private var currentZone: ZoneFilter = retryZone ?: getLastZone(carContext)

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

        // No separate zone-pick screen and no "Find parking" button
        // anymore -- go straight to a live map on whatever zone was last
        // used. Only fires if there's no session already in flight (e.g.
        // a config change recreating this Screen shouldn't restart search).
        if (repository.session.value == null) {
            startSearch(currentZone)
        }

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

    /** Starts (or restarts) the search against [zone], using the driver's
     *  current position if a session is already live, otherwise falling
     *  back through the explicit retry coordinates / last known fix / the
     *  Zurich default -- same fallback chain ZoneSelectScreen used to use. */
    private fun startSearch(zone: ZoneFilter) {
        currentZone = zone
        setLastZone(carContext, zone)
        lifecycleScope.launch {
            val you = repository.session.value?.you
            val (lat, lon) = when {
                you != null -> you.lat to you.lon
                retryLat != null && retryLon != null -> retryLat to retryLon
                else -> lastKnownLocation(carContext) ?: (47.379198 to 8.531307)
            }
            val isTest = isTestLocationEnabled(carContext)
            val bearing = if (isTest) getSimulatedRouteInitialBearing() else null
            repository.startSearch(lat, lon, zone, bearing = bearing)
        }
    }

    /** The "Simulate test drive" entry ZoneSelectScreen used to offer,
     *  preserved here as an action instead of a separate screen: enables
     *  the fixed test-driving-loop location source and searches BOTH
     *  zones, so the clustering/auto-rejection behavior stays reachable
     *  without a real device. */
    private fun startTestDrive() {
        setTestLocationEnabled(carContext, true)
        startSearch(ZoneFilter.BOTH)
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
        val initialCenter = repository.session.value?.you
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
        val newZoom = map.zoomLevelDouble + kotlin.math.ln(scaleFactor.toDouble()) / kotlin.math.ln(2.0)
        map.controller.setZoom(newZoom)
        val afterPx = map.projection.toPixels(GeoPoint(before.latitude, before.longitude), null)
        map.controller.scrollBy(afterPx.x - focusX.toInt(), afterPx.y - focusY.toInt())
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
        MapClustering.clusterByZone(snapshot.rejected).forEach { cluster ->
            polygons += Polygon(map).apply {
                points = MapClustering.orientedBoxCorners(cluster.segments)
                fillColor = MapIcons.COLOR_REJECTED_ZONE_FILL
                strokeColor = MapIcons.COLOR_REJECTED_ZONE_STROKE
                strokeWidth = 2.5f
            }.also { map.overlays.add(it) }
        }

        // Every active candidate, not a capped "nearest N" -- tried
        // capping this (see git history) when a dense result set first
        // looked like an overwhelming pile of overlapping pins, but that
        // was actually the oversized-marker bug (fixed separately), not a
        // real problem with showing the full set; capping was never asked
        // for and just hid real data. Nearby same-zone spots are grouped
        // into one box+icon instead of one pin per spot.
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
                icon = MapIcons.zoneIcon(carContext, cluster.zoneType)
                setAnchor(0.5f, 0.5f)
                setInfoWindow(null)
            }.also { map.overlays.add(it) }
        }

        // Destination marker drawn above parking zones so it is never obscured
        markers += Marker(map).apply {
            position = GeoPoint(snapshot.origin.lat, snapshot.origin.lon)
            title = "Destination"
            icon = destinationMarkerIcon
            setAnchor(MapIcons.DESTINATION_ANCHOR_X, MapIcons.DESTINATION_ANCHOR_Y)
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
            if (framedForSessionId != snapshot.sessionId) {
                val frameCamera = {
                    if (map.width > 0 && map.height > 0) {
                        framedForSessionId = snapshot.sessionId
                        map.zoomToBoundingBox(MapClustering.boundsForRadius(you, snapshot.radiusM), false)
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

    private val youMarkerIcon by lazy { MapIcons.youMarkerIcon(carContext) }
    private val destinationMarkerIcon by lazy { MapIcons.destinationMarkerIcon(carContext) }

    // -- Template: our map as the background, the summary + zone switcher as content --

    // MapWithContentTemplate/MapController (androidx.car.app.navigation.model)
    // are @ExperimentalCarApi in Car App Library 1.4.0. Opted in deliberately
    // as a prototype (see android/README.md); re-check this API's status
    // before relying on it in anything shipped.
    @OptIn(markerClass = [ExperimentalCarApi::class])
    override fun onGetTemplate(): Template {
        val snapshot = repository.session.value ?: return errorOrEmptyTemplate()

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
                    .setPanModeListener { inPanMode -> isInPanMode = inPanMode }
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
                    .addAction(zoneAction())
                    .addAction(
                        Action.Builder()
                            .setTitle("Parked here")
                            .setOnClickListener { onParkedHereClicked() }
                            .build()
                    )
                    .apply {
                        // Dev-only shortcut (needed on emulators with no
                        // phone to toggle test location from); not for drivers.
                        if (isDebuggableBuild) {
                            addAction(
                                Action.Builder()
                                    .setTitle("Test drive")
                                    .setOnClickListener { startTestDrive() }
                                    .build()
                            )
                        }
                    }
                    .build()
            )
            .build()
    }

    /**
     * "I parked here": confirms the backend's own tracked `current` segment
     * (exactly what repository.confirmCurrent() acts on server-side, see
     * backend/session.py's confirm_current -- so this stays consistent with
     * whatever the server considers current, rather than guessing from the
     * uncapped, unranked cluster display), then saves a local ParkedSpot
     * snapshot the phone UI can read back later. Deliberately does NOT
     * require a `current` segment to exist: a driver can park somewhere
     * the session never specifically recommended, in which case this still
     * remembers the *location* (from the live "you" fix) just without a
     * zone/expiry attached to it.
     *
     * Posts an immediate "saved" notification and, for blue-zone spots
     * (the only zone type with a hard legal deadline in this data --
     * see ParkedSpot's own doc), schedules an expiry reminder. Both are
     * genuinely useful only via Android Auto (:app, phone-projected,
     * same process as the phone's MainActivity); on Android Automotive OS
     * there's no phone involved by platform design, so this still saves
     * and still posts a notification (harmless), but the notification's
     * tap target has nothing to resolve to there -- see
     * ParkingReminderScheduler's doc.
     */
    private fun onParkedHereClicked() {
        val snapshot = repository.session.value ?: return
        val you = snapshot.you
        val segment = snapshot.current
        lifecycleScope.launch {
            if (segment != null) repository.confirmCurrent()
            val spot = ParkedSpot(
                lat = you.lat,
                lon = you.lon,
                zoneType = segment?.zoneType,
                addressLabel = segment?.addressLabel,
                confirmedAtEpochMillis = System.currentTimeMillis(),
                expiresAtEpochMillis = segment?.legalUntil?.let(::parseIsoToEpochMillis),
                estimatedFeeChfPerHour = segment?.estimatedFeeChfPerHour,
            )
            saveParkedSpot(carContext, spot)
            notifyParkingSaved(carContext, spot)
            if (spot.expiresAtEpochMillis != null) scheduleExpiryReminder(carContext, spot)
        }
    }

    private fun computeSummaryTitle(snapshot: SessionSnapshot): String {
        if (snapshot.state == SessionState.EXHAUSTED) {
            return "No parking found nearby"
        }
        val active = MapClustering.activeCandidates(snapshot)
        val nearby = active.filter { it.distanceFromYouM <= MapClustering.NEARBY_RADIUS_M }
        val spotCount = nearby.sumOf { it.estimatedCapacity.coerceAtLeast(1) }
        val radiusInt = MapClustering.NEARBY_RADIUS_M.toInt()

        return when (spotCount) {
            0 -> "No parking spaces within ${radiusInt}m"
            1 -> "1 parking space within ${radiusInt}m"
            else -> "$spotCount parking spaces within ${radiusInt}m"
        }
    }

    /**
     * Just the live summary, kept to a single row so the panel leaves as
     * much of the screen as possible to the map. Zone switching lives in
     * the action strip (see zoneAction). A ListTemplate rather than a
     * PaneTemplate: the host rejects click listeners on Pane rows.
     */
    private fun summaryContentTemplate(snapshot: SessionSnapshot): Template {
        val summary = Row.Builder().setTitle(computeSummaryTitle(snapshot))
        // Parking data only covers the city of Zurich, so an empty result
        // far from it is expected -- say so instead of leaving a dead end.
        if (snapshot.state == SessionState.EXHAUSTED) summary.addText("Data covers the city of Zurich")
        val items = ItemList.Builder().addItem(summary.build()).build()
        return ListTemplate.Builder()
            .setTitle("Parking Blues")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .build()
    }

    /** Shows the active zone and cycles Blue -> White -> Both on tap. */
    private fun zoneAction(): Action {
        val zones = ZoneFilter.entries
        val label = currentZone.name.lowercase().replaceFirstChar { it.uppercase() } +
            if (currentZone == ZoneFilter.BOTH) "" else " zones"
        return Action.Builder()
            .setTitle(label)
            .setOnClickListener { startSearch(zones[(zones.indexOf(currentZone) + 1) % zones.size]) }
            .build()
    }

    private val isDebuggableBuild: Boolean
        get() = carContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun listContentTemplate(snapshot: SessionSnapshot): Template {
        val items = ItemList.Builder()
        for (segment in MapClustering.activeCandidates(snapshot)) {
            items.addItem(compactRow(segment))
        }
        return ListTemplate.Builder()
            .setTitle("Parking Blues")
            .setHeaderAction(Action.APP_ICON)
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
        val builder = MessageTemplate.Builder(message).setHeaderAction(Action.APP_ICON)
        if (repository.error.value != null) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener { startSearch(currentZone) }
                    .build()
            )
        }
        return builder.build()
    }
}

/** ParkingSegment.legalUntil is an ISO-8601 offset datetime string, exactly
 *  as backend/blue_zone_rules.py emits it -- parsed, not recomputed, per
 *  the "backend owns the algorithm" rule (see ParkedSpot's doc). */
private fun parseIsoToEpochMillis(iso: String): Long? =
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
