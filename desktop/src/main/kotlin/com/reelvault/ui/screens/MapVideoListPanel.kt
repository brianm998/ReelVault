// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.VideoSummary
import com.reelvault.ui.components.VideoCard
import com.reelvault.ui.theme.ReelVaultSpacing

/**
 * Map-view right panel: the videos at the location(s) the user has selected on
 * the map, rendered with the same [VideoCard] used by the grid. Right-clicking
 * a card offers "Open in Grid / List / Detail" — which switches the main view
 * to that mode focused on the video (the caller wires the actual navigation).
 *
 * This stands in for the metadata inspector (DetailScreen) while the map is the
 * active top-level view.
 */
@Composable
fun MapVideoListPanel(
    videos: List<VideoSummary>,
    /** Show a progress indicator in place of the empty placeholder: a pin was
     *  clicked but its videos are still being resolved (e.g. the filtered
     *  location load is still in flight). Keeps stale/empty content off-screen
     *  while the selection catches up. */
    loading: Boolean,
    thumbnails: Map<String, ByteArray>,
    scrubFrames: Map<String, List<ByteArray?>>,
    currentVideoId: String?,
    onLoadThumbnail: (String) -> Unit,
    onHoverEnter: (String) -> Unit,
    onCardClick: (VideoSummary) -> Unit,
    onOpenInGrid: (VideoSummary) -> Unit,
    onOpenInList: (VideoSummary) -> Unit,
    onOpenInDetail: (VideoSummary) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recessed side-panel background, matching DetailScreen. (DetailScreen uses
    // .background() + per-Text colours rather than a Surface, so it doesn't set
    // LocalContentColor — we must colour our own Text/Icon explicitly, else they
    // default to black and vanish on the dark panel.)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header — title + collapse chevron, matching the details panel.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReelVaultSpacing.Medium, vertical = ReelVaultSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    videos.isNotEmpty() ->
                        "${videos.size} video${if (videos.size == 1) "" else "s"} here"
                    loading -> "Loading…"
                    else -> "Selected location"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            com.reelvault.ui.components.Tooltip(text = "Hide this panel (Tab)") {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Collapse panel",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (videos.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(ReelVaultSpacing.Large)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (loading) {
                    // A location was clicked; its videos are still resolving.
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    Text(
                        text = "Loading videos at this location…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PinDrop,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Small))
                    Text(
                        text = "Click a location on the map to see its videos here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(ReelVaultSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(ReelVaultSpacing.Small),
            ) {
                items(videos, key = { it.id }) { video ->
                    LaunchedEffect(video.id) {
                        if (video.hasThumbnail) onLoadThumbnail(video.id)
                    }
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem("Open in Grid") { onOpenInGrid(video) },
                            ContextMenuItem("Open in List") { onOpenInList(video) },
                            ContextMenuItem("Open in Detail") { onOpenInDetail(video) },
                        )
                    }) {
                        VideoCard(
                            video = video,
                            isSelected = video.id == currentVideoId,
                            thumbnailBytes = thumbnails[video.id],
                            // Hover-scrub through frames, same as grid/list.
                            scrubFrames = scrubFrames[video.id] ?: emptyList(),
                            onHoverEnter = { onHoverEnter(video.id) },
                            onClick = { _, _ -> onCardClick(video) },
                            onDoubleClick = { onOpenInDetail(video) },
                            // Already on the map; the badge would be redundant
                            // and can't re-centre the persistent map peer.
                            onLocationClick = null,
                            // No inline playback in the side panel — opening in
                            // Detail is the way to play.
                            playEnabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
