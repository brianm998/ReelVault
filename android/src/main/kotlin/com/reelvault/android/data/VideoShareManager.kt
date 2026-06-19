// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.reelvault.data.remote.PinnedTls
import com.reelvault.data.remote.RemoteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads a remote video file to the app's internal share-cache and exposes
 * it via [FileProvider], matching iOS ShareExportModel.prepare() behaviour.
 *
 * The server's `/download/:id` endpoint streams the raw original (or a proxy at
 * [heightPx] when non-zero). The file is cached under `getCacheDir()/shared_videos/`
 * and overwritten on each share so stale files don't accumulate.
 *
 * The FileProvider authority is `<applicationId>.fileprovider` and is declared in
 * AndroidManifest.xml together with `res/xml/file_paths.xml`.
 */
object VideoShareManager {

    private const val CACHE_SUBDIR = "shared_videos"

    /**
     * Download the video at [videoId] from the currently-connected daemon and
     * return a content:// [Uri] readable by the receiving editor.
     *
     * @param videoId  Catalog video ID.
     * @param filename Suggested filename (used for the cached file name).
     * @param heightPx Preferred height for the download (0 = original/best).
     *
     * @throws IllegalStateException if no remote connection is active.
     * @throws Exception on network / IO failures.
     */
    suspend fun shareUri(context: Context, videoId: String, filename: String, heightPx: Int = 0): Uri =
        withContext(Dispatchers.IO) {
            val ep = RemoteConnection.endpoint
                ?: throw IllegalStateException("Not connected to a remote server")

            // Build the media endpoint URL.  Use the same /download/:id/:height route
            // that the daemon exposes (height 0 → daemon picks the best available source).
            val url = if (heightPx > 0) {
                "https://${ep.host}:${ep.mediaPort}/download/$videoId/$heightPx"
            } else {
                "https://${ep.host}:${ep.mediaPort}/download/$videoId"
            }

            val okHttpClient = PinnedTls.pinnedHttpClient(ep.fingerprintHex)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${ep.token}")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code}")
            }

            // Sanitise the filename so it is safe on the filesystem.
            val safeName = filename.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR).also { it.mkdirs() }
            val destFile = File(cacheDir, safeName)

            response.body?.use { body ->
                FileOutputStream(destFile).use { out ->
                    body.byteStream().copyTo(out)
                }
            } ?: throw Exception("Empty response body from download endpoint")

            // Expose via FileProvider (authority declared in AndroidManifest.xml).
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, destFile)
        }

    // Guess the MIME type from a filename extension.
    // Falls back to "video/*" when the extension is unrecognised.
    fun mimeTypeFor(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mov"        -> "video/quicktime"
            "mkv"        -> "video/x-matroska"
            "avi"        -> "video/x-msvideo"
            "webm"       -> "video/webm"
            "ts"         -> "video/mp2t"
            "mts", "m2ts" -> "video/mp2t"
            "hevc", "heic" -> "video/hevc"
            "flv"        -> "video/x-flv"
            "3gp"        -> "video/3gpp"
            "wmv"        -> "video/x-ms-wmv"
            else         -> "video/*"
        }
    }
}
