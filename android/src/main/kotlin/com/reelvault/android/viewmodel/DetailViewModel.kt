// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reelvault.data.models.VideoMetadata
import com.reelvault.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android ViewModel for the video detail / inspector screen.
 *
 * Mirrors the desktop DetailViewModel, adapted to:
 * - Extend [ViewModel] with [viewModelScope]
 * - Expose all state as [StateFlow] (no Compose-runtime dependency)
 * - Support tag updates, rating changes, color-label changes, notes updates
 */
class DetailViewModel(
    private val repository: VideoRepository,
) : ViewModel() {

    // ── Metadata ──────────────────────────────────────────────────────────

    private val _metadata = MutableStateFlow<VideoMetadata?>(null)
    val metadata: StateFlow<VideoMetadata?> = _metadata.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Editable notes ────────────────────────────────────────────────────

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    // ── Thumbnail ─────────────────────────────────────────────────────────

    private val _thumbnail = MutableStateFlow<ByteArray?>(null)
    val thumbnail: StateFlow<ByteArray?> = _thumbnail.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────
    // Public API: loading
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Load full metadata for [videoId]. Also kicks off a thumbnail fetch.
     * Sets [isLoading] while in flight; populates [error] on failure.
     */
    fun loadMetadata(videoId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val meta = repository.getVideoMetadata(videoId)
                _metadata.value = meta
                _notes.value = meta?.notes ?: ""
                if (meta != null) {
                    loadThumbnail(videoId)
                } else {
                    _error.value = "Video not found"
                }
            } catch (e: Exception) {
                _error.value = "Failed to load metadata: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadThumbnail(videoId: String) {
        viewModelScope.launch {
            try {
                _thumbnail.value = repository.getThumbnailOrNull(videoId, "large")
            } catch (_: Exception) {
                // Non-fatal — the UI shows a placeholder instead.
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: notes
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Persist updated notes for the currently-loaded video.
     * Optimistically updates [notes] immediately; errors surface in [error].
     */
    fun updateNotes(newNotes: String) {
        _notes.value = newNotes
        val videoId = _metadata.value?.id ?: return
        viewModelScope.launch {
            try {
                val ok = repository.updateVideoNotes(videoId, newNotes)
                if (!ok) _error.value = "Failed to update notes"
            } catch (e: Exception) {
                _error.value = "Failed to update notes: ${e.message}"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: tags
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add [tagId] to the currently-loaded video. Reloads metadata on success
     * so the tag list in the inspector refreshes immediately.
     */
    fun addTag(tagId: String) {
        val videoId = _metadata.value?.id ?: return
        viewModelScope.launch {
            try {
                val ok = repository.tagVideos(listOf(videoId), tagId)
                if (ok) {
                    loadMetadata(videoId)
                } else {
                    _error.value = "Failed to add tag"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add tag: ${e.message}"
            }
        }
    }

    /**
     * Remove [tagId] from the currently-loaded video. Reloads metadata on
     * success.
     */
    fun removeTag(tagId: String) {
        val videoId = _metadata.value?.id ?: return
        viewModelScope.launch {
            try {
                val ok = repository.untagVideos(listOf(videoId), tagId)
                if (ok) {
                    loadMetadata(videoId)
                } else {
                    _error.value = "Failed to remove tag"
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove tag: ${e.message}"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: rating
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Apply a 0..5 star [rating] to the currently-loaded video.
     * Optimistically updates [metadata] and pushes the change to the daemon.
     */
    fun setRating(rating: Int) {
        val videoId = _metadata.value?.id ?: return
        val clamped = rating.coerceIn(0, 5)
        // Optimistic local update.
        _metadata.value = _metadata.value?.copy(rating = clamped)
        viewModelScope.launch {
            try {
                repository.updateVideoRating(listOf(videoId), clamped)
            } catch (e: Exception) {
                _error.value = "Failed to update rating: ${e.message}"
                // Reload to restore the actual persisted value.
                loadMetadata(videoId)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: color label
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Apply Lightroom-style colour [label] ("red", "yellow", "green", "blue",
     * "purple") or "" to clear. Optimistically updates [metadata].
     */
    fun setColorLabel(label: String) {
        val videoId = _metadata.value?.id ?: return
        // Optimistic local update.
        _metadata.value = _metadata.value?.copy(colorLabel = label)
        viewModelScope.launch {
            try {
                repository.updateVideoColorLabel(listOf(videoId), label)
            } catch (e: Exception) {
                _error.value = "Failed to update color label: ${e.message}"
                loadMetadata(videoId)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: location
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Set the GPS location of the currently-loaded video. When [writeToFile]
     * is true the daemon also embeds the GPS tag into the video file (no
     * re-encode). Optimistically updates [metadata]; reloads on completion so
     * the inspector reflects the persisted value. Mirrors iOS
     * GridViewModel.setVideoLocations.
     */
    fun setVideoLocation(latitude: Double, longitude: Double, writeToFile: Boolean = false) {
        val videoId = _metadata.value?.id ?: return
        // Optimistic local update.
        _metadata.value = _metadata.value?.copy(gpsLatitude = latitude, gpsLongitude = longitude)
        viewModelScope.launch {
            try {
                val ok = repository.updateVideoLocation(
                    videoId, latitude, longitude, writeToFile = writeToFile,
                )
                if (!ok) _error.value = "Failed to update location"
                loadMetadata(videoId)
            } catch (e: Exception) {
                _error.value = "Failed to update location: ${e.message}"
                loadMetadata(videoId)
            }
        }
    }

    /**
     * Remove the GPS location of the currently-loaded video (sends lat/lon 0,
     * which the daemon interprets as "no location"). Reloads on completion.
     */
    fun clearVideoLocation() {
        val videoId = _metadata.value?.id ?: return
        _metadata.value = _metadata.value?.copy(gpsLatitude = 0.0, gpsLongitude = 0.0, gpsAltitude = 0.0)
        viewModelScope.launch {
            try {
                val ok = repository.updateVideoLocation(videoId, 0.0, 0.0)
                if (!ok) _error.value = "Failed to remove location"
                loadMetadata(videoId)
            } catch (e: Exception) {
                _error.value = "Failed to remove location: ${e.message}"
                loadMetadata(videoId)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: collections
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add the currently-loaded video to [collectionId]. Reloads metadata on
     * success so the collections list refreshes.
     */
    fun addToCollection(collectionId: String) {
        val videoId = _metadata.value?.id ?: return
        viewModelScope.launch {
            try {
                val ok = repository.addToCollection(listOf(videoId), collectionId)
                if (ok) {
                    loadMetadata(videoId)
                } else {
                    _error.value = "Failed to add to collection"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add to collection: ${e.message}"
            }
        }
    }

    /**
     * Remove the currently-loaded video from [collectionId]. Reloads metadata
     * on success.
     */
    fun removeFromCollection(collectionId: String) {
        val videoId = _metadata.value?.id ?: return
        viewModelScope.launch {
            try {
                val ok = repository.removeFromCollection(listOf(videoId), collectionId)
                if (ok) {
                    loadMetadata(videoId)
                } else {
                    _error.value = "Failed to remove from collection"
                }
            } catch (e: Exception) {
                _error.value = "Failed to remove from collection: ${e.message}"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: misc
    // ─────────────────────────────────────────────────────────────────────

    fun clearError() {
        _error.value = null
    }

    /** Reset all state — call when navigating away from the detail screen. */
    fun clear() {
        _metadata.value = null
        _thumbnail.value = null
        _notes.value = ""
        _error.value = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────

    class Factory(
        private val repository: VideoRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DetailViewModel::class.java))
            return DetailViewModel(repository) as T
        }
    }
}
