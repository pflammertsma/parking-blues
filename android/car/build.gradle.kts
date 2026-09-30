import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Android Auto (phone-projected, via :app) and Android Automotive OS (native,
// via :automotive) run the exact same Screens/Session/CarAppService -- the
// Car App Library docs are explicit that one codebase serves both surfaces.
// Only the manifest wiring differs per platform (see android/README.md), so
// that part stays in :app and :automotive; this module is just the code.
android {
    namespace = "com.parkingblues.car"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JvmTarget.JVM_11.target
    }
}

dependencies {
    api(project(":shared"))
    api(libs.androidx.car.app)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.play.services.location)
    api(libs.osmdroid.android)
    api(libs.kotlinx.coroutines.core)
}
