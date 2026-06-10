// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.time.LocalDate
import java.util.prefs.Preferences

/**
 * Client-side mirror of the core scanner's filename → capture-date inference
 * (`core/src/indexing.rs`, `FilenameDateRule`). Lets the UI preview and apply a
 * date parsed from a video's filename without a round-trip, using the exact
 * same format/position vocabulary the scanner accepts over gRPC.
 *
 * Also owns the persisted *default* inference method, stored in the same
 * `com/reelvault/scanDefaults` Preferences node the Add-Library dialog writes,
 * so a default set in one place (Library settings, the capture-date dialog, or
 * while adding a location) is honored everywhere.
 */
object FilenameDateInference {
    /** (proto value, human label) for the component ordering. */
    val FORMATS: List<Pair<String, String>> = listOf(
        "YYYY-MM-DD" to "Year-Month-Day  (2024-04-24)",
        "MM-DD-YYYY" to "Month-Day-Year  (04-24-2024)",
        "DD-MM-YYYY" to "Day-Month-Year  (24-04-2024)",
    )

    /** (proto value, human label) for where in the name the date must appear. */
    val POSITIONS: List<Pair<String, String>> = listOf(
        "anywhere" to "Anywhere in the name",
        "beginning" to "At the start of the name",
        "end" to "At the end of the name",
    )

    const val DEFAULT_FORMAT = "YYYY-MM-DD"
    const val DEFAULT_POSITION = "anywhere"

    private val prefs: Preferences =
        Preferences.userRoot().node("com/reelvault/scanDefaults")

    /** True when the user has saved a default and left inference enabled. */
    fun defaultEnabled(): Boolean =
        prefs.getBoolean("hasSavedDefaults", false) && prefs.getBoolean("inferDate", false)

    fun defaultFormat(): String = prefs.get("dateFormat", DEFAULT_FORMAT)
    fun defaultPosition(): String = prefs.get("datePosition", DEFAULT_POSITION)

    /** Persist (or clear) the default inference method. Mirrors the keys the
     *  Add-Library dialog reads, so the two stay in sync. */
    fun saveDefault(enabled: Boolean, format: String, position: String) {
        prefs.putBoolean("hasSavedDefaults", true)
        prefs.putBoolean("inferDate", enabled)
        prefs.put("dateFormat", format)
        prefs.put("datePosition", position)
    }

    /**
     * Parse a date out of [filename] using [format] + [position]. Returns null
     * when the pattern doesn't match or the components are out of range. Mirrors
     * `FilenameDateRule::parse_filename` exactly: the extension is stripped,
     * the year is always 4 digits, month/day 1–2, components are separated by a
     * single non-digit, and ranges are validated (month 1–12, day 1–31, year
     * 1900–2999).
     */
    fun inferDate(filename: String, format: String, position: String): LocalDate? {
        val stem = filename.substringBeforeLast('.', filename)
        val (g1, g2, g3) = when (format) {
            "MM-DD-YYYY", "DD-MM-YYYY" -> Triple("(\\d{1,2})", "(\\d{1,2})", "(\\d{4})")
            "YYYY-MM-DD" -> Triple("(\\d{4})", "(\\d{1,2})", "(\\d{1,2})")
            else -> return null
        }
        val body = "$g1\\D$g2\\D$g3"
        val pattern = when (position) {
            "beginning" -> "^$body"
            "end" -> body + "$"
            else -> body
        }
        val match = Regex(pattern).find(stem) ?: return null
        val a = match.groupValues[1].toIntOrNull() ?: return null
        val b = match.groupValues[2].toIntOrNull() ?: return null
        val c = match.groupValues[3].toIntOrNull() ?: return null
        val (year, month, day) = when (format) {
            "MM-DD-YYYY" -> Triple(c, a, b)
            "DD-MM-YYYY" -> Triple(c, b, a)
            "YYYY-MM-DD" -> Triple(a, b, c)
            else -> return null
        }
        if (month !in 1..12 || day !in 1..31 || year !in 1900..2999) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (_: Exception) {
            null // e.g. Feb 30 — a valid-looking pattern that isn't a real date.
        }
    }

    /** For the saved *default* method: the inferred capture timestamp (Unix ms,
     *  noon local — matching the capture-date dialog's date-only commit) and a
     *  short ISO label, or null when no default is configured or [filename] has
     *  no match. Drives the grid/list right-click "Set Capture Date to …". */
    fun inferDefault(filename: String): Pair<Long, String>? {
        if (!defaultEnabled()) return null
        val date = inferDate(filename, defaultFormat(), defaultPosition()) ?: return null
        val ms = date.atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        return ms to date.toString()
    }

    fun formatLabel(value: String): String =
        FORMATS.firstOrNull { it.first == value }?.second ?: value

    fun positionLabel(value: String): String =
        POSITIONS.firstOrNull { it.first == value }?.second ?: value
}
