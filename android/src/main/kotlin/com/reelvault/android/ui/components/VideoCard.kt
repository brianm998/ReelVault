// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(ExperimentalFoundationApi::class)

package com.reelvault.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.reelvault.android.ui.theme.dimmed
import com.reelvault.android.ui.theme.swatch
import com.reelvault.data.models.ColorLabel
import com.reelvault.data.models.VideoSummary

/**
 * The Android VideoCard used in the library grid.
 *
 * Matches the visual structure of the desktop VideoCard.kt (three-band
 * Lightroom-style layout: stat band / square photo area / rating band) and
 * the iOS VideoGridView card style, adapted for Android touch affordances:
 *
 *   - Coil [AsyncImage] (coil-compose) for async thumbnail loading, with a
 *     film-reel icon placeholder.
 *   - [combinedClickable] for tap + long-press (no hover / scrub on mobile).
 *   - Colour-label band tinting the photo-area background.
 *   - Status badges (proxy, full-res, audio, offline, keyword) via
 *     [CardStatusBadges] at the bottom-trailing corner.
 *   - Group / stack indicator badge at the top-start of the photo area.
 *   - Rating stars in the bottom band (read-only in the grid card; editing
 *     lives in the detail screen).
 *   - Selection state: primary-coloured border + checkmark overlay.
 *   - Multi-select state: slightly muted border + checkmark.
 *   - Offline cards: 50% alpha on the thumbnail + centre offline badge.
 *
 * Desktop-only features deliberately omitted:
 *   - Inline VLCJ player surface.
 *   - Hover scrub frames.
 *   - AWT drag-out to external editors (Android uses the share sheet instead).
 *   - Resize handles and proxy-creation progress strip.
 *
 * @param video          The video to display.
 * @param thumbnailUrl   Coil-compatible URL / model for the thumbnail image.
 *                       Pass `null` to show the film-reel placeholder.
 * @param isSelected     True when the video is the primary (single) selection.
 * @param isMultiSelected True when the video is in an active multi-select set
 *                       but is not the primary selection anchor.
 * @param onClick        Fired on a normal tap. Callers navigate to the detail
 *                       screen or add the video to a multi-select set.
 * @param onLongClick    Fired on a long press. Callers enter multi-select mode
 *                       with this video as the initial selection.
 */
