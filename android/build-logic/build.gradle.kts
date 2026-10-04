plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the real AGP comes from the root build's plugin classpath;
    // this is just for the types used by the convention plugins.
    compileOnly(libs.android.gradlePlugin)
}
