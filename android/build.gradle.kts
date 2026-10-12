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

// One string drives the whole version: `parkingblues.version` ("major.minor.patch", e.g. 0.2.0) in
// gradle.properties. The versionName is that string as is, for both apps, and each form factor's
// versionCode is computed from it, a block of a million apart:
//
//     versionCode = formFactor * 1,000,000 + major * 10,000 + minor * 100 + patch
//
//     0.1.6  ->  1,000,106 (phone / Android Auto)   2,000,106 (Automotive)
//     0.2.0  ->  1,000,200                          2,000,200
//     1.0.0  ->  1,010,000                          2,010,000
//
// Every part is 0..99, so a higher version is always a higher code. Play never accepts a code twice
// or a lower one than before, so every upload needs a version higher than the last. The two modules
// read the results from here, so they cannot drift apart.
val appVersionName = providers.gradleProperty("parkingblues.version").get()
val versionParts = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersionName)
    ?.groupValues?.drop(1)?.map { it.toInt() }
    ?: error("parkingblues.version must be major.minor.patch with each part 0..99, got '$appVersionName'")
val versionCodeOffset = versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]
// 1000005 and 2000005 were uploaded before this scheme; Play only accepts higher codes than those.
require(versionCodeOffset > 5) { "parkingblues.version $appVersionName is below the codes already uploaded; use 0.0.6 or higher" }
extra["parkingblues.versionName"] = appVersionName
extra["parkingblues.versionCodeOffset"] = versionCodeOffset
