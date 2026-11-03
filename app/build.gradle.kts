plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bucletv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bucletv.app"
        minSdk = 21          // Android 5.0 o superior (cualquier Android TV)
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Firma fija: así cada APK nuevo se instala encima del anterior sin desinstalar.
    signingConfigs {
        create("bucle") {
            storeFile = file("bucletv.jks")
            storePassword = "bucletv123"
            keyAlias = "bucletv"
            keyPassword = "bucletv123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bucle")
        }
        debug {
            signingConfig = signingConfigs.getByName("bucle")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
