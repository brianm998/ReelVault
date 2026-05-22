// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.data.repository

import com.videoroom.data.models.*
import com.videoroom.data.models.Collection as VideoCollection
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
import videoroom.Videoroom
import videoroom.VideoRoomGrpcKt

/**
 * Repository for communicating with the VideoRoom Rust backend via gRPC.
 */
class VideoRepository(
    private val host: String = "localhost",
    /** Initial port; mutable so [connect] can target a freshly-spawned daemon. */
    private var port: Int = 50051
) {
    private val logger = LoggerFactory.getLogger(VideoRepository::class.java)

    private var channel: ManagedChannel? = null
    private var stub: VideoRoomGrpcKt.VideoRoomCoroutineStub? = null

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
            stub = VideoRoomGrpcKt.VideoRoomCoroutineStub(channel!!)

            // Verify connection by calling GetStatus
            val statusRequest = Videoroom.GetStatusRequest.newBuilder().build()
            stub!!.getStatus(statusRequest)
            logger.info("Connected to VideoRoom backend at $host:$port")
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
        logger.info("Disconnected from VideoRoom backend")
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        return@withContext channel != null && !channel!!.isShutdown
    }

    private fun protoToVideoSummary(proto: Videoroom.VideoSummary): VideoSummary {
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
            playableNatively = proto.playableNatively
        )
    }

    private fun protoToVideoMetadata(proto: Videoroom.VideoMetadata): VideoMetadata {
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
            lensModel = proto.lensModel,
            gpsLatitude = proto.gpsLatitude,
            gpsLongitude = proto.gpsLongitude,
            gpsAltitude = proto.gpsAltitude,
            tags = proto.tagsList.toList(),
            collections = proto.collectionsList.toList(),
            notes = proto.notes,
            volumeId = proto.volumeId,
            isOnline = proto.isOnline
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
    ): Pair<List<VideoSummary>, Long> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(emptyList(), 0L)
        try {
            val builder = Videoroom.ListVideosRequest.newBuilder()
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
        } catch (e: Exception) {
            logger.error("Failed to list videos: ${e.message}", e)
            Pair(emptyList(), 0L)
        }
    }

    suspend fun getFilterOptions(): com.videoroom.data.models.FilterOptions = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext com.videoroom.data.models.FilterOptions()
        try {
            val response = s.getFilterOptions(Videoroom.GetFilterOptionsRequest.newBuilder().build())
            com.videoroom.data.models.FilterOptions(
                cameras = response.camerasList.toList(),
                lenses = response.lensesList.toList(),
                codecs = response.codecsList.toList(),
                captureYears = response.captureYearsList.toList()
            )
        } catch (e: Exception) {
            logger.error("Failed to get filter options: ${e.message}", e)
            com.videoroom.data.models.FilterOptions()
        }
    }

    suspend fun searchVideos(
        query: String,
        limit: Int = 50,
        offset: Int = 0,
        filterTags: List<String> = emptyList()
    ): Pair<List<VideoSummary>, Long> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(emptyList(), 0L)
        try {
            val request = Videoroom.SearchRequest.newBuilder()
                .setQuery(query)
                .setLimit(limit)
                .setOffset(offset)
                .addAllFilterTags(filterTags)
                .build()

            val response = s.searchVideos(request)
            val videos = response.videosList.map { protoToVideoSummary(it) }
            Pair(videos, response.totalCount)
        } catch (e: Exception) {
            logger.error("Failed to search videos: ${e.message}", e)
            Pair(emptyList(), 0L)
        }
    }

    suspend fun getVideoMetadata(videoId: String): VideoMetadata? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Videoroom.GetMetadataRequest.newBuilder()
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
            val request = Videoroom.GetThumbnailRequest.newBuilder()
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
            val request = Videoroom.AddLocationRequest.newBuilder()
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
            val request = Videoroom.ListLocationsRequest.newBuilder().build()
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
            val request = Videoroom.RemoveLocationRequest.newBuilder()
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
        val request = Videoroom.ScanLibraryRequest.newBuilder()
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
    )

    suspend fun getConfig(): ServerConfig? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val response = s.getConfig(Videoroom.GetConfigRequest.newBuilder().build())
            ServerConfig(
                maxNativePlaybackHeight = response.maxNativePlaybackHeight,
                proxyTargetHeight = response.proxyTargetHeight,
                maxConcurrentJobs = response.maxConcurrentJobs,
                enableAutoTagging = response.enableAutoTagging,
            )
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
    ): Boolean = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext false
        try {
            val builder = Videoroom.UpdateConfigRequest.newBuilder()
            maxNativePlaybackHeight?.let { builder.maxNativePlaybackHeight = it }
            proxyTargetHeight?.let { builder.proxyTargetHeight = it }
            maxConcurrentJobs?.let { builder.maxConcurrentJobs = it }
            enableAutoTagging?.let { builder.enableAutoTagging = it }
            s.updateConfig(builder.build())
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
            val request = Videoroom.ListProxiesRequest.newBuilder()
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
        val request = Videoroom.GenerateProxyRequest.newBuilder()
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
            val request = Videoroom.SetProxyOfRequest.newBuilder()
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

    /** Re-run the auto-detector. Returns (pairsCompared, proxiesMarked). */
    suspend fun detectProxies(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext (0 to 0)
        try {
            val response = s.detectProxies(Videoroom.DetectProxiesRequest.newBuilder().build())
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
        val request = Videoroom.SubscribeCatalogEventsRequest.newBuilder().build()
        return s.subscribeCatalogEvents(request).map { proto ->
            CatalogEvent(
                kind = when (proto.kind) {
                    Videoroom.CatalogEvent.Kind.VIDEO_ADDED -> CatalogEventKind.VideoAdded
                    Videoroom.CatalogEvent.Kind.VIDEO_MODIFIED -> CatalogEventKind.VideoModified
                    Videoroom.CatalogEvent.Kind.VIDEO_REMOVED -> CatalogEventKind.VideoRemoved
                    Videoroom.CatalogEvent.Kind.WATCHER_STARTED -> CatalogEventKind.WatcherStarted
                    Videoroom.CatalogEvent.Kind.WATCHER_DISABLED -> CatalogEventKind.WatcherDisabled
                    Videoroom.CatalogEvent.Kind.SCAN_STARTED -> CatalogEventKind.ScanStarted
                    Videoroom.CatalogEvent.Kind.SCAN_COMPLETED -> CatalogEventKind.ScanCompleted
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
                Videoroom.GetWatchSettingsRequest.newBuilder().build()
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
            val request = Videoroom.WatchSettings.newBuilder()
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
            val request = Videoroom.CreateTagRequest.newBuilder()
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
            val request = Videoroom.ListTagsRequest.newBuilder().build()
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
            val request = Videoroom.TagVideosRequest.newBuilder()
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
            val request = Videoroom.UntagVideosRequest.newBuilder()
                .addAllVideoIds(videoIds)
                .setTagId(tagId)
                .build()
            s.untagVideos(request).success
        } catch (e: Exception) {
            logger.error("Failed to untag videos: ${e.message}", e)
            false
        }
    }

    suspend fun createCollection(name: String, isSmart: Boolean = false): VideoCollection? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Videoroom.CreateCollectionRequest.newBuilder()
                .setName(name)
                .setIsSmart(isSmart)
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
            val request = Videoroom.ListCollectionsRequest.newBuilder().build()
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
            val request = Videoroom.AddToCollectionRequest.newBuilder()
                .setCollectionId(collectionId)
                .addAllVideoIds(videoIds)
                .build()
            s.addToCollection(request).success
        } catch (e: Exception) {
            logger.error("Failed to add to collection: ${e.message}", e)
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
            val request = Videoroom.CreateGroupRequest.newBuilder()
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
            val request = Videoroom.ListGroupMembersRequest.newBuilder()
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
            val request = Videoroom.SetGroupPreferredRequest.newBuilder()
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
            val request = Videoroom.UngroupVideoRequest.newBuilder()
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
            val request = Videoroom.AutoGroupRequest.newBuilder()
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
            val request = Videoroom.UpdateNotesRequest.newBuilder()
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
            val req = Videoroom.UpdateVideoLocationRequest.newBuilder()
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
            val req = Videoroom.UpdateVideoCaptureDateRequest.newBuilder()
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
    suspend fun listNamedLocations(): List<com.videoroom.data.models.NamedLocation> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val resp = s.listNamedLocations(
                Videoroom.ListNamedLocationsRequest.newBuilder().build()
            )
            resp.locationsList.map { p ->
                com.videoroom.data.models.NamedLocation(
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
    ): com.videoroom.data.models.NamedLocation? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val req = Videoroom.UpsertNamedLocationRequest.newBuilder()
                .setId(id)
                .setName(name)
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setRadiusM(radiusMeters)
                .build()
            val resp = s.upsertNamedLocation(req)
            if (!resp.success || !resp.hasLocation()) return@withContext null
            val p = resp.location
            com.videoroom.data.models.NamedLocation(
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
            val req = Videoroom.DeleteNamedLocationRequest.newBuilder()
                .setId(id)
                .build()
            s.deleteNamedLocation(req).success
        } catch (e: Exception) {
            logger.error("DeleteNamedLocation failed: ${e.message}", e)
            false
        }
    }

    /** Every geotagged video in the catalog — used to populate the global map. */
    suspend fun listVideosWithLocations(): List<com.videoroom.data.models.VideoLocation> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext emptyList()
        try {
            val resp = s.listVideosWithLocations(
                Videoroom.ListVideosWithLocationsRequest.newBuilder().build()
            )
            resp.locationsList.map { proto ->
                com.videoroom.data.models.VideoLocation(
                    id = proto.id,
                    filename = proto.filename,
                    path = proto.path,
                    latitude = proto.latitude,
                    longitude = proto.longitude,
                    altitude = proto.altitude,
                    hasThumbnail = proto.hasThumbnail,
                )
            }
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
            val req = Videoroom.OpenCatalogRequest.newBuilder().setPath(path).build()
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
            s.closeCatalog(Videoroom.CloseCatalogRequest.newBuilder().build()).success
        } catch (e: Exception) {
            logger.error("CloseCatalog failed: ${e.message}", e)
            false
        }
    }

    /** Returns [CatalogInfo.Closed] when no catalog is open or the call fails. */
    suspend fun getCurrentCatalog(): CatalogInfo = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext CatalogInfo.Closed
        try {
            val info = s.getCurrentCatalog(Videoroom.GetCurrentCatalogRequest.newBuilder().build())
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
