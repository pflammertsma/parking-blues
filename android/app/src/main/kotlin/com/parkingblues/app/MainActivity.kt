package com.parkingblues.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import org.osmdroid.util.GeoPoint
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.parkingblues.car.car.EXTRA_SHOW_PARKED
import com.parkingblues.car.car.cancelExpiryReminder
import com.parkingblues.car.car.clearParkedSpot
import com.parkingblues.car.car.loadParkedSpot
import com.parkingblues.car.location.getLastZone
import com.parkingblues.car.location.isTestLocationEnabled
import com.parkingblues.car.location.lastKnownLocation
import com.parkingblues.car.location.locationUpdates
import com.parkingblues.car.location.setLastZone
import com.parkingblues.car.location.setTestLocationEnabled
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.ParkedSpot
import com.parkingblues.shared.model.ZoneFilter
import com.parkingblues.shared.model.ZoneType
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

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
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

    // Compose state the Activity itself owns (not just `remember`-ed inside
    // setContent's composable), since onNewIntent -- delivered to a
    // singleTask Activity instance that's already running, not a fresh
    // onCreate -- needs a way to push a new parked spot into the already-
    // composed UI.
    private val parkedSpot = mutableStateOf<ParkedSpot?>(null)
    private val currentZone = mutableStateOf(ZoneFilter.BOTH)

    // The device's own position, tracked separately from the session's `you`:
    // while browsing a searched area the session is centered elsewhere.
    private val userLocation = mutableStateOf<Pair<Double, Double>?>(null)

    // True after "Search here": the session is about a spot the user is
    // planning for, so live position updates must not be sent to it (they'd
    // move `you` and auto-reject spots as if the user were driving past).
    private val browsing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        parkedSpot.value = loadParkedSpot(this)
        currentZone.value = getLastZone(this)
        setContent {
            ParkingBluesTheme {
                var useTestLocation by remember {
                    mutableStateOf(isTestLocationEnabled(this@MainActivity))
                }
                AppScaffold(
                    spot = parkedSpot.value,
                    repository = repository,
                    currentZone = currentZone.value,
                    useTestLocation = useTestLocation,
                    onGetDirections = { spot -> openDirections(spot) },
                    onFoundCar = { onFoundCar() },
                    userLocation = userLocation.value,
                    onZoneSelected = { zone -> switchZone(zone) },
                    onSearchHere = { lat, lon -> searchHere(lat, lon) },
                    onRecenter = { recenter() },
                    onUseTestLocationChanged = { enabled ->
                        useTestLocation = enabled
                        setTestLocationEnabled(this@MainActivity, enabled)
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
                    },
                )
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
        if (intent.getBooleanExtra(EXTRA_SHOW_PARKED, false)) {
            parkedSpot.value = loadParkedSpot(this)
        }
    }

    private fun onFoundCar() {
        clearParkedSpot(this)
        cancelExpiryReminder(this)
        parkedSpot.value = null
        browsing.value = false
        repository.reset()
        if (isTestLocationEnabled(this) || hasLocationPermission()) startSearch(currentZone.value)
    }

    private fun openDirections(spot: ParkedSpot) {
        val uri = Uri.parse("geo:${spot.lat},${spot.lon}?q=${spot.lat},${spot.lon}")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
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
            locationUpdates(this@MainActivity).collect { (lat, lon) ->
                userLocation.value = lat to lon
                if (!browsing.value) repository.updatePosition(lat, lon)
            }
        }
    }

    /** Switching zones has no "change zone of an existing session" backend
     *  endpoint to call -- it just restarts the search against the new
     *  zone from the current position (or the browsed area), same as the
     *  car screen. */
    private fun switchZone(zone: ZoneFilter) {
        currentZone.value = zone
        setLastZone(this, zone)
        startSearch(zone)
    }

    private fun searchHere(lat: Double, lon: Double) {
        browsing.value = true
        startSearch(currentZone.value, lat to lon)
    }

    /** Leaving a browsed area: back to live results around the user. */
    private fun recenter() {
        if (browsing.value) {
            browsing.value = false
            startSearch(currentZone.value)
        }
    }

    private fun startSearch(zone: ZoneFilter, at: Pair<Double, Double>? = null) {
        if (!isTestLocationEnabled(this) && !hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        lifecycleScope.launch {
            val browsedOrigin = if (browsing.value) {
                repository.session.value?.origin?.let { it.lat to it.lon }
            } else null
            val existing = at ?: browsedOrigin ?: userLocation.value
            val (lat, lon) = existing ?: lastKnownLocation(this@MainActivity) ?: run {
                // Last-resort default if lastKnownLocation somehow still
                // comes back null (e.g. a permission edge case) -- same
                // fallback web/app.js uses when geolocation isn't available.
                47.379198 to 8.531307
            }
            repository.startSearch(lat, lon, zone)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    spot: ParkedSpot?,
    repository: ParkingSessionRepository,
    currentZone: ZoneFilter,
    useTestLocation: Boolean,
    userLocation: Pair<Double, Double>?,
    onGetDirections: (ParkedSpot) -> Unit,
    onFoundCar: () -> Unit,
    onZoneSelected: (ZoneFilter) -> Unit,
    onSearchHere: (Double, Double) -> Unit,
    onRecenter: () -> Unit,
    onUseTestLocationChanged: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (spot != null) "You parked here" else "Parking Blues") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    if (spot == null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        var zonePage by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onPrimary,
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
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Use test location")
                                            Text(
                                                "Zurich, for development off-site",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Checkbox(checked = useTestLocation, onCheckedChange = null)
                                    },
                                    onClick = {
                                        onUseTestLocationChanged(!useTestLocation)
                                        menuOpen = false
                                    },
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
        if (spot != null) {
            ParkedSpotContent(
                spot = spot,
                contentPadding = innerPadding,
                onGetDirections = { onGetDirections(spot) },
                onFoundCar = onFoundCar,
            )
        } else {
            SearchScreenContent(
                repository = repository,
                userLocation = userLocation,
                contentPadding = innerPadding,
                onSearchHere = onSearchHere,
                onRecenter = onRecenter,
            )
        }
    }
}

@Composable
private fun SearchScreenContent(
    repository: ParkingSessionRepository,
    userLocation: Pair<Double, Double>?,
    contentPadding: PaddingValues,
    onSearchHere: (Double, Double) -> Unit,
    onRecenter: () -> Unit,
) {
    val error by repository.error.collectAsStateWithLifecycle()
    val session by repository.session.collectAsStateWithLifecycle()
    val mapState = remember { MapUiState() }

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

    // A Box overlay, not a Column with the map as one of its children:
    // AndroidView-hosted content (ParkingMapView's embedded osmdroid
    // MapView) composites through its own interop surface, which draws
    // above ordinary Compose siblings regardless of declared order --
    // confirmed live, the chip row was being fully painted over by the
    // map once it had real tiles to draw, not just a measurement/sizing
    // issue. Overlays (the error banner) must be later siblings of the map
    // in a Box rather than Column neighbors.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        ParkingMapView(
            repository = repository,
            userLocation = userLocation,
            mapState = mapState,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                ElevatedButton(
                    onClick = {
                        mapState.center?.let { onSearchHere(it.latitude, it.longitude) }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search here")
                }
            }
        }

        if (!mapState.following) {
            SmallFloatingActionButton(
                onClick = {
                    mapState.following = true
                    onRecenter()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Recenter")
            }
        }
    }
}

