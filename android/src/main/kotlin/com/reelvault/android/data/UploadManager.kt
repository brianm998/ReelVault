// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.reelvault.data.remote.PinnedTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID

/**
 * The last-paired server's media endpoint, rebuilt for uploading on-device videos
 * while in Local mode (the Android mirror of iOS's `AppRouter.lastPairedUploadEndpoint`).
 */
data class UploadEndpoint(
    val host: String,
    val mediaPort: Int,
    val fingerprintHex: String,
    val token: String,
    val serverName: String,
)

/**
 * Rebuild the last-paired upload endpoint from saved prefs (address) + the stored
 * bearer token, or null if no server has been paired. Cheap (prefs read) — used to
 * gate the Upload button's visibility and to drive the upload sheet.
 */
fun lastPairedUploadEndpoint(context: Context): UploadEndpoint? {
    val entry = DefaultServerPrefs(context).load() ?: return null
    val fp = entry.fingerprintHex ?: return null
    val token = AndroidTokenStorage(context).get(fp) ?: return null
    return UploadEndpoint(entry.host, entry.mediaPort, fp, token, entry.host)
}

/**
 * Copy a Local-mode video's original bytes to a temp file for upload, or null if
 * it isn't an on-device (`photos://<MediaStore _ID>`) source (v1 supports
 * MediaStore-sourced videos only — mirrors iOS's Photos-only LocalVideoExport).
 */
fun materializeLocalVideo(context: Context, path: String, filename: String): File? {
    if (!path.startsWith("photos://")) return null
    val id = path.removePrefix("photos://").toLongOrNull() ?: return null
    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val tmp = File(context.cacheDir, "upload_${id}_$safeName")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return null
        tmp
    } catch (e: Exception) {
        tmp.delete()
        null
    }
}

/**
 * Streams local videos to the paired server's `POST /upload?filename=` route
 * (raw `application/octet-stream`, bearer-token + pinned TLS), reporting per-file
 * progress — the Android mirror of iOS's `UploadManager`. The upload lands in the
 * **server's** catalog (the user sees it after switching to that server).
 */
class UploadManager(
    private val endpoint: UploadEndpoint,
) {
    data class Job(
        val id: String = UUID.randomUUID().toString(),
        val filename: String,
        val progress: Float = 0f,
        val status: Status = Status.Uploading,
        val videoId: String? = null,
    )

    enum class Status { Uploading, Finished, Failed }

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private fun update(id: String, transform: (Job) -> Job) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
    }

    /** Upload one already-materialized temp file. Suspends until it finishes. */
    suspend fun upload(file: File, filename: String) = withContext(Dispatchers.IO) {
        val job = Job(filename = filename)
        _jobs.value = _jobs.value + job
        try {
            val url = "https://${endpoint.host}:${endpoint.mediaPort}/upload?filename=" +
                URLEncoder.encode(filename, "UTF-8")
            val client = PinnedTls.pinnedHttpClient(endpoint.fingerprintHex)
            val body = ProgressRequestBody(file) { sent, total ->
                if (total > 0) update(job.id) { it.copy(progress = sent.toFloat() / total) }
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${endpoint.token}")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val vid = resp.body?.string()?.let { raw ->
                        runCatching {
                            val o = JSONObject(raw)
                            if (o.isNull("video_id")) null else o.optString("video_id").ifEmpty { null }
                        }.getOrNull()
                    }
                    update(job.id) { it.copy(progress = 1f, status = Status.Finished, videoId = vid) }
                } else {
                    update(job.id) { it.copy(status = Status.Failed) }
                }
            }
        } catch (e: Exception) {
            update(job.id) { it.copy(status = Status.Failed) }
        }
    }

    /** RequestBody that streams a file in chunks, reporting upload progress. */
    private class ProgressRequestBody(
        private val file: File,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var uploaded = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    sink.write(buf, 0, read)
                    uploaded += read
                    onProgress(uploaded, total)
                }
            }
        }
    }
}
