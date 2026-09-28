package com.parkingblues.car.location

import android.content.Context

/**
 * Same fixed point as web/app.js's DEFAULT_ORIGIN -- central Zurich, where
 * the real parking data actually is. Real GPS on a phone/tablet being
 * developed on is almost never actually in Zurich, so, exactly like the web
 * MVP (which starts on this point and only switches to real geolocation if
 * you click "Use my location"), this is the default here too: real GPS is
 * the opt-in, not the default.
 */
val TEST_LOCATION: Pair<Double, Double> = 47.379198 to 8.531307

private const val PREFS_NAME = "parking_blues_debug"
private const val KEY_USE_TEST_LOCATION = "use_test_location"

/** Per-install (SharedPreferences), so :app/Android Auto and :automotive
 *  each remember their own choice -- they're separate processes/APKs. */
fun isTestLocationEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_TEST_LOCATION, true)

fun setTestLocationEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_USE_TEST_LOCATION, enabled)
        .apply()
}
