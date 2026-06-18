// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5B9BD5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1E3A5A),
    onPrimaryContainer = Color(0xFFB8D4F0),
    secondary = Color(0xFF4A9A5C),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF1C1C1C),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF2A2A2A),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF555555),
    error = Color(0xFFCF6679),
    onError = Color(0xFFFFFFFF),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A6BB5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E8FF),
    onPrimaryContainer = Color(0xFF001E36),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1C),
)

@Composable
fun ReelVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
