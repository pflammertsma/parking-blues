package dev.lammertsma.parkingblues.car.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.lammertsma.parkingblues.shared.model.ZoneFilter

fun ZoneFilter.displayLabel(): String =
    name.lowercase().replaceFirstChar { it.uppercase() } + if (this == ZoneFilter.BOTH) "" else " zones"

/**
 * Zone choice on its own screen: the map's action strip has room for two or
 * three buttons at most, so this keeps the strip short instead of cramming
 * every option into it. A radio list; picking a zone applies it and returns
 * to the map.
 *
 * It must stay the only list on this screen: Car App Library rejects a
 * selectable list alongside any other list, or alongside toggle rows, which
 * is why the debug-only test-location toggle lives in [DeveloperScreen].
 */
class ZoneScreen(
    carContext: CarContext,
    private val current: ZoneFilter,
    private val onZoneSelected: (ZoneFilter) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val zones = ZoneFilter.entries
        val list = ItemList.Builder()
            .setOnSelectedListener { index ->
                val zone = zones[index]
                if (zone != current) onZoneSelected(zone)
                screenManager.pop()
            }
            .setSelectedIndex(zones.indexOf(current))
        zones.forEach { list.addItem(Row.Builder().setTitle(it.displayLabel()).build()) }
        return ListTemplate.Builder()
            .setTitle("Zones")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
