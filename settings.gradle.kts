pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
include(":token-images")
include(":app")
include(":text-drawable")
include(":token-data")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            version("hilt", "2.59.2")
            version("appCompat", "1.7.1")
            version("activityKtx", "1.13.0")
            version("fragmentKtx", "1.8.6")
            version("navigation", "2.8.9")
            version("material", "1.14.0")
            version("coreKtx", "1.18.0")
            version("kotlinxCoroutines", "1.10.2")
            version("lifecycleKtx", "2.10.0")
            version("cameraX", "1.6.0")
            version("biometric", "1.1.0")
            version("ksp", "2.3.2")

            version("zxing", "3.5.4") // Version 3.4.0 contains a crashing bug before api level 24
            version("gson", "2.14.0")
            version("glide", "5.0.7")
            version("androidSvg", "1.4")
            version("room3", "3.0.3")
            version("sqlite", "2.7.1")
            version("androidxTestCore", "1.7.0")
            version("androidxTestOrchestrator", "1.6.1")
            version("androidxJunit", "1.3.0")
            version("androidxTruth", "1.7.0")
            version("espresso", "3.7.0")
            version("guava", "33.4.0-android")
            version("desugar_jdk_libs", "2.1.5")

            library("appCompat", "androidx.appcompat","appcompat").versionRef("appCompat")
            library("material", "com.google.android.material", "material").versionRef("material")
            library("zxing", "com.google.zxing", "core").versionRef("zxing")
            library("gson", "com.google.code.gson", "gson").versionRef("gson")
            library("glide", "com.github.bumptech.glide", "glide").versionRef("glide")
            library("glideKsp", "com.github.bumptech.glide", "ksp").versionRef("glide")
            library("androidSvg", "com.caverock", "androidsvg-aar").versionRef("androidSvg")
            library("coreKtx", "androidx.core", "core-ktx").versionRef("coreKtx")
            library("activityKtx", "androidx.activity", "activity-ktx").versionRef("activityKtx")
            library("fragmentKtx", "androidx.fragment", "fragment-ktx").versionRef("fragmentKtx")
            library("navigationFragmentKtx", "androidx.navigation", "navigation-fragment-ktx").versionRef("navigation")
            library("navigationUiKtx", "androidx.navigation", "navigation-ui-ktx").versionRef("navigation")
            bundle("navigation", listOf("navigationFragmentKtx", "navigationUiKtx"))
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

            library("room3Runtime", "androidx.room3", "room3-runtime").versionRef("room3")
            library("room3Compiler", "androidx.room3", "room3-compiler").versionRef("room3")
            library("sqlite", "androidx.sqlite", "sqlite").versionRef("sqlite")
            library("sqliteFramework", "androidx.sqlite", "sqlite-framework").versionRef("sqlite")
            library("sqliteBundled", "androidx.sqlite", "sqlite-bundled").versionRef("sqlite")

            library("guava", "com.google.guava", "guava").versionRef("guava")
            library("desugarJdkLibs", "com.android.tools", "desugar_jdk_libs").versionRef("desugar_jdk_libs")

            plugin("ksp", "com.google.devtools.ksp").versionRef("ksp")
            plugin("hilt", "com.google.dagger.hilt.android").versionRef("hilt")
        }
    }
}
