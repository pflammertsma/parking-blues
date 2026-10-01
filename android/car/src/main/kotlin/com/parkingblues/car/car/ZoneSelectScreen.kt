package com.parkingblues.car.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.parkingblues.car.location.lastKnownLocation
import com.parkingblues.car.location.setTestLocationEnabled
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.ZoneFilter
import kotlinx.coroutines.launch

/** First car screen: pick a zone, mirrors the "1. Start a search" section of web/index.html. */
class ZoneSelectScreen(
    carContext: CarContext,
    private val repository: ParkingSessionRepository,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        for (zone in ZoneFilter.entries) {
            items.addItem(
                Row.Builder()
                    .setTitle(zone.name.lowercase().replaceFirstChar { it.uppercase() } + " zones")
                    .setOnClickListener {
                        setTestLocationEnabled(carContext, false)
                        startSearch(zone)
                    }
                    .build()
            )
        }

        items.addItem(
            Row.Builder()
                .setTitle("Simulate test drive (Zurich)")
                .addText("Live 15 km/h test drive with car marker & auto-rejection")
                .setOnClickListener {
                    setTestLocationEnabled(carContext, true)
                    startSearch(ZoneFilter.BOTH)
                }
                .build()
        )

        return ListTemplate.Builder()
            .setTitle("Parking Blues")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .build()
    }

    private fun startSearch(zone: ZoneFilter) {
        lifecycleScope.launch {
            val (lat, lon) = lastKnownLocation(carContext) ?: (47.379198 to 8.531307)
            repository.startSearch(lat, lon, zone)
            screenManager.push(MapSearchScreen(carContext, repository, zone, lat, lon))
        }
    }
}
