// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.reelvault.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelvault.android.R
import com.reelvault.android.data.MediaStoreIngest
import com.reelvault.android.data.VideoShareManager
import com.reelvault.android.data.lastPairedUploadEndpoint
import com.reelvault.android.ui.components.LibraryFilterBar
import com.reelvault.android.ui.components.NavHistoryButtons
import com.reelvault.android.ui.components.ThumbnailImage
import com.reelvault.android.viewmodel.GridViewModel
import com.reelvault.android.ui.theme.swatch
import com.reelvault.data.models.Collection
import com.reelvault.data.models.ColorLabel
import com.reelvault.data.models.GridStatKey
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.models.defaultGridTopSlots
import com.reelvault.data.models.PostIndexProgress
import com.reelvault.data.models.Tag
import com.reelvault.data.models.VideoSummary
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.launch

// ── Column count thresholds (dp) ───────────────────────────────────────────
private const val TABLET_WIDTH_DP = 840
private const val WIDE_TABLET_WIDTH_DP = 1100

/**
 * Main library grid screen.  Matches the structure of iOS LibraryGridScreen:
 *
 *  - Top app bar: search, filter, view-mode toggle (grid/list), overflow menu
 *  - Left [NavigationDrawer]: library locations, tags, collections
 *  - Main grid or list content via [LazyVerticalGrid] / [LazyColumn]
 *  - Bottom status bar: video count + scanning banner
 *  - Pull-to-refresh on both layouts
 *  - Responsive columns: 2 on phones, 3 on small tablets, 4 on wide tablets
 *  - Single tap → select + show detail (phone: navigate; tablet: side panel)
 *  - Long press → toggle multi-select
 */
