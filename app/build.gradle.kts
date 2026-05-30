plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.arcisai.nvr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arcisai.nvr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags  += "-std=c++17"
                cFlags    += "-std=c11"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {}
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    // AndroidX core + lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // Networking — OkHttp w/ Basic auth
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted creds storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Media3 (ExoPlayer) — kept for HLS / DASH / playback; RTSP swapped to libVLC
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // libVLC — RTSP player (Media3's RTSP rejects SDPs without fmtp; camera doesn't send it)
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
