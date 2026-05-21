// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.videoroom.data.models.VideoMetadata
import com.videoroom.data.repository.VideoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

class DetailViewModel(
    private val repository: VideoRepository
) {
    private val logger = LoggerFactory.getLogger(DetailViewModel::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _metadata = mutableStateOf<VideoMetadata?>(null)
    val metadata: State<VideoMetadata?> = _metadata

    private val _thumbnail = mutableStateOf<ByteArray?>(null)
    val thumbnail: State<ByteArray?> = _thumbnail

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    // Group / stack info
    private val _groupMembers = MutableStateFlow<List<com.videoroom.data.models.VideoSummary>>(emptyList())
    val groupMembers: StateFlow<List<com.videoroom.data.models.VideoSummary>> = _groupMembers.asStateFlow()

    private val _groupPreferredId = MutableStateFlow("")
    val groupPreferredId: StateFlow<String> = _groupPreferredId.asStateFlow()

    private var currentVideoSummary: com.videoroom.data.models.VideoSummary? = null

    fun setCurrentVideo(video: com.videoroom.data.models.VideoSummary) {
        currentVideoSummary = video
        if (video.isInGroup) {
            loadGroupMembers(video.groupId)
        } else {
            _groupMembers.value = emptyList()
            _groupPreferredId.value = ""
        }
    }

    private fun loadGroupMembers(groupId: String) {
        viewModelScope.launch {
            try {
                val (members, preferred) = repository.listGroupMembers(groupId)
                _groupMembers.value = members
                _groupPreferredId.value = preferred
            } catch (e: Exception) {
                logger.warn("Failed to load group members for $groupId", e)
            }
        }
    }

    fun setGroupPreferred(videoId: String) {
        val groupId = currentVideoSummary?.groupId ?: return
        if (groupId.isEmpty()) return
        viewModelScope.launch {
            try {
                if (repository.setGroupPreferred(groupId, videoId)) {
                    _groupPreferredId.value = videoId
                    logger.info("Set preferred video to $videoId")
                } else {
                    _error.value = "Failed to set preferred video"
                }
            } catch (e: Exception) {
                _error.value = "Failed to set preferred: ${e.message}"
            }
        }
    }

    /** Remove the currently-displayed video from its stack. `onComplete`
     *  fires with the *old* group id after the daemon call succeeds —
     *  callers wire it to `GridViewModel.refreshAfterStackChange` so the
     *  grid's representative + expanded-member caches catch up. */
    fun ungroupCurrent(onComplete: (oldGroupId: String) -> Unit = {}) {
        val videoId = _metadata.value?.id ?: return
        // Capture the *current* group id before we tell the daemon to
        // drop it — VideoMetadata doesn't carry groupId, so we read it
        // off the VideoSummary we cached on selection.
        val oldGroupId = currentVideoSummary?.groupId ?: ""
        viewModelScope.launch {
            try {
                if (repository.ungroupVideo(videoId)) {
                    _groupMembers.value = emptyList()
                    _groupPreferredId.value = ""
                    logger.info("Ungrouped video $videoId")
                    onComplete(oldGroupId)
                } else {
                    _error.value = "Failed to ungroup"
                }
            } catch (e: Exception) {
                _error.value = "Failed to ungroup: ${e.message}"
            }
        }
    }

    fun loadMetadata(videoId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val metadata = repository.getVideoMetadata(videoId)
                _metadata.value = metadata
                _notes.value = metadata?.notes ?: ""

                if (metadata != null) {
                    // Load thumbnail
                    loadThumbnail(videoId)
                    logger.info("Loaded metadata for video: ${metadata.filename}")
                } else {
                    _error.value = "Video not found"
                }
                _isLoading.value = false
            } catch (e: Exception) {
                _error.value = "Failed to load metadata: ${e.message}"
                _isLoading.value = false
                logger.error("Failed to load metadata for video: $videoId", e)
            }
        }
    }

    private fun loadThumbnail(videoId: String) {
        viewModelScope.launch {
            try {
                val thumbnail = repository.getThumbnail(videoId, "large")
                _thumbnail.value = thumbnail
                if (thumbnail != null) {
                    logger.info("Loaded thumbnail for video: $videoId")
                }
            } catch (e: Exception) {
                logger.warn("Failed to load thumbnail for video: $videoId", e)
                // Don't error out, just skip thumbnail
            }
        }
    }

    fun updateNotes(newNotes: String) {
        _notes.value = newNotes
        val videoId = _metadata.value?.id ?: return

        viewModelScope.launch {
            try {
                val success = repository.updateVideoNotes(videoId, newNotes)
                if (!success) {
                    _error.value = "Failed to update notes"
                    logger.warn("Failed to update notes for video: $videoId")
                } else {
                    logger.info("Updated notes for video: $videoId")
                }
            } catch (e: Exception) {
                _error.value = "Failed to update notes: ${e.message}"
                logger.error("Failed to update notes for video: $videoId", e)
            }
        }
    }

    fun addTag(tagId: String) {
        val videoId = _metadata.value?.id ?: return

        viewModelScope.launch {
            try {
                val success = repository.tagVideos(listOf(videoId), tagId)
                if (success) {
                    // Reload metadata to get updated tags
                    loadMetadata(videoId)
                    logger.info("Added tag to video: $videoId")
                } else {
                    _error.value = "Failed to add tag"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add tag: ${e.message}"
                logger.error("Failed to add tag to video: $videoId", e)
            }
        }
    }

    fun removeTag(tagId: String) {
        val videoId = _metadata.value?.id ?: return

        viewModelScope.launch {
            try {
                val success = repository.untagVideos(listOf(videoId), tagId)
                if (success) {
                    // Reload metadata to get updated tags
                    loadMetadata(videoId)
                    logger.info("Removed tag from video: $videoId")
                } else {
                    _error.value = "Failed to remove tag"
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove tag: ${e.message}"
                logger.error("Failed to remove tag from video: $videoId", e)
            }
        }
    }

    fun addToCollection(collectionId: String) {
        val videoId = _metadata.value?.id ?: return

        viewModelScope.launch {
            try {
                val success = repository.addToCollection(listOf(videoId), collectionId)
                if (success) {
                    // Reload metadata to get updated collections
                    loadMetadata(videoId)
                    logger.info("Added video to collection: $collectionId")
                } else {
                    _error.value = "Failed to add to collection"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add to collection: ${e.message}"
                logger.error("Failed to add video to collection: $collectionId", e)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clear() {
        _metadata.value = null
        _thumbnail.value = null
        _notes.value = ""
        _error.value = null
    }

    fun onDestroy() {
        viewModelScope.cancel()
        logger.info("DetailViewModel destroyed")
    }
}
