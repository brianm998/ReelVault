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
import com.reelvault.data.models.defaultGridTopSlots
import com.reelvault.data.models.derivedAttributeMetadataFilters
import com.reelvault.data.models.METADATA_NEGATE_PREFIX
import com.reelvault.data.models.METADATA_VALUE_SEPARATOR
import com.reelvault.data.repository.VideoRepository
import com.reelvault.data.models.VideoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_SORT_FIELD = "sortField"
private const val PREF_SORT_ASCENDING = "sortAscending"
private const val PREF_VIEW_MODE = "viewMode"
private const val DEFAULT_VIEW_MODE = "grid"
private const val PREF_GRID_DENSITY = "gridDensity"
/** Default density step (3 of 5) keeps roughly the same card size as before. */
private const val DEFAULT_GRID_DENSITY = 3

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

    // ── Navigation history (browser-style back/forward) ───────────────────
    // Activity-scoped → survives rotation; wiped in resetForNewSession(). The
    // recording trigger + restore funnel live in AppRouter (it owns the
    // NavController); this VM holds the stack and the restore-suppression latch.
    val navHistory = NavHistory()
    val canGoBack: StateFlow<Boolean> get() = navHistory.canGoBack
    val canGoForward: StateFlow<Boolean> get() = navHistory.canGoForward
    /** Target of an in-flight back/forward restore. While set, observer-driven
     *  [recordNav] calls are absorbed (a restore must not push a new entry); it
     *  is cleared the instant the settled state matches the target. Every
     *  back/forward step changes at least one recorded field, so a matching
     *  observer fire always arrives — the latch cannot stick (the failure mode
     *  of a fire-counting flag). */
    private var restoreExpected: NavEntry? = null

    // ── Grid density ──────────────────────────────────────────────────────
    // Integer in 1..5 (1 = many small columns, 5 = few large columns).
    // Maps to a minimum card width (dp) used with GridCells.Adaptive so the
    // grid reflows its column count in real time as the user drags the slider.
    // Persisted across process restarts.

    private val _gridDensity = MutableStateFlow(
        prefs.getInt(PREF_GRID_DENSITY, DEFAULT_GRID_DENSITY).coerceIn(1, 5)
    )
    /** Grid density step: 1 (smallest / most columns) to 5 (largest / fewest columns). */
    val gridDensity: StateFlow<Int> = _gridDensity.asStateFlow()

    // ── Top-of-card stat slots (loaded from catalog via GridSettings RPC) ─
    // Four [GridStatKey.raw] strings (padded/truncated to exactly 4).
    // Defaults to [defaultGridTopSlots] until [loadGridSettings] answers.
    private val _topSlots = MutableStateFlow(defaultGridTopSlots)
    val topSlots: StateFlow<List<String>> = _topSlots.asStateFlow()

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

    private val _mapFocusTrackJson = MutableStateFlow<String?>(null)
    val mapFocusTrackJson: StateFlow<String?> = _mapFocusTrackJson.asStateFlow()

    // ── Video locations (filtered map) ────────────────────────────────────
    // Snapshot of every geotagged video matching the current grid filters.
    // Refreshed asynchronously after each grid reload so the map always
    // plots the same set the grid shows. The geo-proximity filter itself is
    // excluded (the map draws the circle; applying it here would hide pins
    // the user is trying to inspect).

    private val _videoLocations = MutableStateFlow<List<VideoLocation>>(emptyList())
    /** GPS-tagged videos matching the current grid filters. Points the map. */
    val videoLocations: StateFlow<List<VideoLocation>> = _videoLocations.asStateFlow()

    private val _isLoadingVideoLocations = MutableStateFlow(false)
    /** True while the filtered locations load is in flight. */
    val isLoadingVideoLocations: StateFlow<Boolean> = _isLoadingVideoLocations.asStateFlow()

    private val _hasLoadedLocationOnce = MutableStateFlow(false)
    /** True after the first [loadVideoLocationsFilteredAsync] completes (success or error). */
    val hasLoadedLocationOnce: StateFlow<Boolean> = _hasLoadedLocationOnce.asStateFlow()

    private var locationsRefreshJob: Job? = null

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
    // Backed by an observable flow so the navigation-history recorder (AppRouter)
    // can watch source changes. The computed `var` keeps every existing read/write
    // site working unchanged while mirroring into the flow automatically — no
    // assignment site can be missed.
    private val _locationPathFlow = MutableStateFlow("")
    val locationPathFlow: StateFlow<String> = _locationPathFlow.asStateFlow()
    private var locationPathFilter: String
        get() = _locationPathFlow.value
        set(value) { _locationPathFlow.value = value }

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

    /** Snapshot of the live filter bar taken just before a smart collection
     *  overwrote it, so leaving the smart collection restores the user's
     *  previous filter rather than clearing everything. */
    private data class FilterSnapshot(
        val tags: List<String>,
        val tagId: String,
        val searchQuery: String,
        val minRating: Int,
        val colorLabel: String,
        val geo: Triple<Double, Double, Double>?,
        val locationPath: String,
        val hasLocation: AttributeFilterState,
        val hasKeywords: AttributeFilterState,
        val hasProxies: AttributeFilterState,
        val fullResolution: AttributeFilterState,
        val hasAudio: AttributeFilterState,
        val orientation: OrientationFilterState,
    )
    private var preSmartFilterSnapshot: FilterSnapshot? = null

    /** Metadata column constraints expanded from the active smart collection.
     *  Sent to the daemon as generic metadataFilters. */
    private var smartMetadataFilters = emptyList<MetadataFilter>()

    private var smartHasLocation = AttributeFilterState.Any
    private var smartHasKeywords = AttributeFilterState.Any
    private var smartHasProxies = AttributeFilterState.Any
    private var smartFullResolution = AttributeFilterState.Any

    // ── Scrub frames + loudness (detail view graphs) ──────────────────────
    // Mirrors the desktop GridViewModel's scrub-frame and loudness caches.
    // Keyed by videoId; populated lazily the first time the detail view for
    // that video is opened. Cached for the session (no eviction) to avoid
    // redundant round-trips — the daemon caches its side too.

    private val _scrubFrames = MutableStateFlow<Map<String, List<ByteArray?>>>(emptyMap())
    val scrubFrames: StateFlow<Map<String, List<ByteArray?>>> = _scrubFrames.asStateFlow()

    private val _hiResScrubFrames = MutableStateFlow<Map<String, List<ByteArray?>>>(emptyMap())
    val hiResScrubFrames: StateFlow<Map<String, List<ByteArray?>>> = _hiResScrubFrames.asStateFlow()

    /** Normalized momentary loudness series (0..1), or empty if no audio. A
     *  *missing* key means the fetch is still in flight; an empty list means the
     *  video has no audio track. Both states render differently in the graph. */
    private val _audioLoudness = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val audioLoudness: StateFlow<Map<String, List<Float>>> = _audioLoudness.asStateFlow()

    private val scrubLoading = mutableSetOf<String>()
    private val audioLoudnessLoading = mutableSetOf<String>()

    /** Fetch (once) the scrub-frame strip for [videoId] and cache it. */
    fun loadScrubFrames(videoId: String) {
        if (_scrubFrames.value.containsKey(videoId)) return
        if (videoId in scrubLoading) return
        scrubLoading.add(videoId)
        viewModelScope.launch {
            try {
                val frames = repository.getScrubFrames(videoId, count = 10)
                if (frames.any { it != null }) {
                    _scrubFrames.value = _scrubFrames.value + (videoId to frames)
                }
            } finally {
                scrubLoading.remove(videoId)
            }
        }
    }

    /** Fetch (once) the audio loudness series for [videoId] and cache it.
     *  Stores an empty list on success when the video has no audio — this
     *  distinguishes "loaded, no audio" from "still in flight" (absent key). */
    fun loadAudioLoudness(videoId: String) {
        if (_audioLoudness.value.containsKey(videoId)) return
        if (videoId in audioLoudnessLoading) return
        audioLoudnessLoading.add(videoId)
        viewModelScope.launch {
            try {
                val series = repository.getAudioLoudness(videoId)
                _audioLoudness.value = _audioLoudness.value + (videoId to series)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal: mark as loaded-but-empty so the UI stops spinning.
                _audioLoudness.value = _audioLoudness.value + (videoId to emptyList())
            } finally {
                audioLoudnessLoading.remove(videoId)
            }
        }
    }

    private var listLoadJob: Job? = null
    private var catalogEventsJob: Job? = null
    private var watcherRefreshJob: Job? = null
    private var postIndexClearJob: Job? = null
    private var gridSettingsSaveJob: Job? = null

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
    // Public API: navigation history (back/forward). See [NavHistory].

    /** The active grid source filters (collection + tag + location together —
     *  Android allows them to combine, unlike iOS's single section). */
    fun currentNavSource(): NavSource =
        NavSource(_selectedCollectionId.value, _filterTagId.value, locationPathFilter)

    /** Build the entry for a settled browse route from live state. [viewMode] is
     *  only meaningful for GRID, so it is normalised to "" elsewhere — a
     *  Detail/Map entry must compare equal regardless of the grid/list toggle. */
    private fun snapshotEntry(route: NavRoute, videoId: String?): NavEntry =
        NavEntry(
            route = route,
            source = currentNavSource(),
            viewMode = if (route == NavRoute.GRID) _viewMode.value else "",
            videoId = videoId,
            searchQuery = _searchQuery.value,
            filterMinRating = _filterMinRating.value,
            filterColorLabel = _filterColorLabel.value,
            filterHasLocation = _filterHasLocation.value,
            filterHasKeywords = _filterHasKeywords.value,
            filterHasProxies = _filterHasProxies.value,
            filterFullResolution = _filterFullResolution.value,
            filterHasAudio = _filterHasAudio.value,
            filterOrientation = _filterOrientation.value,
        )

    /** Record a settled browse location, unless a restore is in flight — then
     *  the fire is absorbed (and the latch released once the target is reached). */
    fun recordNav(route: NavRoute, videoId: String?) {
        val e = snapshotEntry(route, videoId)
        val expected = restoreExpected
        if (expected != null) {
            if (e == expected) restoreExpected = null
            return
        }
        navHistory.record(e)
    }

    /** Re-apply the non-route part of an entry (source + grid/list + selection +
     *  library filter state), routing through the existing setters so the
     *  smart-collection snapshot machinery runs identically to user-driven
     *  navigation. The collection is resolved FIRST because setCollectionFilter
     *  rewrites locationPathFilter; library filter state is restored LAST so any
     *  smart-collection side effects are overridden with the recorded values. */
    fun applyEntryState(e: NavEntry) {
        val s = e.source
        // Collection first: setCollectionFilter expands smart-collection filters
        // (and rewrites tag/location); the explicit tag/location writes that
        // follow then pin them to exactly the recorded values.
        setCollectionFilter(s.collectionId)
        setTagFilter(s.tagId)
        setLocationFilter(s.locationPath)
        if (e.route == NavRoute.GRID) setViewMode(e.viewMode)
        if (e.videoId != null) selectVideo(e.videoId)
        // Restore library filter state last.
        searchVideos(e.searchQuery)
        setMinRatingFilter(e.filterMinRating)
        setColorLabelFilter(e.filterColorLabel)
        setHasLocationFilter(e.filterHasLocation)
        setHasKeywordsFilter(e.filterHasKeywords)
        setHasProxiesFilter(e.filterHasProxies)
        setFullResolutionFilter(e.filterFullResolution)
        setHasAudioFilter(e.filterHasAudio)
        setOrientationFilter(e.filterOrientation)
    }

    /** Move the history cursor back/forward and latch the target so the resulting
     *  observer fires are absorbed. Returns null at the ends of the history. */
    fun consumeBack(): NavEntry? = navHistory.goBack()?.also { restoreExpected = it }
    fun consumeForward(): NavEntry? = navHistory.goForward()?.also { restoreExpected = it }

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
        // Leaving whatever we were viewing: if it was a smart collection, undo
        // the filters it injected so they don't linger into the next view.
        clearSmartCollectionFilters()
        _selectedCollectionId.value = id
        val col = _collections.value.firstOrNull { it.id == id }
        if (col != null && col.isSmart && col.filterJson.isNotBlank()) {
            // Snapshot the current (pre-smart-collection) manual filter so
            // leaving the smart collection can restore it. clearSmartCollectionFilters
            // ran above, so the live state here is the user's own manual filter.
            preSmartFilterSnapshot = FilterSnapshot(
                tags = filterTags,
                tagId = _filterTagId.value,
                searchQuery = _searchQuery.value,
                minRating = _filterMinRating.value,
                colorLabel = _filterColorLabel.value,
                geo = _filterLocation.value,
                locationPath = locationPathFilter,
                hasLocation = _filterHasLocation.value,
                hasKeywords = _filterHasKeywords.value,
                hasProxies = _filterHasProxies.value,
                fullResolution = _filterFullResolution.value,
                hasAudio = _filterHasAudio.value,
                orientation = _filterOrientation.value,
            )
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

    /** Parse a [SmartCollectionFilters] and write its criteria into ALL of the
     *  individual filter fields that are forwarded to every ListVideos call,
     *  unconditionally overwriting any pre-existing manual filter state so the
     *  grid shows exactly the smart collection's contents (mirrors desktop/iOS
     *  applySmartFiltersToBar). Does NOT trigger a reload — the caller is
     *  responsible. */
    private fun applySmartFilters(id: String, f: SmartCollectionFilters) {
        activeSmartCollectionId = id
        _selectedCollectionIsSmart.value = true
        collectionId = null  // Smart: never send collectionId to the daemon.

        // Tag IDs travel via filterTags; the grid/count paths both use them.
        // Always assigned (even empty) so a previous manual tag filter is cleared.
        filterTags = f.tagIds
        _filterTagId.value = f.tagIds.firstOrNull() ?: ""

        // Geo filter — null when the collection has no geo constraint so an
        // existing manual geo filter is cleared rather than left active.
        _filterLocation.value = if (f.hasGeo) Triple(f.geoLat, f.geoLon, f.geoRadiusKm) else null
        _filterLocationLabel.value = null

        // Location path(s) — join with newline like the desktop does.
        // Empty string clears any previous manual library-folder filter.
        locationPathFilter = f.locationPaths.joinToString("\n")

        // Rating and color label — always set so a manual filter is zeroed out
        // when the smart collection doesn't constrain those fields.
        _filterMinRating.value = f.minRating
        _filterColorLabel.value = f.colorLabel

        // Search query from the saved filter.
        _searchQuery.value = f.searchQuery

        // Tri-state attribute filters — always assigned (Any = unconstrained).
        smartHasLocation = f.hasLocation
        smartHasKeywords = f.hasKeywords
        smartHasProxies = f.hasProxies
        smartFullResolution = f.fullResolution
        // The user-side copies must also be reset to Any so mergeAttr picks up
        // the smart value cleanly (a leftover user Yes/No would further narrow).
        _filterHasLocation.value = AttributeFilterState.Any
        _filterHasKeywords.value = AttributeFilterState.Any
        _filterHasProxies.value = AttributeFilterState.Any
        _filterFullResolution.value = AttributeFilterState.Any
        _filterHasAudio.value = AttributeFilterState.Any
        _filterOrientation.value = OrientationFilterState.Any

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

    /** Undo the filter fields a smart collection expanded into the bar. Restores
     *  the snapshot captured when the smart collection was entered, so the user
     *  returns to exactly the filter they had before. Falls back to clearing all
     *  filters when there is no snapshot (e.g. the smart collection was the first
     *  thing selected). No-op when no smart collection is currently applied. */
    private fun clearSmartCollectionFilters() {
        if (activeSmartCollectionId == null) return
        activeSmartCollectionId = null
        _selectedCollectionIsSmart.value = false
        // Always clear the smart-only backing vars regardless of snapshot.
        smartHasLocation = AttributeFilterState.Any
        smartHasKeywords = AttributeFilterState.Any
        smartHasProxies = AttributeFilterState.Any
        smartFullResolution = AttributeFilterState.Any
        smartMetadataFilters = emptyList()
        val snap = preSmartFilterSnapshot
        preSmartFilterSnapshot = null
        if (snap != null) {
            // Restore the manual filter state that was active before the smart
            // collection overwrote it.
            filterTags = snap.tags
            _filterTagId.value = snap.tagId
            _searchQuery.value = snap.searchQuery
            _filterMinRating.value = snap.minRating
            _filterColorLabel.value = snap.colorLabel
            _filterLocation.value = snap.geo
            _filterLocationLabel.value = null
            locationPathFilter = snap.locationPath
            _filterHasLocation.value = snap.hasLocation
            _filterHasKeywords.value = snap.hasKeywords
            _filterHasProxies.value = snap.hasProxies
            _filterFullResolution.value = snap.fullResolution
            _filterHasAudio.value = snap.hasAudio
            _filterOrientation.value = snap.orientation
        } else {
            // No snapshot — reset manual filter fields to defaults.
            filterTags = emptyList()
            _filterTagId.value = ""
            _filterLocation.value = null
            _filterLocationLabel.value = null
            locationPathFilter = ""
            _filterMinRating.value = 0
            _filterColorLabel.value = ""
            _searchQuery.value = ""
            _filterHasLocation.value = AttributeFilterState.Any
            _filterHasKeywords.value = AttributeFilterState.Any
            _filterHasProxies.value = AttributeFilterState.Any
            _filterFullResolution.value = AttributeFilterState.Any
            _filterHasAudio.value = AttributeFilterState.Any
            _filterOrientation.value = OrientationFilterState.Any
        }
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

    fun setMapFocusTrack(trackJson: String?) {
        _mapFocusTrackJson.value = trackJson
    }

    /** Clear a consumed map-focus request (also clears the track polyline). */
    fun clearMapFocus() {
        _mapFocus.value = null
        _mapFocusTrackJson.value = null
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
    // Public API: stacking / grouping
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Combine the currently multi-selected [videoIds] into a single stack.
     * The first item in [videoIds] is used as the preferred representative
     * (matches desktop behaviour where the anchor/first selected wins).
     * Reloads the grid on success and clears the selection.
     * Mirrors desktop GridViewModel.groupSelectedVideos.
     */
    fun groupSelectedVideos(
        videoIds: List<String>,
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        if (videoIds.size < 2) {
            val msg = appContext.getString(R.string.stack_select_at_least_two)
            _error.value = msg
            onError(msg)
            return
        }
        val preferred = videoIds.first()
        viewModelScope.launch {
            try {
                val group = repository.createGroup(videoIds, name = "", preferredVideoId = preferred)
                if (group != null) {
                    val stackSize = group.size
                    val msg = appContext.resources.getQuantityString(
                        R.plurals.stack_combined_message, stackSize, stackSize
                    )
                    onSuccess(msg)
                    loadVideos()
                } else {
                    val msg = appContext.getString(R.string.stack_combine_failed)
                    _error.value = msg
                    onError(msg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = appContext.getString(R.string.stack_combine_error, e.message ?: "")
                _error.value = msg
                onError(msg)
            }
        }
    }

    /**
     * Remove [videoId] from its stack (group). The card's [groupId] is used
     * to refresh the representative list after the operation.
     * Mirrors desktop GridViewModel.removeFromStack.
     */
    fun removeFromStack(videoId: String, groupId: String) {
        if (videoId.isEmpty() || groupId.isEmpty()) return
        viewModelScope.launch {
            try {
                if (repository.ungroupVideo(videoId)) {
                    loadVideos()
                } else {
                    _error.value = appContext.getString(R.string.stack_remove_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.stack_remove_error, e.message ?: "")
            }
        }
    }

    /**
     * Dissolve an entire stack — ungroup every member so each becomes a
     * standalone video again. Mirrors desktop GridViewModel.unstackGroup.
     */
    fun unstackGroup(groupId: String) {
        if (groupId.isEmpty()) return
        viewModelScope.launch {
            try {
                val (members, _) = repository.listGroupMembers(groupId)
                members.forEach { member -> repository.ungroupVideo(member.id) }
                loadVideos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.stack_unstack_error, e.message ?: "")
            }
        }
    }

    /**
     * Promote [videoId] to be the preferred (representative) member of its
     * stack. Mirrors desktop GridViewModel.setStackMaster.
     */
    fun setStackMaster(videoId: String, groupId: String) {
        if (videoId.isEmpty() || groupId.isEmpty()) return
        // Optimistic update so the stack badge ticks to the new preferred ID.
        _videos.value = _videos.value.map { v ->
            if (v.groupId == groupId) v.copy(groupPreferredId = videoId) else v
        }
        viewModelScope.launch {
            try {
                if (!repository.setGroupPreferred(groupId, videoId)) {
                    _error.value = appContext.getString(R.string.stack_promote_failed)
                    loadVideos() // Revert optimistic update.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = appContext.getString(R.string.stack_promote_error, e.message ?: "")
                loadVideos()
            }
        }
    }

    /**
     * Fetch the members of stack [groupId]. Used by the stack-members sheet.
     * Returns a list of [VideoSummary] and the preferred video id, or an
     * empty pair on failure.
     */
    suspend fun loadGroupMembers(groupId: String): Pair<List<com.reelvault.data.models.VideoSummary>, String> {
        return try {
            repository.listGroupMembers(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = appContext.getString(R.string.stack_load_members_error, e.message ?: "")
            Pair(emptyList(), "")
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

    /**
     * Set the grid density step in 1..5 and persist to SharedPreferences.
     * 1 = smallest cards / most columns, 5 = largest cards / fewest columns.
     * The screen maps this to a minimum card width passed to [GridCells.Adaptive].
     */
    fun setGridDensity(density: Int) {
        val clamped = density.coerceIn(1, 5)
        if (_gridDensity.value == clamped) return
        _gridDensity.value = clamped
        prefs.edit().putInt(PREF_GRID_DENSITY, clamped).apply()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: grid layout settings (top-of-card stat slots)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Fetch the saved 4-slot configuration from the catalog server.
     * Falls back to [defaultGridTopSlots] when the RPC fails so the grid
     * never starts broken. Mirrors desktop GridViewModel.loadGridSettings().
     */
    fun loadGridSettings() {
        viewModelScope.launch {
            try {
                val raw = repository.getGridSettings()
                _topSlots.value = normaliseSlots(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-fatal — defaults remain intact.
            }
        }
    }

    /**
     * Update one slot's stat key and persist the full set to the catalog.
     * The local [topSlots] update is immediate (the cards repaint at once);
     * the persistence RPC is debounced (500 ms) so rapid slot changes
     * collapse to a single write — mirrors desktop GridViewModel.updateGridTopSlot().
     */
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
                _error.value = appContext.getString(R.string.err_save_grid_settings, e.message ?: "")
            }
        }
    }

    /** Pad / truncate any list to exactly four slot entries. */
    private fun normaliseSlots(raw: List<String>): List<String> {
        val padded = raw.toMutableList()
        while (padded.size < 4) padded.add("")
        return padded.take(4)
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
        locationsRefreshJob?.cancel()
        locationsRefreshJob = null
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
        stopConnectionMonitor()
        navHistory.clear()
        restoreExpected = null
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
        gridSettingsSaveJob?.cancel()
        gridSettingsSaveJob = null
        _topSlots.value = defaultGridTopSlots
        locationsRefreshJob?.cancel()
        locationsRefreshJob = null
        _videoLocations.value = emptyList()
        _isLoadingVideoLocations.value = false
        _hasLoadedLocationOnce.value = false
        // Clear per-video media caches so the next session starts clean.
        _scrubFrames.value = emptyMap()
        _hiResScrubFrames.value = emptyMap()
        _audioLoudness.value = emptyMap()
        scrubLoading.clear()
        audioLoudnessLoading.clear()
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

    // ─────────────────────────────────────────────────────────────────────
    // Video locations (filtered map)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Immediately (re)load GPS-tagged videos matching the current grid filters.
     * Call when the map screen opens to ensure the pin set is fresh even if
     * the debounced [scheduleVideoLocationsRefresh] hasn't fired yet.
     */
    fun loadVideoLocationsFiltered() {
        locationsRefreshJob?.cancel()
        locationsRefreshJob = viewModelScope.launch {
            loadVideoLocationsFilteredAsync()
        }
    }

    /**
     * Debounced trigger for [loadVideoLocationsFilteredAsync]. Collapses a
     * burst of grid reloads (rapid filtering, collection/tag changes) into
     * a single full-library pagination pass, matching the desktop behaviour.
     * The map consumes [videoLocations] on demand, so a short delay is harmless.
     */
    private fun scheduleVideoLocationsRefresh() {
        locationsRefreshJob?.cancel()
        locationsRefreshJob = viewModelScope.launch {
            delay(400)
            loadVideoLocationsFilteredAsync()
        }
    }

    /**
     * Paginate all videos that match the current grid filters with
     * `hasLocation = Yes` forced, and publish the result to [videoLocations].
     *
     * Runs on [Dispatchers.IO] (mirrors the desktop's `withContext(Dispatchers.IO)`
     * block) so a slow NAS-backed catalog never blocks the main thread.
     * The geo-proximity filter is intentionally excluded — the map draws the
     * circle; applying it here would hide pins the user is trying to inspect.
     *
     * Mirrors desktop `loadVideoLocationsFilteredAsync`.
     */
    private suspend fun loadVideoLocationsFilteredAsync() {
        _isLoadingVideoLocations.value = true
        try {
            val accumulated = withContext(Dispatchers.IO) {
                val batchSize = 500
                val acc = mutableListOf<VideoLocation>()
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
                        geoFilter = null,  // exclude proximity circle — map draws it
                        filterMinRating = _filterMinRating.value,
                        filterColorLabel = _filterColorLabel.value,
                        searchQuery = _searchQuery.value,
                        metadataFilters = mergedMetadataFilters(),
                        // Force hasLocation=Yes so only geotagged videos are returned.
                        // A stale "location: no" from grid/list would otherwise empty the map.
                        hasLocation = AttributeFilterState.Yes,
                        hasKeywords = mergeAttr(smartHasKeywords, _filterHasKeywords.value),
                        hasProxies = mergeAttr(smartHasProxies, _filterHasProxies.value),
                        fullResolution = mergeAttr(smartFullResolution, _filterFullResolution.value),
                    )
                    page.filter { it.hasLocation }.forEach { v ->
                        acc += VideoLocation(
                            id = v.id,
                            filename = v.filename,
                            path = v.path,
                            latitude = v.gpsLatitude,
                            longitude = v.gpsLongitude,
                            hasThumbnail = v.hasThumbnail,
                        )
                    }
                    offset += page.size
                    if (page.isEmpty() || offset >= total) break
                }
                acc
            }
            _videoLocations.value = accumulated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-fatal — map keeps its last known locations.
        } finally {
            _isLoadingVideoLocations.value = false
            _hasLoadedLocationOnce.value = true
        }
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

                // Keep map locations in sync with the active grid filters.
                // Debounced so a burst of filter changes collapses into one
                // pagination pass (mirrors desktop scheduleVideoLocationsRefresh).
                scheduleVideoLocationsRefresh()
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
    // Live server connection monitor
    // ─────────────────────────────────────────────────────────────────────

    private val _serverUnreachable = MutableStateFlow(false)
    val serverUnreachable: StateFlow<Boolean> = _serverUnreachable.asStateFlow()
    private var monitorJob: Job? = null

    /** Start probing [host]:[grpcPort] every 30 s. Sets [serverUnreachable] when
     *  the server stops answering. No-op in Local mode (caller checks isRemote). */
    fun startConnectionMonitor(host: String, grpcPort: Int) {
        monitorJob?.cancel()
        _serverUnreachable.value = false
        monitorJob = viewModelScope.launch {
            runMonitor(host, grpcPort, initialDelayMs = 30_000L)
        }
    }

    fun stopConnectionMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        _serverUnreachable.value = false
    }

    /** Silence the alert for 5 minutes, then resume normal 30-s probing. */
    fun keepWaiting(host: String, grpcPort: Int) {
        _serverUnreachable.value = false
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            runMonitor(host, grpcPort, initialDelayMs = 300_000L)
        }
    }

    private suspend fun runMonitor(host: String, grpcPort: Int, initialDelayMs: Long) {
        var delayMs = initialDelayMs
        while (isActive) {
            delay(delayMs)
            _serverUnreachable.value = !isServerReachable(host, grpcPort)
            delayMs = 30_000L
        }
    }

    private suspend fun isServerReachable(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                java.net.Socket().use { socket ->
                    socket.soTimeout = 5_000
                    socket.connect(java.net.InetSocketAddress(host, port), 5_000)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // ViewModel lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopCatalogEventStream()
        stopConnectionMonitor()
        gridSettingsSaveJob?.cancel()
        locationsRefreshJob?.cancel()
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
