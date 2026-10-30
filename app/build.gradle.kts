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
        versionCode = 26
        versionName = "5.3"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Fixed key: har build same signature, taki update bina uninstall ke ho
    signingConfigs {
        create("maira") {
            storeFile = rootProject.file("keystore/maira.p12")
            storePassword = "maira123"
            keyAlias = "maira"
            keyPassword = "maira123"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("maira") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("maira") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("onnx") }
}

dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
