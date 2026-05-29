// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.videoroom

import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.videoroom.data.ReleaseInfo
import com.videoroom.data.UpdateChecker
import com.videoroom.data.models.CatalogInfo
import com.videoroom.data.repository.VideoRepository
import com.videoroom.data.server.RecentCatalogs
import com.videoroom.data.server.ServerLauncher
import com.videoroom.ui.screens.GridScreen
import com.videoroom.ui.screens.DetailScreen
import com.videoroom.ui.screens.DetailViewScreen
import com.videoroom.ui.screens.ListScreen
import com.videoroom.ui.components.PanelPrefs
import com.videoroom.ui.components.PanelResizeHandle
import com.videoroom.ui.screens.InfoOverlayState
import com.videoroom.ui.screens.OpenCatalogDialog
import com.videoroom.ui.theme.AccentScheme
import com.videoroom.ui.theme.VideoRoomTheme
import com.videoroom.ui.theme.VideoRoomSpacing
import com.videoroom.viewmodel.GridViewModel
import com.videoroom.viewmodel.DetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VideoRoom")

// Window-level shift key tracking. Updated by the Window's key listener and
// read at click time by VideoCard / GridScreen.
val LocalShiftPressed = compositionLocalOf { false }

/**
 * Provides the AWT [java.awt.Window] that hosts this Compose tree.
 * Used by drag-out support ([FileDragSource]) to register a
 * [java.awt.dnd.DragGestureRecognizer] on the underlying rendering component.
 *
 * Provided once by the [Window] scope in [main]; null if this composable is
 * ever rendered outside a real window (previews, tests).
 */
val LocalAppWindow = compositionLocalOf<java.awt.Window?> { null }

/**
 * Tracks whether a [com.videoroom.ui.components.PathCompletingTextField]
 * currently holds keyboard focus. The field sets this on focus-gained and
 * clears it on focus-lost. [Window.onPreviewKeyEvent] reads it so the
 * Tab → toggle-panels shortcut is suppressed while the field is active —
 * Tab should drive bash-style path completion, not collapse the side panels.
 */
val LocalPathFieldFocused = compositionLocalOf { mutableStateOf(false) }

