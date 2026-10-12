// Root build script used only with -Ptve.coreOnly=true: builds and tests the pure-JVM :core module without touching
// Google's Maven (useful on machines without the Android SDK). The normal root script is build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
