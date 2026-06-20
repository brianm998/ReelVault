pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "reelvault"
// shared is a composite build so each client's Kotlin plugin classpath stays
// isolated — avoids kotlin-jvm vs kotlin-android classpath conflicts.
includeBuild("shared")
include(":kotlin-desktop")
include(":android")
