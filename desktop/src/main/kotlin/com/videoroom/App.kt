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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.videoroom.data.models.CatalogInfo
import com.videoroom.data.repository.VideoRepository
import com.videoroom.data.server.RecentCatalogs
import com.videoroom.data.server.ServerLauncher
import com.videoroom.ui.screens.GridScreen
import com.videoroom.ui.screens.DetailScreen
import com.videoroom.ui.screens.DetailViewScreen
import com.videoroom.ui.screens.InfoOverlayState
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

/** Top-level view mode for the central content area. */
enum class ViewMode { GRID, DETAIL }

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

    // Enable Compose Desktop's interop blending so Compose overlays (the
    // detail-view info dialog and bottom control bar) draw cleanly on top of
    // the VLCJ video SwingPanel. Without this, on macOS in particular the
    // Swing surface can z-order above Compose and obscure controls — or, in
    // older Compose builds, suppress its own paint and produce a black box.
    // Must be set before the first Window is created.
    System.setProperty("compose.interop.blending", "true")

    val windowState = rememberWindowState(
        size = DpSize(width = 1400.dp, height = 900.dp)
    )

    var shiftPressed by remember { mutableStateOf(false) }
    // True when the top-bar search field has focus. Used to suppress
    // single-key shortcuts ('g', 'd', 'i') so the user can still type those
    // letters into the search box.
    val searchFocused = remember { mutableStateOf(false) }
    // VideoRoomApp registers its "group selected" action here, so the Window-
    // level key listener can invoke it on Cmd/Ctrl+G regardless of focus.
    val groupSelectedAction = remember { mutableStateOf<() -> Unit>({}) }
    // Same pattern for the Tab key panel-toggle.
    val togglePanelsAction = remember { mutableStateOf<() -> Unit>({}) }
    // …and for Cmd/Ctrl+A — "select all currently-visible videos".
    val selectAllAction = remember { mutableStateOf<() -> Unit>({}) }
    // …and for Cmd/Ctrl+D — "deselect everything".
    val deselectAllAction = remember { mutableStateOf<() -> Unit>({}) }
    // Actions for plain 'g' (grid mode), 'd' (detail mode), 'i' (cycle info
    // overlay). Bound via onPreviewKeyEvent so the search field's plain-key
    // input still works via the `searchFocused` gate above.
    val setGridModeAction = remember { mutableStateOf<() -> Unit>({}) }
    val setDetailModeAction = remember { mutableStateOf<() -> Unit>({}) }
    val cycleInfoOverlayAction = remember { mutableStateOf<() -> Unit>({}) }
    // Space bar: toggle inline playback of the selected video.
    val spacebarAction = remember { mutableStateOf<() -> Unit>({}) }
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
            // Single-letter shortcuts: only when no modifier is held AND the
            // search field isn't focused (so the user can still type 'g', 'd',
            // or 'i' in the search box).
            if (event.type == KeyEventType.KeyDown &&
                !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed &&
                !searchFocused.value
            ) {
                when (event.key) {
                    Key.G -> { setGridModeAction.value(); return@Window true }
                    Key.D -> { setDetailModeAction.value(); return@Window true }
                    Key.I -> { cycleInfoOverlayAction.value(); return@Window true }
                    Key.Spacebar -> { spacebarAction.value(); return@Window true }
                    else -> Unit
                }
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
        // Guarantee a valid Compose focus target at all times.
        //
        // Problem: AWT fires a keyTyped event for every key press, independent
        // of whether the paired keyPressed event was "consumed" by Compose's
        // onPreviewKeyEvent handler. Compose converts keyTyped into an internal
        // KeyDown event and routes it through FocusOwnerImpl.dispatchKeyEvent.
        // When a recomposition is in flight at that instant (common during a
        // library scan, because gRPC progress events arrive on the EDT and
        // trigger rapid UI updates), no composable may hold focus yet.
        // FocusOwnerImpl throws IllegalStateException("Event can't be processed
        // because we do not have an active focus target"), which on JDK 17+
        // propagates all the way up EventDispatchThread.pumpEvents and kills
        // the EDT — crashing the app.
        //
        // Fix: this invisible Box is focusable and claims focus exactly once
        // at window launch. Child composables (search bar, text fields, etc.)
        // can still take focus normally; when they release it the Box holds it
        // again as a neutral fallback. The focus system's invariant is never
        // violated regardless of recomposition timing.
        val rootFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // requestFocus() must run after the first composition pass so the
            // node is actually attached to the owner. LaunchedEffect(Unit)
            // fires after the first frame — exactly the right moment.
            rootFocus.requestFocus()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
        ) {
            CompositionLocalProvider(LocalShiftPressed provides shiftPressed) {
                VideoRoomApp(
                    onRegisterGroupAction = { groupSelectedAction.value = it },
                    onRegisterTogglePanelsAction = { togglePanelsAction.value = it },
                    onRegisterSelectAllAction = { selectAllAction.value = it },
                    onRegisterDeselectAllAction = { deselectAllAction.value = it },
                    onRegisterSetGridMode = { setGridModeAction.value = it },
                    onRegisterSetDetailMode = { setDetailModeAction.value = it },
                    onRegisterCycleInfoOverlay = { cycleInfoOverlayAction.value = it },
                    onRegisterSpacebarAction = { spacebarAction.value = it },
                    onSearchFocusChanged = { searchFocused.value = it },
                    onCatalogChanged = { currentCatalog = it }
                )
            }
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
    /** Called once to register the "switch to grid" action for the 'g' shortcut. */
    onRegisterSetGridMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "switch to detail" action for the 'd' shortcut. */
    onRegisterSetDetailMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "cycle info overlay" action for the 'i' shortcut. */
    onRegisterCycleInfoOverlay: (() -> Unit) -> Unit = {},
    /** Called once to register the space-bar play/pause action. */
    onRegisterSpacebarAction: (() -> Unit) -> Unit = {},
    /** Reports search-field focus state to the Window so it can suppress
     *  single-letter shortcuts while the user is typing. */
    onSearchFocusChanged: (Boolean) -> Unit = {},
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
    // Live-updates / file-watcher settings dialog visibility.
    var showWatchSettingsDialog by remember { mutableStateOf(false) }
    // Inline-playback / proxy-resolution preferences dialog visibility.
    var showPlaybackSettingsDialog by remember { mutableStateOf(false) }
    // Library removal confirmation. Non-null while the "Are you sure?" dialog is shown.
    var pendingRemoveLocation by remember { mutableStateOf<com.videoroom.data.models.LibraryLocation?>(null) }
    // Global-map dialog visibility.
    var showGlobalMap by remember { mutableStateOf(false) }
    // Location-picker state. `videoIdsForLocationPicker` non-null means the
    // dialog is open and operates on that set of video ids.
    var videoIdsForLocationPicker by remember { mutableStateOf<List<String>?>(null) }
    var initialLocationForPicker by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Capture-date picker state — same pattern as the location picker.
    var videoIdsForDatePicker by remember { mutableStateOf<List<String>?>(null) }
    var initialTimestampForPicker by remember { mutableStateOf<Long?>(null) }

    // Add-library dialog visibility. Lifted here so both the top-bar
    // "add folder" button and the sidebar's '+' button can open the same
    // dialog.
    var showAddLibraryDialog by remember { mutableStateOf(false) }

    // Top-level view mode. GRID is the default catalog view; DETAIL is the
    // single-video loupe with in-app playback.
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }

    // Info overlay cycle in detail view ('i' key advances through states).
    var infoOverlay by remember { mutableStateOf(InfoOverlayState.NONE) }

    // Space bar in detail mode: each increment triggers a play/pause inside
    // DetailViewScreen without exposing its internal player state upward.
    var detailPlayToggle by remember { mutableStateOf(0) }

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
        onRegisterSpacebarAction {
            when (viewMode) {
                ViewMode.DETAIL -> {
                    // Delegate to DetailViewScreen via the toggle token.
                    detailPlayToggle++
                }
                ViewMode.GRID -> {
                    val playing = gridViewModel.playingVideoId.value
                    if (playing != null) {
                        // A video is playing — stop it.
                        gridViewModel.stopPlayback()
                    } else {
                        // Start playing the selected video (prefer proxy for oversize).
                        // Only trigger when exactly one video is selected so we don't
                        // accidentally start playback during multi-select.
                        val selectedId = gridViewModel.selectedVideoId.value
                        val selCount = gridViewModel.selectedVideoIds.value.size
                        if (selectedId != null && selCount <= 1) {
                            gridViewModel.playVideoPreferProxy(selectedId)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        onRegisterTogglePanelsAction {
            // If either is open, close both. If both are closed, open both.
            val anyOpen = leftPanelExpanded || rightPanelExpanded
            leftPanelExpanded = !anyOpen
            rightPanelExpanded = !anyOpen
        }
    }
    LaunchedEffect(Unit) {
        onRegisterSetGridMode { viewMode = ViewMode.GRID }
        onRegisterSetDetailMode { viewMode = ViewMode.DETAIL }
        onRegisterCycleInfoOverlay {
            infoOverlay = when (infoOverlay) {
                InfoOverlayState.NONE -> InfoOverlayState.CAMERA
                InfoOverlayState.CAMERA -> InfoOverlayState.FILE
                InfoOverlayState.FILE -> InfoOverlayState.NONE
            }
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
        // Open the long-lived CatalogEvents subscription so the grid
        // refreshes when the server's file-watcher picks up new footage.
        // Cheap if live updates are disabled — the server sends a single
        // WATCHER_DISABLED greeting and idles the stream.
        gridViewModel.startCatalogEventStream()
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
            gridViewModel.stopCatalogEventStream()
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
                        onRequestAddLibrary = { showAddLibraryDialog = true },
                        viewMode = viewMode,
                        onViewModeChange = { viewMode = it },
                        onSearchFocusChanged = onSearchFocusChanged,
                        onGroupSelected = { gridViewModel.groupSelectedVideos() },
                        onConfigureEditors = { showEditorsDialog = true },
                        onConfigureWatcher = { showWatchSettingsDialog = true },
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
                    val watcherBanner = gridViewModel.watcherBanner.collectAsState()
                    val effectiveScanText = scanStatus.value ?: watcherBanner.value
                    if (effectiveScanText != null) {
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
                                    text = effectiveScanText ?: "",
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
                                onAddLocation = { showAddLibraryDialog = true },
                                onRemoveLocation = { loc -> pendingRemoveLocation = loc },
                                onCollapse = { leftPanelExpanded = false },
                                modifier = Modifier
                                    .weight(0.18f)
                                    .fillMaxHeight()
                            )

                            // Remove-library confirmation dialog
                            pendingRemoveLocation?.let { loc ->
                                val videoWord = if (loc.videoCount == 1L) "video" else "videos"
                                AlertDialog(
                                    onDismissRequest = { pendingRemoveLocation = null },
                                    icon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    title = { Text("Remove library location?") },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("\"${loc.path}\"")
                                            Text(
                                                "${loc.videoCount} $videoWord from this folder will be removed " +
                                                "from your catalog. The video files on disk will not be deleted.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                gridViewModel.removeLibraryLocation(loc.path)
                                                pendingRemoveLocation = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) { Text("Remove") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pendingRemoveLocation = null }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }
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

                        // Middle area — grid or single-video loupe.
                        when (viewMode) {
                            ViewMode.GRID -> GridScreen(
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
                            ViewMode.DETAIL -> DetailViewScreen(
                                gridViewModel = gridViewModel,
                                detailViewModel = detailViewModel,
                                infoOverlay = infoOverlay,
                                playToggle = detailPlayToggle,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }

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

                // Live-updates / watcher preferences dialog
                if (showWatchSettingsDialog) {
                    com.videoroom.ui.screens.WatchSettingsDialog(
                        repository = repository,
                        onDismiss = { showWatchSettingsDialog = false }
                    )
                }

                // Playback + proxy resolution preferences dialog
                if (showPlaybackSettingsDialog) {
                    com.videoroom.ui.screens.PlaybackSettingsDialog(
                        repository = repository,
                        onDismiss = { showPlaybackSettingsDialog = false }
                    )
                }

                // Proxy resolution picker. Observes the grid view-model:
                // a non-null `proxyCreationVideoId` means the user just
                // clicked Create proxy on a card. Look up the matching
                // VideoSummary so the dialog can show filename + greys
                // out presets larger than the source.
                val proxyTargetId = gridViewModel.proxyCreationVideoId.collectAsState().value
                val visibleVideos = gridViewModel.videos.collectAsState().value
                if (proxyTargetId != null) {
                    val source = visibleVideos.firstOrNull { it.id == proxyTargetId }
                    if (source != null) {
                        com.videoroom.ui.screens.ProxyResolutionDialog(
                            sourceVideo = source,
                            onConfirm = { h ->
                                gridViewModel.startProxyCreation(proxyTargetId, h)
                            },
                            onCancel = { gridViewModel.cancelProxyCreation() },
                        )
                    } else {
                        // The video disappeared from the visible page
                        // (e.g. user scrolled past it) — just dismiss
                        // the request instead of showing an empty dialog.
                        LaunchedEffect(proxyTargetId) { gridViewModel.cancelProxyCreation() }
                    }
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

                // Add-library dialog — shared by the top-bar "add folder"
                // button and the LIBRARY sidebar's '+' button.
                if (showAddLibraryDialog) {
                    AddLibraryDialog(
                        onDismiss = { showAddLibraryDialog = false },
                        onConfirm = { path, recursive, autoGroup, dateFormat, datePosition ->
                            gridViewModel.addLibraryAndScan(
                                path = path,
                                recursive = recursive,
                                autoGroup = autoGroup,
                                filenameDateFormat = dateFormat,
                                filenameDatePosition = datePosition
                            )
                            showAddLibraryDialog = false
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
    onRequestAddLibrary: () -> Unit,
    onGroupSelected: () -> Unit = {},
    onConfigureEditors: () -> Unit = {},
    /** Opens the watcher (live-updates) preferences dialog. */
    onConfigureWatcher: () -> Unit = {},
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
    onSortChange: (String, Boolean) -> Unit = { _, _ -> },
    viewMode: ViewMode = ViewMode.GRID,
    onViewModeChange: (ViewMode) -> Unit = {},
    onSearchFocusChanged: (Boolean) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
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

                // Grid / Detail view-mode toggle. Mirrors the 'g' and 'd'
                // keyboard shortcuts.
                ViewModeToggle(
                    current = viewMode,
                    onChange = onViewModeChange
                )

                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))

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
                        modifier = Modifier
                            .width(300.dp)
                            .onFocusChanged { onSearchFocusChanged(it.isFocused) },
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

                    // Live-updates pill. Green dot = watcher active; grey
                    // dot = paused. Clicking opens the watch-settings
                    // dialog. Same visual language as the macOS client.
                    val liveOn = gridViewModel.liveUpdatesEnabled.collectAsState().value
                    com.videoroom.ui.components.Tooltip(
                        text = if (liveOn)
                            "Live updates are on — VideoRoom is watching your libraries " +
                                "for new and changed files and will add them automatically. " +
                                "Click to adjust."
                        else
                            "Live updates are off. Click to turn them on or adjust the " +
                                "watcher settings."
                    ) {
                        Surface(
                            onClick = onConfigureWatcher,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier.size(8.dp)
                                ) {
                                    drawCircle(
                                        color = if (liveOn)
                                            androidx.compose.ui.graphics.Color(0xFF34C759)
                                        else
                                            androidx.compose.ui.graphics.Color.Gray
                                    )
                                }
                                Text(
                                    "Live",
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
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
                        IconButton(onClick = onRequestAddLibrary) {
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
}

/**
 * Two-segment Grid / Detail toggle. The active mode is filled; the other is
 * outlined. Mirrors the 'g' (grid) and 'd' (detail) keyboard shortcuts.
 */
@Composable
fun ViewModeToggle(
    current: ViewMode,
    onChange: (ViewMode) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.videoroom.ui.components.Tooltip(text = "Grid view — browse all videos as thumbnails (G)") {
            val isGrid = current == ViewMode.GRID
            if (isGrid) {
                Button(
                    onClick = { onChange(ViewMode.GRID) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Grid", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(
                    onClick = { onChange(ViewMode.GRID) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Grid", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        com.videoroom.ui.components.Tooltip(text = "Detail (loupe) view — play and inspect a single video (D)") {
            val isDetail = current == ViewMode.DETAIL
            if (isDetail) {
                Button(
                    onClick = { onChange(ViewMode.DETAIL) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Detail", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(
                    onClick = { onChange(ViewMode.DETAIL) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Detail", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
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
    /**
     * dateFormat: empty disables the filename-date feature. Non-empty values
     * must be one of "MM-DD-YYYY", "DD-MM-YYYY", or "YYYY-MM-DD".
     * datePosition: "anywhere" | "beginning" | "end" (only consulted when
     * dateFormat is non-empty).
     */
    onConfirm: (
        path: String,
        recursive: Boolean,
        autoGroup: Boolean,
        dateFormat: String,
        datePosition: String
    ) -> Unit
) {
    var path by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    var autoGroup by remember { mutableStateOf(true) }
    var inferDate by remember { mutableStateOf(false) }
    var dateFormat by remember { mutableStateOf("YYYY-MM-DD") }
    var datePosition by remember { mutableStateOf("anywhere") }
    var dateFormatMenu by remember { mutableStateOf(false) }
    var datePositionMenu by remember { mutableStateOf(false) }
    var alwaysApplySettings by remember { mutableStateOf(false) }

    // Pre-populate with saved defaults (if any were saved with "always apply").
    val prefs = remember {
        java.util.prefs.Preferences.userRoot().node("com/videoroom/scanDefaults")
    }
    LaunchedEffect(Unit) {
        if (prefs.getBoolean("hasSavedDefaults", false)) {
            inferDate = prefs.getBoolean("inferDate", false)
            dateFormat = prefs.get("dateFormat", "YYYY-MM-DD")
            datePosition = prefs.get("datePosition", "anywhere")
        }
    }

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

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                // Filename-based capture date inference.
                com.videoroom.ui.components.Tooltip(
                    text = "When on, VideoRoom parses each video's filename for a date and " +
                        "uses it as the capture date if the file itself doesn't already have one. " +
                        "Useful for camera exports whose internal metadata lacks a capture time."
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = inferDate,
                        onCheckedChange = { inferDate = it }
                    )
                    Column {
                        Text(
                            text = "Infer capture date from filename",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Only applied when the file has no capture date in its metadata.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }

                if (inferDate) {
                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))
                    // Date format — full-width selector row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Format selector
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { dateFormatMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "Date format",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = dateFormat,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = dateFormatMenu,
                                onDismissRequest = { dateFormatMenu = false }
                            ) {
                                listOf("MM-DD-YYYY", "DD-MM-YYYY", "YYYY-MM-DD").forEach { fmt ->
                                    DropdownMenuItem(
                                        text = { Text(fmt) },
                                        onClick = { dateFormat = fmt; dateFormatMenu = false }
                                    )
                                }
                            }
                        }

                        // Position selector — larger tap target, clearly labelled
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { datePositionMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "Date position",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = datePosition,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = datePositionMenu,
                                onDismissRequest = { datePositionMenu = false }
                            ) {
                                listOf(
                                    "anywhere" to "Anywhere in filename",
                                    "beginning" to "Beginning of filename",
                                    "end" to "End of filename"
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(label, style = MaterialTheme.typography.bodySmall)
                                                if (value == datePosition) {
                                                    Text(
                                                        "✓",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        },
                                        onClick = { datePosition = value; datePositionMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                    // "Always apply" — persist these date settings as the default
                    // for future imports (both manual and automatic via file watcher).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = alwaysApplySettings,
                            onCheckedChange = { alwaysApplySettings = it }
                        )
                        Column {
                            Text(
                                text = "Always apply these settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pre-fills this dialog and applies the same date rule when the " +
                                    "file watcher auto-indexes new files.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (path.isNotBlank()) {
                        // Persist date-rule defaults when "always apply" is ticked.
                        if (alwaysApplySettings && inferDate) {
                            prefs.putBoolean("hasSavedDefaults", true)
                            prefs.putBoolean("inferDate", true)
                            prefs.put("dateFormat", dateFormat)
                            prefs.put("datePosition", datePosition)
                        } else if (alwaysApplySettings && !inferDate) {
                            // User explicitly said "always apply" with date OFF —
                            // remember that preference too.
                            prefs.putBoolean("hasSavedDefaults", true)
                            prefs.putBoolean("inferDate", false)
                        }
                        onConfirm(
                            path.trim(),
                            recursive,
                            autoGroup,
                            if (inferDate) dateFormat else "",
                            if (inferDate) datePosition else ""
                        )
                    }
                },
                enabled = path.isNotBlank()
            ) {
                Text("Add & Scan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
