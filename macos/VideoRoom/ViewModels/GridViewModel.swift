// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import Combine

@MainActor
class GridViewModel: ObservableObject {
    // Grid state
    @Published var videos: [VideoSummary] = []
    @Published var selectedVideoId: String?
    @Published var selectedVideoIds: [String] = []
    @Published var anchorVideoId: String?
    @Published var isLoading = false
    @Published var error: String?
    @Published var totalCount: Int64 = 0
    @Published var hasMore = false
    @Published var searchQuery = ""

    // Sort
    @Published var sortBy: String = "indexed_at"
    @Published var sortAscending: Bool = false

    // Library locations / filter
    @Published var libraryLocations: [LibraryLocation] = []
    @Published var selectedLocationPath: String = ""  // "" = all

    // Keywords / tag filter
    @Published var tags: [Tag] = []
    @Published var filterTagId: String = ""  // "" = no filter

    // Top-bar dropdown filters and their distinct-value options.
    @Published var filterCamera: String = ""
    @Published var filterLens: String = ""
    @Published var filterCodec: String = ""
    @Published var filterCaptureYear: Int32 = 0
    @Published var filterOptions = FilterOptions()

    // Thumbnails
    @Published var thumbnails: [String: NSImage] = [:]

    // Scrub frames per video, loaded lazily on first hover.
    @Published var scrubFrames: [String: [NSImage?]] = [:]
    private var scrubLoading: Set<String> = []

    // Stack expansion
    @Published var expandedGroupIds: Set<String> = []
    @Published var expandedGroupMembers: [String: [VideoSummary]] = [:]

    // Scan status / result banners
    @Published var scanStatus: String?
    @Published var scanResult: ScanResult?

    struct ScanResult: Equatable {
        let success: Bool
        let message: String
        let videosFound: Int
        let videosIndexed: Int
    }

    private let repository = VideoRepository.shared
    private var currentPage = 0
    private let pageSize: Int32 = 50
    private var cancellables = Set<AnyCancellable>()
    private var searchDebounce: AnyCancellable?

    init() {
        // Debounced search
        searchDebounce = $searchQuery
            .debounce(for: 0.5, scheduler: DispatchQueue.main)
            .removeDuplicates()
            .sink { [weak self] _ in
                self?.reloadFromTop()
            }
    }

    // MARK: - Loading

    func loadVideos() {
        reloadFromTop()
    }

    private func reloadFromTop() {
        currentPage = 0
        expandedGroupIds = []
        Task { await loadCurrentPage(replace: true) }
    }

    func loadMore() {
        guard hasMore && !isLoading else { return }
        currentPage += 1
        Task { await loadCurrentPage(replace: false) }
    }

    private func loadCurrentPage(replace: Bool) async {
        isLoading = true
        error = nil
        do {
            let filterTagIds = filterTagId.isEmpty ? [] : [filterTagId]
            let (results, total) = try await repository.listVideos(
                limit: pageSize,
                offset: Int32(currentPage * Int(pageSize)),
                searchQuery: searchQuery,
                sortBy: sortBy,
                sortAscending: sortAscending,
                locationPath: selectedLocationPath,
                filterTagIds: filterTagIds,
                filterCamera: filterCamera,
                filterLens: filterLens,
                filterCodec: filterCodec,
                filterCaptureYear: filterCaptureYear
            )
            if replace {
                videos = results
            } else {
                videos.append(contentsOf: results)
            }
            totalCount = total
            hasMore = Int64(videos.count) < total
            isLoading = false
        } catch {
            self.error = "Failed to load videos: \(error.localizedDescription)"
            isLoading = false
        }
    }

    // MARK: - Sort / filter

    func setSort(_ field: String, ascending: Bool) {
        sortBy = field
        sortAscending = ascending
        reloadFromTop()
    }

    func setLocationFilter(_ path: String) {
        guard selectedLocationPath != path else { return }
        selectedLocationPath = path
        reloadFromTop()
    }

    // MARK: - Keywords / tags

    func loadTags() {
        Task {
            do {
                tags = try await repository.listTags().sorted { $0.name.lowercased() < $1.name.lowercased() }
            } catch {
                NSLog("Failed to load tags: \(error)")
            }
        }
    }

    func setTagFilter(_ tagId: String) {
        guard filterTagId != tagId else { return }
        filterTagId = tagId
        reloadFromTop()
    }

    func loadFilterOptions() {
        Task {
            filterOptions = await repository.getFilterOptions()
        }
    }

