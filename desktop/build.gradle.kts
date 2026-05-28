import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.videoroom"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.compose.ui:ui-util:1.6.1")

    // Kotlin & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // gRPC
    implementation("io.grpc:grpc-netty-shaded:1.59.0")
    implementation("io.grpc:grpc-protobuf:1.59.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.0")
    implementation("com.google.protobuf:protobuf-kotlin:3.24.0")
    implementation("com.google.protobuf:protobuf-java:3.24.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Map rendering. JXMapViewer2 is a pure-Java OpenStreetMap viewer that
    // we embed in Compose Desktop via `SwingPanel`. Chosen over a webview
    // (Google Maps for desktop requires JS embedding) and over commercial
    // SDKs (paid). MIT-licensed, mature, used by tools like JOSM.
    implementation("org.jxmapviewer:jxmapviewer2:2.8")

    // In-app video playback. VLCJ wraps libvlc, which must be installed on
    // the host system (VLC.app on macOS, libvlc package on Linux, VLC for
    // Windows). VLCJ itself only adds ~1MB of jars; no native code is bundled.
    implementation("uk.co.caprica:vlcj:4.8.2")

    // Testing
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

compose.desktop {
    application {
        mainClass = "com.videoroom.AppKt"
        // JNA on JDK 17+ needs reflective access to a few java.* internals to
        // attach native callbacks; without these, EmbeddedMediaPlayerComponent
        // can fail to wire up its event listener (silent: VLCJ swallows the
        // InaccessibleObjectException during its static init).  These same
        // flags are documented by the VLCJ project for modern JDKs.
        jvmArgs += listOf(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
        nativeDistributions {
            // Without this, packageDistributionForCurrentOS has nothing to do
            // and silently produces no artifacts (CI fails uploading an empty dir).
            // Pkg (not Dmg) on macOS — jpackage creates an installer that is then
            // productsigned + notarized by release-desktop.sh.
            targetFormats(TargetFormat.Pkg, TargetFormat.Deb, TargetFormat.Msi)

            packageName = "VideoRoom"
            packageVersion = "0.1.0"
            description = "Video library manager and cataloging tool"
            vendor = "VideoRoom"
            copyright = "2024 VideoRoom Contributors"

            macOS {
                // jpackage on macOS requires MAJOR > 0 in CFBundleVersion /
                // CFBundleShortVersionString — both when building the .app
                // (createDistributable, --type app-image) and the DMG
                // (packageDmg). Deb/Msi accept 0.x. Override the whole
                // macOS chain until we ship 1.0.
                packageVersion = "1.0.0"
                packageBuildVersion = "1.0.0"

                // Sign the app bundle when APPLE_SIGN_IDENTITY is set.
                // Full identity string: "Developer ID Application: Name (TEAMID)"
                System.getenv("APPLE_SIGN_IDENTITY")?.takeIf { it.isNotBlank() }?.let { id ->
                    signing {
                        sign.set(true)
                        identity.set(id)
                    }
                }
            }

            // When release-desktop.sh places the compiled videoroom-core binary in
            // desktop/release-bin/, the packaging step bundles it alongside the
            // application jar. ServerLauncher.kt looks for the binary at
            // <jar dir>/videoroom-core[.exe] — that's where Compose puts
            // appResourcesRootDir files on every platform.
            val releaseBin = project.layout.projectDirectory.dir("release-bin")
            if (releaseBin.asFile.exists()) {
                appResourcesRootDir.set(releaseBin)
            }
        }
    }
}
