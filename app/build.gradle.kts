import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing material lives OUTSIDE the repository: either keystore.properties (git-ignored) or CI secrets.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)

// Single source of truth for the version: 1.2.3 -> versionCode 10203.
val appVersionName = "1.0.0"
val versionParts = appVersionName.split(".").map { it.toInt() }

android {
    namespace = "app.tvessentials.installer"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tvessentials.installer"
        minSdk = 24 // Fire OS 6 and newer (Fire OS 7 = API 28, Fire OS 8 = API 30)
        targetSdk = 35
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            signing("storeFile", "TVE_KEYSTORE_FILE")?.let { storeFile = file(it) }
            storePassword = signing("storePassword", "TVE_KEYSTORE_PASSWORD")
            keyAlias = signing("keyAlias", "TVE_KEY_ALIAS")
            keyPassword = signing("keyPassword", "TVE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore material the release APK is left unsigned (and therefore not installable) on purpose.
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
