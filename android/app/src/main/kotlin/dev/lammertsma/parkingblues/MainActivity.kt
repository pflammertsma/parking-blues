package dev.lammertsma.parkingblues

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import org.osmdroid.util.GeoPoint
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.lammertsma.parkingblues.car.location.HeadingTracker
import dev.lammertsma.parkingblues.car.location.PositionFilter
import dev.lammertsma.parkingblues.car.location.compassHeadings
import dev.lammertsma.parkingblues.car.car.EXTRA_SHOW_PARKED
import dev.lammertsma.parkingblues.car.R as CarR
import dev.lammertsma.parkingblues.car.car.NEARBY_LIMIT_M
import dev.lammertsma.parkingblues.car.car.SheetState
import dev.lammertsma.parkingblues.car.car.CoverageArea
import dev.lammertsma.parkingblues.car.car.ParkedSpotFactory
import dev.lammertsma.parkingblues.car.car.parkedSpotFlow
import dev.lammertsma.parkingblues.car.car.scheduleExpiryReminder
import dev.lammertsma.parkingblues.car.car.sheetState
import dev.lammertsma.parkingblues.car.location.calculateDistanceMeters
import kotlinx.coroutines.delay
import dev.lammertsma.parkingblues.car.location.calculateDistanceMeters
import dev.lammertsma.parkingblues.car.car.cancelExpiryReminder
import dev.lammertsma.parkingblues.car.car.clearParkedSpot
import dev.lammertsma.parkingblues.car.car.loadParkedSpot
import dev.lammertsma.parkingblues.car.car.saveParkedSpot
import dev.lammertsma.parkingblues.car.location.getLastZone
import dev.lammertsma.parkingblues.car.location.isTestLocationEnabled
import dev.lammertsma.parkingblues.car.location.lastKnownLocation
import dev.lammertsma.parkingblues.car.location.locationUpdates
import dev.lammertsma.parkingblues.car.location.setLastZone
import dev.lammertsma.parkingblues.car.location.setTestLocationEnabled
import dev.lammertsma.parkingblues.car.location.testModeFlow
import dev.lammertsma.parkingblues.shared.AuthRepository
import dev.lammertsma.parkingblues.shared.AuthState
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import dev.lammertsma.parkingblues.shared.RoutePlanException
import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkedSpot
import dev.lammertsma.parkingblues.shared.model.ParkingRoute
import dev.lammertsma.parkingblues.shared.model.ZoneFilter
import dev.lammertsma.parkingblues.shared.model.ZoneType
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Companion phone UI -- not just a "connect to the car" gate anymore: the
 * same live osmdroid map as the car screen (ParkingMapView, reusing
 * MapSearchScreen's clustering/icon logic via MapClustering/MapIcons in
 * :car), with zone chips to switch what's being searched. No separate
 * "Find parking" step: auto-starts on whatever zone was last selected
 * (getLastZone, shared with the car screen's own persisted choice) as
 * soon as this Activity/the car screen has a location to search from.
 *
 * Also the landing point for the "parked here" notifications (see
 * ParkingReminderScheduler in :car): tapping either the "saved" or the
 * "expiring soon" notification opens this Activity with EXTRA_SHOW_PARKED,
 * which is exactly the point -- by the time the driver is walking back to
 * their car, Android Auto has long since disconnected, so this phone UI
 * (not the car screen) is the only surface left to show it on.
 * android:launchMode="singleTask" (see the manifest) so a tap while the
 * app's already running re-delivers onNewIntent instead of stacking a
 * second instance.
 *
 * enableEdgeToEdge() + Scaffold/TopAppBar below, not a bare Surface +
 * Column: targetSdk 35 enforces edge-to-edge regardless of whether it's
 * requested, so without inset-aware layout the content draws straight
 * under the status bar/camera cutout (confirmed live -- the title text
 * was rendering behind the cutout). TopAppBar consumes WindowInsets.
 * safeDrawing itself, which covers the display cutout as well as the
 * status bar, so Scaffold's innerPadding is enough on its own; no manual
 * insets plumbing needed beyond using Scaffold instead of Surface.
 */
class MainActivity : ComponentActivity() {
    private val repository: ParkingSessionRepository by lazy {
        (application as ParkingBluesApp).repository
    }
    private val auth: AuthRepository by lazy { (application as ParkingBluesApp).auth }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationDenied.value = !granted
        if (granted) {
            startLocationUpdates()
            if (repository.session.value == null) startSearch(currentZone.value)
        }
    }

    // No result handling needed -- if denied, notifyParkingSaved/Expiring
    // just silently no-op (see ParkingReminderScheduler), same as any other
    // notification permission denial.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    // Cancelled and relaunched whenever the test-location toggle flips, so
    // switching real GPS <-> the fixed Zurich point takes effect right away
    // instead of needing an app restart.
    private var locationJob: Job? = null

    // Bumped when the app is opened from a parking notification (also while it is already running:
    // onNewIntent of a singleTask Activity), so the map can show the car. The parked spot itself is
    // observed from the store (parkedSpotFlow), which the car session and the Android Auto
    // watcher write to as well.
    private val showParkedTrigger = mutableIntStateOf(0)
    private val currentZone = mutableStateOf(ZoneFilter.BOTH)

    // The device's own position, tracked separately from the session's `you`:
    // while browsing a searched area the session is centered elsewhere.
    private val userLocation = mutableStateOf<Pair<Double, Double>?>(null)

    // True after "Search here": the session is about a spot the user is
    // planning for, so live position updates must not be sent to it (they'd
    // move `you` and auto-reject spots as if the user were driving past).
    private val browsing = mutableStateOf(false)

    // True while the device is outside the area the app has maps for. The app then opens on
    // Zurich to browse (a search there, as if "Show Zurich" was tapped) instead of a blank map.
    private val outsideCoverage = mutableStateOf(false)

    // The driver answered the location prompt with no: the sheet then offers Zurich instead of
    // "Waiting for your location" for ever.
    private val locationDenied = mutableStateOf(false)

    // EXPERIMENT: the planned parking route (see backend/route_planner.py), if there is one.
    private val routeState = mutableStateOf<RouteState>(RouteState.None)

    // Phone UI: GPS course while moving, the compass while standing still.
    private val headingTracker = HeadingTracker(useCompass = true)
    private val positionFilter = PositionFilter()
    private val heading = mutableStateOf<Float?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra(EXTRA_SHOW_PARKED, false)) showParkedTrigger.intValue++
        if (loadParkedSpot(this) == null) {
            // No local record (fresh install, app data cleared, a different
            // device parked it) -- the account may still remember it, e.g.
            // "Parked here" was tapped on Android Auto/AAOS and synced via
            // ParkingSessionRepository.syncParkedSpot. Best-effort: silently
            // stays null if signed out, offline, or nothing is on record.
            lifecycleScope.launch {
                repository.fetchParkedSpot()?.let { spot -> saveParkedSpot(this@MainActivity, spot) }
            }
        }
        currentZone.value = getLastZone(this)
        setContent {
            ParkingBluesTheme {
                val testMode by testModeFlow.collectAsState()
                val useTestLocation = testMode == true
                val parkedSpot by parkedSpotFlow(this@MainActivity).collectAsState()
                var page by rememberSaveable { mutableStateOf(Page.Main) }
                var developerUnlocked by remember { mutableStateOf(isDeveloperUnlocked(this@MainActivity)) }
                var crashReports by remember { mutableStateOf(crashReporter.isEnabled) }
                BackHandler(enabled = page == Page.About) { page = Page.Main }
                val authState by auth.state.collectAsState()
                val authNotice by auth.notice.collectAsState()
                LaunchedEffect(authNotice) {
                    authNotice?.let {
                        message(it)
                        auth.clearNotice()
                    }
                }
                if (page == Page.About) {
                    AboutScreen(
                        snackbarHostState = snackbars,
                        versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        developerUnlocked = developerUnlocked,
                        onUnlockDeveloper = {
                            if (!developerUnlocked) message("Developer options unlocked")
                            developerUnlocked = true
                            setDeveloperUnlocked(this@MainActivity, true)
                        },
                        onLockDeveloper = {
                            developerUnlocked = false
                            setDeveloperUnlocked(this@MainActivity, false)
                        },
                        useTestLocation = useTestLocation,
                        onUseTestLocationChanged = { setUseTestLocation(it) },
                        crashReportsAvailable = crashReporter.isAvailable,
                        crashReportsEnabled = crashReports,
                        onCrashReportsChanged = {
                            crashReporter.isEnabled = it
                            crashReports = crashReporter.isEnabled
                        },
                        onOpenUrl = { url ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: ActivityNotFoundException) {
                                message("No app found to open the link")
                            }
                        },
                        onBack = { page = Page.Main },
                    )
                } else AppScaffold(
                    onAbout = { page = Page.About },
                    snackbarHostState = snackbars,
                    onMessage = { message(it) },
                    authState = authState,
                    signInAvailable = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank(),
                    onSignIn = { signIn() },
                    onSignOut = { lifecycleScope.launch { auth.signOut() } },
                    onDeleteAccount = { deleteAccount() },
                    spot = parkedSpot,
                    showParkedTrigger = showParkedTrigger.intValue,
                    onSetParkedHere = { setParkedHere() },
                    repository = repository,
                    currentZone = currentZone.value,
                    onWalkTo = { lat, lon -> openWalking(lat, lon) },
                    onFoundCar = { onFoundCar() },
                    userLocation = userLocation.value,
                    outsideCoverage = outsideCoverage.value,
                    destinationChosen = browsing.value,
                    locationDenied = locationDenied.value,
                    routeState = routeState.value,
                    onPlanRoute = { planRoute() },
                    onClearRoute = { clearRoute() },
                    heading = heading.value,
                    onZoneSelected = { zone -> switchZone(zone) },
                    onSearchHere = { lat, lon -> searchHere(lat, lon) },
                    onRecenter = { recenter() },
                )
            }
        }
        // The compass only runs while the screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                compassHeadings(this@MainActivity) { userLocation.value }.collect { azimuth ->
                    heading.value = headingTracker.onCompass(azimuth)
                }
            }
        }
        if (isTestLocationEnabled(this) || hasLocationPermission()) {
            startLocationUpdates()
            // No zone-pick screen and no "Find parking" button -- go
            // straight to a live map on whatever zone was last used. Only
            // fires if there's no session already in flight.
            if (repository.session.value == null) startSearch(currentZone.value)
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_PARKED, false)) showParkedTrigger.intValue++
    }

    private fun setUseTestLocation(enabled: Boolean) {
        setTestLocationEnabled(this@MainActivity, enabled)
        clearRoute()
        browsing.value = false
        userLocation.value = null
        // The real-GPS path needs the permission; the
        // test-location path never queries GPS at all, so it
        // works before the user has granted (or even been
        // asked for) location permission.
        if (!enabled && !hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            startLocationUpdates()
            startSearch(currentZone.value)
        }
    }

    private val crashReporter get() = (application as ParkingBluesApp).crashReporter

    // Messages to the user are snackbars in the app (SnackbarHost in the sheet scaffold and the About
    // screen), never system toasts: they follow the app theme, sit above the bottom sheet, can be
    // dismissed, and can carry an action such as Retry.
    private val snackbars = SnackbarHostState()

    private fun message(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        lifecycleScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                withDismissAction = actionLabel == null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    /** Optional: the app works anonymously; signing in only raises the request limits. */
    private fun signIn() {
        lifecycleScope.launch {
            val idToken = requestGoogleIdToken(this@MainActivity, BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .getOrElse { failure ->
                    if (failure !is SignInCancelled) message("Google sign-in failed: ${failure.message}")
                    return@launch
                }
            auth.signIn(idToken)
                .onSuccess { message("Signed in as ${it.email}") }
                .onFailure { message(it.message ?: "Sign-in failed") }
        }
    }

    private fun deleteAccount() {
        lifecycleScope.launch {
            auth.deleteAccount()
                .onSuccess { message("Your account was deleted") }
                .onFailure { message("Could not delete the account: ${it.message}") }
        }
    }

    /** Forgets the parked spot. The map and any destination the driver is looking at stay as they are. */
    private fun onFoundCar() {
        clearParkedSpot(this)
        cancelExpiryReminder(this)
        // Best-effort: also clears the account's copy, so another signed-in
        // device (or this one, after a reinstall) doesn't keep offering a
        // spot the driver already found.
        lifecycleScope.launch { repository.clearParkedSpotSync() }
    }

    /**
     * The driver tapped the destination pin and said "this is where I parked". The pin is the
     * spot, so it is saved as such (zone and time limit read from the nearest known parking spot,
     * if any) and the pin turns into the parked pin.
     */
    private fun setParkedHere() {
        val snapshot = repository.session.value ?: return
        val spot = ParkedSpotFactory.at(
            snapshot, snapshot.origin.lat, snapshot.origin.lon, System.currentTimeMillis(), assumed = false,
        )
        clearRoute()
        saveParkedSpot(this, spot)
        if (spot.expiresAtEpochMillis != null) scheduleExpiryReminder(this, spot)
        lifecycleScope.launch {
            // Best-effort, like "Parked here" in the car (see ParkingSessionRepository.syncParkedSpot).
            repository.syncParkedSpot(
                lat = spot.lat,
                lon = spot.lon,
                zoneType = spot.zoneType?.let { if (it == ZoneType.BLUE) "blue" else "white" },
                addressLabel = spot.addressLabel,
                expiresAtEpochMillis = spot.expiresAtEpochMillis,
                estimatedFeeChfPerHour = spot.estimatedFeeChfPerHour,
            )
        }
    }

    /**
     * Walking directions to a place, from wherever the phone is: Google Maps in walking mode when it
     * is installed, otherwise any maps app showing the place.
     */
    private fun openWalking(lat: Double, lon: Double) {
        val walking = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w"))
            .setPackage("com.google.android.apps.maps")
        val place = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))
        for (intent in listOf(walking, place)) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one.
            }
        }
        // No maps app at all (an emulator without one, a work profile): say so, do not crash.
        message("No maps app found")
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            locationUpdates(this@MainActivity).collect { fix ->
                val (lat, lon) = positionFilter.onFix(fix)
                userLocation.value = lat to lon
                heading.value = headingTracker.onFix(fix)
                val outside = !CoverageArea.contains(lat, lon)
                val wasOutside = outsideCoverage.value
                outsideCoverage.value = outside
                if (outside) {
                    // Nothing to follow out here: browse Zurich instead (once, not on every fix).
                    if (!wasOutside) {
                        browsing.value = true
                        startSearch(currentZone.value, ZURICH_LAT to ZURICH_LON)
                    }
                    return@collect
                }
                if (wasOutside) {
                    // Back in range: from browsing Zurich to live results around the device.
                    browsing.value = false
                    startSearch(currentZone.value)
                    return@collect
                }
                if (!browsing.value) {
                    val origin = repository.session.value?.origin
                    if (origin != null && calculateDistanceMeters(origin.lat, origin.lon, lat, lon) > NEARBY_LIMIT_M) {
                        // The session began somewhere else (the Zurich fallback used before the first
                        // fix, or the user travelled far). Moving it would keep that place's spots
                        // around a user who is nowhere near them, so search from here instead.
                        startSearch(currentZone.value)
                    } else {
                        repository.updatePosition(lat, lon)
                    }
                }
            }
        }
    }

    /** Plans a parking route from the device to the chosen destination (the search's origin). */
    private fun planRoute() {
        val from = userLocation.value ?: return
        val to = repository.session.value?.origin ?: return
        if (routeState.value is RouteState.Planning) return
        routeState.value = RouteState.Planning
        lifecycleScope.launch {
            repository.planRoute(LatLon(from.first, from.second), to, currentZone.value)
                .onSuccess { route ->
                    // The destination may have moved while the server was thinking.
                    routeState.value =
                        if (repository.session.value?.origin == to) RouteState.Ready(route) else RouteState.None
                }
                .onFailure {
                    routeState.value = RouteState.None
                    // Retry only where trying again could help (busy, down, offline), not where the
                    // answer would be the same (no road near you, not available on the server).
                    val retry = (it as? RoutePlanException)?.retryable == true
                    message(
                        it.message ?: "Couldn't plan the route.",
                        actionLabel = if (retry) "Retry" else null,
                        onAction = if (retry) ({ planRoute() }) else null,
                    )
                }
        }
    }

    /** A route belongs to one destination, zone and start; anything that changes them drops it. */
    private fun clearRoute() {
        routeState.value = RouteState.None
    }

    /** Switching zones has no "change zone of an existing session" backend
     *  endpoint to call -- it just restarts the search against the new
     *  zone from the current position (or the browsed area), same as the
     *  car screen. */
    private fun switchZone(zone: ZoneFilter) {
        clearRoute()
        currentZone.value = zone
        setLastZone(this, zone)
        startSearch(zone)
    }

    private fun searchHere(lat: Double, lon: Double) {
        clearRoute()
        browsing.value = true
        startSearch(currentZone.value, lat to lon)
    }

    /** Leaving a browsed area: back to live results around the user. */
    private fun recenter() {
        if (browsing.value) {
            clearRoute()
            browsing.value = false
            startSearch(currentZone.value)
        }
    }

    private fun startSearch(zone: ZoneFilter, at: Pair<Double, Double>? = null) {
        // Only a search from the driver's own position needs the permission; looking at a place the
        // driver chose (Show Zurich, a long press, Search here) works without it.
        val aroundTheDriver = at == null && !(browsing.value && repository.session.value != null)
        if (aroundTheDriver && !isTestLocationEnabled(this) && !hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        lifecycleScope.launch {
            val browsedOrigin = if (browsing.value) {
                repository.session.value?.origin?.let { it.lat to it.lon }
            } else null
            val existing = at ?: browsedOrigin ?: userLocation.value
            var (lat, lon) = existing ?: lastKnownLocation(this@MainActivity) ?: run {
                // Last-resort default if lastKnownLocation somehow still
                // comes back null (e.g. a permission edge case) -- same
                // fallback web/app.js uses when geolocation isn't available.
                47.379198 to 8.531307
            }
            if (at == null && browsedOrigin == null && !CoverageArea.contains(lat, lon)) {
                // The device is somewhere the app has no maps for. Whichever search this is (the
                // first one at launch included), it becomes a look at Zurich, so a slower search
                // from the real position cannot overwrite it with an empty one.
                outsideCoverage.value = true
                browsing.value = true
                lat = ZURICH_LAT
                lon = ZURICH_LON
            }
            // A destination the user chose (not the Zurich view shown to someone abroad).
            repository.startSearch(lat, lon, zone, destination = browsing.value && !outsideCoverage.value)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    snackbarHostState: SnackbarHostState,
    onMessage: (String) -> Unit,
    authState: AuthState,
    signInAvailable: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    spot: ParkedSpot?,
    showParkedTrigger: Int,
    onSetParkedHere: () -> Unit,
    repository: ParkingSessionRepository,
    currentZone: ZoneFilter,
    userLocation: Pair<Double, Double>?,
    outsideCoverage: Boolean,
    destinationChosen: Boolean,
    locationDenied: Boolean,
    routeState: RouteState,
    onPlanRoute: () -> Unit,
    onClearRoute: () -> Unit,
    heading: Float?,
    onWalkTo: (Double, Double) -> Unit,
    onFoundCar: () -> Unit,
    onZoneSelected: (ZoneFilter) -> Unit,
    onSearchHere: (Double, Double) -> Unit,
    onRecenter: () -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parking Blues") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = appBarContainerColor(),
                    titleContentColor = appBarContentColor(),
                    actionIconContentColor = appBarContentColor(),
                ),
                actions = {
                    // The menu is always there: the parked spot no longer takes the screen over.
                    run {
                        var menuOpen by remember { mutableStateOf(false) }
                        var zonePage by remember { mutableStateOf(false) }
                        var confirmDelete by remember { mutableStateOf(false) }
                        if (confirmDelete) {
                            AlertDialog(
                                onDismissRequest = { confirmDelete = false },
                                title = { Text("Delete account?") },
                                text = {
                                    Text(
                                        "This permanently deletes your account and signs you out. " +
                                            "Searching for parking never requires an account."
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = { confirmDelete = false; onDeleteAccount() }) {
                                        Text("Delete")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                                },
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                            )
                        }
                        // Compose's DropdownMenu has no native submenu, so
                        // the "Zones" submenu swaps the menu's contents in
                        // place (header with a back arrow + radio items).
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false; zonePage = false },
                        ) {
                            if (!zonePage) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Zones")
                                            Text(
                                                zoneLabel(currentZone),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                    },
                                    onClick = { zonePage = true },
                                )
                                // Optional sign-in; hidden until a Google client id is configured.
                                if (signInAvailable) {
                                    val state = authState
                                    if (state is AuthState.SignedIn) {
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(state.account.name.ifBlank { "Signed in" })
                                                    Text(
                                                        state.account.email,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            enabled = false,
                                            onClick = {},
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Sign out") },
                                            onClick = { menuOpen = false; onSignOut() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete account\u2026") },
                                            onClick = { menuOpen = false; confirmDelete = true },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("Sign in with Google")
                                                    Text(
                                                        "Optional: higher limits",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            onClick = { menuOpen = false; onSignIn() },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text("About") },
                                    onClick = { menuOpen = false; onAbout() },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Zones") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    },
                                    onClick = { zonePage = false },
                                )
                                for (zone in ZoneFilter.entries) {
                                    DropdownMenuItem(
                                        text = { Text(zoneLabel(zone)) },
                                        leadingIcon = {
                                            RadioButton(selected = zone == currentZone, onClick = null)
                                        },
                                        onClick = {
                                            if (zone != currentZone) onZoneSelected(zone)
                                            menuOpen = false
                                            zonePage = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        SearchScreenContent(
            snackbarHostState = snackbarHostState,
            onMessage = onMessage,
            repository = repository,
            userLocation = userLocation,
            outsideCoverage = outsideCoverage,
            destinationChosen = destinationChosen,
            locationDenied = locationDenied,
            routeState = routeState,
            onPlanRoute = onPlanRoute,
            onClearRoute = onClearRoute,
            parkedSpot = spot,
            showParkedTrigger = showParkedTrigger,
            onWalkTo = onWalkTo,
            onFoundCar = onFoundCar,
            onSetParkedHere = onSetParkedHere,
            heading = heading,
            contentPadding = innerPadding,
            onSearchHere = onSearchHere,
            onRecenter = onRecenter,
        )
    }
}

/** Always shown while outside the covered area (no close button), and kept to one line. */
@Composable
private fun OutsideCoverageBanner() {
    Surface(
        modifier = Modifier.padding(top = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 2.dp,
    ) {
        Text(
            "You\u2019re outside Zurich \u00b7 showing the city",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** EXPERIMENT: the planned parking route's state in the UI. */
sealed interface RouteState {
    data object None : RouteState
    data object Planning : RouteState
    data class Ready(val route: ParkingRoute) : RouteState
}

/** What the route is, in two lines, with a way to dismiss it. */
@Composable
private fun RouteSummary(route: ParkingRoute, onClear: () -> Unit) {
    val stops = route.stops.size
    val minutes = (route.route.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
    val chance = (route.chanceOfParking * 100).roundToInt()
    Surface(
        modifier = Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Parking route (experiment)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$stops stop${if (stops == 1) "" else "s"} \u00b7 ~$minutes min drive \u00b7 ~$chance% chance of a space",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The road starts away from the driver: say so (the map shows the gap dashed).
                route.startSnap?.let { gap ->
                    Text(
                        "The road starts ${gap.distanceM} m from you" + (if (gap.name.isNotBlank()) ", on ${gap.name}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Clear route")
            }
        }
    }
}

@Composable
private fun MapLoadingIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("Loading map…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreenContent(
    snackbarHostState: SnackbarHostState,
    onMessage: (String) -> Unit,
    repository: ParkingSessionRepository,
    userLocation: Pair<Double, Double>?,
    outsideCoverage: Boolean,
    destinationChosen: Boolean,
    locationDenied: Boolean,
    routeState: RouteState,
    onPlanRoute: () -> Unit,
    onClearRoute: () -> Unit,
    parkedSpot: ParkedSpot?,
    showParkedTrigger: Int,
    onWalkTo: (Double, Double) -> Unit,
    onFoundCar: () -> Unit,
    onSetParkedHere: () -> Unit,
    heading: Float?,
    contentPadding: PaddingValues,
    onSearchHere: (Double, Double) -> Unit,
    onRecenter: () -> Unit,
) {
    val error by repository.error.collectAsStateWithLifecycle()
    val session by repository.session.collectAsStateWithLifecycle()
    val mapState = remember { MapUiState() }
    // Show the car when there is one to find: when a spot appears, and when the app is opened from
    // a parking notification. Both the driver and the car if they are apart, else just the car.
    val latestUser by rememberUpdatedState(userLocation)
    val showCar = { spot: ParkedSpot ->
        val car = GeoPoint(spot.lat, spot.lon)
        val here = latestUser?.takeIf { !outsideCoverage }
        // The car, plus the driver and the destination they were heading for if either is far
        // enough from it to matter.
        val points = buildList {
            add(car)
            if (here != null && calculateDistanceMeters(here.first, here.second, spot.lat, spot.lon) > FIT_BOTH_MIN_M) {
                add(GeoPoint(here.first, here.second))
            }
            spot.destination
                ?.takeIf { calculateDistanceMeters(it.lat, it.lon, spot.lat, spot.lon) > FIT_BOTH_MIN_M }
                ?.let { add(GeoPoint(it.lat, it.lon)) }
        }
        if (points.size > 1) mapState.fit = points else mapState.focus = car
    }
    LaunchedEffect(parkedSpot?.let { it.lat to it.lon }, showParkedTrigger) {
        parkedSpot?.let(showCar)
    }

    // The destination pin was tapped: offer to make it the parking spot.
    var askSetParking by remember { mutableStateOf(false) }
    if (askSetParking) {
        AlertDialog(
            onDismissRequest = { askSetParking = false },
            title = { Text("Set as parking spot?") },
            text = { Text("Mark this pin as where you parked, so you can find your car from here.") },
            confirmButton = {
                TextButton(onClick = { askSetParking = false; onSetParkedHere() }) { Text("Set parking spot") }
            },
            dismissButton = { TextButton(onClick = { askSetParking = false }) { Text("Cancel") } },
        )
    }

    // Outside the covered area there is no position to follow and no arrow to draw.
    LaunchedEffect(outsideCoverage) {
        mapState.outsideCoverage = outsideCoverage
        if (outsideCoverage) mapState.following = false
    }

    // Only offer "Search here" once the map is meaningfully away from where
    // the current results are centered, i.e. after the user has panned.
    val showSearchHere by remember {
        derivedStateOf {
            val center = mapState.center
            val origin = session?.origin
            !mapState.following && center != null && origin != null &&
                center.distanceToAsDouble(GeoPoint(origin.lat, origin.lon)) > SEARCH_HERE_MIN_DISTANCE_M
        }
    }

    val sheetScaffold = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val hasLocation = userLocation != null
    val sheet = remember(session, hasLocation, locationDenied) { sheetState(session, hasLocation, locationDenied) }
    val bottomInset = contentPadding.calculateBottomPadding()
    BackHandler(enabled = sheetScaffold.bottomSheetState.currentValue == SheetValue.Expanded) {
        scope.launch { sheetScaffold.bottomSheetState.partialExpand() }
    }

    // The sheet reaches under the navigation bar (it adds that inset itself), so only the top of
    // the Scaffold's padding is applied here.
    BottomSheetScaffold(
        scaffoldState = sheetScaffold,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        sheetPeekHeight = peekHeight(sheet) + bottomInset,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
        sheetContent = {
            ParkingSheetContent(
                state = sheet,
                bottomInset = bottomInset,
                onMessage = onMessage,
                onShowArea = { area ->
                    mapState.focus = area.center
                    scope.launch { sheetScaffold.bottomSheetState.partialExpand() }
                },
                onShowZurich = {
                    // Not following the user: the map should stay where the search is.
                    mapState.following = false
                    onSearchHere(ZURICH_LAT, ZURICH_LON)
                },
            )
        },
    ) { sheetPadding ->
        // A Box overlay, not a Column with the map as one of its children:
        // AndroidView-hosted content (ParkingMapView's embedded osmdroid
        // MapView) composites through its own interop surface, which draws
        // above ordinary Compose siblings regardless of declared order --
        // confirmed live, the chip row was being fully painted over by the
        // map once it had real tiles to draw, not just a measurement/sizing
        // issue. Overlays (the error banner) must be later siblings of the map
        // in a Box rather than Column neighbors.
        Box(modifier = Modifier.fillMaxSize()) {
            ParkingMapView(
                repository = repository,
                userLocation = if (outsideCoverage) null else userLocation,
                heading = heading,
                mapState = mapState,
                route = (routeState as? RouteState.Ready)?.route,
                parked = parkedSpot,
                onDestinationTapped = { askSetParking = true },
                onDestinationPicked = { lat, lon ->
                    // A deliberate pick: stay on it rather than following the device.
                    mapState.following = false
                    onSearchHere(lat, lon)
                },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (outsideCoverage) OutsideCoverageBanner()
                parkedSpot?.let { ParkedCard(it, showCar, onWalkTo, onFoundCar) }
                (routeState as? RouteState.Ready)?.let { RouteSummary(it.route, onClearRoute) }
                if (error != null) {
                    Text(
                        "Error: $error",
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                if (showSearchHere) {
                    Button(
                        onClick = {
                            mapState.center?.let { onSearchHere(it.latitude, it.longitude) }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search here")
                    }
                }
            }

            // Until the first tile arrives. Not when the server says there is nothing here (the tile
            // server only covers Zurich, so there it would spin for ever), and not after a timeout.
            var gaveUpWaiting by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(MAP_LOADING_TIMEOUT_MS)
                gaveUpWaiting = true
            }
            if (!mapState.tilesLoaded && !gaveUpWaiting && sheet !is SheetState.Empty) {
                MapLoadingIndicator(Modifier.align(Alignment.Center))
            }

            // EXPERIMENT: a route needs a destination to aim at and a start to drive from.
            val canPlanRoute = destinationChosen && userLocation != null && !outsideCoverage
            val routeReady = routeState is RouteState.Ready
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = sheetPadding.calculateBottomPadding())
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (canPlanRoute) {
                    SmallFloatingActionButton(
                        onClick = onPlanRoute,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        if (routeState is RouteState.Planning) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(painterResource(R.drawable.ic_route), contentDescription = "Plan a parking route")
                        }
                    }
                }
                if (!mapState.following && !outsideCoverage) {
                    SmallFloatingActionButton(
                        onClick = {
                            mapState.following = true
                            // With a route planned, Recenter only goes back to following the
                            // device; otherwise it also leaves the destination (and its route).
                            if (!routeReady) onRecenter()
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Recenter")
                    }
                }
            }
        }
    }
}

private const val SEARCH_HERE_MIN_DISTANCE_M = 75.0

// Apart by more than this, showing the car also shows the driver; closer, just the car.
private const val FIT_BOTH_MIN_M = 60.0

// A remembered destination this close to the car is the car: no separate walk to offer.
private const val DESTINATION_APART_M = 50.0

// Floating cards over the map are no wider than this, however wide the screen is.
private val CARD_MAX_WIDTH = 480.dp
private const val MAP_LOADING_TIMEOUT_MS = 8_000L

// Zurich main station: where "Show Zurich" looks, for a visitor outside the covered area.
private const val ZURICH_LAT = 47.3779
private const val ZURICH_LON = 8.5403

private fun zoneLabel(zone: ZoneFilter): String =
    zone.name.lowercase().replaceFirstChar { it.uppercase() } +
        if (zone == ZoneFilter.BOTH) "" else " zones"

/**
 * Where the car is, at the top of the map for as long as a spot is on record (saved from the car,
 * assumed when Android Auto disconnected, or set on the map). A slim bar, not a panel: the map is
 * what matters, so it is one row (the pin, two short lines, walk to the car, and a menu for the rest)
 * and no wider than [CARD_MAX_WIDTH] however wide the screen is. The map stays usable under it, with
 * the spot drawn as a black pin.
 */
@Composable
private fun ParkedCard(
    spot: ParkedSpot,
    onShow: (ParkedSpot) -> Unit,
    onWalkTo: (Double, Double) -> Unit,
    onFoundCar: () -> Unit,
) {
    // Where the driver was going, when it is somewhere other than where the car is.
    val destination = spot.destination?.takeIf {
        calculateDistanceMeters(spot.lat, spot.lon, it.lat, it.lon) > DESTINATION_APART_M
    }
    val expiresAt = spot.expiresAtEpochMillis
    val blueDeadline = spot.zoneType == ZoneType.BLUE && expiresAt != null
    val rules = when {
        blueDeadline -> "Blue zone, legal until ${formatClockTime(expiresAt!!)}"
        spot.zoneType == ZoneType.WHITE ->
            spot.estimatedFeeChfPerHour?.let { "White zone, ~CHF %.2f/h".format(it) } ?: "White zone (metered)"
        else -> null
    }
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        // It looks like something to tap, so it is: tapping it shows the car on the map.
        onClick = { onShow(spot) },
        modifier = Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(CarR.drawable.ic_parked_pin),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (spot.assumed) "Assuming you parked here" else "You parked here",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Since ${formatClockTime(spot.confirmedAtEpochMillis)}" + (rules?.let { " \u00b7 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blueDeadline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onWalkTo(spot.lat, spot.lon) }) {
                Icon(painterResource(R.drawable.ic_walk), contentDescription = "Walk to car")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (destination != null) {
                        DropdownMenuItem(
                            text = { Text("Walk to destination") },
                            onClick = { menuOpen = false; onWalkTo(destination.lat, destination.lon) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (spot.assumed) "Clear" else "I found my car") },
                        onClick = { menuOpen = false; onFoundCar() },
                    )
                    if (spot.assumed) {
                        // How to correct a wrong guess, where the driver looks when it is wrong.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Wrong place? Long-press the map, tap the pin and set it.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

private fun formatClockTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private enum class Page { Main, About }
