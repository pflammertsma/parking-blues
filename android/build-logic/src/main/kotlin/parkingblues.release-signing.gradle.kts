import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

// Release signing for every application module (:app, :automotive).
// Reads keystore/keystore.properties (git-ignored; back it up together with
// the .jks). If the file is absent, e.g. on CI or a fresh checkout, release
// builds are simply left unsigned instead of failing the build.
val keystorePropsFile = rootProject.file("keystore/keystore.properties")

if (keystorePropsFile.exists()) {
    val keystoreProps = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }

    pluginManager.withPlugin("com.android.application") {
        val android = extensions.getByName("android") as ApplicationExtension
        android.signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        android.buildTypes.getByName("release").signingConfig =
            android.signingConfigs.getByName("release")
    }
}
