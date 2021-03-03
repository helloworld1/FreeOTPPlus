import org.jetbrains.kotlin.config.KotlinCompilerVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
}

android {
    compileSdkVersion(30)
    defaultConfig {
        versionCode = 14
        versionName = "2.3"
        minSdkVersion(21)
        targetSdkVersion(30)
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

dependencies {
    implementation(project(":token-images"))
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("com.google.android.material:material:1.3.0")


    // Version 3.4.0 contains a crashing bug before api level 24
    implementation("com.google.zxing:core:3.3.3")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.squareup.picasso:picasso:2.71828")
    implementation("androidx.core:core-ktx:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.3.9")
    implementation(kotlin("stdlib-jdk7", KotlinCompilerVersion.VERSION))
    implementation("com.google.dagger:dagger:2.28.3")
    implementation("com.google.dagger:dagger-android:2.28.3")
    implementation("com.google.dagger:dagger-android-support:2.28.3")
    implementation("androidx.camera:camera-core:1.0.0-rc02")
    implementation("androidx.camera:camera-camera2:1.0.0-rc02")
    implementation("androidx.camera:camera-lifecycle:1.0.0-rc02")
    implementation("androidx.camera:camera-view:1.0.0-alpha21")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.amulyakhare:com.amulyakhare.textdrawable:1.0.1")

    kapt("com.google.dagger:dagger-compiler:2.28.3")
    kapt("com.google.dagger:dagger-android-processor:2.28.3")
    kapt("com.google.dagger:dagger-android-support:2.28.3")
}
