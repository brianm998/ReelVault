// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.data.editors

import com.videoroom.data.editors.HostPlatform.*

/**
 * Hard-coded catalog of supported external video editors. Both clients (Kotlin
 * and Swift) ship the same list so the user experience is identical
 * cross-platform. Update [EditorCatalogVersion] when entries change.
 *
 * The CLI behavior was researched per-editor; many pro NLEs are project-based
 * and don't accept raw video paths on the command line. For those we set
 * [ExternalEditor.supportsFileArgs] = false so the launch path just opens the
 * application and the configuration dialog tells the user to drag in manually.
 */
const val EditorCatalogVersion = 1

val EditorCatalog: List<ExternalEditor> = listOf(
    ExternalEditor(
        id = "davinci-resolve",
        name = "DaVinci Resolve",
        license = EditorLicense.FreeAndPaid,
        homepage = "https://www.blackmagicdesign.com/products/davinciresolve",
        platforms = setOf(MacOS, Windows, Linux),
        defaultPaths = mapOf(
            // The free Resolve installer drops the bundle in
            // `/Applications/DaVinci Resolve/` (subdirectory); the paid
            // Studio variant uses a sibling subdirectory + bundle name.
            // We check both, plus the legacy flat-Applications install
            // some users have kept around.
            MacOS to listOf(
                "/Applications/DaVinci Resolve/DaVinci Resolve.app",
                "/Applications/DaVinci Resolve Studio/DaVinci Resolve Studio.app",
                "/Applications/DaVinci Resolve.app"
            ),
            Windows to listOf(
                "C:\\Program Files\\Blackmagic Design\\DaVinci Resolve\\Resolve.exe"
            ),
            Linux to listOf(
                "/opt/resolve/bin/resolve",
                "/usr/bin/resolve"
            ),
        ),
        supportsFileArgs = false,
        notes = "Project-based — VideoRoom launches the app; import the file from " +
            "the Media pool inside Resolve."
    ),
    ExternalEditor(
        id = "kdenlive",
        name = "Kdenlive",
        license = EditorLicense.FreeOpenSource,
        homepage = "https://kdenlive.org",
        platforms = setOf(MacOS, Windows, Linux),
        defaultPaths = mapOf(
            // Kdenlive's macOS bundle shipped as lowercase `kdenlive.app`
            // in older releases and capitalised `Kdenlive.app` in newer
            // ones (KDE's naming standardised). Probe both.
            MacOS to listOf(
                "/Applications/Kdenlive.app",
                "/Applications/kdenlive.app"
            ),
            Windows to listOf(
                "C:\\Program Files\\kdenlive\\bin\\kdenlive.exe"
            ),
            Linux to listOf(
                "/usr/bin/kdenlive",
                "/var/lib/flatpak/exports/bin/org.kde.kdenlive",
                "/snap/bin/kdenlive"
            ),
        ),
        supportsFileArgs = false,
        notes = "Expects a `.kdenlive` project; passing a raw video on the CLI is " +
            "unreliable, so VideoRoom just launches the app."
    ),
    ExternalEditor(
        id = "shotcut",
        name = "Shotcut",
        license = EditorLicense.FreeOpenSource,
        homepage = "https://www.shotcut.org",
        platforms = setOf(MacOS, Windows, Linux),
        defaultPaths = mapOf(
            MacOS to listOf("/Applications/Shotcut.app"),
            Windows to listOf(
                "C:\\Program Files\\Shotcut\\shotcut.exe"
            ),
            Linux to listOf(
                "/usr/bin/shotcut",
                "/var/lib/flatpak/exports/bin/org.shotcut.Shotcut",
                "/snap/bin/shotcut"
            ),
        ),
        // Shotcut's CLI accepts one or more media files directly.
        supportsFileArgs = true,
        notes = "Opens video files passed on the command line directly."
    ),
    ExternalEditor(
        id = "lightworks",
        name = "Lightworks",
        license = EditorLicense.FreeAndPaid,
        homepage = "https://lwks.com",
        platforms = setOf(MacOS, Windows, Linux),
        defaultPaths = mapOf(
            MacOS to listOf("/Applications/Lightworks.app"),
            Windows to listOf(
                "C:\\Program Files\\Lightworks\\lightworks.exe",
                "C:\\Program Files (x86)\\Lightworks\\lightworks.exe"
            ),
            Linux to listOf("/usr/bin/lightworks"),
        ),
        supportsFileArgs = false,
        notes = "Project-based. Requires sign-in. VideoRoom just launches the app."
    ),
    ExternalEditor(
        id = "openshot",
        name = "OpenShot",
        license = EditorLicense.FreeOpenSource,
        homepage = "https://www.openshot.org",
        platforms = setOf(MacOS, Windows, Linux),
        defaultPaths = mapOf(
            MacOS to listOf("/Applications/OpenShot Video Editor.app"),
            Windows to listOf(
                "C:\\Program Files\\OpenShot Video Editor\\openshot-qt.exe"
            ),
            Linux to listOf(
                "/usr/bin/openshot-qt",
                "/usr/local/bin/openshot-qt"
            ),
        ),
        supportsFileArgs = false,
        notes = "Known to reject arbitrary CLI paths — VideoRoom launches the app " +
            "and you drag the file into the timeline."
    ),
    ExternalEditor(
        id = "blender",
        name = "Blender",
        license = EditorLicense.FreeOpenSource,
        homepage = "https://www.blender.org",
        platforms = setOf(MacOS, Windows, Linux),
        defaultPaths = mapOf(
            MacOS to listOf("/Applications/Blender.app"),
            Windows to listOf(
                "C:\\Program Files\\Blender Foundation\\Blender 4.2\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 4.1\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 4.0\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.6\\blender.exe"
            ),
            Linux to listOf(
                "/usr/bin/blender",
                "/var/lib/flatpak/exports/bin/org.blender.Blender",
                "/snap/bin/blender"
            ),
        ),
        supportsFileArgs = false,
        notes = "Blender's CLI accepts `.blend` files, not raw video. VideoRoom " +
            "launches Blender; import the video into the VSE manually."
    ),
    ExternalEditor(
        id = "premiere-pro",
        name = "Adobe Premiere Pro",
        license = EditorLicense.Paid,
        homepage = "https://www.adobe.com/products/premiere.html",
        platforms = setOf(MacOS, Windows),
        defaultPaths = mapOf(
            // Adobe installs each Creative Cloud release in its own
            // versioned folder — newest first so we find the user's
            // current install before stumbling onto an older copy.
            // 2025 + Beta added so people on the current shipping
            // version + early-access channel are detected automatically.
            MacOS to listOf(
                "/Applications/Adobe Premiere Pro 2025/Adobe Premiere Pro 2025.app",
                "/Applications/Adobe Premiere Pro (Beta)/Adobe Premiere Pro (Beta).app",
                "/Applications/Adobe Premiere Pro 2024/Adobe Premiere Pro 2024.app",
                "/Applications/Adobe Premiere Pro 2023/Adobe Premiere Pro 2023.app",
                "/Applications/Adobe Premiere Pro 2022/Adobe Premiere Pro 2022.app"
            ),
            Windows to listOf(
                "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2025\\Adobe Premiere Pro.exe",
                "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2024\\Adobe Premiere Pro.exe",
                "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2023\\Adobe Premiere Pro.exe",
                "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2022\\Adobe Premiere Pro.exe"
            ),
        ),
        // Premiere accepts a media file via CLI only when it's already
        // running and a project is open; otherwise the file is silently
        // dropped on the Home screen. Cold launches take 30–90 s on
        // most machines. Don't pass the file — let the user import via
        // Media Browser once they're in the project they actually want.
        supportsFileArgs = false,
        notes = "Project-based; Creative Cloud subscription required. Cold launch " +
            "can take a minute or more — VideoRoom just opens the app, import the " +
            "file via the Media Browser inside Premiere."
    ),
    ExternalEditor(
        id = "final-cut-pro",
        name = "Final Cut Pro",
        license = EditorLicense.Paid,
        homepage = "https://www.apple.com/final-cut-pro/",
        platforms = setOf(MacOS),
        defaultPaths = mapOf(
            MacOS to listOf("/Applications/Final Cut Pro.app"),
        ),
        // FCP on macOS can import media when passed via `open -a` — try it.
        supportsFileArgs = true,
        notes = "Final Cut imports media when opened via `open -a` on macOS, " +
            "though the sandbox may prompt for permission."
    ),
    ExternalEditor(
        id = "capcut",
        name = "CapCut",
        license = EditorLicense.FreeAndPaid,
        homepage = "https://www.capcut.com",
        platforms = setOf(MacOS, Windows),
        defaultPaths = mapOf(
            MacOS to listOf("/Applications/CapCut.app"),
            Windows to listOf(
                System.getenv("LOCALAPPDATA")?.let { "$it\\CapCut\\CapCut.exe" } ?: "",
                "C:\\Program Files\\CapCut\\CapCut.exe"
            ).filter { it.isNotBlank() },
        ),
        supportsFileArgs = false,
        notes = "Project-based; sign-in required for most features."
    ),
    ExternalEditor(
        id = "filmora",
        name = "Filmora",
        license = EditorLicense.Paid,
        homepage = "https://filmora.wondershare.com",
        platforms = setOf(MacOS, Windows),
        defaultPaths = mapOf(
            // Wondershare publishes Filmora with the version in the
            // bundle name; newer first so we pick the current install.
            // The unversioned bundle is what newer updaters use; the
            // numbered variants are legacy installs that linger.
            MacOS to listOf(
                "/Applications/Wondershare Filmora.app",
                "/Applications/Wondershare Filmora 14.app",
                "/Applications/Wondershare Filmora 13.app",
                "/Applications/Wondershare Filmora 12.app"
            ),
            Windows to listOf(
                "C:\\Program Files\\Wondershare\\Wondershare Filmora 14\\Filmora.exe",
                "C:\\Program Files\\Wondershare\\Wondershare Filmora 13\\Filmora.exe",
                "C:\\Program Files\\Wondershare\\Wondershare Filmora 12\\Filmora.exe"
            ),
        ),
        supportsFileArgs = false,
        notes = "Watermarks exports on the free tier. VideoRoom launches the app; " +
            "import manually."
    ),
)

/** Editors filtered to those that ship on the current host. */
fun editorsForCurrentPlatform(): List<ExternalEditor> {
    val host = HostPlatform.detect()
    return EditorCatalog.filter { host in it.platforms }
}
