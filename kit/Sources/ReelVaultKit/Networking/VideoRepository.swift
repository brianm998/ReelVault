// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2

extension AttributeFilterState {
    /// The proto enum value for this tri-state attribute toggle.
    public var proto: Reelvault_AttributeFilter {
        switch self {
        case .any: return .any
        case .yes: return .yes
        case .no:  return .no
        }
    }
}

@MainActor
public class VideoRepository: ObservableObject {
    public static let shared = VideoRepository()

    private var grpcClient: GRPCClient<HTTP2ClientTransport.Posix>?
    private var serviceClient: Reelvault_ReelVault.Client<HTTP2ClientTransport.Posix>?
    private var runTask: Task<Void, Never>?

    @Published public var isConnected = false

    /// Host + port of the currently-active connection, or nil when offline.
    /// Used by the launcher flow to decide whether a reconnect is needed.
    public private(set) var currentHost: String = "localhost"
    public private(set) var currentPort: Int = 50051

    private init() {}

    // MARK: - Connection lifecycle

    /// Connect to the gRPC daemon at `host`:`port` over plaintext (loopback).
    /// Thin convenience wrapper preserved for the macOS app's existing call
    /// sites; delegates to `connect(to:)`.
    public func connect(host: String = "localhost", port: Int = 50051) async -> Bool {
        await connect(to: ServerEndpoint(host: host, port: port, security: .plaintext))
    }

