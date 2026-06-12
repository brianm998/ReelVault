// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import com.reelvault.LocalAppWindow
import com.reelvault.LocalShiftPressed
import com.reelvault.data.models.FullResolutionStatus
import com.reelvault.data.models.VideoSummary
import com.reelvault.ui.components.AdaptiveStatRow
import com.reelvault.ui.components.ComposeVideoPlayer
import com.reelvault.ui.components.Tooltip
import com.reelvault.ui.components.VlcUnavailableOverlay
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.util.FileDragSource
import com.reelvault.util.openUrl
import com.reelvault.viewmodel.GridViewModel
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap

@Composable
fun ListScreen(
    viewModel: GridViewModel,
    onVideoSelect: (VideoSummary) -> Unit,
    thumbnailHeight: Dp = 80.dp,
    /** Fired when the user clicks the location badge on a video card. The
     *  doubles are (latitude, longitude). Callers should open the global
     *  map focused on that coordinate. */
    onLocationClick: ((Double, Double) -> Unit)? = null,
    /** Open the location picker on a set of videos, framed on the given initial
     *  location (null → frame on all data). Wired to the right-click
     *  "Add/Update Location…" items. */
    onEditLocation: ((List<String>, Pair<Double, Double>?) -> Unit)? = null,
    /** Clear the location on a set of videos (right-click "Remove Location"). */
    onClearLocation: ((List<String>) -> Unit)? = null,
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
    val scrubFrames = viewModel.scrubFrames.collectAsState()
    val expandedGroupIds = viewModel.expandedGroupIds.collectAsState()
    val expandedMembers = viewModel.expandedGroupMembers.collectAsState()
    val shiftPressed = LocalShiftPressed.current
    val topSlots = viewModel.topSlots.collectAsState()
    val playingVideoId = viewModel.playingVideoId.collectAsState()
    val playingVideoPath = viewModel.playingVideoPath.collectAsState()

    // Resolves a card's GPS to a registered place-name (or null → raw coords)
    // for the "Location" stat slot. Keyed on namedLocations so rows relabel as
    // soon as a place is named or renamed.
    val namedLocationsForCards = viewModel.namedLocations.collectAsState().value
    val placeNameForCards: (Double, Double) -> String? = remember(namedLocationsForCards) {
        { lat, lon ->
            if (namedLocationsForCards.isEmpty()) null
            else viewModel.nameForLocation(lat, lon)?.name
        }
    }
    val vlcAvailable = remember { ComposeVideoPlayer.isLibVlcAvailable }
    // allowEmbedded = false: the inline card player must use the lightweight
    // callback surface — a heavyweight native surface inside the scrolling list
    // would clip and z-order badly, and card-sized playback doesn't skip frames.
    val inlinePlayer = remember { ComposeVideoPlayer(allowEmbedded = false) }
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

    // Single-column navigation: arrow keys step through every video in visual
    // order (stack children included). cols = 1 makes Left/Up == previous and
    // Right/Down == next, per the list-mode spec.
    LaunchedEffect(rendered) {
        viewModel.setNavContext(rendered.map { it.video }, 1)
    }

    val listState = rememberLazyListState()
    // Keep the active card on screen: scroll to the selection on first compose
    // (e.g. switching from grid mode) and whenever it moves (arrow keys). A
    // click on an already-visible row doesn't scroll.
    LaunchedEffect(selectedVideoId.value) {
        val selectedId = selectedVideoId.value ?: return@LaunchedEffect
        val idx = displayRows.indexOfFirst { row ->
            when (row) {
                is ListDisplayRow.Single -> row.item.video.id == selectedId
                is ListDisplayRow.HorizontalStack ->
                    row.representative.video.id == selectedId ||
                    row.children.any { it.video.id == selectedId }
            }
        }
        if (idx < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.index == idx }
        // Only scroll when the row isn't already fully on screen — so an
        // already-visible click (or arrow step) doesn't jump the list, but a
        // partially-clipped or off-screen row (e.g. "Open in List" from the
        // map) is brought in and centred vertically.
        val fullyVisible = visible != null &&
            visible.offset >= info.viewportStartOffset &&
            visible.offset + visible.size <= info.viewportEndOffset
        if (!fullyVisible) {
            listState.scrollToItem(idx)
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.let { item ->
                val target = (listState.layoutInfo.viewportSize.height - item.size) / 2
                if (target > 0) listState.scrollBy((item.offset - target).toFloat())
            }
        }
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
                    openUrl(url)
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
                    .padding(ReelVaultSpacing.Small),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier
                        .padding(ReelVaultSpacing.Medium)
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
            modifier = Modifier.padding(ReelVaultSpacing.Medium),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (videos.value.isEmpty() && !isLoading.value) {
                // Observe collection selection so the message updates when the
                // user navigates between collections / the library.
                viewModel.selectedCollectionId.collectAsState().value
                viewModel.collections.collectAsState().value
                val (emptyTitle, emptyDetail) = viewModel.emptyStateMessage()
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
                    Spacer(modifier = Modifier.height(ReelVaultSpacing.Medium))
                    Text(
                        text = emptyTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (emptyDetail.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(ReelVaultSpacing.XSmall))
                        Text(
                            text = emptyDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 280.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ReelVaultSpacing.Small),
                    // No inter-row spacing — list rows sit flush vertically.
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
                                        val captureTargets: List<Pair<String, String>> =
                                            if (video.id in multi && multi.size > 1)
                                                videos.value.filter { it.id in multi }.map { it.id to it.filename }
                                            else listOf(video.id to video.filename)
                                        val locationSummaries =
                                            if (video.id in multi && multi.size > 1)
                                                videos.value.filter { it.id in multi }
                                            else listOf(video)
                                        val locationInitial = locationSummaries
                                            .firstOrNull { it.hasLocation }
                                            ?.let { it.gpsLatitude to it.gpsLongitude }
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
                                            // "Combine into stack" / "Attach proxies" act on the
                                            // whole multi-selection, so they're only offered when the
                                            // right-clicked card is part of a 2+ selection.
                                            combineSelectionCount =
                                                if (video.id in multi && multi.size > 1) multi.size else 0,
                                            onCombineIntoStack = { viewModel.groupSelectedVideos() },
                                            onAttachProxies = { viewModel.attachProxiesToSelection() },
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
                                            collections = viewModel.collections.value,
                                            collectionTargetIds = ratingTargets,
                                            videoCollections = emptyList(),
                                            onAddToCollection = { colId, ids -> viewModel.addToCollection(ids, colId) },
                                            onRemoveFromCollection = { colId, ids -> viewModel.removeFromCollection(ids, colId) },
                                            captureDateTargets = captureTargets,
                                            onApplyInferredCaptureDates = { perVideo ->
                                                viewModel.setInferredCaptureDates(perVideo)
                                            },
                                            locationTargetIds = ratingTargets,
                                            locationInitial = locationInitial,
                                            onEditLocation = onEditLocation,
                                            onClearLocation = onClearLocation,
                                        )
                                    }
                                ) {
                                    VideoListRow(
                                        item = item,
                                        isSelected = isPrimary,
                                        isInMultiSelection = isInMultiSelect,
                                        isAnchor = isAnchor,
                                        thumbnailBytes = thumbnails.value[video.id],
                                        scrubFrames = scrubFrames.value[video.id] ?: emptyList(),
                                        thumbnailHeight = thumbnailHeight,
                                        topSlots = topSlots.value,
                                        onPickStatSlot = { slotIndex, key ->
                                            viewModel.updateGridTopSlot(slotIndex, key)
                                        },
                                        placeNameFor = placeNameForCards,
                                        stackMemberFilenames = if (video.isInGroup) {
                                            expandedMembers.value[video.groupId]
                                                ?.filter { it.id != video.id }
                                                ?.map { it.filename }
                                                ?: emptyList()
                                        } else emptyList(),
                                        onStackToggle = { viewModel.toggleStackExpansion(video.groupId) },
                                        onHoverEnter = { viewModel.loadScrubFrames(video.id) },
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
                                                // Prefer the smallest proxy whenever one exists,
                                                // even for natively-playable masters (inline is a
                                                // lightweight hover preview).
                                                video.proxyCount > 0    -> viewModel.playVideoPreferProxy(video.id)
                                                video.playableNatively  -> viewModel.playVideo(video.id)
                                                else                    -> viewModel.requestCreateProxy(video.id)
                                            }
                                        },
                                        onStopPlayback = { viewModel.stopPlayback() },
                                        onLocationClick = onLocationClick,
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
                                        // No top/bottom padding so expanded-stack
                                        // rows sit flush with their neighbours too.
                                        .padding(
                                            start = ReelVaultSpacing.Small,
                                            end = ReelVaultSpacing.Small,
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
                                                val captureTargets: List<Pair<String, String>> =
                                                    if (video.id in multi && multi.size > 1)
                                                        videos.value.filter { it.id in multi }.map { it.id to it.filename }
                                                    else listOf(video.id to video.filename)
                                                val locationSummaries =
                                                    if (video.id in multi && multi.size > 1)
                                                        videos.value.filter { it.id in multi }
                                                    else listOf(video)
                                                val locationInitial = locationSummaries
                                                    .firstOrNull { it.hasLocation }
                                                    ?.let { it.gpsLatitude to it.gpsLongitude }
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
                                                    collections = viewModel.collections.value,
                                                    collectionTargetIds = ratingTargets,
                                                    videoCollections = emptyList(),
                                                    onAddToCollection = { colId, ids -> viewModel.addToCollection(ids, colId) },
                                                    onRemoveFromCollection = { colId, ids -> viewModel.removeFromCollection(ids, colId) },
                                                    captureDateTargets = captureTargets,
                                                    onApplyInferredCaptureDates = { perVideo ->
                                                        viewModel.setInferredCaptureDates(perVideo)
                                                    },
                                                    locationTargetIds = ratingTargets,
                                                    locationInitial = locationInitial,
                                                    onEditLocation = onEditLocation,
                                                    onClearLocation = onClearLocation,
                                                )
                                            }
                                        ) {
                                            VideoListHorizontalCard(
                                                item = stackItem,
                                                isSelected = isPrimary,
                                                isInMultiSelection = isInMultiSelect,
                                                isAnchor = isAnchor,
                                                thumbnailBytes = thumbnails.value[video.id],
                                                scrubFrames = scrubFrames.value[video.id] ?: emptyList(),
                                                thumbnailHeight = thumbnailHeight,
                                                topSlots = topSlots.value,
                                                isRepresentative = idx == 0,
                                                onStackToggle = {
                                                    viewModel.toggleStackExpansion(video.groupId)
                                                },
                                                onPickStatSlot = { slotIndex, key ->
                                                    viewModel.updateGridTopSlot(slotIndex, key)
                                                },
                                                placeNameFor = placeNameForCards,
                                                onHoverEnter = { viewModel.loadScrubFrames(video.id) },
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

@Composable
fun VideoListRow(
    item: GridItem,
    isSelected: Boolean = false,
    isInMultiSelection: Boolean = false,
    isAnchor: Boolean = false,
    thumbnailBytes: ByteArray? = null,
    scrubFrames: List<ByteArray?> = emptyList(),
    thumbnailHeight: Dp = 80.dp,
    /** The four catalog-scoped stat-slot keys (the same slots the grid card
     *  shows on top), rendered as a vertical list in the row's info column. */
    topSlots: List<String> = emptyList(),
    /**
     * Filenames of the other members of this row's stack — empty when
     * the video isn't in a stack or the members haven't been fetched
     * yet. Rendered in the info column on collapsed-stack reps so the
     * user can see what the stack contains without expanding it.
     */
    stackMemberFilenames: List<String> = emptyList(),
    onStackToggle: () -> Unit = {},
    onHoverEnter: () -> Unit = {},
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    /** Fired when one of the five rating positions is clicked. */
    onSetRating: (Int) -> Unit = {},
    /** Fired when the user picks a different stat for one of the four
     *  info-column slots. Receives (slotIndex 0..3, GridStatKey.raw). */
    onPickStatSlot: (Int, String) -> Unit = { _, _ -> },
    /** Resolves a (latitude, longitude) to a registered place-name (or null →
     *  raw coordinates) for the "Location" stat slot. */
    placeNameFor: ((Double, Double) -> String?)? = null,
    /** Fired when the user clicks the location badge. The doubles are
     *  (latitude, longitude). Callers should open the global map focused
     *  on that coordinate. */
    onLocationClick: ((Double, Double) -> Unit)? = null,
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

    val scrubImages = remember(scrubFrames) {
        scrubFrames.map { bytes ->
            bytes?.let {
                try { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() } catch (_: Exception) { null }
            }
        }
    }

    var hoverX by remember { mutableStateOf<Float?>(null) }
    var thumbSize by remember { mutableStateOf(IntSize.Zero) }

    val displayedImage = run {
        if (isPlayingInline) return@run thumbnailImage
        val x = hoverX
        val width = thumbSize.width
        if (x != null && width > 0 && scrubImages.any { it != null }) {
            val frac = (x / width).coerceIn(0f, 1f)
            val idx = (frac * scrubImages.size).toInt().coerceIn(0, scrubImages.size - 1)
            scrubImages[idx] ?: thumbnailImage
        } else {
            thumbnailImage
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
    val colorLabelEnum = com.reelvault.data.models.ColorLabel.from(video.colorLabel)
    // Background neutrals — kept identical to the grid VideoCard so list and
    // grid rows share the same look.
    val rowMiddleBackground = when {
        isSelected -> Color(0xFF999999)
        isInMultiSelection -> Color(0xFF707070)
        isInExpandedStack -> Color(0xFF535660)
        colorLabelEnum != com.reelvault.data.models.ColorLabel.None -> colorLabelEnum.dimmed
        else -> Color(0xFF474747)
    }
    val bottomBandColor = when {
        isSelected -> Color(0xFFCFCFCF)
        isInMultiSelection -> Color(0xFF9E9E9E)
        else -> Color(0xFF5C5C5C)
    }
    val topBandColor = when {
        isSelected -> Color(0xFFDFDFDF)
        isInMultiSelection -> Color(0xFFB3B3B3)
        else -> Color(0xFF6B6B6B)
    }
    val bandDividerColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.35f)
    }
    val cardBorderColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.White
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
        // No top/bottom padding so consecutive list rows sit flush — no
        // vertical gap between them (matches the dense grid).
        .padding(
            start = if (item.isStackChild) (ReelVaultSpacing.Medium + 16.dp) else ReelVaultSpacing.Small,
            end = ReelVaultSpacing.Small,
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
                // Selection border twice as wide (matches the grid card's
                // 2 dp selection edge); 1 dp otherwise.
                .border(
                    if (isSelected || isInMultiSelection) 2.dp else 1.dp,
                    cardBorderColor
                )
        ) {
            // Top stat band — the same four catalog-wide slots the grid card
            // shows on top (slot 0 = TL, 1 = BL, 2 = TR, 3 = BR). These mirror
            // the grid card so the stats appear "on the card" here too, in
            // addition to the readable vertical list in the info column.
            val cardTopSlots = (topSlots + List(4) { "" }).take(4)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(topBandColor)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                AdaptiveStatRow(
                    modifier = Modifier.fillMaxWidth(),
                    leading = { ListRowStatCell(slotIndex = 0, key = cardTopSlots[0], video = video, onPick = onPickStatSlot, alignEnd = false, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                    trailing = { ListRowStatCell(slotIndex = 2, key = cardTopSlots[2], video = video, onPick = onPickStatSlot, alignEnd = true, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                )
                AdaptiveStatRow(
                    modifier = Modifier.fillMaxWidth(),
                    leading = { ListRowStatCell(slotIndex = 1, key = cardTopSlots[1], video = video, onPick = onPickStatSlot, alignEnd = false, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                    trailing = { ListRowStatCell(slotIndex = 3, key = cardTopSlots[3], video = video, onPick = onPickStatSlot, alignEnd = true, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(bandDividerColor))
            // Square thumbnail (cardWidth × thumbnailHeight).
            Box(
                modifier = Modifier
                    .size(cardWidth, thumbnailHeight)
                    .background(rowMiddleBackground)
                    .padding(8.dp)
                    .onSizeChanged { thumbSize = it }
                    .onPointerEvent(PointerEventType.Enter) {
                        if (!isPlayingInline) {
                            onHoverEnter()
                            it.changes.firstOrNull()?.position?.let { p -> hoverX = p.x }
                        }
                    }
                    .onPointerEvent(PointerEventType.Move) {
                        if (!isPlayingInline) {
                            val p = it.changes.firstOrNull()?.position
                            if (p != null && hoverX != p.x) hoverX = p.x
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit) { hoverX = null },
                contentAlignment = Alignment.Center
            ) {
                if (displayedImage != null) {
                    Image(
                        bitmap = displayedImage,
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
                if (!isPlayingInline && video.isOnline && isSelected && isHovered && (canPlayInline || !playEnabled)) {
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
                // Offline indicator — mirrors the grid card. The file was
                // missing at the last scan (moved/renamed, or its drive isn't
                // mounted); dim the thumbnail and badge it so it's obvious
                // before the user tries to play. Play is suppressed above for
                // the same reason. Icon-only here — the row thumbnail is small.
                if (!video.isOnline && !isPlayingInline) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Tooltip(
                            text = "File offline — moved, renamed, or its drive isn't " +
                                "mounted. Re-scan the library to update its location.",
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = "Offline",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White,
                                )
                            }
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
                // Stack/group badge — mirrors the grid card: a colour-coded
                // Surface with the Layers icon + member count. Clicking it
                // toggles the stack's expansion; the pointer-input consume
                // pattern stops the same click from also selecting the row.
                if (video.isInGroup) {
                    // Centre the stack badge in the top letterbox gap above the
                    // video — and use the same 6 dp side inset as the bottom
                    // badges — so its position matches the grid card.
                    val badgeAspect = if (video.width > 0 && video.height > 0)
                        video.width.toFloat() / video.height.toFloat() else 1f
                    val badgeInner = (minOf(cardWidth, thumbnailHeight) - 16.dp).coerceAtLeast(0.dp)
                    val badgeVideoH = if (badgeAspect >= 1f) badgeInner / badgeAspect else badgeInner
                    val badgeTopBand = ((badgeInner - badgeVideoH) / 2)
                        .coerceAtLeast(ReelVaultSpacing.Large)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .height(badgeTopBand)
                            .padding(start = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                    Tooltip(
                        text = if (item.isExpandedRepresentative)
                            "Collapse this stack of ${video.groupSize} videos"
                        else
                            "Expand this stack to see all ${video.groupSize} variants",
                    ) {
                        Surface(
                            modifier = Modifier
                                .pointerInput(onStackToggle) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) {
                                            up.consume()
                                            onStackToggle()
                                        }
                                    }
                                },
                            color = when {
                                item.isExpandedRepresentative -> MaterialTheme.colorScheme.primary
                                item.isStackChild -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = ReelVaultSpacing.Small,
                                    vertical = 2.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = if (item.isExpandedRepresentative) "Collapse stack" else "Expand stack",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${video.groupSize}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    } // letterbox-centring Box
                }
                // Bottom-left location badge — shown when the video has GPS
                // coordinates embedded. Tapping fires [onLocationClick] so
                // the caller can open the global map focused on this video.
                if (video.hasLocation && onLocationClick != null) {
                    Tooltip(
                        text = "Recorded at %.4f, %.4f — click to show on map".format(
                            video.gpsLatitude, video.gpsLongitude
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                                .pointerInput(video.gpsLatitude, video.gpsLongitude) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) {
                                            up.consume()
                                            onLocationClick(video.gpsLatitude, video.gpsLongitude)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NearMe,
                                contentDescription = "Show on map",
                                modifier = Modifier.size(11.dp),
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom-right status badges — keyword / proxy / full-resolution,
                // mirroring the grid card so list and grid cards read identically.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (video.tags.isNotEmpty()) {
                        Tooltip(
                            text = "${video.tags.size} keyword${if (video.tags.size == 1) "" else "s"}: ${video.tags.joinToString(", ")}"
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sell,
                                    contentDescription = "${video.tags.size} keyword(s)",
                                    modifier = Modifier.size(10.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    if (video.hasProxies) {
                        Tooltip(
                            text = "${video.proxyCount} proxy/proxies available for inline playback"
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterNone,
                                    contentDescription = "${video.proxyCount} proxy/proxies",
                                    modifier = Modifier.size(10.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    // Full-resolution badge — only when the daemon's classifier
                    // was sure either way; Unspecified renders nothing.
                    when (video.fullResolution) {
                        FullResolutionStatus.Full -> {
                            Tooltip(
                                text = "Full resolution — matches a known native sensor mode for ${video.cameraDisplayName.ifEmpty { "this camera" }}"
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Verified,
                                        contentDescription = "Full resolution",
                                        modifier = Modifier.size(10.dp),
                                        // Subtle green marks "full resolution" (matches the grid card).
                                        tint = Color(0xFF81C784)
                                    )
                                }
                            }
                        }
                        FullResolutionStatus.NotFull -> {
                            Tooltip(
                                text = "Not full resolution — recorded dimensions don't match any native sensor mode for ${video.cameraDisplayName.ifEmpty { "this camera" }}"
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Crop,
                                        contentDescription = "Not full resolution",
                                        modifier = Modifier.size(10.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                        FullResolutionStatus.Unspecified -> {}
                    }
                }

                // 1 dp black border tight around the video frame itself
                // (matches the grid card). Last child of the thumbnail box so
                // it sits on top of the letterboxed image; no pointer handler,
                // so it doesn't block hover-scrub or the play button.
                val frameAspect = if (video.width > 0 && video.height > 0)
                    video.width.toFloat() / video.height.toFloat() else 1f
                val frameInner = (minOf(cardWidth, thumbnailHeight) - 16.dp).coerceAtLeast(0.dp)
                val frameW = if (frameAspect >= 1f) frameInner else frameInner * frameAspect
                val frameH = if (frameAspect >= 1f) frameInner / frameAspect else frameInner
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(frameW, frameH)
                        .border(1.dp, Color.Black)
                )
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
                            // White star with a thin #1F1F1F outline (dark star
                            // behind), matching the grid card's filled stars.
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Color(0xFF1F1F1F),
                                    modifier = Modifier.size(13.dp)
                                )
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = "Rating $position",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .border(1.dp, Color(0xFF1F1F1F), androidx.compose.foundation.shape.CircleShape)
                                    .background(
                                        Color(0xFFB8B8B8),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                }
            }
            } // Tooltip
        }

        Spacer(modifier = Modifier.width(ReelVaultSpacing.Small))

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

            // Four configurable stat labels — the same catalog-wide slots the
            // grid card shows in its top band, relocated to a vertical list
            // here in list view. Each is click-to-configure via the shared
            // picker, so grid and list stay in sync.
            val paddedSlots = remember(topSlots) {
                val s = topSlots.toMutableList()
                while (s.size < 4) s.add("")
                s.take(4)
            }
            for (slotIndex in 0..3) {
                ListColumnStatLabel(
                    slotIndex = slotIndex,
                    key = paddedSlots[slotIndex],
                    video = video,
                    onPick = onPickStatSlot,
                    placeNameFor = placeNameFor,
                )
            }
            // Collapsed stack: list the other members of the stack so the
            // user can see what's inside without expanding. Skipped when
            // the row is expanded (members are shown as cards instead) or
            // when the prefetch hasn't completed yet.
            if (!isInExpandedStack && stackMemberFilenames.isNotEmpty()) {
                Text(
                    text = "Stack:\n${stackMemberFilenames.joinToString("\n")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** One of the four configurable stat labels shown in [VideoListRow]'s info
 *  column. Mirrors [ListRowStatCell] but stacks vertically (no [RowScope]
 *  weight) and is always start-aligned. It uses theme-surface text colours
 *  because it sits on the panel background rather than the card's light stat
 *  band. Clicking opens the same picker, so the list shares the grid's
 *  catalog-wide slot configuration. */
@Composable
private fun ListColumnStatLabel(
    slotIndex: Int,
    key: String,
    video: VideoSummary,
    onPick: (Int, String) -> Unit,
    placeNameFor: ((Double, Double) -> String?)? = null,
) {
    val stat = com.reelvault.data.models.GridStatKey.fromRaw(key)
    val value = stat.valueFor(video, placeNameFor)
    val displayed: String = if (value.isEmpty()) "—" else value
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 14.dp)
            .clickable { expanded = true },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = displayed,
            style = if (slotIndex == 0) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelSmall,
            color = if (stat == com.reelvault.data.models.GridStatKey.None)
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            else
                MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            com.reelvault.data.models.GridStatKey.values().forEach { choice ->
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

/** Stat cell rendered inside the list-row's top band. Click anywhere in
 *  the cell to open the stat-picker dropdown. Mirrors VideoCard.StatCell
 *  in look + behaviour but lives here so ListScreen owns its own card. */
@Composable
private fun ListRowStatCell(
    slotIndex: Int,
    key: String,
    video: VideoSummary,
    onPick: (Int, String) -> Unit,
    alignEnd: Boolean,
    placeNameFor: ((Double, Double) -> String?)? = null,
    /** Selected cards flip the top-band text to black on their bright band. */
    selected: Boolean = false,
) {
    val stat = com.reelvault.data.models.GridStatKey.fromRaw(key)
    val value = stat.valueFor(video, placeNameFor)
    val displayed: String = if (value.isEmpty()) "—" else value
    var expanded by remember { mutableStateOf(false) }
    // Width assigned by the enclosing [AdaptiveStatRow] (see VideoCard.kt) so a
    // short value yields room to a longer neighbour instead of truncating at
    // the band's centre.
    Box(
        modifier = Modifier
            .heightIn(min = 14.dp)
            .clickable { expanded = true },
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text = displayed,
            style = if (slotIndex == 0) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelSmall,
            // Selected cards: black text on the bright band. Unselected: white.
            color = when {
                stat == com.reelvault.data.models.GridStatKey.None ->
                    if (selected) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)
                selected -> Color.Black
                else -> Color.White.copy(alpha = 0.92f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End
                        else androidx.compose.ui.text.style.TextAlign.Start
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            com.reelvault.data.models.GridStatKey.values().forEach { choice ->
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
        awaitFirstDown(requireUnconsumed = true)
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
    scrubFrames: List<ByteArray?> = emptyList(),
    thumbnailHeight: Dp = 80.dp,
    topSlots: List<String> = emptyList(),
    isRepresentative: Boolean = false,
    onStackToggle: () -> Unit = {},
    onPickStatSlot: (Int, String) -> Unit = { _, _ -> },
    placeNameFor: ((Double, Double) -> String?)? = null,
    onHoverEnter: () -> Unit = {},
    onClick: (shiftPressed: Boolean, togglePressed: Boolean) -> Unit = { _, _ -> },
    onDoubleClick: () -> Unit = {},
    onSetRating: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val video = item.video
    val cardWidth: Dp = thumbnailHeight

    val colorLabelEnum = com.reelvault.data.models.ColorLabel.from(video.colorLabel)
    // Background neutrals — identical to the grid VideoCard / VideoListRow.
    val topBandColor = when {
        isSelected -> Color(0xFFDFDFDF)
        isInMultiSelection -> Color(0xFFB3B3B3)
        else -> Color(0xFF6B6B6B)
    }
    val thumbnailBackground = when {
        isSelected -> Color(0xFF999999)
        isInMultiSelection -> Color(0xFF707070)
        colorLabelEnum != com.reelvault.data.models.ColorLabel.None -> colorLabelEnum.dimmed
        else -> Color(0xFF474747)
    }
    val bottomBandColor = when {
        isSelected -> Color(0xFFCFCFCF)
        isInMultiSelection -> Color(0xFF9E9E9E)
        else -> Color(0xFF5C5C5C)
    }
    val bandDividerColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.Black.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.35f)
    }
    val cardBorderColor = when {
        isAnchor || isSelected || isInMultiSelection -> Color.White
        else -> Color.Black.copy(alpha = 0.4f)
    }

    val thumbnailImage = remember(thumbnailBytes) {
        thumbnailBytes?.let { bytes ->
            try { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() } catch (e: Exception) { null }
        }
    }

    val scrubImages = remember(scrubFrames) {
        scrubFrames.map { bytes ->
            bytes?.let {
                try { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() } catch (_: Exception) { null }
            }
        }
    }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var thumbSize by remember { mutableStateOf(IntSize.Zero) }
    val displayedImage = run {
        val x = hoverX
        val width = thumbSize.width
        if (x != null && width > 0 && scrubImages.any { it != null }) {
            val frac = (x / width).coerceIn(0f, 1f)
            val idx = (frac * scrubImages.size).toInt().coerceIn(0, scrubImages.size - 1)
            scrubImages[idx] ?: thumbnailImage
        } else {
            thumbnailImage
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
                .border(
                    if (isSelected || isInMultiSelection) 2.dp else 1.dp,
                    cardBorderColor
                )
        ) {
            // Top stat band — the same four catalog-wide slots (2×2) the grid
            // and unexpanded list cards show, so expanded stack cards match
            // them instead of showing a lone stat. The collapse chevron sits in
            // the top-left beside slot 0.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(topBandColor)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRepresentative) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clickable(onClick = onStackToggle),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Collapse stack",
                                modifier = Modifier.size(12.dp),
                                tint = Color.Black.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    AdaptiveStatRow(
                        modifier = Modifier.weight(1f),
                        leading = { ListRowStatCell(slotIndex = 0, key = paddedSlots.getOrElse(0) { "" }, video = video, onPick = onPickStatSlot, alignEnd = false, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                        trailing = { ListRowStatCell(slotIndex = 2, key = paddedSlots.getOrElse(2) { "" }, video = video, onPick = onPickStatSlot, alignEnd = true, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                    )
                }
                AdaptiveStatRow(
                    modifier = Modifier.fillMaxWidth(),
                    leading = { ListRowStatCell(slotIndex = 1, key = paddedSlots.getOrElse(1) { "" }, video = video, onPick = onPickStatSlot, alignEnd = false, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                    trailing = { ListRowStatCell(slotIndex = 3, key = paddedSlots.getOrElse(3) { "" }, video = video, onPick = onPickStatSlot, alignEnd = true, placeNameFor = placeNameFor, selected = isSelected || isInMultiSelection) },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(bandDividerColor))
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(cardWidth, thumbnailHeight)
                    .background(thumbnailBackground)
                    .onSizeChanged { thumbSize = it }
                    .onPointerEvent(PointerEventType.Enter) {
                        onHoverEnter()
                        it.changes.firstOrNull()?.position?.let { p -> hoverX = p.x }
                    }
                    .onPointerEvent(PointerEventType.Move) {
                        val p = it.changes.firstOrNull()?.position
                        if (p != null && hoverX != p.x) hoverX = p.x
                    }
                    .onPointerEvent(PointerEventType.Exit) { hoverX = null },
                contentAlignment = Alignment.Center
            ) {
                if (displayedImage != null) {
                    Image(
                        bitmap = displayedImage,
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
                // 1 dp black border tight around the video frame itself
                // (matches the grid card). The thumbnail fills the box here
                // (no photoPadding inset), so the frame tracks the letterboxed
                // bounds within the full box.
                val frameAspect = if (video.width > 0 && video.height > 0)
                    video.width.toFloat() / video.height.toFloat() else 1f
                val frameInner = minOf(cardWidth, thumbnailHeight)
                val frameW = if (frameAspect >= 1f) frameInner else frameInner * frameAspect
                val frameH = if (frameAspect >= 1f) frameInner / frameAspect else frameInner
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(frameW, frameH)
                        .border(1.dp, Color.Black)
                )
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
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Color(0xFF1F1F1F),
                                    modifier = Modifier.size(11.dp)
                                )
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = "Rating $position",
                                    tint = Color.White,
                                    modifier = Modifier.size(9.dp)
                                )
                            }
                        } else {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .border(1.dp, Color(0xFF1F1F1F), androidx.compose.foundation.shape.CircleShape)
                                    .background(
                                        Color(0xFFB8B8B8),
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
        com.reelvault.ui.components.Tooltip(text = video.filename) {
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
