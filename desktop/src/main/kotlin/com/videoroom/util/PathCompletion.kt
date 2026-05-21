// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.util

import java.io.File

/**
 * Filesystem helpers for the path-completion text field. Pure functions:
 * no caching, no asynchronous work — direct `File.list()` against the
 * parent directory is fast enough for the dozens-of-entries typical in
 * a user's home folder, and avoids the complications of background work
 * invalidating the field's local state.
 */
object PathCompletion {
    /**
     * Snapshot of what the completion engine wants the UI to render.
     * [matches] is every sibling whose name extends what the user typed;
     * [bestMatch] is whichever of those we'd nominate as the most likely
     * next pick (alphabetically first, today). [bestMatch] is null
     * exactly when [matches] is empty.
     */
    data class Result(
        val matches: List<String>,
        val bestMatch: String?,
    )

    /**
     * Resolve [input] into a list of candidate child paths. Caller is
     * responsible for the UI side (ghost rendering, dropdown, Tab).
     *
     * Special cases:
     *   * `~/foo` → expanded to the user's home directory.
     *   * Trailing `/` → list every direct child of that directory.
     *   * Empty → no matches (no point completing nothing).
     */
    fun complete(input: String): Result {
        if (input.isEmpty()) return Result(emptyList(), null)
        val expanded = expandTilde(input)
        val (parentDir, partial) = splitParentAndPartial(expanded)

        val children = directoryChildren(parentDir)
        val wantsDotfiles = partial.startsWith(".")
        val matched = children
            .asSequence()
            .filter { wantsDotfiles || !it.startsWith(".") }
            .filter { partial.isEmpty() || it.startsWith(partial) }
            .map { child ->
                val joined = if (parentDir.endsWith("/")) "$parentDir$child" else "$parentDir/$child"
                if (File(joined).isDirectory) "$joined/" else joined
            }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

        return Result(matched, matched.firstOrNull())
    }

    /**
     * Trim `path` to just the leaf name for display in the dropdown —
     * e.g. `/Users/brian/Downloads/` → `Downloads/`. Keeps trailing slash
     * when present so directories stay distinguishable from files.
     */
    fun displayName(path: String): String {
        val stripped = if (path.endsWith("/")) path.dropLast(1) else path
        val leaf = stripped.substringAfterLast("/")
        return if (path.endsWith("/")) "$leaf/" else leaf
    }

    /**
     * Longest common prefix across [paths]. Used by Tab when there are
     * multiple matches — we extend the user's input to the LCP, then
     * surface the dropdown for the remaining ambiguity.
     */
    fun longestCommonPrefix(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        var prefix = paths.first()
        for (path in paths.drop(1)) {
            while (!path.startsWith(prefix)) {
                prefix = prefix.dropLast(1)
                if (prefix.isEmpty()) return ""
            }
        }
        return prefix
    }

    private fun expandTilde(input: String): String {
        val home = System.getProperty("user.home") ?: return input
        return when {
            input == "~" -> home
            input.startsWith("~/") -> home + input.drop(1)
            else -> input
        }
    }

    /**
     * Decide where the filesystem lookup should happen and what partial
     * leaf name to filter by. Boundary is the last `/`: everything before
     * is the directory we list; everything after is the prefix we filter.
     */
    private fun splitParentAndPartial(path: String): Pair<String, String> {
        if (path.endsWith("/")) return path to ""
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash >= 0) {
            val parent = path.substring(0, lastSlash + 1)
            val partial = path.substring(lastSlash + 1)
            return parent to partial
        }
        // No slash — treat as relative to root for safety. Typing
        // "Vide" without a leading `/` or `~` should yield nothing.
        return "/" to path
    }

    private fun directoryChildren(dir: String): List<String> {
        val f = File(dir)
        if (!f.isDirectory) return emptyList()
        return f.list()?.toList() ?: emptyList()
    }
}