    func setCameraFilter(_ value: String) {
        guard filterCamera != value else { return }
        filterCamera = value
        reloadFromTop()
    }
    func setLensFilter(_ value: String) {
        guard filterLens != value else { return }
        filterLens = value
        reloadFromTop()
    }
    func setCodecFilter(_ value: String) {
        guard filterCodec != value else { return }
        filterCodec = value
        reloadFromTop()
    }
    func setCaptureYearFilter(_ year: Int32) {
        guard filterCaptureYear != year else { return }
        filterCaptureYear = year
        reloadFromTop()
    }

    func clearAllDropdownFilters() {
        var changed = false
        if !filterCamera.isEmpty { filterCamera = ""; changed = true }
        if !filterLens.isEmpty { filterLens = ""; changed = true }
        if !filterCodec.isEmpty { filterCodec = ""; changed = true }
        if filterCaptureYear != 0 { filterCaptureYear = 0; changed = true }
        if !filterTagId.isEmpty { filterTagId = ""; changed = true }
        if changed { reloadFromTop() }
    }

    /// Apply [keyword] to all videos in [videoIds]. Creates the tag if it
    /// doesn't exist. Refreshes the tag list (for new counts) and the
    /// optional [onComplete] handler runs afterwards.
    func applyKeyword(_ keyword: String, to videoIds: [String], onComplete: @escaping () -> Void = {}) {
        let name = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !videoIds.isEmpty else { return }
        Task {
            do {
                guard let tag = try await repository.createTag(name: name) else {
                    error = "Failed to create or find tag '\(name)'"
                    return
                }
                if !(try await repository.tagVideos(videoIds: videoIds, tagId: tag.id)) {
                    error = "Failed to apply '\(name)'"
                    return
                }
                loadTags()
                onComplete()
            } catch {
                self.error = "Apply keyword failed: \(error.localizedDescription)"
            }
        }
    }

