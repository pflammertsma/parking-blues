package dev.lammertsma.parkingblues

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lammertsma.parkingblues.car.car.NearbyArea
import dev.lammertsma.parkingblues.car.car.NearbyAreas
import dev.lammertsma.parkingblues.car.car.SheetState
import dev.lammertsma.parkingblues.shared.model.ZoneType
import dev.lammertsma.parkingblues.car.R as CarR

/** The collapsed sheet shows only its header; it needs more room when there is a button in it. */
fun peekHeight(state: SheetState): Dp = when (state) {
    is SheetState.Empty -> PEEK_WITH_ACTION
    is SheetState.Found -> PEEK
    // One line only: any taller and the list below it would show through.
    SheetState.NoLocation -> PEEK_WITH_ACTION
    SheetState.Waiting, SheetState.Searching -> PEEK_ONE_LINE
}

private val PEEK = 90.dp
private val PEEK_WITH_ACTION = 144.dp
private val PEEK_ONE_LINE = 64.dp

/** How many areas the expanded list shows; the header still counts all of them. */
private const val MAX_LISTED_AREAS = 30

/**
 * The sheet under the map: a one-line answer when collapsed, the areas behind it when pulled up.
 * It also says what the app is for, which a bare map does not: where parking is allowed, not
 * which space is free.
 */
@Composable
fun ParkingSheetContent(
    state: SheetState,
    bottomInset: Dp,
    onMessage: (String) -> Unit,
    onShowArea: (NearbyArea) -> Unit,
    onShowZurich: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SheetHeader(state, onShowZurich)
        LazyColumn(Modifier.fillMaxWidth()) {
            if (state is SheetState.Found) {
                items(state.areas.take(MAX_LISTED_AREAS), key = { it.cluster.segments.first().id }) { area ->
                    AreaRow(area, onShowArea, onMessage)
                }
            }
            item { AboutSection() }
            item { Spacer(Modifier.height(bottomInset)) }
        }
    }
}

@Composable
private fun SheetHeader(state: SheetState, onShowZurich: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (state) {
            SheetState.Waiting -> Text("Waiting for your location…", style = MaterialTheme.typography.titleMedium)
            SheetState.Searching -> Text("Looking for parking…", style = MaterialTheme.typography.titleMedium)
            SheetState.NoLocation -> {
                Text("Location is off", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Allow location for parking around you, or look around Zurich.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onShowZurich) { Text("Show Zurich") }
            }
            is SheetState.Found -> {
                Text(NearbyAreas.overviewTitle(state.areas), style = MaterialTheme.typography.titleMedium)
                NearbyAreas.bestBet(state.areas)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SheetState.Empty -> {
                Text("No street parking known here", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Parking Blues covers the City of Zurich.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onShowZurich) { Text("Show Zurich") }
            }
        }
    }
}

@Composable
private fun AreaRow(area: NearbyArea, onShow: (NearbyArea) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onShow(area) }
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val blue = area.cluster.zoneType == ZoneType.BLUE
        Image(
            painterResource(if (blue) CarR.drawable.ic_zone_blue else CarR.drawable.ic_zone_white),
            contentDescription = if (blue) "Blue zone" else "White zone",
            modifier = Modifier.size(36.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(NearbyAreas.title(area), style = MaterialTheme.typography.titleSmall)
            Text(
                NearbyAreas.summary(area),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { navigateTo(context, area, onMessage) }) { Text("Navigate") }
    }
}

@Composable
private fun AboutSection() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("About these results", style = MaterialTheme.typography.titleSmall)
        for (line in ABOUT_LINES) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ABOUT_LINES = listOf(
    "Blue zones are free with a parking disc and time-limited (60–90 minutes) Monday to Saturday, 08:00–18:00.",
    "White zones are metered. The fee shown is an estimate; check the meter.",
    "The city publishes where parking is allowed, not which spaces are free, so a spot may be taken. " +
        "Drive past it and the app moves on to the next one.",
)

private fun navigateTo(context: android.content.Context, area: NearbyArea, onMessage: (String) -> Unit) {
    val uri = Uri.parse("geo:${area.center.latitude},${area.center.longitude}?q=${area.center.latitude},${area.center.longitude}")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        onMessage("No navigation app found")
    }
}