/** Top-level view mode for the central content area. */
enum class ViewMode { GRID, LIST, DETAIL }

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
    // True while a PathCompletingTextField holds focus. Suppresses the
    // Tab → toggle-panels shortcut so Tab drives path completion instead.
    val pathFieldFocused = remember { mutableStateOf(false) }
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
    val setListModeAction = remember { mutableStateOf<() -> Unit>({}) }
    val setDetailModeAction = remember { mutableStateOf<() -> Unit>({}) }
    val cycleInfoOverlayAction = remember { mutableStateOf<() -> Unit>({}) }
    // Space bar: toggle inline playback of the selected video.
    val spacebarAction = remember { mutableStateOf<() -> Unit>({}) }
    // Lightroom-style rating shortcut (digits 0..5). Carries the rating
    // value; the handler applies it to the current selection.
    val setRatingAction = remember { mutableStateOf<(Int) -> Unit>({ _ -> }) }
    // Lightroom-style colour-label shortcut (digits 6..9 + backtick).
    // Carries the colour raw value; "" clears.
    val setColorLabelAction = remember { mutableStateOf<(String) -> Unit>({ _ -> }) }
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
            // Guard: when a PathCompletingTextField is focused, Tab must
            // drive its bash-style path completion — not collapse panels.
            // pathFieldFocused is set/cleared by the field's onFocusChanged.
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.Tab &&
                !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed &&
                !pathFieldFocused.value
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
                    Key.L -> { setListModeAction.value(); return@Window true }
                    Key.D -> { setDetailModeAction.value(); return@Window true }
                    Key.I -> { cycleInfoOverlayAction.value(); return@Window true }
                    Key.Spacebar -> { spacebarAction.value(); return@Window true }
                    // Lightroom-style rating shortcuts (number-row digits).
                    Key.Zero  -> { setRatingAction.value(0); return@Window true }
                    Key.One   -> { setRatingAction.value(1); return@Window true }
                    Key.Two   -> { setRatingAction.value(2); return@Window true }
                    Key.Three -> { setRatingAction.value(3); return@Window true }
                    Key.Four  -> { setRatingAction.value(4); return@Window true }
                    Key.Five  -> { setRatingAction.value(5); return@Window true }
                    // Lightroom-style colour-label shortcuts. Purple has
                    // no shortcut by design (right-click only).
                    Key.Six   -> { setColorLabelAction.value("red");    return@Window true }
                    Key.Seven -> { setColorLabelAction.value("yellow"); return@Window true }
                    Key.Eight -> { setColorLabelAction.value("green");  return@Window true }
                    Key.Nine  -> { setColorLabelAction.value("blue");   return@Window true }
                    // Backtick / grave clears the colour label. Universal
                    // fallback via the right-click "Set Color Label →
                    // None" menu for keyboards where this key is awkward.
                    Key.Grave -> { setColorLabelAction.value("");       return@Window true }
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
            CompositionLocalProvider(
                LocalShiftPressed provides shiftPressed,
                // Expose the AWT window for drag-out support (FileDragSource).
                // `window` is the ComposeWindow (a JFrame) available in
                // FrameWindowScope — the lambda body of Window { ... }.
                LocalAppWindow provides window,
                // Let PathCompletingTextField signal its focus state so
                // onPreviewKeyEvent can suppress Tab → panel-toggle.
                LocalPathFieldFocused provides pathFieldFocused
            ) {
                VideoRoomApp(
                    onRegisterGroupAction = { groupSelectedAction.value = it },
                    onRegisterTogglePanelsAction = { togglePanelsAction.value = it },
                    onRegisterSelectAllAction = { selectAllAction.value = it },
                    onRegisterDeselectAllAction = { deselectAllAction.value = it },
                    onRegisterSetGridMode = { setGridModeAction.value = it },
                    onRegisterSetListMode = { setListModeAction.value = it },
                    onRegisterSetDetailMode = { setDetailModeAction.value = it },
                    onRegisterCycleInfoOverlay = { cycleInfoOverlayAction.value = it },
                    onRegisterSpacebarAction = { spacebarAction.value = it },
                    onRegisterSetRatingAction = { setRatingAction.value = it },
                    onRegisterSetColorLabelAction = { setColorLabelAction.value = it },
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
    /** Called once to register the "switch to list" action for the 'l' shortcut. */
    onRegisterSetListMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "switch to detail" action for the 'd' shortcut. */
    onRegisterSetDetailMode: (() -> Unit) -> Unit = {},
    /** Called once to register the "cycle info overlay" action for the 'i' shortcut. */
    onRegisterCycleInfoOverlay: (() -> Unit) -> Unit = {},
    /** Called once to register the space-bar play/pause action. */
    onRegisterSpacebarAction: (() -> Unit) -> Unit = {},
    /** Called once to register the digit-key rating action (0..5). */
    onRegisterSetRatingAction: ((Int) -> Unit) -> Unit = {},
    /** Called once to register the digit-key colour-label action. Receives
     *  the raw colour string ("" / red / yellow / green / blue). */
    onRegisterSetColorLabelAction: ((String) -> Unit) -> Unit = {},
    /** Reports search-field focus state to the Window so it can suppress
     *  single-letter shortcuts while the user is typing. */
    onSearchFocusChanged: (Boolean) -> Unit = {},
    /** Notified whenever the open-catalog state changes, so the parent can
     *  update the Window title. */
    onCatalogChanged: (CatalogInfo) -> Unit = {}
) {
    val repository = remember { VideoRepository.getInstance() }
    val gridViewModel = remember { GridViewModel(repository) }
    val detailViewModel = remember { DetailViewModel(repository) }
    val launcher = remember { ServerLauncher() }
    val recents = remember { RecentCatalogs.Default }

    // Per-view-mode panel state — width + open/closed flag for each of
    // (Grid, List, Detail). Lightroom keeps these independent across
    // views (Library / Develop / Print), and we apply the same pattern.
    // Defaults are loaded from Java `Preferences` so widths persist
    // across launches; mutations write back via `setLeftPanelWidth`
    // / `setRightPanelWidth` / `setLeftPanelExpanded` etc.
    val leftPanelWidths = remember { mutableStateMapOf<ViewMode, Float>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadWidth(PanelPrefs.Side.LEFT, m) }
    } }
    val rightPanelWidths = remember { mutableStateMapOf<ViewMode, Float>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadWidth(PanelPrefs.Side.RIGHT, m) }
    } }
    val leftPanelExpandeds = remember { mutableStateMapOf<ViewMode, Boolean>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadExpanded(PanelPrefs.Side.LEFT, m) }
    } }
    val rightPanelExpandeds = remember { mutableStateMapOf<ViewMode, Boolean>().also {
        ViewMode.values().forEach { m -> it[m] = PanelPrefs.loadExpanded(PanelPrefs.Side.RIGHT, m) }
    } }
    // Convenience accessors for the active view-mode live further down,
    // after `viewMode` itself is declared — Kotlin can't forward-reference.

    // Accent color scheme — persisted via Java Preferences.
    val uiPrefs = remember { java.util.prefs.Preferences.userRoot().node("com/videoroom/ui") }

    // Thumbnail size controls the minimum column width for the adaptive grid.
    // Smaller value → more columns when there's space; larger → fewer, bigger cards.
    // Persisted to the same uiPrefs node so the user's last size survives restarts.
    var thumbnailWidth by remember {
        mutableStateOf(uiPrefs.getFloat("thumbnailWidth", 220f).dp)
    }
    var accentScheme by remember {
        mutableStateOf(AccentScheme.fromString(uiPrefs.get("accentScheme", null)))
    }
    // Live-updates / file-watcher settings dialog visibility.
    var showWatchSettingsDialog by remember { mutableStateOf(false) }
    // Inline-playback / proxy-resolution preferences dialog visibility.
    var showPlaybackSettingsDialog by remember { mutableStateOf(false) }
    // Appearance (accent color scheme) dialog visibility.
    var showAppearanceDialog by remember { mutableStateOf(false) }
    // Camera-names editor dialog visibility (internal → marketing).
    var showCameraNamesDialog by remember { mutableStateOf(false) }
    // Library removal confirmation. Non-null while the "Are you sure?" dialog is shown.
    var pendingRemoveLocation by remember { mutableStateOf<com.videoroom.data.models.LibraryLocation?>(null) }
    // Collection deletion confirmation. Non-null while the "Are you sure?" dialog is shown.
    var pendingDeleteCollection by remember { mutableStateOf<com.videoroom.data.models.Collection?>(null) }
    // Smart-collection name dialog.
    var showSmartCollectionDialog by remember { mutableStateOf(false) }
    var smartCollectionName by remember { mutableStateOf("") }
    // Global-map dialog visibility.
    var showGlobalMap by remember { mutableStateOf(false) }
    // When non-null, the map opens focused on this (lat, lon) instead of
    // fitting the full pin bounding-box. Set when the user taps a video
    // card's location badge; cleared when the dialog is dismissed.
    var globalMapFocusLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
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

    // Help dialog visibility.
    var showHelpDialog by remember { mutableStateOf(false) }

    // Update-available banner. Non-null when a newer GitHub release is found.
    // Dismissed by the user; rechecked every 24 h (and once at startup).
    var pendingUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }

    // Top-level view mode. GRID is the default catalog view; DETAIL is the
    // single-video loupe with in-app playback.
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }

    // Per-view-mode panel state accessors. The 4 maps above store every
    // view-mode's values; these `val`s + helper funcs read/write the
    // entry for the *current* viewMode and persist on every change.
    val leftPanelWidth = leftPanelWidths[viewMode] ?: PanelPrefs.DEFAULT_WIDTH
    val rightPanelWidth = rightPanelWidths[viewMode] ?: PanelPrefs.DEFAULT_WIDTH
    val leftPanelExpanded = leftPanelExpandeds[viewMode] ?: true
    val rightPanelExpanded = rightPanelExpandeds[viewMode] ?: true
    fun setLeftPanelWidth(w: Float) {
        val clamped = PanelPrefs.clamp(w)
        leftPanelWidths[viewMode] = clamped
        PanelPrefs.saveWidth(PanelPrefs.Side.LEFT, viewMode, clamped)
    }
    fun setRightPanelWidth(w: Float) {
        val clamped = PanelPrefs.clamp(w)
        rightPanelWidths[viewMode] = clamped
        PanelPrefs.saveWidth(PanelPrefs.Side.RIGHT, viewMode, clamped)
    }
    fun setLeftPanelExpanded(open: Boolean) {
        leftPanelExpandeds[viewMode] = open
        PanelPrefs.saveExpanded(PanelPrefs.Side.LEFT, viewMode, open)
    }
    fun setRightPanelExpanded(open: Boolean) {
        rightPanelExpandeds[viewMode] = open
        PanelPrefs.saveExpanded(PanelPrefs.Side.RIGHT, viewMode, open)
    }

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
                ViewMode.GRID, ViewMode.LIST -> {
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
        onRegisterSetRatingAction { rating ->
            gridViewModel.setRatingOnSelection(rating)
        }
        onRegisterSetColorLabelAction { label ->
            gridViewModel.setColorLabelOnSelection(label)
        }
    }
    LaunchedEffect(Unit) {
        onRegisterTogglePanelsAction {
            // If either is open, close both. If both are closed, open both.
            val anyOpen = leftPanelExpanded || rightPanelExpanded
            setLeftPanelExpanded(!anyOpen)
            setRightPanelExpanded(!anyOpen)
        }
    }
    LaunchedEffect(Unit) {
        onRegisterSetGridMode { viewMode = ViewMode.GRID }
        onRegisterSetListMode { viewMode = ViewMode.LIST }
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
        gridViewModel.loadCollections()
        gridViewModel.loadFilterOptions()
        // Per-catalog grid layout — the four top-of-card stat slots.
        gridViewModel.loadGridSettings()
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

    // Check for updates at startup and every 24 h. A null result (network
    // failure, pre-release repo, etc.) is silently ignored — never block
    // the user with an error banner over a background check.
    LaunchedEffect(Unit) {
        while (true) {
            val release = UpdateChecker.checkLatestRelease(
                AppVersion.GITHUB_OWNER, AppVersion.GITHUB_REPO
            )
            if (release != null && UpdateChecker.isNewer(release.version, AppVersion.CURRENT)) {
                pendingUpdate = release
            }
            delay(24L * 60 * 60 * 1_000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            gridViewModel.onDestroy()
            detailViewModel.onDestroy()
            repository.disconnect()
            // Shut down any daemon we spawned ourselves.
            launcher.shutdown()
        }
    }

    // VideoRoom is dark-mode only — light mode is intentionally not offered.
    VideoRoomTheme(accentScheme = accentScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (connectionState == ConnectionState.Connected) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    val selectedIds = gridViewModel.selectedVideoIds.collectAsState()
                    VideoRoomTopBar(
                        gridViewModel = gridViewModel,
                        onSearch = { gridViewModel.setSearchQuery(it) },
                        onRequestAddLibrary = { showAddLibraryDialog = true },
                        onSearchFocusChanged = onSearchFocusChanged,
                        onGroupSelected = { gridViewModel.groupSelectedVideos() },
                        onConfigureWatcher = { showWatchSettingsDialog = true },
                        onConfigureCameraNames = { showCameraNamesDialog = true },
                        onConfigureAppearance = { showAppearanceDialog = true },
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
                                gridViewModel.loadVideoLocationsFilteredAsync()
                                gridViewModel.loadNamedLocationsAsync()
                                showGlobalMap = true
                            }
                        },
                        selectedCount = selectedIds.value.size,
                        onShowHelp = { showHelpDialog = true },
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

                    // Update-available banner — shown when a newer GitHub release is detected.
                    pendingUpdate?.let { release ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = VideoRoomSpacing.Medium,
                                             vertical = VideoRoomSpacing.Small),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Upgrade,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                    Column {
                                        Text(
                                            text = "VideoRoom ${release.version} is available",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "You are running ${AppVersion.CURRENT}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    com.videoroom.ui.components.Tooltip(text = "Open the GitHub releases page to download ${release.version}") {
                                        TextButton(
                                            onClick = {
                                                try {
                                                    java.awt.Desktop.getDesktop()
                                                        .browse(java.net.URI(release.releaseUrl))
                                                } catch (_: Exception) {}
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Download", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    com.videoroom.ui.components.Tooltip(text = "Dismiss this update notification") {
                                        IconButton(
                                            onClick = { pendingUpdate = null },
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

                    // Main content — takes remaining vertical space above the bottom bar.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                onRescan = { loc -> gridViewModel.rescanLibrary(loc.path) },
                                rescanningPaths = gridViewModel.rescanningPaths.collectAsState().value,
                                onCollapse = { setLeftPanelExpanded(false) },
                                collections = gridViewModel.collections.collectAsState().value,
                                selectedCollectionId = gridViewModel.selectedCollectionId.collectAsState().value,
                                onSelectCollection = { id -> gridViewModel.setCollection(id) },
                                onCreateCollection = { name ->
                                    gridViewModel.createCollection(name, isSmart = false)
                                },
                                onCreateSmartCollection = {
                                    smartCollectionName = ""
                                    showSmartCollectionDialog = true
                                },
                                onDeleteCollection = { col -> pendingDeleteCollection = col },
                                modifier = Modifier
                                    .width(leftPanelWidth.dp)
                                    .fillMaxHeight()
                            )

                            // Delete-collection confirmation dialog
                            pendingDeleteCollection?.let { col ->
                                AlertDialog(
                                    onDismissRequest = { pendingDeleteCollection = null },
                                    icon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    title = { Text("Delete collection?") },
                                    text = {
                                        Text(
                                            "\"${col.name}\" will be permanently deleted. " +
                                            "The videos in it will not be affected.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                gridViewModel.deleteCollection(col.id)
                                                pendingDeleteCollection = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) { Text("Delete") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pendingDeleteCollection = null }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }

                            // Smart-collection name dialog
                            if (showSmartCollectionDialog) {
                                AlertDialog(
                                    onDismissRequest = { showSmartCollectionDialog = false },
                                    title = { Text("Save as Smart Collection") },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                "This will capture the current filter settings " +
                                                "(camera, codec, rating, etc.) as a smart collection.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            TextField(
                                                value = smartCollectionName,
                                                onValueChange = { smartCollectionName = it },
                                                placeholder = { Text("Collection name") },
                                                singleLine = true,
                                                colors = TextFieldDefaults.colors(
                                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                                )
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                val name = smartCollectionName.trim()
                                                if (name.isNotEmpty()) {
                                                    gridViewModel.createCollection(
                                                        name,
                                                        isSmart = true,
                                                        filterJson = gridViewModel.buildSmartCollectionFilterJson()
                                                    )
                                                }
                                                showSmartCollectionDialog = false
                                            }
                                        ) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSmartCollectionDialog = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }

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
                                onClick = { setLeftPanelExpanded(true) },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }

                        // Drag handle on the left panel's inner edge.
                        // Only rendered when the panel is expanded — when
                        // collapsed the strip itself absorbs all clicks.
                        if (leftPanelExpanded) {
                            PanelResizeHandle(isLeftPanel = true) { dragDeltaPx ->
                                // Read the panel's *current* width from the
                                // state map fresh on each drag event rather
                                // than the captured `leftPanelWidth` val.
                                // Without this fresh read, fast drags lose
                                // events to a stale snapshot: every event
                                // computes `(stale_width + this_event's_delta)`
                                // and only the latest event wins, so the
                                // cursor sails ahead of the panel.
                                val current = leftPanelWidths[viewMode]
                                    ?: PanelPrefs.DEFAULT_WIDTH
                                setLeftPanelWidth(current + dragDeltaPx)
                            }
                        }

                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )

                        // Middle area — grid, list, or single-video loupe.
                        // Shared handler: tapping a card's location badge
                        // loads all video locations (if not yet loaded)
                        // and opens the global map focused on that video.
                        val onCardLocationClick: (Double, Double) -> Unit = { lat, lon ->
                            scope.launch {
                                gridViewModel.loadVideoLocationsFilteredAsync()
                                gridViewModel.loadNamedLocationsAsync()
                                globalMapFocusLocation = lat to lon
                                showGlobalMap = true
                            }
                        }

                        when (viewMode) {
                            ViewMode.GRID -> GridScreen(
                                viewModel = gridViewModel,
                                onVideoSelect = { video ->
                                    detailViewModel.setCurrentVideo(video)
                                    detailViewModel.loadMetadata(video.id)
                                },
                                thumbnailMinWidth = thumbnailWidth,
                                onLocationClick = onCardLocationClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            ViewMode.LIST -> ListScreen(
                                viewModel = gridViewModel,
                                onVideoSelect = { video ->
                                    detailViewModel.setCurrentVideo(video)
                                    detailViewModel.loadMetadata(video.id)
                                },
                                // Same slider value the grid uses — a
                                // list-mode card matches its grid-mode
                                // counterpart in size, so the size slider
                                // scales both views in lockstep instead
                                // of leaving list-mode cards half the
                                // width.
                                thumbnailHeight = thumbnailWidth,
                                onLocationClick = onCardLocationClick,
                                modifier = Modifier.weight(1f).fillMaxHeight()
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

                        // Drag handle on the right panel's inner edge.
                        // For the right panel, a rightward drag should
                        // shrink the panel — `isLeftPanel = false`
                        // negates the delta sign internally.
                        if (rightPanelExpanded) {
                            PanelResizeHandle(isLeftPanel = false) { dragDeltaPx ->
                                // See the left-panel handler for the
                                // explanation — read the current width
                                // from the state map per-event so the
                                // panel keeps pace with a fast drag.
                                val current = rightPanelWidths[viewMode]
                                    ?: PanelPrefs.DEFAULT_WIDTH
                                setRightPanelWidth(current - dragDeltaPx)
                            }
                        }

                        // Detail panel — expanded view or collapsed strip
                        if (rightPanelExpanded) {
                            DetailScreen(
                                viewModel = detailViewModel,
                                gridViewModel = gridViewModel,
                                viewMode = viewMode,
                                onCollapse = { setRightPanelExpanded(false) },
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
                                    .width(rightPanelWidth.dp)
                                    .fillMaxHeight()
                            )
                        } else {
                            com.videoroom.ui.components.CollapsedPanelStrip(
                                expandIconLeft = true,  // arrow points left (toward expand)
                                tooltip = "Show details panel (Tab)",
                                onClick = { setRightPanelExpanded(true) },
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                    }

                    // Bottom bar — view-mode toggle (left), sort controls (centre),
                    // thumbnail-size slider (right). Replaces the top-bar toggle and
                    // sort button, and the detail-panel slider.
                    val currentSort = gridViewModel.currentSortField.collectAsState()
                    val sortAsc = gridViewModel.currentSortAscending.collectAsState()
                    BottomBar(
                        viewMode = viewMode,
                        onViewModeChange = { viewMode = it },
                        currentSort = currentSort.value,
                        sortAscending = sortAsc.value,
                        onSortChange = { field, ascending -> gridViewModel.setSort(field, ascending) },
                        thumbnailWidth = thumbnailWidth,
                        onThumbnailWidthChange = {
                            thumbnailWidth = it
                            uiPrefs.putFloat("thumbnailWidth", it.value)
                        },
                    )
                }
                // Help dialog
                if (showHelpDialog) {
                    HelpDialog(onDismiss = { showHelpDialog = false })
                }

                // Appearance (accent color scheme) dialog
                if (showAppearanceDialog) {
                    com.videoroom.ui.screens.AppearanceSettingsDialog(
                        currentScheme = accentScheme,
                        onSchemeChange = { scheme ->
                            accentScheme = scheme
                            uiPrefs.put("accentScheme", scheme.name)
                        },
                        onDismiss = { showAppearanceDialog = false }
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

                // Camera-names editor dialog (internal → marketing
                // mapping with user overrides).
                if (showCameraNamesDialog) {
                    com.videoroom.ui.screens.CameraNamesDialog(
                        repository = repository,
                        onDismiss = { showCameraNamesDialog = false }
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
                        onDismiss = {
                            showGlobalMap = false
                            globalMapFocusLocation = null
                        },
                        onLocationPick = { lat, lon, radius ->
                            gridViewModel.setLocationFilter(lat, lon, radius)
                            showGlobalMap = false
                            globalMapFocusLocation = null
                        },
                        focusedLocation = globalMapFocusLocation,
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
                        onConfirm = { paths, recursive, autoGroup, dateFormat, datePosition ->
                            gridViewModel.addLibraryAndScanMultiple(
                                paths = paths,
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

// ─────────────────────────────────────────────────────────────────────────────
// Help Dialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full in-app help reference. Launched from the `?` button in the top bar.
 * Mirrors the macOS [HelpView] content: collapsible sections with icons,
 * markdown-rendered paragraphs, bullet lists, two-column tables, and numbered
 * steps.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(680.dp),
        title = {
            Column {
                Text("VideoRoom Help", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your video catalog, explained",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                    HelpSection(icon = Icons.Default.VideoLibrary, title = "What is VideoRoom?") {
                        HelpPara(
                            "VideoRoom is a video catalog manager — think Adobe Lightroom, but " +
                            "built exclusively for video files. It organises large collections of footage " +
                            "so you can find, inspect, tag, and hand off clips to professional editors, " +
                            "without VideoRoom ever modifying your original files."
                        )
                        HelpPara(
                            "VideoRoom stores all metadata, tags, and settings in a small catalog file " +
                            "(.vrcat). Your video files stay exactly where they are on disk."
                        )
                    }

                    HelpSection(icon = Icons.Default.CheckCircle, title = "What VideoRoom can do") {
                        HelpBullets(listOf(
                            "Browse hundreds of thousands of clips at 60 fps in a thumbnail grid",
                            "Extract and display codec, resolution, FPS, bitrate, duration, GPS, camera model, and more",
                            "Search instantly across filename, notes, and tags",
                            "Apply custom tags to any number of clips at once",
                            "Group related variants into stacks (e.g. 4K + proxy of the same shot)",
                            "Filter by camera, lens, codec, year, GPS radius, or library folder",
                            "Detect or generate lower-resolution proxies for oversize footage",
                            "Play clips inline using VLC (Linux/Windows) or native decoders (macOS)",
                            "Drag clips straight from the grid into DaVinci Resolve, Final Cut Pro, Premiere, and any app that accepts file drops",
                            "See geotagged clips on a world map; filter to a radius with one click",
                            "Watch library folders for new footage and update the catalog automatically",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Cancel, title = "What VideoRoom cannot do") {
                        HelpBullets(listOf(
                            "Edit, trim, or color-grade video (open in DaVinci Resolve, Premiere, etc. instead)",
                            "Transcode or encode (use Handbrake, FFmpeg, or your NLE's export panel)",
                            "Sync libraries or manage cloud storage",
                            "Play video without a compatible codec — VLC is recommended on non-Apple platforms",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Flag, title = "Getting started") {
                        HelpStep(number = "1", heading = "Create or open a catalog") {
                            Text(
                                "A catalog is a small database file that stores all your metadata, tags, and settings. " +
                                "Use File → Open Catalog… (Ctrl+O) to create a new one or open an existing one. " +
                                "Your video files are never moved or modified.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HelpStep(number = "2", heading = "Add a library location") {
                            Text(
                                "Click the folder+ icon in the top bar. You can add multiple folders in one session. " +
                                "Use \$YEAR in a path — for example /Volumes/Footage/\$YEAR/Raw — to import every " +
                                "matching year folder at once. VideoRoom scans in the background and the grid fills " +
                                "as files are indexed.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HelpStep(number = "3", heading = "Browse and inspect") {
                            Text(
                                "Click any thumbnail to select it and load its full metadata in the right panel. " +
                                "The panel shows codec, resolution, FPS, bitrate, GPS, camera model, notes, tags, and more.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    HelpSection(icon = Icons.Default.ViewModule, title = "Views") {
                        HelpTable(listOf(
                            "G — Grid" to "Adaptive thumbnail grid. Drag the slider in the bottom bar to resize cards.",
                            "L — List" to "Horizontal rows: thumbnail left, metadata columns right.",
                            "D — Detail" to "Full-window video player and inspector. Step through your library with ← / →.",
                        ))
                        HelpPara("Switch views with the segment control in the bottom bar, or press G, L, or D.")
                    }

                    HelpSection(icon = Icons.Default.TouchApp, title = "Selecting clips") {
                        HelpTable(listOf(
                            "Click" to "Select one clip",
                            "Shift-click" to "Extend the selection to include everything between the anchor and the clicked card",
                            "Ctrl-click" to "Add or remove individual clips from the selection",
                            "Ctrl+A" to "Select all currently-visible clips",
                            "Ctrl+D" to "Clear the selection",
                            "← ↑ → ↓" to "Navigate the grid one card at a time",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Layers, title = "Stacks") {
                        HelpPara(
                            "Stacks let a single card represent a group of related clips — useful for 4K originals " +
                            "paired with 1080p proxies, or multiple takes from the same setup."
                        )
                        HelpBullets(listOf(
                            "Select 2+ clips and press Ctrl+G (or click the layers icon in the top bar) to create a stack",
                            "Click the N× badge on a stack card to expand or collapse it inline",
                            "Right-click → Remove from stack: pulls just that clip out; the rest stay grouped",
                            "Right-click → Unstack: disbands the entire group so every clip stands alone",
                            "VideoRoom auto-stacks matching variants during import (can be disabled per scan)",
                        ))
                    }

                    HelpSection(icon = Icons.Default.VideoSettings, title = "Proxies") {
                        HelpPara(
                            "A proxy is a lower-resolution stand-in stored alongside the original and linked " +
                            "automatically. Use them when source footage is too large to play inline."
                        )
                        HelpBullets(listOf(
                            "Videos above the inline-playback ceiling (configurable via the playback settings button) show a warning badge",
                            "Right-click → Create proxy… to generate one; choose a target height (720p, 1080p, …)",
                            "Proxies are auto-detected when they appear in the same folder after a rescan",
                            "The P×N badge on a card means N proxies are linked to that clip",
                            "In Detail view you can manually select which proxy to play",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Search, title = "Search & filters") {
                        HelpBullets(listOf(
                            "Search bar: live search across filename, notes, and tags",
                            "Filter dropdowns (Camera · Lens · Keyword · Codec · Year): stack multiple filters; click Clear to reset all",
                            "Map view (globe icon in top bar): click a pin to filter to that GPS radius",
                            "Library panel (left): click a folder to limit the grid to that location",
                            "Right-click → Go to Folder in Library: jumps the left panel to the containing folder",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Label, title = "Tags") {
                        HelpPara(
                            "Tags are catalog-only labels — they are not written into the video file. " +
                            "Add or remove tags from the right panel while one or more clips are selected, " +
                            "or filter the grid using the Keyword dropdown."
                        )
                    }

                    HelpSection(icon = Icons.Default.OpenInNew, title = "Hand off to an editor") {
                        HelpBullets(listOf(
                            "Drag one or more cards from the grid or list directly into DaVinci Resolve, Final Cut Pro, Premiere Pro, or any app that accepts file drops",
                            "Double-click a row in List view to open it with the system's default media player",
                        ))
                    }

                    HelpSection(icon = Icons.Default.FolderOpen, title = "Library management") {
                        HelpBullets(listOf(
                            "Add as many source folders as you like — each appears as a row in the left panel",
                            "Use \$YEAR in a path (e.g. /archive/\$YEAR/) to add an entire decade of year folders in one click",
                            "Hover over a library row in the left panel to reveal the ↺ rescan button — useful after moving files",
                            "Right-click or swipe left on a library row to remove it — your video files are not deleted",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Visibility, title = "Live updates") {
                        HelpPara(
                            "VideoRoom can watch your library folders for new footage using the operating system's " +
                            "file-change notifications. Toggle via the Live pill in the top bar or from the settings panel."
                        )
                        HelpPara(
                            "When on, newly-added files appear in the grid within a few seconds of landing on disk. " +
                            "A short settle delay prevents half-written files from being indexed."
                        )
                    }

                    HelpSection(icon = Icons.Default.Keyboard, title = "Keyboard shortcuts") {
                        HelpTable(listOf(
                            "G" to "Grid view",
                            "L" to "List view",
                            "D" to "Detail / Catalog view",
                            "I" to "Cycle info overlay (Detail mode: none → camera → file → …)",
                            "Tab" to "Toggle both side panels",
                            "Space" to "Play / pause selected clip",
                            "Ctrl+G" to "Stack selected clips into a group",
                            "Ctrl+A" to "Select all currently-visible clips",
                            "Ctrl+D" to "Deselect all",
                            "Ctrl+O" to "Open Catalog…",
                            "← ↑ → ↓" to "Navigate the grid",
                            "Escape" to "Clear search field focus",
                        ))
                    }

                    HelpSection(icon = Icons.Default.Lightbulb, title = "Tips & tricks") {
                        HelpBullets(listOf(
                            "The thumbnail slider in the bottom bar rescales in real time — find the density that suits your display",
                            "Rescan a folder from the left panel without re-adding it — hover the row and click ↺",
                            "Press Tab to hide both panels and give the grid maximum screen space (Lightroom-style)",
                            "Hold Shift to select a range in the grid, then drag the whole selection into your editor",
                            "Using \$YEAR when adding a library (e.g. /footage/\$YEAR/) imports decade-scale archives in one click",
                            "Auto-stacking during import groups 4K + 1080p variants automatically — look for the N× badge",
                        ))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ── Help dialog primitives ────────────────────────────────────────────────────

@Composable
private fun HelpSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Clickable section header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

@Composable
private fun HelpPara(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun HelpBullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp)
                )
                Text(
                    item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HelpTable(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (key, value) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(140.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HelpStep(
    number: String,
    heading: String,
    body: @Composable () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(heading, style = MaterialTheme.typography.labelMedium)
            body()
        }
    }
}

@Composable
fun VideoRoomTopBar(
    gridViewModel: com.videoroom.viewmodel.GridViewModel,
    onSearch: (String) -> Unit,
    onRequestAddLibrary: () -> Unit,
    onGroupSelected: () -> Unit = {},
    /** Opens the watcher (live-updates) preferences dialog. */
    onConfigureWatcher: () -> Unit = {},
    /** Opens the camera-names editor dialog. */
    onConfigureCameraNames: () -> Unit = {},
    /** Opens the appearance (accent color scheme) dialog. */
    onConfigureAppearance: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onCloseCatalog: () -> Unit = {},
    onOpenRecent: (String) -> Unit = {},
    catalogIsOpen: Boolean = false,
    catalogName: String = "",
    recents: List<String> = emptyList(),
    /** Opens the global map dialog showing every geotagged video. */
    onShowGlobalMap: () -> Unit = {},
    selectedCount: Int = 0,
    onSearchFocusChanged: (Boolean) -> Unit = {},
    /** Opens the full in-app help reference. */
    onShowHelp: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var showFileMenu by remember { mutableStateOf(false) }

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

                    // Camera names editor — opens the table of internal →
                    // marketing name mappings (built-in + user overrides).
                    com.videoroom.ui.components.Tooltip(
                        text = "Manage the table that maps internal camera model codes " +
                            "(e.g. \"SONY ILCE-7RM3A\") to marketing-friendly names " +
                            "(e.g. \"Sony a7R IIIA\"). Add custom rows for cameras " +
                            "not in the built-in list, or override built-in entries " +
                            "you'd prefer named differently."
                    ) {
                        IconButton(onClick = onConfigureCameraNames) {
                            Icon(
                                imageVector = Icons.Default.Camera,
                                contentDescription = "Camera Names"
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
                                    // Compose's default `lineHeight` for inline
                                    // Text is ~1.4× font size, which adds
                                    // asymmetric padding above the cap height
                                    // and visibly pushes the glyphs down
                                    // inside a CenterVertically Row. Clamping
                                    // line height to the font size eliminates
                                    // that padding so "Live" sits on the
                                    // dot's vertical axis.
                                    lineHeight = 11.sp,
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

                    // Appearance (accent color scheme) button
                    com.videoroom.ui.components.Tooltip(
                        text = "Choose the accent color scheme for the interface " +
                            "(Purple or Blue)."
                    ) {
                        IconButton(onClick = onConfigureAppearance) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Appearance"
                            )
                        }
                    }

                    // Help button
                    com.videoroom.ui.components.Tooltip(
                        text = "Open VideoRoom Help — learn what VideoRoom can do, " +
                            "keyboard shortcuts, and tips for new users."
                    ) {
                        IconButton(onClick = onShowHelp) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = "Help"
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
 * Full-width bottom status/control bar.
 *
 * Layout (left → right):
 *   • [ViewModeToggle] — three-segment Catalog/Grid/List toggle (left cluster)
 *   • Sort controls — field dropdown + ascending/descending toggle (centred)
 *   • Thumbnail-size slider — only enabled in Grid or List mode (right cluster)
 *
 * Height is fixed at 44 dp with a top divider line, matching the Lightroom
 * filmstrip bar aesthetic.
 */
@Composable
fun BottomBar(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    currentSort: String,
    sortAscending: Boolean,
    onSortChange: (String, Boolean) -> Unit,
    thumbnailWidth: androidx.compose.ui.unit.Dp,
    onThumbnailWidthChange: (androidx.compose.ui.unit.Dp) -> Unit,
) {
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
        "iso" to "ISO",
        "aperture" to "Aperture",
        "exposure_time" to "Exposure Time",
        "focal_length" to "Focal Length",
        "keyword" to "Keyword",
    )
    val currentSortLabel = sortOptions.firstOrNull { it.first == currentSort }?.second ?: currentSort
    var showSortMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top divider
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left cluster — view-mode toggle
                ViewModeToggle(current = viewMode, onChange = onViewModeChange)

                Spacer(modifier = Modifier.weight(1f))

                // Centre — sort controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.XSmall)
                ) {
                    // Sort field dropdown
                    Box {
                        com.videoroom.ui.components.Tooltip(
                            text = "Sort the video grid. Click the same field again to reverse direction."
                        ) {
                            OutlinedButton(
                                onClick = { showSortMenu = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = currentSortLabel,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
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
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
                                                Icon(
                                                    imageVector = if (sortAscending)
                                                        Icons.Default.ArrowUpward
                                                    else
                                                        Icons.Default.ArrowDownward,
                                                    contentDescription = if (sortAscending) "Ascending" else "Descending",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (isSelected) {
                                            onSortChange(key, !sortAscending)
                                        } else {
                                            onSortChange(key, key == "filename" || key == "camera" || key == "codec")
                                        }
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Ascending / descending toggle
                    com.videoroom.ui.components.Tooltip(
                        text = if (sortAscending) "Sorted ascending — click to reverse" else "Sorted descending — click to reverse"
                    ) {
                        IconButton(
                            onClick = { onSortChange(currentSort, !sortAscending) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = if (sortAscending) "Ascending" else "Descending",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Right cluster — thumbnail size slider (disabled in Catalog/Detail mode)
                val sliderEnabled = viewMode != ViewMode.DETAIL
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VideoRoomSpacing.XSmall)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoSizeSelectSmall,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (sliderEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    com.videoroom.ui.components.Tooltip(
                        text = if (sliderEnabled)
                            "Drag to resize thumbnails. The grid automatically adjusts how many columns fit."
                        else
                            "Thumbnail size only applies in Grid or List mode."
                    ) {
                        Slider(
                            value = thumbnailWidth.value,
                            onValueChange = { onThumbnailWidthChange(it.dp) },
                            valueRange = 120f..400f,
                            enabled = sliderEnabled,
                            modifier = Modifier.width(140.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.PhotoSizeSelectLarge,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (sliderEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

/**
 * Three-segment icon-only pill toggle: Grid (G) / List (L) / Detail (D).
 * The active segment has a filled primary background; the others are transparent.
 * Divider lines between segments give the segmented-control appearance.
 */
@Composable
fun ViewModeToggle(
    current: ViewMode,
    onChange: (ViewMode) -> Unit
) {
    val modes = listOf(
        Triple(ViewMode.GRID,   Icons.Default.GridView,          "Grid view (G)"),
        Triple(ViewMode.LIST,   Icons.Default.ViewList,          "List view (L)"),
        Triple(ViewMode.DETAIL, Icons.Default.PlayCircleOutline, "Detail view (D)")
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        color = Color.Transparent
    ) {
        Row(modifier = Modifier.height(32.dp)) {
            modes.forEachIndexed { idx, (mode, icon, tooltip) ->
                if (idx > 0) {
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant))
                }
                com.videoroom.ui.components.Tooltip(text = tooltip) {
                    Box(
                        modifier = Modifier
                            .size(36.dp, 32.dp)
                            .background(
                                if (current == mode) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onChange(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (current == mode)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
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
    val minRating = gridViewModel.filterMinRating.collectAsState().value
    val colorLabel = gridViewModel.filterColorLabel.collectAsState().value
    val anyFilterActive =
        camera.isNotEmpty() || lens.isNotEmpty() || codec.isNotEmpty() ||
            year != 0 || tagId.isNotEmpty() ||
            minRating != 0 || colorLabel.isNotEmpty()

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
                    onSelect = { gridViewModel.setCameraFilter(it) },
                    displayLabels = options.cameraDisplayNames
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
            Spacer(modifier = Modifier.width(VideoRoomSpacing.XSmall))
        }

        // Lightroom-style rating filter.
        com.videoroom.ui.components.Tooltip(
            text = "Show only videos at or above this star rating. Pick \"---\" to clear."
        ) {
            FilterDropdown(
                label = "Rating",
                values = listOf("≥1", "≥2", "≥3", "≥4", "5"),
                selected = when (minRating) {
                    0 -> ""
                    5 -> "5"
                    else -> "≥$minRating"
                },
                onSelect = { raw ->
                    val n = when {
                        raw.isEmpty() -> 0
                        raw == "5"    -> 5
                        else          -> raw.removePrefix("≥").toIntOrNull() ?: 0
                    }
                    gridViewModel.setMinRatingFilter(n)
                }
            )
        }
        Spacer(modifier = Modifier.width(VideoRoomSpacing.XSmall))

        // Lightroom-style colour-label filter.
        com.videoroom.ui.components.Tooltip(
            text = "Show only videos with this colour label. Pick \"---\" to clear."
        ) {
            val colorChoices = com.videoroom.data.models.ColorLabel.values()
                .filter { it != com.videoroom.data.models.ColorLabel.None }
                .map { it.displayName }
            FilterDropdown(
                label = "Color",
                values = colorChoices,
                selected = com.videoroom.data.models.ColorLabel.from(colorLabel).let {
                    if (it == com.videoroom.data.models.ColorLabel.None) "" else it.displayName
                },
                onSelect = { display ->
                    val match = com.videoroom.data.models.ColorLabel.values()
                        .firstOrNull { it.displayName == display }
                        ?: com.videoroom.data.models.ColorLabel.None
                    gridViewModel.setColorLabelFilter(match.raw)
                }
            )
        }

        if (anyFilterActive) {
            Spacer(modifier = Modifier.width(VideoRoomSpacing.Small))
            com.videoroom.ui.components.Tooltip(
                text = "Clear all active filters (camera, lens, keyword, codec, year, rating, colour)."
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
    onSelect: (String) -> Unit,
    /**
     * Optional parallel list of user-facing labels — same length and
     * order as [values]. When provided, dropdown items + the selected
     * chip render the label, but the internal `value` is still what
     * gets passed to [onSelect]. Used by the Camera filter to show
     * marketing names (e.g. "Sony a7R III") while filtering on the
     * internal model code (e.g. "SONY ILCE-7RM3"). Pass an empty list
     * (the default) to keep the legacy "label == value" behaviour.
     */
    displayLabels: List<String> = emptyList()
) {
    var expanded by remember { mutableStateOf(false) }
    fun labelFor(idx: Int, value: String): String =
        if (displayLabels.isNotEmpty() && idx < displayLabels.size) displayLabels[idx] else value
    val display = when {
        selected.isEmpty() -> "---"
        displayLabels.isNotEmpty() -> {
            val idx = values.indexOf(selected)
            if (idx >= 0 && idx < displayLabels.size) displayLabels[idx] else selected
        }
        else -> selected
    }

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
            values.forEachIndexed { idx, value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            labelFor(idx, value),
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
     * paths: all non-blank paths entered by the user (may be one or many).
     */
    onConfirm: (
        paths: List<String>,
        recursive: Boolean,
        autoGroup: Boolean,
        dateFormat: String,
        datePosition: String
    ) -> Unit
) {
    // Each entry in the list is the text in one path row.
    val pathEntries = remember { mutableStateListOf("") }
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

    val hasAnyNonBlank = pathEntries.any { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Library Locations") },
        text = {
            Column {
                Text(
                    text = "Enter one or more directories containing videos. Use \$YEAR in a path " +
                        "(e.g. /Volumes/Media/\$YEAR/Raw) to add every matching year folder at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                // ── Path list ──────────────────────────────────────────────
                // Show at most ~4 rows before scrolling.
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(VideoRoomSpacing.Small)
                ) {
                    itemsIndexed(pathEntries) { idx, pathValue ->
                        // Client-side $YEAR preview: count how many year directories
                        // the user's path template would match on the local filesystem.
                        // The backend does the authoritative expansion; this is UX only.
                        val yearHint: String? = remember(pathValue) {
                            if (!pathValue.contains("\$YEAR")) return@remember null
                            val yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                            val matches = (1970..yearNow)
                                .map { y -> pathValue.replace("\$YEAR", y.toString()) }
                                .filter { java.io.File(it).isDirectory }
                            if (matches.isEmpty()) {
                                "No matching directories found on disk yet"
                            } else {
                                val example = matches.firstOrNull() ?: ""
                                "Will expand to ${matches.size} director${if (matches.size == 1) "y" else "ies"} " +
                                    "(e.g. $example)"
                            }
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                com.videoroom.ui.components.Tooltip(
                                    text = "Full filesystem path to the folder containing your videos. " +
                                        "Press Tab to complete, type more to narrow the suggestions, or " +
                                        "pick a directory from the dropdown. " +
                                        "Use \$YEAR to expand to every matching year folder."
                                ) {
                                    com.videoroom.ui.components.PathCompletingTextField(
                                        value = pathValue,
                                        onValueChange = { pathEntries[idx] = it },
                                        placeholder = if (idx == 0) "/Users/you/Videos" else "Another path…",
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                // Remove button — only shown when there are multiple rows.
                                if (pathEntries.size > 1) {
                                    IconButton(
                                        onClick = { pathEntries.removeAt(idx) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove path",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (yearHint != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = yearHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Small))

                // "+  Add another path" button
                TextButton(
                    onClick = { pathEntries.add("") },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add another path",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(VideoRoomSpacing.Medium))

                // ── Scan options ───────────────────────────────────────────
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
                    val nonBlankPaths = pathEntries.map { it.trim() }.filter { it.isNotBlank() }
                    if (nonBlankPaths.isNotEmpty()) {
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
                            nonBlankPaths,
                            recursive,
                            autoGroup,
                            if (inferDate) dateFormat else "",
                            if (inferDate) datePosition else ""
                        )
                    }
                },
                enabled = hasAnyNonBlank
            ) {
                Text(if (pathEntries.count { it.isNotBlank() } > 1) "Add & Scan All" else "Add & Scan")
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
