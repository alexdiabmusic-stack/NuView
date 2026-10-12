// All Gradle plugins are declared once, here, so they share a single classloader (Kotlin's Gradle plugin must be able
// to see the Android Gradle Plugin that hosts it). Pure-JVM work on :core does not need any of the Android pieces:
// pass -Ptve.coreOnly=true and settings.gradle.kts swaps in build.core.gradle.kts, which declares no AGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