    func removeKeyword(tagId: String, from videoIds: [String], onComplete: @escaping () -> Void = {}) {
        guard !tagId.isEmpty, !videoIds.isEmpty else { return }
        Task {
            do {
                if !(try await repository.untagVideos(videoIds: videoIds, tagId: tagId)) {
                    error = "Failed to remove tag"
                    return
                }
                loadTags()
                onComplete()
            } catch {
                self.error = "Remove keyword failed: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Library locations

    func loadLibraryLocations() {
        Task {
            do {
                libraryLocations = try await repository.listLibraryLocations()
            } catch {
                NSLog("Failed to load library locations: \(error)")
            }
        }
    }

    func addLibraryAndScan(path: String, recursive: Bool = true, autoGroup: Bool = true) {
        Task {
            isLoading = true
            scanStatus = "Adding library location..."
            scanResult = nil

            do {
                let (added, message) = try await repository.addLibraryLocation(path: path, recursive: recursive)
                if !added {
                    scanResult = ScanResult(
                        success: false,
                        message: message.isEmpty ? "Failed to add library location" : message,
                        videosFound: 0,
                        videosIndexed: 0
                    )
                    scanStatus = nil
                    isLoading = false
                    return
                }

                scanStatus = "Scanning..."
                var lastFound = 0
                var lastIndexed = 0
                var errorMessage: String?

                for try await progress in repository.scanLibrary(locationPath: path, autoGroup: autoGroup) {
                    switch progress.status {
                    case "error":
                        errorMessage = progress.currentFile
                    case "complete":
                        lastFound = progress.videosFound
                        lastIndexed = progress.videosIndexed
                        scanStatus = "Complete: \(lastFound) found, \(lastIndexed) indexed"
                    default:
                        lastFound = progress.videosFound
                        lastIndexed = progress.videosIndexed
                        scanStatus = "\(progress.status): \(progress.videosIndexed)/\(progress.videosFound) - \(progress.currentFile)"
                    }
                }

                if let errorMessage = errorMessage {
                    scanResult = ScanResult(success: false, message: errorMessage, videosFound: 0, videosIndexed: 0)
                } else {
                    scanResult = ScanResult(
                        success: true,
                        message: lastFound == 0
                            ? "No videos found in \(path)"
                            : "Scanned \(path): \(lastFound) videos found, \(lastIndexed) indexed",
                        videosFound: lastFound,
                        videosIndexed: lastIndexed
                    )
                }

                scanStatus = nil
                isLoading = false
                reloadFromTop()
                loadLibraryLocations()
                loadFilterOptions()
            } catch {
                scanResult = ScanResult(
                    success: false,
                    message: "Scan failed: \(error.localizedDescription)",
                    videosFound: 0,
                    videosIndexed: 0
                )
                scanStatus = nil
                isLoading = false
            }
        }
    }

    func clearScanResult() { scanResult = nil }

    // MARK: - Selection (plain / shift / cmd-click semantics)

    func selectVideo(_ video: VideoSummary) {
        selectedVideoIds = [video.id]
        anchorVideoId = video.id
        selectedVideoId = video.id
    }

    func toggleVideoSelection(_ video: VideoSummary) {
        if selectedVideoIds.contains(video.id) {
            selectedVideoIds.removeAll { $0 == video.id }
            if anchorVideoId == video.id {
                anchorVideoId = selectedVideoIds.last
            }
        } else {
            selectedVideoIds.append(video.id)
            anchorVideoId = video.id
        }
        selectedVideoId = video.id
    }

    /// Shift-click range selection. The caller computes [rangeIds] in visual order.
    func selectRange(_ target: VideoSummary, rangeIds: [String]) {
        if rangeIds.isEmpty {
            selectVideo(target)
            return
        }
        selectedVideoIds = rangeIds
        selectedVideoId = target.id
        // anchor stays
    }

    func clearSelection() {
        selectedVideoIds = []
        selectedVideoId = nil
        anchorVideoId = nil
    }

    // MARK: - Thumbnails

    func loadThumbnail(videoId: String) {
        if thumbnails[videoId] != nil { return }
        Task {
            if let image = try? await repository.getThumbnail(videoId: videoId, size: "medium") {
                thumbnails[videoId] = image
            }
        }
    }

    /// Lazy-load all scrub frames for a video on first hover. No-ops if already
    /// loaded or in-flight.
    func loadScrubFrames(videoId: String) {
        if scrubFrames[videoId] != nil { return }
        if scrubLoading.contains(videoId) { return }
        scrubLoading.insert(videoId)
        NSLog("[GridViewModel] loadScrubFrames begin video=%@", videoId)
        Task {
            defer { scrubLoading.remove(videoId) }
            let frames = await repository.getScrubFrames(videoId: videoId, count: 10)
            let nonNil = frames.filter { $0 != nil }.count
            NSLog(
                "[GridViewModel] loadScrubFrames done video=%@ got=%d/%d",
                videoId,
                nonNil,
                frames.count
            )
            if nonNil > 0 {
                scrubFrames[videoId] = frames
            }
        }
    }

    // MARK: - Stack expansion

    func toggleStackExpansion(_ groupId: String) {
        guard !groupId.isEmpty else { return }
        if expandedGroupIds.contains(groupId) {
            expandedGroupIds.remove(groupId)
            return
        }
        expandedGroupIds.insert(groupId)
        if expandedGroupMembers[groupId] != nil { return }
        Task {
            do {
                let (members, _) = try await repository.listGroupMembers(groupId: groupId)
                expandedGroupMembers[groupId] = members
                for m in members where m.hasThumbnail { loadThumbnail(videoId: m.id) }
            } catch {
                NSLog("Failed to load group members: \(error)")
            }
        }
    }

    // MARK: - Grouping

    func groupSelectedVideos() {
        let ids = selectedVideoIds
        if ids.count < 2 {
            error = "Select at least 2 videos (Shift+click or Cmd+click) to create a group"
            return
        }
        let preferred = anchorVideoId.flatMap { id in ids.contains(id) ? id : nil } ?? ids.first!
        Task {
            isLoading = true
            do {
                _ = try await repository.createGroup(videoIds: ids, name: "", preferredVideoId: preferred)
                clearSelection()
                reloadFromTop()
            } catch {
                self.error = "Group failed: \(error.localizedDescription)"
            }
            isLoading = false
        }
    }

    // MARK: - External app

    func openVideoInExternal(path: String) {
        let url = URL(fileURLWithPath: path)
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.open(url)
        } else {
            error = "File not found: \(path)"
        }
    }

    func clearError() { error = nil }

    /// Wipe every piece of catalog-derived state so the UI doesn't leak data
    /// from the previously-mounted catalog. Called by `ContentView` right
    /// after `VideoRepository.closeCatalog()`.
    func clearState() {
        videos = []
        selectedVideoId = nil
        selectedVideoIds = []
        anchorVideoId = nil
        isLoading = false
        error = nil
        totalCount = 0
        hasMore = false
        searchQuery = ""
        libraryLocations = []
        selectedLocationPath = ""
        tags = []
        filterTagId = ""
        filterCamera = ""
        filterLens = ""
        filterCodec = ""
        filterCaptureYear = 0
        filterOptions = FilterOptions()
        thumbnails = [:]
        scrubFrames = [:]
        scrubLoading = []
        expandedGroupIds = []
        expandedGroupMembers = [:]
        scanStatus = nil
        scanResult = nil
        currentPage = 0
    }
}
