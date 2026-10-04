package dev.lammertsma.parkingblues

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Not the Material3 baseline purple (the phone UI's previous look, flagged
 * as "barebones/unfinished") -- reuses the same two accents the car screen
 * already established: ZONE_ACCENT from MapSearchScreen's cluster boxes/
 * icons as the primary, and COLOR_YOU (the "you" marker) as the secondary,
 * so the phone UI reads as the same app rather than a generic Material
 * template. Kept as plain Color constants here rather than importing
 * MapSearchScreen's private companion values -- those are an internal
 * rendering detail of that screen, not meant to be a public color API.
 */
private val ParkingBlue = Color(0xFF268BCC)
private val ParkingBlueDark = Color(0xFF1A6999)
private val LocationPurple = Color(0xFF9C27B0)

// Full surface/background set, not just the brand accents: leaving these
// at Material3's own defaults was the actual cause of the chip row's "ugly
// black bar" -- surface defaults to a near-black tone in the baseline dark
// scheme regardless of what primary/secondary are set to, so a bar
// explicitly colored `colorScheme.surface` looked identical to the
// unstyled background it was meant to stand out from. Every tone below is
// chosen relative to ParkingBlue instead of inherited.
private val LightColors = lightColorScheme(
    primary = ParkingBlue,
    onPrimary = Color.White,
    secondary = LocationPurple,
    onSecondary = Color.White,
    tertiary = ParkingBlueDark,
    background = Color(0xFFFAFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFAFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDCE4EA),
    onSurfaceVariant = Color(0xFF42474D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC3EE),
    onPrimary = Color(0xFF00344D),
    secondary = Color(0xFFE1A6F0),
    onSecondary = Color(0xFF4A0060),
    tertiary = Color(0xFF9BD1F2),
    background = Color(0xFF10161C),
    onBackground = Color(0xFFE1E2E5),
    surface = Color(0xFF10161C),
    onSurface = Color(0xFFE1E2E5),
    surfaceVariant = Color(0xFF2B333B),
    onSurfaceVariant = Color(0xFFC1C8CE),
)

@Composable
fun ParkingBluesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