@Composable
fun LibraryGridScreen(
    repository: VideoRepository,
    vm: GridViewModel,
    onVideoSelected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onDisconnect: () -> Unit,
    onGoOffline: () -> Unit = {},
    // Catalog source switching. [isLocal] true = on-device (embedded core) mode;
    // [onOpenLocalMedia] switches server→local, [onSwitchToServer] local→server.
    isLocal: Boolean = false,
    onOpenLocalMedia: () -> Unit = {},
    onSwitchToServer: () -> Unit = {},
    // Session back/forward history (browser-style). Driven by AppRouter, which
    // owns the NavController; the chevrons live in the top bar.
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onHistoryBack: () -> Unit = {},
    onHistoryForward: () -> Unit = {},
) {
    // ── Collect state ────────────────────────────────────────────────────
    val videos by vm.videos.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val hasLoadedOnce by vm.hasLoadedOnce.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val totalCount by vm.totalCount.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val selectedVideoId by vm.selectedVideoId.collectAsStateWithLifecycle()
    val libraryLocations by vm.libraryLocations.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val watcherBanner by vm.watcherBanner.collectAsStateWithLifecycle()
    val incomingPairing by vm.incomingPairingDevice.collectAsStateWithLifecycle()
    val postIndexProgress by vm.postIndexProgress.collectAsStateWithLifecycle()
    val filterTagId by vm.filterTagId.collectAsStateWithLifecycle()
    val selectedCollectionId by vm.selectedCollectionId.collectAsStateWithLifecycle()
    val selectedCollectionIsSmart by vm.selectedCollectionIsSmart.collectAsStateWithLifecycle()
    val filterLocationLabel by vm.filterLocationLabel.collectAsStateWithLifecycle()
    val topSlots by vm.topSlots.collectAsStateWithLifecycle()
    val gridDensity by vm.gridDensity.collectAsStateWithLifecycle()
    // On-device ingest progress (Local mode) — drives the "importing" banner.
    val isIngesting by MediaStoreIngest.isIngesting.collectAsStateWithLifecycle()
    // Server health monitor — shows offline-mode prompt when the server drops.
    val serverUnreachable by vm.serverUnreachable.collectAsStateWithLifecycle()
    val offlineEntries by com.reelvault.android.data.OfflineLibrary.entries
        .collectAsStateWithLifecycle()

    // ── Local UI state ───────────────────────────────────────────────────
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf(searchQuery) }
    // Multi-select: set of selected ids when more than one item is selected.
    var multiSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isMultiSelect = multiSelectedIds.isNotEmpty()
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCardStats by remember { mutableStateOf(false) }
    var showDensitySheet by remember { mutableStateOf(false) }

    // ── Batch action dialog state ────────────────────────────────────────
    var showBatchOrganize by remember { mutableStateOf(false) }
    // Sharing state: true while downloading video files for the share sheet.
    var isBatchSharing by remember { mutableStateOf(false) }
    var batchShareCount by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // ── Local→remote upload (Local mode + a server has been paired) ──────
    val uploadEndpoint = remember(isLocal) {
        if (isLocal) lastPairedUploadEndpoint(context) else null
    }
    var showUploadSheet by remember { mutableStateOf(false) }
    var uploadTargets by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }

    // ── Stacking state ───────────────────────────────────────────────────
    // stackMembersForVideo: the VideoSummary whose stack badge was tapped,
    // used to open the StackMembersSheet.
    var stackMembersForVideo by remember { mutableStateOf<VideoSummary?>(null) }
    // Snackbar host state reused for stacking success feedback.
    // (snackbarHostState is declared below, but we reference it here via
    //  the lambda captures; Kotlin captures the reference, not the value.)

    // ── Adaptive min-card-width from density setting ────────────────────
    // Density 1..5 maps to a minimum card width (dp) passed to GridCells.Adaptive.
    // The grid reflows its column count automatically to fill available width.
    // These values were chosen so the default (density 3) yields roughly the
    // same column count as the previous fixed-column behaviour on each device class:
    //   phones (~360 dp): density 3 → minCardWidth 140 dp → ~2 columns
    //   small tablets (~800 dp): density 3 → ~5 columns (was 3 fixed → now more
    //     with adaptive, which is an improvement)
    // The user can pinch from 1 (many small cards) to 5 (few large cards).
    val gridMinCardWidthDp = when (gridDensity) {
        1 -> 90
        2 -> 120
        3 -> 160
        4 -> 220
        else -> 300  // 5
    }
    // ── Initial data load + event stream ────────────────────────────────
    LaunchedEffect(Unit) {
        vm.loadVideos()
        vm.loadLibraryLocations()
        vm.loadTags()
        vm.loadCollections()
        vm.loadGridSettings()
        vm.startCatalogEventStream()
    }
    DisposableEffect(Unit) {
        onDispose { vm.stopCatalogEventStream() }
    }

    // ── Server unreachable / go-offline prompt ───────────────────────────
    val savedServerEntry = remember {
        com.reelvault.android.data.DefaultServerPrefs(context).load()
    }
    if (serverUnreachable && offlineEntries.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                val e = savedServerEntry
                if (e != null) vm.keepWaiting(e.host, e.grpcPort) else vm.stopConnectionMonitor()
            },
            title = { Text(stringResource(R.string.offline_server_unreachable_title)) },
            text = { Text(stringResource(R.string.offline_server_unreachable_message)) },
            confirmButton = {
                TextButton(onClick = onGoOffline) {
                    Text(stringResource(R.string.offline_go_offline))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val e = savedServerEntry
                    if (e != null) vm.keepWaiting(e.host, e.grpcPort) else vm.stopConnectionMonitor()
                }) {
                    Text(stringResource(R.string.offline_keep_waiting))
                }
            }
        )
    }

    // ── Incoming pairing dialog ──────────────────────────────────────────
    if (incomingPairing != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissIncomingPairing() },
            title = { Text(stringResource(R.string.grid_incoming_pairing_title)) },
            text = { Text(stringResource(R.string.grid_incoming_pairing_message, incomingPairing ?: "")) },
            confirmButton = {
                TextButton(onClick = { vm.dismissIncomingPairing() }) { Text(stringResource(R.string.grid_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissIncomingPairing() }) { Text(stringResource(R.string.grid_deny)) }
            }
        )
    }

    // ── Error snackbar ───────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearError()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LibrarySidebarContent(
                libraryLocations = libraryLocations,
                tags = tags,
                collections = collections,
                activeTagId = filterTagId,
                activeCollectionId = selectedCollectionId,
                onSelectLocation = { path ->
                    vm.setLocationFilter(path)
                    scope.launch { drawerState.close() }
                },
                onSelectTag = { tagId ->
                    vm.setTagFilter(tagId)
                    scope.launch { drawerState.close() }
                },
                onSelectCollection = { id ->
                    vm.setCollectionFilter(id)
                    scope.launch { drawerState.close() }
                },
                onClearFilters = {
                    vm.setLocationFilter("")
                    vm.setTagFilter("")
                    vm.setCollectionFilter(null)
                    vm.clearGeoLocationFilter()
                    scope.launch { drawerState.close() }
                },
                onClose = { scope.launch { drawerState.close() } },
                isLocal = isLocal,
                onOpenLocalMedia = {
                    scope.launch { drawerState.close() }
                    onOpenLocalMedia()
                },
                onSwitchToServer = {
                    scope.launch { drawerState.close() }
                    onSwitchToServer()
                },
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    LibraryTopAppBar(
                        searchActive = searchActive,
                        searchText = searchText,
                        viewMode = viewMode,
                        isMultiSelect = isMultiSelect,
                        multiSelectCount = multiSelectedIds.size,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        onHistoryBack = onHistoryBack,
                        onHistoryForward = onHistoryForward,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onSearchActiveChange = { active ->
                            searchActive = active
                            if (!active) {
                                searchText = ""
                                vm.clearSearch()
                            }
                        },
                        onSearchTextChange = { text ->
                            searchText = text
                            vm.searchVideos(text)
                        },
                        onToggleViewMode = {
                            vm.setViewMode(if (viewMode == "grid") "list" else "grid")
                        },
                        onOpenSettings = onOpenSettings,
                        onOpenMap = onOpenMap,
                        onOpenCardStats = { showCardStats = true },
                        onOpenDensity = { showDensitySheet = true },
                        onDisconnect = onDisconnect,
                        onClearMultiSelect = { multiSelectedIds = emptySet() },
                        canUpload = uploadEndpoint != null,
                        onUpload = {
                            val selected = videos.filter { it.id in multiSelectedIds }
                            if (selected.isNotEmpty()) {
                                uploadTargets = selected
                                multiSelectedIds = emptySet()
                                vm.clearSelection()
                                showUploadSheet = true
                            }
                        },
                        showSortMenu = showSortMenu,
                        onShowSortMenu = { showSortMenu = true },
                        onDismissSortMenu = { showSortMenu = false },
                        onSetSort = { field, asc -> vm.setSort(field, asc) },
                        showOverflowMenu = showOverflowMenu,
                        onShowOverflowMenu = { showOverflowMenu = true },
                        onDismissOverflowMenu = { showOverflowMenu = false },
                        onCombineIntoStack = {
                            val ids = multiSelectedIds.toList()
                            vm.groupSelectedVideos(
                                videoIds = ids,
                                onSuccess = { msg ->
                                    multiSelectedIds = emptySet()
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                                onError = { /* vm already set _error */ },
                            )
                        },
                        onBatchOrganize = { showBatchOrganize = true },
                        onBatchShare = {
                            val ids = multiSelectedIds.toList()
                            val selectedVideos = videos.filter { it.id in multiSelectedIds }
                            if (selectedVideos.isEmpty()) return@LibraryTopAppBar
                            batchShareCount = selectedVideos.size
                            isBatchSharing = true
                            scope.launch {
                                try {
                                    val uris = ArrayList<Uri>()
                                    for (video in selectedVideos) {
                                        try {
                                            val uri = VideoShareManager.shareUri(
                                                context = context,
                                                videoId = video.id,
                                                filename = video.filename,
                                            )
                                            uris.add(uri)
                                        } catch (_: Exception) {
                                            // Skip files that fail to download.
                                        }
                                    }
                                    if (uris.isEmpty()) {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.batch_share_failed)
                                        )
                                    } else if (uris.size == 1) {
                                        val mimeType = VideoShareManager.mimeTypeFor(selectedVideos.first().filename)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = mimeType
                                            putExtra(Intent.EXTRA_STREAM, uris[0])
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent,
                                            context.getString(R.string.batch_share_title, ids.size)))
                                    } else {
                                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "video/*"
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent,
                                            context.getString(R.string.batch_share_title, ids.size)))
                                    }
                                } finally {
                                    isBatchSharing = false
                                }
                            }
                        },
                        isBatchSharing = isBatchSharing,
                    )
                    // Filter chip bar — always shown so the user can access
                    // rating/colour/attribute filters at a glance.
                    LibraryFilterBar(
                        viewModel = vm,
                        tags = tags,
                        collections = collections,
                    )
                    // Loading indicator sits directly below the app bar so it
                    // does not shift content in the main column.
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    // Post-index pipeline progress strip.
                    postIndexProgress?.let { pip ->
                        PostIndexProgressBar(pip)
                    }
                    // Scanning banner from the catalog event watcher.
                    watcherBanner?.let { banner ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = banner,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    // On-device ingest banner (Local mode) — mirrors iOS IngestBanner.
                    if (isIngesting) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.local_importing_banner),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                LibraryStatusBar(
                    totalCount = totalCount,
                    hasMore = hasMore,
                    viewMode = viewMode,
                    filterTagId = filterTagId,
                    selectedCollectionId = selectedCollectionId,
                    filterLocationLabel = filterLocationLabel,
                    onClearGeoFilter = { vm.clearGeoLocationFilter() },
                    tags = tags,
                    collections = collections,
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when {
                    !hasLoadedOnce && isLoading -> {
                        // First load — nothing to show yet.
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    hasLoadedOnce && videos.isEmpty() -> {
                        EmptyLibraryMessage(
                            searchQuery = searchQuery,
                            filterTagId = filterTagId,
                            selectedCollectionId = selectedCollectionId,
                            selectedCollectionIsSmart = selectedCollectionIsSmart,
                            hasActiveFilter = vm.hasActiveLibraryFilter(),
                            onResetFilter = { vm.clearAllFilters() },
                        )
                    }

                    else -> {
                        // ── Shared stacking lambdas ────────────────────────
                        // Defined once and passed to both grid and list content
                        // so that the logic is not duplicated.
                        // ── Stacking lambdas ──────────────────────────────────
                        val onStackBadgeClick: (VideoSummary) -> Unit = { video ->
                            stackMembersForVideo = video
                        }
                        val onRemoveFromStack: (String, String) -> Unit = { videoId, groupId ->
                            vm.removeFromStack(videoId, groupId)
                        }
                        val onUnstack: (String) -> Unit = { groupId ->
                            vm.unstackGroup(groupId)
                        }
                        val onSetStackMaster: (String, String) -> Unit = { videoId, groupId ->
                            vm.setStackMaster(videoId, groupId)
                        }
                        val onCombineIntoStack: () -> Unit = combineIntoStack@{
                            val ids = multiSelectedIds.toList()
                            if (ids.size < 2) return@combineIntoStack
                            vm.groupSelectedVideos(
                                videoIds = ids,
                                onSuccess = { msg ->
                                    multiSelectedIds = emptySet()
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                            )
                        }
                        if (viewMode == "grid") {
                            GridContent(
                                videos = videos,
                                minCardWidthDp = gridMinCardWidthDp,
                                repository = repository,
                                selectedVideoId = selectedVideoId,
                                multiSelectedIds = multiSelectedIds,
                                hasMore = hasMore,
                                topSlots = topSlots,
                                onUpdateGridTopSlot = { idx, key -> vm.updateGridTopSlot(idx, key) },
                                onLoadMore = { vm.loadMore() },
                                onRefresh = { vm.loadVideos() },
                                onTap = { video ->
                                    if (isMultiSelect) {
                                        // Toggle this item in the multi-select set.
                                        multiSelectedIds = multiSelectedIds.toMutableSet().also {
                                            if (video.id in it) it.remove(video.id) else it.add(video.id)
                                        }
                                        if (multiSelectedIds.isEmpty()) {
                                            vm.clearSelection()
                                        }
                                    } else {
                                        vm.selectVideo(video.id)
                                        onVideoSelected(video.id)
                                    }
                                },
                                onLongPress = { video ->
                                    multiSelectedIds = if (isMultiSelect) {
                                        multiSelectedIds.toMutableSet().also {
                                            if (video.id in it) it.remove(video.id) else it.add(video.id)
                                        }
                                    } else {
                                        setOf(video.id)
                                    }
                                    vm.selectVideo(video.id)
                                },
                                onSetRating = { video, rating ->
                                    val ids = if (multiSelectedIds.isNotEmpty()) multiSelectedIds.toList()
                                              else listOf(video.id)
                                    vm.setRating(ids, rating)
                                },
                                onSetColorLabel = { video, label ->
                                    val ids = if (multiSelectedIds.isNotEmpty()) multiSelectedIds.toList()
                                              else listOf(video.id)
                                    vm.setColorLabel(ids, label)
                                },
                                onStackBadgeClick = onStackBadgeClick,
                                onRemoveFromStack = onRemoveFromStack,
                                onUnstack = onUnstack,
                                onSetStackMaster = onSetStackMaster,
                                onCombineIntoStack = onCombineIntoStack,
                            )
                        } else {
                            ListContent(
                                videos = videos,
                                repository = repository,
                                selectedVideoId = selectedVideoId,
                                multiSelectedIds = multiSelectedIds,
                                hasMore = hasMore,
                                onLoadMore = { vm.loadMore() },
                                onRefresh = { vm.loadVideos() },
                                onTap = { video ->
                                    if (isMultiSelect) {
                                        multiSelectedIds = multiSelectedIds.toMutableSet().also {
                                            if (video.id in it) it.remove(video.id) else it.add(video.id)
                                        }
                                        if (multiSelectedIds.isEmpty()) {
                                            vm.clearSelection()
                                        }
                                    } else {
                                        vm.selectVideo(video.id)
                                        onVideoSelected(video.id)
                                    }
                                },
                                onLongPress = { video ->
                                    multiSelectedIds = if (isMultiSelect) {
                                        multiSelectedIds.toMutableSet().also {
                                            if (video.id in it) it.remove(video.id) else it.add(video.id)
                                        }
                                    } else {
                                        setOf(video.id)
                                    }
                                    vm.selectVideo(video.id)
                                },
                                onSetRating = { video, rating ->
                                    val ids = if (multiSelectedIds.isNotEmpty()) multiSelectedIds.toList()
                                              else listOf(video.id)
                                    vm.setRating(ids, rating)
                                },
                                onSetColorLabel = { video, label ->
                                    val ids = if (multiSelectedIds.isNotEmpty()) multiSelectedIds.toList()
                                              else listOf(video.id)
                                    vm.setColorLabel(ids, label)
                                },
                                onRemoveFromStack = onRemoveFromStack,
                                onUnstack = onUnstack,
                                onSetStackMaster = onSetStackMaster,
                                onCombineIntoStack = onCombineIntoStack,
                            )
                        }
                    }
                }
            }
        }

        // ── Batch organize sheet (rating / color label / keyword / collection) ─
        if (showBatchOrganize) {
            BatchOrganizeSheet(
                videoIds = multiSelectedIds.toList(),
                tags = tags,
                collections = collections,
                onDismiss = { showBatchOrganize = false },
                onSetRating = { rating ->
                    vm.setRating(multiSelectedIds.toList(), rating)
                    showBatchOrganize = false
                },
                onSetColorLabel = { label ->
                    vm.setColorLabel(multiSelectedIds.toList(), label)
                    showBatchOrganize = false
                },
                onApplyKeyword = { keyword ->
                    vm.applyKeyword(keyword, multiSelectedIds.toList())
                },
                onAddToCollection = { collectionId ->
                    vm.addToCollection(multiSelectedIds.toList(), collectionId)
                    showBatchOrganize = false
                },
            )
        }

        // ── Stack members sheet ──────────────────────────────────────────────
        // Shown when the user taps a stack badge on a grid card.  Displays all
        // members of the stack with Promote and Remove actions, mirroring the
        // desktop inspector panel's "Versions" section behaviour on a touch
        // device.
        stackMembersForVideo?.let { stackVideo ->
            StackMembersSheet(
                groupId = stackVideo.groupId,
                vm = vm,
                repository = repository,
                onDismiss = { stackMembersForVideo = null },
            )
        }

        // ── Card stats config sheet ─────────────────────────────────────────
        if (showCardStats) {
            TopSlotsConfigSheet(
                topSlots = topSlots,
                onUpdateSlot = { idx, key -> vm.updateGridTopSlot(idx, key) },
                onDismiss = { showCardStats = false },
            )
        }

        // ── Grid density / thumbnail-size sheet ─────────────────────────────
        if (showDensitySheet) {
            GridDensitySheet(
                density = gridDensity,
                onDensityChange = { vm.setGridDensity(it) },
                onDismiss = { showDensitySheet = false },
            )
        }

        // ── Upload-to-server sheet (Local mode) ─────────────────────────────
        if (showUploadSheet) {
            uploadEndpoint?.let { ep ->
                UploadToServerSheet(
                    endpoint = ep,
                    videos = uploadTargets,
                    onDismiss = { showUploadSheet = false },
                )
            }
        }

        // ── Batch sharing progress indicator ────────────────────────────────
        if (isBatchSharing) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(stringResource(R.string.batch_share_preparing, batchShareCount)) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(stringResource(R.string.detail_share_preparing))
                    }
                },
            )
        }
    }
}

