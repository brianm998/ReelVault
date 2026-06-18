// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "1.9.22"
}

android {
    namespace = "com.reelvault.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.reelvault.android"

        val versionFile = project.file("../VERSION")
        val versionString = versionFile.readText().trim()
        val parts = versionString.split(".")
        val versionCode = (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 +
            (parts.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
            (parts.getOrNull(2)?.toIntOrNull() ?: 0)

        this.versionCode = versionCode.coerceAtLeast(1)
        versionName = versionString

        minSdk = 26
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    // When building from android/ standalone (includeBuild), shared is a
    // composite build referenced by its Maven coordinates.
    // When building from the repo root (include), Gradle substitutes this
    // automatically via the root settings.gradle.kts project reference.
    implementation("com.reelvault:shared")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Activity
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")

    // gRPC OkHttp transport (Android)
    implementation("io.grpc:grpc-okhttp:1.59.0")

    // ExoPlayer / Media3 for HLS playback
    implementation("androidx.media3:media3-exoplayer:1.3.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.0")
    implementation("androidx.media3:media3-ui:1.3.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.0")

    // Maps (OSMDroid - open source, no API key)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Encrypted storage for tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coil for thumbnail loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // SLF4J Android binding
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
