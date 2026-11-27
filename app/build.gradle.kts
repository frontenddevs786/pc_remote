plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.pcremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pcremote"
        minSdk = 28            // BluetoothHidDevice needs Android 9+
        targetSdk = 34
        versionCode = 361
        versionName = "3.6.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