// ── Top App Bar ─────────────────────────────────────────────────────────────

@Composable
private fun LibraryTopAppBar(
    searchActive: Boolean,
    searchText: String,
    viewMode: String,
    isMultiSelect: Boolean,
    multiSelectCount: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onHistoryBack: () -> Unit,
    onHistoryForward: () -> Unit,
    onOpenDrawer: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenCardStats: () -> Unit = {},
    onOpenDensity: () -> Unit = {},
    onDisconnect: () -> Unit,
    onClearMultiSelect: () -> Unit,
    canUpload: Boolean = false,
    onUpload: () -> Unit = {},
    showSortMenu: Boolean,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSetSort: (String, Boolean) -> Unit,
    showOverflowMenu: Boolean,
    onShowOverflowMenu: () -> Unit,
    onDismissOverflowMenu: () -> Unit,
    onBatchOrganize: () -> Unit = {},
    onBatchShare: () -> Unit = {},
    isBatchSharing: Boolean = false,
    onCombineIntoStack: () -> Unit = {},
) {
    if (searchActive) {
        // Expanded search bar replaces the full app bar.
        SearchBar(
            query = searchText,
            onQueryChange = onSearchTextChange,
            onSearch = {},
            active = true,
            onActiveChange = { if (!it) onSearchActiveChange(false) },
            leadingIcon = {
                IconButton(onClick = { onSearchActiveChange(false) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.grid_close_search))
                }
            },
            placeholder = { Text(stringResource(R.string.grid_search_videos_hint)) },
            trailingIcon = if (searchText.isNotEmpty()) {
                { IconButton(onClick = { onSearchTextChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.common_clear))
                } }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        ) {}
    } else {
        TopAppBar(
            title = {
                if (isMultiSelect) {
                    Text(stringResource(R.string.grid_selected_count, multiSelectCount))
                }
            },
            navigationIcon = {
                if (isMultiSelect) {
                    IconButton(onClick = onClearMultiSelect) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.grid_clear_selection))
                    }
                } else {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.grid_open_library_panel))
                    }
                }
            },
            actions = {
                if (isMultiSelect) {
                    // ── Multi-select batch actions ───────────────────────
                    // Upload to the paired server (Local mode only).
                    if (canUpload) {
                        IconButton(onClick = onUpload) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = stringResource(R.string.upload_action),
                            )
                        }
                    }
                    // Combine into stack (2+ selected)
                    if (multiSelectCount >= 2) {
                        IconButton(onClick = onCombineIntoStack) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = stringResource(R.string.stack_combine_into_stack),
                            )
                        }
                    }
                    // Share
                    IconButton(
                        onClick = onBatchShare,
                        enabled = !isBatchSharing,
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.batch_share),
                        )
                    }
                    // Organize: rating / color label / keyword / collection
                    IconButton(onClick = onBatchOrganize) {
                        Icon(
                            Icons.Default.Sell,
                            contentDescription = stringResource(R.string.batch_organize),
                        )
                    }
                } else {
                    // ── Normal actions ───────────────────────────────────
                    // Browser-style back/forward across the session's browse path.
                    NavHistoryButtons(
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        onBack = onHistoryBack,
                        onForward = onHistoryForward,
                    )
                    // Search
                    IconButton(onClick = { onSearchActiveChange(true) }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.grid_search))
                    }
                    // Sort
                    Box {
                        IconButton(onClick = onShowSortMenu) {
                            Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.grid_sort))
                        }
                        SortDropdownMenu(
                            expanded = showSortMenu,
                            onDismiss = onDismissSortMenu,
                            onSetSort = onSetSort,
                        )
                    }
                    // Grid / List toggle
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (viewMode == "grid") Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = if (viewMode == "grid") stringResource(R.string.grid_switch_to_list_view) else stringResource(R.string.grid_switch_to_grid_view),
                        )
                    }
                    // Overflow menu
                    Box {
                        IconButton(onClick = onShowOverflowMenu) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.grid_more_options))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = onDismissOverflowMenu,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.grid_map)) },
                                leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) },
                                onClick = { onDismissOverflowMenu(); onOpenMap() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.grid_card_stats)) },
                                leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null) },
                                onClick = { onDismissOverflowMenu(); onOpenCardStats() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.grid_density_menu_item)) },
                                leadingIcon = { Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null) },
                                onClick = { onDismissOverflowMenu(); onOpenDensity() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.grid_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = { onDismissOverflowMenu(); onOpenSettings() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.grid_disconnect)) },
                                leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                                onClick = { onDismissOverflowMenu(); onDisconnect() },
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SortDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSetSort: (String, Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.grid_sort_by),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val options = listOf(
            "indexed_at"    to stringResource(R.string.sort_date_indexed),
            "creation_date" to stringResource(R.string.sort_date_captured),
            "filename"      to stringResource(R.string.sort_filename),
            "size_bytes"    to stringResource(R.string.sort_file_size),
            "duration_ms"   to stringResource(R.string.sort_duration),
            "resolution"    to stringResource(R.string.sort_resolution),
            "fps"           to stringResource(R.string.sort_frame_rate),
            "codec"         to stringResource(R.string.sort_codec),
            "bitrate"       to stringResource(R.string.sort_bitrate),
            "camera"        to stringResource(R.string.sort_camera),
            "lens"          to stringResource(R.string.sort_lens),
            "iso"           to stringResource(R.string.sort_iso),
            "aperture"      to stringResource(R.string.sort_aperture),
            "exposure_time" to stringResource(R.string.sort_exposure_time),
            "focal_length"  to stringResource(R.string.sort_focal_length),
            "keyword"       to stringResource(R.string.sort_keyword),
            "rating"        to stringResource(R.string.sort_rating),
        )
        options.forEach { (field, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.grid_sort_ascending_arrow, label)) },
                onClick = { onDismiss(); onSetSort(field, true) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.grid_sort_descending_arrow, label)) },
                onClick = { onDismiss(); onSetSort(field, false) },
            )
        }
    }
}