    /// Connect to a resolved daemon endpoint. The macOS app always uses
    /// `.plaintext` (it spawns the daemon on loopback); the iOS app uses
    /// `.pinnedTLS` against a daemon whose self-signed certificate fingerprint
    /// was advertised over mDNS. If already connected to the same host:port the
    /// existing connection is reused.
    public func connect(to endpoint: ServerEndpoint) async -> Bool {
        let host = endpoint.host
        let port = endpoint.port
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
            let transport: HTTP2ClientTransport.Posix
            switch endpoint.security {
            case .plaintext:
                transport = try HTTP2ClientTransport.Posix(
                    target: .dns(host: host, port: port),
                    transportSecurity: .plaintext
                )
            case .pinnedTLS(let fingerprintHex):
                // Fetch the server's self-signed cert (TOFU), verify its
                // fingerprint, and pin it as the sole trust root.
                guard let der = await PinnedTLS.fetchServerCertificate(
                    host: host, port: port, expectedFingerprintHex: fingerprintHex
                ) else {
                    NSLog("ReelVault: could not fetch/verify server certificate for pinning")
                    isConnected = false
                    return false
                }
                transport = try HTTP2ClientTransport.Posix(
                    target: .dns(host: host, port: port),
                    transportSecurity: PinnedTLS.clientSecurity(pinnedCertDER: der)
                )
            }
            let client: GRPCClient<HTTP2ClientTransport.Posix>
            if let token = endpoint.bearerToken, !token.isEmpty {
                client = GRPCClient(
                    transport: transport,
                    interceptors: [BearerTokenInterceptor(token: token)]
                )
            } else {
                client = GRPCClient(transport: transport)
            }
            self.grpcClient = client
            self.serviceClient = Reelvault_ReelVault.Client(wrapping: client)
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
                NSLog("ReelVault backend not reachable: \(error)")
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

    public func disconnect() async {
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
    public func openCatalog(path: String) async -> CatalogInfo? {
        guard let service = serviceClient else { return nil }
        var req = Reelvault_OpenCatalogRequest()
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
    public func closeCatalog() async -> Bool {
        guard let service = serviceClient else { return false }
        do {
            let resp = try await service.closeCatalog(Reelvault_CloseCatalogRequest())
            return resp.success
        } catch {
            NSLog("CloseCatalog failed: \(error)")
            return false
        }
    }

    /// Returns `.closed` when no catalog is open or the call fails.
    public func getCurrentCatalog() async -> CatalogInfo {
        guard let service = serviceClient else { return .closed }
        do {
            let info = try await service.getCurrentCatalog(Reelvault_GetCurrentCatalogRequest())
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

    // MARK: - Pairing

    /// A freshly-minted one-time pairing code for authorizing a new remote
    /// device (the iOS app). The operator reads it off this client and types it
    /// on the device, which redeems it at the media server's `POST /pair`.
    public struct PairingCode: Sendable, Equatable {
        public let code: String
        /// Unix epoch milliseconds at which the code stops being accepted.
        public let expiresAtMs: Int64
        public init(code: String, expiresAtMs: Int64) {
            self.code = code
            self.expiresAtMs = expiresAtMs
        }
    }

    /// Ask the connected daemon to mint a one-time pairing code. Intended for a
    /// loopback/desktop client — the daemon surfaces the same code to the
    /// pairing endpoint a new device redeems against. Returns nil if not
    /// connected or the call fails.
    public func startPairing() async -> PairingCode? {
        guard let service = serviceClient else { return nil }
        do {
            let resp = try await service.startPairing(Reelvault_StartPairingRequest())
            return PairingCode(code: resp.code, expiresAtMs: resp.expiresAtMs)
        } catch {
            NSLog("StartPairing failed: \(error)")
            return nil
        }
    }

    // MARK: - Videos

    public func listVideos(
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
        /// Generic metadata filters from the Library Filter's metadata columns.
        /// Camera/lens/codec/year now travel here rather than as scalar args.
        metadataFilters: [(key: String, value: String)] = [],
        /// nil = no collection filter; otherwise restrict to members of this collection.
        collectionId: String? = nil,
        /// Tri-state presence filters from the Library Filter's attribute mode.
        hasLocation: AttributeFilterState = .any,
        hasKeywords: AttributeFilterState = .any,
        hasProxies: AttributeFilterState = .any,
        fullResolution: AttributeFilterState = .any
    ) async throws -> (videos: [VideoSummary], totalCount: Int64) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        // The text query is folded into ListVideos so it composes with every
        // other filter (the separate SearchVideos RPC is no longer used here).
        var request = Reelvault_ListVideosRequest()
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
        request.searchQuery = searchQuery
        request.metadataFilters = metadataFilters.map {
            var m = Reelvault_MetadataFilter()
            m.key = $0.key
            m.value = $0.value
            return m
        }
        request.filterHasLocation = hasLocation.proto
        request.filterHasKeywords = hasKeywords.proto
        request.filterHasProxies = hasProxies.proto
        request.filterFullResolution = fullResolution.proto
        if let cid = collectionId { request.collectionID = cid }
        let response = try await client.listVideos(request)
        return (response.videos.map(Self.makeSummary), response.totalCount)
    }

    // MARK: - Geolocation

    /// Set GPS coordinates on `videoId`. Optionally also embed them into the
    /// underlying video file via the daemon's ffmpeg helper.
    @discardableResult
    public func updateVideoLocation(
        videoId: String,
        latitude: Double,
        longitude: Double,
        altitude: Double = 0,
        writeToFile: Bool = false
    ) async -> Bool {
        guard let client = serviceClient else { return false }
        var req = Reelvault_UpdateVideoLocationRequest()
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
    public func updateVideoCaptureDate(
        videoId: String,
        timestampMs: Int64,
        writeToFile: Bool = false
    ) async -> Bool {
        guard let client = serviceClient else { return false }
        var req = Reelvault_UpdateVideoCaptureDateRequest()
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
    public func listNamedLocations() async -> [NamedLocation] {
        guard let client = serviceClient else { return [] }
        do {
            let resp = try await client.listNamedLocations(
                Reelvault_ListNamedLocationsRequest())
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
    public func upsertNamedLocation(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 250
    ) async -> NamedLocation? {
        guard let client = serviceClient else { return nil }
        var req = Reelvault_UpsertNamedLocationRequest()
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
    public func deleteNamedLocation(id: String) async -> Bool {
        guard let client = serviceClient else { return false }
        var req = Reelvault_DeleteNamedLocationRequest()
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
    public func listVideosWithLocations() async -> [VideoLocation] {
        guard let client = serviceClient else { return [] }
        do {
            let resp = try await client.listVideosWithLocations(Reelvault_ListVideosWithLocationsRequest())
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

    public func getFilterOptions() async -> FilterOptions {
        guard let client = serviceClient else { return FilterOptions() }
        do {
            let response = try await client.getFilterOptions(Reelvault_GetFilterOptionsRequest())
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

    /// Faceted metadata values for the Library Filter's "metadata" mode. Given
    /// the upstream filters + the ordered metadata columns, the daemon returns
    /// the available values for each column (cascaded left→right) plus the set
    /// of keys that have data in the current filtered set. Returns an empty
    /// result on error so the UI degrades to "no values" rather than throwing.
    public func getMetadataFacets(
        locationPath: String = "",
        filterTagIds: [String] = [],
        collectionId: String? = nil,
        geoFilter: (latitude: Double, longitude: Double, radiusKm: Double)? = nil,
        filterMinRating: Int32 = 0,
        filterColorLabel: String = "",
        searchQuery: String = "",
        columns: [(key: String, value: String)] = [],
        hasLocation: AttributeFilterState = .any,
        hasKeywords: AttributeFilterState = .any,
        hasProxies: AttributeFilterState = .any,
        fullResolution: AttributeFilterState = .any
    ) async -> MetadataFacetsResult {
        guard let client = serviceClient else { return MetadataFacetsResult() }
        do {
            var request = Reelvault_MetadataFacetsRequest()
            request.locationPath = locationPath
            request.filterTags = filterTagIds
            if let cid = collectionId { request.collectionID = cid }
            if let geo = geoFilter {
                request.filterByLocation = true
                request.filterLatitude = geo.latitude
                request.filterLongitude = geo.longitude
                request.filterRadiusKm = geo.radiusKm
            }
            request.filterMinRating = filterMinRating
            request.filterColorLabel = filterColorLabel
            request.searchQuery = searchQuery
            request.filterHasLocation = hasLocation.proto
            request.filterHasKeywords = hasKeywords.proto
            request.filterHasProxies = hasProxies.proto
            request.filterFullResolution = fullResolution.proto
            request.columns = columns.map {
                var m = Reelvault_MetadataFilter()
                m.key = $0.key
                m.value = $0.value
                return m
            }
            let response = try await client.getMetadataFacets(request)
            return MetadataFacetsResult(
                columns: response.columns.map { c in
                    MetadataFacetColumn(
                        key: c.key,
                        displayName: c.displayName,
                        isNumeric: c.isNumeric,
                        values: c.values.map { FacetValue(token: $0.token, display: $0.display, count: $0.count) }
                    )
                },
                availableKeys: response.availableKeys.map {
                    MetadataKeyInfo(key: $0.key, displayName: $0.displayName, isNumeric: $0.isNumeric)
                }
            )
        } catch {
            NSLog("Failed to fetch metadata facets: \(error)")
            return MetadataFacetsResult()
        }
    }

    // MARK: - Camera marketing-name mappings (built-in + user overrides)

    /// Fetch the merged mapping table — built-in entries (sorted) plus
    /// any custom overrides the user has added. Each `CameraNameMapping`
    /// already has its `marketing` field reflecting the active override
    /// (when any).
    public func listCameraNameMappings() async throws -> [CameraNameMapping] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listCameraNameMappings(
            Reelvault_ListCameraNameMappingsRequest()
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
    public func setCameraNameMapping(internal internalName: String,
                              marketing: String) async throws -> String {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var req = Reelvault_SetCameraNameMappingRequest()
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

    // MARK: - Lens display-name aliases (user overrides only)

    /// Fetch the lens mapping table: one row per distinct lens in the
    /// catalog, plus any custom-only overrides whose lens no longer
    /// appears. Each `LensNameMapping.alias` already reflects the active
    /// override (when any).
    public func listLensNameMappings() async throws -> [LensNameMapping] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listLensNameMappings(
            Reelvault_ListLensNameMappingsRequest()
        )
        return response.mappings.map {
            LensNameMapping(
                rawName: $0.raw,
                alias: $0.alias,
                isCustom: $0.isCustom,
                inCatalog: $0.inCatalog
            )
        }
    }

    /// Save a custom lens alias. Pass `alias == ""` to delete the
    /// override and fall back to displaying the raw lens string.
    @discardableResult
    public func setLensNameMapping(raw: String, alias: String) async throws -> String {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var req = Reelvault_SetLensNameMappingRequest()
        req.raw = raw
        req.alias = alias
        let response = try await client.setLensNameMapping(req)
        if !response.success {
            throw RepositoryError.serverError(
                response.error.isEmpty ? "set_lens_name_mapping failed"
                                       : response.error
            )
        }
        return response.message
    }

    // MARK: - Tags / keywords

    public func listTags() async throws -> [Tag] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listTags(Reelvault_ListTagsRequest())
        return response.tags.map {
            Tag(id: $0.id, name: $0.name, color: $0.color.isEmpty ? nil : $0.color, videoCount: $0.videoCount)
        }
    }

    /// Create a new tag, or return the existing one if a tag with this name
    /// already exists (the backend's create_tag is idempotent on name).
    public func createTag(name: String, color: String = "") async throws -> Tag? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_CreateTagRequest()
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

    public func tagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_TagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.tagVideos(request)
        return response.success
    }

    public func untagVideos(videoIds: [String], tagId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_UntagVideosRequest()
        request.videoIds = videoIds
        request.tagID = tagId
        let response = try await client.untagVideos(request)
        return response.success
    }

    // MARK: - Collections

    public func listCollections() async throws -> [Collection] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listCollections(Reelvault_ListCollectionsRequest())
        return response.collections.map {
            Collection(id: $0.id, name: $0.name, isSmart: $0.isSmart,
                       filterJson: $0.filterJson, videoCount: $0.videoCount)
        }
    }

    public func createCollection(name: String, isSmart: Bool = false, filterJson: String = "") async throws -> Collection? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_CreateCollectionRequest()
        request.name = name
        request.isSmart = isSmart
        request.filterJson = filterJson
        let response = try await client.createCollection(request)
        return Collection(id: response.id, name: response.name, isSmart: response.isSmart,
                          filterJson: filterJson, videoCount: response.videoCount)
    }

    public func deleteCollection(id: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_DeleteCollectionRequest()
        request.collectionID = id
        let response = try await client.deleteCollection(request)
        return response.success
    }

    public func addToCollection(videoIds: [String], collectionId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_AddToCollectionRequest()
        request.collectionID = collectionId
        request.videoIds = videoIds
        let response = try await client.addToCollection(request)
        return response.success
    }

    public func removeFromCollection(videoIds: [String], collectionId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_RemoveFromCollectionRequest()
        request.collectionID = collectionId
        request.videoIds = videoIds
        let response = try await client.removeFromCollection(request)
        return response.success
    }

    public func getVideoMetadata(videoId: String) async throws -> VideoMetadata {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_GetMetadataRequest()
        request.videoID = videoId
        let proto = try await client.getMetadata(request)
        return Self.makeMetadata(proto)
    }

    /// Fetch all scrub frames for [videoId] in parallel. Returns an array
    /// of [count] entries; individual entries may be nil if a frame failed.
    public func getScrubFrames(videoId: String, count: Int = 10) async -> [PlatformImage?] {
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
        var images: [PlatformImage?] = Array(repeating: nil, count: count)
        for (i, data) in frames {
            images[i] = data.flatMap { PlatformImage.fromData($0) }
        }
        return images
    }

    /// Returns nil only when the daemon reports NOT_FOUND — a definitive miss
    /// the caller should not retry. Transient failures (daemon busy,
    /// connection hiccup) rethrow, so callers that care can retry quickly.
    public func getThumbnail(videoId: String, size: String = "medium") async throws -> PlatformImage? {
        do {
            let data = try await getThumbnailData(videoId: videoId, size: size)
            return PlatformImage.fromData(data)
        } catch let error as RPCError where error.code == .notFound {
            return nil
        }
    }

    /// Fetch a single thumbnail at a higher resolution (`maxWidth` px, never
    /// upscaled past the source) — used by the detail view to upgrade scrub
    /// frames to the render size. Returns nil on any failure.
    public func getThumbnailHiRes(videoId: String, size: String, maxWidth: Int32) async -> PlatformImage? {
        guard let data = try? await getThumbnailData(videoId: videoId, size: size, maxWidth: maxWidth) else {
            return nil
        }
        return PlatformImage.fromData(data)
    }

    /// Fetch the audio loudness-over-time series for `videoId` (the detail
    /// view's volume graph). It travels over the thumbnail RPC under the special
    /// "audio_loudness" size token; the daemon returns the series as
    /// little-endian f32 samples, each normalized to 0...1. An empty array means
    /// the video has no audio track (or the series was unavailable).
    public func getAudioLoudness(videoId: String) async -> [Float] {
        guard let data = try? await getThumbnailData(videoId: videoId, size: "audio_loudness"),
              !data.isEmpty else { return [] }
        let count = data.count / 4
        // macOS hosts are little-endian, matching the daemon's f32 encoding.
        return data.withUnsafeBytes { raw in
            (0..<count).map { raw.loadUnaligned(fromByteOffset: $0 * 4, as: Float.self) }
        }
    }

    private func getThumbnailData(videoId: String, size: String, maxWidth: Int32 = 0) async throws -> Data {
        guard let client = serviceClient else { throw RepositoryError.notConnected }

        var request = Reelvault_GetThumbnailRequest()
        request.videoID = videoId
        request.size = size
        request.maxWidth = maxWidth

        return try await client.getThumbnail(request) { response in
            var data = Data()
            for try await chunk in response.messages {
                data.append(chunk.data)
            }
            return data
        }
    }

    public func updateVideoNotes(videoId: String, notes: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_UpdateNotesRequest()
        request.videoID = videoId
        request.notes = notes
        let response = try await client.updateVideoNotes(request)
        return response.success
    }

    // MARK: - Library locations

    public func listLibraryLocations() async throws -> [LibraryLocation] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.listLibraryLocations(Reelvault_ListLocationsRequest())
        return response.locations.map { l in
            LibraryLocation(
                path: l.path,
                recursive: l.recursive,
                enabled: l.enabled,
                videoCount: l.videoCount,
                lastScanned: l.lastScanned,
                // protoc sanitizes the `has_subdirectories` bool to avoid the
                // generated `hasX` presence-accessor collision.
                hasSubdirectories: l.hasSubdirectories_p
            )
        }
    }

    /// List the immediate child directories of `path` that contain videos
    /// (recursively), for the library panel's expandable tree.
    public func listSubdirectories(_ path: String) async throws -> [Subdirectory] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_ListSubdirectoriesRequest()
        request.path = path
        let response = try await client.listSubdirectories(request)
        return response.subdirectories.map { s in
            Subdirectory(
                path: s.path,
                videoCount: s.videoCount,
                hasSubdirectories: s.hasSubdirectories_p
            )
        }
    }

    /// Add a library location. Returns (success, server message).
    public func addLibraryLocation(path: String, recursive: Bool = true) async throws -> (Bool, String) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_AddLocationRequest()
        request.path = path
        request.recursive = recursive
        let response = try await client.addLibraryLocation(request)
        return (response.success, response.message)
    }

    /// Remove a library location and all its indexed videos from the catalog.
    /// The video files on disk are not touched. Returns true on success.
    public func removeLibraryLocation(path: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_RemoveLocationRequest()
        request.path = path
        let response = try await client.removeLibraryLocation(request)
        return response.success
    }

    /// Streaming scan — yields progress events as the backend works through the library.
    public func scanLibrary(
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
                var request = Reelvault_ScanLibraryRequest()
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
    public struct ServerConfig: Equatable {
        public let maxNativePlaybackHeight: Int
        public let proxyTargetHeight: Int
        public let maxConcurrentJobs: Int
        public let enableAutoTagging: Bool
        /// When true, the post-index pipeline auto-applies a "timelapse" tag
        /// to videos whose recorded resolution exceeds their camera's max
        /// in-camera video resolution. Backed by `auto_tag_history` so a
        /// user-removed tag never gets re-applied.
        public let autoTagTimelapses: Bool
    }

    /// In-memory cache of the last successfully-fetched server config.
    /// Populated on the first `getConfig` call and kept in sync by
    /// `updateConfig` after a successful save.
    ///
    /// Preferences dialogs read `cachedConfig` synchronously to render
    /// with the user's actual settings immediately — without that,
    /// every dialog-open paid a gRPC round-trip, which can be visible
    /// latency on a slow link. Dialogs still call `getConfig` in a
    /// background `.task` to refresh, but they no longer block their
    /// first paint on the network.
    ///
    /// `nil` means "never fetched yet" — dialogs fall back to sensible
    /// defaults until the first fetch completes (typically <100 ms
    /// after the catalog opens).
    public private(set) var cachedConfig: ServerConfig?

    /// Read the daemon's current config. Returns nil on transport
    /// failure so the Preferences UI never has to handle a partial
    /// state — it shows a spinner instead and re-tries on save.
    public func getConfig() async -> ServerConfig? {
        guard let client = serviceClient else { return nil }
        do {
            let response = try await client.getConfig(Reelvault_GetConfigRequest())
            let cfg = ServerConfig(
                maxNativePlaybackHeight: Int(response.maxNativePlaybackHeight),
                proxyTargetHeight: Int(response.proxyTargetHeight),
                maxConcurrentJobs: Int(response.maxConcurrentJobs),
                enableAutoTagging: response.enableAutoTagging,
                autoTagTimelapses: response.autoTagTimelapses,
            )
            self.cachedConfig = cfg
            return cfg
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
    public func updateConfig(
        maxNativePlaybackHeight: Int? = nil,
        proxyTargetHeight: Int? = nil,
        maxConcurrentJobs: Int? = nil,
        enableAutoTagging: Bool? = nil,
        autoTagTimelapses: Bool? = nil
    ) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Reelvault_UpdateConfigRequest()
        if let h = maxNativePlaybackHeight { request.maxNativePlaybackHeight = Int32(h) }
        if let h = proxyTargetHeight { request.proxyTargetHeight = Int32(h) }
        if let n = maxConcurrentJobs { request.maxConcurrentJobs = Int32(n) }
        if let b = enableAutoTagging { request.enableAutoTagging = b }
        if let b = autoTagTimelapses { request.autoTagTimelapses = b }
        do {
            _ = try await client.updateConfig(request)
            // Keep the cache in sync with the value we just persisted so
            // a re-open of any settings dialog sees the saved value
            // without paying another gRPC round-trip. We re-base off
            // the current cache (or sensible defaults) so an
            // `updateConfig` that only touched one field doesn't blank
            // out the others.
            let base = self.cachedConfig ?? ServerConfig(
                maxNativePlaybackHeight: 2160,
                proxyTargetHeight: 720,
                maxConcurrentJobs: 4,
                enableAutoTagging: false,
                autoTagTimelapses: true,
            )
            self.cachedConfig = ServerConfig(
                maxNativePlaybackHeight: maxNativePlaybackHeight ?? base.maxNativePlaybackHeight,
                proxyTargetHeight: proxyTargetHeight ?? base.proxyTargetHeight,
                maxConcurrentJobs: maxConcurrentJobs ?? base.maxConcurrentJobs,
                enableAutoTagging: enableAutoTagging ?? base.enableAutoTagging,
                autoTagTimelapses: autoTagTimelapses ?? base.autoTagTimelapses,
            )
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
    public struct ProxyInfo: Identifiable, Hashable {
        public let id: String
        public let filename: String
        public let path: String
        public let sizeBytes: Int64
        public let width: Int
        public let height: Int
        public let confidence: Double
        public let autoDetected: Bool
        /// Server's verdict that this proxy fits under the locally-playable
        /// height — the detail view prefers a playable proxy for oversize masters.
        public var playableNatively: Bool = true
    }

    /// Break a single master ↔ proxy junction-table edge without
    /// disturbing the row's other proxy relationships. Used by the
    /// inspector's per-row "break" button.
    @discardableResult
    public func removeProxyLink(masterId: String, proxyId: String) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Reelvault_RemoveProxyLinkRequest()
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
    public func listProxies(videoId: String) async throws -> [ProxyInfo] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_ListProxiesRequest()
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
                autoDetected: $0.autoDetected,
                playableNatively: $0.playableNatively
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
    public func generateProxy(
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
                var request = Reelvault_GenerateProxyRequest()
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
    public func setProxyOf(proxyId: String, originalId: String) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Reelvault_SetProxyOfRequest()
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
    public func detectProxies() async -> (pairsCompared: Int, proxiesMarked: Int) {
        guard let client = serviceClient else { return (0, 0) }
        do {
            let response = try await client.detectProxies(Reelvault_DetectProxiesRequest())
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
    /// Returns an `AsyncThrowingStream` that finishes cleanly when the task
    /// is cancelled or the server tears the stream down without error, and
    /// finishes *throwing* when the underlying gRPC call fails. Callers own
    /// a `Task` that loops over it, applies reconnect backoff on a thrown
    /// error, and is cancelled before the repository disconnects.
    public func subscribeCatalogEvents() -> AsyncThrowingStream<CatalogEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                guard let client = serviceClient else {
                    continuation.finish()
                    return
                }
                let request = Reelvault_SubscribeCatalogEventsRequest()
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
                            case .postIndexStarted: kind = .postIndexStarted
                            case .postIndexProgress: kind = .postIndexProgress
                            case .postIndexCompleted: kind = .postIndexCompleted
                            default: kind = .unknown
                            }
                            let postIndex: PostIndexProgress? = proto.hasPostIndex
                                ? PostIndexProgress(
                                    processed: proto.postIndex.processed,
                                    total: proto.postIndex.total,
                                    percent: proto.postIndex.percent,
                                    etaSeconds: proto.postIndex.etaSeconds,
                                    phase: proto.postIndex.phase,
                                    detail: proto.postIndex.detail
                                )
                                : nil
                            continuation.yield(CatalogEvent(
                                kind: kind,
                                videoId: proto.videoID,
                                path: proto.path,
                                atMs: proto.atMs,
                                message: proto.message,
                                postIndex: postIndex
                            ))
                        }
                    }
                    // Server closed the stream cleanly (no error).
                    continuation.finish()
                } catch {
                    // Surface the failure so the caller (the view-model) can
                    // apply reconnect backoff and de-duplicated logging.
                    // Logging every drop here would spam the console when the
                    // daemon is offline.
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Read the daemon's current watcher knobs. Returns the protocol-default
    /// (`WatchSettings.default`) on any error so the Preferences UI never
    /// has to handle a missing-value case.
    public func getWatchSettings() async -> WatchSettings {
        guard let client = serviceClient else { return .default }
        do {
            let response = try await client.getWatchSettings(Reelvault_GetWatchSettingsRequest())
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
    public func updateWatchSettings(_ settings: WatchSettings) async -> Bool {
        guard let client = serviceClient else { return false }
        var request = Reelvault_WatchSettings()
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

    public func listGroupMembers(groupId: String) async throws -> (members: [VideoSummary], preferredId: String) {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_ListGroupMembersRequest()
        request.groupID = groupId
        let response = try await client.listGroupMembers(request)
        return (response.members.map(Self.makeSummary), response.preferredVideoID)
    }

    public func createGroup(videoIds: [String], name: String = "", preferredVideoId: String = "") async throws -> GroupInfo? {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_CreateGroupRequest()
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

    /// Manually attach proxies — the proxy-world analogue of `createGroup`.
    /// The server picks the highest-resolution member of `videoIds` as the
    /// master and links every other selection as a manual proxy of it.
    public func attachProxies(videoIds: [String]) async throws -> AttachProxiesResult {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_AttachProxiesRequest()
        request.videoIds = videoIds
        let response = try await client.attachProxies(request)
        return AttachProxiesResult(
            masterVideoId: response.masterVideoID,
            proxiesAttached: Int(response.proxiesAttached),
            message: response.message
        )
    }

    public func ungroupVideo(videoId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_UngroupVideoRequest()
        request.videoID = videoId
        let response = try await client.ungroupVideo(request)
        return response.success
    }

    public func setGroupPreferred(groupId: String, videoId: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_SetGroupPreferredRequest()
        request.groupID = groupId
        request.videoID = videoId
        let response = try await client.setGroupPreferred(request)
        return response.success
    }

    // MARK: - Proto → Model

    private static func makeSummary(_ p: Reelvault_VideoSummary) -> VideoSummary {
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
            bitrateKbps: Int(p.bitrate / 1000),
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
            focalLengthMm: p.focalLengthMm,
            fullResolution: FullResolutionStatus.from(wire: Int(p.fullResolution.rawValue)),
            isOnline: p.isOnline,
            frameCount: p.frameCount
        )
    }

    private static func makeMetadata(_ p: Reelvault_VideoMetadata) -> VideoMetadata {
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
            whiteBalance: p.whiteBalance,
            fullResolution: FullResolutionStatus.from(wire: Int(p.fullResolution.rawValue)),
            frameCount: p.frameCount
        )
    }

    // MARK: - User marks (rating + color label)

    /// Apply a 0..5 star rating to one or more videos in a single round-trip.
    @discardableResult
    public func updateVideoRating(videoIds: [String], rating: Int) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_UpdateVideoRatingRequest()
        request.videoIds = videoIds
        request.rating = Int32(rating)
        let response = try await client.updateVideoRating(request)
        return response.success
    }

    /// Apply a colour label to one or more videos. Pass empty string to clear.
    @discardableResult
    public func updateVideoColorLabel(videoIds: [String], colorLabel: String) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_UpdateVideoColorLabelRequest()
        request.videoIds = videoIds
        request.colorLabel = colorLabel
        let response = try await client.updateVideoColorLabel(request)
        return response.success
    }

    // MARK: - Grid settings (per-catalog)

    /// Fetch the catalog's saved top-of-card slot configuration. Always
    /// returns exactly four entries; the server pads / truncates as needed.
    public func getGridSettings() async throws -> [String] {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        let response = try await client.getGridSettings(Reelvault_GetGridSettingsRequest())
        return response.topSlots
    }

    /// Persist the four-slot configuration. Both clients pick it up the next
    /// time they open the same catalog (or via a follow-up GetGridSettings).
    @discardableResult
    public func updateGridSettings(topSlots: [String]) async throws -> Bool {
        guard let client = serviceClient else { throw RepositoryError.notConnected }
        var request = Reelvault_GridSettings()
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
        let service = Reelvault_ReelVault.Client(wrapping: self)
        _ = try await service.getStatus(Reelvault_GetStatusRequest())
    }
}

public enum RepositoryError: LocalizedError {
    case notConnected
    /// The daemon accepted the RPC but responded with `success == false`.
    /// Carries the human-readable error message the daemon returned so
    /// the UI can surface it in a toast or alert.
    case serverError(String)

    public var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to ReelVault backend"
        case .serverError(let message):
            return message
        }
    }
}
