import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.nio.file.Files

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.reelvault"
// Version is read from the root VERSION file — the single source of truth.
// Update that file (and AppVersion.kt + macos/ReelVault/Info.plist) when bumping.
version = rootProject.file("../VERSION").readText().trim()

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

// macOS Dock tile says "java" when running an unbundled JVM, because the
// Dock derives its tooltip from NSRunningApplication.localizedName which
// reads the executable's path, *not* any property we can set after the
// JVM has booted. The only knob that actually moves it is a real .app
// bundle with CFBundleName.
//
// For dev runs (`./gradlew run`) we synthesise a throwaway .app inside
// build/tmp/: the executable inside it is a symlink to the JDK's real
// `java` binary, plus a minimal Info.plist. macOS treats it as a proper
// bundle, so the Dock reads "ReelVault" from CFBundleName. The JVM still
// runs the same bytecode — only the launch path changes.
//
// Packaged builds go through jpackage as before and already produce a
// ReelVault.app with the correct Info.plist; this affects only `run`.
val syncDevLaunchBundle by tasks.registering {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    val appDir = layout.buildDirectory.dir("tmp/dev-launch/ReelVault.app")
    val iconSrc = file("src/main/resources/icons/AppIcon.icns")
    outputs.dir(appDir)
    inputs.file(iconSrc)
    doLast {
        val app = appDir.get().asFile
        val macosDir = File(app, "Contents/MacOS")
        val resourcesDir = File(app, "Contents/Resources")
        macosDir.mkdirs()
        resourcesDir.mkdirs()
        // Drop a copy of the icon next to the symlink so the Dock can
        // pick it up via CFBundleIconFile while we're at it.
        if (iconSrc.exists()) {
            iconSrc.copyTo(File(resourcesDir, "AppIcon.icns"), overwrite = true)
        }
        File(app, "Contents/Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>CFBundleExecutable</key><string>ReelVault</string>
              <key>CFBundleName</key><string>ReelVault</string>
              <key>CFBundleDisplayName</key><string>ReelVault</string>
              <key>CFBundleIdentifier</key><string>com.reelvault.dev</string>
              <key>CFBundlePackageType</key><string>APPL</string>
              <key>CFBundleIconFile</key><string>AppIcon</string>
              <key>NSHighResolutionCapable</key><true/>
            </dict>
            </plist>
            """.trimIndent()
        )
        // The symlink target depends on the toolchain, so resolve it
        // late (in the `run` task's doFirst) — see below.
    }
}

compose.desktop {
    application {
        mainClass = "com.reelvault.AppKt"
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
        // macOS Dock tooltip and menu-bar app name. Three knobs:
        //
        // * `-Xdock:name=ReelVault` — parsed by the macOS-aware JVM launcher
        //   (JBR, OpenJDK with Apple's launcher patches). Sets NSApp's name
        //   before any Java code runs.
        // * `-Dapple.awt.application.name=ReelVault` — read by AWT's native
        //   Cocoa init when LWCToolkit boots. Setting this as a JVM `-D`
        //   arg (not via `System.setProperty` in main) is the only reliable
        //   way to land it before AWT init: the Compose entrypoint touches
        //   AWT classes synchronously, and once they load the name is
        //   cached. We tried `System.setProperty` from main() — Dock still
        //   read "java".
        // * `-Dcom.apple.mrj.application.apple.menu.about.name=ReelVault` —
        //   the legacy MRJ property; some older OpenJDK builds still honour
        //   only this one. Harmless if ignored.
        //
        // All three are macOS-only — `-X` options on a Linux/Windows JVM
        // abort startup with "Unrecognized VM option". Gate on the host.
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            jvmArgs += listOf(
                "-Xdock:name=ReelVault",
                "-Dapple.awt.application.name=ReelVault",
                "-Dcom.apple.mrj.application.apple.menu.about.name=ReelVault",
            )
        }
        nativeDistributions {
            // Without this, packageDistributionForCurrentOS has nothing to do
            // and silently produces no artifacts (CI fails uploading an empty dir).
            // Pkg (not Dmg) on macOS — jpackage creates an installer that is then
            // productsigned + notarized by release-desktop.sh.
            targetFormats(TargetFormat.Pkg, TargetFormat.Deb, TargetFormat.Exe)

            packageName = "ReelVault"
            packageVersion = "0.1.0"
            description = "Video library manager and cataloging tool"
            vendor = "ReelVault"
            copyright = "2024 ReelVault Contributors"

            // App-icon files for jpackage. Compose Desktop expects one
            // platform-specific file per target OS — picked up at packaging
            // time and embedded in the .app / installer / .desktop entry.
            val iconsDir = project.layout.projectDirectory.dir("src/main/resources/icons")
            macOS {
                // jpackage on macOS requires MAJOR > 0 in CFBundleVersion /
                // CFBundleShortVersionString — both when building the .app
                // (createDistributable, --type app-image) and the DMG
                // (packageDmg). Deb/Msi accept 0.x. Override the whole
                // macOS chain until we ship 1.0.
                bundleID = "com.reelvault.app"
                packageVersion = "1.0.0"
                packageBuildVersion = "1.0.0"

                iconFile.set(iconsDir.file("AppIcon.icns").asFile)
            }
            linux {
                iconFile.set(iconsDir.file("AppIcon.png").asFile)
            }
            windows {
                iconFile.set(iconsDir.file("AppIcon.ico").asFile)
            }

            // When release-desktop.sh places the compiled reelvault-core binary in
            // desktop/release-bin/, the packaging step bundles it alongside the
            // application jar. ServerLauncher.kt looks for the binary at
            // <jar dir>/reelvault-core[.exe] — that's where Compose puts
            // appResourcesRootDir files on every platform.
            val releaseBin = project.layout.projectDirectory.dir("release-bin")
            if (releaseBin.asFile.exists()) {
                appResourcesRootDir.set(releaseBin)
            }
        }
    }
}

// Wire the dev-launch bundle into Compose's `run` task. We resolve the
// real `java` binary from the configured Java toolchain, symlink it as
// ReelVault.app/Contents/MacOS/ReelVault, and point JavaExec at that
// symlink. macOS sees the parent .app, reads CFBundleName from
// Info.plist, and the Dock tooltip displays "ReelVault" instead of
// "java". Same JVM, same args — only the launch path changes.
afterEvaluate {
    if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX) return@afterEvaluate
    val runTask = tasks.findByName("run") as? JavaExec ?: return@afterEvaluate
    runTask.dependsOn(syncDevLaunchBundle)
    runTask.doFirst {
        val realJava = runTask.javaLauncher.get().executablePath.asFile
        val app = layout.buildDirectory
            .dir("tmp/dev-launch/ReelVault.app").get().asFile
        val exe = File(app, "Contents/MacOS/ReelVault")
        // Refresh the symlink — toolchain path can change between gradle
        // invocations (different JDK, version bump, etc.), so re-resolve
        // every time rather than caching.
        if (exe.exists() || Files.isSymbolicLink(exe.toPath())) {
            exe.delete()
        }
        Files.createSymbolicLink(exe.toPath(), realJava.toPath())
        runTask.executable = exe.absolutePath
    }
}
