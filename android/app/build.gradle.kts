import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.parkingblues.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parkingblues.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Both build types point at the same deployed Cloud Run backend --
        // real Android Auto/DHU testing is on a USB-connected phone, and
        // its USB link resets often enough (observed repeatedly) to make
        // `adb reverse`-to-localhost unreliable for that. Same live Zurich
        // data either way. To point at a local `python -m backend.app`
        // instead for backend-side debugging, temporarily change this
        // field back to "http://localhost:5000" and run
        // `adb reverse tcp:5000 tcp:5000`.
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
        compose = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = JvmTarget.JVM_11.target
    }
}

dependencies {
    implementation(project(":shared"))
    // Android Auto support: same ParkingCarAppService/Screens as :automotive
    // uses for Android Automotive OS -- see android/README.md and :car's
    // build.gradle.kts for why this one module serves both.
    implementation(project(":car"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
