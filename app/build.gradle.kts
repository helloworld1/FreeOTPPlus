import org.jetbrains.kotlin.config.KotlinCompilerVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
}

android {
    compileSdkVersion(29)
    defaultConfig {
        versionCode = 10
        versionName = "1.9"
        minSdkVersion(21)
        targetSdkVersion(29)
        applicationId = "org.liberty.android.freeotpplus"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "FreeOTP+ Debug")
        }
        getByName("release") {
            resValue("string", "app_name", "FreeOTP+")
        }
    }
    buildFeatures {
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("com.google.android.material:material:1.2.0")


    // Version 3.4.0 contains a crashing bug before api level 24
    implementation("com.google.zxing:core:3.3.3")
    implementation("com.google.code.gson:gson:2.8.5")
    implementation("com.squareup.picasso:picasso:2.71828")
    implementation("androidx.core:core-ktx:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.3.2")
    implementation(kotlin("stdlib-jdk7", KotlinCompilerVersion.VERSION))
    implementation("com.google.dagger:dagger:2.22.1")
    implementation("com.google.dagger:dagger-android:2.22.1")
    implementation("com.google.dagger:dagger-android-support:2.22.1")
    implementation("androidx.camera:camera-core:1.0.0-beta08")
    implementation("androidx.camera:camera-camera2:1.0.0-beta08")
    implementation("androidx.camera:camera-lifecycle:1.0.0-beta08")
    implementation("androidx.camera:camera-view:1.0.0-alpha15")
    implementation("androidx.biometric:biometric:1.0.1")
    kapt("com.google.dagger:dagger-compiler:2.22.1")
    kapt("com.google.dagger:dagger-android-processor:2.22.1")
    kapt("com.google.dagger:dagger-android-support:2.22.1")
}
