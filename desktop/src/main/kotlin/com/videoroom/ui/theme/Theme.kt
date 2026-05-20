package com.videoroom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand colors (used across both themes)
object VideoRoomColors {
    val PrimaryLight = Color(0xFF6200EE)
    val PrimaryDark = Color(0xFFBB86FC)
    val Secondary = Color(0xFF03DAC6)
    val Tertiary = Color(0xFF1F6FEB)
}

// Use Material 3 defaults but override brand colors.
// Material 3 darkColorScheme/lightColorScheme provide good defaults for
// all the surface/onSurface/background/etc. colors.
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

private val LightColorScheme = lightColorScheme(
    primary = VideoRoomColors.PrimaryLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),

    secondary = VideoRoomColors.Secondary,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFA0F2E8),
    onSecondaryContainer = Color(0xFF002019),

    tertiary = VideoRoomColors.Tertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD8E2FF),
    onTertiaryContainer = Color(0xFF001A41),

    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),

    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),

    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),

    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun VideoRoomTheme(
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
