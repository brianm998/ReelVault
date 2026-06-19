// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.reelvault.android.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reelvault.data.models.AttributeFilterState
import com.reelvault.data.models.CatalogEvent
import com.reelvault.data.models.CatalogEventKind
import com.reelvault.data.models.Collection
import com.reelvault.data.models.LibraryLocation
import com.reelvault.data.models.MetadataFilter
import com.reelvault.data.models.OrientationFilterState
import com.reelvault.data.models.PostIndexProgress
import com.reelvault.data.models.SmartCollectionFilters
import com.reelvault.data.models.Tag
import com.reelvault.data.models.VideoSummary
import com.reelvault.data.models.derivedAttributeMetadataFilters
import com.reelvault.data.models.METADATA_NEGATE_PREFIX
import com.reelvault.data.models.METADATA_VALUE_SEPARATOR
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
    private val appContext: Context,
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

    /** True when the currently-selected collection is a smart collection (its
     *  filters have been expanded client-side; no collectionId is sent to the
     *  daemon). Drives the empty-state message in the grid. */
    private val _selectedCollectionIsSmart = MutableStateFlow(false)
    val selectedCollectionIsSmart: StateFlow<Boolean> = _selectedCollectionIsSmart.asStateFlow()

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

    // ── Attribute / orientation / audio filter state ──────────────────────
    // Independent (user-set) attribute filters exposed in the filter sheet.
    // These are combined with smart-collection attribute filters (OR? No —
    // combined via tightest constraint) in the listVideos call: if a smart
    // collection says Yes and the user says Any, Yes wins; same semantics as
    // the desktop AttributeFilterState.merge helper.

    private val _filterHasLocation = MutableStateFlow(AttributeFilterState.Any)
    val filterHasLocation: StateFlow<AttributeFilterState> = _filterHasLocation.asStateFlow()

    private val _filterHasKeywords = MutableStateFlow(AttributeFilterState.Any)
    val filterHasKeywords: StateFlow<AttributeFilterState> = _filterHasKeywords.asStateFlow()

    private val _filterHasProxies = MutableStateFlow(AttributeFilterState.Any)
    val filterHasProxies: StateFlow<AttributeFilterState> = _filterHasProxies.asStateFlow()

    private val _filterFullResolution = MutableStateFlow(AttributeFilterState.Any)
    val filterFullResolution: StateFlow<AttributeFilterState> = _filterFullResolution.asStateFlow()

    private val _filterHasAudio = MutableStateFlow(AttributeFilterState.Any)
    val filterHasAudio: StateFlow<AttributeFilterState> = _filterHasAudio.asStateFlow()

    private val _filterOrientation = MutableStateFlow(OrientationFilterState.Any)
    val filterOrientation: StateFlow<OrientationFilterState> = _filterOrientation.asStateFlow()

    // ── Smart collection expanded filter state ────────────────────────────
    // When a smart collection is selected, its filterJson is parsed and these
    // fields are populated instead of sending collectionId to the daemon.
    // Mirrors the desktop GridViewModel's applySmartFiltersToBar logic.

    /** The id of the smart collection currently driving the filter state, or
     *  null when the user is viewing a manual collection or no collection. */
    private var activeSmartCollectionId: String? = null

    /** Metadata column constraints expanded from the active smart collection.
     *  Sent to the daemon as generic metadataFilters. */
    private var smartMetadataFilters = emptyList<MetadataFilter>()

    private var smartHasLocation = AttributeFilterState.Any
    private var smartHasKeywords = AttributeFilterState.Any
    private var smartHasProxies = AttributeFilterState.Any
    private var smartFullResolution = AttributeFilterState.Any

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
                    metadataFilters = mergedMetadataFilters(),
                    hasLocation = mergeAttr(smartHasLocation, _filterHasLocation.value),
                    hasKeywords = mergeAttr(smartHasKeywords, _filterHasKeywords.value),
                    hasProxies = mergeAttr(smartHasProxies, _filterHasProxies.value),
                    fullResolution = mergeAttr(smartFullResolution, _filterFullResolution.value),
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
                _error.value = appContext.getString(R.string.err_load_more_videos, e.message ?: "")
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

    /** Narrow to videos in [id]. Null clears the collection filter.
     *
     *  For smart collections the daemon never parses filter_json — member rows
     *  don't exist — so we parse it client-side and expand its criteria into the
     *  individual ListVideos filter params, matching the desktop/iOS behaviour. */
    fun setCollectionFilter(id: String?) {
        if (_selectedCollectionId.value == id) return
        // Clear any previous smart-collection filter state.
        clearSmartCollectionFilters()
        _selectedCollectionId.value = id
        val col = _collections.value.firstOrNull { it.id == id }
        if (col != null && col.isSmart && col.filterJson.isNotBlank()) {
            // Smart collection: expand its filters into individual params so the
            // daemon receives a normal ListVideos request without a collectionId.
            val f = SmartCollectionFilters.fromJson(col.filterJson)
            applySmartFilters(id!!, f)
        } else {
            // Manual collection (or clearing): send the id to the daemon.
            collectionId = id
        }
        reloadFromTop(showSpinner = true)
    }

    /** Parse a [SmartCollectionFilters] and store its criteria into the internal
     *  filter fields that are forwarded to every ListVideos call. Does NOT trigger
     *  a reload — the caller is responsible. */
    private fun applySmartFilters(id: String, f: SmartCollectionFilters) {
        activeSmartCollectionId = id
        _selectedCollectionIsSmart.value = true
        collectionId = null  // Smart: never send collectionId to the daemon.

        // Tag IDs travel via filterTags; the grid/count paths both use them.
        filterTags = f.tagIds

        // Geo filter.
        if (f.hasGeo) {
            _filterLocation.value = Triple(f.geoLat, f.geoLon, f.geoRadiusKm)
        }

        // Location path(s) — join with newline like the desktop does.
        if (f.locationPaths.isNotEmpty()) {
            locationPathFilter = f.locationPaths.joinToString("\n")
        }

        // Rating and color label.
        if (f.minRating > 0) _filterMinRating.value = f.minRating
        if (f.colorLabel.isNotEmpty()) _filterColorLabel.value = f.colorLabel

        // Search query from the saved filter.
        if (f.searchQuery.isNotEmpty()) _searchQuery.value = f.searchQuery

        // Tri-state attribute filters.
        smartHasLocation = f.hasLocation
        smartHasKeywords = f.hasKeywords
        smartHasProxies = f.hasProxies
        smartFullResolution = f.fullResolution

        // Metadata column constraints (camera, lens, codec, year, ISO, …) plus
        // the derived has-audio / orientation filters that ride as MetadataFilters.
        smartMetadataFilters = f.columns
            .filter { it.values.isNotEmpty() }
            .map { col ->
                val value = col.values.joinToString(METADATA_VALUE_SEPARATOR)
                val wire = if (col.negate) METADATA_NEGATE_PREFIX + value else value
                MetadataFilter(col.key, wire)
            } + derivedAttributeMetadataFilters(f.hasAudio, f.orientation)
    }

    /** Reset all smart-collection-expanded filter fields back to their defaults.
     *  Called whenever a new collection is selected (to avoid stale state leaking
     *  from the previous smart collection into the next selection). */
    private fun clearSmartCollectionFilters() {
        if (activeSmartCollectionId == null) return
        activeSmartCollectionId = null
        _selectedCollectionIsSmart.value = false
        filterTags = emptyList()
        _filterTagId.value = ""
        _filterLocation.value = null
        _filterLocationLabel.value = null
        locationPathFilter = ""
        _filterMinRating.value = 0
        _filterColorLabel.value = ""
        _searchQuery.value = ""
        smartHasLocation = AttributeFilterState.Any
        smartHasKeywords = AttributeFilterState.Any
        smartHasProxies = AttributeFilterState.Any
        smartFullResolution = AttributeFilterState.Any
        smartMetadataFilters = emptyList()
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

    fun setHasLocationFilter(state: AttributeFilterState) {
        if (_filterHasLocation.value == state) return
        _filterHasLocation.value = state
        reloadFromTop(showSpinner = true)
    }

    fun setHasKeywordsFilter(state: AttributeFilterState) {
        if (_filterHasKeywords.value == state) return
        _filterHasKeywords.value = state
        reloadFromTop(showSpinner = true)
    }

    fun setHasProxiesFilter(state: AttributeFilterState) {
        if (_filterHasProxies.value == state) return
        _filterHasProxies.value = state
        reloadFromTop(showSpinner = true)
    }

    fun setFullResolutionFilter(state: AttributeFilterState) {
        if (_filterFullResolution.value == state) return
        _filterFullResolution.value = state
        reloadFromTop(showSpinner = true)
    }

    fun setHasAudioFilter(state: AttributeFilterState) {
        if (_filterHasAudio.value == state) return
        _filterHasAudio.value = state
        reloadFromTop(showSpinner = true)
    }

    fun setOrientationFilter(state: OrientationFilterState) {
        if (_filterOrientation.value == state) return
        _filterOrientation.value = state
        reloadFromTop(showSpinner = true)
    }

    /**
     * True when any library filter is currently narrowing the grid (search,
     * keyword, geo location, library path, min rating, colour, attributes,
     * orientation). Excludes the collection selection — an empty collection
     * has its own message and isn't a "filter". Drives the empty-state
     * "Reset filter" affordance.
     */
    fun hasActiveLibraryFilter(): Boolean =
        _searchQuery.value.isNotEmpty() ||
            _filterTagId.value.isNotEmpty() ||
            _filterLocation.value != null ||
            _filterMinRating.value > 0 ||
            _filterColorLabel.value.isNotEmpty() ||
            locationPathFilter.isNotEmpty() ||
            _filterHasLocation.value != AttributeFilterState.Any ||
            _filterHasKeywords.value != AttributeFilterState.Any ||
            _filterHasProxies.value != AttributeFilterState.Any ||
            _filterFullResolution.value != AttributeFilterState.Any ||
            _filterHasAudio.value != AttributeFilterState.Any ||
            _filterOrientation.value != OrientationFilterState.Any

    /**
     * Clear every active library filter at once and reload. Leaves the
     * collection selection intact (mirrors desktop/iOS clearLibraryFilter).
     */
    fun clearAllFilters() {
        var changed = false
        if (_searchQuery.value.isNotEmpty()) { _searchQuery.value = ""; changed = true }
        if (_filterTagId.value.isNotEmpty()) { _filterTagId.value = ""; filterTags = emptyList(); changed = true }
        if (_filterLocation.value != null) {
            _filterLocation.value = null; _filterLocationLabel.value = null; changed = true
        }
        if (_filterMinRating.value != 0) { _filterMinRating.value = 0; changed = true }
        if (_filterColorLabel.value.isNotEmpty()) { _filterColorLabel.value = ""; changed = true }
        if (locationPathFilter.isNotEmpty()) { locationPathFilter = ""; changed = true }
        if (_filterHasLocation.value != AttributeFilterState.Any) { _filterHasLocation.value = AttributeFilterState.Any; changed = true }
        if (_filterHasKeywords.value != AttributeFilterState.Any) { _filterHasKeywords.value = AttributeFilterState.Any; changed = true }
        if (_filterHasProxies.value != AttributeFilterState.Any) { _filterHasProxies.value = AttributeFilterState.Any; changed = true }
        if (_filterFullResolution.value != AttributeFilterState.Any) { _filterFullResolution.value = AttributeFilterState.Any; changed = true }
        if (_filterHasAudio.value != AttributeFilterState.Any) { _filterHasAudio.value = AttributeFilterState.Any; changed = true }
        if (_filterOrientation.value != OrientationFilterState.Any) { _filterOrientation.value = OrientationFilterState.Any; changed = true }
        if (changed) reloadFromTop(showSpinner = true)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: per-card rating + color label
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Apply a 0..5 star [rating] to [videoIds]. Optimistically updates the
     * in-memory video list so the stars repaint without a round-trip, then
     * persists via the repository. Mirrors the kit GridViewModel.setRating.
     */
    fun setRating(videoIds: List<String>, rating: Int) {
        val clamped = rating.coerceIn(0, 5)
        val ids = videoIds.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return
        // Optimistic update.
        _videos.value = _videos.value.map { v ->
            if (v.id in ids) v.copy(rating = clamped) else v
        }
        viewModelScope.launch {
            try {
                repository.updateVideoRating(ids.toList(), clamped)
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.err_update_rating, e.message ?: "")
                // No rollback — a background reload will restore the real value.
            }
        }
    }

    /**
     * Apply a colour [label] ("red", "yellow", "green", "blue", "purple") or
     * "" to clear, for [videoIds]. Optimistically updates the in-memory list,
     * then persists. Mirrors the kit GridViewModel.setColorLabel.
     */
    fun setColorLabel(videoIds: List<String>, label: String) {
        val ids = videoIds.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return
        // Optimistic update.
        _videos.value = _videos.value.map { v ->
            if (v.id in ids) v.copy(colorLabel = label) else v
        }
        viewModelScope.launch {
            try {
                repository.updateVideoColorLabel(ids.toList(), label)
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.err_update_color_label, e.message ?: "")
            }
        }
    }

    /**
     * Create or reuse a tag [keyword] and apply it to every video in [videoIds].
     * Optimistically adds the tag name to the in-memory video list so the keyword
     * badge appears immediately. Mirrors desktop GridViewModel.applyKeyword and
     * iOS kit GridViewModel.applyKeyword.
     */
    fun applyKeyword(keyword: String, videoIds: List<String>, onComplete: () -> Unit = {}) {
        val name = keyword.trim()
        if (name.isEmpty() || videoIds.isEmpty()) return
        val ids = videoIds.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return
        // Optimistic update — add the name to the tag list so the badge appears.
        _videos.value = _videos.value.map { v ->
            if (v.id in ids && name !in v.tags) v.copy(tags = v.tags + name) else v
        }
        viewModelScope.launch {
            try {
                val tag = repository.createTag(name)
                if (tag == null) {
                    _error.value = appContext.getString(R.string.err_apply_keyword, name)
                    return@launch
                }
                if (!repository.tagVideos(ids.toList(), tag.id)) {
                    _error.value = appContext.getString(R.string.err_apply_keyword, name)
                    return@launch
                }
                loadTags()
                onComplete()
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.err_apply_keyword_detail, e.message ?: "")
            }
        }
    }

    /**
     * Add [videoIds] to the manual collection identified by [collectionId].
     * Mirrors desktop GridViewModel.addToCollection.
     */
    fun addToCollection(videoIds: List<String>, collectionId: String) {
        if (videoIds.isEmpty() || collectionId.isEmpty()) return
        viewModelScope.launch {
            try {
                if (!repository.addToCollection(videoIds, collectionId)) {
                    _error.value = appContext.getString(R.string.err_add_to_collection)
                }
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.err_add_to_collection_detail, e.message ?: "")
            }
        }
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
        _selectedCollectionIsSmart.value = false
        activeSmartCollectionId = null
        smartMetadataFilters = emptyList()
        smartHasLocation = AttributeFilterState.Any
        smartHasKeywords = AttributeFilterState.Any
        smartHasProxies = AttributeFilterState.Any
        smartFullResolution = AttributeFilterState.Any
        locationPathFilter = ""
        _searchQuery.value = ""
        _filterMinRating.value = 0
        _filterColorLabel.value = ""
        _filterHasLocation.value = AttributeFilterState.Any
        _filterHasKeywords.value = AttributeFilterState.Any
        _filterHasProxies.value = AttributeFilterState.Any
        _filterFullResolution.value = AttributeFilterState.Any
        _filterHasAudio.value = AttributeFilterState.Any
        _filterOrientation.value = OrientationFilterState.Any
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

    /**
     * Combine a smart-collection attribute state with a user-set attribute
     * state. When both are [AttributeFilterState.Any] the result is Any;
     * otherwise the tightest non-Any state wins (smart or user, whichever
     * is set). If they disagree (one says Yes, the other No) the smart
     * collection wins — that's a rare edge case and matches the desktop.
     */
    private fun mergeAttr(smart: AttributeFilterState, user: AttributeFilterState): AttributeFilterState =
        when {
            smart != AttributeFilterState.Any -> smart
            else -> user
        }

    /**
     * Merge the smart-collection metadata filters with user-set derived
     * attribute metadata filters (has_audio, orientation). The smart
     * collection's list already contains its own has_audio/orientation
     * entries; user-set ones append when the smart list doesn't already
     * cover the same key.
     */
    private fun mergedMetadataFilters(): List<MetadataFilter> {
        val derived = derivedAttributeMetadataFilters(
            _filterHasAudio.value,
            _filterOrientation.value,
        )
        if (derived.isEmpty()) return smartMetadataFilters
        // Skip derived keys that the smart collection already covers.
        val smartKeys = smartMetadataFilters.map { it.key }.toSet()
        val extra = derived.filter { it.key !in smartKeys }
        return smartMetadataFilters + extra
    }

    private fun handleCatalogEvent(event: CatalogEvent) {
        when (event.kind) {
            CatalogEventKind.WatcherStarted ->
                _liveUpdatesEnabled.value = true

            CatalogEventKind.WatcherDisabled ->
                _liveUpdatesEnabled.value = false

            CatalogEventKind.ScanStarted -> {
                val target = if (event.path.isBlank()) appContext.getString(R.string.scan_target_default)
                             else event.path.substringAfterLast('/')
                _watcherBanner.value = appContext.getString(R.string.scan_scanning_target, target)
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
                _incomingPairingDevice.value = event.message.ifBlank { appContext.getString(R.string.pairing_a_device) }

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
                    metadataFilters = mergedMetadataFilters(),
                    hasLocation = mergeAttr(smartHasLocation, _filterHasLocation.value),
                    hasKeywords = mergeAttr(smartHasKeywords, _filterHasKeywords.value),
                    hasProxies = mergeAttr(smartHasProxies, _filterHasProxies.value),
                    fullResolution = mergeAttr(smartFullResolution, _filterFullResolution.value),
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
                _error.value = appContext.getString(R.string.err_load_videos, e.message ?: "")
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
            return GridViewModel(repository, prefs, context.applicationContext) as T
        }
    }
}
