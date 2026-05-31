// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault

// Single source of truth: root VERSION file at the repository root.
// When bumping the version, update VERSION, this constant, and
// CFBundleShortVersionString in macos/ReelVault/Info.plist together.
// build.gradle.kts reads VERSION for the Gradle `version` property.

internal object AppVersion {
    const val CURRENT      = "0.1.0"
    const val GITHUB_OWNER = "reelvault"
    const val GITHUB_REPO  = "reelvault"
}
