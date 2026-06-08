// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.reelvault.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.models.VideoSummary
import com.reelvault.ui.components.ComposeVideoPlayer
import com.reelvault.ui.components.VideoCard
import com.reelvault.ui.theme.ReelVaultSpacing
import com.reelvault.util.openUrl
import com.reelvault.util.openWithDefault
import com.reelvault.util.revealInFileManager
import com.reelvault.viewmodel.GridViewModel
import org.slf4j.LoggerFactory

private val gridScreenLogger = LoggerFactory.getLogger("com.reelvault.ui.screens.GridScreen")

@Composable
fun GridScreen(
    viewModel: GridViewModel,
    onVideoSelect: (VideoSummary) -> Unit,
    /** Minimum width of each grid cell — also controls how many columns appear. */
    thumbnailMinWidth: androidx.compose.ui.unit.Dp = 220.dp,
    /** Fired when the user clicks the location badge on a video card. The
     *  doubles are (latitude, longitude). Callers should open the global
     *  map focused on that coordinate. */
    onLocationClick: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val videos = viewModel.videos.collectAsState()
    val selectedVideoId = viewModel.selectedVideoId.collectAsState()
    val selectedVideoIds = viewModel.selectedVideoIds.collectAsState()
    val anchorVideoId = viewModel.anchorVideoId.collectAsState()
    val isLoading = viewModel.isLoading.collectAsState()
    val hasMore = viewModel.hasMore.collectAsState()
    val error = viewModel.error.collectAsState()
    val thumbnails = viewModel.thumbnails.collectAsState()
    val scrubFrames = viewModel.scrubFrames.collectAsState()
    val expandedGroupIds = viewModel.expandedGroupIds.collectAsState()
    val expandedMembers = viewModel.expandedGroupMembers.collectAsState()
    val shiftPressed = com.reelvault.LocalShiftPressed.current
    val playingVideoId = viewModel.playingVideoId.collectAsState()

    // Fast startup check: NativeDiscovery inspects filesystem paths only —
    // no JNI or player init — so this is safe to evaluate on the Compose
    // thread. The result is cached in ComposeVideoPlayer.isLibVlcAvailable.
    val vlcAvailable = remember { ComposeVideoPlayer.isLibVlcAvailable }

    // Single VLCJ player instance shared by all cards. Only one card plays
    // at a time; swapping is handled by loading a new path into this player.
    val inlinePlayer = remember { ComposeVideoPlayer() }
    DisposableEffect(Unit) { onDispose { inlinePlayer.release() } }

    val playingVideoPath = viewModel.playingVideoPath.collectAsState()
    val activeProxyCreations = viewModel.activeProxyCreations.collectAsState()

    // Start playback whenever playingVideoId changes to a non-null value.
    // Use the proxy override path when set (oversize videos), otherwise
    // fall back to the video's own openPath.
    LaunchedEffect(playingVideoId.value) {
        val id = playingVideoId.value ?: run {
            // Playback was stopped — make sure audio stops too.
            inlinePlayer.stop()
            return@LaunchedEffect
        }
        val path = playingVideoPath.value
            ?: videos.value.find { it.id == id }?.openPath
            ?: run {
                gridScreenLogger.warn("playingVideoId={} but no openPath in video list", id)
                return@LaunchedEffect
            }
        gridScreenLogger.info("Grid playback: loading {} into shared player", path)
        inlinePlayer.load(path, playImmediately = true)
    }

    // Build the rendered list by splicing expanded stack members in after each
    // expanded representative. Recomputes only when an input changes.
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

    val gridState = rememberLazyGridState()
    // Keep the active card on screen: scroll to the selection when this view is
    // first composed (e.g. switching from list mode) and whenever the selection
    // moves (arrow-key navigation). A click on an already-visible card doesn't
    // scroll, since it's already in the visible range.
    LaunchedEffect(selectedVideoId.value) {
        val selectedId = selectedVideoId.value ?: return@LaunchedEffect
        val idx = rendered.indexOfFirst { it.video.id == selectedId }
        if (idx < 0) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
            gridState.scrollToItem(idx)
        }
    }

    // Show when the user taps play but libvlc isn't installed.
    var showVlcErrorDialog by remember { mutableStateOf(false) }
    if (showVlcErrorDialog) {
        val osName = System.getProperty("os.name") ?: ""
        val url = when {
            osName.contains("Mac", ignoreCase = true) ->
                "https://www.videolan.org/vlc/download-macosx.html"
            osName.contains("Windows", ignoreCase = true) ->
                "https://www.videolan.org/vlc/download-windows.html"
            else ->
                "https://www.videolan.org/vlc/"
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
        // Status bar
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
                    com.reelvault.ui.components.Tooltip(text = "Dismiss this error message") {
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

        // Grid
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Column count matches GridCells.Adaptive (zero spacing/padding):
            // as many `thumbnailMinWidth`-wide cells as fit. Reported to the
            // view-model so arrow-key Up/Down can jump a whole row.
            val columns = (maxWidth / thumbnailMinWidth).toInt().coerceAtLeast(1)
            LaunchedEffect(rendered, columns) {
                viewModel.setNavContext(rendered.map { it.video }, columns)
            }
            // Selected ids as a set for O(1) neighbour lookups when deciding
            // which card edges sit on the selection group's outer boundary.
            val selectedIdSet = selectedVideoIds.value.toSet()
            fun selectedAt(i: Int): Boolean =
                i in rendered.indices && rendered[i].video.id in selectedIdSet
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
                LazyVerticalGrid(
                    // Adaptive: as many columns as fit at the given minimum
                    // width. Cards expand from there to fill available space.
                    columns = GridCells.Adaptive(minSize = thumbnailMinWidth),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    // Lightroom-style: zero spacing between cards so the grid
                    // reads as a dense edge-to-edge filmstrip. No content
                    // padding either — the cards run flush to the viewport.
                    contentPadding = PaddingValues(0.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(
                        count = rendered.size,
                        // Key uses the video id + a child-row indicator so expanded
                        // children get distinct keys from their representative.
                        key = { index ->
                            val item = rendered[index]
                            if (item.isStackChild) "child:${item.video.id}" else item.video.id
                        }
                    ) { index ->
                        val item = rendered[index]
                        val video = item.video
                        val isPrimary = selectedVideoId.value == video.id
                        val isInMultiSelect = video.id in selectedIdSet
                        val isAnchor = anchorVideoId.value == video.id &&
                                       selectedVideoIds.value.size > 1
                        // A side is on the selection's outer edge when the
                        // grid-neighbour in that direction isn't also selected.
                        // Left/right only count same-row neighbours.
                        val edgeTop = !selectedAt(index - columns)
                        val edgeBottom = !selectedAt(index + columns)
                        val edgeLeft = index % columns == 0 || !selectedAt(index - 1)
                        val edgeRight = index % columns == columns - 1 || !selectedAt(index + 1)

                        // Trigger thumbnail load when card appears
                        LaunchedEffect(video.id) {
                            if (video.hasThumbnail) {
                                viewModel.loadThumbnail(video.id)
                            }
                        }

                        // Shared between ContextMenuArea (which writes
                        // the open/closed status) and VideoCard (which
                        // reads it to suppress its dwell tooltip while
                        // the menu is up — otherwise the popup would
                        // render on top of the menu after the 2-second
                        // dwell elapsed).
                        val contextMenuState = remember {
                            androidx.compose.foundation.ContextMenuState()
                        }
                        val isContextMenuOpen = contextMenuState.status is
                            androidx.compose.foundation.ContextMenuState.Status.Open
                        ContextMenuArea(
                            state = contextMenuState,
                            items = {
                                // Compute the target file paths each time the menu opens
                                // so it always reflects the latest selection. If the
                                // right-clicked card is part of the current
                                // multi-selection, operate on all selected videos;
                                // otherwise operate on just this video.
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
                                    // Stack actions are surfaced only when
                                    // the right-clicked card itself is in a
                                    // stack — even within a multi-selection,
                                    // "Remove from stack" operates on this
                                    // one card per the product spec.
                                    stackVideoId = video.id.takeIf { video.isInGroup },
                                    stackGroupId = video.groupId.takeIf { video.isInGroup },
                                    onRemoveFromStack = { vid, gid ->
                                        viewModel.removeFromStack(vid, gid)
                                    },
                                    onUnstack = { gid ->
                                        viewModel.unstackGroup(gid)
                                    },
                                    // Don't offer "Create proxy" on cards
                                    // that are themselves proxies — chaining
                                    // proxy-of-a-proxy makes no sense.
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
                                )
                            }
                        ) {
                            VideoCard(
                                video = video,
                                isSelected = isPrimary,
                                isInMultiSelection = isInMultiSelect,
                                isAnchor = isAnchor,
                                selectionEdgeTop = edgeTop,
                                selectionEdgeBottom = edgeBottom,
                                selectionEdgeLeft = edgeLeft,
                                selectionEdgeRight = edgeRight,
                                isStackExpanded = item.isExpandedRepresentative,
                                isStackChild = item.isStackChild,
                                stackMemberPosition = item.memberPosition,
                                stackMemberCount = item.memberCount,
                                thumbnailBytes = thumbnails.value[video.id],
                                scrubFrames = scrubFrames.value[video.id] ?: emptyList(),
                                isPlayingInline = playingVideoId.value == video.id,
                                inlinePlayer = inlinePlayer,
                                playEnabled = vlcAvailable,
                                onPlayClick = {
                                    gridScreenLogger.info(
                                        "Play clicked: video={} playableNatively={} " +
                                            "vlcAvailable={} player.available={} initError={}",
                                        video.id, video.playableNatively, vlcAvailable,
                                        inlinePlayer.available,
                                        inlinePlayer.initError?.javaClass?.simpleName
                                    )
                                    when {
                                        // Startup check (fast path): NativeDiscovery said no
                                        // libvlc before we even tried to build a player.
                                        !vlcAvailable           -> showVlcErrorDialog = true
                                        // Instance check (fallback): player built but init failed.
                                        !inlinePlayer.available -> showVlcErrorDialog = true
                                        // Prefer a proxy whenever one exists — even for natively
                                        // playable masters. Inline playback is a hover preview, so
                                        // the smallest proxy (playVideoPreferProxy picks
                                        // `proxies.last()`, lowest-res from the server's
                                        // descending-by-pixel-count list) is the right default.
                                        video.proxyCount > 0    -> viewModel.playVideoPreferProxy(video.id)
                                        // No proxy but natively playable — play the master directly.
                                        video.playableNatively  -> viewModel.playVideo(video.id)
                                        // Oversize and no proxy — offer to create one.
                                        else -> viewModel.requestCreateProxy(video.id)
                                    }
                                },
                                onStopPlayback = { viewModel.stopPlayback() },
                                onClick = { shiftFromEvent, toggleFromEvent ->
                                    // Modifier-key state can come from either the pointer event
                                    // (preferred) or the Window-level fallback.
                                    val shift = shiftFromEvent || shiftPressed
                                    val toggle = toggleFromEvent
                                    when {
                                        shift -> {
                                            // Range-select from anchor to this video (inclusive)
                                            // using the visual order of the rendered grid.
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
                                onStackBadgeClick = {
                                    viewModel.toggleStackExpansion(video.groupId)
                                },
                                onHoverEnter = { viewModel.loadScrubFrames(video.id) },
                                dragPaths = run {
                                    val multi = selectedVideoIds.value
                                    if (video.id in multi && multi.size > 1) {
                                        // Drag all selected cards as a batch.
                                        rendered.mapNotNull { gi ->
                                            gi.video.openPath.takeIf { gi.video.id in multi }
                                        }
                                    } else {
                                        listOf(video.openPath)
                                    }
                                },
                                topSlots = viewModel.topSlots.collectAsState().value,
                                onSetRating = { rating -> viewModel.setRating(rating, listOf(video.id)) },
                                onPickStatSlot = { slotIndex, key -> viewModel.updateGridTopSlot(slotIndex, key) },
                                proxyCreationState = activeProxyCreations.value[video.id],
                                onLocationClick = onLocationClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Load more when near the end (only triggered by representatives,
                        // not stack children — children are local and don't paginate).
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
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            // Loading overlay
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

/**
 * Compute the list of video IDs visually between [anchorId] and [targetId]
 * (both inclusive) in the rendered grid order. If either ID isn't present in
 * the rendered list (e.g. anchor was in a now-collapsed stack), falls back to
 * just the target.
 */
internal fun computeVisualRange(
    rendered: List<GridItem>,
    anchorId: String,
    targetId: String
): List<String> {
    val ids = rendered.map { it.video.id }
    val anchorIdx = ids.indexOf(anchorId)
    val targetIdx = ids.indexOf(targetId)
    if (anchorIdx < 0 || targetIdx < 0) return listOf(targetId)
    val (start, end) = if (anchorIdx <= targetIdx) anchorIdx to targetIdx else targetIdx to anchorIdx
    return ids.subList(start, end + 1).toList()
}

/**
 * Build the context-menu items shown when the user right-clicks a video card.
 * Targets the supplied [targetFiles] — when the right-clicked card is part of
 * a multi-selection, this is every selected file; otherwise just the one card.
 *
 * Compose Desktop's [ContextMenuItem] doesn't support submenus, so we render a
 * flat list.
 */
internal fun buildVideoContextMenu(
    targetFiles: List<String>,
    /** Video ID of the right-clicked card *if* it sits in a stack — null
     *  otherwise. Used to surface "Remove from stack" / "Unstack". */
    stackVideoId: String? = null,
    /** The right-clicked card's group ID, when it's in a stack. */
    stackGroupId: String? = null,
    onRemoveFromStack: (videoId: String, groupId: String) -> Unit = { _, _ -> },
    onUnstack: (groupId: String) -> Unit = {},
    /** Video ID of the right-clicked card *if* "Create proxy" should be
     *  offered (i.e. the card isn't itself a proxy). Null suppresses the
     *  menu entry. */
    proxyableVideoId: String? = null,
    onCreateProxy: (videoId: String) -> Unit = {},
    /** Path of the right-clicked video itself (not the drag-target list).
     *  Used to identify which library location contains it. Null suppresses
     *  the "Go to Folder in Library" menu entry. */
    videoPath: String? = null,
    /** All known library locations, for longest-prefix matching. */
    libraryLocations: List<LibraryLocation> = emptyList(),
    /** Called with the matched location path when "Go to Folder in Library"
     *  is selected. */
    onGoToFolder: ((String) -> Unit)? = null,
    /** Video IDs the rating / colour-label / stack-master actions should
     *  apply to. Usually the multi-selection (or just `[video.id]`). */
    ratingTargetIds: List<String> = emptyList(),
    /** Called when the user picks "Set Rating → N stars". Receives (rating
     *  0..5, target ids). */
    onSetRating: ((Int, List<String>) -> Unit)? = null,
    /** Called when the user picks "Set Color Label → X". Receives (label
     *  raw, target ids); empty string clears. */
    onSetColorLabel: ((String, List<String>) -> Unit)? = null,
    /** Video ID + group ID for the right-clicked card *if* it sits in a
     *  stack AND is NOT already the representative. Used to surface
     *  "Set as Stack Master". */
    stackMasterCandidate: Pair<String, String>? = null,
    onSetStackMaster: ((String, String) -> Unit)? = null,
    /** All known collections, for the "Add to Collection" submenu. */
    collections: List<com.reelvault.data.models.Collection> = emptyList(),
    /** Video IDs to apply collection operations to (same set as ratingTargetIds). */
    collectionTargetIds: List<String> = emptyList(),
    /** Collections the right-clicked video already belongs to (for "Remove from Collection"). */
    videoCollections: List<String> = emptyList(),
    onAddToCollection: ((collectionId: String, videoIds: List<String>) -> Unit)? = null,
    onRemoveFromCollection: ((collectionId: String, videoIds: List<String>) -> Unit)? = null,
): List<androidx.compose.foundation.ContextMenuItem> {
    val items = mutableListOf<androidx.compose.foundation.ContextMenuItem>()
    val n = targetFiles.size

    items += androidx.compose.foundation.ContextMenuItem(
        label = if (n == 1) "Open with Default Player" else "Open $n videos with Default Player"
    ) {
        targetFiles.forEach { openWithDefault(it) }
    }

    if (n == 1) {
        items += androidx.compose.foundation.ContextMenuItem("Reveal in File Manager") {
            revealInFileManager(targetFiles.first())
        }
    }

    // Stack-membership actions. Only when the right-clicked card sits
    // in a stack — even with a multi-selection active, these operate on
    // the *one* card the user clicked (per the product spec) and that
    // card's group, never on the rest of the selection.
    if (stackVideoId != null && !stackGroupId.isNullOrEmpty()) {
        items += androidx.compose.foundation.ContextMenuItem("Remove from stack") {
            onRemoveFromStack(stackVideoId, stackGroupId)
        }
        items += androidx.compose.foundation.ContextMenuItem("Unstack") {
            onUnstack(stackGroupId)
        }
    }

    // Proxy creation. Not offered on cards that are themselves proxies
    // (chaining proxy-of-a-proxy makes no sense; the user should pick
    // the original instead).
    if (proxyableVideoId != null) {
        items += androidx.compose.foundation.ContextMenuItem("Create proxy…") {
            onCreateProxy(proxyableVideoId)
        }
    }

    // "Go to Folder in Library" — find the library location whose path is
    // the longest prefix of this video's path, then scroll the left panel
    // to that location. Only shown when the caller supplies both the video
    // path and a non-empty locations list.
    if (videoPath != null && onGoToFolder != null && libraryLocations.isNotEmpty()) {
        val containing = libraryLocations
            .filter { videoPath.startsWith(it.path) }
            .maxByOrNull { it.path.length }
        if (containing != null) {
            items += androidx.compose.foundation.ContextMenuItem("Go to Folder in Library") {
                onGoToFolder(containing.path)
            }
        }
    }

    // Lightroom-style user-mark items. Apply to every video in
    // `ratingTargetIds` so multi-select keyboard equivalents map to the
    // same set the right-click menu addresses.
    if (onSetRating != null && ratingTargetIds.isNotEmpty()) {
        // Render 5..0 so the menu reads "★★★★★" at the top, which is the
        // common Lightroom muscle-memory.
        for (stars in 5 downTo 0) {
            val label = if (stars == 0) "Set Rating: No rating" else "Set Rating: ${"★".repeat(stars)}"
            items += androidx.compose.foundation.ContextMenuItem(label) {
                onSetRating(stars, ratingTargetIds)
            }
        }
    }
    if (onSetColorLabel != null && ratingTargetIds.isNotEmpty()) {
        for (label in com.reelvault.data.models.ColorLabel.values()) {
            val text = if (label == com.reelvault.data.models.ColorLabel.None)
                "Set Color Label: None"
            else
                "Set Color Label: ${label.displayName}"
            items += androidx.compose.foundation.ContextMenuItem(text) {
                onSetColorLabel(label.raw, ratingTargetIds)
            }
        }
    }

    // Stack-master picker. Only surfaces when the right-clicked card is a
    // non-representative member of its stack.
    if (stackMasterCandidate != null && onSetStackMaster != null) {
        val (vid, gid) = stackMasterCandidate
        items += androidx.compose.foundation.ContextMenuItem("Set as Stack Master") {
            onSetStackMaster(vid, gid)
        }
    }

    // Collection membership. Compose Desktop ContextMenuItem doesn't support
    // true submenus, so we render each collection as a flat menu item with a
    // "›" prefix to suggest the grouping.
    if (onAddToCollection != null && collections.isNotEmpty() && collectionTargetIds.isNotEmpty()) {
        val manualCollections = collections.filter { !it.isSmart }
        if (manualCollections.isNotEmpty()) {
            manualCollections.forEach { col ->
                val alreadyIn = col.id in videoCollections
                if (!alreadyIn) {
                    items += androidx.compose.foundation.ContextMenuItem("Add to Collection › ${col.name}") {
                        onAddToCollection(col.id, collectionTargetIds)
                    }
                }
            }
        }
    }
    if (onRemoveFromCollection != null && videoCollections.isNotEmpty() && collectionTargetIds.isNotEmpty()) {
        val collectionsContaining = collections.filter { it.id in videoCollections }
        collectionsContaining.forEach { col ->
            items += androidx.compose.foundation.ContextMenuItem("Remove from Collection › ${col.name}") {
                onRemoveFromCollection(col.id, collectionTargetIds)
            }
        }
    }

    return items
}

/**
 * One entry in the rendered grid. May be a regular ungrouped video, a stack
 * representative (collapsed or expanded), or an expanded stack child.
 */
data class GridItem(
    val video: VideoSummary,
    /** Representative of a stack that is currently expanded inline. */
    val isExpandedRepresentative: Boolean = false,
    /** A non-representative stack member shown inline because the stack is expanded. */
    val isStackChild: Boolean = false,
    /** 1-based position within the stack (only meaningful inside an expanded stack). */
    val memberPosition: Int = 0,
    val memberCount: Int = 0
)

/**
 * Splice the members of each expanded group into the grid right after its
 * representative. Preserves order of [videos] (representatives), and orders
 * stack members so the preferred one comes first.
 */
internal fun buildRenderedList(
    videos: List<VideoSummary>,
    expandedGroupIds: Set<String>,
    membersByGroup: Map<String, List<VideoSummary>>
): List<GridItem> {
    val out = mutableListOf<GridItem>()
    for (video in videos) {
        val isExpanded = video.isInGroup && video.groupId in expandedGroupIds
        if (!isExpanded) {
            out += GridItem(video = video)
            continue
        }

        // Stack is expanded — show the representative first, then non-representative
        // members inline.
        val allMembers = membersByGroup[video.groupId] ?: emptyList()
        // Reorder so the preferred (= representative shown) comes first; preserve original
        // order for the rest.
        val reordered = if (allMembers.isEmpty()) {
            emptyList()
        } else {
            val preferred = allMembers.firstOrNull { it.id == video.id }
            val others = allMembers.filter { it.id != video.id }
            listOfNotNull(preferred) + others
        }
        val total = reordered.size.coerceAtLeast(1)

        if (reordered.isEmpty()) {
            // Members not yet loaded — show just the representative with the expanded badge.
            out += GridItem(
                video = video,
                isExpandedRepresentative = true,
                memberPosition = 1,
                memberCount = total
            )
        } else {
            reordered.forEachIndexed { idx, member ->
                val position = idx + 1
                if (member.id == video.id) {
                    // Representative — keep its full VideoSummary (with group_size etc.)
                    out += GridItem(
                        video = video,
                        isExpandedRepresentative = true,
                        memberPosition = position,
                        memberCount = total
                    )
                } else {
                    // Stack child. The members fetched via ListGroupMembers carry their
                    // own group_id/group_size already.
                    out += GridItem(
                        video = member,
                        isStackChild = true,
                        memberPosition = position,
                        memberCount = total
                    )
                }
            }
        }
    }
    return out
}
