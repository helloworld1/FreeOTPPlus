plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        testInstrumentationRunnerArguments += mapOf("clearPackageData" to "true")
        versionCode = rootProject.extra["versionCode"] as Int
        versionName = rootProject.extra["versionName"] as String
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        applicationId = "org.liberty.android.freeotpplus"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "FreeOTP+ Debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "FreeOTP+")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }
    namespace = "org.fedorahosted.freeotp"
    testNamespace = "org.fedorahosted.freeotp.uitest"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugarJdkLibs)
    implementation(project(":token-images"))
    implementation(project(":text-drawable"))
    implementation(project(":token-data"))
    implementation(libs.appCompat)
    implementation(libs.material)
    implementation(libs.zxing)
    implementation(libs.gson)
    implementation(libs.glide)
    ksp(libs.glideKsp)
    implementation(libs.androidSvg)
    implementation(libs.coreKtx)
    implementation(libs.activityKtx)
    implementation(libs.fragmentKtx)
    implementation(libs.bundles.lifecycleKtx)
    implementation(libs.bundles.navigation)
    implementation(libs.bundles.kotlinxCoroutines)

    implementation(libs.bundles.cameraX)
    implementation(libs.biometric)
    implementation(libs.guava)

    implementation(libs.hiltAndroid)
    ksp(libs.hiltAndroidCompiler)

    testImplementation(libs.bundles.androidxJunit)
    testImplementation(libs.androidxTruth)

    androidTestImplementation(libs.bundles.androidxTest)
    androidTestUtil(libs.androidxTestOrchestrator)

    androidTestImplementation(libs.bundles.androidxJunit)
    androidTestImplementation(libs.androidxTruth)
    androidTestImplementation(libs.bundles.espresso)
    androidTestImplementation(libs.bundles.navigation)
}
