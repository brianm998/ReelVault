// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.reelvault.data.models.VideoMetadata
import com.reelvault.data.repository.VideoRepository
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
    private val _groupMembers = MutableStateFlow<List<com.reelvault.data.models.VideoSummary>>(emptyList())
    val groupMembers: StateFlow<List<com.reelvault.data.models.VideoSummary>> = _groupMembers.asStateFlow()

    private val _groupPreferredId = MutableStateFlow("")
    val groupPreferredId: StateFlow<String> = _groupPreferredId.asStateFlow()

    // Proxies attached to the currently-selected video.
    //
    // Sorted by height descending — the same order `list_proxies` returns
    // from the server. UI sites can take `.lastOrNull()` to get the
    // smallest proxy (used for grid/list inline playback) or
    // `.firstOrNull()` for the largest.
    private val _proxies = MutableStateFlow<List<VideoRepository.ProxyInfo>>(emptyList())
    val proxies: StateFlow<List<VideoRepository.ProxyInfo>> = _proxies.asStateFlow()

    // ID of the proxy the user explicitly picked for full-screen detail-view
    // playback. `null` means "play the master" (or, when the master is too
    // large, the player code falls back to the smallest proxy).
    //
    // Carries across selection changes intentionally — once cleared (via
    // [setCurrentVideo]) it stays `null` until the user clicks a proxy row
    // in the right panel.
    private val _selectedProxyId = MutableStateFlow<String?>(null)
    val selectedProxyId: StateFlow<String?> = _selectedProxyId.asStateFlow()

    // The summary backing the currently-selected card. Exposed as a
    // StateFlow so the right-panel proxy section (which keys off
    // `playableNatively` and `hasProxies` — neither carried by
    // VideoMetadata) can collect it directly.
    private val _currentSummary = MutableStateFlow<com.reelvault.data.models.VideoSummary?>(null)
    val currentSummary: StateFlow<com.reelvault.data.models.VideoSummary?> = _currentSummary.asStateFlow()
    private val currentVideoSummary: com.reelvault.data.models.VideoSummary?
        get() = _currentSummary.value

    fun setCurrentVideo(video: com.reelvault.data.models.VideoSummary) {
        _currentSummary.value = video
        if (video.isInGroup) {
            loadGroupMembers(video.groupId)
        } else {
            _groupMembers.value = emptyList()
            _groupPreferredId.value = ""
        }
        // Reset proxy selection on each new video, then lazily load the
        // proxy list when the summary advertises any. Servers send
        // `proxyCount = 0` for almost every video, so the conditional
        // saves a round trip on the common case.
        _selectedProxyId.value = null
        if (video.hasProxies) {
            loadProxies(video.id)
        } else {
            _proxies.value = emptyList()
        }
    }

    private fun loadProxies(videoId: String) {
        viewModelScope.launch {
            try {
                _proxies.value = repository.listProxies(videoId)
            } catch (e: Exception) {
                logger.warn("Failed to load proxies for $videoId", e)
                _proxies.value = emptyList()
            }
        }
    }

    /** User picked a specific proxy in the detail-view right panel.
     *  Pass `null` to revert to playing the master. */
    fun setSelectedProxy(proxyId: String?) {
        _selectedProxyId.value = proxyId
    }

    /** Break the link between the currently-displayed master and one
     *  of its proxies. Auto-refreshes the inspector list on success;
     *  surfaces failures via [error]. */
    fun breakProxyLink(proxyId: String) {
        val masterId = currentVideoSummary?.id ?: return
        viewModelScope.launch {
            try {
                if (repository.removeProxyLink(masterId, proxyId)) {
                    _proxies.value = repository.listProxies(masterId)
                    // If the user had this proxy selected for loupe
                    // playback, reset the selection so the player
                    // reverts to the master on the next frame.
                    if (_selectedProxyId.value == proxyId) {
                        _selectedProxyId.value = null
                    }
                    logger.info("Removed proxy link $masterId → $proxyId")
                } else {
                    _error.value = "Failed to remove proxy link"
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove proxy link: ${e.message}"
            }
        }
    }

    /** Mark `proxyId` as a manual proxy of the currently-displayed
     *  master. Used by the inspector's "Add selected as proxy"
     *  button. Confidence is 1.0 and auto_detected=false so the
     *  link is presented as user-authored. */
    fun forceProxyLink(proxyId: String) {
        val masterId = currentVideoSummary?.id ?: return
        if (masterId == proxyId) {
            _error.value = "A video can't be a proxy of itself"
            return
        }
        viewModelScope.launch {
            try {
                if (repository.setProxyOf(proxyId, masterId)) {
                    _proxies.value = repository.listProxies(masterId)
                    logger.info("Added manual proxy link $masterId → $proxyId")
                } else {
                    _error.value = "Failed to add proxy link"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add proxy link: ${e.message}"
            }
        }
    }

    /** The on-disk path that the detail-view player should load, based
     *  on the current proxy selection and the master's playability. The
     *  rules:
     *    • User explicitly picked a proxy → that proxy's path.
     *    • Master is not natively playable AND a proxy exists → smallest proxy's path.
     *    • Otherwise → null, callers should fall back to the master's own path.
     */
    fun playbackPathFor(video: com.reelvault.data.models.VideoSummary): String? {
        val pickedId = _selectedProxyId.value
        if (pickedId != null) {
            return _proxies.value.firstOrNull { it.id == pickedId }?.path
        }
        if (!video.playableNatively && _proxies.value.isNotEmpty()) {
            // `list_proxies` returns descending by pixel count — last = smallest.
            return _proxies.value.lastOrNull()?.path
        }
        return null
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
        _proxies.value = emptyList()
        _selectedProxyId.value = null
    }

    fun onDestroy() {
        viewModelScope.cancel()
        logger.info("DetailViewModel destroyed")
    }
}
