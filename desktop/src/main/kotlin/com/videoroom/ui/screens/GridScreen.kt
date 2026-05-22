// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.videoroom.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoroom.data.editors.EditorRegistry
import com.videoroom.data.editors.ExternalEditor
import com.videoroom.data.models.VideoSummary
import com.videoroom.ui.components.ComposeVideoPlayer
import com.videoroom.ui.components.VideoCard
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.GridViewModel

@Composable
fun GridScreen(
    viewModel: GridViewModel,
    onVideoSelect: (VideoSummary) -> Unit,
    /** Minimum width of each grid cell — also controls how many columns appear. */
    thumbnailMinWidth: androidx.compose.ui.unit.Dp = 220.dp,
    /** Opens the "External Editors" preferences dialog from the context menu. */
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
    val scrubFrames = viewModel.scrubFrames.collectAsState()
    val expandedGroupIds = viewModel.expandedGroupIds.collectAsState()
    val expandedMembers = viewModel.expandedGroupMembers.collectAsState()
    val shiftPressed = com.videoroom.LocalShiftPressed.current
    val playingVideoId = viewModel.playingVideoId.collectAsState()

    // Fast startup check: NativeDiscovery inspects filesystem paths only —
    // no JNI or player init — so this is safe to evaluate on the Compose
    // thread. The result is cached in ComposeVideoPlayer.isLibVlcAvailable.
    val vlcAvailable = remember { ComposeVideoPlayer.isLibVlcAvailable }

    // Single VLCJ player instance shared by all cards. Only one card plays
    // at a time; swapping is handled by loading a new path into this player.
    val inlinePlayer = remember { ComposeVideoPlayer() }
    DisposableEffect(Unit) { onDispose { inlinePlayer.release() } }

    // Start playback whenever playingVideoId changes to a non-null value.
    LaunchedEffect(playingVideoId.value) {
        val id = playingVideoId.value ?: return@LaunchedEffect
        val path = videos.value.find { it.id == id }?.openPath ?: return@LaunchedEffect
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
        // Status bar
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
                    com.videoroom.ui.components.Tooltip(text = "Dismiss this error message") {
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

        // Grid
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
                LazyVerticalGrid(
                    // Adaptive: as many columns as fit at the given minimum
                    // width. Cards expand from there to fill available space.
                    columns = GridCells.Adaptive(minSize = thumbnailMinWidth),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(VideoRoomSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small)
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
                        val isInMultiSelect = video.id in selectedVideoIds.value
                        val isAnchor = anchorVideoId.value == video.id &&
                                       selectedVideoIds.value.size > 1

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
                                buildVideoContextMenu(
                                    targetFiles = targets,
                                    onConfigureEditors = onConfigureEditors,
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
                                )
                            }
                        ) {
                            VideoCard(
                                video = video,
                                isSelected = isPrimary,
                                isInMultiSelection = isInMultiSelect,
                                isAnchor = isAnchor,
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
                                    when {
                                        // Startup check (fast path): NativeDiscovery said no
                                        // libvlc before we even tried to build a player.
                                        !vlcAvailable          -> showVlcErrorDialog = true
                                        // Instance check (fallback): player built but init failed.
                                        !inlinePlayer.available -> showVlcErrorDialog = true
                                        video.playableNatively  -> viewModel.playVideo(video.id)
                                        // Oversize video — open the proxy picker
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
                                suppressTooltip = isContextMenuOpen,
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
 * flat list. Disabled editors and editors that aren't installed are silently
 * omitted — the "Configure External Editors…" entry at the bottom is the
 * canonical way to enable more.
 */
internal fun buildVideoContextMenu(
    targetFiles: List<String>,
    onConfigureEditors: () -> Unit,
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
): List<androidx.compose.foundation.ContextMenuItem> {
    val items = mutableListOf<androidx.compose.foundation.ContextMenuItem>()
    val registry = EditorRegistry.Default
    val n = targetFiles.size
    val plural = if (n == 1) "" else "s"

    items += androidx.compose.foundation.ContextMenuItem(
        label = if (n == 1) "Open with Default Player" else "Open $n videos with Default Player"
    ) {
        targetFiles.forEach { registry.openWithDefault(it) }
    }

    if (n == 1) {
        items += androidx.compose.foundation.ContextMenuItem("Reveal in File Manager") {
            registry.revealInFileManager(targetFiles.first())
        }
    }

    // One entry per enabled+installed external editor.
    val available = registry.availableEditors()
    if (available.isNotEmpty()) {
        available.forEach { (editor, _) ->
            items += androidx.compose.foundation.ContextMenuItem(
                label = "Open with ${editor.name}" + (if (n > 1 && editor.supportsFileArgs) " ($n video$plural)" else "")
            ) {
                registry.launch(editor, targetFiles)
            }
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

    items += androidx.compose.foundation.ContextMenuItem("Configure External Editors…") {
        onConfigureEditors()
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
