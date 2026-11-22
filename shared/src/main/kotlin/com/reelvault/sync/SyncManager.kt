// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.sync

import com.reelvault.data.remote.ChannelFactory
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import reelvault.Reelvault
import reelvault.ReelVaultGrpcKt
import java.io.File

/**
 * Drives a catalog sync run between the local embedded core (loopback) and a
 * remote daemon (TLS-pinned, bearer-token authed).
 *
 * Lives in `shared/` so it can be reused by both Android and desktop.  The
 * Android-specific ingest step (JNI call into the embedded core) is injected
 * via [onIngestSynced] so this class stays platform-neutral.
 *
 * Usage:
 *   1. Construct with both endpoints.
 *   2. Collect [jobs] / [isRunning] / [lastResult] in your UI.
 *   3. Call [startSync] from a coroutine (it suspends until the run finishes).
 *   4. Optionally call [cancel] to abort early.
 */
class SyncManager(
    /** Host:port for the LOCAL embedded core (loopback, plaintext). */
    private val localChannelFactory: ChannelFactory,
    private val localGrpcPort: Int,
    /** Host:port + credentials for the REMOTE daemon (TLS-pinned, bearer token). */
    private val remoteChannelFactory: ChannelFactory,
    private val remoteHost: String,
    private val remoteGrpcPort: Int,
    private val remoteMediaPort: Int,
    private val token: String,
    private val fingerprint: String,
    /**
     * Platform callback invoked after a video has been downloaded during a
     * FROM_REMOTE pull.  Implementations should ingest the file into the local
     * core (Android: JNI call; future desktop: direct gRPC call into local
     * daemon).  Returns 0 on success, non-zero on failure.
     */
    private val onIngestSynced: ((path: String, filename: String, contentHash: String, derivedHeight: Int) -> Int)? = null,
) {
    private val _jobs = MutableStateFlow<List<SyncJob>>(emptyList())
    val jobs: StateFlow<List<SyncJob>> = _jobs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastResult = MutableStateFlow<SyncRunResult?>(null)
    val lastResult: StateFlow<SyncRunResult?> = _lastResult.asStateFlow()

    private val concurrencyLimit = 2

    private companion object {
        const val UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024
    }

    @Volatile
    private var cancelled = false

    /**
     * Start a sync run for the given [profile].  Suspends until the run
     * finishes (or is cancelled via [cancel]).  No-op if already running.
     */
    suspend fun startSync(profile: SyncProfile) {
        if (_isRunning.value) return
        if (!CatalogSyncFeature.ENABLED) {
            _lastResult.value = SyncRunResult(errors = listOf("Catalog sync is not available yet"))
            return
        }
        _isRunning.value = true
        cancelled = false
        _jobs.value = emptyList()

        try {
            val result = when (profile.direction) {
                SyncDirection.TO_REMOTE -> runPush(profile)
                SyncDirection.FROM_REMOTE -> runPull(profile)
                SyncDirection.MIRROR -> {
                    val pull = runPull(profile)
                    val push = if (!cancelled) runPush(profile) else SyncRunResult()
                    SyncRunResult(
                        total = pull.total + push.total,
                        completed = pull.completed + push.completed,
                        failed = pull.failed + push.failed,
                        conflicts = pull.conflicts + push.conflicts,
                        errors = pull.errors + push.errors,
                    )
                }
            }
            _lastResult.value = result
        } catch (e: Exception) {
            _lastResult.value = SyncRunResult(errors = listOf(e.message ?: "Unknown error"))
        } finally {
            _isRunning.value = false
        }
    }

    /** Request cancellation of the current run. The run finishes its in-flight
     *  transfers before returning. */
    fun cancel() {
        cancelled = true
    }

    // ── Push (TO_REMOTE) ──────────────────────────────────────────────────

    private suspend fun runPush(profile: SyncProfile): SyncRunResult {
        var total = 0
        var completed = 0
        var failed = 0
        val errors = mutableListOf<String>()

        val localChannel = localChannelFactory.createPlaintext("127.0.0.1", localGrpcPort)
        val remoteChannel = remoteChannelFactory.createPinned(remoteHost, remoteGrpcPort, fingerprint)
        try {
            val localStub = ReelVaultGrpcKt.ReelVaultCoroutineStub(localChannel)
            val authedChannel: Channel = ClientInterceptors.intercept(
                remoteChannel, BearerTokenInterceptor(token)
            )
            val remoteStub = ReelVaultGrpcKt.ReelVaultCoroutineStub(authedChannel)

            val entries = collectManifest(localStub, profile)
            total = entries.size

            coroutineScope {
                val semaphore = kotlinx.coroutines.sync.Semaphore(concurrencyLimit)
                entries.map { entry ->
                    async {
                        if (cancelled) return@async
                        semaphore.acquire()
                        try {
                            pushVideo(entry, localStub, remoteStub)
                            completed++
                        } catch (e: Exception) {
                            failed++
                            errors.add(e.message ?: "push failed for ${entry.filename}")
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            // Sync smart collections: rewrite tag UUIDs from local → remote.
            if (!cancelled) {
                syncSmartCollections(
                    srcStub = localStub,
                    dstStub = remoteStub,
                    errors = errors,
                )
            }
        } finally {
            localChannel.shutdown()
            remoteChannel.shutdown()
        }

        return SyncRunResult(total, completed, failed, 0, errors)
    }

    /**
     * Push one video: read the original from the path the local core reports
     * (`GetMetadata.path`; no loopback media route is involved), stream it to
     * the remote through the resumable `/upload` protocol in [UPLOAD_CHUNK_BYTES]
     * pieces (resuming from the server's `bytes_received`, verified by
     * `content_hash`), then copy the catalog data (tags, rating, ...) onto the
     * video the remote indexed.
     */
    private suspend fun pushVideo(
        entry: Reelvault.SyncManifestEntry,
        localStub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
        remoteStub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
    ) = withContext(Dispatchers.IO) {
        val job = SyncJob(
            videoId = entry.videoId,
            filename = entry.filename,
            direction = SyncDirection.TO_REMOTE,
            status = SyncJobStatus.IN_FLIGHT,
        )
        updateJobs { it + job }
        fun finish(status: SyncJobStatus, progress: Float = 0f) =
            updateJobs { jobs -> jobs.map { if (it.id == job.id) it.copy(status = status, progress = progress) else it } }

        try {
            val path = localStub.getMetadata(
                Reelvault.GetMetadataRequest.newBuilder().setVideoId(entry.videoId).build()
            ).path
            val file = File(path)
            val total = checkPushable(entry.filename, file, entry.contentHash)

            val client = com.reelvault.data.remote.PinnedTls.pinnedHttpClient(fingerprint)
            val base = "https://$remoteHost:$remoteMediaPort"
            // Deterministic id so an interrupted push resumes (hex hash is alphanumeric).
            val uploadId = "sync-" + entry.contentHash.filter { it.isLetterOrDigit() }.take(40)

            var offset = queryBytesReceived(client, base, uploadId).coerceAtMost(total)
            if (offset == total) offset = 0 // a stale full .part: restart rather than resend nothing
            var remoteVideoId: String? = null
            java.io.RandomAccessFile(file, "r").use { raf ->
                while (offset < total) {
                    val len = minOf(UPLOAD_CHUNK_BYTES.toLong(), total - offset).toInt()
                    val chunk = ByteArray(len).also { raf.seek(offset); raf.readFully(it) }
                    val url = "$base/upload?filename=${java.net.URLEncoder.encode(entry.filename, "UTF-8")}" +
                        "&upload_id=$uploadId&total_size=$total&offset=$offset" +
                        "&content_hash=${java.net.URLEncoder.encode(entry.contentHash, "UTF-8")}"
                    val req = okhttp3.Request.Builder().url(url)
                        .header("Authorization", "Bearer $token")
                        .post(chunk.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("Upload failed: HTTP ${resp.code}")
                        val json = org.json.JSONObject(resp.body?.string().orEmpty())
                        offset += len
                        if (offset >= total) {
                            remoteVideoId = json.optString("video_id").takeIf { it.isNotEmpty() && it != "null" }
                        }
                    }
                    finish(SyncJobStatus.IN_FLIGHT, offset.toFloat() / total * 0.95f)
                }
            }
            val newId = remoteVideoId ?: throw Exception("${entry.filename}: remote did not index the upload")

            val data = localStub.getVideoCatalogData(
                Reelvault.VideoCatalogDataRequest.newBuilder().setVideoId(entry.videoId).build()
            )
            remoteStub.applyVideoCatalogData(
                Reelvault.ApplyCatalogDataRequest.newBuilder().setVideoId(newId).setData(data).build()
            )
            finish(SyncJobStatus.DONE, 1f)
        } catch (e: Exception) {
            finish(SyncJobStatus.FAILED)
            throw e
        }
    }

    /** Bytes the remote already holds for [uploadId] (0 when none). */
    private fun queryBytesReceived(client: okhttp3.OkHttpClient, base: String, uploadId: String): Long {
        val req = okhttp3.Request.Builder().url("$base/upload/$uploadId/status")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Upload status failed: HTTP ${resp.code}")
            org.json.JSONObject(resp.body?.string().orEmpty()).optLong("bytes_received", 0L)
        }
    }

    // ── Pull (FROM_REMOTE) ────────────────────────────────────────────────

    private suspend fun runPull(profile: SyncProfile): SyncRunResult {
        var total = 0
        var completed = 0
        var failed = 0
        val errors = mutableListOf<String>()

        val remoteChannel = remoteChannelFactory.createPinned(remoteHost, remoteGrpcPort, fingerprint)
        val localChannel = localChannelFactory.createPlaintext("127.0.0.1", localGrpcPort)
        try {
            val authedChannel: Channel = ClientInterceptors.intercept(
                remoteChannel, BearerTokenInterceptor(token)
            )
            val remoteStub = ReelVaultGrpcKt.ReelVaultCoroutineStub(authedChannel)
            val localStub = ReelVaultGrpcKt.ReelVaultCoroutineStub(localChannel)

            val entries = collectManifest(remoteStub, profile)
            total = entries.size

            coroutineScope {
                val semaphore = kotlinx.coroutines.sync.Semaphore(concurrencyLimit)
                entries.map { entry ->
                    async {
                        if (cancelled) return@async
                        semaphore.acquire()
                        try {
                            pullVideo(entry, profile, remoteStub)
                            completed++
                        } catch (e: Exception) {
                            failed++
                            errors.add(e.message ?: "pull failed for ${entry.filename}")
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            // Sync smart collections: rewrite tag UUIDs from remote → local.
            if (!cancelled) {
                syncSmartCollections(
                    srcStub = remoteStub,
                    dstStub = localStub,
                    errors = errors,
                )
            }
        } finally {
            remoteChannel.shutdown()
            localChannel.shutdown()
        }

        return SyncRunResult(total, completed, failed, 0, errors)
    }

    private suspend fun pullVideo(
        entry: Reelvault.SyncManifestEntry,
        profile: SyncProfile,
        remoteStub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
    ) = withContext(Dispatchers.IO) {
        val job = SyncJob(
            videoId = entry.videoId,
            filename = entry.filename,
            direction = SyncDirection.FROM_REMOTE,
            status = SyncJobStatus.IN_FLIGHT,
        )
        updateJobs { it + job }

        // 1. Ask the remote to prepare a rendition at the target resolution.
        val prepReq = Reelvault.PrepareRenditionRequest.newBuilder()
            .setVideoId(entry.videoId)
            .setTargetHeight(profile.targetHeight)
            .build()

        var downloadPath = ""
        remoteStub.prepareRendition(prepReq).collect { progress ->
            when (progress.phase) {
                Reelvault.PrepareRenditionProgress.Phase.FAILED ->
                    throw Exception("PrepareRendition failed: ${progress.detail}")
                Reelvault.PrepareRenditionProgress.Phase.READY ->
                    downloadPath = progress.downloadPath
                else -> {
                    val fraction = progress.fraction
                    updateJobs { jobs ->
                        jobs.map { if (it.id == job.id) it.copy(progress = fraction * 0.5f) else it }
                    }
                }
            }
        }

        if (downloadPath.isEmpty()) throw Exception("No download path from PrepareRendition")

        // 2. Download the rendition.
        val remoteMediaBase = "https://$remoteHost:$remoteMediaPort"
        val downloadUrl = java.net.URL("$remoteMediaBase$downloadPath")
        val destDir = File(System.getProperty("user.home", "/tmp"), "ReelVaultSynced").also { it.mkdirs() }
        val destFile = File(destDir, entry.filename)

        val client = com.reelvault.data.remote.PinnedTls.pinnedHttpClient(fingerprint)
        val dlRequest = okhttp3.Request.Builder()
            .url(downloadUrl.toString())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(dlRequest).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Download failed: HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw Exception("Empty response body for download")
        }

        updateJobs { jobs ->
            jobs.map { if (it.id == job.id) it.copy(progress = 0.8f) else it }
        }

        // 3. Ingest into the local core (platform-injected callback).
        onIngestSynced?.invoke(
            destFile.absolutePath,
            entry.filename,
            entry.contentHash,
            profile.targetHeight,
        )

        updateJobs { jobs ->
            jobs.map { if (it.id == job.id) it.copy(status = SyncJobStatus.DONE, progress = 1f) else it }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Collect all (non-derived) manifest entries from [stub], following cursors. */
    private suspend fun collectManifest(
        stub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
        profile: SyncProfile,
    ): List<Reelvault.SyncManifestEntry> {
        val entries = mutableListOf<Reelvault.SyncManifestEntry>()
        val req = Reelvault.SyncManifestRequest.newBuilder()
            .setPageSize(200)
            .apply { if (profile.filterJson.isNotEmpty()) setSmartFilterJson(profile.filterJson) }
            .build()
        stub.getSyncManifest(req).collect { entry ->
            if (!entry.isDerived) entries.add(entry)
        }
        return entries
    }

    private fun updateJobs(transform: (List<SyncJob>) -> List<SyncJob>) {
        _jobs.value = transform(_jobs.value)
    }

    // ── Smart-collection sync ─────────────────────────────────────────────

    /**
     * Fetches all tags from [stub] and returns a map of tag-id → tag-name.
     * Returns an empty map on any error.
     */
    private suspend fun fetchTagIdToName(
        stub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val response = stub.listTags(Reelvault.ListTagsRequest.newBuilder().build())
            response.tagsList.associate { it.id to it.name }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Copies smart collections from [srcStub] to [dstStub], rewriting tag UUIDs
     * from the source catalog to the destination catalog (matched by name).
     *
     * Skips collections that already exist on the destination (matched by name).
     * Non-smart collections are skipped (they have no filter_json and their
     * membership is determined by the video sync, not here).
     * Errors are appended to [errors] but do not abort the video sync.
     */
    private suspend fun syncSmartCollections(
        srcStub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
        dstStub: ReelVaultGrpcKt.ReelVaultCoroutineStub,
        errors: MutableList<String>,
    ) = withContext(Dispatchers.IO) {
        try {
            // Fetch tag maps from both catalogs in parallel.
            val srcTagsDeferred = async { fetchTagIdToName(srcStub) }
            val dstTagsDeferred = async { fetchTagIdToName(dstStub) }
            val srcTags = srcTagsDeferred.await()
            val dstTags = dstTagsDeferred.await()

            // Fetch smart collections from source.
            val srcCollections = try {
                srcStub.listCollections(
                    Reelvault.ListCollectionsRequest.newBuilder().build()
                ).collectionsList.filter { it.isSmart && it.filterJson.isNotBlank() }
            } catch (e: Exception) {
                errors.add("syncSmartCollections: failed to list source collections: ${e.message}")
                return@withContext
            }

            if (srcCollections.isEmpty()) return@withContext

            // Fetch existing destination collection names to skip duplicates.
            val dstCollectionNames: Set<String> = try {
                dstStub.listCollections(
                    Reelvault.ListCollectionsRequest.newBuilder().build()
                ).collectionsList.map { it.name }.toHashSet()
            } catch (e: Exception) {
                errors.add("syncSmartCollections: failed to list destination collections: ${e.message}")
                return@withContext
            }

            for (col in srcCollections) {
                if (cancelled) break
                if (col.name in dstCollectionNames) continue  // already present

                val rewrittenJson = rewriteSmartFilterTagIds(col.filterJson, srcTags, dstTags)

                try {
                    dstStub.createCollection(
                        Reelvault.CreateCollectionRequest.newBuilder()
                            .setName(col.name)
                            .setIsSmart(true)
                            .setFilterJson(rewrittenJson)
                            .build()
                    )
                } catch (e: Exception) {
                    errors.add("syncSmartCollections: failed to create '${col.name}' on destination: ${e.message}")
                }
            }
        } catch (e: Exception) {
            errors.add("syncSmartCollections: unexpected error: ${e.message}")
        }
    }
}

// ── Bearer token interceptor (same pattern as VideoRepository) ────────────────

private class BearerTokenInterceptor(private val token: String) : ClientInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                headers.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer $token",
                )
                super.start(responseListener, headers)
            }
        }
}

/**
 * Validate a push source and return its size. A missing/non-file original, an
 * empty (0-byte) one, or one whose content hash is not computed yet fails with
 * a specific message instead of an opaque upload error.
 */
internal fun checkPushable(filename: String, file: File, contentHash: String): Long {
    if (!file.isFile) throw Exception("$filename: original is not a local file (${file.path})")
    val total = file.length()
    if (total == 0L) throw Exception("$filename: original is empty (0 bytes); nothing to push")
    if (contentHash.isEmpty()) throw Exception("$filename: content hash not computed yet")
    return total
}