// ── Navigation Drawer content (mirrors iOS LibrarySidebar) ──────────────────

@Composable
private fun LibrarySidebarContent(
    libraryLocations: List<LibraryLocation>,
    tags: List<Tag>,
    collections: List<Collection>,
    activeTagId: String,
    activeCollectionId: String?,
    onSelectLocation: (String) -> Unit,
    onSelectTag: (String) -> Unit,
    onSelectCollection: (String) -> Unit,
    onClearFilters: () -> Unit,
    onClose: () -> Unit,
    isLocal: Boolean = false,
    onOpenLocalMedia: () -> Unit = {},
    onSwitchToServer: () -> Unit = {},
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.grid_library),
                style = MaterialTheme.typography.titleMedium,
            )
            Row {
                if (activeTagId.isNotEmpty() || activeCollectionId != null) {
                    TextButton(onClick = onClearFilters) {
                        Text(stringResource(R.string.grid_clear_filters))
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.grid_close))
                }
            }
        }

        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ── Catalog switcher ───────────────────────────────────────
            item {
                SidebarSectionHeader(stringResource(R.string.grid_catalog))
            }
            item {
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (!isLocal) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    label = { Text(stringResource(R.string.grid_server_library)) },
                    selected = !isLocal,
                    onClick = { if (isLocal) onSwitchToServer() else onClose() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            Icons.Default.Smartphone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isLocal) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    label = { Text(stringResource(R.string.local_this_device)) },
                    selected = isLocal,
                    onClick = { if (!isLocal) onOpenLocalMedia() else onClose() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item { HorizontalDivider() }

            // ── All Videos ─────────────────────────────────────────────
            item {
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(stringResource(R.string.grid_all_videos)) },
                    selected = activeTagId.isEmpty() && activeCollectionId == null,
                    onClick = { onClearFilters() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            // ── Library Locations ──────────────────────────────────────
            if (libraryLocations.isNotEmpty()) {
                item {
                    SidebarSectionHeader(stringResource(R.string.grid_locations))
                }
                items(libraryLocations) { loc ->
                    // Last path segment only, e.g. "/volume1/Videos" -> "Videos".
                    // Trim a trailing slash first so "/volume1/Videos/" doesn't
                    // fall through to the full path. Matches desktop/iOS/macOS.
                    val trimmed = loc.path.trimEnd('/')
                    val displayName = trimmed.substringAfterLast('/', missingDelimiterValue = trimmed)
                        .ifEmpty { "/" }
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = {
                            Column {
                                Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (loc.videoCount > 0) {
                                    val locCount = loc.videoCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                    Text(
                                        pluralStringResource(R.plurals.grid_location_video_count, locCount, locCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        selected = false,
                        onClick = { onSelectLocation(loc.path) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            // ── Tags ───────────────────────────────────────────────────
            if (tags.isNotEmpty()) {
                item { HorizontalDivider() }
                item { SidebarSectionHeader(stringResource(R.string.grid_keywords)) }
                items(tags) { tag ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Default.Sell,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (activeTagId == tag.id)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tag.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (tag.videoCount > 0) {
                                    Text(
                                        "${tag.videoCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        selected = activeTagId == tag.id,
                        onClick = { onSelectTag(tag.id) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            // ── Collections ────────────────────────────────────────────
            if (collections.isNotEmpty()) {
                item { HorizontalDivider() }
                item { SidebarSectionHeader(stringResource(R.string.grid_collections)) }
                items(collections) { collection ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                if (collection.isSmart) Icons.Default.AutoAwesome else Icons.Default.Collections,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (activeCollectionId == collection.id)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    collection.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (collection.videoCount > 0) {
                                    Text(
                                        "${collection.videoCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        selected = activeCollectionId == collection.id,
                        onClick = { onSelectCollection(collection.id) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

// ── Grid content ────────────────────────────────────────────────────────────

@Composable
private fun GridContent(
    videos: List<VideoSummary>,
    minCardWidthDp: Int,
    repository: VideoRepository,
    selectedVideoId: String?,
    multiSelectedIds: Set<String>,
    hasMore: Boolean,
    topSlots: List<String> = defaultGridTopSlots,
    onUpdateGridTopSlot: (Int, String) -> Unit = { _, _ -> },
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onTap: (VideoSummary) -> Unit,
    onLongPress: (VideoSummary) -> Unit,
    onSetRating: (VideoSummary, Int) -> Unit,
    onSetColorLabel: (VideoSummary, String) -> Unit,
    onStackBadgeClick: ((VideoSummary) -> Unit)? = null,
    onRemoveFromStack: ((videoId: String, groupId: String) -> Unit)? = null,
    onUnstack: ((groupId: String) -> Unit)? = null,
    onSetStackMaster: ((videoId: String, groupId: String) -> Unit)? = null,
    onCombineIntoStack: (() -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    // Trigger pagination near the end of the list.
    val shouldLoadMore = remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val totalItems = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && totalItems > 0 && lastVisible >= totalItems - 8
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) onLoadMore()
    }

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) {
            onRefresh()
            pullRefreshState.endRefresh()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // Adaptive layout: the grid flows as many columns as fit given the
            // minimum card width. The user-controlled density maps to minCardWidthDp
            // so adjusting the slider instantly reflows the entire grid without
            // an explicit reload — mirroring the iOS/desktop adaptive grid.
            columns = GridCells.Adaptive(minSize = minCardWidthDp.dp),
            state = gridState,
            modifier = Modifier.nestedScroll(pullRefreshState.nestedScrollConnection).fillMaxSize(),
            // A small gutter so cards don't sit flush against each other / the edges.
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                items = videos,
                key = { it.id },
            ) { video ->
                val isSelected = video.id == selectedVideoId
                val isMultiSelected = video.id in multiSelectedIds
                AndroidVideoCard(
                    video = video,
                    isSelected = isSelected,
                    isMultiSelected = isMultiSelected,
                    repository = repository,
                    topSlots = topSlots,
                    onUpdateGridTopSlot = onUpdateGridTopSlot,
                    onTap = { onTap(video) },
                    onLongPress = { onLongPress(video) },
                    onSetRating = { rating -> onSetRating(video, rating) },
                    onSetColorLabel = { label -> onSetColorLabel(video, label) },
                    multiSelectCount = multiSelectedIds.size,
                    onStackBadgeClick = if (onStackBadgeClick != null) { { onStackBadgeClick(video) } } else null,
                    onRemoveFromStack = onRemoveFromStack,
                    onUnstack = onUnstack,
                    onSetStackMaster = onSetStackMaster,
                    onCombineIntoStack = onCombineIntoStack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ── List content ─────────────────────────────────────────────────────────────

@Composable
private fun ListContent(
    videos: List<VideoSummary>,
    repository: VideoRepository,
    selectedVideoId: String?,
    multiSelectedIds: Set<String>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onTap: (VideoSummary) -> Unit,
    onLongPress: (VideoSummary) -> Unit,
    onSetRating: (VideoSummary, Int) -> Unit,
    onSetColorLabel: (VideoSummary, String) -> Unit,
    onRemoveFromStack: ((videoId: String, groupId: String) -> Unit)? = null,
    onUnstack: ((groupId: String) -> Unit)? = null,
    onSetStackMaster: ((videoId: String, groupId: String) -> Unit)? = null,
    onCombineIntoStack: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Trigger pagination near the end.
    val shouldLoadMore = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val totalItems = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && totalItems > 0 && lastVisible >= totalItems - 5
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) onLoadMore()
    }

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) {
            onRefresh()
            pullRefreshState.endRefresh()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.nestedScroll(pullRefreshState.nestedScrollConnection).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 0.dp),
        ) {
            items(
                items = videos,
                key = { it.id },
            ) { video ->
                val isSelected = video.id == selectedVideoId
                val isMultiSelected = video.id in multiSelectedIds
                VideoListRow(
                    video = video,
                    isSelected = isSelected,
                    isMultiSelected = isMultiSelected,
                    repository = repository,
                    onTap = { onTap(video) },
                    onLongPress = { onLongPress(video) },
                    onSetRating = { rating -> onSetRating(video, rating) },
                    onSetColorLabel = { label -> onSetColorLabel(video, label) },
                    multiSelectCount = multiSelectedIds.size,
                    onRemoveFromStack = onRemoveFromStack,
                    onUnstack = onUnstack,
                    onSetStackMaster = onSetStackMaster,
                    onCombineIntoStack = onCombineIntoStack,
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ── Android VideoCard (grid cell) ────────────────────────────────────────────
//
// The desktop VideoCard is a heavyweight Compose Desktop component with many
// desktop-only features (VLCJ inline player, AWT drag-out, hover scrub frames).
// This Android version is a clean re-implementation that preserves the visual
// structure (three-band card: stat band / thumbnail / rating band) while using
// Android-appropriate affordances: Coil for async image loading, ripple for
// press feedback, no drag-out.

// Top band of the grid card: four metadata stats laid out 2×2 (slot 0 = top-
// left/emphasized, 1 = bottom-left, 2 = top-right, 3 = bottom-right), using the
// shared GridStatKey slots — matching iOS and the two desktop clients.
// Each cell is tappable: a DropdownMenu lets the user pick a new stat key for
// that slot, applying catalog-wide (via onUpdateSlot).
@Composable
private fun CardTopStatBand(
    video: VideoSummary,
    backgroundColor: Color,
    topSlots: List<String> = defaultGridTopSlots,
    onUpdateSlot: (Int, String) -> Unit = { _, _ -> },
    selected: Boolean = false,
) {
    val paddedSlots = remember(topSlots) {
        val s = topSlots.toMutableList()
        while (s.size < 4) s.add("")
        s.take(4)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            CardStatCell(
                video = video,
                key = paddedSlots[0],
                slotIndex = 0,
                leading = true,
                emphasized = true,
                selected = selected,
                onPick = onUpdateSlot,
            )
            CardStatCell(
                video = video,
                key = paddedSlots[2],
                slotIndex = 2,
                leading = false,
                emphasized = false,
                selected = selected,
                onPick = onUpdateSlot,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            CardStatCell(
                video = video,
                key = paddedSlots[1],
                slotIndex = 1,
                leading = true,
                emphasized = false,
                selected = selected,
                onPick = onUpdateSlot,
            )
            CardStatCell(
                video = video,
                key = paddedSlots[3],
                slotIndex = 3,
                leading = false,
                emphasized = false,
                selected = selected,
                onPick = onUpdateSlot,
            )
        }
    }
}

/**
 * One cell in the card's top stat band.
 *
 * Tapping the cell opens a [DropdownMenu] with all [GridStatKey] choices —
 * the same picker the desktop shows on right-click. The chosen key is applied
 * catalog-wide via [onPick](slotIndex, GridStatKey.raw).
 *
 * An empty/unset slot displays "—" so the user can discover the tap target,
 * matching the desktop StatCell behaviour.
 */
@Composable
private fun RowScope.CardStatCell(
    video: VideoSummary,
    key: String,
    slotIndex: Int,
    leading: Boolean,
    emphasized: Boolean,
    selected: Boolean = false,
    onPick: (Int, String) -> Unit = { _, _ -> },
) {
    val stat = GridStatKey.fromRaw(key)
    val value = stat.valueFor(video)
    val displayed = if (value.isEmpty()) "—" else value

    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .weight(1f)
            .clickable { expanded = true },
        contentAlignment = if (leading) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Text(
            text = displayed,
            style = if (emphasized) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            color = when {
                stat == GridStatKey.None ->
                    if (selected) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.45f)
                selected -> Color.Black
                else -> Color.White.copy(alpha = if (emphasized) 0.92f else 0.72f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (leading) TextAlign.Start else TextAlign.End,
        )
        // Slot picker dropdown — same choices as the desktop StatCell right-click menu.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            GridStatKey.values().forEach { choice ->
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
                    },
                )
            }
        }
    }
}

@Composable
private fun AndroidVideoCard(
    video: VideoSummary,
    isSelected: Boolean,
    isMultiSelected: Boolean,
    repository: VideoRepository,
    topSlots: List<String> = defaultGridTopSlots,
    onUpdateGridTopSlot: (Int, String) -> Unit = { _, _ -> },
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (String) -> Unit,
    // ── Stacking actions ─────────────────────────────────────────────────
    multiSelectCount: Int = 0,
    onStackBadgeClick: (() -> Unit)? = null,
    onRemoveFromStack: ((videoId: String, groupId: String) -> Unit)? = null,
    onUnstack: ((groupId: String) -> Unit)? = null,
    onSetStackMaster: ((videoId: String, groupId: String) -> Unit)? = null,
    onCombineIntoStack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isMultiSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val borderWidth = if (isSelected || isMultiSelected) 2.dp else 0.5.dp

    val topBandColor = when {
        isSelected -> Color(0xFFDFDFDF)
        isMultiSelected -> Color(0xFFB3B3B3)
        else -> Color(0xFF6B6B6B)
    }
    val bottomBandColor = when {
        isSelected -> Color(0xFFCFCFCF)
        isMultiSelected -> Color(0xFF9E9E9E)
        else -> Color(0xFF5C5C5C)
    }
    val photoAreaBackground = when {
        isSelected -> Color(0xFF999999)
        isMultiSelected -> Color(0xFF707070)
        else -> Color(0xFF474747)
    }

    // Context menu state — shown on long-press.
    var showContextMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .border(borderWidth, borderColor)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    onLongPress()
                    showContextMenu = true
                },
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Top stat band (2×2 configurable slots) ─────────────────
            CardTopStatBand(
                video = video,
                backgroundColor = topBandColor,
                topSlots = topSlots,
                onUpdateSlot = onUpdateGridTopSlot,
                selected = isSelected || isMultiSelected,
            )

            HorizontalDivider(color = Color.Black.copy(alpha = 0.35f), thickness = 1.dp)

            // ── Thumbnail ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(photoAreaBackground),
                contentAlignment = Alignment.Center,
            ) {
                ThumbnailImage(
                    videoId = video.id,
                    repository = repository,
                    size = "small",
                    contentScale = ContentScale.Fit,
                    contentDescription = video.filename,
                    modifier = Modifier.fillMaxSize(),
                )

                // Offline indicator
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
                                contentDescription = stringResource(R.string.card_offline),
                                modifier = Modifier.size(14.dp),
                                tint = Color.White,
                            )
                            Text(
                                stringResource(R.string.card_offline),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                }

                // ── Stack / group badge (top-start) ──────────────────────
                // Mirrors the badge in the standalone VideoCard component.
                // Tapping the badge opens the StackMembersSheet so the user
                // can see all members and promote/remove them from the grid.
                if (video.isInGroup) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 6.dp, top = 4.dp)
                            .then(
                                if (onStackBadgeClick != null)
                                    Modifier.clickable { onStackBadgeClick() }
                                else Modifier
                            ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = pluralStringResource(R.plurals.card_stack_count, video.groupSize, video.groupSize),
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

                // Multi-select checkbox overlay — top-start corner.
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
                            contentDescription = stringResource(R.string.card_selected),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                // Bottom-right status badges (keyword / proxy / full-resolution)
                AndroidCardStatusBadges(video = video, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }

            HorizontalDivider(color = Color.Black.copy(alpha = 0.35f), thickness = 1.dp)

            // ── Bottom rating band (tappable stars) ────────────────────
            // Tap a star to set that rating; tap the current star to clear
            // (toggle back to 0) — mirrors the iOS and desktop behaviour.
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
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                // Tap current star to clear; tap any other to set.
                                onSetRating(if (video.rating == pos) 0 else pos)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (filled) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF1F1F1F),
                                )
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = stringResource(R.string.card_star, pos),
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
                    if (pos < 5) Spacer(Modifier.width(4.dp))
                }
            }
        }

        // ── Long-press context menu (rating + color label + stacking) ───
        // Anchored to the top-start of the card so it doesn't clip off screen.
        VideoCardContextMenu(
            video = video,
            expanded = showContextMenu,
            onDismiss = { showContextMenu = false },
            onSetRating = { rating ->
                onSetRating(rating)
                showContextMenu = false
            },
            onSetColorLabel = { label ->
                onSetColorLabel(label)
                showContextMenu = false
            },
            onRemoveFromStack = onRemoveFromStack,
            onUnstack = onUnstack,
            onSetStackMaster = onSetStackMaster,
            multiSelectCount = multiSelectCount,
            onCombineIntoStack = onCombineIntoStack,
        )
    }
}

// ── List row (list mode) ─────────────────────────────────────────────────────

@Composable
private fun VideoListRow(
    video: VideoSummary,
    isSelected: Boolean,
    isMultiSelected: Boolean,
    repository: VideoRepository,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (String) -> Unit,
    multiSelectCount: Int = 0,
    onRemoveFromStack: ((videoId: String, groupId: String) -> Unit)? = null,
    onUnstack: ((groupId: String) -> Unit)? = null,
    onSetStackMaster: ((videoId: String, groupId: String) -> Unit)? = null,
    onCombineIntoStack: (() -> Unit)? = null,
) {
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isMultiSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }

    var showContextMenu by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    onLongPress()
                    showContextMenu = true
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Thumbnail — square, fixed size
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF474747)),
            contentAlignment = Alignment.Center,
        ) {
            ThumbnailImage(
                videoId = video.id,
                repository = repository,
                size = "small",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (!video.isOnline) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = stringResource(R.string.card_offline),
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        // Text info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = video.durationFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = video.resolution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (video.codecVideo.isNotEmpty()) {
                    Text(
                        text = video.codecVideo.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (video.cameraDisplayName.isNotEmpty()) {
                Text(
                    text = video.cameraDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Right-side badges + selection indicator
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isSelected || isMultiSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.card_selected),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (video.rating > 0) {
                Row {
                    repeat(video.rating) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            AndroidCardStatusBadges(video = video)
        }
    } // Row

        // Long-press context menu anchored to the row.
        VideoCardContextMenu(
            video = video,
            expanded = showContextMenu,
            onDismiss = { showContextMenu = false },
            onSetRating = { rating ->
                onSetRating(rating)
                showContextMenu = false
            },
            onSetColorLabel = { label ->
                onSetColorLabel(label)
                showContextMenu = false
            },
            onRemoveFromStack = onRemoveFromStack,
            onUnstack = onUnstack,
            onSetStackMaster = onSetStackMaster,
            multiSelectCount = multiSelectCount,
            onCombineIntoStack = onCombineIntoStack,
        )
    } // Box
}

// ── Long-press context menu: rating + color label ─────────────────────────────
//
// Mirrors the iOS VideoCardMenu and the desktop's right-click context menu.
// Appears on a long-press of a grid card or list row; applies to the
// target video (or to every video in an active multi-select set, which the
// caller computes before forwarding here via onSetRating/onSetColorLabel).
//
// Rating sub-menu: 0 (none) through 5 stars; the current value gets a
// checkmark dot. Tapping the current rating clears it (0), matching
// Lightroom and the iOS/desktop clients.
//
// Colour-label sub-menu: None + 5 Lightroom colours. A coloured dot precedes
// each label name (menu-item icons can't be tinted per-item in Material3,
// so a unicode circle is used instead — matching the iOS approach).

@Composable
private fun VideoCardContextMenu(
    video: VideoSummary,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (String) -> Unit,
    // ── Stacking actions ─────────────────────────────────────────────────
    // When the long-pressed card is in a stack, callers supply these.
    onRemoveFromStack: ((videoId: String, groupId: String) -> Unit)? = null,
    onUnstack: ((groupId: String) -> Unit)? = null,
    onSetStackMaster: ((videoId: String, groupId: String) -> Unit)? = null,
    // When 2+ cards are selected and the user long-pressed one of them.
    multiSelectCount: Int = 0,
    onCombineIntoStack: (() -> Unit)? = null,
) {
    val colorLabelEnum = ColorLabel.from(video.colorLabel)

    // Single flat DropdownMenu with labelled sections.
    // Cascading sub-menus are not idiomatic on Android — a flat list with
    // section headers reads better on a touch screen and avoids the awkward
    // two-layer overlay pattern.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // ── Stack section ─────────────────────────────────────────────
        // Only shown when relevant stacking actions are available. Mirrors the
        // desktop and iOS context-menu stack section: per-card operations
        // (Remove, Unstack, Promote) appear when the pressed card is in a
        // stack; Combine appears when 2+ cards are selected.
        val hasStackActions = (video.isInGroup && (onRemoveFromStack != null || onUnstack != null)) ||
            (video.isInGroup && video.id != video.groupPreferredId && onSetStackMaster != null) ||
            (multiSelectCount >= 2 && onCombineIntoStack != null)
        if (hasStackActions) {
            Text(
                text = stringResource(R.string.stack_section_header).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
            if (video.isInGroup && onRemoveFromStack != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.stack_remove_from_stack)) },
                    leadingIcon = { Icon(Icons.Default.Deselect, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        onRemoveFromStack(video.id, video.groupId)
                        onDismiss()
                    },
                )
            }
            if (video.isInGroup && onUnstack != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.stack_unstack)) },
                    leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        onUnstack(video.groupId)
                        onDismiss()
                    },
                )
            }
            if (video.isInGroup && video.id != video.groupPreferredId && onSetStackMaster != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.stack_promote_to_leader)) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        onSetStackMaster(video.id, video.groupId)
                        onDismiss()
                    },
                )
            }
            if (multiSelectCount >= 2 && onCombineIntoStack != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.stack_combine_into_stack)) },
                    leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        onCombineIntoStack()
                        onDismiss()
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        // ── Rating section header ─────────────────────────────────────
        Text(
            text = stringResource(R.string.card_menu_rating).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
        )
        // 5 down to 0 — descending order (highest at top) mirrors the iOS
        // Picker and keeps the most-useful values closest to the long-press.
        for (n in 5 downTo 0) {
            val isCurrentRating = video.rating == n
            DropdownMenuItem(
                text = {
                    Text(
                        if (n == 0) stringResource(R.string.card_menu_rating_none)
                        else "★".repeat(n)
                    )
                },
                leadingIcon = if (isCurrentRating) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                onClick = {
                    // Tap current rating to clear it (Lightroom toggle semantics).
                    onSetRating(if (isCurrentRating) 0 else n)
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Color label section header ────────────────────────────────
        Text(
            text = stringResource(R.string.card_menu_color_label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
        )
        for (label in ColorLabel.values()) {
            val isCurrent = colorLabelEnum == label
            DropdownMenuItem(
                text = {
                    Text(
                        when (label) {
                            ColorLabel.None   -> stringResource(R.string.card_menu_label_none)
                            ColorLabel.Red    -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_red)}"
                            ColorLabel.Yellow -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_yellow)}"
                            ColorLabel.Green  -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_green)}"
                            ColorLabel.Blue   -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_blue)}"
                            ColorLabel.Purple -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_purple)}"
                        }
                    )
                },
                leadingIcon = if (isCurrent) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                onClick = { onSetColorLabel(label.raw) },
            )
        }
    }
}

