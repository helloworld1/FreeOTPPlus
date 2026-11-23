plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
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
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
    }
    testOptions {
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
    }
}

dependencies {
    implementation(libs.coreKtx)
    implementation(libs.appCompat)

    api(libs.room3Runtime)
    api(libs.sqlite)
    api(libs.sqliteFramework)
    ksp(libs.room3Compiler)

    implementation(libs.hiltAndroid)
    ksp(libs.hiltAndroidCompiler)

    implementation(libs.bundles.kotlinxCoroutines)
    implementation(libs.gson)

    testImplementation(libs.bundles.androidxJunit)
    testImplementation(libs.androidxTruth)
    testImplementation(libs.sqliteBundled) {
        attributes {
            attribute(
                org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(org.gradle.api.attributes.java.TargetJvmEnvironment.STANDARD_JVM)
            )
        }
    }
    androidTestImplementation(libs.bundles.androidxTest)
}