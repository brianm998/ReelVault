// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.reelvault.data.remote.PinnedTls
import com.reelvault.data.remote.RemoteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

data class LocalVideo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val dateAdded: Long,
)

class LocalMediaRepository(private val context: Context) {

    suspend fun listVideos(): List<LocalVideo> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<LocalVideo>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                videos += LocalVideo(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "video_$id",
                    durationMs = cursor.getLong(durCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: "video/mp4",
                    dateAdded = cursor.getLong(dateCol),
                )
            }
        }
        videos
    }

    /**
     * Uploads [video] to the currently connected remote daemon's `/upload`
     * endpoint. Copies the video to a temp file first (ContentProvider URIs
     * are not directly seekable by OkHttp), then streams it as multipart.
     *
     * Returns `true` on HTTP 2xx, throws on network/IO errors.
     */
    suspend fun uploadToServer(video: LocalVideo): Boolean = withContext(Dispatchers.IO) {
        val ep = RemoteConnection.endpoint
            ?: throw IllegalStateException("Not connected to a remote server")

        // Copy to a temp file so OkHttp can read it with a seekable source.
        val tmpFile = File(context.cacheDir, "upload_${video.id}.tmp")
        try {
            context.contentResolver.openInputStream(video.uri)?.use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open ${video.uri}")

            val okHttpClient = PinnedTls.pinnedHttpClient(ep.fingerprintHex)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    video.displayName,
                    tmpFile.asRequestBody(video.mimeType.toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url("https://${ep.host}:${ep.mediaPort}/upload")
                .header("Authorization", "Bearer ${ep.token}")
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { it.isSuccessful }
        } finally {
            tmpFile.delete()
        }
    }
}
