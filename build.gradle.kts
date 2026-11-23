// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
        google()
    }

    dependencies {
        classpath(libs.kotlinGradlePlugin)
        classpath(libs.hiltPlugin)
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37
extra["minSdkVersion"] = 24
extra["versionCode"] = 28
extra["versionName"] = "3.6"
