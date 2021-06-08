import org.jetbrains.kotlin.config.KotlinCompilerVersion

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("dagger.hilt.android.plugin")
}

android {
    compileSdkVersion(AppConfigs.COMPILE_SDK_VERSION)
    defaultConfig {
        versionCode = AppConfigs.VERSION_CODE
        versionName = AppConfigs.VERSION_NAME
        minSdkVersion(AppConfigs.MIN_SDK_VERSION)
        targetSdkVersion(AppConfigs.TARGET_SDK_VERSION)
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
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":token-images"))
    implementation(project(":text-drawable"))
    implementation(project(":token-data"))
    implementation("androidx.appcompat:appcompat:${Versions.APP_COMPAT}")
    implementation("com.google.android.material:material:${Versions.MATERIAL}")

    // Version 3.4.0 contains a crashing bug before api level 24
    implementation("com.google.zxing:core:${Versions.ZXING}")
    implementation("com.google.code.gson:gson:${Versions.GSON}")
    implementation("com.github.bumptech.glide:glide:${Versions.GLIDE}")
    implementation("androidx.core:core-ktx:${Versions.CORE_KTX}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.KOTLINX_COROUTINES}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.KOTLINX_COROUTINES}")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${Versions.LIFECYCLE_KTX}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${Versions.LIFECYCLE_KTX}")
    implementation(kotlin("stdlib-jdk8", Versions.KOTLIN))
    implementation("androidx.camera:camera-core:${Versions.CAMERAX}")
    implementation("androidx.camera:camera-camera2:${Versions.CAMERAX}")
    implementation("androidx.camera:camera-lifecycle:${Versions.CAMERAX}")
    implementation("androidx.camera:camera-view:${Versions.CAMERAX_VIEW}")
    implementation("androidx.biometric:biometric:${Versions.BIOMETRIC}")

    implementation("com.google.dagger:hilt-android:${Versions.HILT}")
    kapt("com.google.dagger:hilt-android-compiler:${Versions.HILT}")

}
