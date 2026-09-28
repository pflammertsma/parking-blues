package com.parkingblues.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.parkingblues.car.location.isTestLocationEnabled
import com.parkingblues.car.location.lastKnownLocation
import com.parkingblues.car.location.locationUpdates
import com.parkingblues.car.location.setTestLocationEnabled
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.ZoneFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Companion phone UI. Deliberately minimal -- not aiming for parity with
 * web/index.html here, just a permission gate, a zone picker, and a status
 * readout, since the actual point of this app is the car screen (see
 * car/ParkingCarSession.kt). A CarAppService needs a normal launchable
 * Activity to exist in the app, which is the other reason this exists.
 */
class MainActivity : ComponentActivity() {
    private val repository: ParkingSessionRepository by lazy {
        (application as ParkingBluesApp).repository
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startLocationUpdates() }

    // Cancelled and relaunched whenever the test-location toggle flips, so
    // switching real GPS <-> the fixed Zurich point takes effect right away
    // instead of needing an app restart.
    private var locationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var useTestLocation by remember {
                        mutableStateOf(isTestLocationEnabled(this@MainActivity))
                    }
                    SearchScreenContent(
                        repository = repository,
                        useTestLocation = useTestLocation,
                        onFindParking = { zone -> startSearch(zone) },
                        onUseTestLocationChanged = { enabled ->
                            useTestLocation = enabled
                            setTestLocationEnabled(this@MainActivity, enabled)
                            // The real-GPS path needs the permission; the
                            // test-location path never queries GPS at all,
                            // so it works before the user has granted (or
                            // even been asked for) location permission.
                            if (!enabled && !hasLocationPermission()) {
                                requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                startLocationUpdates()
                            }
                        },
                    )
                }
            }
        }
        if (isTestLocationEnabled(this) || hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            locationUpdates(this@MainActivity).collect { (lat, lon) ->
                repository.updatePosition(lat, lon)
            }
        }
    }

    private fun startSearch(zone: ZoneFilter) {
        if (!isTestLocationEnabled(this) && !hasLocationPermission()) {
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        lifecycleScope.launch {
            val existing = repository.session.value?.you?.let { it.lat to it.lon }
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

@Composable
private fun SearchScreenContent(
    repository: ParkingSessionRepository,
    useTestLocation: Boolean,
    onFindParking: (ZoneFilter) -> Unit,
    onUseTestLocationChanged: (Boolean) -> Unit,
) {
    val session by repository.session.collectAsStateWithLifecycle()
    val error by repository.error.collectAsStateWithLifecycle()
    var selectedZone by remember { mutableStateOf(ZoneFilter.BOTH) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Parking Blues", style = MaterialTheme.typography.headlineMedium)

        // On by default, matching web/app.js's DEFAULT_ORIGIN -- real device
        // GPS is almost never actually in Zurich during development.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Use test location (Zurich)")
            Switch(checked = useTestLocation, onCheckedChange = onUseTestLocationChanged)
        }

        Column(Modifier.selectableGroup()) {
            for (zone in ZoneFilter.entries) {
                Column(
                    Modifier.selectable(
                        selected = zone == selectedZone,
                        onClick = { selectedZone = zone },
                    )
                ) {
                    RadioButton(selected = zone == selectedZone, onClick = { selectedZone = zone })
                    Text(zone.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }

        Button(onClick = { onFindParking(selectedZone) }) {
            Text("Find parking")
        }

        when {
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            session?.current != null -> Text(
                "Current target: ${session!!.current!!.addressLabel} " +
                    "(${session!!.current!!.distanceFromYouM} m from you)"
            )
            session != null -> Text("State: ${session!!.state}")
            else -> Text("Pick a zone and connect this app to the car to start searching.")
        }
    }
}
