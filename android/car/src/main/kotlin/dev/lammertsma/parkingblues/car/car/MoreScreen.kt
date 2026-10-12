package dev.lammertsma.parkingblues.car.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import dev.lammertsma.parkingblues.car.HasParkingRepository
import dev.lammertsma.parkingblues.shared.model.ZoneFilter

/**
 * Everything MapSearchScreen's action strip doesn't have room for as its own
 * button: Zones always, the crash-report switch in release builds, Developer options in
 * debug builds only. Replaces
 * what used to be a separate "Zones" text action plus a debug-only icon
 * action -- up to 4 actions at once, which didn't reliably fit the strip
 * alongside Recenter and Parked here on a real content pane width (reported
 * live: the leftmost action clipped behind the content pane). A single
 * "More" icon action, fixed regardless of build type, keeps the strip to 3
 * actions always.
 */
class MoreScreen(
    carContext: CarContext,
    private val currentZone: ZoneFilter,
    private val onZoneSelected: (ZoneFilter) -> Unit,
    private val isDebuggableBuild: Boolean,
    private val testLocationEnabled: Boolean,
    private val onTestLocationChanged: (Boolean) -> Unit,
) : Screen(carContext) {

    private val crashReporter get() = (carContext.applicationContext as HasParkingRepository).crashReporter

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Zones")
                    .addText(currentZone.displayLabel())
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(ZoneScreen(carContext, currentZone, onZoneSelected))
                    }
                    .build()
            )
            .apply {
                // Only where reports can be sent at all (release builds with Firebase configured).
                if (crashReporter.isAvailable) {
                    addItem(
                        Row.Builder()
                            .setTitle("Send crash reports")
                            .addText("Anonymous. No location or searches.")
                            .setToggle(
                                Toggle.Builder { enabled ->
                                    crashReporter.isEnabled = enabled
                                    invalidate()
                                }.setChecked(crashReporter.isEnabled).build()
                            )
                            .build()
                    )
                }
                if (isDebuggableBuild) {
                    addItem(
                        Row.Builder()
                            .setTitle("Developer options")
                            .setBrowsable(true)
                            .setOnClickListener {
                                screenManager.push(
                                    DeveloperScreen(carContext, testLocationEnabled, onTestLocationChanged)
                                )
                            }
                            .build()
                    )
                }
            }
            .build()
        return ListTemplate.Builder()
            .setTitle("More")
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }
}
