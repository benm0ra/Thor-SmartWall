plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thor.smartwall"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            // Checked into the repo on purpose: without a stable debug key, a CI runner
            // generates a fresh random one on every build, which makes every new APK look
            // like a different app to Android and forces a full uninstall before installing
            // the next one - silently wiping your saved image/settings on every single update.
            // This keystore is debug-only, has no real security value, and exists purely so
            // rebuilds upgrade in place instead of resetting your data every time.
            storeFile = file("keystore/debug.keystore")
            storePassword = "thorpaperdebug"
            keyAlias = "thorpaperdebugkey"
            keyPassword = "thorpaperdebug"
        }
    }

    defaultConfig {
        applicationId = "com.thor.smartwall"
        // 30+ guarantees both WallpaperService.Engine#getDisplayContext() (added API 29)
        // AND Context#getDisplay() (added API 30, used to read which physical display
        // that context belongs to) are present. The Thor ships Android 13, so this
        // costs nothing in practice.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Mature MediaCodec-based transcoder used to pre-crop a video into per-screen files ONCE,
    // so the wallpaper can then play each with a plain MediaPlayer (smooth) that's already the
    // right shape (split). Avoids hand-rolling a MediaCodec+GL pipeline. https://github.com/natario1/Transcoder
    implementation("com.otaliastudios:transcoder:0.11.2")
}
