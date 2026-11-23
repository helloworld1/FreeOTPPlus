// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
        google()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath(libs.hiltPlugin)
    }
}
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    kotlin("android") version "2.2.10" apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37
extra["minSdkVersion"] = 24
extra["versionCode"] = 28
extra["versionName"] = "3.6"

// Keep module classpaths aligned with Kotlin 2.2.x & AGP 9.1 Lint until the Phase 3 Kotlin/AGP upgrade
subprojects {
    configurations.configureEach {
        if (!name.startsWith("ksp")) {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
                    useVersion("2.2.20")
                }
            }
        }
    }
}
