package com.videoroom.data.repository

import com.videoroom.data.models.*
import com.videoroom.data.models.Collection as VideoCollection
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
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
    private val port: Int = 50051
) {
    private val logger = LoggerFactory.getLogger(VideoRepository::class.java)

    private var channel: ManagedChannel? = null
    private var stub: VideoRoomGrpcKt.VideoRoomCoroutineStub? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
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
            logger.error("Failed to connect to backend: ${e.message}", e)
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
            groupPreferredPath = proto.groupPreferredPath
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
        locationPath: String = ""
    ): Pair<List<VideoSummary>, Long> = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext Pair(emptyList(), 0L)
        try {
            val request = Videoroom.ListVideosRequest.newBuilder()
                .setLimit(limit)
                .setOffset(offset)
                .setSortBy(sortBy)
                .setSortAscending(sortAscending)
                .addAllFilterTags(filterTags)
                .setCollectionId(collectionId ?: "")
                .setLocationPath(locationPath)
                .build()

            val response = s.listVideos(request)
            val videos = response.videosList.map { protoToVideoSummary(it) }
            Pair(videos, response.totalCount)
        } catch (e: Exception) {
            logger.error("Failed to list videos: ${e.message}", e)
            Pair(emptyList(), 0L)
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

    fun scanLibrary(locationPath: String = "", autoGroup: Boolean = true): Flow<ScanProgress> {
        val s = stub ?: return flow { }
        val request = Videoroom.ScanLibraryRequest.newBuilder()
            .setLocationPath(locationPath)
            .setForceFullScan(false)
            .setAutoGroup(autoGroup)
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

    suspend fun createTag(name: String, color: String = ""): Tag? = withContext(Dispatchers.IO) {
        val s = stub ?: return@withContext null
        try {
            val request = Videoroom.CreateTagRequest.newBuilder()
                .setName(name)
                .setColor(color)
                .build()
            val response = s.createTag(request)
            Tag(id = response.id, name = response.name, color = response.color)
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
            response.tagsList.map { Tag(id = it.id, name = it.name, color = it.color) }
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