// ── Card stats configuration sheet ───────────────────────────────────────────
//
// A bottom sheet (ModalBottomSheet) that lets the user pick which GridStatKey
// shows in each of the four top-of-card slots. Writes catalog-wide — changes
// show immediately on every card. Mirrors iOS TopSlotsConfigSheet and the
// desktop StatCell right-click menu.

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TopSlotsConfigSheet(
    topSlots: List<String>,
    onUpdateSlot: (Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val paddedSlots = remember(topSlots) {
        val s = topSlots.toMutableList()
        while (s.size < 4) s.add("")
        s.take(4)
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.card_stats_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.card_stats_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (i in 0..3) {
                SlotPickerRow(
                    slotNumber = i + 1,
                    currentKey = paddedSlots[i],
                    onPick = { newKey -> onUpdateSlot(i, newKey) },
                )
            }
        }
    }
}

@Composable
private fun SlotPickerRow(
    slotNumber: Int,
    currentKey: String,
    onPick: (String) -> Unit,
) {
    val currentStat = GridStatKey.fromRaw(currentKey)
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.card_stats_slot_label, slotNumber),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    text = currentStat.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GridStatKey.values().forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (choice == currentStat) "✓ ${choice.displayName}"
                                else choice.displayName
                            )
                        },
                        onClick = {
                            onPick(choice.raw)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

// ── Grid density bottom sheet ─────────────────────────────────────────────────
//
// A ModalBottomSheet with a horizontal slider (1..5) and small/large photo
// icons at either end — matching the iOS LibraryTopBar thumbnailSlider and the
// desktop BottomBar thumbnail-size slider aesthetics.

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GridDensitySheet(
    density: Int,
    onDensityChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.grid_density_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.grid_density_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoSizeSelectSmall,
                    contentDescription = stringResource(R.string.grid_density_smaller),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = density.toFloat(),
                    onValueChange = { onDensityChange(it.toInt().coerceIn(1, 5)) },
                    valueRange = 1f..5f,
                    steps = 3,  // 5 positions → 3 intermediate steps
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.PhotoSizeSelectLarge,
                    contentDescription = stringResource(R.string.grid_density_larger),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Unicode circle used as a per-item colour dot in the colour-label menu.
 *  Material3 doesn't support per-item icon tinting, so a unicode emoji
 *  is the cleanest cross-version option — matches the iOS VideoCardMenu. */
private fun colorDot(label: ColorLabel): String = when (label) {
    ColorLabel.None   -> "⚪"
    ColorLabel.Red    -> "🔴"
    ColorLabel.Yellow -> "🟡"
    ColorLabel.Green  -> "🟢"
    ColorLabel.Blue   -> "🔵"
    ColorLabel.Purple -> "🟣"
}

// ── Shared card status badges (keyword / proxy / full-resolution / audio) ────

@Composable
private fun AndroidCardStatusBadges(
    video: VideoSummary,
    modifier: Modifier = Modifier,
) {
    if (video.tags.isEmpty() && !video.hasProxies && !video.hasAudio) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (video.tags.isNotEmpty()) {
            SmallBadge(icon = Icons.Default.Sell, tint = Color(0xFF64B5F6))
        }
        if (video.hasProxies) {
            SmallBadge(icon = Icons.Default.FilterNone, tint = Color.White)
        }
        if (video.hasAudio) {
            SmallBadge(icon = Icons.Default.GraphicEq, tint = Color.White)
        }
    }
}

@Composable
private fun SmallBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = tint,
        )
    }
}

