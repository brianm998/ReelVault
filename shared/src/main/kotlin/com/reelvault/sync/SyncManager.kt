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

    @Volatile
    private var cancelled = false

    /**
     * Start a sync run for the given [profile].  Suspends until the run
     * finishes (or is cancelled via [cancel]).  No-op if already running.
     */
    suspend fun startSync(profile: SyncProfile) {
        if (_isRunning.value) return
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
        try {
            val localStub = ReelVaultGrpcKt.ReelVaultCoroutineStub(localChannel)
            val entries = collectManifest(localStub, profile)
            total = entries.size

            coroutineScope {
                val semaphore = kotlinx.coroutines.sync.Semaphore(concurrencyLimit)
                entries.map { entry ->
                    async {
                        if (cancelled) return@async
                        semaphore.acquire()
                        try {
                            pushVideo(entry, profile)
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
        } finally {
            localChannel.shutdown()
        }

        return SyncRunResult(total, completed, failed, 0, errors)
    }

    private suspend fun pushVideo(
        entry: Reelvault.SyncManifestEntry,
        profile: SyncProfile,
    ) = withContext(Dispatchers.IO) {
        val job = SyncJob(
            videoId = entry.videoId,
            filename = entry.filename,
            direction = SyncDirection.TO_REMOTE,
            status = SyncJobStatus.IN_FLIGHT,
        )
        updateJobs { it + job }

        // Resolve local media URL from the local daemon's media port (loopback).
        val localMediaBase = "http://127.0.0.1:${localGrpcPort + 1}"
        val remoteUploadUrl = "https://$remoteHost:$remoteMediaPort/upload"

        // Stream local → remote via HTTP upload.
        val client = com.reelvault.data.remote.PinnedTls.pinnedHttpClient(fingerprint)
        val sourceUrl = java.net.URL("$localMediaBase/media/${entry.videoId}")
        val bytes = sourceUrl.openStream().use { it.readBytes() }
        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val body = bytes.toRequestBody(mediaType)
        val request = okhttp3.Request.Builder()
            .url("$remoteUploadUrl?filename=${java.net.URLEncoder.encode(entry.filename, "UTF-8")}")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                updateJobs { jobs -> jobs.map { if (it.id == job.id) it.copy(status = SyncJobStatus.FAILED) else it } }
                throw Exception("Upload failed: HTTP ${resp.code}")
            }
        }

        updateJobs { jobs -> jobs.map { if (it.id == job.id) it.copy(status = SyncJobStatus.DONE, progress = 1f) else it } }
    }

    // ── Pull (FROM_REMOTE) ────────────────────────────────────────────────

    private suspend fun runPull(profile: SyncProfile): SyncRunResult {
        var total = 0
        var completed = 0
        var failed = 0
        val errors = mutableListOf<String>()

        val remoteChannel = remoteChannelFactory.createPinned(remoteHost, remoteGrpcPort, fingerprint)
        try {
            val authedChannel: Channel = ClientInterceptors.intercept(
                remoteChannel, BearerTokenInterceptor(token)
            )
            val remoteStub = ReelVaultGrpcKt.ReelVaultCoroutineStub(authedChannel)
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
        } finally {
            remoteChannel.shutdown()
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

