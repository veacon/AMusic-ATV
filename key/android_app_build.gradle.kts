import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

// One release definition also supplies packaging, website metadata and delivery checks.
val releaseVersion = Properties().apply {
    rootProject.file("release-version.properties").inputStream().use { load(it) }
}

// Short git commit the build was cut from — shown next to the version and used by the beta updater to
// tell whether the rolling "dev" prerelease is a different build than the one running. Configuration-cache
// safe via providers.exec; falls back to "unknown" outside a git checkout (e.g. a source tarball).
val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("unknown").get().ifBlank { "unknown" }

android {
    namespace = "com.applemusicktv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.applemusic.lavande"
        minSdk = 23
        targetSdk = 35
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://applemusictv.pages.dev\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Set your proxy server IP here
        // Proxy server URL. Set `proxyBaseUrl` in local.properties (gitignored)
        // to your machine's LAN IP, e.g. http://192.168.1.50:3000/. Falls back
        // to the Android-emulator host loopback. Also overridable in the Dev menu.
        val proxyBaseUrl = localProps.getProperty("proxyBaseUrl") ?: "http://10.0.2.2:3000/"
        buildConfigField("String", "PROXY_BASE_URL", "\"$proxyBaseUrl\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Continue the installed candidate's certificate so adb install -r preserves data.
            // The build type and debuggable flag remain Release regardless of the key's name.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        // lifecycle 2.8.6's checker is binary-incompatible with this Kotlin UAST.
        // This app uses StateFlow, not MutableLiveData; keep all other lint checks enabled.
        disable += "NullSafeMutableLiveData"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("amtv.test.artifacts", project.layout.buildDirectory.dir("test-artifacts").get().asFile.absolutePath)
            System.getenv("ROBOLECTRIC_DEPENDENCY_DIR")?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
    packaging {
        resources.excludes += setOf(
            "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
            "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
            "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties"
        )
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    // tv-foundation 1.0.0 resolves Compose UI 1.10.3; keep device-test APIs aligned.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // TV
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.0.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // FFmpeg audio decoder extension, built from media3 1.4.1 source for armeabi-v7a.
    // Native lib lives in src/main/jniLibs; these are just the FfmpegAudioRenderer
    // classes. Gives on-device HE-AAC decoding that matches the proxy (kills chop).
    implementation(files("libs/media3-ffmpeg.jar"))

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Palette (artwork → accent color)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // AppCompat (for theme)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.56")
    ksp("com.google.dagger:hilt-compiler:2.56")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
