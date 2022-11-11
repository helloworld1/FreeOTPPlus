include(":token-images")
include(":app")
include(":text-drawable")
include(":token-data")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("hilt", "2.44")
            version("appCompat", "1.5.1")
            version("activityKtx", "1.6.1")
            version("material", "1.7.0")
            version("coreKtx", "1.9.0")
            version("kotlinxCoroutines", "1.6.4")
            version("lifecycleKtx", "2.5.1")
            version("cameraX", "1.1.0")
            version("biometric", "1.1.0")

            version("zxing", "3.3.3") // Version 3.4.0 contains a crashing bug before api level 24
            version("gson", "2.9.0")
            version("glide", "4.13.1")
            version("room", "2.4.0")
            version("androidxTestCore", "1.5.0")
            version("androidxTestOrchestrator", "1.4.2")
            version("androidxJunit", "1.1.4")
            version("androidxTruth", "1.5.0")
            version("espresso", "3.5.0")

            library("appCompat", "androidx.appcompat","appcompat").versionRef("appCompat")
            library("material", "com.google.android.material", "material").versionRef("material")
            library("zxing", "com.google.zxing", "core").versionRef("zxing")
            library("gson", "com.google.code.gson", "gson").versionRef("gson")
            library("glide", "com.github.bumptech.glide", "glide").versionRef("glide")
            library("coreKtx", "androidx.core", "core-ktx").versionRef("coreKtx")
            library("activityKtx", "androidx.activity", "activity-ktx").versionRef("activityKtx")
            library("kotlinxCoroutinesCore", "org.jetbrains.kotlinx", "kotlinx-coroutines-core").versionRef("kotlinxCoroutines")
            library("kotlinxCoroutinesAndroid", "org.jetbrains.kotlinx", "kotlinx-coroutines-android").versionRef("kotlinxCoroutines")
            bundle("kotlinxCoroutines", listOf("kotlinxCoroutinesCore", "kotlinxCoroutinesAndroid"))


            library("cameraCore", "androidx.camera", "camera-core").versionRef("cameraX")
            library("cameraCamera2", "androidx.camera", "camera-camera2").versionRef("cameraX")
            library("cameraLifecycle", "androidx.camera", "camera-lifecycle").versionRef("cameraX")
            library("cameraView", "androidx.camera", "camera-view").versionRef("cameraX")
            bundle("cameraX", listOf("cameraCore", "cameraCamera2", "cameraLifecycle", "cameraView"))

            library("biometric", "androidx.biometric", "biometric").versionRef("biometric")

            library("hiltAndroid", "com.google.dagger", "hilt-android").versionRef("hilt")
            library("hiltAndroidCompiler", "com.google.dagger", "hilt-android-compiler").versionRef("hilt")
            library("hiltPlugin", "com.google.dagger", "hilt-android-gradle-plugin").versionRef("hilt")

            library("androidxTestCore","androidx.test", "core").versionRef("androidxTestCore")
            library("androidxTestCoreKtx","androidx.test", "core-ktx").versionRef("androidxTestCore")
            library("androidxTestRules","androidx.test", "rules").versionRef("androidxTestCore")
            library("androidxTestRunner","androidx.test", "runner").versionRef("androidxTestCore")
            library("androidxTestOrchestrator","androidx.test", "orchestrator").versionRef("androidxTestOrchestrator")
            bundle("androidxTest", listOf("androidxTestCore", "androidxTestCoreKtx",
                "androidxTestRules", "androidxTestRunner")
            )

            library("androidxJunit", "androidx.test.ext", "junit").versionRef("androidxJunit")
            library("androidxJunitKtx", "androidx.test.ext", "junit-ktx").versionRef("androidxJunit")
            bundle("androidxJunit", listOf("androidxJunit", "androidxJunitKtx"))

            library("androidxTruth", "androidx.test.ext", "truth").versionRef("androidxTruth")
            library("espressoCore", "androidx.test.espresso", "espresso-core").versionRef("espresso")
            library("espressoContrib", "androidx.test.espresso", "espresso-contrib").versionRef("espresso")
            bundle("espresso", listOf("espressoCore", "espressoContrib"))

            library("roomRuntime", "androidx.room", "room-runtime").versionRef("room")
            library("roomCompiler", "androidx.room", "room-compiler").versionRef("room")
            library("roomKtx", "androidx.room", "room-ktx").versionRef("room")
        }
    }
}