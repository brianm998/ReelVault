// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.data.editors

/** License tier displayed next to each editor in the configuration UI. */
enum class EditorLicense(val display: String) {
    FreeOpenSource("Free / Open Source"),
    Paid("Paid"),
    FreeAndPaid("Free + Paid"),
}

/** Host OS we're currently running on. Detected once at startup. */
enum class HostPlatform {
    MacOS, Windows, Linux, Unknown;

    companion object {
        /** Detect the host platform from JVM system properties. */
        fun detect(): HostPlatform {
            val osName = (System.getProperty("os.name") ?: "").lowercase()
            return when {
                "mac" in osName || "darwin" in osName -> MacOS
                "win" in osName -> Windows
                "nux" in osName || "nix" in osName -> Linux
                else -> Unknown
            }
        }
    }
}

/**
 * A supported external video editor.
 *
 * VideoRoom doesn't bundle these apps — it just knows how to find them on disk
 * and how to launch them with one or more video files. Each entry is read-only
 * data; user configuration (enabled / custom path) lives in [EditorRegistry].
 *
 * @param id Stable string identifier (e.g. "davinci-resolve"). Used as the key
 *   in the persisted config file, so don't rename without a migration.
 * @param name Display name shown in menus and dialogs.
 * @param license Free / paid / mixed.
 * @param homepage Vendor URL where the user can download or learn about the app.
 * @param platforms Operating systems the app is shipped for.
 * @param defaultPaths Best-guess executable locations per platform. The first
 *   one that exists wins. For macOS, paths typically point at a `.app` bundle
 *   and are opened via `open -a`; on Windows/Linux they're direct executables.
 * @param supportsFileArgs If true, VideoRoom will pass the video file paths
 *   as arguments. If false the app is project-based and only the application
 *   itself is launched — VideoRoom shows a hint that the user needs to
 *   import manually.
 * @param notes Human-readable explanation of any caveats — shown in the
 *   configuration dialog so the user knows what to expect.
 */
data class ExternalEditor(
    val id: String,
    val name: String,
    val license: EditorLicense,
    val homepage: String,
    val platforms: Set<HostPlatform>,
    val defaultPaths: Map<HostPlatform, List<String>>,
    val supportsFileArgs: Boolean = false,
    val notes: String = "",
)
