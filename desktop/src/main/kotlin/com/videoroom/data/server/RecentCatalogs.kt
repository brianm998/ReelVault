// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.data.server

import java.io.File
import java.util.Properties

/**
 * Most-recently-opened catalog list. Persisted as a tiny properties file in
 * `~/.config/videoroom/recents.properties`. Entries are stored newest-first
 * with keys `recent.0`, `recent.1`, … and capped at [MaxEntries].
 *
 * This deliberately does not validate that the files still exist — the UI
 * shows them and lets the user pick one even if it was moved; the open
 * call will surface a clear error if the path is now invalid.
 */
class RecentCatalogs(
    private val configFile: File = defaultConfigFile()
) {
    private val props = Properties().also { p ->
        if (configFile.exists()) {
            try { configFile.inputStream().use { p.load(it) } } catch (_: Exception) {}
        }
    }

    /** Snapshot of the recent list, newest-first. Filters out blanks. */
    fun list(): List<String> {
        return (0 until MaxEntries).mapNotNull { i ->
            props.getProperty("recent.$i")?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Move [path] to the head of the recent list. Duplicate paths are
     * collapsed; the list is truncated at [MaxEntries].
     */
    fun touch(path: String) {
        if (path.isBlank()) return
        val current = list().toMutableList()
        current.remove(path)
        current.add(0, path)
        while (current.size > MaxEntries) current.removeAt(current.lastIndex)
        rewrite(current)
    }

    /** Drop [path] from the list entirely (e.g. user said "remove from recents"). */
    fun remove(path: String) {
        val current = list().toMutableList()
        if (current.remove(path)) rewrite(current)
    }

    private fun rewrite(items: List<String>) {
        props.clear()
        items.forEachIndexed { i, p -> props.setProperty("recent.$i", p) }
        try {
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { props.store(it, "VideoRoom recent catalogs") }
        } catch (_: Exception) { /* best-effort */ }
    }

    companion object {
        const val MaxEntries = 10

        fun defaultConfigFile(): File {
            val home = System.getProperty("user.home") ?: "."
            return File(File(home, ".config/videoroom"), "recents.properties")
        }

        /** Singleton — recent list is process-wide. */
        val Default by lazy { RecentCatalogs() }
    }
}
