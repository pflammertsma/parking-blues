package dev.lammertsma.parkingblues.car.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import dev.lammertsma.parkingblues.shared.model.ZoneFilter

fun ZoneFilter.displayLabel(): String =
    name.lowercase().replaceFirstChar { it.uppercase() } + if (this == ZoneFilter.BOTH) "" else " zones"

/**
 * Zone choice on its own screen: the map's action strip has room for two or
 * three buttons at most, so this keeps the strip short instead of cramming
 * every option into it. Picking a zone applies it and returns to the map.
 * [onTestDrive] is non-null only in debuggable builds.
 */
class ZoneScreen(
    carContext: CarContext,
    private val current: ZoneFilter,
    private val onZoneSelected: (ZoneFilter) -> Unit,
    private val onTestDrive: (() -> Unit)?,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val zones = ZoneFilter.entries
        val zoneList = ItemList.Builder()
            .setOnSelectedListener { index ->
                val zone = zones[index]
                if (zone != current) onZoneSelected(zone)
                screenManager.pop()
            }
            .setSelectedIndex(zones.indexOf(current))
        zones.forEach { zoneList.addItem(Row.Builder().setTitle(it.displayLabel()).build()) }

        val template = ListTemplate.Builder()
            .setTitle("Zones")
            .setHeaderAction(Action.BACK)

        if (onTestDrive == null) {
            template.setSingleList(zoneList.build())
        } else {
            template.addSectionedList(SectionedItemList.create(zoneList.build(), "Show"))
            template.addSectionedList(
                SectionedItemList.create(
                    ItemList.Builder()
                        .addItem(
                            Row.Builder()
                                .setTitle("Test drive")
                                .addText("Simulated driving in Zurich (debug builds only)")
                                .setOnClickListener {
                                    onTestDrive.invoke()
                                    screenManager.pop()
                                }
                                .build()
                        )
                        .build(),
                    "Developer",
                )
            )
        }
        return template.build()
    }
}
