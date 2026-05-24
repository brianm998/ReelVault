// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand colors used by the dark theme.
//
// VideoRoom is dark-mode only. A light theme was intentionally removed —
// the app is a media-browsing surface that's always viewed against
// thumbnails and footage, where a dark chrome is the established
// (Lightroom / Resolve / Final Cut / Premiere) convention.
object VideoRoomColors {
    val PrimaryDark = Color(0xFFBB86FC)
    val Secondary = Color(0xFF03DAC6)
    val Tertiary = Color(0xFF1F6FEB)
}

private val DarkColorScheme = darkColorScheme(
    primary = VideoRoomColors.PrimaryDark,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3700B3),
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = VideoRoomColors.Secondary,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFA0F2E8),

    tertiary = VideoRoomColors.Tertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF003D8A),
    onTertiaryContainer = Color(0xFFD8E2FF),

    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),

    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E1E5),

    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFCAC4D0),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),

    error = Color(0xFFCF6679),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun VideoRoomTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}

object VideoRoomSpacing {
    val XSmall = 4.dp
    val Small = 8.dp
    val Medium = 16.dp
    val Large = 24.dp
    val XLarge = 32.dp
    val XXLarge = 48.dp
}

object VideoRoomCornerRadius {
    val Small = RoundedCornerShape(4.dp)
    val Medium = RoundedCornerShape(8.dp)
    val Large = RoundedCornerShape(12.dp)
}
