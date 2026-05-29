// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import Foundation
import AppKit
import GRPCCore
import GRPCNIOTransportHTTP2

@MainActor
class VideoRepository: ObservableObject {
    static let shared = VideoRepository()

    private var grpcClient: GRPCClient<HTTP2ClientTransport.Posix>?
    private var serviceClient: Videoroom_VideoRoom.Client<HTTP2ClientTransport.Posix>?
    private var runTask: Task<Void, Never>?

    @Published var isConnected = false

    /// Host + port of the currently-active connection, or nil when offline.
    /// Used by the launcher flow to decide whether a reconnect is needed.
    private(set) var currentHost: String = "localhost"
    private(set) var currentPort: Int = 50051

    private init() {}

    // MARK: - Connection lifecycle

    /// Connect to the gRPC daemon at `host`:`port`. If the repository is
    /// already connected to a different host:port, it tears that down first
    /// so reconnecting to a freshly-spawned daemon "just works".
    func connect(host: String = "localhost", port: Int = 50051) async -> Bool {
        // Re-use the existing connection if we're already pointed at the same
        // endpoint.
        if grpcClient != nil && isConnected && currentHost == host && currentPort == port {
            return true
        }
        // Otherwise drop the previous connection.
        if grpcClient != nil {
            await disconnect()
        }

        do {
            let transport = try HTTP2ClientTransport.Posix(
                target: .dns(host: host, port: port),
                transportSecurity: .plaintext
            )
            let client = GRPCClient(transport: transport)
            self.grpcClient = client
            self.serviceClient = Videoroom_VideoRoom.Client(wrapping: client)
            self.currentHost = host
            self.currentPort = port

            runTask = Task {
                try? await client.runConnections()
            }

            // Quick smoke-test that the backend is actually reachable.
            do {
                _ = try await client.getStatusPing()
            } catch {
                // If the smoke test fails, tear it back down so a retry can start fresh.
                NSLog("VideoRoom backend not reachable: \(error)")
                client.beginGracefulShutdown()
                await runTask?.value
                runTask = nil
                grpcClient = nil
                serviceClient = nil
                isConnected = false
                return false
            }

            isConnected = true
            return true
        } catch {
            NSLog("Failed to connect to gRPC: \(error)")
            isConnected = false
            return false
        }
    }

    func disconnect() async {
        grpcClient?.beginGracefulShutdown()
        await runTask?.value
        runTask = nil
        grpcClient = nil
        serviceClient = nil
        isConnected = false
    }

    // MARK: - Catalog lifecycle

    /// Ask the daemon to mount the SQLite catalog at `path`. Returns the
    /// resulting [CatalogInfo] on success, or `nil` if the server rejected
    /// the request.
    func openCatalog(path: String) async -> CatalogInfo? {
        guard let service = serviceClient else { return nil }
        var req = Videoroom_OpenCatalogRequest()
        req.path = path
        do {
            let info = try await service.openCatalog(req)
            return CatalogInfo(
                path: info.path,
                name: info.name,
                videoCount: info.videoCount,
                openedAtMs: info.openedAtMs
            )
        } catch {
            NSLog("OpenCatalog failed: \(error)")
            return nil
        }
    }

    /// Ask the daemon to drop its current catalog. Subsequent RPCs will
    /// fail until `openCatalog` succeeds again.
    @discardableResult
    func closeCatalog() async -> Bool {
        guard let service = serviceClient else { return false }
        do {
            let resp = try await service.closeCatalog(Videoroom_CloseCatalogRequest())
            return resp.success
        } catch {
            NSLog("CloseCatalog failed: \(error)")
            return false
        }
    }

    /// Returns `.closed` when no catalog is open or the call fails.
    func getCurrentCatalog() async -> CatalogInfo {
        guard let service = serviceClient else { return .closed }
        do {
            let info = try await service.getCurrentCatalog(Videoroom_GetCurrentCatalogRequest())
            return CatalogInfo(
                path: info.path,
                name: info.name,
                videoCount: info.videoCount,
                openedAtMs: info.openedAtMs
            )
        } catch {
            NSLog("GetCurrentCatalog failed: \(error)")
            return .closed
        }
    }

    // MARK: - Videos

