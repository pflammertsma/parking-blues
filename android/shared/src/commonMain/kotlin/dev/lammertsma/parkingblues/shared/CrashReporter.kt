package dev.lammertsma.parkingblues.shared

/**
 * Crash reporting as the apps see it, independent of the SDK behind it, so the same switch
 * and the same promises hold on every platform. Android implements it with Firebase
 * Crashlytics (AndroidCrashReporter in :car); an iOS app implements it with the Firebase
 * Crashlytics iOS SDK (configured by a GoogleService-Info.plist) and hands it to the same
 * shared code.
 *
 * What an implementation must keep to, on any platform:
 *  - **Content:** a stack trace, the device model and OS version, and the SDK's installation
 *    id. Never location, searches, account data or anything else personal (no custom keys,
 *    no log lines with such data).
 *  - **Default:** reporting is on in release builds and off in debug builds, and the user can
 *    switch it off ([isEnabled]); the choice is remembered and takes effect immediately.
 *  - **Absence:** a build without the SDK configuration (forks, contributors) reports
 *    [isAvailable] = false and does nothing, rather than failing.
 */
interface CrashReporter {
    /** Whether this build can report crashes at all; UI offers the switch only when true. */
    val isAvailable: Boolean

    /** The user's choice. True until they say otherwise; setting it applies it. */
    var isEnabled: Boolean
}

/** For builds and tests without a crash-reporting SDK. */
object NoCrashReporter : CrashReporter {
    override val isAvailable: Boolean = false

    /** Always off; setting it does nothing, because there is nothing to switch. */
    override var isEnabled: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {}
}
