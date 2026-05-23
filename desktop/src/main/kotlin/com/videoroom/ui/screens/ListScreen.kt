// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.videoroom.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import com.videoroom.LocalAppWindow
import com.videoroom.LocalShiftPressed
import com.videoroom.data.models.VideoSummary
import com.videoroom.ui.components.Tooltip
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.util.FileDragSource
import com.videoroom.viewmodel.GridViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap

@Composable
fun ListScreen(
    viewModel: GridViewModel,
    onVideoSelect: (VideoSummary) -> Unit,
    thumbnailHeight: Dp = 80.dp,
    onConfigureEditors: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val videos = viewModel.videos.collectAsState()
    val selectedVideoId = viewModel.selectedVideoId.collectAsState()
    val selectedVideoIds = viewModel.selectedVideoIds.collectAsState()
    val anchorVideoId = viewModel.anchorVideoId.collectAsState()
    val isLoading = viewModel.isLoading.collectAsState()
    val hasMore = viewModel.hasMore.collectAsState()
    val totalCount = viewModel.totalCount.collectAsState()
    val error = viewModel.error.collectAsState()
    val thumbnails = viewModel.thumbnails.collectAsState()
    val expandedGroupIds = viewModel.expandedGroupIds.collectAsState()
    val expandedMembers = viewModel.expandedGroupMembers.collectAsState()
    val shiftPressed = LocalShiftPressed.current
    val listColumns = viewModel.listColumns.collectAsState()

    val rendered: List<GridItem> = remember(
        videos.value,
        expandedGroupIds.value,
        expandedMembers.value
    ) {
        buildRenderedList(
            videos.value,
            expandedGroupIds.value,
            expandedMembers.value
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Error banner
        if (error.value != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VideoRoomSpacing.Small),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier
                        .padding(VideoRoomSpacing.Medium)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Error: ${error.value}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Tooltip(text = "Dismiss this error message") {
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }

        // Video count
        Text(
            text = "Videos: ${videos.value.size}${if (totalCount.value > 0) " / ${totalCount.value}" else ""}",
            modifier = Modifier.padding(VideoRoomSpacing.Medium),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (videos.value.isEmpty() && !isLoading.value) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "No videos",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))
                    Text(
                        text = "No videos found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(VideoRoomSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = rendered.size,
                        key = { index ->
                            val item = rendered[index]
                            if (item.isStackChild) "child:${item.video.id}" else item.video.id
                        }
                    ) { index ->
                        val item = rendered[index]
                        val video = item.video
                        val isPrimary = selectedVideoId.value == video.id
                        val isInMultiSelect = video.id in selectedVideoIds.value
                        val isAnchor = anchorVideoId.value == video.id &&
                                       selectedVideoIds.value.size > 1

                        // Trigger thumbnail load when row appears
                        LaunchedEffect(video.id) {
                            if (video.hasThumbnail) {
                                viewModel.loadThumbnail(video.id)
                            }
                        }

                        val contextMenuState = remember {
                            androidx.compose.foundation.ContextMenuState()
                        }

                        ContextMenuArea(
                            state = contextMenuState,
                            items = {
                                val multi = selectedVideoIds.value
                                val targets = if (video.id in multi && multi.size > 1) {
                                    videos.value.filter { it.id in multi }.map { it.openPath }
                                } else {
                                    listOf(video.openPath)
                                }
                                buildVideoContextMenu(
                                    targetFiles = targets,
                                    onConfigureEditors = onConfigureEditors,
                                    stackVideoId = video.id.takeIf { video.isInGroup },
                                    stackGroupId = video.groupId.takeIf { video.isInGroup },
                                    onRemoveFromStack = { vid, gid ->
                                        viewModel.removeFromStack(vid, gid)
                                    },
                                    onUnstack = { gid ->
                                        viewModel.unstackGroup(gid)
                                    },
                                    proxyableVideoId = video.id.takeIf { !video.isProxy },
                                    onCreateProxy = { vid -> viewModel.requestCreateProxy(vid) },
                                )
                            }
                        ) {
                            VideoListRow(
                                item = item,
                                isSelected = isPrimary,
                                isInMultiSelection = isInMultiSelect,
                                isAnchor = isAnchor,
                                thumbnailBytes = thumbnails.value[video.id],
                                thumbnailHeight = thumbnailHeight,
                                visibleColumns = listColumns.value,
                                onClick = { shiftFromEvent, toggleFromEvent ->
                                    val shift = shiftFromEvent || shiftPressed
                                    val toggle = toggleFromEvent
                                    when {
                                        shift -> {
                                            val anchorId = anchorVideoId.value ?: video.id
                                            val rangeIds = computeVisualRange(rendered, anchorId, video.id)
                                            viewModel.selectRange(video, rangeIds)
                                        }
                                        toggle -> viewModel.toggleVideoSelection(video)
                                        else -> viewModel.selectVideo(video)
                                    }
                                    onVideoSelect(video)
                                },
                                onDoubleClick = { viewModel.openVideoInExternal(video.openPath) },
                                dragPaths = run {
                                    val multi = selectedVideoIds.value
                                    if (video.id in multi && multi.size > 1) {
                                        videos.value.filter { it.id in multi }.map { it.openPath }
                                    } else {
                                        listOf(video.openPath)
                                    }
                                }
                            )
                        }

                        // Load more when near the end
                        if (!item.isStackChild &&
                            index == rendered.size - 5 &&
                            hasMore.value
                        ) {
                            LaunchedEffect(Unit) {
                                viewModel.loadMore()
                            }
                        }
                    }

                    // Loading indicator at the end
                    if (isLoading.value && hasMore.value) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            // Loading overlay when the list is empty
            if (isLoading.value && videos.value.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun VideoListRow(
    item: GridItem,
    isSelected: Boolean = false,
    isInMultiSelection: Boolean = false,
    isAnchor: Boolean = false,
    thumbnailBytes: ByteArray? = null,
    thumbnailHeight: Dp = 80.dp,
    visibleColumns: Set<String> = emptySet(),
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    /**
     * File paths to transfer when the user drags this row out to an external
     * app. When empty, the row's own [item.video.openPath] is used.
     */
    dragPaths: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val video = item.video
    val thumbnailWidth = thumbnailHeight * 16f / 9f

    val thumbnailImage = remember(thumbnailBytes) {
        thumbnailBytes?.let { bytes ->
            try {
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    // AWT window for drag-out support.
    val awtWindow = LocalAppWindow.current

    // FileDragSource is stateless between gestures.
    val fileDragSource = remember { FileDragSource() }

    // Row background based on selection state
    val rowBackground = when {
        isAnchor -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        isInMultiSelection -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        item.isStackChild -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    val rowModifier = modifier
        .fillMaxWidth()
        .background(rowBackground)
        // Drag-out: detect drag motion in Compose and hand off to AWT.
        .pointerInput(dragPaths, video.openPath) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startPos = down.position
                val effectivePaths = dragPaths.ifEmpty { listOf(video.openPath) }
                fileDragSource.setPendingFiles(effectivePaths)

                var handled = false
                while (!handled) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) {
                        fileDragSource.clearPending()
                        handled = true
                    } else {
                        val delta = change.position - startPos
                        val dist = kotlin.math.sqrt(
                            (delta.x * delta.x + delta.y * delta.y).toDouble()
                        ).toFloat()
                        if (dist >= LIST_ROW_DRAG_THRESHOLD_PX && awtWindow != null) {
                            val winX = awtWindow.x
                            val winY = awtWindow.y
                            val screenX = (winX + change.position.x).toInt()
                            val screenY = (winY + change.position.y).toInt()
                            fileDragSource.startDragIfPending(awtWindow, screenX, screenY)
                            handled = true
                        }
                    }
                }
            }
        }
        .shiftAwareRowClickable(onClick = onClick, onDoubleClick = onDoubleClick)
        .padding(
            start = if (item.isStackChild) (VideoRoomSpacing.Medium + 16.dp) else VideoRoomSpacing.Small,
            end = VideoRoomSpacing.Small,
            top = VideoRoomSpacing.XSmall,
            bottom = VideoRoomSpacing.XSmall
        )

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(thumbnailWidth, thumbnailHeight)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailImage != null) {
                Image(
                    bitmap = thumbnailImage,
                    contentDescription = video.filename,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "No thumbnail",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))

        // Metadata column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Filename — always visible
            Text(
                text = video.filename,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Secondary metadata row — only include columns that are toggled on
            val secondaryParts = buildList {
                if ("resolution" in visibleColumns && video.width > 0 && video.height > 0) {
                    add("${video.width}×${video.height}")
                }
                if ("duration" in visibleColumns) {
                    add(video.durationFormatted)
                }
                if ("fps" in visibleColumns && video.fps > 0) {
                    add(String.format("%.2f fps", video.fps))
                }
                if ("codec" in visibleColumns && video.codecVideo.isNotEmpty()) {
                    add(video.codecVideo)
                }
                if ("filesize" in visibleColumns && video.sizeBytes > 0) {
                    add("${video.sizeBytes / 1_048_576} MB")
                }
                if ("date" in visibleColumns && video.creationDate > 0) {
                    val instant = Instant.ofEpochMilli(video.creationDate)
                    add(dateFormatter.format(instant.atZone(ZoneId.systemDefault())))
                }
            }
            if (secondaryParts.isNotEmpty()) {
                Text(
                    text = secondaryParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Tags row
            if ("tags" in visibleColumns && video.tags.isNotEmpty()) {
                Text(
                    text = video.tags.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Proxy badge on the right edge
        if ("proxy" in visibleColumns && video.proxyCount > 0) {
            Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
            Surface(
                color = Color(0xFF408888),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "P×${video.proxyCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = VideoRoomSpacing.Small, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * A pointer-input modifier that distinguishes single-click from double-click
 * and captures the shift/toggle modifier state from the pointer event.
 * Mirrors [VideoCard]'s `shiftAwareClickable` but works on arbitrary composables
 * (not just Surface/Box with hover).
 */
private fun Modifier.shiftAwareRowClickable(
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit,
    onDoubleClick: () -> Unit
): Modifier = this.pointerInput(onClick, onDoubleClick) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val mods = currentEvent.keyboardModifiers
        val shiftAtDown = mods.isShiftPressed
        val toggleAtDown = mods.isMetaPressed || mods.isCtrlPressed

        waitForUpOrCancellation() ?: return@awaitEachGesture

        val tapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
        val secondDown = withTimeoutOrNull(tapTimeoutMs) {
            awaitFirstDown(requireUnconsumed = false)
        }

        if (secondDown != null) {
            waitForUpOrCancellation()
            onDoubleClick()
        } else {
            onClick(shiftAtDown, toggleAtDown)
        }
    }
}

/** Minimum pointer travel (px) before a press-and-move is treated as a
 *  drag-out gesture in [VideoListRow]. */
private const val LIST_ROW_DRAG_THRESHOLD_PX = 8f
