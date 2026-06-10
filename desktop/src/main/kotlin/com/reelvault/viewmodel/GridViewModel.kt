// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.reelvault.data.models.PostIndexProgress
import com.reelvault.data.models.VideoSummary
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.prefs.Preferences

/**
 * How long the background post-index panel lingers after a pass completes
 * before clearing. The file watcher runs the pass in short waves; this
 * window bridges the gap between consecutive waves so the panel doesn't
 * flicker on and off during a bulk refresh.
 */
private const val POST_INDEX_LINGER_MS = 3_500L

/** uiPrefs keys for the persisted grid sort (field + direction). Kept here so
 *  the load (property init) and save ([GridViewModel.setSort]) can't drift. */
private const val PREF_SORT_FIELD = "sortField"
private const val PREF_SORT_ASCENDING = "sortAscending"

/** Arrow-key navigation direction in the grid / list. */
enum class NavDirection { Up, Down, Left, Right }

/**
 * Destination index for an arrow-key move, or -1 for "no move" (edge of the
 * navigable area). Pure so the geometry is unit-testable.
 *
 * `cols == 1` means a single-column layout (list mode, or a one-wide grid):
 * every direction collapses to previous/next, with Left/Up == previous and
 * Right/Down == next. For a wider grid, Left/Right step within the row (no
 * wrap) and Up/Down jump a whole row.
 */
fun navTargetIndex(current: Int, size: Int, cols: Int, dir: NavDirection): Int {
    if (current < 0 || current >= size) return -1
    val columns = cols.coerceAtLeast(1)
    return when (dir) {
        NavDirection.Left ->
            if (columns == 1) (if (current > 0) current - 1 else -1)
            else if (current % columns != 0) current - 1 else -1
        NavDirection.Right ->
            if (columns == 1) (if (current < size - 1) current + 1 else -1)
            else if (current % columns != columns - 1 && current + 1 < size) current + 1 else -1
        NavDirection.Up ->
            if (columns == 1) (if (current > 0) current - 1 else -1)
            else if (current - columns >= 0) current - columns else -1
        NavDirection.Down ->
            if (columns == 1) (if (current < size - 1) current + 1 else -1)
            else if (current + columns < size) current + columns else -1
    }
}

/**
 * Index of the next/previous card that's in [selected], scanning outward from
 * [from] in visual (row-major) order. Drives Left/Right traversal of a
 * multi-row selection: at a row's edge the next selected card is on the
 * following row, so the cursor wraps a line. Returns -1 past the selection's
 * first/last member.
 */
fun nextSelectedIndex(
    order: List<VideoSummary>,
    from: Int,
    selected: Set<String>,
    forward: Boolean,
): Int {
    if (forward) {
        for (j in from + 1 until order.size) if (order[j].id in selected) return j
    } else {
        for (j in from - 1 downTo 0) if (order[j].id in selected) return j
    }
    return -1
}

