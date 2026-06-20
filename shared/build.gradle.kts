// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import java.io.File

plugins {
    kotlin("jvm") version "1.9.22"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.reelvault"
version = project.file("../VERSION").readText().trim()

repositories {
    mavenCentral()
    google()
}

dependencies {
    // gRPC — transport-agnostic (no Netty, no OkHttp transport here)
    api("io.grpc:grpc-stub:1.59.0")
    api("io.grpc:grpc-protobuf:1.59.0")
    api("io.grpc:grpc-kotlin-stub:1.4.0")
    api("com.google.protobuf:protobuf-kotlin:3.24.0")
    api("com.google.protobuf:protobuf-java:3.24.0")

    // Kotlin & Coroutines
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // OkHttp for the TLS-pinned HTTP client (pairing, fingerprint fetch)
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.24.0"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.59.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("../core/proto")
        }
        java {
            srcDirs("build/generated/source/proto/main/java")
            srcDirs("build/generated/source/proto/main/kotlin")
            srcDirs("build/generated/source/proto/main/grpckt")
        }
    }
}
