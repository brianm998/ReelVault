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

// Allow `./gradlew run` from inside desktop/ — wire in the shared module
// the same way the root settings.gradle.kts does.
include(":shared")
project(":shared").projectDir = file("../shared")
