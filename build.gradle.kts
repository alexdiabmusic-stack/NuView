// Kotlin plugins are declared once, here, so they load a single time for all modules (Gradle requirement).
// The Android Gradle Plugin is intentionally NOT declared here: only :app applies it, which keeps the pure-JVM
// :core module buildable and testable on machines without access to Google's Maven repository.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
