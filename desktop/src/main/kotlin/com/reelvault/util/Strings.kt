// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Localized string accessor backed by Java ResourceBundle.
 * Loads "strings" bundle from the classpath, honoring the JVM default locale.
 * Falls back to the key itself when a translation is missing.
 */
object Strings {
    private val bundle: ResourceBundle by lazy {
        ResourceBundle.getBundle("strings", Locale.getDefault())
    }

    operator fun get(key: String): String =
        try { bundle.getString(key) } catch (_: MissingResourceException) { key }

    fun format(key: String, vararg args: Any?): String =
        MessageFormat.format(get(key), *args)
}
