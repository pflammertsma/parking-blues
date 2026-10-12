import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("parkingblues.release-signing")
    alias(libs.plugins.kotlin.compose)
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
val versionCodeOffset = rootProject.extra["parkingblues.versionCodeOffset"] as Int
val releaseVersionName = rootProject.extra["parkingblues.versionName"] as String

android {
    namespace = "dev.lammertsma.parkingblues"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lammertsma.parkingblues"
        minSdk = 28
        targetSdk = 37
        versionCode = 1 * 1_000_000 + versionCodeOffset
        versionName = releaseVersionName
        // The OAuth *web* client id (audience of Google ID tokens). Empty hides
        // the sign-in menu entry; see deploy/auth-setup.md.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"" + providers.gradleProperty("parkingblues.googleWebClientId").getOrElse("") + "\"",
        )
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
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// The phone app is pure Compose and uses no fragments. The only thing pulling
// androidx.fragment in here is play-services-location (via play-services-base,
// an ancient 1.1.0), used only for FusedLocationProviderClient, so exclude it.
// NOT done in :automotive: its CarAppActivity extends FragmentActivity.
configurations.configureEach {
    exclude(group = "androidx.fragment", module = "fragment")
}

dependencies {
    implementation(project(":shared"))
    // Android Auto support: same ParkingCarAppService/Screens as :automotive
    // uses for Android Automotive OS -- see android/README.md and :car's
    // build.gradle.kts for why this one module serves both.
    implementation(project(":car"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
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
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
}
