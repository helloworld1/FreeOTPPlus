// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
        google()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:7.3.1")
        classpath(kotlin("gradle-plugin", "1.6.10"))
        classpath(libs.hiltPlugin)
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

extra["compileSdkVersion"] = 33
extra["targetSdkVersion"] = 33
extra["minSdkVersion"] = 21
extra["versionCode"] = 21
extra["versionName"] = "3.0"
