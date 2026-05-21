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
import org.slf4j.LoggerFactory

class GridViewModel(
    private val repository: VideoRepository
) {
    private val logger = LoggerFactory.getLogger(GridViewModel::class.java)
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

    // UI state
    private val _selectedVideo = mutableStateOf<VideoSummary?>(null)
    val selectedVideo: State<VideoSummary?> = _selectedVideo

    init {
        logger.info("GridViewModel created")
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
            currentPage++

            try {
                val (newVideos, totalCount) = if (searchQuery.isNotEmpty()) {
                    repository.searchVideos(
                        query = searchQuery,
                        limit = pageSize,
                        offset = currentPage * pageSize,
                        filterTags = filterTags
                    )
                } else {
                    repository.listVideos(
                        limit = pageSize,
                        offset = currentPage * pageSize,
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
                    )
                }

                _videos.value = _videos.value + newVideos
                _totalCount.value = totalCount
                _hasMore.value = (_videos.value.size) < totalCount
                _isLoading.value = false

                logger.info("Loaded more videos, total now: ${_videos.value.size}/$totalCount")
            } catch (e: Exception) {
                _error.value = "Failed to load more videos: ${e.message}"
                _isLoading.value = false
                currentPage-- // Revert page increment on error
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

    fun clearAllDropdownFilters() {
        var changed = false
        if (_filterCamera.value.isNotEmpty()) { _filterCamera.value = ""; changed = true }
        if (_filterLens.value.isNotEmpty()) { _filterLens.value = ""; changed = true }
        if (_filterCodec.value.isNotEmpty()) { _filterCodec.value = ""; changed = true }
        if (_filterCaptureYear.value != 0) { _filterCaptureYear.value = 0; changed = true }
        if (_filterLocation.value != null) { _filterLocation.value = null; changed = true }
        if (changed) loadVideos()
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

    /** Collapse all expanded stacks (useful when sort/filter changes). */
    private fun collapseAllStacks() {
        _expandedGroupIds.value = emptySet()
    }

    fun loadThumbnail(videoId: String) {
        if (_thumbnails.value.containsKey(videoId)) return

        viewModelScope.launch {
            val data = repository.getThumbnail(videoId, "medium")
            if (data != null) {
                _thumbnails.value = _thumbnails.value + (videoId to data)
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
                val frames = repository.getScrubFrames(videoId, count = 10)
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
        autoGroup: Boolean = true
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

                repository.scanLibrary(path, autoGroup).collect { progress ->
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

    data class ScanResult(
        val success: Boolean,
        val message: String,
        val videosFound: Int,
        val videosIndexed: Int
    )

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
        _thumbnails.value = emptyMap()
        _scrubFrames.value = emptyMap()
        _scanStatus.value = null
        searchQuery = ""
        currentPage = 0
        collapseAllStacks()
    }
}
