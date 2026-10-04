package dev.lammertsma.parkingblues.car.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.lammertsma.parkingblues.car.HasParkingRepository
import dev.lammertsma.parkingblues.car.location.HeadingTracker
import dev.lammertsma.parkingblues.car.location.PositionFilter
import dev.lammertsma.parkingblues.car.location.locationUpdates
import kotlinx.coroutines.launch

class ParkingCarSession : Session() {
    // Works on both the phone/Android Auto app and the Android Automotive OS
    // app -- they're separate installed processes with their own Application
    // subclass (see HasParkingRepository), but this Session code is shared.
    private val repository by lazy {
        (carContext.applicationContext as HasParkingRepository).repository
    }

    override fun onCreateScreen(intent: Intent): Screen {
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            // The car host can't show a normal Android runtime-permission
            // dialog; requesting it here is the Car App Library way.
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                if (granted.isNotEmpty()) startLocationUpdates()
            }
        }

        // MapSearchScreen is the whole app now -- no separate zone-pick
        // screen first; it auto-starts a search on the last-selected zone
        // itself (see its init block) if one isn't already running.
        return MapSearchScreen(carContext, repository)
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        carContext, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // In the car the phone's compass is meaningless (it sits in a cradle or
    // cupholder), so heading is GPS course only and holds while stopped.
    private val headingTracker = HeadingTracker(useCompass = false)
    private val positionFilter = PositionFilter()

    private fun startLocationUpdates() {
        // Feed real GPS into the repository for as long as the car session
        // is alive -- the on-device equivalent of dragging the "you" marker
        // continuously in the web MVP (see LocationSource for why this is a
        // continuous stream, not a one-shot fix).
        lifecycleScope.launch {
            locationUpdates(carContext).collect { fix ->
                val (lat, lon) = positionFilter.onFix(fix)
                repository.updatePosition(lat, lon, headingTracker.onFix(fix))
            }
        }
    }
}
