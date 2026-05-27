// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.videoroom.data.models.VideoSummary
import com.videoroom.data.repository.VideoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.prefs.Preferences

class GridViewModel(
    private val repository: VideoRepository
) {
    private val logger = LoggerFactory.getLogger(GridViewModel::class.java)

    /** Local UI preferences shared with the settings dialogs. */
    private val uiPrefs: Preferences =
        Preferences.userRoot().node("com/videoroom/ui")
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
    private var sortBy = "indexed_at"
    private var sortAscending = false
    private var filterTags = emptyList<String>()
    private var collectionId: String? = null
    private var searchQuery = ""
    /** Currently selected library location to filter by. Empty string = all. */
    private var locationPathFilter: String = ""

    // Library locations panel state
    private val _libraryLocations = MutableStateFlow<List<com.videoroom.data.models.LibraryLocation>>(emptyList())
    val libraryLocations: StateFlow<List<com.videoroom.data.models.LibraryLocation>> = _libraryLocations.asStateFlow()

    private val _selectedLocationPath = MutableStateFlow("")  // "" = all locations
    val selectedLocationPath: StateFlow<String> = _selectedLocationPath.asStateFlow()

    // Keywords (tags). `tags` is the full list of known tags with usage counts;
    // `filterTagId` narrows the grid to a single tag (drives the `filterTags`
    // list passed to listVideos).
    private val _tags = MutableStateFlow<List<com.videoroom.data.models.Tag>>(emptyList())
    val tags: StateFlow<List<com.videoroom.data.models.Tag>> = _tags.asStateFlow()

    private val _filterTagId = MutableStateFlow("")  // "" = no tag filter
    val filterTagId: StateFlow<String> = _filterTagId.asStateFlow()

    // Top-bar dropdown filters. The empty string / 0 means "no filter (---)".
    private val _filterCamera = MutableStateFlow("")
    val filterCamera: StateFlow<String> = _filterCamera.asStateFlow()
    private val _filterLens = MutableStateFlow("")
    val filterLens: StateFlow<String> = _filterLens.asStateFlow()
    private val _filterCodec = MutableStateFlow("")
    val filterCodec: StateFlow<String> = _filterCodec.asStateFlow()
    private val _filterCaptureYear = MutableStateFlow(0)
    val filterCaptureYear: StateFlow<Int> = _filterCaptureYear.asStateFlow()

    // Lightroom-style user-mark filters. `_filterMinRating` of 0 = no filter;
    // 1..5 = "show videos with at least N stars". `_filterColorLabel` of ""
    // = no filter; otherwise exact-match the colour name.
    private val _filterMinRating = MutableStateFlow(0)
    val filterMinRating: StateFlow<Int> = _filterMinRating.asStateFlow()
    private val _filterColorLabel = MutableStateFlow("")
    val filterColorLabel: StateFlow<String> = _filterColorLabel.asStateFlow()

    // Lightroom-style top-of-card slot configuration. Four entries, each a
    // GridStatKey.raw value. Defaults until `loadGridSettings` answers.
    private val _topSlots = MutableStateFlow(com.videoroom.data.models.defaultGridTopSlots)
    val topSlots: StateFlow<List<String>> = _topSlots.asStateFlow()

    // Distinct values fetched from the backend to populate the dropdowns.
    private val _filterOptions = MutableStateFlow(com.videoroom.data.models.FilterOptions())
    val filterOptions: StateFlow<com.videoroom.data.models.FilterOptions> = _filterOptions.asStateFlow()

    // Geographic proximity filter — set when the user taps a pin on the
    // global map. Triple of (latitude, longitude, radius_km). Null = no
    // proximity filter active.
    private val _filterLocation = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val filterLocation: StateFlow<Triple<Double, Double, Double>?> = _filterLocation.asStateFlow()

    // Snapshot of every geotagged video, refreshed when the user opens the
    // global map. Kept here (not in App.kt) so the open-map button can stay
    // disabled when there's nothing to plot.
    private val _videoLocations = MutableStateFlow<List<com.videoroom.data.models.VideoLocation>>(emptyList())
    val videoLocations: StateFlow<List<com.videoroom.data.models.VideoLocation>> = _videoLocations.asStateFlow()

    // Catalog's user-defined named places (e.g. "Home"). Refreshed by
    // `loadNamedLocations`; used by `nameForLocation` to render named pins
    // on the map and named GPS readouts in the detail panel.
    private val _namedLocations = MutableStateFlow<List<com.videoroom.data.models.NamedLocation>>(emptyList())
    val namedLocations: StateFlow<List<com.videoroom.data.models.NamedLocation>> = _namedLocations.asStateFlow()

    // Sort state exposed for the UI
    private val _currentSortField = MutableStateFlow(sortBy)
    val currentSortField: StateFlow<String> = _currentSortField.asStateFlow()

    private val _currentSortAscending = MutableStateFlow(sortAscending)
    val currentSortAscending: StateFlow<Boolean> = _currentSortAscending.asStateFlow()

    // Columns visible in list mode. Persists only for session lifetime.
    private val _listColumns = MutableStateFlow(
        setOf("resolution", "duration", "fps", "codec", "date", "tags", "proxy")
    )
    val listColumns: StateFlow<Set<String>> = _listColumns.asStateFlow()

    fun toggleListColumn(column: String) {
        _listColumns.update { if (column in it) it - column else it + column }
    }

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

    init {
        logger.info("GridViewModel created")
    }

    // --- Live updates ---

    /**
     * Open (or re-open) the long-lived `SubscribeCatalogEvents` stream
     * against the daemon. Idempotent — calling twice cancels the prior
     * job and starts a fresh one. Reconnects with a 2 s backoff if the
     * stream ends while live updates are still enabled.
     */
    fun startCatalogEventStream() {
        catalogEventsJob?.cancel()
        catalogEventsJob = viewModelScope.launch {
            try {
                repository.subscribeCatalogEvents().collect { event ->
                    handleCatalogEvent(event)
                }
            } catch (e: Exception) {
                logger.warn("Catalog events stream ended", e)
            }
            // Stream ended: reconnect after a short backoff if we're
            // still expecting live updates.
            if (isActive && _liveUpdatesEnabled.value) {
                delay(2_000)
                if (isActive) startCatalogEventStream()
            }
        }
    }

    fun stopCatalogEventStream() {
        catalogEventsJob?.cancel()
        catalogEventsJob = null
        watcherRefreshJob?.cancel()
        watcherRefreshJob = null
    }

    /**
     * React to one [com.videoroom.data.models.CatalogEvent]: drives the
     * live-updates indicator, the watcher banner, and a debounced grid
     * refresh when video rows actually change.
     */
    private fun handleCatalogEvent(event: com.videoroom.data.models.CatalogEvent) {
        when (event.kind) {
            com.videoroom.data.models.CatalogEventKind.WatcherStarted ->
                _liveUpdatesEnabled.value = true
            com.videoroom.data.models.CatalogEventKind.WatcherDisabled ->
                _liveUpdatesEnabled.value = false
            com.videoroom.data.models.CatalogEventKind.ScanStarted -> {
                val target = if (event.path.isBlank()) "library" else event.path.substringAfterLast('/')
                _watcherBanner.value = "Scanning $target…"
            }
            com.videoroom.data.models.CatalogEventKind.ScanCompleted -> {
                _watcherBanner.value = null
                scheduleWatcherRefresh()
            }
            com.videoroom.data.models.CatalogEventKind.VideoAdded,
            com.videoroom.data.models.CatalogEventKind.VideoModified,
            com.videoroom.data.models.CatalogEventKind.VideoRemoved ->
                scheduleWatcherRefresh()
            com.videoroom.data.models.CatalogEventKind.Unknown -> { /* future kinds */ }
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

    fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            currentPage = 0
            // Reset stack expansion — representatives may have shifted/changed.
            collapseAllStacks()

            try {
                val (videosList, totalCount) = if (searchQuery.isNotEmpty()) {
                    repository.searchVideos(
                        query = searchQuery,
                        limit = pageSize,
                        offset = 0,
                        filterTags = filterTags
                    )
                } else {
                    repository.listVideos(
                        limit = pageSize,
                        offset = 0,
                        sortBy = sortBy,
                        sortAscending = sortAscending,
                        filterTags = filterTags,
                        collectionId = collectionId,
                        locationPath = locationPathFilter,
                        filterCamera = _filterCamera.value,
                        filterLens = _filterLens.value,
                        filterCodec = _filterCodec.value,
                        filterCaptureYear = _filterCaptureYear.value,
                        geoFilter = _filterLocation.value,
                        filterMinRating = _filterMinRating.value,
                        filterColorLabel = _filterColorLabel.value,
                    )
                }

                _videos.value = videosList
                _totalCount.value = totalCount
                _hasMore.value = videosList.size < totalCount
                _isLoading.value = false

                logger.info("Loaded ${videosList.size} videos, total: $totalCount" +
                    if (locationPathFilter.isNotEmpty()) " (filtered to $locationPathFilter)" else "")
            } catch (e: Exception) {
                _error.value = "Failed to load videos: ${e.message}"
                _isLoading.value = false
                logger.error("Failed to load videos", e)
            }
        }
    }

    fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // Snapshot the offset *before* the first suspension point.
            // Using the actual list size (not currentPage * pageSize) means
            // that even if a concurrent loadVideos() resets currentPage = 0
            // while this coroutine is suspended at the gRPC call, we still
            // fetch the correct next page — preventing the duplicate-key
            // crash that occurred when currentPage was reset mid-flight.
            val offset = _videos.value.size
            currentPage = offset / pageSize  // keep counter in sync

            try {
                val (newVideos, totalCount) = if (searchQuery.isNotEmpty()) {
                    repository.searchVideos(
                        query = searchQuery,
                        limit = pageSize,
                        offset = offset,
                        filterTags = filterTags
                    )
                } else {
                    repository.listVideos(
                        limit = pageSize,
                        offset = offset,
                        sortBy = sortBy,
                        sortAscending = sortAscending,
                        filterTags = filterTags,
                        collectionId = collectionId,
                        locationPath = locationPathFilter,
                        filterCamera = _filterCamera.value,
                        filterLens = _filterLens.value,
                        filterCodec = _filterCodec.value,
                        filterCaptureYear = _filterCaptureYear.value,
                        geoFilter = _filterLocation.value,
                        filterMinRating = _filterMinRating.value,
                        filterColorLabel = _filterColorLabel.value,
                    )
                }

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

    fun setSearchQuery(query: String) {
        searchQuery = query
        loadVideos()
    }

    fun clearSearch() {
        searchQuery = ""
        loadVideos()
    }

    fun setSort(field: String, ascending: Boolean) {
        sortBy = field
        sortAscending = ascending
        _currentSortField.value = field
        _currentSortAscending.value = ascending
        loadVideos()
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
                    if (locationPathFilter == path) setLocationFilter("")
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
        if (locationPathFilter == path) return
        locationPathFilter = path
        _selectedLocationPath.value = path
        loadVideos()
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
        loadVideos()
    }

    /** Refresh the distinct values for the top-bar dropdowns. */
    fun loadFilterOptions() {
        viewModelScope.launch {
            try {
                _filterOptions.value = repository.getFilterOptions()
            } catch (e: Exception) {
                logger.warn("Failed to load filter options", e)
            }
        }
    }

    fun setCameraFilter(value: String) {
        if (_filterCamera.value == value) return
        _filterCamera.value = value
        loadVideos()
    }
    fun setLensFilter(value: String) {
        if (_filterLens.value == value) return
        _filterLens.value = value
        loadVideos()
    }
    fun setCodecFilter(value: String) {
        if (_filterCodec.value == value) return
        _filterCodec.value = value
        loadVideos()
    }
    fun setCaptureYearFilter(year: Int) {
        if (_filterCaptureYear.value == year) return
        _filterCaptureYear.value = year
        loadVideos()
    }

    fun setMinRatingFilter(n: Int) {
        if (_filterMinRating.value == n) return
        _filterMinRating.value = n
        loadVideos()
    }

    fun setColorLabelFilter(label: String) {
        if (_filterColorLabel.value == label) return
        _filterColorLabel.value = label
        loadVideos()
    }

    fun clearAllDropdownFilters() {
        var changed = false
        if (_filterCamera.value.isNotEmpty()) { _filterCamera.value = ""; changed = true }
        if (_filterLens.value.isNotEmpty()) { _filterLens.value = ""; changed = true }
        if (_filterCodec.value.isNotEmpty()) { _filterCodec.value = ""; changed = true }
        if (_filterCaptureYear.value != 0) { _filterCaptureYear.value = 0; changed = true }
        if (_filterLocation.value != null) { _filterLocation.value = null; changed = true }
        if (_filterMinRating.value != 0) { _filterMinRating.value = 0; changed = true }
        if (_filterColorLabel.value.isNotEmpty()) { _filterColorLabel.value = ""; changed = true }
        if (changed) loadVideos()
    }

    // --- Lightroom-style user marks ---

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

    /** Update one slot's stat key and persist the full set to the catalog. */
    fun updateGridTopSlot(slotIndex: Int, statKey: String) {
        if (slotIndex !in 0..3) return
        val current = normaliseSlots(_topSlots.value).toMutableList()
        current[slotIndex] = statKey
        _topSlots.value = current.toList()
        viewModelScope.launch {
            try {
                repository.updateGridSettings(current.toList())
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
        _filterLocation.value = if (latitude != null && longitude != null) {
            Triple(latitude, longitude, radiusKm)
        } else null
        loadVideos()
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
    fun nameForLocation(latitude: Double, longitude: Double): com.videoroom.data.models.NamedLocation? {
        val list = _namedLocations.value
        if (list.isEmpty()) return null
        var bestLoc: com.videoroom.data.models.NamedLocation? = null
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
        onComplete: (com.videoroom.data.models.NamedLocation?) -> Unit = {},
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
     *  refresh. */
    fun setVideoLocations(
        videoIds: List<String>,
        latitude: Double,
        longitude: Double,
        writeToFile: Boolean,
        onComplete: () -> Unit = {},
    ) {
        if (videoIds.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            for (id in videoIds) {
                if (repository.updateVideoLocation(id, latitude, longitude, 0.0, writeToFile)) ok++
            }
            logger.info("Updated location on $ok/${videoIds.size} video(s)")
            loadVideoLocations()
            loadVideos()
            onComplete()
        }
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
            loadFilterOptions()
            loadVideos()
            onComplete()
        }
    }

    /**
     * Apply [keyword] to every video in [videoIds]. If the tag doesn't exist yet
     * it's created. Refreshes the tag list (for updated counts) and the
     * detail-panel metadata afterwards.
     */
    fun applyKeyword(keyword: String, videoIds: List<String>, onComplete: () -> Unit = {}) {
        val name = keyword.trim()
        if (name.isEmpty() || videoIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val tag = repository.createTag(name)
                if (tag == null) {
                    _error.value = "Failed to create/get tag '$name'"
                    return@launch
                }
                if (!repository.tagVideos(videoIds, tag.id)) {
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

    /** Remove [tagId] from every video in [videoIds]. */
    fun removeKeyword(tagId: String, videoIds: List<String>, onComplete: () -> Unit = {}) {
        if (tagId.isEmpty() || videoIds.isEmpty()) return
        viewModelScope.launch {
            try {
                if (!repository.untagVideos(videoIds, tagId)) {
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
        loadVideos()
    }

    fun setCollection(id: String?) {
        collectionId = id
        loadVideos()
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
                // Retry up to 3 times with short back-off. The first attempt
                // can fail with an empty gRPC stream when many cards load
                // simultaneously (connection under load) or when a newly-added
                // video's thumbnail file hasn't been flushed yet.
                var attempt = 0
                while (attempt <= 2 && !_thumbnails.value.containsKey(videoId)) {
                    val data = thumbnailSemaphore.withPermit {
                        repository.getThumbnail(videoId, "medium")
                    }
                    if (data != null) {
                        _thumbnails.update { it + (videoId to data) }
                        break
                    }
                    attempt++
                    if (attempt <= 2) delay(500L * attempt)
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

    fun openVideoInExternal(path: String) {
        viewModelScope.launch {
            try {
                val file = java.io.File(path)
                if (file.exists()) {
                    java.awt.Desktop.getDesktop().open(file)
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
                loadFilterOptions()
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
            loadFilterOptions()
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
        _selectedVideoIds.value = emptyList()
        _anchorVideoId.value = null
        _totalCount.value = 0
        _hasMore.value = false
        _isLoading.value = false
        _error.value = null
        _libraryLocations.value = emptyList()
        _selectedLocationPath.value = ""
        _tags.value = emptyList()
        _filterTagId.value = ""
        _filterCamera.value = ""
        _filterLens.value = ""
        _filterCodec.value = ""
        _filterCaptureYear.value = 0
        _filterOptions.value = com.videoroom.data.models.FilterOptions()
        _filterMinRating.value = 0
        _filterColorLabel.value = ""
        _topSlots.value = com.videoroom.data.models.defaultGridTopSlots
        _thumbnails.value = emptyMap()
        _scrubFrames.value = emptyMap()
        _scanStatus.value = null
        searchQuery = ""
        currentPage = 0
        collapseAllStacks()
    }
}
