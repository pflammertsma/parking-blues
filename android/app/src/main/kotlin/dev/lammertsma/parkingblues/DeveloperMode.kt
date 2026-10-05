package dev.lammertsma.parkingblues

import android.content.Context

private const val PREFS_NAME = "parking_blues_developer"
private const val KEY_UNLOCKED = "unlocked"

/**
 * Whether the hidden developer options (e.g. simulated location) are shown.
 * Off by default in every build; unlocked by long-pressing the version number
 * on the About screen, and remembered until it is hidden again. The options
 * themselves (test location) still reset on every launch.
 */
fun isDeveloperUnlocked(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_UNLOCKED, false)

fun setDeveloperUnlocked(context: Context, unlocked: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_UNLOCKED, unlocked).apply()
}
