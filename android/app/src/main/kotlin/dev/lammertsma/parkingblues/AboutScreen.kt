package dev.lammertsma.parkingblues

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val PRIVACY_URL = "https://lammertsma.dev/projects/parking-blues/privacy"
const val TERMS_URL = "https://lammertsma.dev/projects/parking-blues/terms"
const val CONTACT_URL = "mailto:paul@lammertsma.dev"

/**
 * Version, legal links and credits. Long-pressing the version number at the bottom reveals
 * the developer options (kept out of the normal menu so users never see them).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AboutScreen(
    versionLabel: String,
    developerUnlocked: Boolean,
    onUnlockDeveloper: () -> Unit,
    onLockDeveloper: () -> Unit,
    useTestLocation: Boolean,
    onUseTestLocationChanged: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = appBarContainerColor(),
                    titleContentColor = appBarContentColor(),
                    navigationIconContentColor = appBarContentColor(),
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                ListItem(
                    headlineContent = { Text("Parking Blues") },
                    supportingContent = { Text("Find legal on-street parking in Zurich") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Privacy policy") },
                    modifier = Modifier.clickable { onOpenUrl(PRIVACY_URL) },
                )
                ListItem(
                    headlineContent = { Text("Terms of use") },
                    modifier = Modifier.clickable { onOpenUrl(TERMS_URL) },
                )
                ListItem(
                    headlineContent = { Text("Contact") },
                    modifier = Modifier.clickable { onOpenUrl(CONTACT_URL) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Data and maps") },
                    supportingContent = {
                        Text(
                            "Parking data: City of Zurich open data. Map data \u00a9 OpenStreetMap " +
                                "contributors, map tiles \u00a9 CARTO. Not affiliated with the City of Zurich."
                        )
                    },
                )

                if (developerUnlocked) {
                    HorizontalDivider()
                    Text(
                        "Developer options",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                    ListItem(
                        headlineContent = { Text("Use test location") },
                        supportingContent = { Text("Simulated driving in Zurich. Resets when the app restarts.") },
                        trailingContent = { Checkbox(checked = useTestLocation, onCheckedChange = null) },
                        modifier = Modifier.clickable { onUseTestLocationChanged(!useTestLocation) },
                    )
                    ListItem(
                        headlineContent = { Text("Hide developer options") },
                        modifier = Modifier.clickable { onLockDeveloper() },
                    )
                }
            }

            // Version footer. Deliberately plain text with no ripple or other pressed
            // feedback: long-pressing it is a hidden way to reveal developer options.
            Text(
                text = "Version $versionLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onUnlockDeveloper,
                    )
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}