    func listVideos(
        limit: Int32 = 50,
        offset: Int32 = 0,
        searchQuery: String = "",
        sortBy: String = "indexed_at",
        sortAscending: Bool = false,
        locationPath: String = "",
        filterTagIds: [String] = [],
        filterCamera: String = "",
        filterLens: String = "",
        filterCodec: String = "",
        filterCaptureYear: Int32 = 0,
        /// Proximity filter (latitude, longitude, radius_km). nil = disabled.
        geoFilter: (latitude: Double, longitude: Double, radiusKm: Double)? = nil,
        /// 0 = no rating filter; 1..5 = "show videos with at least this rating".
        filterMinRating: Int32 = 0,
        /// "" = no colour filter; otherwise exact-match the colour label.
        filterColorLabel: String = "",
        /// nil = no collection filter; otherwise restrict to members of this collection.
        collectionId: String? = nil
    ) async throws -> (videos: [VideoSummary], totalCount: Int64) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        if !searchQuery.isEmpty {
            var request = Videoroom_SearchRequest()
            request.query = searchQuery
            request.limit = limit
            request.offset = offset
            request.filterTags = filterTagIds
            let response = try await client.searchVideos(request)
            return (response.videos.map(Self.makeSummary), response.totalCount)
        }

        var request = Videoroom_ListVideosRequest()
        request.limit = limit
        request.offset = offset
        request.sortBy = sortBy
        request.sortAscending = sortAscending
        request.locationPath = locationPath
        request.filterTags = filterTagIds
        request.filterCamera = filterCamera
        request.filterLens = filterLens
        request.filterCodec = filterCodec
        request.filterCaptureYear = filterCaptureYear
        if let geo = geoFilter {
            request.filterByLocation = true
            request.filterLatitude = geo.latitude
            request.filterLongitude = geo.longitude
            request.filterRadiusKm = geo.radiusKm
        }
        request.filterMinRating = filterMinRating
        request.filterColorLabel = filterColorLabel
        if let cid = collectionId { request.collectionID = cid }
        let response = try await client.listVideos(request)
        return (response.videos.map(Self.makeSummary), response.totalCount)
    }

    // MARK: - Geolocation

    /// Set GPS coordinates on `videoId`. Optionally also embed them into the
    /// underlying video file via the daemon's ffmpeg helper.
    @discardableResult
    func updateVideoLocation(
        videoId: String,
        latitude: Double,
        longitude: Double,
        altitude: Double = 0,
        writeToFile: Bool = false
    ) async -> Bool {
        guard let client = serviceClient else { return false }
        var req = Videoroom_UpdateVideoLocationRequest()
        req.videoID = videoId
        req.latitude = latitude
        req.longitude = longitude
        req.altitude = altitude
        req.writeToFile = writeToFile
        do {
            let resp = try await client.updateVideoLocation(req)
            return resp.success
        } catch {
            NSLog("UpdateVideoLocation failed: \(error)")
            return false
        }
    }

    /// Set the capture timestamp (Unix ms, UTC) on `videoId`. Optionally
    /// also embeds `creation_time` into the file via the daemon's ffmpeg
    /// helper.
    @discardableResult
    func updateVideoCaptureDate(
        videoId: String,
        timestampMs: Int64,
        writeToFile: Bool = false
    ) async -> Bool {
        guard let client = serviceClient else { return false }
        var req = Videoroom_UpdateVideoCaptureDateRequest()
        req.videoID = videoId
        req.timestampMs = timestampMs
        req.writeToFile = writeToFile
        do {
            let resp = try await client.updateVideoCaptureDate(req)
            return resp.success
        } catch {
            NSLog("UpdateVideoCaptureDate failed: \(error)")
            return false
        }
    }

    // MARK: - Named locations

    /// List every user-named place in the catalog. Clients cache the result
    /// and use [GridViewModel.nameForLocation] to resolve any (lat, lon)
    /// into a name client-side.
    func listNamedLocations() async -> [NamedLocation] {
        guard let client = serviceClient else { return [] }
        do {
            let resp = try await client.listNamedLocations(
                Videoroom_ListNamedLocationsRequest())
            return resp.locations.map { proto in
                NamedLocation(
                    id: proto.id,
                    name: proto.name,
                    latitude: proto.latitude,
                    longitude: proto.longitude,
                    radiusMeters: proto.radiusM,
                    createdAtMs: proto.createdAtMs,
                    updatedAtMs: proto.updatedAtMs
                )
            }
        } catch {
            NSLog("ListNamedLocations failed: \(error)")
            return []
        }
    }

    /// Insert or update a named location. Pass an empty `id` to create a
    /// new row; otherwise it updates the existing one. Returns the
    /// persisted entity (with assigned id + timestamps) or nil on failure.
    func upsertNamedLocation(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 250
    ) async -> NamedLocation? {
        guard let client = serviceClient else { return nil }
        var req = Videoroom_UpsertNamedLocationRequest()
        req.id = id
        req.name = name
        req.latitude = latitude
        req.longitude = longitude
        req.radiusM = radiusMeters
        do {
            let resp = try await client.upsertNamedLocation(req)
            guard resp.success, resp.hasLocation else { return nil }
            let p = resp.location
            return NamedLocation(
                id: p.id,
                name: p.name,
                latitude: p.latitude,
                longitude: p.longitude,
                radiusMeters: p.radiusM,
                createdAtMs: p.createdAtMs,
                updatedAtMs: p.updatedAtMs
            )
        } catch {
            NSLog("UpsertNamedLocation failed: \(error)")
            return nil
        }
    }

    /// Delete a named location by id. Idempotent — deleting a missing id
    /// is silently a success on the server side.
    @discardableResult
    func deleteNamedLocation(id: String) async -> Bool {
        guard let client = serviceClient else { return false }
        var req = Videoroom_DeleteNamedLocationRequest()
        req.id = id
        do {
            let resp = try await client.deleteNamedLocation(req)
            return resp.success
        } catch {
            NSLog("DeleteNamedLocation failed: \(error)")
            return false
        }
    }

    /// Every geotagged video in the catalog — used to populate the global map.
    func listVideosWithLocations() async -> [VideoLocation] {
        guard let client = serviceClient else { return [] }
        do {
            let resp = try await client.listVideosWithLocations(Videoroom_ListVideosWithLocationsRequest())
            return resp.locations.map { proto in
                VideoLocation(
                    id: proto.id,
                    filename: proto.filename,
                    path: proto.path,
                    latitude: proto.latitude,
                    longitude: proto.longitude,
                    altitude: proto.altitude,
                    hasThumbnail: proto.hasThumbnail_p
                )
            }
        } catch {
            NSLog("ListVideosWithLocations failed: \(error)")
            return []
        }
    }

    func getFilterOptions() async -> FilterOptions {
        guard let client = serviceClient else { return FilterOptions() }
        do {
            let response = try await client.getFilterOptions(Videoroom_GetFilterOptionsRequest())
            return FilterOptions(
                cameras: response.cameras,
                cameraDisplayNames: response.cameraDisplayNames,
                lenses: response.lenses,
                codecs: response.codecs,
                captureYears: response.captureYears
            )
        } catch {
            NSLog("Failed to fetch filter options: \(error)")
            return FilterOptions()
        }
    }

    // MARK: - Camera marketing-name mappings (built-in + user overrides)

    /// Fetch the merged mapping table — built-in entries (sorted) plus
    /// any custom overrides the user has added. Each `CameraNameMapping`
    /// already has its `marketing` field reflecting the active override
    /// (when any).
    func listCameraNameMappings() async throws -> [CameraNameMapping] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listCameraNameMappings(
            Videoroom_ListCameraNameMappingsRequest()
        )
        return response.mappings.map {
            CameraNameMapping(
                internalName: $0.internal,
                marketingName: $0.marketing,
                isBuiltin: $0.isBuiltin,
                isCustom: $0.isCustom
            )
        }
    }

    /// Save a custom override. Pass `marketing == ""` to delete the
    /// override and fall back to the built-in entry (if one exists).
    @discardableResult
    func setCameraNameMapping(internal internalName: String,
                              marketing: String) async throws -> String {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var req = Videoroom_SetCameraNameMappingRequest()
        req.internal = internalName
        req.marketing = marketing
        let response = try await client.setCameraNameMapping(req)
        if !response.success {
            throw RepositoryError.serverError(
                response.error.isEmpty ? "set_camera_name_mapping failed"
                                       : response.error
            )
        }
        return response.message
    }

    // MARK: - Tags / keywords

    func listTags() async throws -> [Tag] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listTags(Videoroom_ListTagsRequest())
        return response.tags.map {
            Tag(id: $0.id, name: $0.name, color: $0.color.isEmpty ? nil : $0.color, videoCount: $0.videoCount)
        }
    }

    /// Create a new tag, or return the existing one if a tag with this name
    /// already exists (the backend's create_tag is idempotent on name).
    func createTag(name: String, color: String = "") async throws -> Tag? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_CreateTagRequest()
        request.name = name
        request.color = color
        let response = try await client.createTag(request)
        return Tag(
            id: response.id,
            name: response.name,
            color: response.color.isEmpty ? nil : response.color,
            videoCount: response.videoCount
        )
    }

    func tagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_TagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.tagVideos(request)
        return response.success
    }

    func untagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UntagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.untagVideos(request)
        return response.success
    }

    // MARK: - Collections

    func listCollections() async throws -> [Collection] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listCollections(Videoroom_ListCollectionsRequest())
        return response.collections.map {
            Collection(id: $0.id, name: $0.name, isSmart: $0.isSmart,
                       filterJson: $0.filterJson, videoCount: $0.videoCount)
        }
    }

    func createCollection(name: String, isSmart: Bool = false, filterJson: String = "") async throws -> Collection? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_CreateCollectionRequest()
        request.name = name
        request.isSmart = isSmart
        request.filterJson = filterJson
        let response = try await client.createCollection(request)
        return Collection(id: response.id, name: response.name, isSmart: response.isSmart,
                          filterJson: filterJson, videoCount: response.videoCount)
    }

    func deleteCollection(id: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_DeleteCollectionRequest()
        request.collectionID = id
        let response = try await client.deleteCollection(request)
        return response.success
    }

    func addToCollection(videoIds: [String], collectionId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_AddToCollectionRequest()
        request.collectionID = collectionId
        request.videoIds = videoIds
        let response = try await client.addToCollection(request)
        return response.success
    }

    func removeFromCollection(videoIds: [String], collectionId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_RemoveFromCollectionRequest()
        request.collectionID = collectionId
        request.videoIds = videoIds
        let response = try await client.removeFromCollection(request)
        return response.success
    }

    func getVideoMetadata(videoId: String) async throws -> VideoMetadata {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_GetMetadataRequest()
        request.videoID = videoId
        let proto = try await client.getMetadata(request)
        return Self.makeMetadata(proto)
    }

    /// Fetch all scrub frames for [videoId] in parallel. Returns an array
    /// of [count] entries; individual entries may be nil if a frame failed.
    func getScrubFrames(videoId: String, count: Int = 10) async -> [NSImage?] {
        let frames = await withTaskGroup(of: (Int, Data?).self) { group in
            for i in 0..<count {
                group.addTask { [self] in
                    let data = try? await self.getThumbnailData(videoId: videoId, size: "scrub_\(i)")
                    return (i, data)
                }
            }
            var result: [(Int, Data?)] = []
            for await pair in group {
                result.append(pair)
            }
            return result
        }
        var images: [NSImage?] = Array(repeating: nil, count: count)
        for (i, data) in frames {
            images[i] = data.flatMap { NSImage(data: $0) }
        }
        return images
    }

    func getThumbnail(videoId: String, size: String = "medium") async throws -> NSImage? {
        let data = try await getThumbnailData(videoId: videoId, size: size)
        return NSImage(data: data)
    }

    private func getThumbnailData(videoId: String, size: String) async throws -> Data {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        var request = Videoroom_GetThumbnailRequest()
        request.videoID = videoId
        request.size = size

        return try await client.getThumbnail(request) { response in
            var data = Data()
            for try await chunk in response.messages {
                data.append(chunk.data)
            }
            return data
        }
    }

    func updateVideoNotes(videoId: String, notes: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UpdateNotesRequest()
        request.videoID = videoId
        request.notes = notes
        let response = try await client.updateVideoNotes(request)
        return response.success
    }

    // MARK: - Library locations

    func listLibraryLocations() async throws -> [LibraryLocation] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listLibraryLocations(Videoroom_ListLocationsRequest())
        return response.locations.map { l in
            LibraryLocation(
                path: l.path,
                recursive: l.recursive,
                enabled: l.enabled,
                videoCount: l.videoCount,
                lastScanned: l.lastScanned
            )
        }
    }

    /// Add a library location. Returns (success, server message).
    func addLibraryLocation(path: String, recursive: Bool = true) async throws -> (Bool, String) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_AddLocationRequest()
        request.path = path
        request.recursive = recursive
        let response = try await client.addLibraryLocation(request)
        return (response.success, response.message)
    }

    /// Remove a library location and all its indexed videos from the catalog.
    /// The video files on disk are not touched. Returns true on success.
    func removeLibraryLocation(path: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_RemoveLocationRequest()
        request.path = path
        let response = try await client.removeLibraryLocation(request)
        return response.success
    }

    /// Streaming scan — yields progress events as the backend works through the library.
    func scanLibrary(
        locationPath: String = "",
        autoGroup: Bool = true,
        filenameDateFormat: String = "",
        filenameDatePosition: String = ""
    ) -> AsyncThrowingStream<ScanProgress, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                guard let client = serviceClient else {
                    continuation.finish(throwing: RepositoryError.notConnected)
                    return
                }
                var request = Videoroom_ScanLibraryRequest()
                request.locationPath = locationPath
                request.autoGroup = autoGroup
                request.filenameDateFormat = filenameDateFormat
                request.filenameDatePosition = filenameDatePosition

                do {
                    try await client.scanLibrary(request) { response in
                        for try await proto in response.messages {
                            let progress = ScanProgress(
                                status: proto.status,
                                videosFound: Int(proto.videosFound),
                                videosIndexed: Int(proto.videosIndexed),
                                currentFile: proto.currentFile,
                                progressPercent: proto.progressPercent
                            )
                            continuation.yield(progress)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Server config

    /// Subset of `ConfigResponse` the macOS client currently surfaces.
    /// Extended as new fields are exposed.
    struct ServerConfig: Equatable {
        let maxNativePlaybackHeight: Int
        let proxyTargetHeight: Int
        let maxConcurrentJobs: Int
        let enableAutoTagging: Bool
    }

    /// Read the daemon's current config. Returns nil on transport
    /// failure so the Preferences UI never has to handle a partial
    /// state — it shows a spinner instead and re-tries on save.
    func getConfig() async -> ServerConfig? {
        guard let client = serviceClient else { return nil }
        do {
            let response = try await client.getConfig(Videoroom_GetConfigRequest())
            return ServerConfig(
                maxNativePlaybackHeight: Int(response.maxNativePlaybackHeight),
                proxyTargetHeight: Int(response.proxyTargetHeight),
                maxConcurrentJobs: Int(response.maxConcurrentJobs),
                enableAutoTagging: response.enableAutoTagging,
            )
        } catch {
            NSLog("getConfig failed: \(error)")
            return nil
        }
    }

    /// Push updated config to the daemon. All parameters are optional;
    /// unspecified values are sent as 0 / false which the server
    /// preserves only when the corresponding clamp doesn't reject them.
    /// (The server treats negative / out-of-range values as "use the
    /// existing config".)
    @discardableResult
    func updateConfig(
        maxNativePlaybackHeight: Int? = nil,
        proxyTargetHeight: Int? = nil,
        maxConcurrentJobs: Int? = nil,
        enableAutoTagging: Bool? = nil
    ) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Videoroom_UpdateConfigRequest()
        if let h = maxNativePlaybackHeight { request.maxNativePlaybackHeight = Int32(h) }
        if let h = proxyTargetHeight { request.proxyTargetHeight = Int32(h) }
        if let n = maxConcurrentJobs { request.maxConcurrentJobs = Int32(n) }
        if let b = enableAutoTagging { request.enableAutoTagging = b }
        do {
            _ = try await client.updateConfig(request)
            return true
        } catch {
            NSLog("updateConfig failed: \(error)")
            return false
        }
    }

    // MARK: - Proxies

    /// One proxy of a parent video. Bundles enough info for the detail
    /// panel's proxy sub-list (filename, path, resolution, size) plus
    /// the auto-detection metadata so the UI can show a "🤖
    /// auto-detected" affordance.
    struct ProxyInfo: Identifiable, Hashable {
        let id: String
        let filename: String
        let path: String
        let sizeBytes: Int64
        let width: Int
        let height: Int
        let confidence: Double
        let autoDetected: Bool
    }

    /// Break a single master ↔ proxy junction-table edge without
    /// disturbing the row's other proxy relationships. Used by the
    /// inspector's per-row "break" button.
    @discardableResult
    func removeProxyLink(masterId: String, proxyId: String) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Videoroom_RemoveProxyLinkRequest()
        request.masterID = masterId
        request.proxyID = proxyId
        do {
            _ = try await client.removeProxyLink(request)
            return true
        } catch {
            NSLog("removeProxyLink failed: \(error)")
            return false
        }
    }

    /// List every lower-resolution proxy of `videoId`. Sorted descending
    /// by pixel count so the highest-resolution proxy is first.
    func listProxies(videoId: String) async throws -> [ProxyInfo] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_ListProxiesRequest()
        request.videoID = videoId
        let response = try await client.listProxies(request)
        return response.proxies.map {
            ProxyInfo(
                id: $0.id,
                filename: $0.filename,
                path: $0.path,
                sizeBytes: $0.sizeBytes,
                width: Int($0.width),
                height: Int($0.height),
                confidence: $0.confidence,
                autoDetected: $0.autoDetected
            )
        }
    }

    /// Kick off a proxy-generation job. `targetHeight` 0 means "use the
    /// server's configured default" (typically 720 px).
    ///
    /// Yields one `ProxyGenerationProgress`-shaped `(status, percent,
    /// message, proxyVideoId)` tuple per server event. The final event
    /// has status="complete" and `proxyVideoId` set to the newly-created
    /// video row.
    func generateProxy(
        videoId: String,
        targetHeight: Int = 0,
        outputPath: String = ""
    ) -> AsyncThrowingStream<(status: String, percent: Double, message: String, proxyVideoId: String), Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                guard let client = serviceClient else {
                    continuation.finish(throwing: RepositoryError.notConnected)
                    return
                }
                var request = Videoroom_GenerateProxyRequest()
                request.videoID = videoId
                request.targetHeight = Int32(targetHeight)
                request.outputPath = outputPath
                do {
                    try await client.generateProxy(request) { response in
                        for try await proto in response.messages {
                            continuation.yield((
                                status: proto.status,
                                percent: proto.progressPercent,
                                message: proto.message,
                                proxyVideoId: proto.proxyVideoID
                            ))
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Manually mark `proxyId` as a proxy for `originalId`. Pass an
    /// empty `originalId` to un-mark.
    @discardableResult
    func setProxyOf(proxyId: String, originalId: String) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Videoroom_SetProxyOfRequest()
        request.proxyID = proxyId
        request.originalID = originalId
        do {
            _ = try await client.setProxyOf(request)
            return true
        } catch {
            NSLog("setProxyOf failed: \(error)")
            return false
        }
    }

    /// Re-run the auto-detector. Useful from the menu after a manual
    /// scan or after thumbnails are regenerated; idempotent.
    @discardableResult
    func detectProxies() async -> (pairsCompared: Int, proxiesMarked: Int) {
        guard let client = serviceClient else { return (0, 0) }
        do {
            let response = try await client.detectProxies(Videoroom_DetectProxiesRequest())
            return (Int(response.pairsCompared), Int(response.proxiesMarked))
        } catch {
            NSLog("detectProxies failed: \(error)")
            return (0, 0)
        }
    }

    // MARK: - Real-time catalog events

    /// Open a server-streamed `SubscribeCatalogEvents` and translate each
    /// proto `CatalogEvent` into a Swift `CatalogEvent` for the view-model
    /// layer. The first emission is always a `.watcherStarted` or
    /// `.watcherDisabled` greeting so the UI can paint the live-updates
    /// indicator without an extra round-trip.
    ///
    /// Returns an `AsyncStream` that finishes (cleanly) when the task is
    /// cancelled or the server tears down the stream. Callers should
    /// own a `Task` that loops over it and is cancelled before the
    /// repository disconnects.
    func subscribeCatalogEvents() -> AsyncStream<CatalogEvent> {
        AsyncStream { continuation in
            let task = Task {
                guard let client = serviceClient else {
                    continuation.finish()
                    return
                }
                let request = Videoroom_SubscribeCatalogEventsRequest()
                do {
                    try await client.subscribeCatalogEvents(request) { response in
                        for try await proto in response.messages {
                            let kind: CatalogEventKind
                            switch proto.kind {
                            case .videoAdded: kind = .videoAdded
                            case .videoModified: kind = .videoModified
                            case .videoRemoved: kind = .videoRemoved
                            case .watcherStarted: kind = .watcherStarted
                            case .watcherDisabled: kind = .watcherDisabled
                            case .scanStarted: kind = .scanStarted
                            case .scanCompleted: kind = .scanCompleted
                            default: kind = .unknown
                            }
                            continuation.yield(CatalogEvent(
                                kind: kind,
                                videoId: proto.videoID,
                                path: proto.path,
                                atMs: proto.atMs,
                                message: proto.message
                            ))
                        }
                    }
                } catch {
                    // Stream ended (server closed, network blip, or task
                    // cancellation). The view-model treats any finish as
                    // "live updates dropped" and may attempt a reconnect.
                    NSLog("subscribeCatalogEvents stream ended: \(error)")
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Read the daemon's current watcher knobs. Returns the protocol-default
    /// (`WatchSettings.default`) on any error so the Preferences UI never
    /// has to handle a missing-value case.
    func getWatchSettings() async -> WatchSettings {
        guard let client = serviceClient else { return .default }
        do {
            let response = try await client.getWatchSettings(Videoroom_GetWatchSettingsRequest())
            return WatchSettings(
                enabled: response.enabled,
                writeSettleMs: response.writeSettleMs,
                pollIntervalMs: response.pollIntervalMs
            )
        } catch {
            NSLog("getWatchSettings failed: \(error)")
            return .default
        }
    }

    /// Push new watcher settings to the daemon. The server clamps values
    /// to sensible ranges; the UI clamps too but this is the source of
    /// truth. Side effect on the daemon: the watcher is restarted with
    /// the new values.
    @discardableResult
    func updateWatchSettings(_ settings: WatchSettings) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Videoroom_WatchSettings()
        request.enabled = settings.enabled
        request.writeSettleMs = settings.writeSettleMs
        request.pollIntervalMs = settings.pollIntervalMs
        do {
            _ = try await client.updateWatchSettings(request)
            return true
        } catch {
            NSLog("updateWatchSettings failed: \(error)")
            return false
        }
    }

    // MARK: - Groups (Lightroom-style stacks)

    func listGroupMembers(groupId: String) async throws -> (members: [VideoSummary], preferredId: String) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_ListGroupMembersRequest()
        request.groupID = groupId
        let response = try await client.listGroupMembers(request)
        return (response.members.map(Self.makeSummary), response.preferredVideoID)
    }

    func createGroup(videoIds: [String], name: String = "", preferredVideoId: String = "") async throws -> GroupInfo? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_CreateGroupRequest()
        request.videoIds = videoIds
        request.name = name
        request.preferredVideoID = preferredVideoId
        let response = try await client.createGroup(request)
        return GroupInfo(
            id: response.id,
            name: response.name,
            size: Int(response.size),
            preferredVideoId: response.preferredVideoID
        )
    }

    func ungroupVideo(videoId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UngroupVideoRequest()
        request.videoID = videoId
        let response = try await client.ungroupVideo(request)
        return response.success
    }

    func setGroupPreferred(groupId: String, videoId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_SetGroupPreferredRequest()
        request.groupID = groupId
        request.videoID = videoId
        let response = try await client.setGroupPreferred(request)
        return response.success
    }

    // MARK: - Proto → Model

    private static func makeSummary(_ p: Videoroom_VideoSummary) -> VideoSummary {
        VideoSummary(
            id: p.id,
            filename: p.filename,
            path: p.path,
            width: Int(p.width),
            height: Int(p.height),
            durationMs: Int(p.durationMs),
            fps: p.fps,
            codecVideo: p.codecVideo,
            codecAudio: p.codecAudio,
            bitrateKbps: 0,
            sizeBytes: Int(p.sizeBytes),
            indexedAt: p.indexedAt,
            creationDate: p.creationDate,
            tags: p.tags,
            hasThumbnail: p.hasThumbnail_p,
            groupId: p.groupID,
            groupSize: Int(p.groupSize),
            groupPreferredId: p.groupPreferredID,
            groupPreferredPath: p.groupPreferredPath,
            proxyCount: Int(p.proxyCount),
            proxyOf: p.proxyOf,
            playableNatively: p.playableNatively,
            rating: Int(p.rating),
            colorLabel: p.colorLabel,
            cameraModel: p.cameraModel,
            cameraDisplayName: p.cameraDisplayName.isEmpty ? p.cameraModel : p.cameraDisplayName,
            gpsLatitude: p.gpsLatitude,
            gpsLongitude: p.gpsLongitude,
            lensModel: p.lensModel,
            iso: Int(p.iso),
            aperture: p.aperture,
            exposureTimeS: p.exposureTimeS,
            focalLengthMm: p.focalLengthMm
        )
    }

    private static func makeMetadata(_ p: Videoroom_VideoMetadata) -> VideoMetadata {
        VideoMetadata(
            id: p.id,
            filename: p.filename,
            path: p.path,
            width: Int(p.width),
            height: Int(p.height),
            durationMs: Int(p.durationMs),
            fps: p.fps,
            codecVideo: p.codecVideo,
            codecAudio: p.codecAudio,
            bitrateKbps: Int(p.bitrate / 1000),
            sizeBytes: Int(p.sizeBytes),
            colorSpace: p.colorSpace,
            hdr: p.hdr,
            audioChannels: Int(p.audioChannels),
            audioSampleRate: Int(p.audioSampleRate),
            creationDate: p.creationDate,
            cameraModel: p.cameraModel,
            cameraDisplayName: p.cameraDisplayName.isEmpty ? p.cameraModel : p.cameraDisplayName,
            lensModel: p.lensModel,
            gpsLat: p.gpsLatitude,
            gpsLon: p.gpsLongitude,
            gpsAltitude: p.gpsAltitude,
            notes: p.notes,
            tags: p.tags,
            collections: p.collections,
            rating: Int(p.rating),
            colorLabel: p.colorLabel,
            iso: Int(p.iso),
            aperture: p.aperture,
            exposureTimeS: p.exposureTimeS,
            focalLengthMm: p.focalLengthMm,
            exposureMode: p.exposureMode,
            exposureProgram: p.exposureProgram,
            whiteBalance: p.whiteBalance
        )
    }

    // MARK: - User marks (rating + color label)

    /// Apply a 0..5 star rating to one or more videos in a single round-trip.
    @discardableResult
    func updateVideoRating(videoIds: [String], rating: Int) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UpdateVideoRatingRequest()
        request.videoIds = videoIds
        request.rating = Int32(rating)
        let response = try await client.updateVideoRating(request)
        return response.success
    }

    /// Apply a colour label to one or more videos. Pass empty string to clear.
    @discardableResult
    func updateVideoColorLabel(videoIds: [String], colorLabel: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_UpdateVideoColorLabelRequest()
        request.videoIds = videoIds
        request.colorLabel = colorLabel
        let response = try await client.updateVideoColorLabel(request)
        return response.success
    }

    // MARK: - Grid settings (per-catalog)

    /// Fetch the catalog's saved top-of-card slot configuration. Always
    /// returns exactly four entries; the server pads / truncates as needed.
    func getGridSettings() async throws -> [String] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.getGridSettings(Videoroom_GetGridSettingsRequest())
        return response.topSlots
    }

    /// Persist the four-slot configuration. Both clients pick it up the next
    /// time they open the same catalog (or via a follow-up GetGridSettings).
    @discardableResult
    func updateGridSettings(topSlots: [String]) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Videoroom_GridSettings()
        request.topSlots = topSlots
        let response = try await client.updateGridSettings(request)
        return response.success
    }
}

private extension GRPCClient {
    /// Tiny no-op call to verify the backend is reachable during connect().
    /// Uses `GetStatus` specifically because that RPC is guaranteed to
    /// succeed even when the daemon has no catalog mounted yet — which is
    /// the normal state right after `ServerLauncher` spawns it with
    /// `--no-catalog`. Any RPC that touches SQL (e.g. `listLibraryLocations`)
    /// would error here and the launcher would wrongly report the daemon
    /// as unreachable.
    func getStatusPing() async throws {
        let service = Videoroom_VideoRoom.Client(wrapping: self)
        _ = try await service.getStatus(Videoroom_GetStatusRequest())
    }
}

enum RepositoryError: LocalizedError {
    case notConnected
    /// The daemon accepted the RPC but responded with `success == false`.
    /// Carries the human-readable error message the daemon returned so
    /// the UI can surface it in a toast or alert.
    case serverError(String)

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to VideoRoom backend"
        case .serverError(let message):
            return message
        }
    }
}
