// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reelvault.data.models.CatalogEvent
import com.reelvault.data.models.CatalogEventKind
import com.reelvault.data.models.Collection
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.models.PostIndexProgress
import com.reelvault.data.models.Tag
import com.reelvault.data.models.VideoSummary
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PREF_SORT_FIELD = "sortField"
private const val PREF_SORT_ASCENDING = "sortAscending"
private const val PREF_VIEW_MODE = "viewMode"
private const val DEFAULT_VIEW_MODE = "grid"

private const val POST_INDEX_LINGER_MS = 3_500L

/**
 * Android ViewModel for the video grid screen.
 *
 * Mirrors the desktop GridViewModel's state and logic, adapted to:
 * - Extend [ViewModel] (lifecycle-aware, survives configuration changes)
 * - Use [viewModelScope] instead of a manually-managed CoroutineScope
 * - Read/write preferences via Android [SharedPreferences]
 * - Expose state as [StateFlow] (no Compose-runtime dependency)
 */
class GridViewModel(
    private val repository: VideoRepository,
    private val prefs: SharedPreferences,
) : ViewModel() {

    // ── Video list ────────────────────────────────────────────────────────

    private val _videos = MutableStateFlow<List<VideoSummary>>(emptyList())
    val videos: StateFlow<List<VideoSummary>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** True once the first load has completed (success or failure). Guards
     *  against showing "No videos" before the first response arrives. */
    private val _hasLoadedOnce = MutableStateFlow(false)
    val hasLoadedOnce: StateFlow<Boolean> = _hasLoadedOnce.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _totalCount = MutableStateFlow(0L)
    val totalCount: StateFlow<Long> = _totalCount.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // ── Selection ─────────────────────────────────────────────────────────

    private val _selectedVideoId = MutableStateFlow<String?>(null)
    val selectedVideoId: StateFlow<String?> = _selectedVideoId.asStateFlow()

    // ── Search ────────────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── Library locations ─────────────────────────────────────────────────

    private val _libraryLocations = MutableStateFlow<List<LibraryLocation>>(emptyList())
    val libraryLocations: StateFlow<List<LibraryLocation>> = _libraryLocations.asStateFlow()

    // ── Tags ──────────────────────────────────────────────────────────────

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    // ── Collections ───────────────────────────────────────────────────────

    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    val collections: StateFlow<List<Collection>> = _collections.asStateFlow()

    // ── View mode (persisted) ─────────────────────────────────────────────

    private val _viewMode = MutableStateFlow(
        prefs.getString(PREF_VIEW_MODE, DEFAULT_VIEW_MODE) ?: DEFAULT_VIEW_MODE
    )
    /** "grid" or "list". Persisted across process restarts via SharedPreferences. */
    val viewMode: StateFlow<String> = _viewMode.asStateFlow()

    // ── Sort (persisted) ──────────────────────────────────────────────────

    private var sortBy: String = prefs.getString(PREF_SORT_FIELD, "indexed_at") ?: "indexed_at"
    private var sortAscending: Boolean = prefs.getBoolean(PREF_SORT_ASCENDING, false)

    private val _currentSortField = MutableStateFlow(sortBy)
    val currentSortField: StateFlow<String> = _currentSortField.asStateFlow()

    private val _currentSortAscending = MutableStateFlow(sortAscending)
    val currentSortAscending: StateFlow<Boolean> = _currentSortAscending.asStateFlow()

    // ── Filter state ──────────────────────────────────────────────────────

    private val _filterTagId = MutableStateFlow("")
    val filterTagId: StateFlow<String> = _filterTagId.asStateFlow()

    private val _selectedCollectionId = MutableStateFlow<String?>(null)
    val selectedCollectionId: StateFlow<String?> = _selectedCollectionId.asStateFlow()

    private val _filterMinRating = MutableStateFlow(0)
    val filterMinRating: StateFlow<Int> = _filterMinRating.asStateFlow()

    private val _filterColorLabel = MutableStateFlow("")
    val filterColorLabel: StateFlow<String> = _filterColorLabel.asStateFlow()

    // ── Geographic (map) filter ───────────────────────────────────────────
    // Set when the user taps a location cluster on the map: (lat, lon, radiusKm).
    // Passed to the daemon as a proximity filter so the grid shows only videos
    // shot at that place. Mirrors the desktop/iOS `filterLocation`.

    private val _filterLocation = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val filterLocation: StateFlow<Triple<Double, Double, Double>?> = _filterLocation.asStateFlow()

    /** Human label for the active geo filter (place name or coordinates), or
     *  null when no geo filter is set. Drives the status-bar filter pill. */
    private val _filterLocationLabel = MutableStateFlow<String?>(null)
    val filterLocationLabel: StateFlow<String?> = _filterLocationLabel.asStateFlow()

    // ── Map focus ("Show on Map") ──────────────────────────────────────────
    // Set by the detail view's "Show on Map" action; the map screen consumes it
    // to recenter the camera on that point, then clears it. (lat, lon, radiusKm).
    // Mirrors iOS GridViewModel.mapFocus — an in-app focus, not Apple/Google Maps.

    private val _mapFocus = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val mapFocus: StateFlow<Triple<Double, Double, Double>?> = _mapFocus.asStateFlow()

    // ── Catalog events ────────────────────────────────────────────────────

    private val _liveUpdatesEnabled = MutableStateFlow(true)
    val liveUpdatesEnabled: StateFlow<Boolean> = _liveUpdatesEnabled.asStateFlow()

    private val _watcherBanner = MutableStateFlow<String?>(null)
    val watcherBanner: StateFlow<String?> = _watcherBanner.asStateFlow()

    private val _incomingPairingDevice = MutableStateFlow<String?>(null)
    val incomingPairingDevice: StateFlow<String?> = _incomingPairingDevice.asStateFlow()

    private val _postIndexProgress = MutableStateFlow<PostIndexProgress?>(null)
    val postIndexProgress: StateFlow<PostIndexProgress?> = _postIndexProgress.asStateFlow()

    // ── Internal pagination / job tracking ────────────────────────────────

    private val pageSize = 50
    private var currentPage = 0
    private var filterTags = emptyList<String>()
    private var collectionId: String? = null
    private var locationPathFilter: String = ""

    private var listLoadJob: Job? = null
    private var catalogEventsJob: Job? = null
    private var watcherRefreshJob: Job? = null
    private var postIndexClearJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────
    // Public API: loading
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Background refresh — used by catalog events and watcher notifications.
     * Leaves the current grid in place while the new page loads (no spinner),
     * matching the desktop behaviour.
     */
    fun loadVideos() {
        reloadFromTop(showSpinner = false)
    }

    /**
     * Load more videos (pagination). No-op when a load is already in flight
     * or there are no more pages.
     */
    fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return

        _isLoading.value = true
        _error.value = null

        val offset = _videos.value.size
        currentPage = offset / pageSize

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
                    searchQuery = _searchQuery.value,
                )
                val existing = _videos.value
                val merged = existing + newVideos.filter { n -> existing.none { it.id == n.id } }
                _videos.value = merged
                _totalCount.value = totalCount
                _hasMore.value = merged.size < totalCount
                _isLoading.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Failed to load more videos: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /** Load library locations from the backend. */
    fun loadLibraryLocations() {
        viewModelScope.launch {
            try {
                _libraryLocations.value = repository.listLibraryLocations()
            } catch (e: Exception) {
                // Non-fatal — sidebar just stays empty.
            }
        }
    }

    /** Load tags (keywords) with usage counts. */
    fun loadTags() {
        viewModelScope.launch {
            try {
                _tags.value = repository.listTags().sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                // Non-fatal.
            }
        }
    }

    /** Load collections (manual + smart). */
    fun loadCollections() {
        viewModelScope.launch {
            try {
                _collections.value = repository.listCollections()
            } catch (e: Exception) {
                // Non-fatal.
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: search
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Update the full-text search query and reload the grid.
     * No-op when the query hasn't changed.
     */
    fun searchVideos(query: String) {
        if (_searchQuery.value == query) return
        _searchQuery.value = query
        reloadFromTop(showSpinner = true)
    }

    /** Clear the search query and show all videos. */
    fun clearSearch() {
        if (_searchQuery.value.isEmpty()) return
        _searchQuery.value = ""
        reloadFromTop(showSpinner = true)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: selection
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Select a video by its ID. The detail screen observes [selectedVideoId]
     * and loads metadata accordingly.
     */
    fun selectVideo(id: String) {
        _selectedVideoId.value = id
    }

    /** Clear the selection (detail panel returns to empty state). */
    fun clearSelection() {
        _selectedVideoId.value = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: sort
    // ─────────────────────────────────────────────────────────────────────

    fun setSort(field: String, ascending: Boolean) {
        sortBy = field
        sortAscending = ascending
        _currentSortField.value = field
        _currentSortAscending.value = ascending
        prefs.edit()
            .putString(PREF_SORT_FIELD, field)
            .putBoolean(PREF_SORT_ASCENDING, ascending)
            .apply()
        reloadFromTop(showSpinner = true)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: filters
    // ─────────────────────────────────────────────────────────────────────

    /** Filter the grid to videos tagged with [tagId]. Empty string clears. */
    fun setTagFilter(tagId: String) {
        if (_filterTagId.value == tagId) return
        _filterTagId.value = tagId
        filterTags = if (tagId.isEmpty()) emptyList() else listOf(tagId)
        reloadFromTop(showSpinner = true)
    }

    /** Narrow to videos in [id]. Null clears the collection filter. */
    fun setCollectionFilter(id: String?) {
        if (_selectedCollectionId.value == id) return
        _selectedCollectionId.value = id
        collectionId = id
        reloadFromTop(showSpinner = true)
    }

    /** Narrow to videos within [path]. Empty string shows all. */
    fun setLocationFilter(path: String) {
        if (locationPathFilter == path) return
        locationPathFilter = path
        reloadFromTop(showSpinner = true)
    }

    /**
     * Narrow the grid to videos within [radiusKm] of ([latitude], [longitude]).
     * Invoked when the user taps a location cluster on the map. [label] is the
     * place name (or coordinates) shown in the status-bar filter pill.
     */
    fun setGeoLocationFilter(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        label: String? = null,
    ) {
        _filterLocation.value = Triple(latitude, longitude, radiusKm)
        _filterLocationLabel.value = label
        reloadFromTop(showSpinner = true)
    }

    /** Drop the geographic filter and show all videos again. */
    fun clearGeoLocationFilter() {
        if (_filterLocation.value == null) return
        _filterLocation.value = null
        _filterLocationLabel.value = null
        reloadFromTop(showSpinner = true)
    }

    /**
     * Ask the map to recenter on ([latitude], [longitude]) — the detail view's
     * "Show on Map" action. The map consumes this on its next composition and
     * calls [clearMapFocus].
     */
    fun setMapFocus(latitude: Double, longitude: Double, radiusKm: Double = 1.0) {
        _mapFocus.value = Triple(latitude, longitude, radiusKm)
    }

    /** Clear a consumed map-focus request. */
    fun clearMapFocus() {
        _mapFocus.value = null
    }

    fun setMinRatingFilter(n: Int) {
        if (_filterMinRating.value == n) return
        _filterMinRating.value = n
        reloadFromTop(showSpinner = true)
    }

    fun setColorLabelFilter(label: String) {
        if (_filterColorLabel.value == label) return
        _filterColorLabel.value = label
        reloadFromTop(showSpinner = true)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: view mode
    // ─────────────────────────────────────────────────────────────────────

    /** Switch between "grid" and "list" view. Persisted to SharedPreferences. */
    fun setViewMode(mode: String) {
        if (_viewMode.value == mode) return
        _viewMode.value = mode
        prefs.edit().putString(PREF_VIEW_MODE, mode).apply()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: catalog events
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Open (or re-open) the long-lived catalog events stream. Retries with
     * exponential backoff (same schedule as the desktop client: 2, 4, 8, 16,
     * 32, 60 s, then 60 s) and suppresses duplicate error logs.
     */
    fun startCatalogEventStream() {
        catalogEventsJob?.cancel()
        catalogEventsJob = viewModelScope.launch {
            var attempt = 0
            var lastErrorSignature: String? = null
            while (isActive && _liveUpdatesEnabled.value) {
                try {
                    repository.subscribeCatalogEvents().collect { event ->
                        if (attempt > 0 || lastErrorSignature != null) {
                            attempt = 0
                            lastErrorSignature = null
                        }
                        handleCatalogEvent(event)
                    }
                    lastErrorSignature = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val signature = e.message ?: e::class.simpleName ?: "unknown"
                    if (signature != lastErrorSignature) {
                        lastErrorSignature = signature
                    }
                }
                if (!isActive || !_liveUpdatesEnabled.value) break
                attempt++
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

    fun dismissIncomingPairing() {
        _incomingPairingDevice.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Reset all per-session state back to defaults — call when the user
     * disconnects so a later connection (possibly to a *different* library)
     * starts clean. Because this view-model is activity-scoped (shared across
     * the Grid/Map/Detail destinations) it outlives the connection, so without
     * this the previous session's filters — including a geo filter and its
     * status-bar pill — would leak into the next one. Persisted preferences
     * (sort field/direction, view mode) are intentionally left intact.
     */
    fun resetForNewSession() {
        listLoadJob?.cancel()
        stopCatalogEventStream()
        _filterLocation.value = null
        _filterLocationLabel.value = null
        _mapFocus.value = null
        filterTags = emptyList()
        _filterTagId.value = ""
        collectionId = null
        _selectedCollectionId.value = null
        locationPathFilter = ""
        _searchQuery.value = ""
        _filterMinRating.value = 0
        _filterColorLabel.value = ""
        _selectedVideoId.value = null
        _videos.value = emptyList()
        _totalCount.value = 0L
        _hasMore.value = false
        _hasLoadedOnce.value = false
        _error.value = null
        _watcherBanner.value = null
        _incomingPairingDevice.value = null
        _postIndexProgress.value = null
        _liveUpdatesEnabled.value = true
        currentPage = 0
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun handleCatalogEvent(event: CatalogEvent) {
        when (event.kind) {
            CatalogEventKind.WatcherStarted ->
                _liveUpdatesEnabled.value = true

            CatalogEventKind.WatcherDisabled ->
                _liveUpdatesEnabled.value = false

            CatalogEventKind.ScanStarted -> {
                val target = if (event.path.isBlank()) "library"
                             else event.path.substringAfterLast('/')
                _watcherBanner.value = "Scanning $target…"
            }

            CatalogEventKind.ScanCompleted -> {
                _watcherBanner.value = null
                scheduleWatcherRefresh()
            }

            CatalogEventKind.VideoAdded,
            CatalogEventKind.VideoModified,
            CatalogEventKind.VideoRemoved ->
                scheduleWatcherRefresh()

            CatalogEventKind.PostIndexStarted,
            CatalogEventKind.PostIndexProgress -> {
                postIndexClearJob?.cancel()
                postIndexClearJob = null
                _postIndexProgress.value = event.postIndex
            }

            CatalogEventKind.PostIndexCompleted -> {
                scheduleWatcherRefresh()
                postIndexClearJob?.cancel()
                postIndexClearJob = viewModelScope.launch {
                    delay(POST_INDEX_LINGER_MS)
                    _postIndexProgress.value = null
                }
            }

            CatalogEventKind.PairingRequested ->
                _incomingPairingDevice.value = event.message.ifBlank { "A device" }

            CatalogEventKind.Unknown -> { /* future kinds */ }
        }
    }

    private fun scheduleWatcherRefresh() {
        watcherRefreshJob?.cancel()
        watcherRefreshJob = viewModelScope.launch {
            delay(500)
            loadVideos()
            loadLibraryLocations()
        }
    }

    /**
     * Core reload entry point.
     *
     * [showSpinner] true: clear the list immediately and show a spinner
     * (user-visible filter/sort/search changes).
     * [showSpinner] false: leave the existing rows and swap them silently
     * (background watcher/scan refreshes).
     */
    private fun reloadFromTop(showSpinner: Boolean) {
        if (!showSpinner && _isLoading.value) {
            // A user-initiated load is already in flight — don't interrupt it.
            return
        }
        listLoadJob?.cancel()
        if (showSpinner) {
            _videos.value = emptyList()
            _totalCount.value = 0L
            _hasMore.value = false
            _isLoading.value = true
        }
        _error.value = null
        currentPage = 0

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
                    searchQuery = _searchQuery.value,
                )
                _videos.value = videosList
                _totalCount.value = totalCount
                _hasMore.value = videosList.size < totalCount
                _isLoading.value = false
                _hasLoadedOnce.value = true

                // Drop a selection that is no longer in the result set.
                if (videosList.isEmpty() && _selectedVideoId.value != null) {
                    clearSelection()
                } else if (_selectedVideoId.value != null &&
                    videosList.none { it.id == _selectedVideoId.value }
                ) {
                    clearSelection()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Failed to load videos: ${e.message}"
                _isLoading.value = false
                _hasLoadedOnce.value = true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ViewModel lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopCatalogEventStream()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────

    class Factory(
        private val repository: VideoRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GridViewModel::class.java))
            val prefs = context.getSharedPreferences("reelvault_grid_prefs", Context.MODE_PRIVATE)
            return GridViewModel(repository, prefs) as T
        }
    }
}
