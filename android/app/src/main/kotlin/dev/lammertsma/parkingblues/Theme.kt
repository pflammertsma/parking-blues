package dev.lammertsma.parkingblues

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Every Material 3 colour role is set explicitly. Leaving roles unset makes
 * them fall back to Material's baseline *purple* scheme -- that was behind the
 * lavender menus and buttons (container/surfaceContainer roles) and, earlier,
 * the near-black "bar" (surface). Neutrals are blue-tinted greys generated
 * from the brand hue; the accent blues are picked for contrast:
 *
 *  - [AppBarBlue] (#227CB7): the brand blue #268BCC darkened just enough that
 *    white text reaches 4.5:1 (the exact brand blue gives 3.7:1).
 *  - `primary` (#1F6FA8): buttons and links, 5.4:1 against white.
 *
 * Contrast of every text/background pair below is at least 4.5:1.
 */
private val AppBarBlue = Color(0xFF227CB7)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE5F7),
    onPrimaryContainer = Color(0xFF00344D),
    inversePrimary = Color(0xFFA9D3FC),
    secondary = Color(0xFF516170),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E4F6),
    onSecondaryContainer = Color(0xFF445362),
    tertiary = Color(0xFF5B5B85),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCCAFB),
    onTertiaryContainer = Color(0xFF42426A),
    error = Color(0xFFA83836),
    onError = Color.White,
    errorContainer = Color(0xFFFA746F),
    onErrorContainer = Color(0xFF6E0A12),
    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF2D3339),
    surface = Color(0xFFF8F9FE),
    onSurface = Color(0xFF2D3339),
    surfaceVariant = Color(0xFFDDE3EB),
    onSurfaceVariant = Color(0xFF5A6067),
    surfaceTint = Color(0xFF1F6FA8),
    inverseSurface = Color(0xFF2D3339),
    inverseOnSurface = Color(0xFFF1F4F9),
    outline = Color(0xFF757B83),
    outlineVariant = Color(0xFFADB2BA),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF8F9FE),
    surfaceDim = Color(0xFFD5DAE3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEAEEF5),
    surfaceContainerHigh = Color(0xFFE4E8F0),
    surfaceContainerHighest = Color(0xFFDDE3EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC3EE),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0E4F77),
    onPrimaryContainer = Color(0xFFCFE8FA),
    inversePrimary = Color(0xFF1F6FA8),
    secondary = Color(0xFFB8C8DA),
    onSecondary = Color(0xFF334250),
    secondaryContainer = Color(0xFF2E3D4B),
    onSecondaryContainer = Color(0xFFB1C1D2),
    tertiary = Color(0xFFDBD9FF),
    onTertiary = Color(0xFF4B4A73),
    tertiaryContainer = Color(0xFF42426A),
    onTertiaryContainer = Color(0xFFDBD9FF),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    background = Color(0xFF0C0E11),
    onBackground = Color(0xFFE0E6EE),
    surface = Color(0xFF0C0E11),
    onSurface = Color(0xFFE0E6EE),
    surfaceVariant = Color(0xFF20262D),
    onSurfaceVariant = Color(0xFFA6ACB3),
    surfaceTint = Color(0xFF7CC3EE),
    inverseSurface = Color(0xFFE0E6EE),
    inverseOnSurface = Color(0xFF2D3339),
    outline = Color(0xFF70767D),
    outlineVariant = Color(0xFF42494F),
    scrim = Color.Black,
    surfaceBright = Color(0xFF272D33),
    surfaceDim = Color(0xFF0C0E11),
    surfaceContainerLowest = Color(0xFF07090B),
    surfaceContainerLow = Color(0xFF101418),
    surfaceContainer = Color(0xFF151A1F),
    surfaceContainerHigh = Color(0xFF1B2025),
    surfaceContainerHighest = Color(0xFF20262D),
)

@Composable
fun ParkingBluesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Brand-blue bar in light mode, a quiet raised surface in dark mode. */
@Composable
fun appBarContainerColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else AppBarBlue

@Composable
fun appBarContentColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.White
