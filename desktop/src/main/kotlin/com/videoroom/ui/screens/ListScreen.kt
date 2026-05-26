// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.videoroom.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.videoroom.ui.components.ComposeVideoPlayer
import com.videoroom.ui.components.Tooltip
import com.videoroom.ui.components.VlcUnavailableOverlay
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
    val topSlots = viewModel.topSlots.collectAsState()
    val playingVideoId = viewModel.playingVideoId.collectAsState()
    val playingVideoPath = viewModel.playingVideoPath.collectAsState()
    val vlcAvailable = remember { ComposeVideoPlayer.isLibVlcAvailable }
    val inlinePlayer = remember { ComposeVideoPlayer() }
    DisposableEffect(Unit) { onDispose { inlinePlayer.release() } }
    LaunchedEffect(playingVideoId.value) {
        val id = playingVideoId.value ?: run { inlinePlayer.stop(); return@LaunchedEffect }
        val path = playingVideoPath.value
            ?: videos.value.find { it.id == id }?.openPath
            ?: return@LaunchedEffect
        inlinePlayer.load(path, playImmediately = true)
    }
    var showVlcErrorDialog by remember { mutableStateOf(false) }

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

    val displayRows: List<ListDisplayRow> = remember(rendered) {
        buildListDisplayRows(rendered)
    }

    val listState = rememberLazyListState()
    // Scroll to the selected video when this view is first composed, e.g.
    // immediately after switching from grid mode.
    LaunchedEffect(Unit) {
        val selectedId = selectedVideoId.value ?: return@LaunchedEffect
        val idx = displayRows.indexOfFirst { row ->
            when (row) {
                is ListDisplayRow.Single -> row.item.video.id == selectedId
                is ListDisplayRow.HorizontalStack ->
                    row.representative.video.id == selectedId ||
                    row.children.any { it.video.id == selectedId }
            }
        }
        if (idx >= 0) listState.scrollToItem(idx)
    }

    if (showVlcErrorDialog) {
        val osName = System.getProperty("os.name") ?: ""
        val url = when {
            osName.contains("Mac", ignoreCase = true) ->
                "https://www.videolan.org/vlc/download-macosx.html"
            osName.contains("Windows", ignoreCase = true) ->
                "https://www.videolan.org/vlc/download-windows.html"
            else -> "https://www.videolan.org/vlc/"
        }
        AlertDialog(
            onDismissRequest = { showVlcErrorDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("VLC not installed") },
            text = {
                Text(
                    "Inline video playback requires VLC (libvlc) to be installed " +
                    "on this machine."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                    showVlcErrorDialog = false
                }) {
                    Text("Download VLC")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVlcErrorDialog = false }) {
                    Text("Dismiss")
                }
            }
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(VideoRoomSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = displayRows.size,
                        key = { index ->
                            when (val row = displayRows[index]) {
                                is ListDisplayRow.Single ->
                                    if (row.item.isStackChild) "child:${row.item.video.id}"
                                    else row.item.video.id
                                is ListDisplayRow.HorizontalStack ->
                                    "hstack:${row.representative.video.id}"
                            }
                        }
                    ) { index ->
                        when (val row = displayRows[index]) {
                            is ListDisplayRow.Single -> {
                                val item = row.item
                                val video = item.video
                                val isPrimary = selectedVideoId.value == video.id
                                val isInMultiSelect = video.id in selectedVideoIds.value
                                val isAnchor = anchorVideoId.value == video.id &&
                                               selectedVideoIds.value.size > 1

                                LaunchedEffect(video.id) {
                                    if (video.hasThumbnail) {
                                        viewModel.loadThumbnail(video.id)
                                    }
                                }

                                // Collapsed stack reps surface their members
                                // alongside the row's other details, so trigger
                                // a one-shot prefetch when the row scrolls into
                                // view. Cache is shared with the expand path.
                                if (video.isInGroup) {
                                    LaunchedEffect(video.groupId) {
                                        viewModel.ensureStackMembersLoaded(video.groupId)
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
                                        val ratingTargets: List<String> =
                                            if (video.id in multi && multi.size > 1) multi.toList()
                                            else listOf(video.id)
                                        buildVideoContextMenu(
                                            targetFiles = targets,
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
                                            videoPath = video.path,
                                            libraryLocations = viewModel.libraryLocations.value,
                                            onGoToFolder = { path -> viewModel.setLocationFilter(path) },
                                            ratingTargetIds = ratingTargets,
                                            onSetRating = { rating, ids -> viewModel.setRating(rating, ids) },
                                            onSetColorLabel = { label, ids -> viewModel.setColorLabel(label, ids) },
                                            stackMasterCandidate =
                                                if (video.isInGroup && video.id != video.groupPreferredId)
                                                    video.id to video.groupId
                                                else null,
                                            onSetStackMaster = { vid, gid -> viewModel.setStackMaster(vid, gid) },
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
                                        stackMemberFilenames = if (video.isInGroup) {
                                            expandedMembers.value[video.groupId]
                                                ?.filter { it.id != video.id }
                                                ?.map { it.filename }
                                                ?: emptyList()
                                        } else emptyList(),
                                        onStackToggle = { viewModel.toggleStackExpansion(video.groupId) },
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
                                        onSetRating = { rating -> viewModel.setRating(rating, listOf(video.id)) },
                                        dragPaths = run {
                                            val multi = selectedVideoIds.value
                                            if (video.id in multi && multi.size > 1) {
                                                videos.value.filter { it.id in multi }.map { it.openPath }
                                            } else {
                                                listOf(video.openPath)
                                            }
                                        },
                                        isPlayingInline = playingVideoId.value == video.id,
                                        inlinePlayer = inlinePlayer,
                                        playEnabled = vlcAvailable,
                                        onPlayClick = {
                                            when {
                                                !vlcAvailable           -> showVlcErrorDialog = true
                                                !inlinePlayer.available -> showVlcErrorDialog = true
                                                video.playableNatively  -> viewModel.playVideo(video.id)
                                                video.proxyCount > 0    -> viewModel.playVideoPreferProxy(video.id)
                                                else                    -> viewModel.requestCreateProxy(video.id)
                                            }
                                        },
                                        onStopPlayback = { viewModel.stopPlayback() },
                                    )
                                }

                                if (index >= displayRows.size - 5 && hasMore.value) {
                                    LaunchedEffect(Unit) { viewModel.loadMore() }
                                }
                            }

                            is ListDisplayRow.HorizontalStack -> {
                                val allItems = listOf(row.representative) + row.children

                                LaunchedEffect(allItems.map { it.video.id }) {
                                    allItems.forEach { si ->
                                        if (si.video.hasThumbnail) viewModel.loadThumbnail(si.video.id)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(
                                            start = VideoRoomSpacing.Small,
                                            end = VideoRoomSpacing.Small,
                                            top = VideoRoomSpacing.XSmall,
                                            bottom = VideoRoomSpacing.XSmall,
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    allItems.forEachIndexed { idx, stackItem ->
                                        val video = stackItem.video
                                        val isPrimary = selectedVideoId.value == video.id
                                        val isInMultiSelect = video.id in selectedVideoIds.value
                                        val isAnchor = anchorVideoId.value == video.id &&
                                                       selectedVideoIds.value.size > 1

                                        val contextMenuState = remember(video.id) {
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
                                                val ratingTargets: List<String> =
                                                    if (video.id in multi && multi.size > 1) multi.toList()
                                                    else listOf(video.id)
                                                buildVideoContextMenu(
                                                    targetFiles = targets,
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
                                                    videoPath = video.path,
                                                    libraryLocations = viewModel.libraryLocations.value,
                                                    onGoToFolder = { path -> viewModel.setLocationFilter(path) },
                                                    ratingTargetIds = ratingTargets,
                                                    onSetRating = { rating, ids -> viewModel.setRating(rating, ids) },
                                                    onSetColorLabel = { label, ids -> viewModel.setColorLabel(label, ids) },
                                                    stackMasterCandidate =
                                                        if (video.isInGroup && video.id != video.groupPreferredId)
                                                            video.id to video.groupId
                                                        else null,
                                                    onSetStackMaster = { vid, gid -> viewModel.setStackMaster(vid, gid) },
                                                )
                                            }
                                        ) {
                                            VideoListHorizontalCard(
                                                item = stackItem,
                                                isSelected = isPrimary,
                                                isInMultiSelection = isInMultiSelect,
                                                isAnchor = isAnchor,
                                                thumbnailBytes = thumbnails.value[video.id],
                                                thumbnailHeight = thumbnailHeight,
                                                topSlots = topSlots.value,
                                                isRepresentative = idx == 0,
                                                onStackToggle = {
                                                    viewModel.toggleStackExpansion(video.groupId)
                                                },
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
                                                onDoubleClick = {
                                                    viewModel.openVideoInExternal(video.openPath)
                                                },
                                                onSetRating = { rating ->
                                                    viewModel.setRating(rating, listOf(video.id))
                                                }
                                            )
                                        }
                                    }
                                }

                                if (index >= displayRows.size - 5 && hasMore.value) {
                                    LaunchedEffect(Unit) { viewModel.loadMore() }
                                }
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
    /**
     * Filenames of the other members of this row's stack — empty when
     * the video isn't in a stack or the members haven't been fetched
     * yet. Rendered in the info column on collapsed-stack reps so the
     * user can see what the stack contains without expanding it.
     */
    stackMemberFilenames: List<String> = emptyList(),
    onStackToggle: () -> Unit = {},
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    /** Fired when one of the five rating positions is clicked. */
    onSetRating: (Int) -> Unit = {},
    /**
     * File paths to transfer when the user drags this row out to an external
     * app. When empty, the row's own [item.video.openPath] is used.
     */
    dragPaths: List<String> = emptyList(),
    isPlayingInline: Boolean = false,
    inlinePlayer: ComposeVideoPlayer? = null,
    playEnabled: Boolean = true,
    onPlayClick: () -> Unit = {},
    onStopPlayback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val video = item.video
    val isInExpandedStack = item.isExpandedRepresentative || item.isStackChild

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

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Lightroom band palette — mirrors the grid card so list rows and
    // grid cards share visual language. The middle row picks up the
    // colour-label tint when unselected; the bands stay neutral.
    val colorLabelEnum = com.videoroom.data.models.ColorLabel.from(video.colorLabel)
    val rowMiddleBackground = when {
        isAnchor || (isSelected && !isInMultiSelection) -> Color(0xFFF0F0F0)
        isInMultiSelection -> Color(0xFFD7D7D7)
        isInExpandedStack -> Color(0xFF8B8FA0)
        colorLabelEnum != com.videoroom.data.models.ColorLabel.None -> colorLabelEnum.dimmed
        else -> Color(0xFF858585)
    }
    val bottomBandColor = when {
        isAnchor || (isSelected && !isInMultiSelection) -> Color(0xFFF0F0F0)
        isInMultiSelection -> Color(0xFFD7D7D7)
        else -> Color(0xFF999999)
    }
    val bandDividerColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.35f)
    }
    val cardBorderColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.White.copy(alpha = 0.6f)
        else -> Color.Black.copy(alpha = 0.4f)
    }

    // Card width matches `thumbnailHeight` so the card is a strict
    // square in the middle band — same proportions as a grid card,
    // never wider than its grid-mode counterpart. Textual metadata
    // sits alongside (trailing) the card instead of inside it.
    val cardWidth: Dp = thumbnailHeight

    // Outer Row: compact card on the leading edge, info column to its
    // right. The whole row absorbs the row-level click / drag-out
    // gestures so clicking anywhere selects the video.
    val outerModifier = modifier
        .fillMaxWidth()
        .hoverable(interactionSource)
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
        modifier = outerModifier,
        verticalAlignment = Alignment.Top
    ) {
        // ----- Card on the leading edge: top stat band, square
        //       thumbnail, bottom rating band. Width is `cardWidth`
        //       so it never widens past the grid-mode card.
        Column(
            modifier = Modifier
                .width(cardWidth)
                .border(1.dp, cardBorderColor)
        ) {
            // Square thumbnail (cardWidth × thumbnailHeight).
            Box(
                modifier = Modifier
                    .size(cardWidth, thumbnailHeight)
                    .background(rowMiddleBackground)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailImage != null) {
                    Image(
                        bitmap = thumbnailImage,
                        contentDescription = video.filename,
                        modifier = Modifier.fillMaxSize(),
                        // `Fit` (not `Crop`) so non-square footage
                        // letterboxes inside the square — matching
                        // the grid card's behaviour for landscape
                        // clips.
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "No thumbnail",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                // Player surface layered on top of thumbnail.
                if (isPlayingInline && inlinePlayer?.available == true) {
                    inlinePlayer.Surface(modifier = Modifier.fillMaxSize())
                    VlcUnavailableOverlay(player = inlinePlayer)
                } else if (isPlayingInline) {
                    VlcUnavailableOverlay()
                }
                // Play-button overlay — visible on hover when selected and not playing.
                val canPlayInline = video.playableNatively || video.hasProxies
                if (!isPlayingInline && isSelected && isHovered && (canPlayInline || !playEnabled)) {
                    Tooltip(text = if (playEnabled) "Play inline" else "Install VLC to enable inline playback") {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    Color.Black.copy(alpha = if (playEnabled) 0.55f else 0.35f),
                                    RoundedCornerShape(50)
                                )
                                .pointerInput(onPlayClick, playEnabled) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) { up.consume(); onPlayClick() }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = if (playEnabled) "Play inline" else "VLC not installed",
                                modifier = Modifier.size(20.dp),
                                tint = if (playEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
                // Stop button — top-end corner while playing.
                if (isPlayingInline) {
                    Tooltip(
                        text = "Stop playback",
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                                .pointerInput(onStopPlayback) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) { up.consume(); onStopPlayback() }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop playback",
                                modifier = Modifier.size(12.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
                // Stack count badge for collapsed group representatives.
                if (video.isInGroup) {
                    Tooltip(
                        text = if (item.isExpandedRepresentative)
                            "Collapse this stack of ${video.groupSize} videos"
                        else
                            "Expand this stack to see all ${video.groupSize} variants",
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(3.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    shape = MaterialTheme.shapes.extraSmall
                                )
                                .clickable(onClick = onStackToggle)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${video.groupSize}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(bandDividerColor)
            )
            // Bottom rating band: 5 star/dot positions.
            Tooltip(text = "Click a star to rate 1–5. Click the current rating again to clear it.") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(bottomBandColor),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                for (position in 1..5) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                if (video.rating == position) onSetRating(0)
                                else onSetRating(position)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (position <= video.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Rating $position",
                                tint = Color.Black,
                                modifier = Modifier.size(11.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(
                                        Color(0xFF595959),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                }
            }
            } // Tooltip
        }

        Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))

        // ----- Info column: filename, secondary tech line, tags. Lives
        //       *alongside* the card rather than inside it so the
        //       card's geometry matches the grid card exactly.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = video.filename,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

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
            if ("tags" in visibleColumns && video.tags.isNotEmpty()) {
                Text(
                    text = video.tags.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if ("proxy" in visibleColumns && video.proxyCount > 0) {
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
            // Collapsed stack: list the other members of the stack so the
            // user can see what's inside without expanding. Skipped when
            // the row is expanded (members are shown as cards instead) or
            // when the prefetch hasn't completed yet.
            if (!isInExpandedStack && stackMemberFilenames.isNotEmpty()) {
                Text(
                    text = "Stack: ${stackMemberFilenames.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Stat cell rendered inside the list-row's top band. Click anywhere in
 *  the cell to open the stat-picker dropdown. Mirrors VideoCard.StatCell
 *  in look + behaviour but lives here so ListScreen owns its own card. */
@Composable
private fun RowScope.ListRowStatCell(
    slotIndex: Int,
    key: String,
    video: VideoSummary,
    onPick: (Int, String) -> Unit,
    alignEnd: Boolean,
    weight: Float,
) {
    val stat = com.videoroom.data.models.GridStatKey.fromRaw(key)
    val value = stat.valueFor(video)
    val displayed: String =
        if (stat == com.videoroom.data.models.GridStatKey.None) "—" else value
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .weight(weight)
            .heightIn(min = 14.dp)
            .clickable { expanded = true },
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text = displayed,
            style = if (slotIndex == 0) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelSmall,
            color = if (stat == com.videoroom.data.models.GridStatKey.None)
                Color.Black.copy(alpha = 0.4f)
            else
                Color.Black.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End
                        else androidx.compose.ui.text.style.TextAlign.Start
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            com.videoroom.data.models.GridStatKey.values().forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (choice == stat) "✓ ${choice.displayName}"
                            else choice.displayName
                        )
                    },
                    onClick = {
                        onPick(slotIndex, choice.raw)
                        expanded = false
                    }
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

// ─── Horizontal stack expansion ──────────────────────────────────────────────

private sealed class ListDisplayRow {
    data class Single(val item: GridItem) : ListDisplayRow()
    /** An expanded stack: representative + already-loaded children laid out horizontally. */
    data class HorizontalStack(
        val representative: GridItem,
        val children: List<GridItem>
    ) : ListDisplayRow()
}

private fun buildListDisplayRows(rendered: List<GridItem>): List<ListDisplayRow> {
    val result = mutableListOf<ListDisplayRow>()
    var i = 0
    while (i < rendered.size) {
        val item = rendered[i]
        if (item.isExpandedRepresentative) {
            val children = mutableListOf<GridItem>()
            var j = i + 1
            while (j < rendered.size && rendered[j].isStackChild) {
                children.add(rendered[j])
                j++
            }
            result.add(ListDisplayRow.HorizontalStack(item, children))
            i = j
        } else {
            result.add(ListDisplayRow.Single(item))
            i++
        }
    }
    return result
}

/** Compact card used inside the horizontal stack expansion strip. Shows the
 *  same top band / thumbnail / rating band as [VideoListRow] but at a fixed
 *  [thumbnailHeight]-wide footprint with the filename below. */
@Composable
private fun VideoListHorizontalCard(
    item: GridItem,
    isSelected: Boolean = false,
    isInMultiSelection: Boolean = false,
    isAnchor: Boolean = false,
    thumbnailBytes: ByteArray? = null,
    thumbnailHeight: Dp = 80.dp,
    topSlots: List<String> = emptyList(),
    isRepresentative: Boolean = false,
    onStackToggle: () -> Unit = {},
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    onSetRating: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val video = item.video
    val cardWidth: Dp = thumbnailHeight

    val colorLabelEnum = com.videoroom.data.models.ColorLabel.from(video.colorLabel)
    val topBandColor = when {
        isAnchor || (isSelected && !isInMultiSelection) -> Color(0xFFF0F0F0)
        isInMultiSelection -> Color(0xFFD7D7D7)
        else -> Color(0xFFB3B3B3)
    }
    val thumbnailBackground = when {
        isAnchor || (isSelected && !isInMultiSelection) -> Color(0xFFF0F0F0)
        isInMultiSelection -> Color(0xFFD7D7D7)
        colorLabelEnum != com.videoroom.data.models.ColorLabel.None -> colorLabelEnum.dimmed
        else -> Color(0xFF858585)
    }
    val bottomBandColor = when {
        isAnchor || (isSelected && !isInMultiSelection) -> Color(0xFFF0F0F0)
        isInMultiSelection -> Color(0xFFD7D7D7)
        else -> Color(0xFF999999)
    }
    val bandDividerColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.35f)
    }
    val cardBorderColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.White.copy(alpha = 0.6f)
        else -> Color.Black.copy(alpha = 0.4f)
    }

    val thumbnailImage = remember(thumbnailBytes) {
        thumbnailBytes?.let { bytes ->
            try { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() } catch (e: Exception) { null }
        }
    }

    val paddedSlots: List<String> = run {
        val s = topSlots.toMutableList()
        while (s.size < 4) s.add("")
        if (s.size > 4) s.subList(4, s.size).clear()
        s
    }

    Column(
        modifier = modifier
            .width(cardWidth)
            .shiftAwareRowClickable(onClick = onClick, onDoubleClick = onDoubleClick),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .width(cardWidth)
                .border(1.dp, cardBorderColor)
        ) {
            // Top band — first stat slot, with collapse chevron for the representative.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(topBandColor)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRepresentative) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onStackToggle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Collapse stack",
                            modifier = Modifier.size(14.dp),
                            tint = Color.Black.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }
                val stat = com.videoroom.data.models.GridStatKey.fromRaw(paddedSlots.getOrElse(0) { "" })
                Text(
                    text = if (stat == com.videoroom.data.models.GridStatKey.None) "" else stat.valueFor(video),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(bandDividerColor))
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(cardWidth, thumbnailHeight)
                    .background(thumbnailBackground),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailImage != null) {
                    Image(
                        bitmap = thumbnailImage,
                        contentDescription = video.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
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
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(bandDividerColor))
            // Rating band
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(bottomBandColor),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                for (position in 1..5) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                if (video.rating == position) onSetRating(0)
                                else onSetRating(position)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (position <= video.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Rating $position",
                                tint = Color.Black,
                                modifier = Modifier.size(9.dp)
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(3.dp)
                                    .background(
                                        Color(0xFF595959),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }
        // Filename label below the card. Wrapped in a Tooltip because
        // stack-member names are usually long enough that the truncated
        // cardWidth-bound label clips them — hovering reveals the full
        // name without needing to expand the card.
        com.videoroom.ui.components.Tooltip(text = video.filename) {
            Text(
                text = video.filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(cardWidth)
                    .padding(top = 2.dp)
            )
        }
    }
}
