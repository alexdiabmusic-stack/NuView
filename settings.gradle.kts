pluginManagement {
    repositories {
        // Google's Maven only hosts Android/Google artifacts; keep it content-filtered so pure
        // Kotlin/JVM modules never touch it (lets :core build and test without the Android SDK).
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "TvEssentials"

include(":core")

// -Ptve.coreOnly=true: JVM-only build of :core (no Android Gradle Plugin, no Google Maven).
if (providers.gradleProperty("tve.coreOnly").orNull == "true") {
    rootProject.buildFileName = "build.core.gradle.kts"
} else {
    include(":app")
}
