package dev.lammertsma.parkingblues.car.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

private const val MAX_AREAS = 5

/**
 * The best parking areas around the driver as a short list, the same areas that are
 * drawn on the map, ranked in the backend's order. Each row is an *area* (a run of
 * adjacent same-zone spots), not a single space: "Blue zone · 120 m NE", with the
 * rules and an estimated space count underneath. Picking one opens [AreaScreen].
 */
class NearbyScreen(
    carContext: CarContext,
    private val repository: ParkingSessionRepository,
    private val onShowOnMap: (GeoPoint) -> Unit,
) : Screen(carContext) {

    private var shownKey: String? = null

    init {
        // The host throttles non-navigation templates, so only refresh when what the
        // list says actually changes (positions update about once a second).
        lifecycleScope.launch {
            repository.session.collect { invalidateIfChanged() }
        }
    }

    private fun areas(): List<NearbyArea> {
        val snapshot = repository.session.value ?: return emptyList()
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(MAX_AREAS)
        return NearbyAreas.top(snapshot, minOf(MAX_AREAS, limit))
    }

    private fun key(areas: List<NearbyArea>) =
        areas.joinToString("|") { NearbyAreas.title(it) + NearbyAreas.summary(it) }

    private fun invalidateIfChanged() {
        val key = key(areas())
        if (key != shownKey) invalidate()
    }

    override fun onGetTemplate(): Template {
        val areas = areas()
        shownKey = key(areas)

        if (areas.isEmpty()) {
            return MessageTemplate.Builder("No parking areas nearby right now. Data covers the city of Zurich.")
                .setTitle("Nearby parking")
                .setHeaderAction(Action.BACK)
                .build()
        }

        val list = ItemList.Builder()
        for (area in areas) {
            list.addItem(
                Row.Builder()
                    .setTitle(NearbyAreas.title(area))
                    .addText(NearbyAreas.summary(area))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(AreaScreen(carContext, area, onShowOnMap)) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Nearby parking")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** One area in detail, with the two things a driver does next: drive there, or look at it on the map. */
class AreaScreen(
    carContext: CarContext,
    private val area: NearbyArea,
    private val onShowOnMap: (GeoPoint) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Distance").addText("${area.distanceM} m ${area.direction} of you").build())
            .addRow(Row.Builder().setTitle("Spaces").addText(NearbyAreas.spaces(area)).build())
            .addRow(Row.Builder().setTitle("Rules").addText(NearbyAreas.rules(area)).build())
            .addAction(
                Action.Builder()
                    .setTitle("Navigate")
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener { navigate() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Show on map")
                    .setOnClickListener {
                        screenManager.popToRoot()
                        onShowOnMap(area.center)
                    }
                    .build()
            )
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(NearbyAreas.title(area))
            .setHeaderAction(Action.BACK)
            .build()
    }

    /** Hands the area's location to the car's navigation app, if there is one. */
    private fun navigate() {
        val uri = Uri.parse("geo:${area.center.latitude},${area.center.longitude}")
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
        } catch (e: Exception) {
            // No navigation app installed (an emulator, or a car without one), or the
            // host refused: say so instead of failing silently or crashing.
            CarToast.makeText(carContext, "No navigation app available", CarToast.LENGTH_LONG).show()
        }
    }
}
