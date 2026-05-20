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
                        collectionId = collectionId
                    )
                }

                _videos.value = videosList
                _totalCount.value = totalCount
                _hasMore.value = videosList.size < totalCount
                _isLoading.value = false

                logger.info("Loaded ${videosList.size} videos, total: $totalCount")
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
                        collectionId = collectionId
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

                // Trigger scan
                _scanStatus.value = "Scanning..."
                var lastVideosFound = 0
                var lastVideosIndexed = 0
                var errorMessage: String? = null

                repository.scanLibrary(path, autoGroup).collect { progress ->
                    when (progress.status) {
                        "error" -> {
                            errorMessage = progress.currentFile
                            logger.warn("Scan error: ${progress.currentFile}")
                        }
                        "complete" -> {
                            lastVideosFound = progress.videosFound
                            lastVideosIndexed = progress.videosIndexed
                            _scanStatus.value = "Complete: $lastVideosFound found, $lastVideosIndexed indexed"
                        }
                        else -> {
                            lastVideosFound = progress.videosFound
                            lastVideosIndexed = progress.videosIndexed
                            _scanStatus.value = "${progress.status}: ${progress.videosIndexed}/${progress.videosFound} - ${progress.currentFile}"
                        }
                    }
                    logger.info("Scan progress: ${progress.status} | found: ${progress.videosFound} | indexed: ${progress.videosIndexed}")
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
                    ScanResult(
                        success = true,
                        message = if (lastVideosFound == 0) {
                            "No videos found in $path"
                        } else {
                            "Scanned $path: $lastVideosFound videos found, $lastVideosIndexed indexed"
                        },
                        videosFound = lastVideosFound,
                        videosIndexed = lastVideosIndexed
                    )
                }

                _scanStatus.value = null
                _isLoading.value = false
                // Refresh video list after scan
                loadVideos()
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
}
