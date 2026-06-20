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

rootProject.name = "reelvault-desktop"

// Allow `./gradlew run` from inside kotlin-desktop/ — shared as a composite build
// keeps its Kotlin classpath isolated from the desktop JVM classpath.
includeBuild("../shared")
