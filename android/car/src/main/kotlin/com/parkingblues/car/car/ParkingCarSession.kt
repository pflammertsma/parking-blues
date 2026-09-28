package com.parkingblues.car.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.parkingblues.car.HasParkingRepository
import com.parkingblues.car.location.locationUpdates
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

        return if (repository.session.value?.current != null) {
            MapSearchScreen(carContext, repository)
        } else {
            ZoneSelectScreen(carContext, repository)
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        carContext, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        // Feed real GPS into the repository for as long as the car session
        // is alive -- the on-device equivalent of dragging the "you" marker
        // continuously in the web MVP (see LocationSource for why this is a
        // continuous stream, not a one-shot fix).
        lifecycleScope.launch {
            locationUpdates(carContext).collect { (lat, lon) ->
                repository.updatePosition(lat, lon)
            }
        }
    }
}
