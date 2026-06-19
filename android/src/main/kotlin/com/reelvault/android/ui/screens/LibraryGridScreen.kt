// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.reelvault.android.ui.screens

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelvault.android.R
import com.reelvault.android.ui.components.LibraryFilterBar
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
    onOpenLocalMedia: () -> Unit = {},
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

    // ── Screen width for responsive column count ─────────────────────────
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    // In single-pane mode on phones the drawer occupies the full screen; on
    // tablets (>= TABLET_WIDTH_DP) the drawer slides in from the side at 280 dp,
    // so effective grid width shrinks when the drawer is open. We use the
    // device screen width for the column decision — open/closed is handled
    // by Material's DrawerLayout, which reflowstis content automatically.
    val gridColumns = when {
        screenWidthDp >= WIDE_TABLET_WIDTH_DP -> 4
        screenWidthDp >= TABLET_WIDTH_DP -> 3
        else -> 2
    }
    // ── Initial data load + event stream ────────────────────────────────
    LaunchedEffect(Unit) {
        vm.loadVideos()
        vm.loadLibraryLocations()
        vm.loadTags()
        vm.loadCollections()
        vm.startCatalogEventStream()
    }
    DisposableEffect(Unit) {
        onDispose { vm.stopCatalogEventStream() }
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
                onOpenLocalMedia = {
                    scope.launch { drawerState.close() }
                    onOpenLocalMedia()
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
                        onDisconnect = onDisconnect,
                        onClearMultiSelect = { multiSelectedIds = emptySet() },
                        showSortMenu = showSortMenu,
                        onShowSortMenu = { showSortMenu = true },
                        onDismissSortMenu = { showSortMenu = false },
                        onSetSort = { field, asc -> vm.setSort(field, asc) },
                        showOverflowMenu = showOverflowMenu,
                        onShowOverflowMenu = { showOverflowMenu = true },
                        onDismissOverflowMenu = { showOverflowMenu = false },
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
                        if (viewMode == "grid") {
                            GridContent(
                                videos = videos,
                                columns = gridColumns,
                                repository = repository,
                                selectedVideoId = selectedVideoId,
                                multiSelectedIds = multiSelectedIds,
                                hasMore = hasMore,
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
                                    multiSelectedIds = setOf(video.id)
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
                                    multiSelectedIds = setOf(video.id)
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
                            )
                        }
                    }
                }
            }
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
    onOpenDrawer: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onDisconnect: () -> Unit,
    onClearMultiSelect: () -> Unit,
    showSortMenu: Boolean,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSetSort: (String, Boolean) -> Unit,
    showOverflowMenu: Boolean,
    onShowOverflowMenu: () -> Unit,
    onDismissOverflowMenu: () -> Unit,
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
                } else {
                    Text(stringResource(R.string.app_name))
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
            "indexed_at" to stringResource(R.string.sort_date_indexed),
            "creation_date" to stringResource(R.string.sort_date_captured),
            "filename" to stringResource(R.string.sort_filename),
            "size_bytes" to stringResource(R.string.sort_file_size),
            "duration_ms" to stringResource(R.string.sort_duration),
            "rating" to stringResource(R.string.sort_rating),
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
    onOpenLocalMedia: () -> Unit = {},
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
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = { Text(stringResource(R.string.grid_remote_library)) },
                    selected = true,
                    onClick = { onClose() },
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
                        )
                    },
                    label = { Text(stringResource(R.string.grid_local_videos)) },
                    selected = false,
                    onClick = { onClose(); onOpenLocalMedia() },
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
    columns: Int,
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
) {
    val gridState = rememberLazyGridState()
    // Trigger pagination near the end of the list.
    val shouldLoadMore = remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val totalItems = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && totalItems > 0 && lastVisible >= totalItems - columns * 2
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
            columns = GridCells.Fixed(columns),
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
                    onTap = { onTap(video) },
                    onLongPress = { onLongPress(video) },
                    onSetRating = { rating -> onSetRating(video, rating) },
                    onSetColorLabel = { label -> onSetColorLabel(video, label) },
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
// shared GridStatKey slots — matching iOS and the two desktop clients (which
// previously showed four where Android showed only duration + resolution).
@Composable
private fun CardTopStatBand(video: VideoSummary, backgroundColor: Color) {
    val slots = remember {
        val s = defaultGridTopSlots.toMutableList()
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
            CardStatCell(video, slots[0], leading = true, emphasized = true)
            CardStatCell(video, slots[2], leading = false, emphasized = false)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            CardStatCell(video, slots[1], leading = true, emphasized = false)
            CardStatCell(video, slots[3], leading = false, emphasized = false)
        }
    }
}

@Composable
private fun RowScope.CardStatCell(
    video: VideoSummary,
    key: String,
    leading: Boolean,
    emphasized: Boolean,
) {
    Text(
        text = GridStatKey.fromRaw(key).valueFor(video),
        style = if (emphasized) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = if (emphasized) 0.92f else 0.72f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (leading) TextAlign.Start else TextAlign.End,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun AndroidVideoCard(
    video: VideoSummary,
    isSelected: Boolean,
    isMultiSelected: Boolean,
    repository: VideoRepository,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSetRating: (Int) -> Unit,
    onSetColorLabel: (String) -> Unit,
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
            CardTopStatBand(video = video, backgroundColor = topBandColor)

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

                // Multi-select checkbox overlay — top-start corner.
                if (isSelected || isMultiSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
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

        // ── Long-press context menu (rating + color label) ─────────────
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
) {
    val colorLabelEnum = ColorLabel.from(video.colorLabel)

    // Single flat DropdownMenu with two labelled sections.
    // Cascading sub-menus are not idiomatic on Android — a flat list with
    // section headers reads better on a touch screen and avoids the awkward
    // two-layer overlay pattern.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
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
