// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * Diameter of a card status badge on Android. Mirrors [CardBadgeSize] in the
 * desktop's CardStatusBadges.kt — kept at 18 dp so the badges are the same
 * visual size across all clients.
 */
val CardBadgeSize: Dp = 18.dp

/**
 * Vertical inset (above the photo-area bottom edge) for the bottom badges.
 *
 * `gap` is the distance from the photo-area bottom up to the video frame's
 * bottom edge — the bottom letterbox plus the photo padding.
 *
 * Landscape: one badge-height of padding by default, transitioning to
 * "centred in the gap" once the gap shrinks below 3× the badge height.
 * Portrait: one badge-height of flat padding (the video fills the height, so
 * the transition maths are meaningless).
 *
 * Mirrors the identical function in the desktop CardStatusBadges.kt and the
 * macOS `cardBottomBadgeInset(gap:portrait:badge:)` helper.
 */
fun cardBottomBadgeInset(gap: Dp, portrait: Boolean, badge: Dp = CardBadgeSize): Dp =
    if (portrait) badge else minOf(badge, (gap - badge) / 2f).coerceAtLeast(0.dp)

/**
 * The keyword / proxy / full-resolution / audio / online status badges shown
 * at the bottom-trailing corner of a video card.
 *
 * Visual spec (matches desktop CardStatusBadges and iOS CardStatusBadges):
 *   - Landscape cards: a horizontal row, left→right: keyword, proxy,
 *     full-resolution, audio, offline.
 *   - Portrait cards: a vertical column with each badge icon rotated 90° CW,
 *     top→bottom matching the landscape left→right order.
 *   - Each badge: 18 dp circle, 55% opaque black background, coloured icon.
 *     Keyword = light blue; full-resolution = light green; others = white;
 *     offline = orange.
 *
 * [modifier] is applied to the outermost Row/Column so callers can position
 * the group via `Modifier.align(...).padding(...)`.
 */
@Composable
fun CardStatusBadges(
    video: VideoSummary,
    portrait: Boolean = false,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    badge: Dp = CardBadgeSize,
    spacing: Dp = 4.dp,
) {
    val badges = ArrayList<@Composable () -> Unit>(5)

    if (video.tags.isNotEmpty()) {
        badges += {
            StatusBadge(
                icon = Icons.Default.Sell,
                tint = Color(0xFF64B5F6), // light blue — "has keywords"
                contentDescription = "${video.tags.size} keyword${if (video.tags.size == 1) "" else "s"}",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
    }
    if (video.hasProxies) {
        badges += {
            StatusBadge(
                icon = Icons.Default.FilterNone,
                tint = Color.White,
                contentDescription = "${video.proxyCount} proxy/proxies available",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
    }
    when (video.fullResolution) {
        FullResolutionStatus.Full -> badges += {
            StatusBadge(
                icon = Icons.Default.Verified,
                tint = Color(0xFF81C784), // subtle green — "full resolution"
                contentDescription = "Full resolution",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
        FullResolutionStatus.NotFull -> badges += {
            StatusBadge(
                icon = Icons.Default.Crop,
                tint = Color.White,
                contentDescription = "Not full resolution",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
        FullResolutionStatus.Unspecified -> {} // no badge
    }
    if (video.hasAudio) {
        badges += {
            StatusBadge(
                icon = Icons.Default.GraphicEq,
                tint = Color.White,
                contentDescription = "Has an audio track",
                iconSize = iconSize, badge = badge, rotate = portrait,
            )
        }
    }
    if (!video.isOnline) {
        badges += {
            StatusBadge(
                icon = Icons.Default.CloudOff,
                tint = Color(0xFFFF8A65), // orange — offline
                contentDescription = "File offline",
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

// ── Proxy badge ───────────────────────────────────────────────────────────────

/**
 * Standalone proxy-count badge ("P×N"). Used when the caller needs an inline
 * text pill rather than the icon-only circle badge in [CardStatusBadges].
 * Matches the desktop and iOS "P×N" badge text convention.
 */
@Composable
fun ProxyCountBadge(
    proxyCount: Int,
    modifier: Modifier = Modifier,
) {
    if (proxyCount <= 0) return
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "P×$proxyCount",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

// ── HDR badge ─────────────────────────────────────────────────────────────────

/**
 * HDR badge — shown when the daemon classified the video as HDR. Renders as a
 * small pill matching the text-pill style used on iOS/macOS.
 */
@Composable
fun HdrBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xFF7B61FF).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "HDR",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

// ── Internal primitive ────────────────────────────────────────────────────────

@Composable
internal fun StatusBadge(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    iconSize: Dp,
    badge: Dp,
    rotate: Boolean,
) {
    Box(
        modifier = Modifier
            .size(badge)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                .then(if (rotate) Modifier.rotate(90f) else Modifier),
            tint = tint,
        )
    }
}
