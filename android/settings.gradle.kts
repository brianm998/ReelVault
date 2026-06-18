pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "reelvault-android"

// Allow `./gradlew assembleDebug` from inside android/ — include the shared
// module as a composite build so its Kotlin classpath stays isolated from ours.
includeBuild("../shared")
