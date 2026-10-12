import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("parkingblues.release-signing")
}

// Crash reporting (Firebase Crashlytics) is optional: it needs google-services.json from
// our Firebase project, which is deliberately not in the repository (it is gitignored).
// Without the file the Firebase plugins are not applied and the app builds and runs as
// before; the crash reporter then reports itself unavailable. See android/README.md.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Shared with the other app module via gradle.properties; see the comment there.
val releaseNumber = providers.gradleProperty("parkingblues.release").get().toInt()

android {
    namespace = "dev.lammertsma.parkingblues.automotive"
    compileSdk = 37

    defaultConfig {
        // Same applicationId as :app on purpose -- Google Play treats the
        // phone build and the Android Automotive OS build as two APKs/AABs
        // under one listing, published together, sharing one signing key.
        // See https://developer.android.com/training/cars/apps/automotive-os
        applicationId = "dev.lammertsma.parkingblues"
        minSdk = 29
        targetSdk = 37
        versionCode = 2 * 1_000_000 + releaseNumber
        versionName = providers.gradleProperty("parkingblues.versionName").get()
    }

    buildTypes {
        debug {
            // Installs next to the release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // :car brings :shared, androidx.car.app, play-services-location/maps,
    // lifecycle-runtime-ktx and kotlinx-coroutines transitively (see its
    // build.gradle.kts) -- the Screens/Session/CarAppService in ParkingCarAppService
    // etc. are shared with :app (Android Auto); only app-automotive (for
    // CarAppActivity, the AAOS launcher) is specific to this module.
    implementation(project(":car"))
    implementation(libs.androidx.car.app.automotive)
}
