package com.parkingblues.car.car

import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.model.ParkingSegment
import com.parkingblues.shared.model.SessionState
import com.parkingblues.shared.model.ZoneFilter
import com.parkingblues.shared.model.ZoneType
import kotlinx.coroutines.launch

/**
 * Live top-down map of candidate spots, refreshing as GPS updates flow in
 * from ParkingCarSession -- the car-screen equivalent of the map view in
 * web/app.js. This is a POI template (see the category note in
 * AndroidManifest.xml), not a navigation one: the app ranks and shows
 * candidates, it doesn't turn-by-turn route to them. No manual reject/
 * confirm actions here either, since auto-rejection from continuous
 * position updates already covers that (see the web MVP's session history
 * for why the manual button was removed).
 */
class SearchScreen(
    carContext: CarContext,
    private val repository: ParkingSessionRepository,
    // Only needed to retry after a failed startSearch (e.g. backend
    // unreachable) without bouncing the user back to ZoneSelectScreen.
    // Null when entering with an already-active session (see
    // ParkingCarSession.onCreateScreen), where a retry is never offered.
    private val retryZone: ZoneFilter? = null,
    private val retryLat: Double? = null,
    private val retryLon: Double? = null,
) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repository.session.collect { invalidate() }
        }
        lifecycleScope.launch {
            repository.error.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val snapshot = repository.session.value
            ?: return errorOrEmptyTemplate()

        if (snapshot.state == SessionState.EXHAUSTED) {
            return MessageTemplate.Builder(
                "No more candidates nearby, even after widening the search radius."
            ).setHeaderAction(Action.BACK).build()
        }

        // Respect whatever the host actually allows rather than guessing a
        // fixed row count -- see README section 4's "glanced at, not typed
        // into" goal: a list longer than the host wants to render would
        // just get silently truncated (or rejected) anyway.
        val maxItems = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)

        val items = ItemList.Builder()
        var rank = 1
        snapshot.current?.let { items.addItem(segmentRow(it, rank++, current = true)) }
        for (segment in snapshot.upcoming.take((maxItems - 1).coerceAtLeast(0))) {
            items.addItem(segmentRow(segment, rank++, current = false))
        }

        return PlaceListMapTemplate.Builder()
            .setTitle("Parking Blues")
            .setHeaderAction(Action.BACK)
            .setItemList(items.build())
            .setCurrentLocationEnabled(true)
            .build()
    }

    /** Distinguishes a genuine failure (network/backend error) from simply
     *  not having searched yet, and offers Retry for the former -- without
     *  this, every failure looked identical to the pristine "no search
     *  started" state, which is what made the backend-unreachable case so
     *  hard to diagnose from the car screen alone. */
    private fun errorOrEmptyTemplate(): Template {
        val message = repository.error.value ?: "No active search."
        val builder = MessageTemplate.Builder(message).setHeaderAction(Action.BACK)
        if (repository.error.value != null && retryZone != null && retryLat != null && retryLon != null) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener {
                        lifecycleScope.launch {
                            repository.startSearch(retryLat, retryLon, retryZone)
                        }
                    }
                    .build()
            )
        }
        return builder.build()
    }

    private fun segmentRow(segment: ParkingSegment, rank: Int, current: Boolean): Row {
        val zoneLabel = if (segment.zoneType == ZoneType.BLUE) "Blue zone" else "White zone"
        val prefix = if (current) "→ " else ""
        val marker = PlaceMarker.Builder()
            .setLabel(rank.toString())
            .setColor(if (segment.zoneType == ZoneType.BLUE) CarColor.BLUE else CarColor.DEFAULT)
            .build()
        val place = Place.Builder(CarLocation.create(segment.lat, segment.lon))
            .setMarker(marker)
            .build()
        return Row.Builder()
            .setTitle("$prefix${shortLabel(segment)}")
            .addText(summaryText(segment, zoneLabel))
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .build()
    }

    /**
     * The ingested data has no real street name, just an internal id --
     * address_label is *always* literally "Street parking spot #<id>" (see
     * scripts/ingest_zurich_parking.py), so repeating that boilerplate on
     * every row was most of why only ~2 rows fit on screen at once (it wraps
     * to 2 lines in the narrow list column). Falls back to the full label
     * if that ever changes (e.g. real addresses later).
     */
    private fun shortLabel(segment: ParkingSegment): String =
        segment.addressLabel.removePrefix("Street parking spot ").ifBlank { segment.addressLabel }

    /**
     * Single subtext line -- was two (zone+distance, then duration/fee) --
     * combining zone, distance, and the legal/fee detail so each row is
     * title + one line instead of title + two, roughly doubling how many
     * rows fit in PlaceListMapTemplate's list pane without dropping the map.
     *
     * PlaceListMapTemplate requires every non-browsable row to carry a
     * DistanceSpan on its title or one of its texts (verified against the
     * actual 1.4.0 jar/docs after this crashed with "All non-browsable rows
     * must have a distance span..."). The span replaces a single placeholder
     * character with the host's own formatted distance, matching Google's
     * own sample pattern -- everything else in the string is plain text.
     */
    private fun summaryText(segment: ParkingSegment, zoneLabel: String): CharSequence {
        val prefix = "$zoneLabel · "
        val placeholderIndex = prefix.length
        val text = SpannableString("$prefix#${detailText(segment)}")
        text.setSpan(
            DistanceSpan.create(Distance.create(segment.distanceFromYouM, Distance.UNIT_METERS)),
            placeholderIndex,
            placeholderIndex + 1,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return text
    }

    private fun detailText(segment: ParkingSegment): String {
        if (segment.zoneType == ZoneType.BLUE) {
            // legalUntil is an ISO 8601 timestamp (Python's datetime.isoformat(),
            // see backend/app.py) -- pull out just "HH:MM" rather than showing
            // the raw offset-qualified string on a car screen.
            val clockTime = segment.legalUntil?.let { it.substring(11, 16) }
            return clockTime?.let { " · until ~$it" } ?: " · no time limit"
        }
        val cap = segment.maxDurationMinutes?.let { "$it min" } ?: "no fixed limit"
        val rate = segment.estimatedFeeChfPerHour
        return if (rate != null) " · $cap · ~CHF ${"%.2f".format(rate)}/h" else " · $cap"
    }
}
