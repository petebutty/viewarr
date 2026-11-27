import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Private, never committed: secrets.properties (Trakt client id, report notice channel) and keystore.properties (release signing).
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
        versionCode = 73
        versionName = "0.29.9.7"
        // Trakt device login needs a client id from your own Trakt app (trakt.tv/oauth/applications). Blank = no Trakt.
        // Android TV boxes are ARM; leaving out x86 keeps the APK smaller.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${secrets.getProperty("traktClientId", "")}\"")
        // Send a report's notice channel (ntfy topic): private, so it isn't in the public code. Empty: no notice is sent.
        buildConfigField("String", "REPORT_NOTIFY", "\"${secrets.getProperty("reportNotify", "")}\"")
    }

    // Two apps from one codebase (2026-11-24): Viewarr itself, and "Viewarr launcher", a launcher-style front end
    // (no main menu) on the same live TV, guide, players and libraries. Each has its own app id, so both can be
    // installed side by side; its own viewarr:// link name ("viewarr" / "viewarrl") so links reach the right one.
    flavorDimensions += "app"
    productFlavors {
        create("viewarr") {
            dimension = "app"
            resValue("string", "app_name", "Viewarr")
            buildConfigField("boolean", "LAUNCHER", "false")
            buildConfigField("boolean", "MOBILE", "false")
            buildConfigField("String", "LINK_SCHEME", "\"viewarr\"")
            manifestPlaceholders["linkScheme"] = "viewarr"
        }
        create("launcher") {
            dimension = "app"
            applicationIdSuffix = ".launcher"
            versionNameSuffix = "-launcher"
            // Previewarr's own version numbers: Viewarr's × 100 plus its own build count (bumped for each Previewarr
            // build put out between Viewarr releases), so every one counts as newer: home screens (Projectivy) refresh
            // its icon, and its updates install. (2026-11-25: the same number kept the old icon on the home screen.)
            versionCode = (defaultConfig.versionCode ?: 0) * 100 + 1
            resValue("string", "app_name", "Previewarr")
            buildConfigField("boolean", "LAUNCHER", "true")
            buildConfigField("boolean", "MOBILE", "false")
            buildConfigField("String", "LINK_SCHEME", "\"viewarrl\"")
            manifestPlaceholders["linkScheme"] = "viewarrl"
        }
        // Previewarr Mobile (2026-11-26): Previewarr for phones and tablets, its own app beside it. The same live TV,
        // guide, players and libraries; its own screens, made for fingers (ui/mobile), upright or sideways. The TV
        // Previewarr never shows them. Version numbers: Viewarr's × 100 plus its own build count, as Previewarr's.
        create("mobile") {
            dimension = "app"
            applicationIdSuffix = ".mobile"
            versionNameSuffix = "-mobile"
            versionCode = (defaultConfig.versionCode ?: 0) * 100 + 1
            resValue("string", "app_name", "Previewarr Mobile")
            buildConfigField("boolean", "LAUNCHER", "true")
            buildConfigField("boolean", "MOBILE", "true")
            buildConfigField("String", "LINK_SCHEME", "\"viewarrm\"")
            manifestPlaceholders["linkScheme"] = "viewarrm"
        }
    }

    // Previewarr Mobile uses Previewarr's icon and pictures (app/src/launcher), with its own manifest changes on top.
    sourceSets["mobile"].res.srcDirs("src/launcher/res")
    sourceSets["mobile"].assets.srcDirs("src/launcher/assets")

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
    // Previewarr Mobile only: music keeps playing with the screen off or in another app (a media service).
    "mobileImplementation"(libs.media3.session)
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
