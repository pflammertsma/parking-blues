package dev.lammertsma.parkingblues.car

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.lammertsma.parkingblues.shared.CrashReporter

/**
 * [CrashReporter] on Android: Firebase Crashlytics. See the interface for what may and may
 * not be reported. Off in debug builds, and a no-op when the build has no
 * google-services.json (Firebase is then never initialised).
 */
class AndroidCrashReporter(private val context: Context) : CrashReporter {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val isAvailable: Boolean
        get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            FirebaseApp.getApps(context).isNotEmpty()

    override var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            applyToSdk()
        }

    /** Applies the stored choice to the SDK; call once from Application.onCreate. */
    fun applyToSdk() {
        if (!isAvailable) return
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(isEnabled)
    }

    private companion object {
        const val PREFS = "parking_blues_privacy"
        const val KEY_ENABLED = "crash_reports"
    }
}
