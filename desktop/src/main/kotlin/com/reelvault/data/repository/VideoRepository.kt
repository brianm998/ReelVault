// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.repository

import com.reelvault.data.models.*
import com.reelvault.data.models.Collection as VideoCollection
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import reelvault.Reelvault
import reelvault.ReelVaultGrpcKt

/**
 * Repository for communicating with the ReelVault Rust backend via gRPC.
 */
class VideoRepository(
    private val host: String = "localhost",
    /** Initial port; mutable so [connect] can target a freshly-spawned daemon. */
    private var port: Int = 50051
) {
    private val logger = LoggerFactory.getLogger(VideoRepository::class.java)

    private var channel: ManagedChannel? = null
    private var stub: ReelVaultGrpcKt.ReelVaultCoroutineStub? = null

    /** Port the repository is currently configured to talk to. */
    val currentPort: Int get() = port

    /**
     * Try to connect to the daemon at the configured (or supplied) port.
     * Returns `true` if the GetStatus probe succeeds; on failure the
     * channel is torn down so the caller can retry on a different port.
     *
     * If [overridePort] is non-null, it both targets that port and updates
     * the repository's notion of `port` for subsequent reconnects.
     */
    suspend fun connect(overridePort: Int? = null): Boolean = withContext(Dispatchers.IO) {
        overridePort?.let { port = it }
        // Tear down any stale channel before reconnecting.
        try { channel?.shutdownNow() } catch (_: Exception) {}
        channel = null
        stub = null
        return@withContext try {
            channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
            stub = ReelVaultGrpcKt.ReelVaultCoroutineStub(channel!!)

            // Verify connection by calling GetStatus
            val statusRequest = Reelvault.GetStatusRequest.newBuilder().build()
            stub!!.getStatus(statusRequest)
            logger.info("Connected to ReelVault backend at $host:$port")
            true
        } catch (e: Exception) {
            logger.error("Failed to connect to backend on port $port: ${e.message}")
            channel?.shutdown()
            channel = null
            stub = null
            false
        }
    }

    fun disconnect() {
        channel?.shutdown()
        channel = null
        stub = null
        logger.info("Disconnected from ReelVault backend")
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        return@withContext channel != null && !channel!!.isShutdown
    }

    private fun protoToVideoSummary(proto: Reelvault.VideoSummary): VideoSummary {
        return VideoSummary(
            id = proto.id,
            filename = proto.filename,
            path = proto.path,
            durationMs = proto.durationMs,
            width = proto.width,
            height = proto.height,
            codecVideo = proto.codecVideo,
            codecAudio = proto.codecAudio,
            fps = proto.fps,
            sizeBytes = proto.sizeBytes,
            indexedAt = proto.indexedAt,
            creationDate = proto.creationDate,
            tags = proto.tagsList.toList(),
            hasThumbnail = proto.hasThumbnail,
            groupId = proto.groupId,
            groupSize = proto.groupSize,
            groupPreferredId = proto.groupPreferredId,
            groupPreferredPath = proto.groupPreferredPath,
            proxyCount = proto.proxyCount,
            proxyOf = proto.proxyOf,
            playableNatively = proto.playableNatively,
            rating = proto.rating,
            colorLabel = proto.colorLabel,
            cameraModel = proto.cameraModel,
            cameraDisplayName = proto.cameraDisplayName.ifEmpty { proto.cameraModel },
            gpsLatitude = proto.gpsLatitude,
            gpsLongitude = proto.gpsLongitude,
            lensModel = proto.lensModel,
            iso = proto.iso,
            aperture = proto.aperture,
            exposureTimeS = proto.exposureTimeS,
            focalLengthMm = proto.focalLengthMm,
            fullResolution = FullResolutionStatus.fromWire(proto.fullResolutionValue),
        )
    }

    private fun protoToVideoMetadata(proto: Reelvault.VideoMetadata): VideoMetadata {
        return VideoMetadata(
            id = proto.id,
            filename = proto.filename,
            path = proto.path,
            sizeBytes = proto.sizeBytes,
            durationMs = proto.durationMs,
            width = proto.width,
            height = proto.height,
            fps = proto.fps,
            bitrate = proto.bitrate,
            codecVideo = proto.codecVideo,
            colorSpace = proto.colorSpace,
            hdr = proto.hdr,
            codecAudio = proto.codecAudio,
            audioChannels = proto.audioChannels,
            audioSampleRate = proto.audioSampleRate,
            creationDate = proto.creationDate,
            modificationDate = proto.modificationDate,
            indexedAt = proto.indexedAt,
            cameraModel = proto.cameraModel,
            cameraDisplayName = proto.cameraDisplayName.ifEmpty { proto.cameraModel },
            lensModel = proto.lensModel,
            gpsLatitude = proto.gpsLatitude,
            gpsLongitude = proto.gpsLongitude,
            gpsAltitude = proto.gpsAltitude,
            tags = proto.tagsList.toList(),
            collections = proto.collectionsList.toList(),
            notes = proto.notes,
            volumeId = proto.volumeId,
            isOnline = proto.isOnline,
            rating = proto.rating,
            colorLabel = proto.colorLabel,
            iso = proto.iso,
            aperture = proto.aperture,
            exposureTimeS = proto.exposureTimeS,
            focalLengthMm = proto.focalLengthMm,
            exposureMode = proto.exposureMode,
            exposureProgram = proto.exposureProgram,
            whiteBalance = proto.whiteBalance,
            fullResolution = FullResolutionStatus.fromWire(proto.fullResolutionValue),
        )
    }

    suspend fun listVideos(
        limit: Int = 50,
        offset: Int = 0,
        sortBy: String = "name",
        sortAscending: Boolean = true,
        filterTags: List<String> = emptyList(),
        collectionId: String? = null,
        locationPath: String = "",
        filterCamera: String = "",
        filterLens: String = "",
        filterCodec: String = "",
        filterCaptureYear: Int = 0,
        /** Set non-null to filter to videos within `geoFilter.third` km of
         *  (lat, lon). Used when the user taps a pin on the global map. */
        geoFilter: Triple<Double, Double, Double>? = null,
        /** 0 = no rating filter; 1..5 = "show videos with at least this rating". */
        filterMinRating: Int = 0,
        /** "" = no colour filter; otherwise exact-match the label. */
        filterColorLabel: String = "",
    ): Pair<List<VideoSummary>, Long> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(emptyList(), 0L)
        try {
            val builder = Reelvault.ListVideosRequest.newBuilder()
                .setLimit(limit)
                .setOffset(offset)
                .setSortBy(sortBy)
                .setSortAscending(sortAscending)
                .addAllFilterTags(filterTags)  // tag IDs or names; backend resolves
                .setCollectionId(collectionId ?: "")
                .setLocationPath(locationPath)
                .setFilterCamera(filterCamera)
                .setFilterLens(filterLens)
                .setFilterCodec(filterCodec)
                .setFilterCaptureYear(filterCaptureYear)
                .setFilterMinRating(filterMinRating)
                .setFilterColorLabel(filterColorLabel)
            if (geoFilter != null) {
                builder
                    .setFilterByLocation(true)
                    .setFilterLatitude(geoFilter.first)
                    .setFilterLongitude(geoFilter.second)
                    .setFilterRadiusKm(geoFilter.third)
            }
            val request = builder.build()

            val response = s.listVideos(request)
            val videos = response.videosList.map { protoToVideoSummary(it) }
            Pair(videos, response.totalCount)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not an error — the caller superseded this
            // request. Rethrow so structured concurrency unwinds cleanly
            // and the calling coroutine doesn't see a stale empty result.
            throw e
        } catch (e: Exception) {
            logger.error("Failed to list videos: ${e.message}", e)
            Pair(emptyList(), 0L)
        }
    }

    suspend fun getFilterOptions(): com.reelvault.data.models.FilterOptions = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext com.reelvault.data.models.FilterOptions()
        try {
            val response = s.getFilterOptions(Reelvault.GetFilterOptionsRequest.newBuilder().build())
            com.reelvault.data.models.FilterOptions(
                cameras = response.camerasList.toList(),
                cameraDisplayNames = response.cameraDisplayNamesList.toList(),
                lenses = response.lensesList.toList(),
                codecs = response.codecsList.toList(),
                captureYears = response.captureYearsList.toList()
            )
        } catch (e: Exception) {
            logger.error("Failed to get filter options: ${e.message}", e)
            com.reelvault.data.models.FilterOptions()
        }
    }

    /**
     * Fetch the merged camera-name mapping table — every built-in
     * entry plus any custom overrides the user has saved for this
     * catalog. Each entry's [marketingName] already reflects the
     * active override (if any).
     */
    suspend fun listCameraNameMappings(): List<com.reelvault.data.models.CameraNameMapping> =
        withContext(Dispatchers.IO) {
            val s = stub ?: return@withContext emptyList()
            try {
                val response = s.listCameraNameMappings(
                    Reelvault.ListCameraNameMappingsRequest.newBuilder().build()
                )
                response.mappingsList.map {
                    com.reelvault.data.models.CameraNameMapping(
                        internalName = it.internal,
                        marketingName = it.marketing,
                        isBuiltin = it.isBuiltin,
                        isCustom = it.isCustom
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to list camera name mappings: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * Save (or remove) a custom override. Pass an empty
     * [marketingName] to delete the override for [internalName] —
     * the row will fall back to its built-in mapping (if one exists)
     * or vanish from the list entirely.
     *
     * Returns the server's human-readable status message ("Saved …"
     * or "Removed …") so the caller can surface it in a toast.
     */
    suspend fun setCameraNameMapping(
        internalName: String,
        marketingName: String,
    ): String = withContext(Dispatchers.IO) {
        val s = stub ?: throw IllegalStateException("Not connected to ReelVault backend")
        val req = Reelvault.SetCameraNameMappingRequest.newBuilder()
            .setInternal(internalName)
            .setMarketing(marketingName)
            .build()
        val response = s.setCameraNameMapping(req)
        if (!response.success) {
            throw RuntimeException(
                response.error.ifEmpty { "Failed to save camera name mapping" }
            )
        }
        response.message
    }

    suspend fun searchVideos(
        query: String,
        limit: Int = 50,
        offset: Int = 0,
        filterTags: List<String> = emptyList()
    ): Pair<List<VideoSummary>, Long> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(emptyList(), 0L)
        try {
            val request = Reelvault.SearchRequest.newBuilder()
                .setQuery(query)
                .setLimit(limit)
                .setOffset(offset)
                .addAllFilterTags(filterTags)
                .build()

            val response = s.searchVideos(request)
            val videos = response.videosList.map { protoToVideoSummary(it) }
            Pair(videos, response.totalCount)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not an error — caller superseded us.
            throw e
        } catch (e: Exception) {
            logger.error("Failed to search videos: ${e.message}", e)
            Pair(emptyList(), 0L)
        }
    }

    suspend fun getVideoMetadata(videoId: String): VideoMetadata? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Reelvault.GetMetadataRequest.newBuilder()
                .setVideoId(videoId)
                .build()
            protoToVideoMetadata(s.getMetadata(request))
        } catch (e: Exception) {
            logger.error("Failed to get metadata for $videoId: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch the N evenly-spaced scrub frames for [videoId] in parallel.
     * Returns a list of size [count] (some entries may be null if a particular
     * frame failed to generate). Used by the grid's hover-scrub feature.
     */
    suspend fun getScrubFrames(videoId: String, count: Int = 10): List<ByteArray?> =
        coroutineScope {
            val deferred = (0 until count).map { i ->
                async(Dispatchers.IO) { getThumbnail(videoId, "scrub_$i") }
            }
            deferred.map { it.await() }
        }

    suspend fun getThumbnail(videoId: String, size: String = "medium"): ByteArray? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Reelvault.GetThumbnailRequest.newBuilder()
                .setVideoId(videoId)
                .setSize(size)
                .build()

            val chunks = mutableListOf<Byte>()
            s.getThumbnail(request).collect { chunk ->
                chunks.addAll(chunk.data.toByteArray().toList())
            }
            if (chunks.isEmpty()) null else chunks.toByteArray()
        } catch (e: Exception) {
            logger.error("Failed to get thumbnail for $videoId: ${e.message}", e)
            null
        }
    }

    suspend fun addLibraryLocation(path: String, recursive: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        addLibraryLocationWithMessage(path, recursive).first
    }

    suspend fun addLibraryLocationWithMessage(path: String, recursive: Boolean = true): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(false, "Not connected")
        try {
            val request = Reelvault.AddLocationRequest.newBuilder()
                .setPath(path)
                .setRecursive(recursive)
                .build()
            val response = s.addLibraryLocation(request)
            Pair(response.success, response.message)
        } catch (e: Exception) {
            logger.error("Failed to add library location $path: ${e.message}", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun listLibraryLocations(): List<LibraryLocation> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val request = Reelvault.ListLocationsRequest.newBuilder().build()
            val response = s.listLibraryLocations(request)
            response.locationsList.map {
                LibraryLocation(
                    path = it.path,
                    recursive = it.recursive,
                    enabled = it.enabled,
                    videoCount = it.videoCount,
                    lastScanned = it.lastScanned
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to list library locations: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Remove a library location and all its indexed videos from the catalog.
     * The video FILES on disk are not touched. Returns true on success.
     */
    suspend fun removeLibraryLocation(path: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.RemoveLocationRequest.newBuilder()
                .setPath(path)
                .build()
            val response = s.removeLibraryLocation(request)
            if (!response.success) logger.warn("removeLibraryLocation($path): ${response.message}")
            response.success
        } catch (e: Exception) {
            logger.error("Failed to remove library location $path: ${e.message}", e)
            false
        }
    }

    fun scanLibrary(
        locationPath: String = "",
        autoGroup: Boolean = true,
        filenameDateFormat: String = "",
        filenameDatePosition: String = ""
    ): Flow<ScanProgress> {
        val s = stub ?: return flow { }
        val request = Reelvault.ScanLibraryRequest.newBuilder()
            .setLocationPath(locationPath)
            .setForceFullScan(false)
            .setAutoGroup(autoGroup)
            .setFilenameDateFormat(filenameDateFormat)
            .setFilenameDatePosition(filenameDatePosition)
            .build()

        return s.scanLibrary(request).map { proto ->
            ScanProgress(
                status = proto.status,
                videosFound = proto.videosFound.toInt(),
                videosIndexed = proto.videosIndexed.toInt(),
                currentFile = proto.currentFile,
                progressPercent = proto.progressPercent
            )
        }
    }

    // --- Server config ---

    /** Subset of `ConfigResponse` the Kotlin client currently
     *  surfaces. Extended as new fields are exposed. */
    data class ServerConfig(
        val maxNativePlaybackHeight: Int,
        val proxyTargetHeight: Int,
        val maxConcurrentJobs: Int,
        val enableAutoTagging: Boolean,
        /** When true, the post-index pipeline auto-applies a "timelapse" tag
         *  to videos whose recorded resolution exceeds their camera's max
         *  in-camera video resolution. Backed by `auto_tag_history` so a
         *  user-removed tag never gets re-applied. */
        val autoTagTimelapses: Boolean,
    )

    /**
     * In-memory cache of the last successfully-fetched server config.
     * Populated on the first [getConfig] call (or refreshed via the same
     * method) and kept in sync by [updateConfig] after a successful save.
     *
     * Preferences dialogs read [cachedConfig] synchronously to render
     * with the user's actual settings immediately — without that, every
     * dialog-open paid a gRPC round-trip, which can be visible latency
     * on a slow link. Dialogs still call [getConfig] in a background
     * `LaunchedEffect` to refresh, but they no longer block their first
     * paint on the network.
     *
     * `null` means "never fetched yet" — dialogs fall back to sensible
     * defaults until the first fetch completes (typically <100 ms after
     * the catalog opens).
     */
    @Volatile
    private var cachedConfig: ServerConfig? = null

    /** The most recently fetched config, or `null` if we haven't talked
     *  to the daemon yet. Synchronous; suitable for initialising
     *  `mutableStateOf(...)` in a dialog's `remember { }` block. */
    fun cachedConfig(): ServerConfig? = cachedConfig

    suspend fun getConfig(): ServerConfig? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val response = s.getConfig(Reelvault.GetConfigRequest.newBuilder().build())
            val cfg = ServerConfig(
                maxNativePlaybackHeight = response.maxNativePlaybackHeight,
                proxyTargetHeight = response.proxyTargetHeight,
                maxConcurrentJobs = response.maxConcurrentJobs,
                enableAutoTagging = response.enableAutoTagging,
                autoTagTimelapses = response.autoTagTimelapses,
            )
            cachedConfig = cfg
            cfg
        } catch (e: Exception) {
            logger.warn("getConfig failed", e)
            null
        }
    }

    /** Push updated config to the daemon. Server clamps to ranges. */
    suspend fun updateConfig(
        maxNativePlaybackHeight: Int? = null,
        proxyTargetHeight: Int? = null,
        maxConcurrentJobs: Int? = null,
        enableAutoTagging: Boolean? = null,
        autoTagTimelapses: Boolean? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val builder = Reelvault.UpdateConfigRequest.newBuilder()
            maxNativePlaybackHeight?.let { builder.maxNativePlaybackHeight = it }
            proxyTargetHeight?.let { builder.proxyTargetHeight = it }
            maxConcurrentJobs?.let { builder.maxConcurrentJobs = it }
            enableAutoTagging?.let { builder.enableAutoTagging = it }
            autoTagTimelapses?.let { builder.autoTagTimelapses = it }
            s.updateConfig(builder.build())
            // Keep the cache in sync with the value we just persisted so
            // a re-open of any settings dialog sees the saved value
            // without paying another gRPC round-trip. We re-base off the
            // current cache (or a sensible default) so an `updateConfig`
            // that only touched one field doesn't blank out the others.
            val base = cachedConfig ?: ServerConfig(
                maxNativePlaybackHeight = 2160,
                proxyTargetHeight = 720,
                maxConcurrentJobs = 4,
                enableAutoTagging = false,
                autoTagTimelapses = true,
            )
            cachedConfig = base.copy(
                maxNativePlaybackHeight = maxNativePlaybackHeight ?: base.maxNativePlaybackHeight,
                proxyTargetHeight = proxyTargetHeight ?: base.proxyTargetHeight,
                maxConcurrentJobs = maxConcurrentJobs ?: base.maxConcurrentJobs,
                enableAutoTagging = enableAutoTagging ?: base.enableAutoTagging,
                autoTagTimelapses = autoTagTimelapses ?: base.autoTagTimelapses,
            )
            true
        } catch (e: Exception) {
            logger.warn("updateConfig failed", e)
            false
        }
    }

    // --- Proxies ---

    /** Compact view of one proxy video — what the detail-panel sub-list
     *  needs to render the entry (filename, path, resolution, size) plus
     *  the auto-detection metadata so the UI can show a small
     *  "auto-detected" indicator. */
    data class ProxyInfo(
        val id: String,
        val filename: String,
        val path: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val confidence: Double,
        val autoDetected: Boolean,
    )

    suspend fun listProxies(videoId: String): List<ProxyInfo> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val request = Reelvault.ListProxiesRequest.newBuilder()
                .setVideoId(videoId)
                .build()
            s.listProxies(request).proxiesList.map {
                ProxyInfo(
                    id = it.id,
                    filename = it.filename,
                    path = it.path,
                    sizeBytes = it.sizeBytes,
                    width = it.width,
                    height = it.height,
                    confidence = it.confidence,
                    autoDetected = it.autoDetected,
                )
            }
        } catch (e: Exception) {
            logger.warn("listProxies failed", e)
            emptyList()
        }
    }

    /** Server-streamed proxy-creation progress. Use targetHeight=0 to
     *  defer to the daemon's configured default. */
    data class ProxyProgress(
        val status: String,
        val progressPercent: Double,
        val message: String,
        val proxyVideoId: String,
    )

    fun generateProxy(
        videoId: String,
        targetHeight: Int = 0,
        outputPath: String = "",
    ): Flow<ProxyProgress> {
        val s = stub ?: return flow { }
        val request = Reelvault.GenerateProxyRequest.newBuilder()
            .setVideoId(videoId)
            .setTargetHeight(targetHeight)
            .setOutputPath(outputPath)
            .build()
        return s.generateProxy(request).map {
            ProxyProgress(
                status = it.status,
                progressPercent = it.progressPercent,
                message = it.message,
                proxyVideoId = it.proxyVideoId,
            )
        }
    }

    suspend fun setProxyOf(proxyId: String, originalId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.SetProxyOfRequest.newBuilder()
                .setProxyId(proxyId)
                .setOriginalId(originalId)
                .build()
            s.setProxyOf(request)
            true
        } catch (e: Exception) {
            logger.warn("setProxyOf failed", e)
            false
        }
    }

    /** Break a single master ↔ proxy edge without disturbing any
     *  other proxy relationships the row may have. Used by the
     *  inspector's "break" affordance. */
    suspend fun removeProxyLink(masterId: String, proxyId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.RemoveProxyLinkRequest.newBuilder()
                .setMasterId(masterId)
                .setProxyId(proxyId)
                .build()
            s.removeProxyLink(request)
            true
        } catch (e: Exception) {
            logger.warn("removeProxyLink failed", e)
            false
        }
    }

    /** Re-run the auto-detector. Returns (pairsCompared, proxiesMarked). */
    suspend fun detectProxies(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext (0 to 0)
        try {
            val response = s.detectProxies(Reelvault.DetectProxiesRequest.newBuilder().build())
            response.pairsCompared to response.proxiesMarked
        } catch (e: Exception) {
            logger.warn("detectProxies failed", e)
            (0 to 0)
        }
    }

    // --- Real-time catalog events ---

    /**
     * Open a long-lived server-streamed [CatalogEvent] subscription.
     *
     * Returns a cold [Flow] that, when collected, opens the gRPC stream
     * and emits one [CatalogEvent] per server event. The first emission
     * is always a `WATCHER_STARTED` or `WATCHER_DISABLED` greeting so
     * the UI can render its "live updates" indicator without an extra
     * RPC round-trip.
     *
     * The flow ends cleanly when the underlying gRPC stream finishes;
     * the view-model is expected to launch a coroutine that loops over
     * the flow and reconnects with backoff if it ends.
     */
    fun subscribeCatalogEvents(): Flow<CatalogEvent> {
        val s = stub ?: return flow { }
        val request = Reelvault.SubscribeCatalogEventsRequest.newBuilder().build()
        return s.subscribeCatalogEvents(request).map { proto ->
            CatalogEvent(
                kind = when (proto.kind) {
                    Reelvault.CatalogEvent.Kind.VIDEO_ADDED -> CatalogEventKind.VideoAdded
                    Reelvault.CatalogEvent.Kind.VIDEO_MODIFIED -> CatalogEventKind.VideoModified
                    Reelvault.CatalogEvent.Kind.VIDEO_REMOVED -> CatalogEventKind.VideoRemoved
                    Reelvault.CatalogEvent.Kind.WATCHER_STARTED -> CatalogEventKind.WatcherStarted
                    Reelvault.CatalogEvent.Kind.WATCHER_DISABLED -> CatalogEventKind.WatcherDisabled
                    Reelvault.CatalogEvent.Kind.SCAN_STARTED -> CatalogEventKind.ScanStarted
                    Reelvault.CatalogEvent.Kind.SCAN_COMPLETED -> CatalogEventKind.ScanCompleted
                    else -> CatalogEventKind.Unknown
                },
                videoId = proto.videoId,
                path = proto.path,
                atMs = proto.atMs,
                message = proto.message,
            )
        }
    }

    /** Read the daemon's current watcher knobs. Returns
     *  [WatchSettings.Default] on any error so the Preferences UI never
     *  has to handle a missing-value case. */
    suspend fun getWatchSettings(): WatchSettings = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext WatchSettings.Default
        try {
            val response = s.getWatchSettings(
                Reelvault.GetWatchSettingsRequest.newBuilder().build()
            )
            WatchSettings(
                enabled = response.enabled,
                writeSettleMs = response.writeSettleMs,
                pollIntervalMs = response.pollIntervalMs,
            )
        } catch (e: Exception) {
            logger.warn("getWatchSettings failed", e)
            WatchSettings.Default
        }
    }

    /** Push new watcher settings to the daemon. The server clamps to
     *  sensible ranges; we let it. Side effect on the daemon: the
     *  watcher is restarted with the new values. */
    suspend fun updateWatchSettings(settings: WatchSettings): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.WatchSettings.newBuilder()
                .setEnabled(settings.enabled)
                .setWriteSettleMs(settings.writeSettleMs)
                .setPollIntervalMs(settings.pollIntervalMs)
                .build()
            s.updateWatchSettings(request)
            true
        } catch (e: Exception) {
            logger.warn("updateWatchSettings failed", e)
            false
        }
    }

    suspend fun createTag(name: String, color: String = ""): Tag? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Reelvault.CreateTagRequest.newBuilder()
                .setName(name)
                .setColor(color)
                .build()
            val response = s.createTag(request)
            Tag(
                id = response.id,
                name = response.name,
                color = response.color,
                videoCount = response.videoCount
            )
        } catch (e: Exception) {
            logger.error("Failed to create tag: ${e.message}", e)
            null
        }
    }

    suspend fun listTags(): List<Tag> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val request = Reelvault.ListTagsRequest.newBuilder().build()
            val response = s.listTags(request)
            response.tagsList.map {
                Tag(id = it.id, name = it.name, color = it.color, videoCount = it.videoCount)
            }
        } catch (e: Exception) {
            logger.error("Failed to list tags: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun tagVideos(videoIds: List<String>, tagId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.TagVideosRequest.newBuilder()
                .addAllVideoIds(videoIds)
                .setTagId(tagId)
                .build()
            s.tagVideos(request).success
        } catch (e: Exception) {
            logger.error("Failed to tag videos: ${e.message}", e)
            false
        }
    }

    suspend fun untagVideos(videoIds: List<String>, tagId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.UntagVideosRequest.newBuilder()
                .addAllVideoIds(videoIds)
                .setTagId(tagId)
                .build()
            s.untagVideos(request).success
        } catch (e: Exception) {
            logger.error("Failed to untag videos: ${e.message}", e)
            false
        }
    }

    suspend fun createCollection(name: String, isSmart: Boolean = false, filterJson: String = ""): VideoCollection? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Reelvault.CreateCollectionRequest.newBuilder()
                .setName(name)
                .setIsSmart(isSmart)
                .setFilterJson(filterJson)
                .build()
            val response = s.createCollection(request)
            VideoCollection(
                id = response.id,
                name = response.name,
                isSmart = response.isSmart,
                videoCount = response.videoCount
            )
        } catch (e: Exception) {
            logger.error("Failed to create collection: ${e.message}", e)
            null
        }
    }

    suspend fun listCollections(): List<VideoCollection> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val request = Reelvault.ListCollectionsRequest.newBuilder().build()
            val response = s.listCollections(request)
            response.collectionsList.map {
                VideoCollection(
                    id = it.id,
                    name = it.name,
                    isSmart = it.isSmart,
                    videoCount = it.videoCount
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to list collections: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun addToCollection(videoIds: List<String>, collectionId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.AddToCollectionRequest.newBuilder()
                .setCollectionId(collectionId)
                .addAllVideoIds(videoIds)
                .build()
            s.addToCollection(request).success
        } catch (e: Exception) {
            logger.error("Failed to add to collection: ${e.message}", e)
            false
        }
    }

    suspend fun removeFromCollection(videoIds: List<String>, collectionId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.RemoveFromCollectionRequest.newBuilder()
                .setCollectionId(collectionId)
                .addAllVideoIds(videoIds)
                .build()
            s.removeFromCollection(request).success
        } catch (e: Exception) {
            logger.error("Failed to remove from collection: ${e.message}", e)
            false
        }
    }

    suspend fun deleteCollection(collectionId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.DeleteCollectionRequest.newBuilder()
                .setCollectionId(collectionId)
                .build()
            s.deleteCollection(request).success
        } catch (e: Exception) {
            logger.error("Failed to delete collection: ${e.message}", e)
            false
        }
    }

    data class GroupInfo(val id: String, val name: String, val size: Int, val preferredVideoId: String)

    suspend fun createGroup(
        videoIds: List<String>,
        name: String = "",
        preferredVideoId: String = ""
    ): GroupInfo? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Reelvault.CreateGroupRequest.newBuilder()
                .addAllVideoIds(videoIds)
                .setName(name)
                .setPreferredVideoId(preferredVideoId)
                .build()
            val response = s.createGroup(request)
            GroupInfo(
                id = response.id,
                name = response.name,
                size = response.size,
                preferredVideoId = response.preferredVideoId
            )
        } catch (e: Exception) {
            logger.error("Failed to create group: ${e.message}", e)
            null
        }
    }

    suspend fun listGroupMembers(groupId: String): Pair<List<VideoSummary>, String> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(emptyList(), "")
        try {
            val request = Reelvault.ListGroupMembersRequest.newBuilder()
                .setGroupId(groupId)
                .build()
            val response = s.listGroupMembers(request)
            val members = response.membersList.map { protoToVideoSummary(it) }
            Pair(members, response.preferredVideoId)
        } catch (e: Exception) {
            logger.error("Failed to list group members: ${e.message}", e)
            Pair(emptyList(), "")
        }
    }

    suspend fun setGroupPreferred(groupId: String, videoId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.SetGroupPreferredRequest.newBuilder()
                .setGroupId(groupId)
                .setVideoId(videoId)
                .build()
            s.setGroupPreferred(request).success
        } catch (e: Exception) {
            logger.error("Failed to set preferred video: ${e.message}", e)
            false
        }
    }

    suspend fun ungroupVideo(videoId: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.UngroupVideoRequest.newBuilder()
                .setVideoId(videoId)
                .build()
            s.ungroupVideo(request).success
        } catch (e: Exception) {
            logger.error("Failed to ungroup video: ${e.message}", e)
            false
        }
    }

    suspend fun autoGroup(
        sameDirectoryOnly: Boolean = true,
        matchDuration: Boolean = true,
        matchFps: Boolean = true
    ): Triple<Int, Int, String> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Triple(0, 0, "Not connected")
        try {
            val request = Reelvault.AutoGroupRequest.newBuilder()
                .setSameDirectoryOnly(sameDirectoryOnly)
                .setMatchDuration(matchDuration)
                .setMatchFps(matchFps)
                .build()
            val response = s.autoGroupVideos(request)
            Triple(response.groupsCreated, response.videosGrouped, response.message)
        } catch (e: Exception) {
            logger.error("Failed to auto-group: ${e.message}", e)
            Triple(0, 0, "Error: ${e.message}")
        }
    }

    suspend fun updateVideoNotes(videoId: String, notes: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.UpdateNotesRequest.newBuilder()
                .setVideoId(videoId)
                .setNotes(notes)
                .build()
            s.updateVideoNotes(request).success
        } catch (e: Exception) {
            logger.error("Failed to update notes: ${e.message}", e)
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Geolocation
    // ────────────────────────────────────────────────────────────────────

    /** Set GPS coordinates on `videoId`. Optionally also embed them into
     *  the video file via the daemon's ffmpeg helper. */
    suspend fun updateVideoLocation(
        videoId: String,
        latitude: Double,
        longitude: Double,
        altitude: Double = 0.0,
        writeToFile: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val req = Reelvault.UpdateVideoLocationRequest.newBuilder()
                .setVideoId(videoId)
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setAltitude(altitude)
                .setWriteToFile(writeToFile)
                .build()
            s.updateVideoLocation(req).success
        } catch (e: Exception) {
            logger.error("UpdateVideoLocation failed: ${e.message}", e)
            false
        }
    }

    /** Set the capture timestamp (Unix ms, UTC) on `videoId`. Optionally
     *  also embeds `creation_time` into the video file via the daemon's
     *  ffmpeg helper. */
    suspend fun updateVideoCaptureDate(
        videoId: String,
        timestampMs: Long,
        writeToFile: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val req = Reelvault.UpdateVideoCaptureDateRequest.newBuilder()
                .setVideoId(videoId)
                .setTimestampMs(timestampMs)
                .setWriteToFile(writeToFile)
                .build()
            s.updateVideoCaptureDate(req).success
        } catch (e: Exception) {
            logger.error("UpdateVideoCaptureDate failed: ${e.message}", e)
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Named locations
    // ────────────────────────────────────────────────────────────────────

    /** List every user-named place in the catalog. Clients cache the
     *  result and use `GridViewModel.nameForLocation` to resolve any
     *  (lat, lon) into a name client-side. */
    suspend fun listNamedLocations(): List<com.reelvault.data.models.NamedLocation> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val resp = s.listNamedLocations(
                Reelvault.ListNamedLocationsRequest.newBuilder().build()
            )
            resp.locationsList.map { p ->
                com.reelvault.data.models.NamedLocation(
                    id = p.id,
                    name = p.name,
                    latitude = p.latitude,
                    longitude = p.longitude,
                    radiusMeters = p.radiusM,
                    createdAtMs = p.createdAtMs,
                    updatedAtMs = p.updatedAtMs,
                )
            }
        } catch (e: Exception) {
            logger.error("ListNamedLocations failed: ${e.message}", e)
            emptyList()
        }
    }

    /** Insert or update a named location. Pass an empty `id` to create a
     *  new row; otherwise it updates. Returns the persisted entity (with
     *  assigned id + timestamps) or null on failure. */
    suspend fun upsertNamedLocation(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 250.0,
    ): com.reelvault.data.models.NamedLocation? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val req = Reelvault.UpsertNamedLocationRequest.newBuilder()
                .setId(id)
                .setName(name)
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setRadiusM(radiusMeters)
                .build()
            val resp = s.upsertNamedLocation(req)
            if (!resp.success || !resp.hasLocation()) return@withContext null
            val p = resp.location
            com.reelvault.data.models.NamedLocation(
                id = p.id,
                name = p.name,
                latitude = p.latitude,
                longitude = p.longitude,
                radiusMeters = p.radiusM,
                createdAtMs = p.createdAtMs,
                updatedAtMs = p.updatedAtMs,
            )
        } catch (e: Exception) {
            logger.error("UpsertNamedLocation failed: ${e.message}", e)
            null
        }
    }

    /** Delete a named location by id. Idempotent on the server side. */
    suspend fun deleteNamedLocation(id: String): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val req = Reelvault.DeleteNamedLocationRequest.newBuilder()
                .setId(id)
                .build()
            s.deleteNamedLocation(req).success
        } catch (e: Exception) {
            logger.error("DeleteNamedLocation failed: ${e.message}", e)
            false
        }
    }

    /** Every geotagged video in the catalog — used to populate the global map. */
    suspend fun listVideosWithLocations(): List<com.reelvault.data.models.VideoLocation> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val resp = s.listVideosWithLocations(
                Reelvault.ListVideosWithLocationsRequest.newBuilder().build()
            )
            resp.locationsList.map { proto ->
                com.reelvault.data.models.VideoLocation(
                    id = proto.id,
                    filename = proto.filename,
                    path = proto.path,
                    latitude = proto.latitude,
                    longitude = proto.longitude,
                    altitude = proto.altitude,
                    hasThumbnail = proto.hasThumbnail,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not an error — caller superseded us.
            throw e
        } catch (e: Exception) {
            logger.error("ListVideosWithLocations failed: ${e.message}", e)
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Catalog lifecycle
    // ────────────────────────────────────────────────────────────────────

    /**
     * Ask the daemon to mount the SQLite catalog at [path]. Returns the
     * resulting [CatalogInfo] on success, or `null` if the server rejected
     * the request (e.g. malformed path).
     */
    suspend fun openCatalog(path: String): CatalogInfo? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val req = Reelvault.OpenCatalogRequest.newBuilder().setPath(path).build()
            val info = s.openCatalog(req)
            CatalogInfo(
                path = info.path,
                name = info.name,
                videoCount = info.videoCount,
                openedAtMs = info.openedAtMs
            )
        } catch (e: Exception) {
            logger.error("OpenCatalog failed: ${e.message}", e)
            null
        }
    }

    /** Ask the daemon to close its current catalog. Subsequent RPCs will fail until OpenCatalog is called. */
    suspend fun closeCatalog(): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            s.closeCatalog(Reelvault.CloseCatalogRequest.newBuilder().build()).success
        } catch (e: Exception) {
            logger.error("CloseCatalog failed: ${e.message}", e)
            false
        }
    }

    /** Returns [CatalogInfo.Closed] when no catalog is open or the call fails. */
    suspend fun getCurrentCatalog(): CatalogInfo = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext CatalogInfo.Closed
        try {
            val info = s.getCurrentCatalog(Reelvault.GetCurrentCatalogRequest.newBuilder().build())
            CatalogInfo(
                path = info.path,
                name = info.name,
                videoCount = info.videoCount,
                openedAtMs = info.openedAtMs
            )
        } catch (e: Exception) {
            logger.error("GetCurrentCatalog failed: ${e.message}", e)
            CatalogInfo.Closed
        }
    }

    // --- Lightroom-style user marks ---

    /** Apply a 0..5 star rating to one or more videos in a single round-trip. */
    suspend fun updateVideoRating(videoIds: List<String>, rating: Int): Boolean =
        withContext(Dispatchers.IO) {
            val s = stub ?: return@withContext false
            try {
                val request = Reelvault.UpdateVideoRatingRequest.newBuilder()
                    .addAllVideoIds(videoIds)
                    .setRating(rating)
                    .build()
                val response = s.updateVideoRating(request)
                response.success
            } catch (e: Exception) {
                logger.error("UpdateVideoRating failed: ${e.message}", e)
                false
            }
        }

    /** Apply a colour label to one or more videos. Empty string clears. */
    suspend fun updateVideoColorLabel(videoIds: List<String>, colorLabel: String): Boolean =
        withContext(Dispatchers.IO) {
            val s = stub ?: return@withContext false
            try {
                val request = Reelvault.UpdateVideoColorLabelRequest.newBuilder()
                    .addAllVideoIds(videoIds)
                    .setColorLabel(colorLabel)
                    .build()
                val response = s.updateVideoColorLabel(request)
                response.success
            } catch (e: Exception) {
                logger.error("UpdateVideoColorLabel failed: ${e.message}", e)
                false
            }
        }

    // --- Grid settings (catalog-scoped top-of-card slot config) ---

    /** Fetch the catalog's saved top-of-card slot configuration. Always
     *  returns exactly four entries; the server pads / truncates as needed. */
    suspend fun getGridSettings(): List<String> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext defaultGridTopSlots
        try {
            val response = s.getGridSettings(Reelvault.GetGridSettingsRequest.newBuilder().build())
            response.topSlotsList.toList()
        } catch (e: Exception) {
            logger.error("GetGridSettings failed: ${e.message}", e)
            defaultGridTopSlots
        }
    }

    /** Persist the four-slot configuration. Both clients pick it up the next
     *  time they open the same catalog (or via a follow-up GetGridSettings). */
    suspend fun updateGridSettings(topSlots: List<String>): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val request = Reelvault.GridSettings.newBuilder()
                .addAllTopSlots(topSlots)
                .build()
            val response = s.updateGridSettings(request)
            response.success
        } catch (e: Exception) {
            logger.error("UpdateGridSettings failed: ${e.message}", e)
            false
        }
    }

    companion object {
        private var instance: VideoRepository? = null

        fun getInstance(host: String = "localhost", port: Int = 50051): VideoRepository {
            if (instance == null) {
                instance = VideoRepository(host, port)
            }
            return instance!!
        }
    }
}

data class ScanProgress(
    val status: String,
    val videosFound: Int,
    val videosIndexed: Int,
    val currentFile: String,
    val progressPercent: Double
)
