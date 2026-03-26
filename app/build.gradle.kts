plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version management - increment versionCode for each release
val appVersionCode = 11  // Increment this for each update
val appVersionName = "1.3.7"  // Human-readable version

android {
    namespace = "com.micmonitor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.device.services.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Make version accessible in code
        buildConfigField("int", "VERSION_CODE", "$appVersionCode")
        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("C:\\Users\\vishw\\micmonitor.jks")
            storePassword = "Durgesh12##"
            keyAlias = "micmonitor"
            keyPassword = "Durgesh12##"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // DISABLED: ProGuard breaks WebRTC native libs
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = false   // looks less like a dev build
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true  // Enable BuildConfig generation
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WebSocket for live streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC (Opus over UDP) for low-latency audio streaming
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Coroutines for background operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // WorkManager — periodic watchdog to restart service if killed
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
