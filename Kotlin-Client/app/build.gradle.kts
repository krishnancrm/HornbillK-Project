// app/build.gradle.kts

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt") // Required for Glide's annotation processor
    id("org.jetbrains.kotlin.plugin.serialization") // If you are using Kotlin Serialization
}

android {
    namespace = "com.example.hornbillk"
    compileSdk = 34 // Consistent with latest Android SDK

    defaultConfig {
        applicationId = "com.hornbill.k"
        minSdk = 26 // Keep it for broader device compatibility, if desired
        targetSdk = 34 // Always target the latest stable SDK
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Set to true for production apps
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true // ⚡ FIX: ENABLE DATABINDING TO RESOLVE THE PSI SUPERTYPE ERROR
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core AndroidX libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat) // Keep this explicitly for AppCompatActivity and themes
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.annotation)


    // Google Material Design (for UI components like Slider, Buttons, TextInputLayout)
    implementation(libs.google.android.material)
    implementation(libs.androidx.activity.ktx)

    // Lifecycles & Coroutines
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Networking (OkHttp) & JSON Serialization/Deserialization (Gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)

    // Image Loading (Glide)
    implementation(libs.glide)
    kapt(libs.glide.compiler) // Kapt for Glide's annotation processor.

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // Performance Tracing
    implementation(libs.androidx.tracing.perfetto.handshake)
}