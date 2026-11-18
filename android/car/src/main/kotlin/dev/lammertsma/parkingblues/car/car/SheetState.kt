package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.shared.model.SessionSnapshot

/**
 * What the bottom sheet is saying, decided from the session alone so it can be reasoned about
 * (and tested) without any UI. [Empty] is what a visitor outside Zurich gets: the backend only
 * knows the City of Zurich, so anywhere else a search comes back with nothing.
 */
sealed interface SheetState {
    data object Waiting : SheetState

    /** The driver said no to location: nothing will ever arrive, so offer Zurich instead of waiting. */
    data object NoLocation : SheetState
    data object Searching : SheetState
    data object Empty : SheetState
    data class Found(val areas: List<NearbyArea>) : SheetState
}

/**
 * How far an area may be and still count as "nearby": the backend never searches wider than this
 * (MAX_RADIUS_M). Anything farther is what a visitor outside Zurich is left with, because the
 * session can still hold Zurich's spots while the visitor stands hundreds of kilometres away.
 */
const val NEARBY_LIMIT_M = 2_000

fun sheetState(session: SessionSnapshot?, hasLocation: Boolean, locationDenied: Boolean = false): SheetState {
    if (session == null) {
        return when {
            hasLocation -> SheetState.Searching
            locationDenied -> SheetState.NoLocation
            else -> SheetState.Waiting
        }
    }
    val areas = NearbyAreas.all(session).filter { it.distanceM <= NEARBY_LIMIT_M }
    return if (areas.isEmpty()) SheetState.Empty else SheetState.Found(areas)
}
