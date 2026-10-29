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
        versionCode = 12
        versionName = "2.2"
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

dependencies { }
