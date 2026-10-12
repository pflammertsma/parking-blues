// Root build file: plugin declarations only (applied `false` here so the
// actual version is resolved once for the whole build); each module
// applies the ones it needs.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Crash reporting; applied per module only when google-services.json is present.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// One number drives the whole version. Bump `parkingblues.release` for every Play upload and
// everything else follows, so the phone/Android Auto build and the Automotive build always carry
// the same visible version while their codes stay a block of a million apart:
//
//     release 6  ->  versionName "0.1.6" (both apps)
//                    versionCode 1,000,006 (phone) / 2,000,006 (Automotive)
//
// `parkingblues.versionBase` is the major.minor part and only changes on a deliberate, user-visible
// version bump. The two modules read the results from here, so they cannot drift apart.
val releaseNumber = providers.gradleProperty("parkingblues.release").get().toInt()
require(releaseNumber in 1 until 1_000_000) {
    "parkingblues.release must be between 1 and 999,999 (each form factor owns a block of a million codes)"
}
val versionBase = providers.gradleProperty("parkingblues.versionBase").get()
extra["parkingblues.releaseNumber"] = releaseNumber
extra["parkingblues.versionName"] = "$versionBase.$releaseNumber"
