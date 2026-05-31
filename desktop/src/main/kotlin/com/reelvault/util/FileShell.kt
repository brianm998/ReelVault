// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.io.File

/** Hand a file off to the OS's default application. */
fun openWithDefault(filePath: String): Boolean {
    return try {
        val f = File(filePath)
        if (!f.exists()) return false
        java.awt.Desktop.getDesktop().open(f)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Reveal a file in the OS's native file manager (Finder / Explorer /
 * configured file manager on Linux).
 */
fun revealInFileManager(filePath: String): Boolean {
    val f = File(filePath)
    if (!f.exists()) return false
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return try {
        when {
            os.contains("mac") || os.contains("darwin") ->
                ProcessBuilder("/usr/bin/open", "-R", filePath).start()
            os.contains("win") ->
                ProcessBuilder("explorer.exe", "/select,$filePath").start()
            os.contains("nux") || os.contains("nix") -> {
                val parent = f.parentFile?.absolutePath ?: return false
                ProcessBuilder("xdg-open", parent).start()
            }
            else -> return false
        }
        true
    } catch (_: Exception) {
        false
    }
}