@Composable
fun VideoCard(
    video: VideoSummary,
    thumbnailUrl: String?,
    isSelected: Boolean,
    isMultiSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // ── Colour label ──────────────────────────────────────────────────────
    val colorLabelEnum = ColorLabel.from(video.colorLabel)

    // ── Band colours — mirrors desktop VideoCard three-band palette ───────
    val topBandColor = when {
        isSelected -> Color(0xFFDFDFDF)
        isMultiSelected -> Color(0xFFB3B3B3)
        else -> Color(0xFF6B6B6B)
    }
    val photoAreaBackground = when {
        isSelected -> Color(0xFF999999)
        isMultiSelected -> Color(0xFF707070)
        colorLabelEnum != ColorLabel.None -> colorLabelEnum.dimmed
        else -> Color(0xFF474747)
    }
    val bottomBandColor = when {
        isSelected -> Color(0xFFCFCFCF)
        isMultiSelected -> Color(0xFF9E9E9E)
        else -> Color(0xFF5C5C5C)
    }
    val bandDividerColor = when {
        isSelected || isMultiSelected -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.35f)
    }

    // ── Selection border ──────────────────────────────────────────────────
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isMultiSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val borderWidth = if (isSelected || isMultiSelected) 2.dp else 0.5.dp

    Box(
        modifier = modifier
            .border(borderWidth, borderColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ── Top stat band ─────────────────────────────────────────────
            // Two lines (filename top-left, size top-right) matching the
            // desktop's default top-slot configuration: slot 0 = filename,
            // slot 1 = file size. On Android we use a compact 36 dp height
            // with two lines of text, matching the desktop's band height.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(topBandColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Line 1: filename (left) + resolution name (right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val textColor = if (isSelected || isMultiSelected) Color.Black else Color.White.copy(alpha = 0.92f)
                    Text(
                        text = video.filename,
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val resLabel = resolutionLabel(video.height)
                    if (resLabel.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = resLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
                // Line 2: size (left) + fps (right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val dimColor = if (isSelected || isMultiSelected) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.65f)
                    val sizeStr = formatBytes(video.sizeBytes)
                    Text(
                        text = sizeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = dimColor,
                        maxLines = 1,
                    )
                    if (video.fps > 0) {
                        Text(
                            text = "%.0f fps".format(video.fps),
                            style = MaterialTheme.typography.labelSmall,
                            color = dimColor,
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider(color = bandDividerColor, thickness = 1.dp)

            // ── Square photo area ─────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(photoAreaBackground),
            ) {
                val videoAspect = if (video.width > 0 && video.height > 0)
                    video.width.toFloat() / video.height.toFloat() else 1f
                val isPortrait = videoAspect < 1f

                // Thumbnail (or placeholder)
                val thumbAlpha = if (!video.isOnline) 0.5f else 1f
                if (thumbnailUrl != null) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = video.filename,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(thumbAlpha),
                    ) {
                        when (painter.state) {
                            is AsyncImagePainter.State.Error,
                            is AsyncImagePainter.State.Empty -> {
                                ThumbnailPlaceholder()
                            }
                            else -> SubcomposeAsyncImageContent(
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                } else {
                    ThumbnailPlaceholder(modifier = Modifier.alpha(thumbAlpha))
                }

                // ── Offline overlay ───────────────────────────────────────
                // Dims the thumbnail and centres a badge so the card is
                // obviously unplayable at a glance (mirrors the desktop).
                if (!video.isOnline) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.7f),
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = "Offline",
                                modifier = Modifier.size(14.dp),
                                tint = Color.White,
                            )
                            Text(
                                text = "Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                }

                // ── Group/stack badge (top-start) ─────────────────────────
                // Shown when the video belongs to a stack of variants. On
                // Android it is read-only in the grid (stack expansion lives
                // in the detail screen). The badge sits in the letterbox band
                // above the video, mirroring the desktop and iOS placement.
                if (video.isInGroup) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 6.dp, top = 4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = "Stack of ${video.groupSize}",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${video.groupSize}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // ── Selection checkmark (top-start) ───────────────────────
                // Overlaid when selected or multi-selected. Placed in the
                // top-start corner of the photo area (below the group badge
                // when both are present — only one can be active in practice
                // because multi-select doesn't expand stacks).
                if (isSelected || isMultiSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = if (video.isInGroup) 34.dp else 6.dp, top = 4.dp)
                            .size(20.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(50),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                // ── Status badges (bottom-end) ────────────────────────────
                // Keyword / proxy / full-res / audio / offline. Portrait
                // clips use a vertical column (badges rotate 90° CW).
                CardStatusBadges(
                    video = video,
                    portrait = isPortrait,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = CardBadgeSize, bottom = 4.dp),
                    iconSize = 10.dp,
                )

                // ── Rating stars overlay (bottom-end) ─────────────────────
                // Only the filled stars are shown here (unrated = nothing).
                // The full star editor lives in the detail screen. Sits below
                // the status badges, separated by 4 dp.
                if (video.rating > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = CardBadgeSize, bottom = CardBadgeSize + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        repeat(video.rating) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }

                // ── Colour-label frame on selected + labelled cards ───────
                // When the card is selected AND has a colour label, a thin
                // coloured border wraps the video's own aspect-ratio bounds
                // (not the full square area), mirroring the desktop card.
                if ((isSelected || isMultiSelected) && colorLabelEnum != ColorLabel.None) {
                    val photoPadding = 8.dp
                    val available = maxWidth - photoPadding * 2
                    val videoFrameW = if (videoAspect >= 1f) available else available * videoAspect
                    val videoFrameH = if (videoAspect >= 1f) available / videoAspect else available
                    val frameLine = 3.dp
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(videoFrameW + frameLine * 2, videoFrameH + frameLine * 2)
                            .border(frameLine, colorLabelEnum.swatch),
                    )
                }
            } // photo area

            HorizontalDivider(color = bandDividerColor, thickness = 1.dp)

            // ── Bottom rating band ─────────────────────────────────────────
            // Five positions: filled stars (white) for the current rating,
            // tiny grey dots for unrated positions. Matches the desktop and
            // iOS band height (22 dp on Android vs. 26 dp on desktop — the
            // grid cells are tighter on phone screens).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(bottomBandColor),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                for (pos in 1..5) {
                    val filled = pos <= video.rating
                    Box(
                        modifier = Modifier.size(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (filled) {
                            Box(contentAlignment = Alignment.Center) {
                                // Dark outline ring behind the white star so it
                                // reads on the bright selected-card band.
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF1F1F1F),
                                )
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "$pos star",
                                    modifier = Modifier.size(12.dp),
                                    tint = Color.White,
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .border(0.5.dp, Color(0xFF1F1F1F), RoundedCornerShape(50))
                                    .background(Color(0xFFB8B8B8), RoundedCornerShape(50)),
                            )
                        }
                    }
                    if (pos < 5) Spacer(modifier = Modifier.width(4.dp))
                }
            }
        } // Column
    } // outer Box
}

// ── Placeholder shown when no thumbnail is available ─────────────────────────

@Composable
private fun ThumbnailPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Movie,
            contentDescription = "No thumbnail",
            modifier = Modifier.size(36.dp),
            tint = Color.White.copy(alpha = 0.4f),
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun resolutionLabel(height: Int): String = when {
    height <= 0  -> ""
    height < 480 -> "SD"
    height < 576 -> "480p"
    height < 720 -> "576p"
    height < 1080 -> "720p"
    height < 1440 -> "1080p"
    height < 2160 -> "1440p"
    height < 4320 -> "4K"
    else          -> "8K"
}

private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024        -> "%.2f MB".format(b / (1024.0 * 1024))
    b > 0                    -> "%.2f KB".format(b / 1024.0)
    else                     -> ""
}