// ── Bottom status bar ─────────────────────────────────────────────────────────

@Composable
private fun LibraryStatusBar(
    totalCount: Long,
    hasMore: Boolean,
    viewMode: String,
    filterTagId: String,
    selectedCollectionId: String?,
    filterLocationLabel: String?,
    onClearGeoFilter: () -> Unit,
    tags: List<Tag>,
    collections: List<Collection>,
) {
    val activeTag = tags.firstOrNull { it.id == filterTagId }
    val activeCollection = collections.firstOrNull { it.id == selectedCollectionId }

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Video count
            val countLabel = if (totalCount == 0L) {
                stringResource(R.string.grid_no_videos)
            } else {
                val countInt = totalCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                pluralStringResource(R.plurals.grid_video_count, countInt, countInt)
            }
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Active filter pills (geo-location + tag/collection).
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Geo-location filter (set by tapping a cluster on the map).
                // Tapping the pill clears it and shows all videos again.
                if (filterLocationLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable { onClearGeoFilter() },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = filterLocationLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.grid_clear_location_filter),
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                val filterLabel = when {
                    activeTag != null -> activeTag.name
                    activeCollection != null -> activeCollection.name
                    else -> null
                }
                if (filterLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = filterLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // View mode indicator
            Icon(
                imageVector = if (viewMode == "grid") Icons.Default.GridView else Icons.Default.ViewList,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Post-index pipeline progress bar ─────────────────────────────────────────

@Composable
private fun PostIndexProgressBar(pip: PostIndexProgress) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = pip.phase.ifEmpty { stringResource(R.string.grid_processing) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.grid_percent, pip.percent.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            if (pip.percent > 0) {
                LinearProgressIndicator(
                    progress = { (pip.percent / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyLibraryMessage(
    searchQuery: String,
    filterTagId: String,
    selectedCollectionId: String?,
    selectedCollectionIsSmart: Boolean = false,
    hasActiveFilter: Boolean,
    onResetFilter: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when {
                    searchQuery.isNotEmpty() -> Icons.Default.SearchOff
                    filterTagId.isNotEmpty() -> Icons.Default.Sell
                    selectedCollectionId != null -> Icons.Default.Collections
                    else -> Icons.Default.VideoLibrary
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = when {
                    searchQuery.isNotEmpty() -> stringResource(R.string.grid_no_videos_match_search, searchQuery)
                    filterTagId.isNotEmpty() -> stringResource(R.string.grid_no_videos_with_keyword)
                    selectedCollectionId != null && selectedCollectionIsSmart ->
                        stringResource(R.string.grid_smart_collection_empty)
                    selectedCollectionId != null -> stringResource(R.string.grid_collection_empty)
                    else -> stringResource(R.string.grid_no_videos_in_library)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searchQuery.isEmpty() && filterTagId.isEmpty() &&
                selectedCollectionId == null && !hasActiveFilter
            ) {
                Text(
                    text = stringResource(R.string.grid_empty_add_location_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            // When a filter is hiding everything, offer a one-tap reset.
            if (hasActiveFilter) {
                Text(
                    text = stringResource(R.string.grid_empty_no_match_filter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Button(onClick = onResetFilter) {
                    Text(stringResource(R.string.grid_reset_filter))
                }
            }
        }
    }
}

// ── Batch organize sheet ─────────────────────────────────────────────────────
//
// Full-screen bottom sheet shown when the user taps the "Organize" action button
// in multi-select mode. Mirrors iOS BatchOrganizeSheet and the macOS multi-select
// context menu: rating, color label, keyword, and collection sections.
//
// Each action either:
//   • fires immediately and closes the sheet (rating, color label, collection),
//   • or stays open to allow multiple keywords to be added in sequence (keyword).

@Composable
private fun BatchOrganizeSheet(
    videoIds: List<String>,
    tags: List<Tag>,
    collections: List<Collection>,
    onDismiss: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (String) -> Unit,
    onApplyKeyword: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
) {
    val count = videoIds.size
    val manualCollections = remember(collections) { collections.filter { !it.isSmart } }
    var newKeyword by remember { mutableStateOf("") }

    // Use ModalBottomSheet if a sheet host is available; fall back to an AlertDialog
    // on smaller devices or when the Sheet API is unavailable. We use AlertDialog
    // (always works) wrapped in a scrollable Column to keep the implementation simple
    // and avoid needing a BottomSheetScaffold that would complicate the parent layout.
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
        title = {
            Text(
                pluralStringResource(R.plurals.batch_organize_title, count, count),
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // ── Rating ─────────────────────────────────────────────
                item {
                    Text(
                        text = stringResource(R.string.card_menu_rating).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                item {
                    // Inline star row: tap to set rating (0 = clear)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // No-rating chip
                        FilterChip(
                            selected = false,
                            onClick = { onSetRating(0) },
                            label = { Text(stringResource(R.string.card_menu_rating_none)) },
                        )
                        for (n in 1..5) {
                            FilterChip(
                                selected = false,
                                onClick = { onSetRating(n) },
                                label = { Text("★".repeat(n)) },
                            )
                        }
                    }
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

                // ── Color label ────────────────────────────────────────
                item {
                    Text(
                        text = stringResource(R.string.card_menu_color_label).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                items(ColorLabel.values()) { label ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (label) {
                                    ColorLabel.None   -> stringResource(R.string.card_menu_label_none)
                                    ColorLabel.Red    -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_red)}"
                                    ColorLabel.Yellow -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_yellow)}"
                                    ColorLabel.Green  -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_green)}"
                                    ColorLabel.Blue   -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_blue)}"
                                    ColorLabel.Purple -> "${colorDot(label)} ${stringResource(R.string.card_menu_label_purple)}"
                                }
                            )
                        },
                        onClick = { onSetColorLabel(label.raw) },
                    )
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

                // ── Keywords ───────────────────────────────────────────
                item {
                    Text(
                        text = stringResource(R.string.grid_keywords).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                item {
                    // Text field to type a new keyword + Add button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newKeyword,
                            onValueChange = { newKeyword = it },
                            placeholder = { Text(stringResource(R.string.batch_add_keyword_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val k = newKeyword.trim()
                                if (k.isNotEmpty()) {
                                    onApplyKeyword(k)
                                    newKeyword = ""
                                }
                            }),
                        )
                        TextButton(
                            onClick = {
                                val k = newKeyword.trim()
                                if (k.isNotEmpty()) {
                                    onApplyKeyword(k)
                                    newKeyword = ""
                                }
                            },
                            enabled = newKeyword.trim().isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.detail_tag_picker_add_button))
                        }
                    }
                }
                // Existing keyword chips — tap to apply to all selected
                if (tags.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.batch_existing_keywords),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(tags) { tag ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Sell,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(tag.name)
                                }
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.batch_apply_keyword, tag.name),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = { onApplyKeyword(tag.name) },
                        )
                    }
                }

                // ── Collections ────────────────────────────────────────
                if (manualCollections.isNotEmpty()) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    item {
                        Text(
                            text = stringResource(R.string.batch_add_to_collection).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(manualCollections) { col ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Collections,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(col.name)
                                }
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.batch_add_to_collection_name, col.name),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = { onAddToCollection(col.id) },
                        )
                    }
                }
            }
        },
    )
}

