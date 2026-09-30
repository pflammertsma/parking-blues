import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.parkingblues.automotive"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as :app on purpose -- Google Play treats the
        // phone build and the Android Automotive OS build as two APKs/AABs
        // under one listing, published together, sharing one signing key.
        // See https://developer.android.com/training/cars/apps/automotive-os
        applicationId = "com.parkingblues.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Both build types point at the same deployed Cloud Run backend --
        // see the matching comment in app/build.gradle.kts. Removes the
        // need to keep a local `python -m backend.app` running just to test
        // the car screens; same live Zurich data either way. To point at a
        // local backend instead (AAOS emulator only -- 10.0.2.2 is the
        // emulator's own loopback alias, it won't resolve on a real device),
        // temporarily change this field back to "http://10.0.2.2:5000".
        debug {
            buildConfigField(
                "String", "BASE_URL",
                "\"https://parking-blues-794638973209.europe-west1.run.app\"",
            )
        }
        release {
            buildConfigField(
                "String", "BASE_URL",
                "\"https://parking-blues-794638973209.europe-west1.run.app\"",
            )
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = JvmTarget.JVM_11.target
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
