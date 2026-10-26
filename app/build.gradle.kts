import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing details live OUTSIDE the repository and must never be committed.
// Default location: ~/.android/clearsave-release.properties (override with the CLEARSAVE_SIGNING environment variable).
// It holds: storeFile, storePassword, keyAlias, keyPassword.
val signingProps = Properties().apply {
    val f = File(System.getenv("CLEARSAVE_SIGNING") ?: "${System.getProperty("user.home")}/.android/clearsave-release.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "app.clearsave"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clearsave"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.3"
        // 64-bit ARM covers practically every phone from the last several years and keeps the APK smaller.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // stays unsigned (and therefore not installable) when no signing settings file exists
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // yt-dlp's Python, ffmpeg and friends ship as native libraries and must be extracted to disk to be executable.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
