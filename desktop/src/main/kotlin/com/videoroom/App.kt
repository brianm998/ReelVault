// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.videoroom

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.videoroom.data.models.CatalogInfo
import com.videoroom.data.repository.VideoRepository
import com.videoroom.data.server.RecentCatalogs
import com.videoroom.data.server.ServerLauncher
import com.videoroom.ui.screens.GridScreen
import com.videoroom.ui.screens.DetailScreen
import com.videoroom.ui.screens.OpenCatalogDialog
import com.videoroom.ui.theme.VideoRoomTheme
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.GridViewModel
import com.videoroom.viewmodel.DetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VideoRoom")

// Window-level shift key tracking. Updated by the Window's key listener and
// read at click time by VideoCard / GridScreen.
val LocalShiftPressed = compositionLocalOf { false }

fun main() = application {
    // Set the JVM-wide HTTP User-Agent before any networking happens. The
    // OpenStreetMap tile server (used by the map views) blocks Java's
    // default `Java/<version>` UA, which is why JXMapViewer renders blank
    // until this is set. Must happen before the first URLConnection;
    // setting it here at the top of main() is the safest spot.
    if (System.getProperty("http.agent").isNullOrEmpty()) {
        System.setProperty(
            "http.agent",
            "VideoRoom/0.1 (+https://github.com/videoroom/videoroom)"
        )
    }

    val windowState = rememberWindowState(
        size = DpSize(width = 1400.dp, height = 900.dp)
    )

    var shiftPressed by remember { mutableStateOf(false) }
    // VideoRoomApp registers its "group selected" action here, so the Window-
    // level key listener can invoke it on Cmd/Ctrl+G regardless of focus.
    val groupSelectedAction = remember { mutableStateOf<() -> Unit>({}) }
    // Same pattern for the Tab key panel-toggle.
    val togglePanelsAction = remember { mutableStateOf<() -> Unit>({}) }
    // …and for Cmd/Ctrl+A — "select all currently-visible videos".
    val selectAllAction = remember { mutableStateOf<() -> Unit>({}) }
    // …and for Cmd/Ctrl+D — "deselect everything".
    val deselectAllAction = remember { mutableStateOf<() -> Unit>({}) }
    // Title reflects the currently-open catalog (lifted here so Window.title
    // recomposes when the catalog changes).
    var currentCatalog by remember { mutableStateOf(CatalogInfo.Closed) }
    val windowTitle = if (currentCatalog.isOpen) {
        "VideoRoom — ${currentCatalog.name}"
    } else {
        "VideoRoom"
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = windowTitle,
        icon = null, // TODO: Add app icon
        // onPreviewKeyEvent fires BEFORE focused widgets consume the event,
        // so it works even when the search TextField is focused.
        onPreviewKeyEvent = { event ->
            // Track shift state for the rest of the UI.
            if (event.key == Key.ShiftLeft || event.key == Key.ShiftRight) {
                shiftPressed = event.type == KeyEventType.KeyDown
            }
            // Cmd+G (macOS) / Ctrl+G (Windows/Linux) → group selected videos.
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.G &&
                (event.isMetaPressed || event.isCtrlPressed)
            ) {
                groupSelectedAction.value()
                return@Window true // consume so default shortcuts don't also fire
            }
            // Tab → toggle both side panels (Lightroom-style).
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.Tab &&
                !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed
            ) {
                togglePanelsAction.value()
                return@Window true // consume so focus traversal doesn't also fire
            }
            false
        },
        // onKeyEvent fires AFTER focused widgets — so a focused TextField
        // (search bar, notes, keyword input) can still handle Cmd/Ctrl+A as
        // "select all text"; we only catch it when nothing else does.
        onKeyEvent = { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.A &&
                    (event.isMetaPressed || event.isCtrlPressed) &&
                    !event.isShiftPressed && !event.isAltPressed -> {
                    selectAllAction.value()
                    true
                }
                // Cmd/Ctrl+D — deselect every selected video. Same
                // post-process placement as Cmd+A so a focused TextField
                // gets first crack (it doesn't actually use Cmd+D, but the
                // policy is "Window-level shortcuts never steal from a
                // focused widget").
                event.key == Key.D &&
                    (event.isMetaPressed || event.isCtrlPressed) &&
                    !event.isShiftPressed && !event.isAltPressed -> {
                    deselectAllAction.value()
                    true
                }
                else -> false
            }
        }
    ) {
        CompositionLocalProvider(LocalShiftPressed provides shiftPressed) {
            VideoRoomApp(
                onRegisterGroupAction = { groupSelectedAction.value = it },
                onRegisterTogglePanelsAction = { togglePanelsAction.value = it },
                onRegisterSelectAllAction = { selectAllAction.value = it },
                onRegisterDeselectAllAction = { deselectAllAction.value = it },
                onCatalogChanged = { currentCatalog = it }
            )
        }
    }
}

