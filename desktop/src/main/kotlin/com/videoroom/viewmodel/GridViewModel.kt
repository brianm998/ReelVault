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

    fun selectVideo(video: VideoSummary) {
        _selectedVideoId.value = video.id
        _selectedVideo.value = video
        logger.info("Selected video: ${video.filename}")
    }

    fun deselectVideo() {
        _selectedVideoId.value = null
        _selectedVideo.value = null
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

    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    fun clearScanResult() {
        _scanResult.value = null
    }

    fun addLibraryAndScan(path: String, recursive: Boolean = true) {
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

                repository.scanLibrary(path).collect { progress ->
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
