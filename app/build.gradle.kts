plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.magames.tecladoia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magames.tecladoia"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"

        ndk {
            // Real phones only (keeps the APK smaller); Vosk ships native libs per ABI.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Stable debug key committed to the repo (free personal project) so every CI build
        // is signed the same way and updates install over the previous version.
        getByName("debug") {
            storeFile = file("teclado-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
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

    androidResources {
        // Do not compress the dictionary so it streams fast
        noCompress += listOf("txt", "zip")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Offline speech recognition (Apache 2.0). The CI "completo" build bundles the pt model zip
    // in assets (downloaded during the build, not committed); otherwise it is downloaded at runtime.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
}
