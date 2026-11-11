plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    id("dagger.hilt.android.plugin")
}

android {
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "org.liberty.android.freeotp.token_data"
    lint {
        targetSdk = 35
    }
    testOptions {
        targetSdk = 35
    }
}

dependencies {
    implementation(libs.coreKtx)
    implementation(libs.appCompat)

    api(libs.roomRuntime)
    // To use Kotlin annotation processing tool (kapt)
    kapt(libs.roomCompiler)
    // optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.roomKtx)

    implementation(libs.hiltAndroid)
    kapt(libs.hiltAndroidCompiler)

    implementation(libs.bundles.kotlinxCoroutines)
    implementation(libs.gson)

    testImplementation(libs.bundles.androidxJunit)
    testImplementation(libs.androidxTruth)
    androidTestImplementation(libs.bundles.androidxTest)
}