/*
 * Shared badge rendering for the video cards (grid, list row, expanded-stack
 * row, and the map panel, which reuses the grid card).
 *
 * The several card implementations have historically drifted apart, so the
 * bottom-badge placement maths and the landscape-row / portrait-column status
 * group live here, in one place, and every card calls in.
 */
package com.reelvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.FullResolutionStatus
import com.reelvault.data.models.VideoSummary

/**
 * Diameter of a card status / location badge. The bottom padding and the side
 * insets of the bottom badges are all expressed as multiples of this, so the
 * placement scales together with the badge itself.
 */
val CardBadgeSize: Dp = 18.dp

/**
 * Vertical inset (above the photo-area bottom edge) for the bottom badges.
 *
 * `gap` is the distance from the photo-area bottom up to the video frame's
 * bottom edge — i.e. the bottom letterbox plus the photo padding.
 *
 * Landscape: one badge-height of padding by default, transitioning
 * continuously to "centred in the gap" once fewer than a badge-height of
 * clearance is left between the badge's top and the video's bottom edge
 * (i.e. once `gap < 3·badge`). That is `min(badge, (gap - badge) / 2)`,
 * clamped to ≥ 0 so a near-square clip's badge never spills below the card.
 *
 * Portrait: the bottom gap is ~0 (the video fills the height), so the
 * transition is meaningless — we just use one badge-height of padding and let
 * the status badges move to a vertical column (see [CardStatusBadges]).
 */
fun cardBottomBadgeInset(gap: Dp, portrait: Boolean, badge: Dp = CardBadgeSize): Dp =
    if (portrait) badge else minOf(badge, (gap - badge) / 2f).coerceAtLeast(0.dp)

/**
 * The keyword / proxy / full-resolution status badges shown at the bottom of a
 * video card.
 *
 * Landscape: a horizontal row — left→right keyword, proxy, full-resolution.
 * Portrait: a vertical column hugging the bottom-right side, each badge icon
 * rotated 90° clockwise. The column's top→bottom order matches the landscape
 * left→right order, so the right-most landscape badge ends up at the bottom
 * (nearest the corner).
 *
 * [modifier] is applied to the row/column container — callers pass the
 * `Modifier.align(...).padding(...)` placement from their own BoxScope.
 */
@Composable
fun CardStatusBadges(
    video: VideoSummary,
    portrait: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    badge: Dp = CardBadgeSize,
    spacing: Dp = 4.dp,
) {
    val badges = ArrayList<@Composable () -> Unit>(3)
    if (video.tags.isNotEmpty()) {
        badges += {
            StatusBadge(
                icon = Icons.Default.Sell,
                tint = Color(0xFF64B5F6), // light blue — "has keywords"
                tooltip = "${video.tags.size} keyword${if (video.tags.size == 1) "" else "s"}: ${video.tags.joinToString(", ")}",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
    }
    if (video.hasProxies) {
        badges += {
            StatusBadge(
                icon = Icons.Default.FilterNone,
                tint = Color.White,
                tooltip = "${video.proxyCount} proxy/proxies available for inline playback",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
    }
    when (video.fullResolution) {
        FullResolutionStatus.Full -> badges += {
            StatusBadge(
                icon = Icons.Default.Verified,
                tint = Color(0xFF81C784), // subtle green — "full resolution"
                tooltip = "Full resolution — matches a known native sensor mode for ${video.cameraDisplayName.ifEmpty { "this camera" }}",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
        FullResolutionStatus.NotFull -> badges += {
            StatusBadge(
                icon = Icons.Default.Crop,
                tint = Color.White,
                tooltip = "Not full resolution — recorded dimensions don't match any native sensor mode for ${video.cameraDisplayName.ifEmpty { "this camera" }}",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
        FullResolutionStatus.Unspecified -> {} // intentionally no badge
    }
    if (video.hasAudio) {
        badges += {
            StatusBadge(
                icon = Icons.Default.GraphicEq,
                tint = Color.White,
                tooltip = "Has an audio track",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
    }
    if (badges.isEmpty()) return

    if (portrait) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            badges.forEach { it() }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            badges.forEach { it() }
        }
    }
}

@Composable
private fun StatusBadge(
    icon: ImageVector,
    tint: Color,
    tooltip: String,
    iconSize: Dp,
    badge: Dp,
    rotate: Boolean,
) {
    Tooltip(text = tooltip) {
        Box(
            modifier = Modifier
                .size(badge)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tooltip,
                // Positive degrees rotate clockwise in Compose. The badge
                // background is circular, so only the glyph turns.
                modifier = Modifier
                    .size(iconSize)
                    .then(if (rotate) Modifier.rotate(90f) else Modifier),
                tint = tint,
            )
        }
    }
}