@Composable
fun VideoRoomApp(
    /** Called once to register the "group selected" action for the Cmd/Ctrl+G shortcut. */
    onRegisterGroupAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "toggle panels" action for the Tab shortcut. */
    onRegisterTogglePanelsAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "select all visible" action for the
     *  Cmd/Ctrl+A shortcut. */
    onRegisterSelectAllAction: (() -> Unit) -> Unit = {},
    /** Called once to register the "deselect everything" action for the
     *  Cmd/Ctrl+D shortcut. */
    onRegisterDeselectAllAction: (() -> Unit) -> Unit = {},
    /** Notified whenever the open-catalog state changes, so the parent can
     *  update the Window title. */
    onCatalogChanged: (CatalogInfo) -> Unit = {}
) {
    var isDarkTheme by remember { mutableStateOf(true) }
    val repository = remember { VideoRepository.getInstance() }
    val gridViewModel = remember { GridViewModel(repository) }
    val detailViewModel = remember { DetailViewModel(repository) }
    val launcher = remember { ServerLauncher() }
    val recents = remember { RecentCatalogs.Default }

    // Side panel expansion state. Tab toggles both at once (Lightroom-style),
    // and each panel also has its own chevron to collapse/expand individually.
    var leftPanelExpanded by remember { mutableStateOf(true) }
    var rightPanelExpanded by remember { mutableStateOf(true) }

    // Thumbnail size controls the minimum column width for the adaptive grid.
    // Smaller value → more columns when there's space; larger → fewer, bigger cards.
    var thumbnailWidth by remember { mutableStateOf(220.dp) }

    // External editors preferences dialog visibility.
    var showEditorsDialog by remember { mutableStateOf(false) }
    // Global-map dialog visibility.
    var showGlobalMap by remember { mutableStateOf(false) }
    // Location-picker state. `videoIdsForLocationPicker` non-null means the
    // dialog is open and operates on that set of video ids.
    var videoIdsForLocationPicker by remember { mutableStateOf<List<String>?>(null) }
    var initialLocationForPicker by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Capture-date picker state — same pattern as the location picker.
    var videoIdsForDatePicker by remember { mutableStateOf<List<String>?>(null) }
    var initialTimestampForPicker by remember { mutableStateOf<Long?>(null) }

    // OpenCatalog dialog state. Shown automatically when the daemon has no
    // catalog open, or when the user picks File → Open.
    var showOpenCatalogDialog by remember { mutableStateOf(false) }
    var openDialogIsStartup by remember { mutableStateOf(false) }

    // Currently-open catalog from the server's perspective. Drives the title
    // and the File menu's enable state.
    var currentCatalog by remember { mutableStateOf(CatalogInfo.Closed) }
    // Propagate changes up so the Window title can recompose.
    LaunchedEffect(currentCatalog) { onCatalogChanged(currentCatalog) }

    // Register the keyboard shortcut handlers with the Window-level key listener.
    LaunchedEffect(gridViewModel) {
        onRegisterGroupAction { gridViewModel.groupSelectedVideos() }
        onRegisterSelectAllAction { gridViewModel.selectAllVisible() }
        onRegisterDeselectAllAction { gridViewModel.clearSelection() }
    }
    LaunchedEffect(Unit) {
        onRegisterTogglePanelsAction {
            // If either is open, close both. If both are closed, open both.
            val anyOpen = leftPanelExpanded || rightPanelExpanded
            leftPanelExpanded = !anyOpen
            rightPanelExpanded = !anyOpen
        }
    }

    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.Connecting) }
    var errorMessage by remember { mutableStateOf("") }

    /** Load library data after a successful catalog open. */
    fun loadAfterCatalogOpened() {
        gridViewModel.loadVideos()
        gridViewModel.loadLibraryLocations()
        gridViewModel.loadTags()
        gridViewModel.loadFilterOptions()
        // Pre-load both location-related data sources so the global-map
        // and location-picker dialogs can open with the camera framed on
        // real data, not the global-view fallback.
        gridViewModel.loadVideoLocations()
        gridViewModel.loadNamedLocations()
    }

    /** Tell the daemon to switch to [path], persist it as a recent, refresh. */
    fun openCatalog(path: String) {
        scope.launch {
            val info = repository.openCatalog(path)
            if (info != null && info.isOpen) {
                recents.touch(info.path)
                currentCatalog = info
                showOpenCatalogDialog = false
                loadAfterCatalogOpened()
            } else {
                errorMessage = "Could not open catalog at $path"
                // Re-open the dialog so the user can pick again.
                showOpenCatalogDialog = true
            }
        }
    }

    /** Close the current catalog and prompt the user to open another. */
    fun closeCatalog() {
        scope.launch {
            repository.closeCatalog()
            currentCatalog = CatalogInfo.Closed
            gridViewModel.clearState()
            openDialogIsStartup = false
            showOpenCatalogDialog = true
        }
    }

    /**
     * Try to connect to the backend. The flow:
     *   1. If a daemon is already listening on the default port, reuse it.
     *   2. Otherwise spawn one with no catalog mounted yet.
     *   3. Then ask the daemon what catalog it has open. If none, prompt.
     *   4. If the daemon's existing catalog matches our recents-head, just go.
     */
    fun attemptConnect() {
        scope.launch {
            connectionState = ConnectionState.Connecting

            // Step 1: probe the default port.
            val defaultPort = 50051
            val reachable = withContext(Dispatchers.IO) {
                launcher.isReachable("127.0.0.1", defaultPort)
            }

            // Step 2: if not reachable, spawn our own daemon.
            val port = if (reachable) {
                defaultPort
            } else {
                val listening = withContext(Dispatchers.IO) {
                    launcher.launch(preferredPort = defaultPort, dbPath = null)
                }
                if (listening == null) {
                    errorMessage = "Couldn't start the VideoRoom backend. " +
                        "Set VIDEOROOM_CORE_BIN or build core with `cargo build`."
                    connectionState = ConnectionState.Failed
                    return@launch
                }
                listening.port
            }

            val ok = repository.connect(overridePort = port)
            if (!ok) {
                errorMessage = "Connected to port $port but the daemon didn't respond"
                connectionState = ConnectionState.Failed
                return@launch
            }
            connectionState = ConnectionState.Connected

            // Step 3: discover what catalog (if any) the daemon already has open.
            val existing = repository.getCurrentCatalog()
            if (existing.isOpen) {
                currentCatalog = existing
                recents.touch(existing.path)
                loadAfterCatalogOpened()
            } else {
                // Step 4: nothing open — pick one. Use the most-recent if it
                // still exists for one-click "resume", otherwise prompt.
                val head = recents.list().firstOrNull { java.io.File(it).exists() }
                if (head != null) {
                    openCatalog(head)
                } else {
                    openDialogIsStartup = true
                    showOpenCatalogDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) { attemptConnect() }

    DisposableEffect(Unit) {
        onDispose {
            gridViewModel.onDestroy()
            detailViewModel.onDestroy()
            repository.disconnect()
            // Shut down any daemon we spawned ourselves.
            launcher.shutdown()
        }
    }

    VideoRoomTheme(darkTheme = isDarkTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (connectionState == ConnectionState.Connected) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    val currentSort = gridViewModel.currentSortField.collectAsState()
                    val sortAsc = gridViewModel.currentSortAscending.collectAsState()
                    val selectedIds = gridViewModel.selectedVideoIds.collectAsState()
                    VideoRoomTopBar(
                        gridViewModel = gridViewModel,
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { isDarkTheme = !isDarkTheme },
                        onSearch = { gridViewModel.setSearchQuery(it) },
                        onAddLibrary = { path, recursive, autoGroup ->
                            gridViewModel.addLibraryAndScan(path, recursive, autoGroup)
                        },
                        onGroupSelected = { gridViewModel.groupSelectedVideos() },
                        onConfigureEditors = { showEditorsDialog = true },
                        onOpenCatalog = {
                            openDialogIsStartup = false
                            showOpenCatalogDialog = true
                        },
                        onCloseCatalog = { closeCatalog() },
                        onOpenRecent = { path -> openCatalog(path) },
                        catalogIsOpen = currentCatalog.isOpen,
                        catalogName = currentCatalog.name,
                        recents = recents.list(),
                        onShowGlobalMap = {
                            // Await the loads before opening the dialog so
                            // the map frames the centroid of real data
                            // instead of (51.4769, 0) — Europe — when the
                            // catalog's locations haven't arrived yet.
                            // Pre-load on catalog open keeps this fast in
                            // steady state.
                            scope.launch {
                                gridViewModel.loadVideoLocationsAsync()
                                gridViewModel.loadNamedLocationsAsync()
                                showGlobalMap = true
                            }
                        },
                        selectedCount = selectedIds.value.size,
                        currentSort = currentSort.value,
                        sortAscending = sortAsc.value,
                        onSortChange = { field, ascending -> gridViewModel.setSort(field, ascending) }
                    )

                    // Scan status banner (during scan)
                    val scanStatus = gridViewModel.scanStatus.collectAsState()
                    if (scanStatus.value != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(VideoRoomSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                Text(
                                    text = scanStatus.value ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Scan result banner (after scan)
                    val scanResult = gridViewModel.scanResult.collectAsState()
                    scanResult.value?.let { result ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (result.success) {
                                if (result.videosFound == 0) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(VideoRoomSpacing.Small),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when {
                                            !result.success -> Icons.Default.Error
                                            result.videosFound == 0 -> Icons.Default.Warning
                                            else -> Icons.Default.CheckCircle
                                        },
                                        contentDescription = "Status",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (result.success) {
                                            if (result.videosFound == 0) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                    Text(
                                        text = result.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (result.success) {
                                            if (result.videosFound == 0) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                }
                                com.videoroom.ui.components.Tooltip(text = "Dismiss this notification") {
                                    IconButton(
                                        onClick = { gridViewModel.clearScanResult() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Active location-filter banner — sits above the
                    // panels so users always notice why the grid is
                    // narrowed, and can clear the filter in one click.
                    val activeFilter = gridViewModel.filterLocation.collectAsState().value
                    val totalCountForBanner = gridViewModel.totalCount.collectAsState().value
                    val namedLocsForBanner = gridViewModel.namedLocations.collectAsState().value
                    if (activeFilter != null) {
                        val (fLat, fLon, fRadius) = activeFilter
                        val matched = remember(fLat, fLon, namedLocsForBanner) {
                            com.videoroom.ui.screens.nearestNamedLocation(
                                fLat, fLon, namedLocsForBanner)
                        }
                        val identity = matched?.name
                            ?: "%.4f, %.4f".format(fLat, fLon)
                        val radiusLabel = if (fRadius == kotlin.math.floor(fRadius))
                            "%.0f".format(fRadius) else "%.1f".format(fRadius)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = VideoRoomSpacing.Medium,
                                             vertical = VideoRoomSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (matched != null) Icons.Default.Place
                                                  else Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Filtered to videos near $identity",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        text = "$totalCountForBanner match" +
                                            (if (totalCountForBanner == 1L) "" else "es") +
                                            " within $radiusLabel km",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                            .copy(alpha = 0.75f),
                                    )
                                }
                                com.videoroom.ui.components.Tooltip(
                                    text = "Clear the location filter and return to the full library."
                                ) {
                                    TextButton(
                                        onClick = {
                                            gridViewModel.setLocationFilter(null, null)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Show all videos",
                                             style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    // Main content
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        val libraryLocations = gridViewModel.libraryLocations.collectAsState()
                        val selectedLocation = gridViewModel.selectedLocationPath.collectAsState()

                        // Library panel — expanded view or collapsed strip
                        if (leftPanelExpanded) {
                            com.videoroom.ui.components.LibraryPanel(
                                locations = libraryLocations.value,
                                selectedPath = selectedLocation.value,
                                totalVideosAcrossLibrary = libraryLocations.value
                                    .sumOf { it.videoCount },
                                onSelect = { path -> gridViewModel.setLocationFilter(path) },
                                onCollapse = { leftPanelExpanded = false },
                                modifier = Modifier
                                    .weight(0.18f)
                                    .fillMaxHeight()
                            )
                        } else {
                            com.videoroom.ui.components.CollapsedPanelStrip(
                                expandIconLeft = false,  // arrow points right (toward expand)
                                tooltip = "Show library panel (Tab)",
                                onClick = { leftPanelExpanded = true },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Grid view (middle) — fills remaining space
                        GridScreen(
                            viewModel = gridViewModel,
                            onVideoSelect = { video ->
                                detailViewModel.setCurrentVideo(video)
                                detailViewModel.loadMetadata(video.id)
                            },
                            thumbnailMinWidth = thumbnailWidth,
                            onConfigureEditors = { showEditorsDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Detail panel — expanded view or collapsed strip
                        if (rightPanelExpanded) {
                            DetailScreen(
                                viewModel = detailViewModel,
                                gridViewModel = gridViewModel,
                                onCollapse = { rightPanelExpanded = false },
                                thumbnailWidth = thumbnailWidth,
                                onThumbnailWidthChange = { thumbnailWidth = it },
                                onEditLocation = { videoIds, initial ->
                                    // Await before showing the dialog so
                                    // its bbox-framing logic captures
                                    // populated arrays. Without this, the
                                    // dialog opens before the gRPC
                                    // location data arrives and the map
                                    // centers on Europe.
                                    scope.launch {
                                        gridViewModel.loadVideoLocationsAsync()
                                        gridViewModel.loadNamedLocationsAsync()
                                        videoIdsForLocationPicker = videoIds
                                        initialLocationForPicker = initial
                                    }
                                },
                                onEditCaptureDate = { videoIds, initialTs ->
                                    videoIdsForDatePicker = videoIds
                                    initialTimestampForPicker = initialTs
                                },
                                modifier = Modifier
                                    .weight(0.18f)
                                    .fillMaxHeight()
                            )
                        } else {
                            com.videoroom.ui.components.CollapsedPanelStrip(
                                expandIconLeft = true,  // arrow points left (toward expand)
                                tooltip = "Show details panel (Tab)",
                                onClick = { rightPanelExpanded = true },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                    }
                }
                // External editors preferences dialog
                if (showEditorsDialog) {
                    com.videoroom.ui.screens.ExternalEditorsDialog(
                        onDismiss = { showEditorsDialog = false }
                    )
                }

                // Global map dialog — shows every geotagged video.
                if (showGlobalMap) {
                    val locs = gridViewModel.videoLocations.collectAsState()
                    com.videoroom.ui.screens.GlobalMapDialog(
                        locations = locs.value,
                        onDismiss = { showGlobalMap = false },
                        onLocationPick = { lat, lon, radius ->
                            gridViewModel.setLocationFilter(lat, lon, radius)
                            showGlobalMap = false
                        }
                    )
                }

                // Location-picker dialog — set/replace GPS on one or more videos.
                videoIdsForLocationPicker?.let { ids ->
                    val knownLocations = gridViewModel.videoLocations.collectAsState()
                    val namedPlaces = gridViewModel.namedLocations.collectAsState()
                    com.videoroom.ui.screens.LocationPickerDialog(
                        targetVideoIds = ids,
                        initialLocation = initialLocationForPicker,
                        existingLocations = knownLocations.value,
                        namedLocations = namedPlaces.value,
                        onDismiss = {
                            videoIdsForLocationPicker = null
                            initialLocationForPicker = null
                        },
                        onApply = { lat, lon, writeToFile, name ->
                            // If the user typed (or kept) a name, upsert it
                            // first so subsequent UI refreshes can resolve
                            // the new GPS into a name immediately. Re-uses
                            // an existing named-location's id when the
                            // candidate is already within range of one.
                            if (name != null) {
                                val existing = com.videoroom.ui.screens.nearestNamedLocation(
                                    lat, lon, namedPlaces.value
                                )
                                gridViewModel.saveNamedLocation(
                                    id = existing?.id ?: "",
                                    name = name,
                                    latitude = lat,
                                    longitude = lon,
                                )
                            }
                            gridViewModel.setVideoLocations(ids, lat, lon, writeToFile) {
                                // Refresh the detail panel's metadata so the
                                // new GPS shows up immediately.
                                ids.firstOrNull()?.let { detailViewModel.loadMetadata(it) }
                            }
                            videoIdsForLocationPicker = null
                            initialLocationForPicker = null
                        }
                    )
                }

                // Capture-date picker — set/replace creation time.
                videoIdsForDatePicker?.let { ids ->
                    com.videoroom.ui.screens.CaptureDateDialog(
                        targetVideoIds = ids,
                        initialTimestampMs = initialTimestampForPicker,
                        onDismiss = {
                            videoIdsForDatePicker = null
                            initialTimestampForPicker = null
                        },
                        onApply = { ts, writeToFile ->
                            gridViewModel.setVideoCaptureDates(ids, ts, writeToFile) {
                                ids.firstOrNull()?.let { detailViewModel.loadMetadata(it) }
                            }
                            videoIdsForDatePicker = null
                            initialTimestampForPicker = null
                        }
                    )
                }

                // Open Catalog dialog — shown automatically when the daemon
                // has no catalog mounted, or when the user picks File → Open.
                if (showOpenCatalogDialog) {
                    OpenCatalogDialog(
                        onDismiss = { showOpenCatalogDialog = false },
                        onPick = { openCatalog(it) },
                        isStartup = openDialogIsStartup,
                        recents = recents,
                    )
                }
            } else if (connectionState == ConnectionState.Connecting) {
                // Friendly loading screen while we attempt to reach the backend.
                ConnectingScreen()
            } else {
                // Connection failed — error screen with retry.
                ConnectionErrorScreen(
                    errorMessage = errorMessage,
                    onRetry = { attemptConnect() }
                )
            }
        }
    }
}

/** Three-state connection lifecycle for the startup flow. */
private enum class ConnectionState { Connecting, Connected, Failed }

@Composable
fun ConnectingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))
            Text(
                text = "Connecting to VideoRoom…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
            Text(
                text = "Reaching out to the backend on localhost:50051",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VideoRoomTopBar(
    gridViewModel: com.videoroom.viewmodel.GridViewModel,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onSearch: (String) -> Unit,
    onAddLibrary: (path: String, recursive: Boolean, autoGroup: Boolean) -> Unit,
    onGroupSelected: () -> Unit = {},
    onConfigureEditors: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onCloseCatalog: () -> Unit = {},
    onOpenRecent: (String) -> Unit = {},
    catalogIsOpen: Boolean = false,
    catalogName: String = "",
    recents: List<String> = emptyList(),
    /** Opens the global map dialog showing every geotagged video. */
    onShowGlobalMap: () -> Unit = {},
    selectedCount: Int = 0,
    currentSort: String = "indexed_at",
    sortAscending: Boolean = false,
    onSortChange: (String, Boolean) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddLibraryDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFileMenu by remember { mutableStateOf(false) }

    val sortOptions = listOf(
        "filename" to "Filename",
        "indexed_at" to "Date Added",
        "creation_date" to "Date Captured",
        "duration" to "Duration",
        "size" to "File Size",
        "resolution" to "Resolution",
        "fps" to "Frame Rate",
        "codec" to "Codec",
        "bitrate" to "Bitrate",
        "camera" to "Camera",
        "lens" to "Lens",
        "keyword" to "Keyword",
    )

    TopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoRoomSpacing.Medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VideoRoom",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))

                // File menu — Open / Close / Open Recent.
                Box {
                    com.videoroom.ui.components.Tooltip(
                        text = "Open a different catalog, close the current one, or pick from " +
                            "recent catalogs."
                    ) {
                        OutlinedButton(
                            onClick = { showFileMenu = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LibraryBooks,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (catalogIsOpen) catalogName else "No catalog",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = showFileMenu, onDismissRequest = { showFileMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open Catalog…") },
                            onClick = { onOpenCatalog(); showFileMenu = false },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Close Catalog") },
                            onClick = { onCloseCatalog(); showFileMenu = false },
                            enabled = catalogIsOpen,
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) }
                        )
                        if (recents.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "Recent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = VideoRoomSpacing.Medium,
                                    vertical = VideoRoomSpacing.XSmall
                                )
                            )
                            recents.take(8).forEach { recent ->
                                val name = java.io.File(recent)
                                    .nameWithoutExtension
                                    .ifEmpty { java.io.File(recent).name }
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(name, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                recent,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    },
                                    onClick = { onOpenRecent(recent); showFileMenu = false }
                                )
                            }
                        }
                    }
                }

                // Search bar - use OutlinedTextField which has a more compact
                // default height that fits inside the TopAppBar without
                // clipping text.
                com.videoroom.ui.components.Tooltip(
                    text = "Search videos by filename, notes, or tag. Matches as you type."
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            onSearch(it)
                        },
                        placeholder = {
                            Text(
                                "Search videos...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.width(300.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    )
                }

                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))

                // Filter dropdowns — only visible fields with data appear.
                FilterDropdowns(gridViewModel)

                Spacer(modifier = Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sort menu
                    Box {
                        com.videoroom.ui.components.Tooltip(
                            text = "Sort the video grid. Click the same field again to reverse direction."
                        ) {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Sort,
                                    contentDescription = "Sort"
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            Text(
                                text = "Sort by",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = VideoRoomSpacing.Medium,
                                    vertical = VideoRoomSpacing.Small
                                )
                            )
                            sortOptions.forEach { (key, label) ->
                                val isSelected = key == currentSort
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = label,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                                Icon(
                                                    imageVector = if (sortAscending) {
                                                        Icons.Default.ArrowUpward
                                                    } else {
                                                        Icons.Default.ArrowDownward
                                                    },
                                                    contentDescription = if (sortAscending) "Ascending" else "Descending",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (isSelected) {
                                            // Toggle direction on second click
                                            onSortChange(key, !sortAscending)
                                        } else {
                                            // Default to descending for most fields, ascending for filename
                                            onSortChange(key, key == "filename" || key == "camera" || key == "codec")
                                        }
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Group Selected button: enabled when 2+ videos are multi-selected.
                    // Shows a small count badge to make the selection visible.
                    Box {
                        val groupTooltip = if (selectedCount >= 2) {
                            "Stack the $selectedCount selected videos into a group (Cmd/Ctrl+G). " +
                                "One representative will be shown in the grid; click the stack badge to expand."
                        } else {
                            "Shift-click or Cmd/Ctrl-click two or more videos in the grid to enable grouping."
                        }
                        com.videoroom.ui.components.Tooltip(text = groupTooltip) {
                            IconButton(
                                onClick = onGroupSelected,
                                enabled = selectedCount >= 2
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = if (selectedCount >= 2) {
                                        "Group $selectedCount selected videos"
                                    } else {
                                        "Shift+click to select videos to group"
                                    },
                                    tint = if (selectedCount >= 2) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    }
                                )
                            }
                        }
                        if (selectedCount > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = selectedCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // External editors preferences
                    com.videoroom.ui.components.Tooltip(
                        text = "Configure which external video editors are available " +
                            "in the right-click \"Open with\" menu. See free/paid status " +
                            "and download links for each supported editor."
                    ) {
                        IconButton(onClick = onConfigureEditors) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "External Editors"
                            )
                        }
                    }

                    // World-map button
                    com.videoroom.ui.components.Tooltip(
                        text = "Show every geotagged video on a world map. " +
                            "Click a pin to filter the grid to videos taken near " +
                            "that location."
                    ) {
                        IconButton(onClick = onShowGlobalMap) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Map view"
                            )
                        }
                    }

                    // (The active location-filter affordance lives in a
                    // full-width banner above the grid — see
                    // `locationFilterBanner` in App.kt — rather than as a
                    // tiny chip up here, so users actually notice why the
                    // grid is narrowed.)

                    // Add Library button
                    com.videoroom.ui.components.Tooltip(
                        text = "Add a folder to your library. VideoRoom will scan it for videos " +
                            "and extract their metadata in the background."
                    ) {
                        IconButton(onClick = { showAddLibraryDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "Add Library Location"
                            )
                        }
                    }

                    // Theme toggle
                    com.videoroom.ui.components.Tooltip(
                        text = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
                    ) {
                        IconButton(onClick = onThemeToggle) {
                            Icon(
                                imageVector = if (isDarkTheme) {
                                    Icons.Default.LightMode
                                } else {
                                    Icons.Default.DarkMode
                                },
                                contentDescription = "Toggle theme"
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )

    if (showAddLibraryDialog) {
        AddLibraryDialog(
            onDismiss = { showAddLibraryDialog = false },
            onConfirm = { path, recursive, autoGroup ->
                onAddLibrary(path, recursive, autoGroup)
                showAddLibraryDialog = false
            }
        )
    }
}

/**
 * Compact row of filter dropdowns shown to the right of the search field.
 * Each dropdown is hidden when no data exists for its column. Selecting any
 * value narrows the grid; "---" clears that filter.
 */
@Composable
fun FilterDropdowns(gridViewModel: com.videoroom.viewmodel.GridViewModel) {
    val options = gridViewModel.filterOptions.collectAsState().value
    val camera = gridViewModel.filterCamera.collectAsState().value
    val lens = gridViewModel.filterLens.collectAsState().value
    val codec = gridViewModel.filterCodec.collectAsState().value
    val year = gridViewModel.filterCaptureYear.collectAsState().value
    val tagId = gridViewModel.filterTagId.collectAsState().value
    val allTags = gridViewModel.tags.collectAsState().value
    val anyFilterActive =
        camera.isNotEmpty() || lens.isNotEmpty() || codec.isNotEmpty() ||
            year != 0 || tagId.isNotEmpty()

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (options.cameras.isNotEmpty()) {
            com.videoroom.ui.components.Tooltip(
                text = "Show only videos captured with this camera model. " +
                    "Pick \"---\" to clear."
            ) {
                FilterDropdown(
                    label = "Camera",
                    values = options.cameras,
                    selected = camera,
                    onSelect = { gridViewModel.setCameraFilter(it) }
                )
            }
            Spacer(modifier = Modifier.width(VideoRoomSpacing.XSmall))
        }
        if (options.lenses.isNotEmpty()) {
            com.videoroom.ui.components.Tooltip(
                text = "Show only videos shot with this lens model. " +
                    "Pick \"---\" to clear."
            ) {
                FilterDropdown(
                    label = "Lens",
                    values = options.lenses,
                    selected = lens,
                    onSelect = { gridViewModel.setLensFilter(it) }
                )
            }
            Spacer(modifier = Modifier.width(VideoRoomSpacing.XSmall))
        }
        if (allTags.isNotEmpty()) {
            com.videoroom.ui.components.Tooltip(
                text = "Show only videos tagged with this keyword. " +
                    "Pick \"---\" to clear."
            ) {
                FilterDropdown(
                    label = "Keyword",
                    // Tags use ID as the "value" but display name; build a map.
                    values = allTags.map { it.name },
                    selected = allTags.firstOrNull { it.id == tagId }?.name ?: "",
                    onSelect = { name ->
                        val matched = allTags.firstOrNull { it.name == name }
                        gridViewModel.setTagFilter(matched?.id ?: "")
                    }
                )
            }
            Spacer(modifier = Modifier.width(VideoRoomSpacing.XSmall))
        }
        if (options.codecs.isNotEmpty()) {
            com.videoroom.ui.components.Tooltip(
                text = "Show only videos using this video codec (e.g. h264, hevc, prores). " +
                    "Pick \"---\" to clear."
            ) {
                FilterDropdown(
                    label = "Codec",
                    values = options.codecs,
                    selected = codec,
                    onSelect = { gridViewModel.setCodecFilter(it) }
                )
            }
            Spacer(modifier = Modifier.width(VideoRoomSpacing.XSmall))
        }
        if (options.captureYears.isNotEmpty()) {
            com.videoroom.ui.components.Tooltip(
                text = "Show only videos whose capture date falls in this year. " +
                    "Pick \"---\" to clear."
            ) {
                FilterDropdown(
                    label = "Year",
                    values = options.captureYears.map { it.toString() },
                    selected = if (year == 0) "" else year.toString(),
                    onSelect = { gridViewModel.setCaptureYearFilter(it.toIntOrNull() ?: 0) }
                )
            }
        }
        if (anyFilterActive) {
            Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
            com.videoroom.ui.components.Tooltip(
                text = "Clear all active filters (camera, lens, keyword, codec, year)."
            ) {
                TextButton(onClick = {
                    gridViewModel.clearAllDropdownFilters()
                    gridViewModel.setTagFilter("")
                }) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * One compact dropdown. The current selection is shown on the button; "---"
 * at the top of the menu clears the filter.
 */
@Composable
fun FilterDropdown(
    label: String,
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val display = if (selected.isEmpty()) "---" else selected

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = display,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("---") },
                onClick = {
                    onSelect("")
                    expanded = false
                }
            )
            HorizontalDivider()
            values.forEach { value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            value,
                            color = if (value == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AddLibraryDialog(
    onDismiss: () -> Unit,
    onConfirm: (path: String, recursive: Boolean, autoGroup: Boolean) -> Unit
) {
    var path by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    var autoGroup by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Library Location") },
        text = {
            Column {
                Text(
                    text = "Enter the full path to a directory containing videos:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                com.videoroom.ui.components.Tooltip(
                    text = "Full filesystem path to the folder containing your videos. " +
                        "Press Tab to complete, type more to narrow the suggestions, or " +
                        "pick a directory from the dropdown."
                ) {
                    com.videoroom.ui.components.PathCompletingTextField(
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/Users/you/Videos",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

                com.videoroom.ui.components.Tooltip(
                    text = "When on, VideoRoom walks into every subdirectory. " +
                        "When off, only files directly inside the chosen folder are indexed."
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = recursive,
                        onCheckedChange = { recursive = it }
                    )
                    Column {
                        Text(
                            text = "Scan subdirectories",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "When off, only video files directly in this folder are indexed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                com.videoroom.ui.components.Tooltip(
                    text = "When on, videos that share a base filename, duration, and frame rate " +
                        "are automatically stacked together (e.g. 4K + 1080p exports of the same clip). " +
                        "You can always group/ungroup manually later."
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = autoGroup,
                        onCheckedChange = { autoGroup = it }
                    )
                    Column {
                        Text(
                            text = "Auto-group similar variants",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Stack videos that share a base name, duration, and frame rate (e.g. multiple resolutions of the same source).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }
            }
        },
        confirmButton = {
            com.videoroom.ui.components.Tooltip(
                text = "Save this location and start scanning. Indexing runs in the background — " +
                    "you can keep using VideoRoom while it works."
            ) {
                Button(
                    onClick = { if (path.isNotBlank()) onConfirm(path.trim(), recursive, autoGroup) },
                    enabled = path.isNotBlank()
                ) {
                    Text("Add & Scan")
                }
            }
        },
        dismissButton = {
            com.videoroom.ui.components.Tooltip(text = "Close this dialog without adding the location.") {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ConnectionErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(VideoRoomSpacing.Large)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

            Text(
                text = "Connection Error",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

            Text(
                text = "Make sure the VideoRoom backend is running:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "cd core && cargo run --bin videoroom-core",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(VideoRoomSpacing.Large))

            com.videoroom.ui.components.Tooltip(
                text = "Try connecting to the VideoRoom backend daemon again. " +
                    "Make sure `videoroom-core` is running on localhost:50051."
            ) {
                Button(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                    Text("Retry")
                }
            }
        }
    }
}
