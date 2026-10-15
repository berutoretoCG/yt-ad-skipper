plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ytskip"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.ytskip"
        minSdk = 24          // gestures, Quick Settings tile and disableSelf need API 24+
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Fixed key committed to the repo, so every APK built by GitHub has the same
    // signature and new versions install on top of the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

// No extra dependencies: the app only uses the Android framework.