// ── Stack members sheet ───────────────────────────────────────────────────────
//
// Shown when the user taps the stack-count badge on a grid card. Fetches the
// full member list for the group, then lists each member with:
//   • A thumbnail (ThumbnailImage)
//   • The filename
//   • A "Leader" chip when this member is the preferred representative
//   • A "Promote" button (shown for non-preferred members)
//   • A "Remove" button to extract this member from the stack
//
// Mirrors the desktop inspector's "Versions" section and the iOS detail view's
// stack panel, adapted for Android's ModalBottomSheet affordance.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StackMembersSheet(
    groupId: String,
    vm: GridViewModel,
    repository: VideoRepository,
    onDismiss: () -> Unit,
) {
    // Members are loaded asynchronously from the repository.
    var members by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var preferredId by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(groupId) {
        isLoading = true
        val (m, pId) = vm.loadGroupMembers(groupId)
        members = m
        preferredId = pId
        isLoading = false
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.stack_members_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(members, key = { it.id }) { member ->
                        val isPreferred = member.id == preferredId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF474747)),
                            ) {
                                ThumbnailImage(
                                    videoId = member.id,
                                    repository = repository,
                                    size = "small",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // Filename + Leader chip
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = member.filename,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isPreferred) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.stack_members_preferred_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            // Actions: Promote (non-preferred only) + Remove
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!isPreferred) {
                                    TextButton(
                                        onClick = {
                                            vm.setStackMaster(member.id, groupId)
                                            preferredId = member.id  // Optimistic
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.stack_members_promote_action),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        vm.removeFromStack(member.id, groupId)
                                        val remaining = members.filter { it.id != member.id }
                                        members = remaining
                                        if (remaining.size <= 1) {
                                            onDismiss()
                                        } else if (member.id == preferredId) {
                                            preferredId = remaining.first().id
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.stack_members_remove_action),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}