class GridViewModel(
    private val repository: VideoRepository
) {
    private val logger = LoggerFactory.getLogger(GridViewModel::class.java)

    /** Local UI preferences shared with the settings dialogs. */
    private val uiPrefs: Preferences =
        Preferences.userRoot().node("com/reelvault/ui")
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State
    private val _videos = MutableStateFlow<List<VideoSummary>>(emptyList())
    val videos: StateFlow<List<VideoSummary>> = _videos.asStateFlow()

    private val _selectedVideoId = MutableStateFlow<String?>(null)
    val selectedVideoId: StateFlow<String?> = _selectedVideoId.asStateFlow()

    // Multi-selection: ordered set of selected video IDs.
    private val _selectedVideoIds = MutableStateFlow<List<String>>(emptyList())
    val selectedVideoIds: StateFlow<List<String>> = _selectedVideoIds.asStateFlow()

    // The "anchor" video — pivot point used for shift-click range selection.
    // Set by a plain click or toggle-click; not changed by shift-click itself.
    private val _anchorVideoId = MutableStateFlow<String?>(null)
    val anchorVideoId: StateFlow<String?> = _anchorVideoId.asStateFlow()

    // Arrow-key navigation context, pushed by whichever screen (grid / list) is
    // currently composed: the videos in visual order (stack children spliced
    // in) and the live column count (1 for list mode). `moveSelection` reads
    // these; nothing in the UI binds to them so plain vars suffice.
    private var navVideos: List<VideoSummary> = emptyList()
    private var navColumns: Int = 1

    /** Whichever of grid / list is on screen reports its layout here. */
    fun setNavContext(orderedVideos: List<VideoSummary>, columns: Int) {
        navVideos = orderedVideos
        navColumns = columns
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _totalCount = MutableStateFlow(0L)
    val totalCount: StateFlow<Long> = _totalCount.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // Pagination
    private val pageSize = 50
    private var currentPage = 0
    // Sort field + direction. Loaded from uiPrefs so the user's last choice
    // survives restarts; written back in [setSort]. Defaults match a fresh
    // install (newest-indexed first).
    private var sortBy = uiPrefs.get(PREF_SORT_FIELD, "indexed_at")
    private var sortAscending = uiPrefs.getBoolean(PREF_SORT_ASCENDING, false)
    private var filterTags = emptyList<String>()
    private var collectionId: String? = null
    // Library Filter "text" mode. A StateFlow so the editor can bind to it and
    // the value survives switching the visible editor (COMBINE semantics).
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    /** Currently selected library location to filter by. Empty string = all. */
    private var locationPathFilter: String = ""

    // Library locations panel state
    private val _libraryLocations = MutableStateFlow<List<com.reelvault.data.models.LibraryLocation>>(emptyList())
    val libraryLocations: StateFlow<List<com.reelvault.data.models.LibraryLocation>> = _libraryLocations.asStateFlow()

    private val _selectedLocationPath = MutableStateFlow("")  // "" = all locations
    val selectedLocationPath: StateFlow<String> = _selectedLocationPath.asStateFlow()

    // Multi-select: the set of selected library directories (empty = all).
    // The grid shows the union of their videos; `locationPathFilter` carries
    // them to the backend as a '\n'-joined string. `selectedLocationPath`
    // above stays in sync with the first entry for single-path callers.
    private val _selectedLocationPaths = MutableStateFlow<List<String>>(emptyList())
    val selectedLocationPaths: StateFlow<List<String>> = _selectedLocationPaths.asStateFlow()
    // Pivot for shift-click range selection over the library list.
    private var locationAnchorPath: String? = null

    // Keywords (tags). `tags` is the full list of known tags with usage counts;
    // `filterTagId` narrows the grid to a single tag (drives the `filterTags`
    // list passed to listVideos).
    private val _tags = MutableStateFlow<List<com.reelvault.data.models.Tag>>(emptyList())
    val tags: StateFlow<List<com.reelvault.data.models.Tag>> = _tags.asStateFlow()

    private val _filterTagId = MutableStateFlow("")  // "" = no tag filter
    val filterTagId: StateFlow<String> = _filterTagId.asStateFlow()

    private val _collections = MutableStateFlow<List<com.reelvault.data.models.Collection>>(emptyList())
    val collections: StateFlow<List<com.reelvault.data.models.Collection>> = _collections.asStateFlow()

    private val _selectedCollectionId = MutableStateFlow<String?>(null)
    val selectedCollectionId: StateFlow<String?> = _selectedCollectionId.asStateFlow()

    // Library Filter — "metadata" mode. `metadataColumns` is the ordered list
    // of columns (camera/lens/exposure/iso by default); `metadataFacets` holds
    // the server's per-column available values (1:1 with columns by index);
    // `metadataAvailableKeys` populates each column's key picker.
    private val _libraryFilterMode =
        MutableStateFlow(com.reelvault.data.models.LibraryFilterMode.Clear)
    val libraryFilterMode: StateFlow<com.reelvault.data.models.LibraryFilterMode> =
        _libraryFilterMode.asStateFlow()
    private val _metadataColumns =
        MutableStateFlow(LibraryFilterPrefs.loadColumns())
    val metadataColumns: StateFlow<List<com.reelvault.data.models.MetadataColumn>> =
        _metadataColumns.asStateFlow()
    private val _metadataFacets =
        MutableStateFlow<List<com.reelvault.data.models.FacetColumn>>(emptyList())
    val metadataFacets: StateFlow<List<com.reelvault.data.models.FacetColumn>> =
        _metadataFacets.asStateFlow()
    private val _metadataAvailableKeys =
        MutableStateFlow<List<com.reelvault.data.models.MetadataKeyInfo>>(emptyList())
    val metadataAvailableKeys: StateFlow<List<com.reelvault.data.models.MetadataKeyInfo>> =
        _metadataAvailableKeys.asStateFlow()

    // Lightroom-style user-mark filters. `_filterMinRating` of 0 = no filter;
    // 1..5 = "show videos with at least N stars". `_filterColorLabel` of ""
    // = no filter; otherwise exact-match the colour name.
    private val _filterMinRating = MutableStateFlow(0)
    val filterMinRating: StateFlow<Int> = _filterMinRating.asStateFlow()
    private val _filterColorLabel = MutableStateFlow("")
    val filterColorLabel: StateFlow<String> = _filterColorLabel.asStateFlow()

    // Tri-state presence filters (Library Filter "attribute" mode). Any = no
    // constraint; Yes = must have; No = must not have.
    private val _filterHasLocation = MutableStateFlow(com.reelvault.data.models.AttributeFilterState.Any)
    val filterHasLocation: StateFlow<com.reelvault.data.models.AttributeFilterState> = _filterHasLocation.asStateFlow()
    private val _filterHasKeywords = MutableStateFlow(com.reelvault.data.models.AttributeFilterState.Any)
    val filterHasKeywords: StateFlow<com.reelvault.data.models.AttributeFilterState> = _filterHasKeywords.asStateFlow()
    private val _filterHasProxies = MutableStateFlow(com.reelvault.data.models.AttributeFilterState.Any)
    val filterHasProxies: StateFlow<com.reelvault.data.models.AttributeFilterState> = _filterHasProxies.asStateFlow()
    private val _filterFullResolution = MutableStateFlow(com.reelvault.data.models.AttributeFilterState.Any)
    val filterFullResolution: StateFlow<com.reelvault.data.models.AttributeFilterState> = _filterFullResolution.asStateFlow()

    // Lightroom-style top-of-card slot configuration. Four entries, each a
    // GridStatKey.raw value. Defaults until `loadGridSettings` answers.
    private val _topSlots = MutableStateFlow(com.reelvault.data.models.defaultGridTopSlots)
    val topSlots: StateFlow<List<String>> = _topSlots.asStateFlow()

    // Geographic proximity filter — set when the user taps a pin on the
    // global map. Triple of (latitude, longitude, radius_km). Null = no
    // proximity filter active.
    private val _filterLocation = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val filterLocation: StateFlow<Triple<Double, Double, Double>?> = _filterLocation.asStateFlow()

    // Known locations (named places + unnamed coordinate clusters) with video
    // counts, for the Library Filter's "Location" mode. Refreshed via
    // [loadFilterLocations] from the full geotagged set so the list isn't itself
    // narrowed by the active filter.
    private val _filterLocationGroups =
        MutableStateFlow<List<com.reelvault.data.models.LocationFilterGroup>>(emptyList())
    val filterLocationGroups: StateFlow<List<com.reelvault.data.models.LocationFilterGroup>> =
        _filterLocationGroups.asStateFlow()

    // Snapshot of every geotagged video, refreshed when the user opens the
    // global map. Kept here (not in App.kt) so the open-map button can stay
    // disabled when there's nothing to plot.
    private val _videoLocations = MutableStateFlow<List<com.reelvault.data.models.VideoLocation>>(emptyList())
    val videoLocations: StateFlow<List<com.reelvault.data.models.VideoLocation>> = _videoLocations.asStateFlow()

    // Full VideoSummary for every geotagged video in the current filtered
    // set — captured alongside [_videoLocations] so the map view's right
    // panel can render real video cards for a selected pin without an extra
    // round-trip. Same filter and order as [videoLocations].
    private val _geotaggedVideos = MutableStateFlow<List<VideoSummary>>(emptyList())
    val geotaggedVideos: StateFlow<List<VideoSummary>> = _geotaggedVideos.asStateFlow()

    // True while a filtered video-locations load is in flight. The map view's
    // right panel shows a progress indicator (instead of an empty/stale list)
    // while a clicked location's videos are still being resolved — important on
    // a slow NAS catalog, where pins can appear before [geotaggedVideos] fills.
    private val _isLoadingVideoLocations = MutableStateFlow(false)
    val isLoadingVideoLocations: StateFlow<Boolean> = _isLoadingVideoLocations.asStateFlow()

    // Catalog's user-defined named places (e.g. "Home"). Refreshed by
    // `loadNamedLocations`; used by `nameForLocation` to render named pins
    // on the map and named GPS readouts in the detail panel.
    private val _namedLocations = MutableStateFlow<List<com.reelvault.data.models.NamedLocation>>(emptyList())
    val namedLocations: StateFlow<List<com.reelvault.data.models.NamedLocation>> = _namedLocations.asStateFlow()

    // Sort state exposed for the UI
    private val _currentSortField = MutableStateFlow(sortBy)
    val currentSortField: StateFlow<String> = _currentSortField.asStateFlow()

    private val _currentSortAscending = MutableStateFlow(sortAscending)
    val currentSortAscending: StateFlow<Boolean> = _currentSortAscending.asStateFlow()

    // UI state
    private val _selectedVideo = mutableStateOf<VideoSummary?>(null)
    val selectedVideo: State<VideoSummary?> = _selectedVideo

    // --- Real-time updates ---

    /**
     * Reflects the most-recent live-updates state we heard about from the
     * server (initial value matches the protocol default; updated after
     * the first `CatalogEvent` arrives). Drives the toolbar's "live"
     * indicator.
     */
    private val _liveUpdatesEnabled = MutableStateFlow(true)
    val liveUpdatesEnabled: StateFlow<Boolean> = _liveUpdatesEnabled.asStateFlow()

    /**
     * Sticky banner set by `SCAN_STARTED` and cleared by `SCAN_COMPLETED`.
     * Distinct from per-user scan progress so the watcher can light up the
     * banner without colliding with `addLibraryAndScan`'s own reporter.
     */
    private val _watcherBanner = MutableStateFlow<String?>(null)
    val watcherBanner: StateFlow<String?> = _watcherBanner.asStateFlow()

    /**
     * Live progress for the daemon's background post-index pass (proxy
     * detection / auto-grouping / camera-sensor lookups). Null when no pass
     * is active. Driven by the POST_INDEX_* catalog events and rendered in
     * a background-activity panel so a long, CPU-heavy pass isn't invisible.
     *
     * The file watcher runs the pass in short waves (one per batch of
     * settled files), so we linger briefly after PostIndexCompleted — see
     * [postIndexClearJob] — to render back-to-back waves as one continuous
     * "busy" panel instead of flickering on and off.
     */
    private val _postIndexProgress = MutableStateFlow<PostIndexProgress?>(null)
    val postIndexProgress: StateFlow<PostIndexProgress?> = _postIndexProgress.asStateFlow()

    /**
     * Pending "clear the post-index panel" job, scheduled on
     * PostIndexCompleted and cancelled if another pass starts within the
     * linger window.
     */
    private var postIndexClearJob: Job? = null

    /**
     * Coroutine job owning the open `subscribeCatalogEvents` collection.
     * Cancelled on `stopCatalogEventStream()`; replaced if the stream
     * drops and we reconnect.
     */
    private var catalogEventsJob: Job? = null

    /**
     * Coalesces a flurry of file-level watcher events into a single grid
     * reload. Typical SAN-drop generates 5–20 events in <1 s; this
     * collapses them into one `loadVideos()` call.
     */
    private var watcherRefreshJob: Job? = null

    /**
     * In-flight `listVideos` coroutine for the active page load.
     * `loadVideos()` / `loadMore()` cancel it before launching a new one so
     * a late response from the prior selection / filter can't clobber the
     * new one. Coroutine cancellation throws `CancellationException` from
     * the suspend call, so the body after the await never runs.
     */
    private var listLoadJob: Job? = null

    init {
        logger.info("GridViewModel created")
    }

    // --- Live updates ---

    /**
     * Open (or re-open) the long-lived `SubscribeCatalogEvents` stream
     * against the daemon. Idempotent — calling twice cancels the prior
     * job and starts a fresh one. While live updates are enabled, the
     * coroutine retries on failure with exponential backoff (2, 4, 8,
     * 16, 32, 60 s, then 60 s) and suppresses duplicate error logs so
     * a daemon that stays offline doesn't spam the console with the
     * same `Connection refused` stack every two seconds.
     */
    fun startCatalogEventStream() {
        catalogEventsJob?.cancel()
        catalogEventsJob = viewModelScope.launch {
            var attempt = 0
            var lastErrorSignature: String? = null
            while (isActive && _liveUpdatesEnabled.value) {
                try {
                    repository.subscribeCatalogEvents().collect { event ->
                        // First event after a (re)connect — announce we're
                        // back online (if we had been logging failures) and
                        // reset the retry state.
                        if (attempt > 0 || lastErrorSignature != null) {
                            logger.info("Catalog events stream reconnected after {} attempt(s)", attempt)
                            attempt = 0
                            lastErrorSignature = null
                        }
                        handleCatalogEvent(event)
                    }
                    // Stream ended cleanly (server closed it without throwing).
                    lastErrorSignature = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Suppress duplicate log spam when the daemon stays
                    // offline: log the first occurrence of each distinct
                    // error, then stay quiet until either the error type
                    // changes or the stream reconnects.
                    val signature = e.message ?: e::class.simpleName ?: "unknown"
                    if (signature != lastErrorSignature) {
                        logger.warn("Catalog events stream ended: {}", signature)
                        lastErrorSignature = signature
                    }
                }
                if (!isActive || !_liveUpdatesEnabled.value) break
                attempt++
                // Exponential backoff capped at 60 s: 2, 4, 8, 16, 32, 60, ...
                val delayMs = minOf(60_000L, 1_000L * (1L shl minOf(attempt, 6)))
                delay(delayMs)
            }
        }
    }

    fun stopCatalogEventStream() {
        catalogEventsJob?.cancel()
        catalogEventsJob = null
        watcherRefreshJob?.cancel()
        watcherRefreshJob = null
        postIndexClearJob?.cancel()
        postIndexClearJob = null
        _postIndexProgress.value = null
    }

    /**
     * React to one [com.reelvault.data.models.CatalogEvent]: drives the
     * live-updates indicator, the watcher banner, and a debounced grid
     * refresh when video rows actually change.
     */
    private fun handleCatalogEvent(event: com.reelvault.data.models.CatalogEvent) {
        when (event.kind) {
            com.reelvault.data.models.CatalogEventKind.WatcherStarted ->
                _liveUpdatesEnabled.value = true
            com.reelvault.data.models.CatalogEventKind.WatcherDisabled ->
                _liveUpdatesEnabled.value = false
            com.reelvault.data.models.CatalogEventKind.ScanStarted -> {
                val target = if (event.path.isBlank()) "library" else event.path.substringAfterLast('/')
                _watcherBanner.value = "Scanning $target…"
            }
            com.reelvault.data.models.CatalogEventKind.ScanCompleted -> {
                _watcherBanner.value = null
                scheduleWatcherRefresh()
            }
            com.reelvault.data.models.CatalogEventKind.VideoAdded,
            com.reelvault.data.models.CatalogEventKind.VideoModified,
            com.reelvault.data.models.CatalogEventKind.VideoRemoved ->
                scheduleWatcherRefresh()
            com.reelvault.data.models.CatalogEventKind.PostIndexStarted,
            com.reelvault.data.models.CatalogEventKind.PostIndexProgress -> {
                // A background pass is running — cancel any pending "clear"
                // and show the latest snapshot.
                postIndexClearJob?.cancel()
                postIndexClearJob = null
                _postIndexProgress.value = event.postIndex
            }
            com.reelvault.data.models.CatalogEventKind.PostIndexCompleted -> {
                // Refresh the grid so newly-linked proxies / groups appear,
                // then clear the panel after a short linger. The watcher
                // runs the pass in waves; lingering bridges the gap between
                // consecutive waves so the panel reads as one continuous
                // "busy" state instead of flickering.
                scheduleWatcherRefresh()
                postIndexClearJob?.cancel()
                postIndexClearJob = viewModelScope.launch {
                    delay(POST_INDEX_LINGER_MS)
                    _postIndexProgress.value = null
                }
            }
            com.reelvault.data.models.CatalogEventKind.Unknown -> { /* future kinds */ }
        }
    }

    // --- Proxy management ---

    /** Live state for a proxy being generated for a single video. */
    data class ProxyCreationState(
        val videoId: String,
        val progressPercent: Double,
        val status: String,
        val message: String,
    )

    /** Map of videoId → active proxy generation state. Multiple proxies
     *  can be queued concurrently (one per coroutine). Entries are removed
     *  on completion or error. */
    private val _activeProxyCreations = MutableStateFlow<Map<String, ProxyCreationState>>(emptyMap())
    val activeProxyCreations: StateFlow<Map<String, ProxyCreationState>> = _activeProxyCreations.asStateFlow()

    /** Set to the video the user wants to create a proxy of. App.kt
     *  hosts a sheet that observes this state and shows the resolution
     *  picker; user confirmation calls `startProxyCreation`. */
    private val _proxyCreationVideoId = MutableStateFlow<String?>(null)
    val proxyCreationVideoId: StateFlow<String?> = _proxyCreationVideoId.asStateFlow()

    /** Called by the grid's right-click menu. Just publishes the
     *  request — the picker sheet picks up `proxyCreationVideoId` and
     *  asks the user for a target resolution. */
    fun requestCreateProxy(videoId: String) {
        _proxyCreationVideoId.value = videoId
    }

    /** Dismiss the picker without starting a job. */
    fun cancelProxyCreation() {
        _proxyCreationVideoId.value = null
    }

    /**
     * Kick off proxy generation. Streams real encoding progress into
     * [activeProxyCreations] so cards and the detail panel can show a
     * live progress bar. Refreshes the grid on completion so the proxy
     * badge appears on the source card. Clears [proxyCreationVideoId]
     * so the resolution picker dismisses.
     */
    fun startProxyCreation(videoId: String, targetHeight: Int) {
        _proxyCreationVideoId.value = null
        _activeProxyCreations.update { current ->
            current + (videoId to ProxyCreationState(
                videoId = videoId,
                progressPercent = 0.0,
                status = "started",
                message = if (targetHeight > 0) "Generating ${targetHeight}p proxy…"
                          else "Generating proxy…",
            ))
        }
        viewModelScope.launch {
            try {
                repository.generateProxy(videoId = videoId, targetHeight = targetHeight)
                    .collect { event ->
                        when (event.status) {
                            "complete" -> {
                                _activeProxyCreations.update { it - videoId }
                                loadVideos()
                            }
                            "error" -> {
                                _activeProxyCreations.update { it - videoId }
                                logger.warn("Proxy generation error for {}: {}", videoId, event.message)
                            }
                            else -> {
                                // Only push a UI update when the progress
                                // changes by ≥1 percentage point. This caps
                                // recompositions to ~85 during encoding instead
                                // of one per ffmpeg output frame, which prevents
                                // excessive recompositions from causing
                                // spurious pointer-exit events on card overlays.
                                val prev = _activeProxyCreations.value[videoId]
                                val percentDelta = event.progressPercent - (prev?.progressPercent ?: 0.0)
                                if (prev == null || prev.status != event.status || percentDelta >= 1.0) {
                                    _activeProxyCreations.update { current ->
                                        current + (videoId to ProxyCreationState(
                                            videoId = videoId,
                                            progressPercent = event.progressPercent,
                                            status = event.status,
                                            message = event.message,
                                        ))
                                    }
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                logger.warn("Proxy generation failed for {}", videoId, e)
                _activeProxyCreations.update { it - videoId }
            }
        }
    }

    // --- Inline grid playback ---

    /** ID of the video that is currently playing inline in the grid, or null. */
    private val _playingVideoId = MutableStateFlow<String?>(null)
    val playingVideoId: StateFlow<String?> = _playingVideoId.asStateFlow()

    /**
     * Override filesystem path for the playing card — set to a proxy path
     * when the video is oversize. null → use [VideoSummary.openPath].
     */
    private val _playingVideoPath = MutableStateFlow<String?>(null)
    val playingVideoPath: StateFlow<String?> = _playingVideoPath.asStateFlow()

    /** Begin inline playback for [videoId]. Replaces any previously playing card. */
    fun playVideo(videoId: String) {
        _playingVideoPath.value = null
        _playingVideoId.value = videoId
    }

    /**
     * Like [playVideo], but always picks the lowest-resolution proxy
     * whenever any is available — even for natively-playable masters.
     *
     * Rationale: inline grid/list playback is a hover-preview surface,
     * not a master-quality experience. The smallest proxy decodes
     * cheapest, leaves CPU + GPU headroom for a busy grid, and avoids
     * "the card is laggy" complaints on high-bitrate 4K masters that
     * the server *would* let us play but that the user's machine
     * struggles to decode in real time. The right panel / detail view
     * picker is where the user can explicitly choose the master.
     *
     * Falls back to the master path when no proxy exists or the gRPC
     * call fails.
     */
    fun playVideoPreferProxy(videoId: String) {
        val video = _videos.value.find { it.id == videoId }
        if (video == null || video.proxyCount == 0) {
            _playingVideoPath.value = null
            _playingVideoId.value = videoId
            return
        }
        viewModelScope.launch {
            try {
                val proxies = repository.listProxies(videoId)
                // listProxies returns sorted desc by pixel count; last = smallest.
                _playingVideoPath.value = proxies.lastOrNull()?.path
            } catch (_: Exception) {
                _playingVideoPath.value = null
            }
            _playingVideoId.value = videoId
        }
    }

    /** Stop inline playback and return the card to thumbnail mode. */
    fun stopPlayback() {
        _playingVideoId.value = null
        _playingVideoPath.value = null
    }

    /** Coalesces a burst of watcher events into a single `loadVideos()`. */
    private fun scheduleWatcherRefresh() {
        watcherRefreshJob?.cancel()
        watcherRefreshJob = viewModelScope.launch {
            delay(500)
            loadVideos()
            loadLibraryLocations()
        }
    }

    /**
     * Background refresh — used by scan-tick progress and watcher events.
     * Re-fetches the first page and replaces in place; does NOT clear the
     * current grid or show a full-page spinner, so periodic scan ticks
     * don't make the UI flicker every few seconds.
     */
    fun loadVideos() {
        reloadFromTop(showSpinner = false)
    }

    /**
     * User-initiated filter / sort / search change. Cancels any in-flight
     * load, clears the grid, and shows a spinner so the user sees the change
     * took effect immediately instead of staring at stale data while the new
     * fetch is in flight.
     */
    private fun reloadForFilterChange() {
        // If the active selection no longer passes the (just-changed) filters,
        // drop it now — before reloadFromTop clears `videos` — so the loupe and
        // inspector don't strand the now-hidden video's details on screen.
        clearSelectionIfFilteredOut()
        reloadFromTop(showSpinner = true)
        // Every filter change also re-narrows the available metadata facets.
        scheduleFacetRefresh()
    }

    /**
     * Does [s] still satisfy the attribute + Lightroom-mark filters we can
     * evaluate client-side? Mirrors the daemon's `build_filter_clauses`
     * (core/src/db.rs) for exactly those filters, so a filter change can tell
     * when the current selection has just been filtered out of the grid
     * without waiting for the reload. Search / metadata / location / tag /
     * collection filters need the server and aren't checked here, so `true`
     * means only "not filtered out by anything we can see locally".
     */
    private fun summaryMatchesLocalFilters(s: VideoSummary): Boolean {
        when (_filterHasLocation.value) {
            com.reelvault.data.models.AttributeFilterState.Yes -> if (!s.hasLocation) return false
            com.reelvault.data.models.AttributeFilterState.No -> if (s.hasLocation) return false
            com.reelvault.data.models.AttributeFilterState.Any -> {}
        }
        when (_filterHasKeywords.value) {
            com.reelvault.data.models.AttributeFilterState.Yes -> if (s.tags.isEmpty()) return false
            com.reelvault.data.models.AttributeFilterState.No -> if (s.tags.isNotEmpty()) return false
            com.reelvault.data.models.AttributeFilterState.Any -> {}
        }
        when (_filterHasProxies.value) {
            com.reelvault.data.models.AttributeFilterState.Yes -> if (!s.hasProxies) return false
            com.reelvault.data.models.AttributeFilterState.No -> if (s.hasProxies) return false
            com.reelvault.data.models.AttributeFilterState.Any -> {}
        }
        // The daemon classifies "full resolution" via a (camera, w, h) tuple
        // set; the summary's already-classified status is the client-side
        // mirror, so Full ⇔ in that set and anything else (incl. Unspecified)
        // counts as "not full".
        when (_filterFullResolution.value) {
            com.reelvault.data.models.AttributeFilterState.Yes ->
                if (s.fullResolution != com.reelvault.data.models.FullResolutionStatus.Full) return false
            com.reelvault.data.models.AttributeFilterState.No ->
                if (s.fullResolution == com.reelvault.data.models.FullResolutionStatus.Full) return false
            com.reelvault.data.models.AttributeFilterState.Any -> {}
        }
        if (_filterMinRating.value > 0 && s.rating < _filterMinRating.value) return false
        if (_filterColorLabel.value.isNotEmpty() && s.colorLabel != _filterColorLabel.value) return false
        return true
    }

    /**
     * Drop the primary selection when it no longer passes the active filters —
     * e.g. the user sets "location: no" while a geotagged video is selected, so
     * its card is about to leave the grid. The loupe keys off [selectedVideoId]
     * and the inspector is gated on it, so clearing here sends both back to
     * their "select a video" placeholder instead of stranding the now-hidden
     * video on screen. Call before the reload empties [videos] so the
     * selection's summary is still resolvable.
     */
    private fun clearSelectionIfFilteredOut() {
        val id = _selectedVideoId.value ?: return
        val summary = _videos.value.firstOrNull { it.id == id } ?: _selectedVideo.value ?: return
        if (!summaryMatchesLocalFilters(summary)) {
            clearSelection()
        }
    }

    /**
     * @param showSpinner When true, clear the current grid and flip into the
     *   loading state synchronously (filter-change path). When false, leave
     *   the existing rows in place and only swap them out once the fetch
     *   returns (background-refresh path).
     */
    private fun reloadFromTop(showSpinner: Boolean) {
        if (!showSpinner && _isLoading.value) {
            // A user-initiated filter change is still in flight (it set
            // isLoading = true). Don't let a scan-tick / watcher background
            // refresh interrupt it — that would re-cancel the user's load
            // and risk a spinner that never resolves under a busy scan.
            return
        }
        // Cancel any in-flight list load so a late response from the prior
        // selection / filter can't clobber the new one.
        listLoadJob?.cancel()
        if (showSpinner) {
            // Synchronously clear the old list and switch into the loading
            // state so the user sees the spinner immediately instead of
            // stale videos from the previous selection.
            _videos.value = emptyList()
            _totalCount.value = 0L
            _hasMore.value = false
            _isLoading.value = true
        }
        _error.value = null
        currentPage = 0
        // Reset stack expansion — representatives may have shifted/changed.
        collapseAllStacks()

        listLoadJob = viewModelScope.launch {
            try {
                val (videosList, totalCount) = repository.listVideos(
                    limit = pageSize,
                    offset = 0,
                    sortBy = sortBy,
                    sortAscending = sortAscending,
                    filterTags = filterTags,
                    collectionId = collectionId,
                    locationPath = locationPathFilter,
                    geoFilter = _filterLocation.value,
                    filterMinRating = _filterMinRating.value,
                    filterColorLabel = _filterColorLabel.value,
                    metadataFilters = activeMetadataFilters(),
                    searchQuery = _searchQuery.value,
                    hasLocation = _filterHasLocation.value,
                    hasKeywords = _filterHasKeywords.value,
                    hasProxies = _filterHasProxies.value,
                    fullResolution = _filterFullResolution.value,
                )

                _videos.value = videosList
                _totalCount.value = totalCount
                _hasMore.value = videosList.size < totalCount
                _isLoading.value = false

                logger.info("Loaded ${videosList.size} videos, total: $totalCount" +
                    if (locationPathFilter.isNotEmpty()) " (filtered to $locationPathFilter)" else "")

                // Keep map locations in sync with the active grid filters.
                // Debounced and run off the UI thread (see
                // scheduleVideoLocationsRefresh) so a burst of filter/selection
                // changes doesn't kick off a full-library re-pagination per
                // change on the UI dispatcher.
                scheduleVideoLocationsRefresh()
            } catch (e: CancellationException) {
                // Superseded by a newer load — leave state alone so the
                // newer load owns isLoading / videos.
                throw e
            } catch (e: Exception) {
                _error.value = "Failed to load videos: ${e.message}"
                _isLoading.value = false
                logger.error("Failed to load videos", e)
            }
        }
    }

    fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return

        _isLoading.value = true
        _error.value = null

        // Snapshot the offset *before* launching the coroutine. Using the
        // actual list size (not currentPage * pageSize) means that even if a
        // concurrent loadVideos() resets currentPage = 0 while this
        // coroutine is suspended at the gRPC call, we still fetch the
        // correct next page — preventing the duplicate-key crash that
        // occurred when currentPage was reset mid-flight.
        val offset = _videos.value.size
        currentPage = offset / pageSize  // keep counter in sync

        listLoadJob = viewModelScope.launch {
            try {
                val (newVideos, totalCount) = repository.listVideos(
                    limit = pageSize,
                    offset = offset,
                    sortBy = sortBy,
                    sortAscending = sortAscending,
                    filterTags = filterTags,
                    collectionId = collectionId,
                    locationPath = locationPathFilter,
                    geoFilter = _filterLocation.value,
                    filterMinRating = _filterMinRating.value,
                    filterColorLabel = _filterColorLabel.value,
                    metadataFilters = activeMetadataFilters(),
                    searchQuery = _searchQuery.value,
                    hasLocation = _filterHasLocation.value,
                    hasKeywords = _filterHasKeywords.value,
                    hasProxies = _filterHasProxies.value,
                    fullResolution = _filterFullResolution.value,
                )

                // Deduplicate as a belt-and-suspenders guard: if loadVideos()
                // ran and refreshed the list while this coroutine was
                // suspended, the append could otherwise create duplicate IDs.
                val existing = _videos.value
                val merged = existing + newVideos.filter { n -> existing.none { it.id == n.id } }
                _videos.value = merged
                _totalCount.value = totalCount
                _hasMore.value = merged.size < totalCount
                _isLoading.value = false

                logger.info("Loaded more videos, total now: ${merged.size}/$totalCount")
            } catch (e: CancellationException) {
                // Superseded by loadVideos() — leave state alone so the
                // newer load owns isLoading / videos.
                throw e
            } catch (e: Exception) {
                _error.value = "Failed to load more videos: ${e.message}"
                _isLoading.value = false
                logger.error("Failed to load more videos", e)
            }
        }
    }

    /**
     * Plain click: replace the selection with this single video. Also resets
     * the range-selection anchor to this video.
     */
    fun selectVideo(video: VideoSummary) {
        _selectedVideoIds.value = listOf(video.id)
        _anchorVideoId.value = video.id
        _selectedVideoId.value = video.id
        _selectedVideo.value = video
        logger.info("Replace-select: ${video.filename}")
    }

    /**
     * Cmd/Ctrl click: toggle this video's membership in the selection. Sets
     * a new anchor only when adding (not when removing).
     */
    fun toggleVideoSelection(video: VideoSummary) {
        val current = _selectedVideoIds.value
        if (video.id in current) {
            _selectedVideoIds.value = current.filter { it != video.id }
            // If we just removed the anchor, pick a new one (last remaining) or null.
            if (_anchorVideoId.value == video.id) {
                _anchorVideoId.value = _selectedVideoIds.value.lastOrNull()
            }
            logger.info("Toggle-off: ${video.filename}")
        } else {
            _selectedVideoIds.value = current + video.id
            _anchorVideoId.value = video.id
            logger.info("Toggle-on: ${video.filename}")
        }
        _selectedVideoId.value = video.id
        _selectedVideo.value = video
    }

    /**
     * Shift click: replace the selection with the range of videos from the
     * existing anchor to [target] (inclusive). The caller computes [rangeIds]
     * from whatever visual order the grid is using (which includes expanded
     * stack children inline). The anchor is left unchanged so subsequent
     * shift-clicks pivot from the same starting point.
     */
    fun selectRange(target: VideoSummary, rangeIds: List<String>) {
        if (rangeIds.isEmpty()) {
            selectVideo(target)
            return
        }
        _selectedVideoIds.value = rangeIds
        _selectedVideoId.value = target.id
        _selectedVideo.value = target
        // anchor intentionally not updated
        logger.info("Range-select: ${rangeIds.size} videos, target=${target.filename}")
    }

    fun clearSelection() {
        _selectedVideoId.value = null
        _selectedVideo.value = null
        _selectedVideoIds.value = emptyList()
        _anchorVideoId.value = null
    }

    fun deselectVideo() {
        clearSelection()
        logger.info("Deselected video")
    }

    /**
     * Move the "active" card to [video] without disturbing the multi-selection
     * or the anchor. Used by arrow-key navigation when a multi-selection is in
     * effect: the highlighted card steps through the selection while every
     * selected card stays selected (Lightroom-style).
     */
    fun setActiveVideo(video: VideoSummary) {
        _selectedVideoId.value = video.id
        _selectedVideo.value = video
    }

    /**
     * Arrow-key navigation. Moves the active card one step in [dir] over the
     * visual order reported via [setNavContext]. With a multi-selection, the
     * move is confined to the selected cards (stops at the selection's edges);
     * otherwise it replace-selects the neighbouring card. Returns the card
     * navigated to so the caller can sync the detail panel, or null on a no-op.
     */
    fun moveSelection(dir: NavDirection): VideoSummary? {
        val order = navVideos
        if (order.isEmpty()) return null
        val currentId = _selectedVideoId.value
        val curIdx = if (currentId != null) order.indexOfFirst { it.id == currentId } else -1
        // Nothing active yet → an arrow key drops onto the first card.
        if (curIdx < 0) {
            val first = order.first()
            selectVideo(first)
            return first
        }
        val multi = _selectedVideoIds.value
        if (multi.size > 1) {
            // Confined to the selection. Left/Right step through the whole
            // selection in visual order — wrapping to the prev/next row at a
            // row boundary — so a multi-row selection is fully traversable
            // without Up/Down. Up/Down stay geometric (±one row) and only
            // land on a selected card.
            val target = when (dir) {
                NavDirection.Left, NavDirection.Right ->
                    nextSelectedIndex(order, curIdx, multi.toSet(), forward = dir == NavDirection.Right)
                else -> {
                    val t = navTargetIndex(curIdx, order.size, navColumns, dir)
                    if (t >= 0 && order[t].id in multi) t else -1
                }
            }
            if (target < 0) return null
            setActiveVideo(order[target])
            return order[target]
        }
        // Single / no selection: move over the whole grid and replace-select.
        val target = navTargetIndex(curIdx, order.size, navColumns, dir)
        if (target < 0) return null
        selectVideo(order[target])
        return order[target]
    }

    /**
     * Shift+arrow: extend the selection from the anchor (the originally-clicked
     * / last plain-selected card) to where the arrow moves the active end —
     * the same flat range a shift-click produces. The anchor is left in place
     * so repeated Shift+arrows pivot from it. Returns the new active card, or
     * null on a no-op (edge of the grid / empty).
     */
    fun extendSelection(dir: NavDirection): VideoSummary? {
        val order = navVideos
        if (order.isEmpty()) return null
        val currentId = _selectedVideoId.value
        val curIdx = if (currentId != null) order.indexOfFirst { it.id == currentId } else -1
        // Nothing active yet → behave like a plain move onto the first card.
        if (curIdx < 0) {
            val first = order.first()
            selectVideo(first)
            return first
        }
        // Move the active (moving) end with the same grid geometry as a plain
        // move; the selection spans anchor → there.
        val target = navTargetIndex(curIdx, order.size, navColumns, dir)
        if (target < 0) return null
        val anchorId = _anchorVideoId.value ?: currentId
        val anchorIdx = order.indexOfFirst { it.id == anchorId }.let { if (it < 0) curIdx else it }
        val lo = minOf(anchorIdx, target)
        val hi = maxOf(anchorIdx, target)
        val rangeIds = order.subList(lo, hi + 1).map { it.id }
        // selectRange keeps the anchor and sets the active end to [target].
        selectRange(order[target], rangeIds)
        return order[target]
    }

    fun setSearchQuery(query: String) {
        if (_searchQuery.value == query) return
        _searchQuery.value = query
        reloadForFilterChange()
    }

    fun clearSearch() {
        if (_searchQuery.value.isEmpty()) return
        _searchQuery.value = ""
        reloadForFilterChange()
    }

    fun setSort(field: String, ascending: Boolean) {
        sortBy = field
        sortAscending = ascending
        _currentSortField.value = field
        _currentSortAscending.value = ascending
        // Persist so the next launch restores this sort (see property init).
        uiPrefs.put(PREF_SORT_FIELD, field)
        uiPrefs.putBoolean(PREF_SORT_ASCENDING, ascending)
        reloadForFilterChange()
    }

    /** Load the list of library locations from the backend (with per-directory counts). */
    fun loadLibraryLocations() {
        viewModelScope.launch {
            try {
                val locations = repository.listLibraryLocations()
                _libraryLocations.value = locations
                logger.info("Loaded ${locations.size} library locations")
            } catch (e: Exception) {
                logger.warn("Failed to load library locations", e)
            }
        }
    }

    /**
     * Remove a library location from the catalog. All videos indexed from that
     * location are also removed from the catalog (the files on disk are safe).
     * Clears the location filter if it was pointing at the removed path.
     */
    fun removeLibraryLocation(path: String) {
        viewModelScope.launch {
            try {
                val success = repository.removeLibraryLocation(path)
                if (success) {
                    // Drop the removed directory from the selection if present.
                    if (path in _selectedLocationPaths.value) {
                        applyLocationSelection(
                            _selectedLocationPaths.value - path,
                            anchor = null,
                        )
                    }
                    loadLibraryLocations()
                    loadVideos()
                    logger.info("Removed library location: $path")
                } else {
                    _error.value = "Failed to remove library location"
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove library location: ${e.message}"
                logger.error("Failed to remove library location", e)
            }
        }
    }

    /**
     * Narrow the grid to videos within [path] (recursive). Pass an empty string
     * to clear the filter and show all videos.
     */
    fun setLocationFilter(path: String) {
        // Plain click: replace the selection with this single directory (or
        // clear it for the "All Videos" entry, path == "").
        val paths = if (path.isEmpty()) emptyList() else listOf(path)
        applyLocationSelection(paths, anchor = path.ifEmpty { null })
    }

    /** Cmd/Ctrl-click: toggle this directory's membership in the selection. */
    fun toggleLocationFilter(path: String) {
        if (path.isEmpty()) { setLocationFilter(""); return }
        val current = _selectedLocationPaths.value
        val next = if (path in current) current - path else current + path
        applyLocationSelection(next, anchor = path)
    }

    /**
     * Shift-click: select every directory between the anchor and [path]
     * (inclusive) in the library's display order. Falls back to a plain
     * select when there's no usable anchor.
     */
    fun selectLocationRange(path: String) {
        if (path.isEmpty()) { setLocationFilter(""); return }
        val order = _libraryLocations.value.map { it.path }
        val anchor = locationAnchorPath ?: _selectedLocationPaths.value.firstOrNull() ?: path
        val ai = order.indexOf(anchor)
        val ti = order.indexOf(path)
        if (ai < 0 || ti < 0) { setLocationFilter(path); return }
        val (s, e) = if (ai <= ti) ai to ti else ti to ai
        // Anchor intentionally preserved so successive shift-clicks pivot
        // from the same start, matching the video-grid range behaviour.
        applyLocationSelection(order.subList(s, e + 1).toList(), anchor = anchor, keepAnchor = true)
    }

    private fun applyLocationSelection(
        paths: List<String>,
        anchor: String?,
        keepAnchor: Boolean = false,
    ) {
        val joined = paths.joinToString("\n")
        if (joined == locationPathFilter && paths == _selectedLocationPaths.value) return
        locationPathFilter = joined
        _selectedLocationPaths.value = paths
        _selectedLocationPath.value = paths.firstOrNull() ?: ""
        if (!keepAnchor) locationAnchorPath = anchor
        reloadForFilterChange()
    }

    /** Load (or refresh) the full list of keywords/tags with their usage counts. */
    fun loadTags() {
        viewModelScope.launch {
            try {
                _tags.value = repository.listTags().sortedBy { it.name.lowercase() }
                logger.info("Loaded ${_tags.value.size} tags")
            } catch (e: Exception) {
                logger.warn("Failed to load tags", e)
            }
        }
    }

    /** Narrow the grid to videos tagged with [tagId]. Empty string clears the filter. */
    fun setTagFilter(tagId: String) {
        if (_filterTagId.value == tagId) return
        _filterTagId.value = tagId
        filterTags = if (tagId.isEmpty()) emptyList() else listOf(tagId)
        reloadForFilterChange()
    }

    // --- Library Filter: attribute mode (rating + colour) ---

    fun setMinRatingFilter(n: Int) {
        if (_filterMinRating.value == n) return
        _filterMinRating.value = n
        reloadForFilterChange()
    }

    fun setColorLabelFilter(label: String) {
        if (_filterColorLabel.value == label) return
        _filterColorLabel.value = label
        reloadForFilterChange()
    }

    fun setHasLocationFilter(state: com.reelvault.data.models.AttributeFilterState) {
        if (_filterHasLocation.value == state) return
        _filterHasLocation.value = state
        reloadForFilterChange()
    }

    fun setHasKeywordsFilter(state: com.reelvault.data.models.AttributeFilterState) {
        if (_filterHasKeywords.value == state) return
        _filterHasKeywords.value = state
        reloadForFilterChange()
    }

    fun setHasProxiesFilter(state: com.reelvault.data.models.AttributeFilterState) {
        if (_filterHasProxies.value == state) return
        _filterHasProxies.value = state
        reloadForFilterChange()
    }

    fun setFullResolutionFilter(state: com.reelvault.data.models.AttributeFilterState) {
        if (_filterFullResolution.value == state) return
        _filterFullResolution.value = state
        reloadForFilterChange()
    }

    // --- Library Filter: mode + metadata columns ---

    /** Switch which Library Filter editor is visible. Clear is a momentary
     *  action that resets the whole library filter; the others stay applied
     *  across mode switches (COMBINE semantics). */
    fun setLibraryFilterMode(mode: com.reelvault.data.models.LibraryFilterMode) {
        if (mode == com.reelvault.data.models.LibraryFilterMode.Clear) {
            clearLibraryFilter()
            return
        }
        if (_libraryFilterMode.value == mode) return
        _libraryFilterMode.value = mode
        if (mode == com.reelvault.data.models.LibraryFilterMode.Metadata) scheduleFacetRefresh()
    }

    /** The active metadata-column constraints sent to the daemon. Each column
     *  with a non-empty selection becomes one filter whose value is its selected
     *  tokens joined by [METADATA_VALUE_SEPARATOR]; the daemon OR-matches them. */
    private fun activeMetadataFilters(): List<com.reelvault.data.models.MetadataFilter> =
        _metadataColumns.value
            .filter { it.key.isNotEmpty() && it.values.isNotEmpty() }
            .map {
                com.reelvault.data.models.MetadataFilter(
                    it.key,
                    it.values.joinToString(com.reelvault.data.models.METADATA_VALUE_SEPARATOR),
                )
            }

    /** Handle a click on facet [token] in metadata column [index]. [token] ==
     *  "" is the "All" row (clears the column). [shift] / [toggle] carry the
     *  keyboard modifiers (toggle = Ctrl on Windows/Linux, Cmd on macOS).
     *
     *  Lightroom / file-manager multi-select semantics:
     *   - "All" selected (empty set) + any value → select only that value,
     *     regardless of modifiers.
     *   - toggle-click → flip that value's membership; empties back to "All".
     *   - shift-click → select the contiguous range from the anchor to the
     *     clicked value (in the displayed facet order).
     *   - plain click → select only that value.
     *  Selected values within one column are OR-ed by the daemon. */
    fun onMetadataValueClicked(index: Int, token: String, shift: Boolean, toggle: Boolean) {
        val cur = _metadataColumns.value
        val col = cur.getOrNull(index) ?: return
        val newCol = when {
            token.isEmpty() ->
                col.copy(values = emptySet(), anchor = "")
            col.values.isEmpty() ->
                col.copy(values = setOf(token), anchor = token)
            toggle -> {
                val next = col.values.toMutableSet().apply { if (!add(token)) remove(token) }
                col.copy(values = next, anchor = token)
            }
            shift -> {
                val order = _metadataFacets.value.getOrNull(index)?.values?.map { it.token } ?: emptyList()
                val anchorTok = col.anchor.ifEmpty { col.values.firstOrNull() ?: token }
                val ai = order.indexOf(anchorTok)
                val ci = order.indexOf(token)
                if (ai >= 0 && ci >= 0) {
                    col.copy(values = order.subList(minOf(ai, ci), maxOf(ai, ci) + 1).toSet(), anchor = anchorTok)
                } else {
                    col.copy(values = setOf(token), anchor = token)
                }
            }
            else ->
                col.copy(values = setOf(token), anchor = token)
        }
        if (newCol == col) return
        _metadataColumns.value = cur.toMutableList().also { it[index] = newCol }
        reloadForFilterChange()
    }

    /** Change the metadata key of column [index]; resets its selected value. */
    fun setMetadataColumnKey(index: Int, key: String) {
        val cur = _metadataColumns.value
        val col = cur.getOrNull(index) ?: return
        if (col.key == key) return
        val hadActiveValue = col.key.isNotEmpty() && col.values.isNotEmpty()
        _metadataColumns.value = cur.toMutableList()
            .also { it[index] = com.reelvault.data.models.MetadataColumn(key = key) }
        LibraryFilterPrefs.saveColumns(_metadataColumns.value)
        // Grid only changes if this column was actively filtering; otherwise
        // just re-fetch facets so the new key's values appear.
        if (hadActiveValue) reloadForFilterChange() else scheduleFacetRefresh()
    }

    /** Insert a new (empty) metadata column at the front or the end. */
    fun addMetadataColumn(atFront: Boolean) {
        val cur = _metadataColumns.value.toMutableList()
        val newCol = com.reelvault.data.models.MetadataColumn()
        if (atFront) cur.add(0, newCol) else cur.add(newCol)
        _metadataColumns.value = cur
        LibraryFilterPrefs.saveColumns(cur)
        scheduleFacetRefresh()
    }

    /** Remove metadata column [index]. No-op when only one column remains. */
    fun removeMetadataColumn(index: Int) {
        val cur = _metadataColumns.value
        if (cur.size <= 1) return
        val removed = cur.getOrNull(index) ?: return
        _metadataColumns.value = cur.toMutableList().also { it.removeAt(index) }
        LibraryFilterPrefs.saveColumns(_metadataColumns.value)
        if (removed.key.isNotEmpty() && removed.values.isNotEmpty()) reloadForFilterChange()
        else scheduleFacetRefresh()
    }

    /** Reset the Library Filter (search + attribute + metadata values) so all
     *  videos show, subject to the higher-level location / keyword filters. The
     *  metadata column layout (keys/order) is preserved. */
    fun clearLibraryFilter() {
        var changed = false
        if (_searchQuery.value.isNotEmpty()) { _searchQuery.value = ""; changed = true }
        if (_filterMinRating.value != 0) { _filterMinRating.value = 0; changed = true }
        if (_filterColorLabel.value.isNotEmpty()) { _filterColorLabel.value = ""; changed = true }
        if (_filterLocation.value != null) { _filterLocation.value = null; changed = true }
        val anyAttr = com.reelvault.data.models.AttributeFilterState.Any
        if (_filterHasLocation.value != anyAttr) { _filterHasLocation.value = anyAttr; changed = true }
        if (_filterHasKeywords.value != anyAttr) { _filterHasKeywords.value = anyAttr; changed = true }
        if (_filterHasProxies.value != anyAttr) { _filterHasProxies.value = anyAttr; changed = true }
        if (_filterFullResolution.value != anyAttr) { _filterFullResolution.value = anyAttr; changed = true }
        if (_metadataColumns.value.any { it.values.isNotEmpty() }) {
            _metadataColumns.value = _metadataColumns.value.map { it.copy(values = emptySet(), anchor = "") }
            changed = true
        }
        // Clear is a resting mode: it stays selected and shows nothing below.
        _libraryFilterMode.value = com.reelvault.data.models.LibraryFilterMode.Clear
        if (changed) reloadForFilterChange() else scheduleFacetRefresh()
    }

    /** Trigger an initial facet load (e.g. right after a catalog opens) so the
     *  metadata editor and key picker have data before the user opens them. */
    fun refreshMetadataFacets() = scheduleFacetRefresh()

    private var facetJob: kotlinx.coroutines.Job? = null

    /** Recompute the metadata facets (debounced, cancel-in-flight). The server
     *  owns the cascade, so we always send the full ordered column list and
     *  replace the results wholesale. */
    private fun scheduleFacetRefresh() {
        facetJob?.cancel()
        facetJob = viewModelScope.launch {
            kotlinx.coroutines.delay(180)
            try {
                val result = repository.getMetadataFacets(
                    locationPath = locationPathFilter,
                    filterTags = filterTags,
                    collectionId = collectionId,
                    geoFilter = _filterLocation.value,
                    filterMinRating = _filterMinRating.value,
                    filterColorLabel = _filterColorLabel.value,
                    searchQuery = _searchQuery.value,
                    columns = _metadataColumns.value,
                    hasLocation = _filterHasLocation.value,
                    hasKeywords = _filterHasKeywords.value,
                    hasProxies = _filterHasProxies.value,
                    fullResolution = _filterFullResolution.value,
                )
                _metadataFacets.value = result.columns
                _metadataAvailableKeys.value = result.availableKeys
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to refresh metadata facets", e)
            }
        }
    }

    /** Persists only the metadata column *layout* (the ordered keys) across
     *  sessions; selected values are intentionally transient. */
    private object LibraryFilterPrefs {
        private val prefs = java.util.prefs.Preferences.userRoot().node("com/reelvault/libraryfilter")

        fun loadColumns(): List<com.reelvault.data.models.MetadataColumn> {
            val raw = prefs.get("columns", "")
            if (raw.isBlank()) return com.reelvault.data.models.defaultMetadataColumns
            val cols = raw.split(",").filter { it.isNotBlank() }
                .map { com.reelvault.data.models.MetadataColumn(it) }
            return cols.ifEmpty { com.reelvault.data.models.defaultMetadataColumns }
        }

        fun saveColumns(cols: List<com.reelvault.data.models.MetadataColumn>) {
            prefs.put("columns", cols.filter { it.key.isNotEmpty() }.joinToString(",") { it.key })
        }
    }

    // --- Lightroom-style user marks ---

    /** After an optimistic rating/colour change, drop any cached video that no
     *  longer satisfies the active Lightroom mark-filters so it leaves the view
     *  immediately, instead of lingering until the next watcher-driven refresh
     *  (tens of seconds out on a slow NAS). Additions — a video that now matches
     *  the filter — still surface on the next reload, since they aren't in the
     *  loaded page to begin with. */
    private fun pruneVideosFailingMarkFilters() {
        val colorFilter = _filterColorLabel.value
        val minRating = _filterMinRating.value
        if (colorFilter.isEmpty() && minRating <= 0) return
        val before = _videos.value
        val after = before.filter { v ->
            (colorFilter.isEmpty() || v.colorLabel == colorFilter) &&
                (minRating <= 0 || v.rating >= minRating)
        }
        if (after.size != before.size) {
            _videos.value = after
            _totalCount.value = (_totalCount.value - (before.size - after.size)).coerceAtLeast(0)
        }
    }

    /** Apply a 0..5 star rating to the given videos. Optimistic local update
     *  followed by an RPC; the cached list is replaced in-place so the
     *  grid repaints immediately. */
    fun setRating(rating: Int, videoIds: List<String>) {
        val clamped = rating.coerceIn(0, 5)
        val ids = videoIds.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return
        _videos.value = _videos.value.map { v ->
            if (v.id in ids) v.copy(rating = clamped) else v
        }
        pruneVideosFailingMarkFilters()
        viewModelScope.launch {
            try {
                repository.updateVideoRating(ids.toList(), clamped)
            } catch (e: Exception) {
                logger.error("setRating failed: ${e.message}", e)
            }
        }
    }

    /** Apply a colour label to the given videos. Empty string clears. */
    fun setColorLabel(label: String, videoIds: List<String>) {
        val ids = videoIds.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return
        _videos.value = _videos.value.map { v ->
            if (v.id in ids) v.copy(colorLabel = label) else v
        }
        pruneVideosFailingMarkFilters()
        viewModelScope.launch {
            try {
                repository.updateVideoColorLabel(ids.toList(), label)
            } catch (e: Exception) {
                logger.error("setColorLabel failed: ${e.message}", e)
            }
        }
    }

    /** Apply the rating to the current selection. Wired from the keyboard
     *  handler for digits 0..5. Falls back to the anchor when no multi-select. */
    fun setRatingOnSelection(rating: Int) {
        val ids = if (_selectedVideoIds.value.isNotEmpty()) {
            _selectedVideoIds.value
        } else {
            _selectedVideoId.value?.let { listOf(it) } ?: emptyList()
        }
        setRating(rating, ids)
    }

    /** Apply the colour label to the current selection. Wired from the
     *  keyboard handler for digits 6..9 and backtick (clear).
     *
     *  Toggle semantics: pressing a colour hotkey when every selected
     *  video already carries that colour clears the colour from all of
     *  them. Pressing the same hotkey on a mixed / differently-labelled
     *  selection applies the colour uniformly. Explicit clear via
     *  backtick (`label == ""`) bypasses the toggle and always clears. */
    fun setColorLabelOnSelection(label: String) {
        val ids = if (_selectedVideoIds.value.isNotEmpty()) {
            _selectedVideoIds.value
        } else {
            _selectedVideoId.value?.let { listOf(it) } ?: emptyList()
        }
        if (ids.isEmpty()) return
        if (label.isEmpty()) {
            setColorLabel("", ids)
            return
        }
        val idSet = ids.toSet()
        val affected = _videos.value.filter { it.id in idSet }
        val allAlreadyHaveLabel = affected.isNotEmpty() &&
            affected.all { it.colorLabel == label }
        setColorLabel(if (allAlreadyHaveLabel) "" else label, ids)
    }

    // --- Grid layout settings (top-of-card stat slots) ---

    /** Pull the saved 4-slot configuration from the catalog. Defaults
     *  survive an RPC failure so the grid never starts broken. */
    fun loadGridSettings() {
        viewModelScope.launch {
            try {
                val raw = repository.getGridSettings()
                _topSlots.value = normaliseSlots(raw)
            } catch (e: Exception) {
                logger.warn("loadGridSettings failed: ${e.message}", e)
            }
        }
    }

    private var gridSettingsSaveJob: Job? = null

    /** Update one slot's stat key and persist the full set to the catalog. The
     *  local [topSlots] update is immediate (the cards repaint at once); the
     *  persistence RPC is debounced so reconfiguring several slots in quick
     *  succession collapses to a single write instead of one DB write per
     *  click — which on a slow NAS-backed catalog could otherwise back up. */
    fun updateGridTopSlot(slotIndex: Int, statKey: String) {
        if (slotIndex !in 0..3) return
        val current = normaliseSlots(_topSlots.value).toMutableList()
        current[slotIndex] = statKey
        _topSlots.value = current.toList()
        gridSettingsSaveJob?.cancel()
        gridSettingsSaveJob = viewModelScope.launch {
            delay(500)
            try {
                repository.updateGridSettings(_topSlots.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("updateGridSettings failed: ${e.message}", e)
            }
        }
    }

    /** Pad / truncate any list to exactly four entries. */
    private fun normaliseSlots(raw: List<String>): List<String> {
        val padded = raw.toMutableList()
        while (padded.size < 4) padded.add("")
        return padded.take(4)
    }

    /** Apply (or clear) the geographic proximity filter and reload the grid.
     *  Called when the user taps a pin on the global map. */
    fun setLocationFilter(latitude: Double?, longitude: Double?, radiusKm: Double = 1.0) {
        if (latitude != null && longitude != null) {
            _filterLocation.value = Triple(latitude, longitude, radiusKm)
            // A geographic filter means "videos at this place", which inherently
            // have a location — so drop a stale "location: no" (or "yes")
            // attribute filter that would otherwise contradict it and blank the
            // grid. Unlocated videos can't match a proximity radius anyway.
            _filterHasLocation.value = com.reelvault.data.models.AttributeFilterState.Any
        } else {
            _filterLocation.value = null
        }
        reloadForFilterChange()
    }

    /**
     * Refresh the list of known locations (named places + unnamed coordinate
     * clusters) with per-location video counts, for the Library Filter's
     * "Location" mode. Always fetches the full geotagged set so the list isn't
     * itself narrowed by whatever filter is currently active.
     */
    fun loadFilterLocations() {
        viewModelScope.launch {
            val locs = try {
                withContext(Dispatchers.IO) { repository.listVideosWithLocations() }
            } catch (e: Exception) {
                logger.error("Failed to load locations for the Location filter", e)
                return@launch
            }
            _filterLocationGroups.value = buildLocationFilterGroups(locs, _namedLocations.value)
        }
    }

    /**
     * Apply a proximity filter that frames exactly [videoIds] — used by the map
     * view's "open these here in grid/list". Centres on their centroid with a
     * radius covering the farthest member (plus a small margin), so only that
     * spot's videos show; unlocated videos lack coordinates and never match. A
     * named place containing the centroid lends its own radius instead.
     */
    fun filterToVideosLocation(videoIds: List<String>) {
        val idSet = videoIds.toSet()
        val pts = (_geotaggedVideos.value + _videos.value)
            .asSequence()
            .filter { it.id in idSet && it.hasLocation }
            .distinctBy { it.id }
            .map { it.gpsLatitude to it.gpsLongitude }
            .toList()
        if (pts.isEmpty()) return
        val cLat = pts.sumOf { it.first } / pts.size
        val cLon = pts.sumOf { it.second } / pts.size
        val named = nameForLocation(cLat, cLon)
        val radiusKm = if (named != null) {
            named.radiusMeters / 1000.0
        } else {
            val maxMeters = pts.maxOf { haversineMeters(cLat, cLon, it.first, it.second) }
            (maxMeters / 1000.0 + 0.1).coerceAtLeast(0.1)
        }
        setLocationFilter(cLat, cLon, radiusKm)
    }

    /** Group the catalog's geotagged videos into named places (counted within
     *  each place's radius) and unnamed coordinate clusters (~110 m buckets),
     *  with counts, sorted by popularity then label. */
    private fun buildLocationFilterGroups(
        locs: List<com.reelvault.data.models.VideoLocation>,
        named: List<com.reelvault.data.models.NamedLocation>,
    ): List<com.reelvault.data.models.LocationFilterGroup> {
        val groups = mutableListOf<com.reelvault.data.models.LocationFilterGroup>()
        val claimed = HashSet<String>()
        for (n in named) {
            val members = locs.filter {
                it.id !in claimed &&
                    haversineMeters(n.latitude, n.longitude, it.latitude, it.longitude) <= n.radiusMeters
            }
            if (members.isEmpty()) continue
            members.forEach { claimed += it.id }
            groups += com.reelvault.data.models.LocationFilterGroup(
                label = n.name, latitude = n.latitude, longitude = n.longitude,
                radiusKm = n.radiusMeters / 1000.0, count = members.size, isNamed = true,
            )
        }
        locs.filter { it.id !in claimed }
            .groupBy { "%.3f,%.3f".format(it.latitude, it.longitude) }
            .forEach { (_, members) ->
                val cLat = members.sumOf { it.latitude } / members.size
                val cLon = members.sumOf { it.longitude } / members.size
                groups += com.reelvault.data.models.LocationFilterGroup(
                    label = "%.4f, %.4f".format(cLat, cLon),
                    latitude = cLat, longitude = cLon,
                    radiusKm = 0.2, count = members.size, isNamed = false,
                )
            }
        return groups.sortedWith(
            compareByDescending<com.reelvault.data.models.LocationFilterGroup> { it.count }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Refresh the list of geotagged videos (used by the global-map screen). */
    fun loadVideoLocations() {
        viewModelScope.launch { loadVideoLocationsAsync() }
    }

    /** Suspending variant — callers that need the data populated *before*
     *  they take a UI action (opening a dialog, framing a map) can await
     *  this. Always hits the daemon; relies on the catalog-open pre-load
     *  to keep the call fast in steady state. */
    suspend fun loadVideoLocationsAsync() {
        try {
            _videoLocations.value = repository.listVideosWithLocations()
            logger.info("Loaded ${_videoLocations.value.size} geotagged videos")
        } catch (e: Exception) {
            logger.error("Failed to load video locations", e)
        }
    }

    /** Load GPS-tagged videos that match the current grid filters and update [videoLocations].
     *  Uses a large page size to minimise round-trips. Does NOT apply [_filterLocation] so
     *  the map pin list is unaffected by an active proximity circle — the map itself draws
     *  the circle. */
    fun loadVideoLocationsFiltered() {
        viewModelScope.launch { loadVideoLocationsFilteredAsync() }
    }

    private var locationsRefreshJob: Job? = null

    /** Debounced trigger for [loadVideoLocationsFilteredAsync]. Collapses a
     *  burst of grid reloads (rapid filtering, selection) into a single
     *  full-library pagination once activity settles, instead of one per
     *  reload. The map / location-picker dialogs that consume [videoLocations]
     *  are opened on demand, so a short delay before they're warm is harmless. */
    private fun scheduleVideoLocationsRefresh() {
        locationsRefreshJob?.cancel()
        locationsRefreshJob = viewModelScope.launch {
            delay(400)
            loadVideoLocationsFilteredAsync()
        }
    }

    suspend fun loadVideoLocationsFilteredAsync() {
        _isLoadingVideoLocations.value = true
        try {
            // The full-library pagination runs entirely off the UI thread: on a
            // slow NAS-backed catalog this can be several seconds of round-trips,
            // and it must never compete with grid recomposition / input handling
            // on the UI dispatcher.
            val accumulated = withContext(Dispatchers.IO) {
                val batchSize = 500
                val acc = mutableListOf<com.reelvault.data.models.VideoLocation>()
                val accVideos = mutableListOf<VideoSummary>()
                var offset = 0
                while (true) {
                    val (page, total) = repository.listVideos(
                        limit = batchSize,
                        offset = offset,
                        sortBy = sortBy,
                        sortAscending = sortAscending,
                        filterTags = filterTags,
                        collectionId = collectionId,
                        locationPath = locationPathFilter,
                        geoFilter = null,
                        filterMinRating = _filterMinRating.value,
                        filterColorLabel = _filterColorLabel.value,
                        metadataFilters = activeMetadataFilters(),
                        searchQuery = _searchQuery.value,
                        // The map only ever plots located videos, so force
                        // has-location here rather than honoring the attribute
                        // filter (which is hidden in map mode) — otherwise a
                        // stale "location: no" from grid/list would empty it.
                        hasLocation = com.reelvault.data.models.AttributeFilterState.Yes,
                        hasKeywords = _filterHasKeywords.value,
                        hasProxies = _filterHasProxies.value,
                        fullResolution = _filterFullResolution.value,
                    )
                    page.filter { it.hasLocation }.forEach { v ->
                        acc += com.reelvault.data.models.VideoLocation(
                            id = v.id,
                            filename = v.filename,
                            path = v.path,
                            latitude = v.gpsLatitude,
                            longitude = v.gpsLongitude,
                            hasThumbnail = v.hasThumbnail
                        )
                        accVideos += v
                    }
                    offset += page.size
                    if (page.isEmpty() || offset >= total) break
                }
                acc to accVideos
            }
            _videoLocations.value = accumulated.first
            _geotaggedVideos.value = accumulated.second
            logger.info("Loaded ${accumulated.first.size} geotagged videos (filtered)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to load filtered video locations", e)
        } finally {
            _isLoadingVideoLocations.value = false
        }
    }

    /** Refresh the catalog's named-location list. Cheap (a few hundred
     *  rows at most for typical libraries) so we never paginate. */
    fun loadNamedLocations() {
        viewModelScope.launch { loadNamedLocationsAsync() }
    }

    /** Suspending variant of [loadNamedLocations]. */
    suspend fun loadNamedLocationsAsync() {
        try {
            _namedLocations.value = repository.listNamedLocations()
        } catch (e: Exception) {
            logger.error("Failed to load named locations", e)
        }
    }

    /** Resolve a (lat, lon) into the nearest named place within its
     *  `radiusMeters`, or null if no entry is close enough. Linear scan —
     *  the named-location list is small. */
    fun nameForLocation(latitude: Double, longitude: Double): com.reelvault.data.models.NamedLocation? {
        val list = _namedLocations.value
        if (list.isEmpty()) return null
        var bestLoc: com.reelvault.data.models.NamedLocation? = null
        var bestDist = Double.MAX_VALUE
        for (loc in list) {
            val d = haversineMeters(latitude, longitude, loc.latitude, loc.longitude)
            if (d <= loc.radiusMeters && d < bestDist) {
                bestLoc = loc
                bestDist = d
            }
        }
        return bestLoc
    }

    /** Upsert a named location on the daemon and (on success) merge the
     *  returned row into the local cache so the UI sees it immediately. */
    fun saveNamedLocation(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 250.0,
        onComplete: (com.reelvault.data.models.NamedLocation?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val saved = repository.upsertNamedLocation(id, name, latitude, longitude, radiusMeters)
            if (saved != null) {
                val current = _namedLocations.value.toMutableList()
                val idx = current.indexOfFirst { it.id == saved.id }
                if (idx >= 0) current[idx] = saved else current.add(saved)
                current.sortBy { it.name.lowercase() }
                _namedLocations.value = current
            }
            onComplete(saved)
        }
    }

    /** Great-circle distance in meters between two (lat, lon) pairs.
     *  Standard haversine — accurate enough for the sub-kilometer
     *  resolution we need to resolve names. */
    private fun haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
    ): Double {
        val earthRadius = 6_371_000.0
        val toRad = Math.PI / 180.0
        val dLat = (lat2 - lat1) * toRad
        val dLon = (lon2 - lon1) * toRad
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1 * toRad) * kotlin.math.cos(lat2 * toRad) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Select every video that's currently visible in the grid: each loaded
     * representative plus, when its stack is expanded, all of its visible
     * members. Mirrors what the user actually sees as cards. Drives the
     * Cmd/Ctrl+A shortcut.
     */
    fun selectAllVisible() {
        val ids = LinkedHashSet<String>()
        val expandedIds = _expandedGroupIds.value
        val members = _expandedGroupMembers.value
        for (v in _videos.value) {
            ids.add(v.id)
            if (v.groupId.isNotEmpty() && v.groupId in expandedIds) {
                members[v.groupId]?.forEach { ids.add(it.id) }
            }
        }
        val list = ids.toList()
        _selectedVideoIds.value = list
        // Make sure the detail panel + anchor reflect the new selection.
        _selectedVideoId.value = list.firstOrNull()
        _anchorVideoId.value = list.firstOrNull()
    }

    /** Persist a new GPS location on every video in [videoIds]. After all
     *  writes complete the grid is reloaded so EXIF + filter dropdowns
     *  refresh. When a target video belongs to a collapsed stack the
     *  location is applied to all members of that stack. */
    fun setVideoLocations(
        videoIds: List<String>,
        latitude: Double,
        longitude: Double,
        writeToFile: Boolean,
        onComplete: () -> Unit = {},
    ) {
        if (videoIds.isEmpty()) return
        viewModelScope.launch {
            val finalIds = expandForCollapsedStacks(videoIds)
            // Optimistic update so the location badge appears immediately —
            // both on the grid representatives and on cached stack members
            // (so the badge shows on every card when the stack is expanded).
            val idSet = finalIds.toSet()
            // Optimistically apply the new GPS, then drop any video the change
            // pushes out of the active filter (e.g. it gained a location under
            // "location: no", or lost one under "location: yes"). Doing it here
            // — rather than leaning on the background reload — keeps the grid
            // correct the instant the location is set: the located card leaves
            // and the section's remaining unlocated videos stay put, instead of
            // the whole grid blanking until the user re-picks the library
            // section. The reload below still reconciles against the server.
            val relocated = _videos.value.map { v ->
                if (v.id in idSet) v.copy(gpsLatitude = latitude, gpsLongitude = longitude) else v
            }
            val keptVideos = relocated.filter { summaryMatchesLocalFilters(it) }
            val droppedCount = relocated.size - keptVideos.size
            _videos.value = keptVideos
            if (droppedCount > 0) {
                _totalCount.value = (_totalCount.value - droppedCount).coerceAtLeast(0)
            }
            _expandedGroupMembers.value = _expandedGroupMembers.value.mapValues { (_, members) ->
                members.map { v ->
                    if (v.id in idSet) v.copy(gpsLatitude = latitude, gpsLongitude = longitude) else v
                }.filter { summaryMatchesLocalFilters(it) }
            }
            // Mirror the optimistic GPS change onto the cached selection summary,
            // then drop the selection if the just-changed video no longer passes
            // the active filter, so the inspector doesn't strand its stale
            // details. Same intent as the filter-change clearSelectionIfFilteredOut().
            _selectedVideo.value?.let { sel ->
                if (sel.id in idSet) {
                    _selectedVideo.value = sel.copy(gpsLatitude = latitude, gpsLongitude = longitude)
                }
            }
            clearSelectionIfFilteredOut()
            var ok = 0
            for (id in finalIds) {
                if (repository.updateVideoLocation(id, latitude, longitude, 0.0, writeToFile)) ok++
            }
            logger.info("Updated location on $ok/${finalIds.size} video(s)")
            loadVideoLocations()
            loadVideos()
            onComplete()
        }
    }

    /** Remove the GPS location from every video in [videoIds]. Clearing is
     *  done by writing lat/lon 0.0 which the catalog treats as "no location". */
    fun clearVideoLocations(videoIds: List<String>, onComplete: () -> Unit = {}) {
        setVideoLocations(videoIds, 0.0, 0.0, false, onComplete)
    }

    /** Persist a new capture timestamp (Unix ms, UTC) on every video in
     *  [videoIds]. Reloads the grid + filter options afterwards so the
     *  year-filter dropdown picks up the new dates. */
    fun setVideoCaptureDates(
        videoIds: List<String>,
        timestampMs: Long,
        writeToFile: Boolean,
        onComplete: () -> Unit = {},
    ) {
        if (videoIds.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            for (id in videoIds) {
                if (repository.updateVideoCaptureDate(id, timestampMs, writeToFile)) ok++
            }
            logger.info("Updated capture date on $ok/${videoIds.size} video(s)")
            scheduleFacetRefresh()
            loadVideos()
            onComplete()
        }
    }

    /**
     * Expand [videoIds] so that any video in a COLLAPSED stack is replaced
     * by all of its stack members. Videos in an expanded stack or not in a
     * stack are left as-is. Used by keyword and location operations so the
     * action fans out to every member when the user acts on a collapsed card.
     */
    private suspend fun expandForCollapsedStacks(videoIds: List<String>): List<String> {
        val result = mutableSetOf<String>()
        for (id in videoIds) {
            val video = _videos.value.firstOrNull { it.id == id }
            if (video != null && video.isInGroup && video.groupId !in _expandedGroupIds.value) {
                val groupId = video.groupId
                val cached = _expandedGroupMembers.value[groupId]
                val members = cached ?: try {
                    val (m, _) = repository.listGroupMembers(groupId)
                    _expandedGroupMembers.value = _expandedGroupMembers.value + (groupId to m)
                    m
                } catch (e: Exception) { null }
                if (members != null) {
                    members.forEach { result.add(it.id) }
                } else {
                    result.add(id)
                }
            } else {
                result.add(id)
            }
        }
        return result.toList()
    }

    /**
     * Apply [keyword] to every video in [videoIds]. If the tag doesn't exist yet
     * it's created. Refreshes the tag list (for updated counts) and the
     * detail-panel metadata afterwards. When a target video belongs to a
     * collapsed stack the keyword is applied to all members of that stack.
     */
    fun applyKeyword(keyword: String, videoIds: List<String>, onComplete: () -> Unit = {}) {
        val name = keyword.trim()
        if (name.isEmpty() || videoIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val finalIds = expandForCollapsedStacks(videoIds)
                // Optimistic update so the keyword badge appears immediately —
                // mirrored into the cached stack members so the badge also
                // shows on every member when the stack is expanded next.
                val idSet = finalIds.toSet()
                _videos.value = _videos.value.map { v ->
                    if (v.id in idSet && name !in v.tags) v.copy(tags = v.tags + name) else v
                }
                _expandedGroupMembers.value = _expandedGroupMembers.value.mapValues { (_, members) ->
                    members.map { v ->
                        if (v.id in idSet && name !in v.tags) v.copy(tags = v.tags + name) else v
                    }
                }
                val tag = repository.createTag(name)
                if (tag == null) {
                    _error.value = "Failed to create/get tag '$name'"
                    return@launch
                }
                if (!repository.tagVideos(finalIds, tag.id)) {
                    _error.value = "Failed to apply '$name'"
                    return@launch
                }
                loadTags()
                onComplete()
            } catch (e: Exception) {
                _error.value = "Apply keyword failed: ${e.message}"
                logger.error("applyKeyword failed", e)
            }
        }
    }

    /** Remove [tagId] from every video in [videoIds]. When a target video
     *  belongs to a collapsed stack the keyword is removed from all members. */
    fun removeKeyword(tagId: String, videoIds: List<String>, onComplete: () -> Unit = {}) {
        if (tagId.isEmpty() || videoIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val finalIds = expandForCollapsedStacks(videoIds)
                // Optimistic update so the badge clears immediately — also
                // applied to the cached stack members so the change reflects
                // when the stack is expanded.
                val tagName = _tags.value.firstOrNull { it.id == tagId }?.name
                if (tagName != null) {
                    val idSet = finalIds.toSet()
                    _videos.value = _videos.value.map { v ->
                        if (v.id in idSet) v.copy(tags = v.tags - tagName) else v
                    }
                    _expandedGroupMembers.value = _expandedGroupMembers.value.mapValues { (_, members) ->
                        members.map { v ->
                            if (v.id in idSet) v.copy(tags = v.tags - tagName) else v
                        }
                    }
                }
                if (!repository.untagVideos(finalIds, tagId)) {
                    _error.value = "Failed to remove tag"
                    return@launch
                }
                loadTags()
                onComplete()
            } catch (e: Exception) {
                _error.value = "Remove keyword failed: ${e.message}"
                logger.error("removeKeyword failed", e)
            }
        }
    }

    fun setFilterTags(tags: List<String>) {
        filterTags = tags
        reloadForFilterChange()
    }

    /** Id of the smart collection whose filters are currently expanded into the
     *  live filter fields, or null. A smart collection isn't a `collection_id`
     *  constraint — its saved filters are written into the bar — so we must
     *  remember it to undo those writes when the user navigates away. */
    private var activeSmartCollectionId: String? = null

    /** Undo the filter fields a smart collection expanded into the bar, so they
     *  don't strand the user on an empty grid after they leave it (e.g. after
     *  deleting a smart collection that matched nothing — the bug this fixes).
     *  No-op when no smart collection is currently applied. */
    private fun clearActiveSmartCollectionFilters() {
        if (activeSmartCollectionId == null) return
        activeSmartCollectionId = null
        _filterMinRating.value = 0
        _filterColorLabel.value = ""
        filterTags = emptyList()
        _filterTagId.value = ""
        if (_metadataColumns.value.any { it.values.isNotEmpty() }) {
            _metadataColumns.value = _metadataColumns.value.map { it.copy(values = emptySet(), anchor = "") }
            LibraryFilterPrefs.saveColumns(_metadataColumns.value)
        }
    }

    fun setCollection(id: String?) {
        // Already viewing this collection (or already cleared) — keep the
        // current results rather than reloading and flashing the spinner.
        // Library rows clear the collection on every click, so without this
        // guard re-selecting the current location would still reload.
        if (id == _selectedCollectionId.value) return
        // Leaving whatever we were viewing: if it was a smart collection, undo
        // the filters it injected so they don't linger into the next view.
        clearActiveSmartCollectionFilters()
        _selectedCollectionId.value = id
        val col = _collections.value.firstOrNull { it.id == id }
        if (col != null && col.isSmart && col.filterJson.isNotBlank()) {
            // Smart collection: apply its saved filters to the individual filter
            // fields. The grid is driven by the filters, not by collection_id.
            val f = com.reelvault.data.models.SmartCollectionFilters.fromJson(col.filterJson)
            collectionId = null
            _metadataColumns.value = metadataColumnsFromSmartFilters(f)
            LibraryFilterPrefs.saveColumns(_metadataColumns.value)
            _filterMinRating.value = f.minRating
            _filterColorLabel.value = f.colorLabel
            filterTags = f.tagIds
            _filterTagId.value = f.tagIds.firstOrNull() ?: ""
            activeSmartCollectionId = id
        } else {
            collectionId = id
        }
        reloadForFilterChange()
    }

    /** Human-readable selection criteria for a smart [collection], resolving
     *  tag IDs to names. An empty list means the collection constrains nothing
     *  (it would match every video). Shown in the details panel when no card is
     *  selected, so the user can see why a smart collection gathers what it does. */
    fun smartCollectionCriteria(
        collection: com.reelvault.data.models.Collection
    ): List<Pair<String, String>> {
        val f = com.reelvault.data.models.SmartCollectionFilters.fromJson(collection.filterJson)
        val sep = com.reelvault.data.models.METADATA_VALUE_SEPARATOR
        fun multi(s: String) = s.split(sep).filter { it.isNotEmpty() }.joinToString(", ")
        val out = mutableListOf<Pair<String, String>>()
        if (f.camera.isNotEmpty()) out += "Camera" to multi(f.camera)
        if (f.lens.isNotEmpty()) out += "Lens" to multi(f.lens)
        if (f.codec.isNotEmpty()) out += "Codec" to multi(f.codec)
        if (f.captureYear != 0) out += "Year" to f.captureYear.toString()
        if (f.minRating > 0) out += "Rating" to "${f.minRating}+ stars"
        if (f.colorLabel.isNotEmpty()) out += "Color" to f.colorLabel.replaceFirstChar { it.uppercase() }
        if (f.tagIds.isNotEmpty()) {
            val names = f.tagIds.map { id -> _tags.value.firstOrNull { it.id == id }?.name ?: id }
            out += "Keywords" to names.joinToString(", ")
        }
        return out
    }

    /** Title + detail for the grid/list empty state, tailored to *why* the grid
     *  is empty. Keeps grid and list in sync and avoids the old advice to "add a
     *  library location", which is wrong inside an empty collection. */
    fun emptyStateMessage(): Pair<String, String> {
        val id = _selectedCollectionId.value
        val col = id?.let { cid -> _collections.value.firstOrNull { it.id == cid } }
        return when {
            col != null && col.isSmart ->
                "No videos match this smart collection" to
                    "Its selection rules are listed in the details panel. Edit the collection to change what it gathers."
            col != null ->
                "This collection is empty" to
                    "Add videos by selecting them in the grid and choosing “Add to Collection”."
            hasActiveLibraryFilter() ->
                "No videos match the current filter" to
                    "Choose “Clear” in the filter bar to show all videos again."
            _libraryLocations.value.isEmpty() ->
                "Your library is empty" to
                    "Click the + button at the top of the Library panel to add a folder."
            else ->
                "No videos found" to ""
        }
    }

    /** Whether any Library Filter constraint (text / attribute / metadata /
     *  keyword / map proximity) is currently narrowing the grid. */
    private fun hasActiveLibraryFilter(): Boolean {
        val anyAttr = com.reelvault.data.models.AttributeFilterState.Any
        return _searchQuery.value.isNotEmpty() ||
            _filterMinRating.value > 0 ||
            _filterColorLabel.value.isNotEmpty() ||
            _filterHasLocation.value != anyAttr ||
            _filterHasKeywords.value != anyAttr ||
            _filterHasProxies.value != anyAttr ||
            _filterFullResolution.value != anyAttr ||
            _filterTagId.value.isNotEmpty() ||
            _filterLocation.value != null ||
            _metadataColumns.value.any { it.values.isNotEmpty() }
    }

    fun loadCollections() {
        viewModelScope.launch {
            try {
                _collections.value = repository.listCollections().sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                logger.warn("Failed to load collections", e)
            }
        }
    }

    fun createCollection(name: String, isSmart: Boolean, filterJson: String = "") {
        viewModelScope.launch {
            try {
                repository.createCollection(name, isSmart, filterJson)
                loadCollections()
            } catch (e: Exception) {
                _error.value = "Failed to create collection: ${e.message}"
            }
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteCollection(id)
                if (_selectedCollectionId.value == id) setCollection(null)
                loadCollections()
            } catch (e: Exception) {
                _error.value = "Failed to delete collection: ${e.message}"
            }
        }
    }

    fun addToCollection(videoIds: List<String>, collectionId: String) {
        viewModelScope.launch {
            try {
                repository.addToCollection(videoIds, collectionId)
                loadCollections()
            } catch (e: Exception) {
                _error.value = "Failed to add to collection: ${e.message}"
            }
        }
    }

    fun removeFromCollection(videoIds: List<String>, collectionId: String) {
        viewModelScope.launch {
            try {
                repository.removeFromCollection(videoIds, collectionId)
                loadCollections()
            } catch (e: Exception) {
                _error.value = "Failed to remove from collection: ${e.message}"
            }
        }
    }

    fun buildSmartCollectionFilterJson(): String {
        val tagId = _filterTagId.value
        val cols = _metadataColumns.value
        // A column's multiple selected values are persisted joined by the wire
        // separator; the daemon (and metadataColumnsFromSmartFilters) split it.
        fun colValue(key: String): String =
            cols.firstOrNull { it.key == key && it.values.isNotEmpty() }
                ?.values?.joinToString(com.reelvault.data.models.METADATA_VALUE_SEPARATOR) ?: ""
        return com.reelvault.data.models.SmartCollectionFilters(
            camera = colValue("camera"),
            lens = colValue("lens"),
            codec = colValue("codec"),
            captureYear = colValue("year").toIntOrNull() ?: 0,
            minRating = _filterMinRating.value,
            colorLabel = _filterColorLabel.value,
            tagIds = if (tagId.isEmpty()) emptyList() else listOf(tagId)
        ).toJson()
    }

    /** Build metadata columns from a smart collection's saved scalar filters.
     *  Falls back to the defaults when the saved filter set is empty. */
    private fun metadataColumnsFromSmartFilters(
        f: com.reelvault.data.models.SmartCollectionFilters
    ): List<com.reelvault.data.models.MetadataColumn> {
        val cols = mutableListOf<com.reelvault.data.models.MetadataColumn>()
        val sep = com.reelvault.data.models.METADATA_VALUE_SEPARATOR
        fun parse(s: String): Set<String> = s.split(sep).filter { it.isNotEmpty() }.toSet()
        if (f.camera.isNotEmpty()) cols.add(com.reelvault.data.models.MetadataColumn("camera", parse(f.camera)))
        if (f.lens.isNotEmpty()) cols.add(com.reelvault.data.models.MetadataColumn("lens", parse(f.lens)))
        if (f.codec.isNotEmpty()) cols.add(com.reelvault.data.models.MetadataColumn("codec", parse(f.codec)))
        if (f.captureYear != 0) {
            cols.add(com.reelvault.data.models.MetadataColumn("year", setOf(f.captureYear.toString())))
        }
        return cols.ifEmpty { com.reelvault.data.models.defaultMetadataColumns }
    }

    fun clearError() {
        _error.value = null
    }

    private val _scanStatus = MutableStateFlow<String?>(null)
    val scanStatus: StateFlow<String?> = _scanStatus.asStateFlow()

    // Thumbnail cache: video_id -> bytes
    private val _thumbnails = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ByteArray>> = _thumbnails.asStateFlow()

    // Scrub-frame cache: video_id -> list of N frames (index 0..N-1).
    // Loaded lazily on first hover over each card.
    private val _scrubFrames = MutableStateFlow<Map<String, List<ByteArray?>>>(emptyMap())
    val scrubFrames: StateFlow<Map<String, List<ByteArray?>>> = _scrubFrames.asStateFlow()

    // Limits concurrent thumbnail fetches. A large grid can have 30-50+
    // cards enter the viewport at once; without a cap, each fires its own
    // gRPC streaming call, which can overwhelm the connection and cause
    // some streams to return empty — silently dropping thumbnails.
    private val thumbnailSemaphore = Semaphore(8)

    // In-flight guard for thumbnail fetches — mirrors scrubLoading below.
    // Prevents duplicate concurrent launches when the same video ID is
    // requested more than once before the first fetch completes.
    private val thumbnailLoading = mutableSetOf<String>()

    // Tracks which video IDs are currently being loaded so we don't fire
    // duplicate requests if the user hovers in/out repeatedly.
    private val scrubLoading = mutableSetOf<String>()

    // Higher-resolution detail thumbnails. Populated only while the user dwells
    // on the detail view (see startHiResDetail). `_hiResScrubFrames` mirrors
    // `_scrubFrames` but at the detail render resolution; `_hiResPoster` is the
    // upgraded static (non-hover) frame. Both fill incrementally so each scrub
    // location upgrades as soon as its frame arrives, and survive a cancel so
    // returning to the video resumes rather than refetches.
    private val _hiResScrubFrames = MutableStateFlow<Map<String, List<ByteArray?>>>(emptyMap())
    val hiResScrubFrames: StateFlow<Map<String, List<ByteArray?>>> = _hiResScrubFrames.asStateFlow()
    private val _hiResPoster = MutableStateFlow<Map<String, ByteArray?>>(emptyMap())
    val hiResPoster: StateFlow<Map<String, ByteArray?>> = _hiResPoster.asStateFlow()
    private val hiResJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val hiResWidth = mutableMapOf<String, Int>()
    // The base scrub width the core generates by default; a "hi-res" request at
    // or below it would gain nothing.
    private val baseScrubWidth = 320

    // Set of group IDs that are currently "open" (Lightroom-style stack expansion)
    private val _expandedGroupIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroupIds: StateFlow<Set<String>> = _expandedGroupIds.asStateFlow()

    // Cached members of currently-expanded groups: groupId -> ordered members
    private val _expandedGroupMembers = MutableStateFlow<Map<String, List<VideoSummary>>>(emptyMap())
    val expandedGroupMembers: StateFlow<Map<String, List<VideoSummary>>> = _expandedGroupMembers.asStateFlow()

    /**
     * Toggle whether the given group is expanded in the grid. On expand, fetches
     * the group's members from the backend (cached for subsequent toggles).
     */
    /**
     * Refresh every piece of UI state that depends on a stack's
     * membership: the cached expanded-stack member list, the grid's
     * representative entries (which carry `groupId` / `groupSize` for
     * each video), and — if the stack has collapsed to a single video —
     * the expanded-id set itself. Called after Ungroup This,
     * Remove-from-stack, or Unstack so the grid catches up with the
     * daemon's view of the world.
     *
     * `groupId` is the *old* group the video was a member of, even if
     * the ungroup made that group disappear. Pass an empty string when
     * the operation isn't tied to a specific group (e.g. a bulk
     * ungroup that already cleared the local state itself).
     */
    /** Remove a single video from its stack and refresh the grid. Wired
     *  from the right-click "Remove from stack" menu item. */
    fun removeFromStack(videoId: String, groupId: String) {
        if (videoId.isEmpty()) return
        viewModelScope.launch {
            try {
                if (repository.ungroupVideo(videoId)) {
                    refreshAfterStackChange(groupId)
                } else {
                    _error.value = "Failed to remove video from stack"
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove from stack: ${e.message}"
            }
        }
    }

    /** Disband an entire stack — ungroups every member, leaving each
     *  video standalone. Wired from the right-click "Unstack" menu
     *  item. We iterate via individual `UngroupVideo` calls rather than
     *  a bulk RPC because the daemon doesn't currently expose one;
     *  stacks are small (a handful of variants), so the chatter is fine. */
    fun unstackGroup(groupId: String) {
        if (groupId.isEmpty()) return
        viewModelScope.launch {
            try {
                val (members, _) = repository.listGroupMembers(groupId)
                for (member in members) {
                    repository.ungroupVideo(member.id)
                }
                refreshAfterStackChange(groupId)
            } catch (e: Exception) {
                _error.value = "Failed to unstack: ${e.message}"
            }
        }
    }

    /** Promote a video within an existing stack to become the
     *  representative shown when the stack is collapsed. Wired from the
     *  right-click "Set as Stack Master" menu item. */
    fun setStackMaster(videoId: String, groupId: String) {
        if (videoId.isEmpty() || groupId.isEmpty()) return
        // Optimistic local update so the badge / order changes before
        // the round-trip completes.
        _videos.value = _videos.value.map { v ->
            if (v.groupId == groupId) v.copy(groupPreferredId = videoId) else v
        }
        viewModelScope.launch {
            try {
                repository.setGroupPreferred(groupId, videoId)
                refreshAfterStackChange(groupId)
                loadVideos()  // representative changed → grid order may shift
            } catch (e: Exception) {
                _error.value = "Failed to set stack master: ${e.message}"
            }
        }
    }

    fun refreshAfterStackChange(groupId: String) {
        if (groupId.isNotEmpty()) {
            // Re-pull the member list for this group. If the daemon
            // dissolved the group entirely (last member ungrouped), the
            // call returns zero members and we drop it from the cache
            // so the stack badge stops trying to expand it.
            viewModelScope.launch {
                try {
                    val (members, _) = repository.listGroupMembers(groupId)
                    if (members.size < 2) {
                        _expandedGroupMembers.value = _expandedGroupMembers.value - groupId
                        _expandedGroupIds.value = _expandedGroupIds.value - groupId
                    } else {
                        _expandedGroupMembers.value =
                            _expandedGroupMembers.value + (groupId to members)
                    }
                } catch (e: Exception) {
                    // On error, evict the cached entry so a subsequent
                    // expansion re-fetches fresh data rather than
                    // serving stale members.
                    _expandedGroupMembers.value = _expandedGroupMembers.value - groupId
                    logger.warn("Failed to refresh members for group $groupId", e)
                }
            }
        }
        // Reload the representative list so each video's `groupId` /
        // `groupSize` reflects the post-ungroup reality.
        loadVideos()
    }

    fun toggleStackExpansion(groupId: String) {
        if (groupId.isEmpty()) return
        val currentlyExpanded = _expandedGroupIds.value
        if (groupId in currentlyExpanded) {
            // Collapse
            _expandedGroupIds.value = currentlyExpanded - groupId
            return
        }

        // Expand: fetch members if we don't have them cached yet
        _expandedGroupIds.value = currentlyExpanded + groupId
        if (_expandedGroupMembers.value.containsKey(groupId)) {
            return
        }
        viewModelScope.launch {
            try {
                val (members, _) = repository.listGroupMembers(groupId)
                _expandedGroupMembers.value = _expandedGroupMembers.value + (groupId to members)
                // Pre-load thumbnails for the new members
                members.forEach { m ->
                    if (m.hasThumbnail) loadThumbnail(m.id)
                }
            } catch (e: Exception) {
                logger.warn("Failed to load members for group $groupId", e)
            }
        }
    }

    /** Tracks groupIds whose members are currently being prefetched, so the
     *  list-row composables can fire-and-forget from LaunchedEffect without
     *  flooding the daemon with duplicate ListGroupMembers RPCs. */
    private val stackMembersLoading = mutableSetOf<String>()

    /**
     * Populate [expandedGroupMembers] for [groupId] without expanding the
     * stack. Used by the list view to render the names of a collapsed
     * stack's members in the row's info column alongside the other
     * details. The cache is shared with [toggleStackExpansion] so a
     * subsequent expand reuses the already-fetched members.
     */
    fun ensureStackMembersLoaded(groupId: String) {
        if (groupId.isEmpty()) return
        if (_expandedGroupMembers.value.containsKey(groupId)) return
        if (!stackMembersLoading.add(groupId)) return
        viewModelScope.launch {
            try {
                val (members, _) = repository.listGroupMembers(groupId)
                _expandedGroupMembers.value =
                    _expandedGroupMembers.value + (groupId to members)
            } catch (e: Exception) {
                logger.warn("Failed to preload members for group $groupId", e)
            } finally {
                stackMembersLoading.remove(groupId)
            }
        }
    }

    /** Collapse all expanded stacks (useful when sort/filter changes). */
    private fun collapseAllStacks() {
        _expandedGroupIds.value = emptySet()
    }

    fun loadThumbnail(videoId: String) {
        if (_thumbnails.value.containsKey(videoId)) return
        if (videoId in thumbnailLoading) return
        thumbnailLoading.add(videoId)
        viewModelScope.launch {
            try {
                // Fast initial retries handle the cards-mount-storm failure
                // mode: an empty gRPC stream when many cards request thumbnails
                // at once. Slow tail retries cover the case where the storm
                // exhausts the fast budget — without them the card stayed
                // permanently thumbnail-less until a re-mount (e.g. via a
                // mode switch) re-triggered the load.
                val delaysMs = longArrayOf(0L, 500L, 1_000L, 10_000L, 30_000L, 60_000L)
                for (delayMs in delaysMs) {
                    if (_thumbnails.value.containsKey(videoId)) break
                    if (delayMs > 0) delay(delayMs)
                    val data = thumbnailSemaphore.withPermit {
                        repository.getThumbnail(videoId, "medium")
                    }
                    if (data != null) {
                        _thumbnails.update { it + (videoId to data) }
                        break
                    }
                }
            } finally {
                thumbnailLoading.remove(videoId)
            }
        }
    }

    /** Fetch (once per video) the scrub frames used by the hover preview. */
    fun loadScrubFrames(videoId: String) {
        if (_scrubFrames.value.containsKey(videoId)) return
        if (videoId in scrubLoading) return
        scrubLoading.add(videoId)
        viewModelScope.launch {
            try {
                val count = uiPrefs.getInt("scrubFrameCount", 10)
                val frames = repository.getScrubFrames(videoId, count = count)
                if (frames.any { it != null }) {
                    _scrubFrames.value = _scrubFrames.value + (videoId to frames)
                }
            } finally {
                scrubLoading.remove(videoId)
            }
        }
    }

    /** Begin (or resume) fetching detail-resolution thumbnails for [videoId],
     *  sized to the [targetWidth] px render area — the dwell action behind the
     *  detail view's higher-res scrubbing. Fetches the upgraded poster first
     *  (the frame the user is staring at), then every scrub frame, updating the
     *  caches incrementally. Idempotent at a given width; resumes (skips frames
     *  already fetched) after a cancel. No-op when the area is no wider than the
     *  base scrub resolution. */
    fun startHiResDetail(videoId: String, targetWidth: Int) {
        if (targetWidth <= baseScrubWidth) return
        if (hiResWidth[videoId] == targetWidth && hiResJobs.containsKey(videoId)) return
        // A resize to a different width invalidates the cached hi-res frames.
        if (hiResWidth[videoId] != null && hiResWidth[videoId] != targetWidth) {
            _hiResScrubFrames.value = _hiResScrubFrames.value - videoId
            _hiResPoster.value = _hiResPoster.value - videoId
        }
        hiResWidth[videoId] = targetWidth
        hiResJobs[videoId]?.cancel()
        val count = (_scrubFrames.value[videoId]?.size ?: uiPrefs.getInt("scrubFrameCount", 10))
            .coerceAtLeast(1)
        hiResJobs[videoId] = viewModelScope.launch {
            try {
                // 1) The static (non-hover) frame the user is currently seeing.
                if (_hiResPoster.value[videoId] == null) {
                    repository.getThumbnail(videoId, "large", maxWidth = targetWidth)?.let {
                        _hiResPoster.value = _hiResPoster.value + (videoId to it)
                    }
                }
                // 2) Every scrub frame, so scrubbing shows hi-res too. Filled in
                //    place so each location upgrades as soon as its frame lands.
                val acc = _hiResScrubFrames.value[videoId]?.toMutableList()
                    ?: MutableList<ByteArray?>(count) { null }
                for (i in 0 until count) {
                    if (acc.getOrNull(i) != null) continue
                    val bytes = repository.getThumbnail(videoId, "scrub_$i", maxWidth = targetWidth)
                    if (bytes != null) {
                        acc[i] = bytes
                        _hiResScrubFrames.value = _hiResScrubFrames.value + (videoId to acc.toList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Hi-res detail thumbnails failed for {}", videoId, e)
            } finally {
                hiResJobs.remove(videoId)
            }
        }
    }

    /** Stop any in-flight hi-res detail fetch for [videoId] (the user left the
     *  detail view). Frames already fetched are kept so a return resumes. */
    fun cancelHiResDetail(videoId: String) {
        hiResJobs.remove(videoId)?.cancel()
    }

    fun openVideoInExternal(path: String) {
        viewModelScope.launch {
            try {
                val file = java.io.File(path)
                if (file.exists()) {
                    withContext(Dispatchers.IO) {
                        java.awt.Desktop.getDesktop().open(file)
                    }
                    logger.info("Opened video in external app: $path")
                } else {
                    _error.value = "File not found: $path"
                }
            } catch (e: Exception) {
                _error.value = "Failed to open video: ${e.message}"
                logger.error("Failed to open video externally", e)
            }
        }
    }

    /**
     * Create a group from the currently multi-selected videos. The anchor
     * (first clicked, or last toggle-clicked-on) is used as the "preferred"
     * (default-open) one — falls back to the first selected if anchor is
     * somehow missing.
     */
    fun groupSelectedVideos() {
        val ids = _selectedVideoIds.value
        if (ids.size < 2) {
            _error.value = "Select at least 2 videos (shift+click or Cmd/Ctrl+click) to create a group"
            return
        }

        // Proxies are stand-ins for their masters and must not also belong to a
        // stack — that would let the same file appear in two roles at once.
        val proxyIds = _videos.value
            .filter { it.id in ids && it.isProxy }
            .map { it.id }
        if (proxyIds.isNotEmpty()) {
            _error.value = "Proxy videos cannot be added to a stack. " +
                "Deselect the ${if (proxyIds.size == 1) "proxy" else "${proxyIds.size} proxies"} " +
                "and try again."
            return
        }

        val anchor = _anchorVideoId.value
        val preferred = if (anchor != null && anchor in ids) anchor else ids.first()
        viewModelScope.launch {
            _isLoading.value = true
            _scanStatus.value = "Creating group..."
            try {
                val group = repository.createGroup(ids, name = "", preferredVideoId = preferred)
                if (group != null) {
                    _scanResult.value = ScanResult(
                        success = true,
                        message = "Grouped ${ids.size} videos into a new stack",
                        videosFound = ids.size,
                        videosIndexed = ids.size
                    )
                    clearSelection()
                    loadVideos()
                } else {
                    _error.value = "Failed to create group"
                }
            } catch (e: Exception) {
                _error.value = "Group failed: ${e.message}"
                logger.error("Failed to group selected", e)
            } finally {
                _scanStatus.value = null
                _isLoading.value = false
            }
        }
    }

    /** Keep auto-group available for the initial import path (not exposed as a button). */
    fun autoGroupVideos(
        sameDirectoryOnly: Boolean = true,
        matchDuration: Boolean = true,
        matchFps: Boolean = true
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _scanStatus.value = "Auto-grouping videos..."
            try {
                val (groups, videos, _) = repository.autoGroup(sameDirectoryOnly, matchDuration, matchFps)
                _scanResult.value = ScanResult(
                    success = true,
                    message = if (groups == 0) {
                        "No new groups created (no matching variants found)"
                    } else {
                        "Auto-grouped $videos videos into $groups stacks"
                    },
                    videosFound = videos,
                    videosIndexed = videos
                )
                loadVideos()
            } catch (e: Exception) {
                _error.value = "Auto-group failed: ${e.message}"
                logger.error("Failed to auto-group", e)
            } finally {
                _scanStatus.value = null
                _isLoading.value = false
            }
        }
    }

    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    fun clearScanResult() {
        _scanResult.value = null
    }

    fun addLibraryAndScan(
        path: String,
        recursive: Boolean = true,
        autoGroup: Boolean = true,
        /** "MM-DD-YYYY" | "DD-MM-YYYY" | "YYYY-MM-DD" — empty disables. */
        filenameDateFormat: String = "",
        /** "anywhere" | "beginning" | "end" — only used when filenameDateFormat is set. */
        filenameDatePosition: String = ""
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _scanStatus.value = "Adding library location..."
            _scanResult.value = null

            try {
                // Add library location (backend now validates the path exists)
                val (added, addMessage) = repository.addLibraryLocationWithMessage(path, recursive)
                if (!added) {
                    _scanResult.value = ScanResult(
                        success = false,
                        message = addMessage.ifBlank { "Failed to add library location" },
                        videosFound = 0,
                        videosIndexed = 0
                    )
                    _scanStatus.value = null
                    _isLoading.value = false
                    return@launch
                }

                // Surface the new location in the left panel right away —
                // without this, the row only appears after the scan
                // finishes, which can be minutes on a large folder.
                loadLibraryLocations()

                // Trigger scan
                _scanStatus.value = "Scanning..."
                // We track the *max* values seen across all progress
                // ticks: some scan phases emit zero-valued progress
                // events (e.g. the "indexing_metadata" phase resets
                // `videos_found` to the running count of the current
                // pass, which can briefly read 0 before the daemon
                // re-emits). Taking the max stops the final summary
                // from claiming "0 videos found" when it actually
                // indexed dozens.
                var peakFound = 0
                var peakIndexed = 0
                var errorMessage: String? = null
                var libraryRefreshTick = 0

                repository.scanLibrary(
                    locationPath = path,
                    autoGroup = autoGroup,
                    filenameDateFormat = filenameDateFormat,
                    filenameDatePosition = filenameDatePosition
                ).collect { progress ->
                    if (progress.status == "error") {
                        errorMessage = progress.currentFile
                        logger.warn("Scan error: ${progress.currentFile}")
                    } else {
                        peakFound = maxOf(peakFound, progress.videosFound)
                        peakIndexed = maxOf(peakIndexed, progress.videosIndexed)
                        _scanStatus.value = if (progress.status == "complete") {
                            "Complete: $peakFound found, $peakIndexed indexed"
                        } else {
                            "${progress.status}: $peakIndexed/$peakFound - ${progress.currentFile}"
                        }
                    }
                    logger.info("Scan progress: ${progress.status} | found: ${progress.videosFound} | indexed: ${progress.videosIndexed}")
                    // Refresh the grid + library panel every ~12 progress
                    // ticks so the user sees newly-indexed videos and the
                    // location's video-count update as the scan
                    // progresses instead of waiting until the very end.
                    libraryRefreshTick += 1
                    if (libraryRefreshTick % 12 == 0) {
                        loadVideos()
                        loadLibraryLocations()
                    }
                }

                // Build final result
                _scanResult.value = if (errorMessage != null) {
                    ScanResult(
                        success = false,
                        message = errorMessage ?: "Scan failed",
                        videosFound = 0,
                        videosIndexed = 0
                    )
                } else {
                    // "No videos found" only makes sense when the scan
                    // genuinely turned up zero — if we indexed anything,
                    // the count was just lost to a transient progress
                    // event and we should treat the scan as successful.
                    val foundForReport = maxOf(peakFound, peakIndexed)
                    ScanResult(
                        success = true,
                        message = if (foundForReport == 0) {
                            "No videos found in $path"
                        } else {
                            "Scanned $path: $foundForReport videos found, $peakIndexed indexed"
                        },
                        videosFound = foundForReport,
                        videosIndexed = peakIndexed
                    )
                }

                _scanStatus.value = null
                _isLoading.value = false
                // Refresh video list, library panel counts, and filter
                // dropdown values after scan (new cameras / codecs may have
                // appeared).
                loadVideos()
                loadLibraryLocations()
                scheduleFacetRefresh()
            } catch (e: Exception) {
                _scanResult.value = ScanResult(
                    success = false,
                    message = "Scan failed: ${e.message}",
                    videosFound = 0,
                    videosIndexed = 0
                )
                _scanStatus.value = null
                _isLoading.value = false
                logger.error("Failed to add/scan library", e)
            }
        }
    }

    // Set of library paths currently being rescanned. The UI uses this to
    // show a per-row spinner instead of the refresh button during a scan.
    private val _rescanningPaths = MutableStateFlow<Set<String>>(emptySet())
    val rescanningPaths: StateFlow<Set<String>> = _rescanningPaths.asStateFlow()

    /**
     * Re-scan an already-registered library location without re-adding it.
     * Runs the same scan pipeline as [addLibraryAndScan] (including proxy
     * detection) but skips the addLibraryLocation RPC.
     */
    fun rescanLibrary(path: String) {
        viewModelScope.launch {
            _rescanningPaths.update { it + path }
            _scanStatus.value = "Rescanning ${java.io.File(path).name}..."
            _scanResult.value = null
            var peakFound = 0
            var peakIndexed = 0
            var errorMessage: String? = null
            var libraryRefreshTick = 0
            try {
                repository.scanLibrary(
                    locationPath = path,
                    autoGroup = true,
                    filenameDateFormat = "",
                    filenameDatePosition = ""
                ).collect { progress ->
                    if (progress.status == "error") {
                        errorMessage = progress.currentFile
                    } else {
                        peakFound = maxOf(peakFound, progress.videosFound)
                        peakIndexed = maxOf(peakIndexed, progress.videosIndexed)
                        _scanStatus.value = if (progress.status == "complete") {
                            "Rescan complete: $peakFound found, $peakIndexed indexed"
                        } else {
                            "${progress.status}: $peakIndexed/$peakFound"
                        }
                    }
                    libraryRefreshTick += 1
                    if (libraryRefreshTick % 12 == 0) {
                        loadVideos()
                        loadLibraryLocations()
                    }
                }
                loadVideos()
                loadLibraryLocations()
                _scanResult.value = ScanResult(
                    success = errorMessage == null,
                    message = errorMessage
                        ?: "Rescan complete — $peakFound found, $peakIndexed indexed",
                    videosFound = peakFound,
                    videosIndexed = peakIndexed
                )
            } catch (e: Exception) {
                _scanResult.value = ScanResult(
                    success = false,
                    message = "Rescan failed: ${e.message}",
                    videosFound = 0,
                    videosIndexed = 0
                )
            } finally {
                _rescanningPaths.update { it - path }
                _scanStatus.value = null
                _isLoading.value = false
            }
        }
    }

    data class ScanResult(
        val success: Boolean,
        val message: String,
        val videosFound: Int,
        val videosIndexed: Int
    )

    /** Accumulated progress across all paths in a multi-path add+scan batch. */
    data class BatchScanProgress(
        /** Total videos found across all paths scanned so far. */
        val found: Int,
        /** Total videos indexed across all paths scanned so far. */
        val indexed: Int,
        /** How many paths have finished scanning. */
        val pathsDone: Int,
        /** Total number of paths in the batch. */
        val pathsTotal: Int
    )

    private val _batchScanProgress = MutableStateFlow<BatchScanProgress?>(null)
    val batchScanProgress: StateFlow<BatchScanProgress?> = _batchScanProgress.asStateFlow()

    /**
     * Add multiple library locations and scan them sequentially.
     *
     * Each path is expanded on the **backend** (which handles `$YEAR` template
     * substitution). The UI progress counters are accumulated across all paths
     * so the progress bar reflects the whole batch rather than resetting per
     * path.
     *
     * The [options] block shares the same `recursive`, `autoGroup`, and date
     * settings for every path in the list — if you need per-path settings, call
     * [addLibraryAndScan] individually.
     */
    fun addLibraryAndScanMultiple(
        paths: List<String>,
        recursive: Boolean = true,
        autoGroup: Boolean = true,
        filenameDateFormat: String = "",
        filenameDatePosition: String = ""
    ) {
        if (paths.isEmpty()) return
        if (paths.size == 1) {
            // Fast-path: reuse the well-tested single-path implementation.
            addLibraryAndScan(
                path = paths[0],
                recursive = recursive,
                autoGroup = autoGroup,
                filenameDateFormat = filenameDateFormat,
                filenameDatePosition = filenameDatePosition
            )
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _scanResult.value = null
            _batchScanProgress.value = BatchScanProgress(0, 0, 0, paths.size)

            var totalFound = 0
            var totalIndexed = 0
            var failures = 0

            for ((idx, path) in paths.withIndex()) {
                val trimmed = path.trim()
                if (trimmed.isEmpty()) {
                    _batchScanProgress.value = _batchScanProgress.value?.copy(pathsDone = idx + 1)
                    continue
                }

                _scanStatus.value = "Adding location ${idx + 1}/${paths.size}: ${java.io.File(trimmed).name}"

                val (added, addMessage) = repository.addLibraryLocationWithMessage(trimmed, recursive)
                if (!added) {
                    logger.warn("Failed to add $trimmed: $addMessage")
                    failures++
                    _batchScanProgress.value = _batchScanProgress.value?.copy(pathsDone = idx + 1)
                    continue
                }

                // Surface new location in the sidebar immediately.
                loadLibraryLocations()

                _scanStatus.value = "Scanning ${idx + 1}/${paths.size}: ${java.io.File(trimmed).name}"

                var peakFound = 0
                var peakIndexed = 0
                var libraryRefreshTick = 0

                try {
                    repository.scanLibrary(
                        locationPath = trimmed,
                        autoGroup = autoGroup,
                        filenameDateFormat = filenameDateFormat,
                        filenameDatePosition = filenameDatePosition
                    ).collect { progress ->
                        if (progress.status != "error") {
                            peakFound = maxOf(peakFound, progress.videosFound)
                            peakIndexed = maxOf(peakIndexed, progress.videosIndexed)
                            // Update accumulated totals so the progress bar
                            // reflects the entire batch, not just the current path.
                            _batchScanProgress.value = BatchScanProgress(
                                found   = totalFound + peakFound,
                                indexed = totalIndexed + peakIndexed,
                                pathsDone   = idx,       // still in progress
                                pathsTotal  = paths.size
                            )
                            _scanStatus.value = "Scanning ${idx + 1}/${paths.size}: " +
                                "$peakIndexed/$peakFound in ${java.io.File(trimmed).name}"
                        }
                        libraryRefreshTick += 1
                        if (libraryRefreshTick % 12 == 0) {
                            loadVideos()
                            loadLibraryLocations()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Scan failed for $trimmed", e)
                    failures++
                }

                totalFound  += peakFound
                totalIndexed += peakIndexed
                _batchScanProgress.value = BatchScanProgress(
                    found       = totalFound,
                    indexed     = totalIndexed,
                    pathsDone   = idx + 1,
                    pathsTotal  = paths.size
                )
            }

            val successCount = paths.size - failures
            _scanResult.value = ScanResult(
                success = failures < paths.size,
                message = buildString {
                    append("Scanned $successCount/${paths.size} location(s): ")
                    append("$totalFound videos found, $totalIndexed indexed")
                    if (failures > 0) append(" ($failures failed)")
                },
                videosFound   = totalFound,
                videosIndexed = totalIndexed
            )

            _scanStatus.value = null
            _isLoading.value = false
            _batchScanProgress.value = null
            loadVideos()
            loadLibraryLocations()
            scheduleFacetRefresh()
        }
    }

    fun onDestroy() {
        viewModelScope.cancel()
        logger.info("GridViewModel destroyed")
    }

    /**
     * Wipe every piece of catalog-derived state so the UI doesn't leak data
     * from the previously-mounted catalog. Called by App.kt right after
     * [VideoRepository.closeCatalog]. Filter dropdowns, tags, library
     * locations, thumbnails — everything goes back to the just-launched state.
     */
    fun clearState() {
        _videos.value = emptyList()
        _selectedVideoId.value = null
        _selectedVideo.value = null
        _selectedVideoIds.value = emptyList()
        _anchorVideoId.value = null
        _totalCount.value = 0
        _hasMore.value = false
        _isLoading.value = false
        _error.value = null
        _libraryLocations.value = emptyList()
        _selectedLocationPath.value = ""
        _selectedLocationPaths.value = emptyList()
        locationPathFilter = ""
        locationAnchorPath = null
        _tags.value = emptyList()
        _filterTagId.value = ""
        _metadataColumns.value = com.reelvault.data.models.defaultMetadataColumns
        _metadataFacets.value = emptyList()
        _metadataAvailableKeys.value = emptyList()
        _libraryFilterMode.value = com.reelvault.data.models.LibraryFilterMode.Clear
        _filterMinRating.value = 0
        _filterColorLabel.value = ""
        _topSlots.value = com.reelvault.data.models.defaultGridTopSlots
        _thumbnails.value = emptyMap()
        _scrubFrames.value = emptyMap()
        hiResJobs.values.forEach { it.cancel() }
        hiResJobs.clear()
        hiResWidth.clear()
        _hiResScrubFrames.value = emptyMap()
        _hiResPoster.value = emptyMap()
        _scanStatus.value = null
        _searchQuery.value = ""
        currentPage = 0
        collapseAllStacks()
    }
}