private const val SEARCH_HERE_MIN_DISTANCE_M = 75.0

private fun zoneLabel(zone: ZoneFilter): String =
    zone.name.lowercase().replaceFirstChar { it.uppercase() } +
        if (zone == ZoneFilter.BOTH) "" else " zones"

/**
 * Shown instead of the search UI whenever a ParkedSpot is on record --
 * this takes over the phone app's whole launch surface rather than being
 * a tab/section, since "where's my car" is the only thing that matters
 * once it exists, same reasoning as the notification driving the user
 * here in the first place.
 */
@Composable
private fun ParkedSpotContent(
    spot: ParkedSpot,
    contentPadding: PaddingValues,
    onGetDirections: () -> Unit,
    onFoundCar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    spot.addressLabel ?: "Unnamed spot",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Parked at ${formatClockTime(spot.confirmedAtEpochMillis)}",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                val expiresAt = spot.expiresAtEpochMillis
                when {
                    spot.zoneType == ZoneType.BLUE && expiresAt != null ->
                        Text(
                            "Blue zone · legal until ${formatClockTime(expiresAt)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    spot.zoneType == ZoneType.WHITE ->
                        Text(
                            "White zone (metered)" +
                                (spot.estimatedFeeChfPerHour?.let { " · ~CHF %.2f/h".format(it) } ?: ""),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    else -> Text(
                        "No zone info recorded for this spot.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Button(
            onClick = onGetDirections,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Walking directions")
        }

        OutlinedButton(
            onClick = onFoundCar,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I found my car")
        }
    }
}

private fun formatClockTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
