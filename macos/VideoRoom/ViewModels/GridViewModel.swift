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

    // Thumbnails
    @Published var thumbnails: [String: NSImage] = [:]

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
            let (results, total) = try await repository.listVideos(
                limit: pageSize,
                offset: Int32(currentPage * Int(pageSize)),
                searchQuery: searchQuery,
                sortBy: sortBy,
                sortAscending: sortAscending,
                locationPath: selectedLocationPath
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

    func addLibraryAndScan(path: String, autoGroup: Bool) {
        Task {
            isLoading = true
            scanStatus = "Adding library location..."
            scanResult = nil

            do {
                let (added, message) = try await repository.addLibraryLocation(path: path, recursive: true)
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
}
