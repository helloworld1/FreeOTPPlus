import org.jetbrains.kotlin.config.KotlinCompilerVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
}

android {
    compileSdkVersion(28)
    defaultConfig {
        versionCode = 9
        versionName = "1.8"
        minSdkVersion(21)
        targetSdkVersion(28)
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
    dataBinding {
        isEnabled = true
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
    implementation("androidx.appcompat:appcompat:1.1.0")
    implementation("com.google.android.material:material:1.1.0")
    implementation("com.google.zxing:core:3.4.0")
    implementation("com.google.code.gson:gson:2.8.5")
    implementation("com.squareup.picasso:picasso:2.71828")
    implementation("androidx.core:core-ktx:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.3.2")
    implementation(kotlin("stdlib-jdk7", KotlinCompilerVersion.VERSION))
    implementation("com.google.dagger:dagger:2.22.1")
    implementation("com.google.dagger:dagger-android:2.22.1")
    implementation("com.google.dagger:dagger-android-support:2.22.1")
    implementation("androidx.camera:camera-core:1.0.0-beta01")
    implementation("androidx.camera:camera-camera2:1.0.0-beta01")
    implementation("androidx.camera:camera-lifecycle:1.0.0-beta01")
    implementation("androidx.camera:camera-view:1.0.0-alpha08")
    kapt("com.google.dagger:dagger-compiler:2.22.1")
    kapt("com.google.dagger:dagger-android-processor:2.22.1")
    kapt("com.google.dagger:dagger-android-support:2.22.1")
}
