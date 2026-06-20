// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("com.reelvault.util.FileShell")

/**
 * These helpers are normally called from Compose event handlers, which run on
 * the AWT event-dispatch thread. `Desktop.open()` (macOS Launch Services) and,
 * to a lesser degree, `ProcessBuilder.start()` can block until the OS responds,
 * which freezes the whole UI. So the actual hand-off runs on a short-lived
 * daemon thread; the (cheap) existence check stays synchronous so a missing
 * file still reports `false` immediately. The OS surfaces its own errors, so
 * fire-and-forget is fine.
 */
private fun offThread(name: String, block: () -> Unit) {
    Thread(block, name).apply { isDaemon = true }.start()
}

/** Open a URL in the user's default browser (non-blocking). */
fun openUrl(url: String) {
    offThread("open-url") {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (e: Exception) {
            logger.warn("Failed to open URL {}: {}", url, e.message)
        }
    }
}

/** Hand a file off to the OS's default application (non-blocking). */
fun openWithDefault(filePath: String): Boolean {
    val f = File(filePath)
    if (!f.exists()) return false
    offThread("open-with-default") {
        try {
            java.awt.Desktop.getDesktop().open(f)
        } catch (e: Exception) {
            logger.warn("Failed to open {} in the default app: {}", filePath, e.message)
        }
    }
    return true
}

/**
 * Reveal a file in the OS's native file manager (Finder / Explorer /
 * configured file manager on Linux). Non-blocking.
 */
fun revealInFileManager(filePath: String): Boolean {
    val f = File(filePath)
    if (!f.exists()) return false
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    offThread("reveal-in-file-manager") {
        try {
            when {
                os.contains("mac") || os.contains("darwin") ->
                    ProcessBuilder("/usr/bin/open", "-R", filePath).start()
                os.contains("win") ->
                    ProcessBuilder("explorer.exe", "/select,$filePath").start()
                os.contains("nux") || os.contains("nix") -> {
                    val parent = f.parentFile?.absolutePath
                    if (parent != null) ProcessBuilder("xdg-open", parent).start()
                }
                else -> {}
            }
        } catch (e: Exception) {
            logger.warn("Failed to reveal {} in the file manager: {}", filePath, e.message)
        }
    }
    return true
}
