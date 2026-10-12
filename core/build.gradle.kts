import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

sourceSets {
    test {
        // The tests validate the exact catalog that ships inside the APK.
        resources.srcDir("../app/src/main/assets")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("tve.live", providers.gradleProperty("tve.live").orNull ?: "")
    systemProperty("tve.manifest", rootProject.file("app/src/main/AndroidManifest.xml").absolutePath)
    // Optional local-only fixtures: directory of real, publicly released APKs (never committed).
    systemProperty("tve.fixtureApkDir", providers.gradleProperty("tve.fixtureApkDir").orNull ?: "")
}
