plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vilas.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vilas.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "3.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
