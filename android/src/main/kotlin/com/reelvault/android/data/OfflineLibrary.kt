// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.reelvault.data.models.VideoSummary
import com.reelvault.data.remote.PinnedTls
import com.reelvault.data.remote.RemoteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val TAG = "OfflineLibrary"
private const val OFFLINE_DIR = "ReelVaultOffline"
private const val INDEX_FILE = "index.json"

/**
 * App-private offline library: a subset of a remote catalog downloaded onto
 * the device so it can be browsed and played when no core daemon is reachable.
 *
 * Files and a small JSON index live under the app's internal files directory
 * (not the purgeable cache), so a download survives until the user removes it.
 * These videos are intentionally separate from the server catalog — they are a
 * private on-device copy. Mirrors iOS OfflineLibrary.swift.
 */
object OfflineLibrary {

    /** One downloaded video persisted in the index. */
    data class Entry(
        val id: String,
        val filename: String,
        val renditionHeight: Int,   // 0 = original, else the downscaled height
        val pixelWidth: Int,
        val pixelHeight: Int,
        val durationMs: Long,
        val sizeBytes: Long,
        val codecVideo: String,
        val fileName: String,       // relative video file name inside the offline dir
        val thumbName: String?,     // relative thumbnail file name (jpg), or null
        val addedAt: Long,          // unix epoch seconds
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Video ids currently downloading (drives progress UI). */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Late-initialised once via [init].
    private lateinit var dir: File
    private lateinit var indexFile: File
    private var initialised = false

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Must be called once at application startup (e.g. from [ReelVaultApp.onCreate]).
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (initialised) return
        dir = File(context.filesDir, OFFLINE_DIR).also { it.mkdirs() }
        indexFile = File(dir, INDEX_FILE)
        initialised = true
        loadIndex()
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun isDownloaded(videoId: String): Boolean =
        _entries.value.any { it.id == videoId }

    /** Absolute [File] for a downloaded video, or null if not present. */
    fun videoFile(videoId: String): File? {
        val e = _entries.value.firstOrNull { it.id == videoId } ?: return null
        return File(dir, e.fileName)
    }

    /** Load thumbnail bitmap synchronously (call from a background thread). */
    fun thumbnailBitmap(videoId: String): Bitmap? {
        val e = _entries.value.firstOrNull { it.id == videoId } ?: return null
        val t = e.thumbName ?: return null
        val f = File(dir, t)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
    }

    val totalBytes: Long get() = _entries.value.sumOf { it.sizeBytes }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Download [video] at [height] (0 = original) from the currently-connected
     * server into app-private storage, plus a poster thumbnail, and index it.
     *
     * Idempotent per video id — a second download for the same id replaces the
     * first. All network and file I/O runs on [Dispatchers.IO]; the flows are
     * updated from the IO dispatcher after completion.
     *
     * @throws IllegalStateException if no remote endpoint is set.
     */
    suspend fun download(video: VideoSummary, height: Int) {
        require(initialised) { "OfflineLibrary.init() not called" }
        if (_downloading.value.contains(video.id)) return

        val ep = RemoteConnection.endpoint
            ?: throw IllegalStateException("Not connected to a remote server")

        _downloading.value = _downloading.value + video.id

        try {
            withContext(Dispatchers.IO) {
                val safeId = video.id.replace("/", "_")
                val ext = if (height == 0) originalExt(video.filename) else "mp4"
                val fileName = "${safeId}_h${height}.$ext"
                val thumbName = "${safeId}.jpg"

                val destFile = File(dir, fileName)

                // Download the video via /download/:id or /download/:id/:height.
                val url = if (height > 0) {
                    "https://${ep.host}:${ep.mediaPort}/download/${video.id}/$height"
                } else {
                    "https://${ep.host}:${ep.mediaPort}/download/${video.id}"
                }

                val client = PinnedTls.pinnedHttpClient(ep.fingerprintHex)
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${ep.token}")
                    .get()
                    .build()

                val response = client.newCall(req).execute()
                if (!response.isSuccessful) {
                    throw Exception("Download failed: HTTP ${response.code}")
                }
                response.body?.use { body ->
                    FileOutputStream(destFile).use { out ->
                        body.byteStream().copyTo(out)
                    }
                } ?: throw Exception("Empty response body")

                val sizeBytes = destFile.length()

                // Poster thumbnail (best-effort): fetch via /thumbnail/:id.
                var savedThumb: String? = null
                try {
                    val thumbUrl = "https://${ep.host}:${ep.mediaPort}/thumbnail/${video.id}"
                    val thumbReq = Request.Builder()
                        .url(thumbUrl)
                        .header("Authorization", "Bearer ${ep.token}")
                        .get()
                        .build()
                    val thumbResp = client.newCall(thumbReq).execute()
                    if (thumbResp.isSuccessful) {
                        val thumbFile = File(dir, thumbName)
                        thumbResp.body?.use { body ->
                            FileOutputStream(thumbFile).use { out ->
                                body.byteStream().copyTo(out)
                            }
                        }
                        if (thumbFile.exists() && thumbFile.length() > 0) {
                            savedThumb = thumbName
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail download failed for ${video.id}: ${e.message}")
                }

                val entry = Entry(
                    id = video.id,
                    filename = video.filename,
                    renditionHeight = height,
                    pixelWidth = video.width,
                    pixelHeight = video.height,
                    durationMs = video.durationMs,
                    sizeBytes = sizeBytes,
                    codecVideo = video.codecVideo,
                    fileName = fileName,
                    thumbName = savedThumb,
                    addedAt = System.currentTimeMillis() / 1000L,
                )

                val updated = _entries.value.filter { it.id != video.id } + entry
                _entries.value = updated.sortedByDescending { it.addedAt }
                persistIndex()
                Log.i(TAG, "Downloaded ${video.filename} ($sizeBytes bytes)")
            }
        } catch (e: Exception) {
            val msg = "Couldn't download ${video.filename}: ${e.message}"
            _lastError.value = msg
            Log.e(TAG, "Download failed for ${video.id}: ${e.message}")
        } finally {
            _downloading.value = _downloading.value - video.id
        }
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    suspend fun remove(videoId: String) {
        val e = _entries.value.firstOrNull { it.id == videoId } ?: return
        withContext(Dispatchers.IO) {
            File(dir, e.fileName).delete()
            e.thumbName?.let { File(dir, it).delete() }
        }
        _entries.value = _entries.value.filter { it.id != videoId }
        withContext(Dispatchers.IO) { persistIndex() }
    }

    suspend fun removeAll() {
        val snapshot = _entries.value
        withContext(Dispatchers.IO) {
            for (e in snapshot) {
                File(dir, e.fileName).delete()
                e.thumbName?.let { File(dir, it).delete() }
            }
        }
        _entries.value = emptyList()
        withContext(Dispatchers.IO) { persistIndex() }
    }

    fun clearError() { _lastError.value = null }

    // ── Persistence (JSON via org.json — always available on Android) ─────────

    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            val arr = JSONArray(indexFile.readText())
            val list = (0 until arr.length()).map { i ->
                arr.getJSONObject(i).toEntry()
            }
            // Drop rows whose video file went missing (e.g. manual deletion).
            _entries.value = list.filter { File(dir, it.fileName).exists() }
                .sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load offline index: ${e.message}")
        }
    }

    /** Must be called from a background thread. */
    private fun persistIndex() {
        try {
            val arr = JSONArray()
            _entries.value.forEach { arr.put(it.toJson()) }
            indexFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist offline index: ${e.message}")
        }
    }

    private fun Entry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("filename", filename)
        put("renditionHeight", renditionHeight)
        put("pixelWidth", pixelWidth)
        put("pixelHeight", pixelHeight)
        put("durationMs", durationMs.toString())
        put("sizeBytes", sizeBytes)
        put("codecVideo", codecVideo)
        put("fileName", fileName)
        put("thumbName", thumbName ?: JSONObject.NULL)
        put("addedAt", addedAt)
    }

    private fun JSONObject.toEntry(): Entry = Entry(
        id = getString("id"),
        filename = getString("filename"),
        renditionHeight = getInt("renditionHeight"),
        pixelWidth = getInt("pixelWidth"),
        pixelHeight = getInt("pixelHeight"),
        durationMs = getString("durationMs").toLong(),
        sizeBytes = getLong("sizeBytes"),
        codecVideo = getString("codecVideo"),
        fileName = getString("fileName"),
        thumbName = if (isNull("thumbName")) null else getString("thumbName"),
        addedAt = getLong("addedAt"),
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun originalExt(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext.ifEmpty { "mov" }
    }
}
