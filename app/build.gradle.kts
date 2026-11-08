import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Private, never committed: secrets.properties (Trakt client id) and keystore.properties (release signing).
fun props(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}
val secrets = props("secrets.properties")
val keystore = props("keystore.properties")

android {
    namespace = "io.github.petebutty.viewarr"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.petebutty.viewarr"
        minSdk = 26
        targetSdk = 34
        versionCode = 27
        versionName = "0.15.0"
        // Trakt device login needs a client id from your own Trakt app (trakt.tv/oauth/applications). Blank = no Trakt.
        // Android TV boxes are ARM; leaving out x86 keeps the APK smaller.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${secrets.getProperty("traktClientId", "")}\"")
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Your release key if keystore.properties exists; otherwise the debug key (fine for trying it out).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses java.nio / java.time APIs that Android below 13 lacks.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        // libmpv is built with a newer Kotlin; its few classes are still readable.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging {
        // libmpv and the FFmpeg decoder each ship libc++_shared.so.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Android TV home screen "Watch Next" row (Continue watching from Viewarr).
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    // NewPipe Extractor (GPL-3.0): finds the playable video behind a YouTube trailer link.
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.nio)
    // Prebuilt FFmpeg audio decoders (DTS, TrueHD, MP2…) for soundtracks the box can't decode itself.
    implementation(libs.media3.ffmpeg)
    // mpv (MIT): the alternative player for films, shows and recordings.
    implementation(libs.libmpv) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.zxing.core)
    implementation(libs.bouncycastle)
}
