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
    @Published var rescanningPaths: Set<String> = []

    // Keywords / tag filter
    @Published var tags: [Tag] = []
    @Published var filterTagId: String = ""  // "" = no filter

    // Top-bar dropdown filters and their distinct-value options.
    @Published var filterCamera: String = ""
    @Published var filterLens: String = ""
    @Published var filterCodec: String = ""
    @Published var filterCaptureYear: Int32 = 0
    @Published var filterOptions = FilterOptions()

    // Lightroom-style user-mark filters.
    //   filterMinRating: 0 = no filter; 1..5 = "show videos with ≥ N stars".
    //   filterColorLabel: "" = no filter; otherwise exact-match the colour.
    @Published var filterMinRating: Int32 = 0
    @Published var filterColorLabel: String = ""

    // Lightroom-style top-of-card stat slots. Exactly four entries — empty
    // string means "blank slot". Defaults to a sensible set on first launch;
    // overwritten by `loadGridSettings()` once the daemon answers.
    @Published var topSlots: [String] = defaultGridTopSlots

    // Geographic proximity filter — set when the user taps a pin on the
    // global map. nil = no proximity filter active.
    @Published var filterLocation: GeoFilter? = nil

    // Snapshot of every geotagged video, refreshed when the user opens
    // the global map view.
    @Published var videoLocations: [VideoLocation] = []

    // Catalog's user-defined named places (e.g. "Home"). Refreshed by
    // [loadNamedLocations]; used by [nameForLocation] to render named pins
    // on the map and named GPS readouts in the detail panel.
    @Published var namedLocations: [NamedLocation] = []

    // Thumbnails
    @Published var thumbnails: [String: NSImage] = [:]

    // Scrub frames per video, loaded lazily on first hover.
    @Published var scrubFrames: [String: [NSImage?]] = [:]
    private var scrubLoading: Set<String> = []

    // Thumbnail fetch concurrency control — mirrors the scrubLoading pattern.
    // Caps simultaneous gRPC thumbnail streams so a large grid entering view
    // at once can't overwhelm the connection and drop some fetches silently.
    private let thumbnailSemaphore = ThumbnailSemaphore(8)
    private var thumbnailLoading: Set<String> = []

    // Stack expansion
    @Published var expandedGroupIds: Set<String> = []
    @Published var expandedGroupMembers: [String: [VideoSummary]] = [:]

    // List-mode column visibility
    @Published var listColumns: Set<String> = ["resolution", "duration", "fps", "codec", "date", "tags", "proxy"]

    // Scan status / result banners
    @Published var scanStatus: String?
    @Published var scanResult: ScanResult?

    // Accumulated progress across all paths in a multi-path add+scan batch.
    struct BatchScanProgress: Equatable {
        /// Total videos found across all paths scanned so far.
        let found: Int
        /// Total videos indexed across all paths scanned so far.
        let indexed: Int
        /// How many paths have finished scanning.
        let pathsDone: Int
        /// Total number of paths in the batch.
        let pathsTotal: Int
    }
    @Published var batchScanProgress: BatchScanProgress?

    // Real-time updates from the server's file watcher.
    //
    // `liveUpdatesEnabled` reflects the most recent state we heard about
    // (initial value matches the server default; updated on the first
    // event after `startCatalogEventStream` connects). The toolbar uses
    // it to render the "live" indicator.
    @Published var liveUpdatesEnabled: Bool = true
    /// Best-effort sticky banner — "Scanning …" — set by SCAN_STARTED and
    /// cleared by SCAN_COMPLETED. Distinct from `scanStatus` (which is
    /// driven by the user's own `addLibraryAndScan` flow) so the watcher
    /// can light up the banner without colliding with that progress
    /// reporter.
    @Published var watcherBanner: String?

    /// AsyncStream task owning the open `SubscribeCatalogEvents` connection.
    /// Cancelled in `stopCatalogEventStream()`; replaced if the stream
    /// drops and we reconnect.
    private var catalogEventsTask: Task<Void, Never>?

    /// Debounce timer for refreshing the grid after a burst of watcher
    /// events. We get one event per file; refreshing the entire grid for
    /// each event would thrash. A 500 ms window collapses bursts into a
    /// single reload.
    private var watcherRefreshWorkItem: DispatchWorkItem?

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

    // MARK: - List mode columns

    func toggleListColumn(_ column: String) {
        if listColumns.contains(column) {
            listColumns.remove(column)
        } else {
            listColumns.insert(column)
        }
    }

    // MARK: - Live updates

    /// Open (or re-open) the long-lived `SubscribeCatalogEvents` stream
    /// against the daemon. Idempotent — calling twice cancels the prior
    /// task and starts a fresh one. Cancellation happens automatically
    /// when the grid view-model is deallocated (deinit can't be async, so
    /// we rely on the task's own cleanup path).
    func startCatalogEventStream() {
        catalogEventsTask?.cancel()
        catalogEventsTask = Task { [weak self] in
            guard let self else { return }
            let stream = self.repository.subscribeCatalogEvents()
            for await event in stream {
                if Task.isCancelled { break }
                self.handleCatalogEvent(event)
            }
            // Stream ended. If we still have a catalog open and the user
            // hasn't disabled live updates, retry once after a short
            // backoff — handles transient gRPC disconnects gracefully.
            if !Task.isCancelled && self.liveUpdatesEnabled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if !Task.isCancelled {
                    self.startCatalogEventStream()
                }
            }
        }
    }

    func stopCatalogEventStream() {
        catalogEventsTask?.cancel()
        catalogEventsTask = nil
    }

    /// Reacts to one `CatalogEvent`. Drives the live-updates indicator,
    /// the watcher banner, and a debounced grid refresh when video rows
    /// change.
    @MainActor
    private func handleCatalogEvent(_ event: CatalogEvent) {
        switch event.kind {
        case .watcherStarted:
            liveUpdatesEnabled = true
        case .watcherDisabled:
            liveUpdatesEnabled = false
        case .scanStarted:
            let target = event.path.isEmpty ? "library" : (event.path as NSString).lastPathComponent
            watcherBanner = "Scanning \(target)…"
        case .scanCompleted:
            watcherBanner = nil
            scheduleWatcherRefresh()
        case .videoAdded, .videoModified, .videoRemoved:
            scheduleWatcherRefresh()
        case .unknown:
            break
        }
    }

    // MARK: - Proxy management

    struct ProxyCreationState {
        let videoId: String
        let progressPercent: Double
        let status: String
        let message: String
    }

    /// Surfaces the "Create proxy" sheet for `videoId`. The actual sheet
    /// is hosted by ContentView; we just publish the request via this
    /// `@Published` property and clear it once acknowledged.
    @Published var proxyCreationVideoId: String?
    /// Per-video progress state for active proxy generation jobs.
    @Published var activeProxyCreations: [String: ProxyCreationState] = [:]

    /// Called by the grid's right-click menu. Just publishes the
    /// request — the sheet hosted by ContentView observes
    /// `proxyCreationVideoId` and presents a resolution picker; once
    /// the user confirms, it calls `startProxyCreation`.
    func requestCreateProxy(videoId: String) {
        proxyCreationVideoId = videoId
    }

    /// Dismiss the proxy picker without starting a job. Bound to the
    /// sheet's Cancel button.
    func cancelProxyCreation() {
        proxyCreationVideoId = nil
    }

    /// Kick off proxy generation against the server. Awaits the stream
    /// to completion and refreshes the grid so the new proxy badge
    /// appears on the source. Surfaces per-video progress through
    /// `activeProxyCreations`. Also clears `proxyCreationVideoId` so
    /// the picker sheet dismisses.
    func startProxyCreation(videoId: String, targetHeight: Int) {
        proxyCreationVideoId = nil
        activeProxyCreations[videoId] = ProxyCreationState(
            videoId: videoId,
            progressPercent: 0,
            status: "started",
            message: targetHeight > 0 ? "Generating \(targetHeight)p proxy…" : "Generating proxy…"
        )
        Task {
            let stream = repository.generateProxy(
                videoId: videoId,
                targetHeight: targetHeight
            )
            do {
                for try await event in stream {
                    if event.status == "complete" {
                        activeProxyCreations.removeValue(forKey: videoId)
                        loadVideos()
                    } else if event.status == "error" {
                        activeProxyCreations.removeValue(forKey: videoId)
                    } else {
                        let prev = activeProxyCreations[videoId]
                        let delta = event.percent - (prev?.progressPercent ?? 0)
                        if prev == nil || prev?.status != event.status || delta >= 1.0 {
                            activeProxyCreations[videoId] = ProxyCreationState(
                                videoId: videoId,
                                progressPercent: event.percent,
                                status: event.status,
                                message: event.message
                            )
                        }
                    }
                }
            } catch {
                activeProxyCreations.removeValue(forKey: videoId)
            }
        }
    }

    // MARK: - Inline grid playback

    /// ID of the video currently playing inline in the grid, or nil.
    @Published var playingVideoId: String? = nil
    /// Filesystem path to play — set to a proxy path when the video is
    /// oversize and a proxy exists. nil means "use video.openPath".
    @Published var playingVideoPath: String? = nil

    /// Begin inline playback for the given video. Replaces any currently
    /// playing card.
    func playVideo(videoId: String) {
        playingVideoPath = nil
        playingVideoId = videoId
    }

    /// Like `playVideo`, but always picks the lowest-resolution proxy
    /// whenever any is available — even for natively-playable masters.
    ///
    /// Rationale: inline grid/list playback is a hover-preview surface,
    /// not a master-quality experience. The smallest proxy decodes
    /// cheapest, leaves CPU + GPU headroom for a busy grid, and avoids
    /// "the card is laggy" complaints on high-bitrate 4K masters that
    /// the server would let us play but that the user's machine
    /// struggles to decode in real time. The right panel / detail
    /// view picker is where the user can explicitly choose the master.
    ///
    /// Falls back to the master path when no proxy exists or the gRPC
    /// call fails.
    func playVideoPreferProxy(videoId: String) {
        guard let video = videos.first(where: { $0.id == videoId }) else {
            playVideo(videoId: videoId)
            return
        }
        if !video.hasProxies {
            // No proxy available — play the master directly.
            playingVideoPath = nil
            playingVideoId = videoId
            return
        }
        // Fetch the proxy list and pick the smallest entry.
        // (`listProxies` returns descending by pixel count; `.last` = smallest.)
        Task { @MainActor in
            do {
                let proxies = try await repository.listProxies(videoId: videoId)
                playingVideoPath = proxies.last?.path
            } catch {
                playingVideoPath = nil
            }
            playingVideoId = videoId
        }
    }

    /// Stop inline playback and return the card to thumbnail mode.
    func stopPlayback() {
        playingVideoId = nil
        playingVideoPath = nil
    }

    /// Coalesce a flurry of watcher events into a single grid reload. Fires
    /// 500 ms after the last event in the burst — typical SAN-drop scenario
    /// is 5–20 file events in <1 s, so this collapses them into one
    /// `loadVideos()` call.
    private func scheduleWatcherRefresh() {
        watcherRefreshWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.loadVideos()
            self?.loadLibraryLocations()
        }
        watcherRefreshWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
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
            let geo: (latitude: Double, longitude: Double, radiusKm: Double)? = filterLocation.map {
                (latitude: $0.latitude, longitude: $0.longitude, radiusKm: $0.radiusKm)
            }
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
                filterCaptureYear: filterCaptureYear,
                geoFilter: geo,
                filterMinRating: filterMinRating,
                filterColorLabel: filterColorLabel
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

    func setMinRatingFilter(_ n: Int32) {
        guard filterMinRating != n else { return }
        filterMinRating = n
        reloadFromTop()
    }

    func setColorLabelFilter(_ label: String) {
        guard filterColorLabel != label else { return }
        filterColorLabel = label
        reloadFromTop()
    }

    func clearAllDropdownFilters() {
        var changed = false
        if !filterCamera.isEmpty { filterCamera = ""; changed = true }
        if !filterLens.isEmpty { filterLens = ""; changed = true }
        if !filterCodec.isEmpty { filterCodec = ""; changed = true }
        if filterCaptureYear != 0 { filterCaptureYear = 0; changed = true }
        if !filterTagId.isEmpty { filterTagId = ""; changed = true }
        if filterMinRating != 0 { filterMinRating = 0; changed = true }
        if !filterColorLabel.isEmpty { filterColorLabel = ""; changed = true }
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

    /// Remove a library location and all its indexed videos from the catalog.
    /// The video files on disk are not touched.
    func removeLibraryLocation(path: String) {
        Task {
            do {
                let success = try await repository.removeLibraryLocation(path: path)
                if success {
                    if selectedLocationPath == path { setLocationFilter("") }
                    loadLibraryLocations()
                    loadVideos()
                } else {
                    await MainActor.run { self.error = "Failed to remove library location" }
                }
            } catch {
                await MainActor.run {
                    self.error = "Failed to remove library location: \(error.localizedDescription)"
                }
            }
        }
    }

    func addLibraryAndScan(
        path: String,
        recursive: Bool = true,
        autoGroup: Bool = true,
        /// "MM-DD-YYYY" | "DD-MM-YYYY" | "YYYY-MM-DD" — empty disables.
        filenameDateFormat: String = "",
        /// "anywhere" | "beginning" | "end" — only consulted when format is non-empty.
        filenameDatePosition: String = ""
    ) {
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

                // Surface the new location in the left panel right away
                // — without this, the row only appears after the scan
                // finishes, which can be minutes on a large folder.
                loadLibraryLocations()

                scanStatus = "Scanning..."
                // We track the *max* values seen across all progress
                // ticks: some scan phases emit zero-valued progress
                // events (e.g. the "indexing_metadata" phase resets
                // `videos_found` to the running count of the current
                // pass, which can briefly read 0 before the daemon
                // re-emits). Taking the max stops the final summary
                // from claiming "0 videos found" when it actually
                // indexed dozens.
                var peakFound = 0
                var peakIndexed = 0
                var errorMessage: String?
                var libraryRefreshTick = 0

                for try await progress in repository.scanLibrary(
                    locationPath: path,
                    autoGroup: autoGroup,
                    filenameDateFormat: filenameDateFormat,
                    filenameDatePosition: filenameDatePosition
                ) {
                    if progress.status == "error" {
                        errorMessage = progress.currentFile
                    } else {
                        peakFound = max(peakFound, progress.videosFound)
                        peakIndexed = max(peakIndexed, progress.videosIndexed)
                        if progress.status == "complete" {
                            scanStatus = "Complete: \(peakFound) found, \(peakIndexed) indexed"
                        } else {
                            scanStatus = "\(progress.status): \(peakIndexed)/\(peakFound) - \(progress.currentFile)"
                        }
                    }
                    // Refresh the grid + library panel every ~12 progress
                    // ticks so the user sees newly-indexed videos and the
                    // location's video-count update as the scan progresses
                    // instead of having to wait until the very end.
                    libraryRefreshTick += 1
                    if libraryRefreshTick % 12 == 0 {
                        reloadFromTop()
                        loadLibraryLocations()
                    }
                }

                if let errorMessage = errorMessage {
                    scanResult = ScanResult(success: false, message: errorMessage, videosFound: 0, videosIndexed: 0)
                } else {
                    // "No videos found" only makes sense when the scan
                    // genuinely turned up zero — if we indexed anything,
                    // the count was just lost to a transient progress
                    // event and we should treat the scan as successful.
                    let foundForReport = max(peakFound, peakIndexed)
                    scanResult = ScanResult(
                        success: true,
                        message: foundForReport == 0
                            ? "No videos found in \(path)"
                            : "Scanned \(path): \(foundForReport) videos found, \(peakIndexed) indexed",
                        videosFound: foundForReport,
                        videosIndexed: peakIndexed
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

    /// Add multiple library locations and scan them sequentially.
    ///
    /// Each path is expanded on the **backend** (which handles `$YEAR`
    /// template substitution). The `batchScanProgress` property accumulates
    /// totals across all paths so callers can show a single progress indicator
    /// for the entire batch instead of one that resets per path.
    func addLibraryAndScanMultiple(
        paths: [String],
        recursive: Bool = true,
        autoGroup: Bool = true,
        filenameDateFormat: String = "",
        filenameDatePosition: String = ""
    ) {
        guard !paths.isEmpty else { return }

        // Fast-path: single path reuses the well-tested single-path implementation.
        if paths.count == 1 {
            addLibraryAndScan(
                path: paths[0],
                recursive: recursive,
                autoGroup: autoGroup,
                filenameDateFormat: filenameDateFormat,
                filenameDatePosition: filenameDatePosition
            )
            return
        }

        Task {
            isLoading = true
            scanResult = nil
            batchScanProgress = BatchScanProgress(found: 0, indexed: 0, pathsDone: 0, pathsTotal: paths.count)

            var totalFound = 0
            var totalIndexed = 0
            var failures = 0

            for (idx, rawPath) in paths.enumerated() {
                let path = rawPath.trimmingCharacters(in: .whitespaces)
                guard !path.isEmpty else {
                    batchScanProgress = BatchScanProgress(
                        found: totalFound, indexed: totalIndexed,
                        pathsDone: idx + 1, pathsTotal: paths.count
                    )
                    continue
                }

                scanStatus = "Adding location \(idx + 1)/\(paths.count): \(URL(fileURLWithPath: path).lastPathComponent)"

                do {
                    let (added, message) = try await repository.addLibraryLocation(path: path, recursive: recursive)
                    if !added {
                        print("[GridViewModel] Failed to add \(path): \(message)")
                        failures += 1
                        batchScanProgress = BatchScanProgress(
                            found: totalFound, indexed: totalIndexed,
                            pathsDone: idx + 1, pathsTotal: paths.count
                        )
                        continue
                    }
                } catch {
                    print("[GridViewModel] Error adding \(path): \(error)")
                    failures += 1
                    batchScanProgress = BatchScanProgress(
                        found: totalFound, indexed: totalIndexed,
                        pathsDone: idx + 1, pathsTotal: paths.count
                    )
                    continue
                }

                // Surface the new location in the left panel right away.
                loadLibraryLocations()

                scanStatus = "Scanning \(idx + 1)/\(paths.count): \(URL(fileURLWithPath: path).lastPathComponent)"

                var peakFound = 0
                var peakIndexed = 0
                var libraryRefreshTick = 0

                do {
                    for try await progress in repository.scanLibrary(
                        locationPath: path,
                        autoGroup: autoGroup,
                        filenameDateFormat: filenameDateFormat,
                        filenameDatePosition: filenameDatePosition
                    ) {
                        if progress.status != "error" {
                            peakFound = max(peakFound, progress.videosFound)
                            peakIndexed = max(peakIndexed, progress.videosIndexed)
                            // Update accumulated totals so the progress indicator
                            // reflects the entire batch, not just the current path.
                            batchScanProgress = BatchScanProgress(
                                found:      totalFound + peakFound,
                                indexed:    totalIndexed + peakIndexed,
                                pathsDone:  idx,          // still scanning this path
                                pathsTotal: paths.count
                            )
                            scanStatus = "Scanning \(idx + 1)/\(paths.count): " +
                                "\(peakIndexed)/\(peakFound) in \(URL(fileURLWithPath: path).lastPathComponent)"
                        }
                        libraryRefreshTick += 1
                        if libraryRefreshTick % 12 == 0 {
                            reloadFromTop()
                            loadLibraryLocations()
                        }
                    }
                } catch {
                    print("[GridViewModel] Scan failed for \(path): \(error)")
                    failures += 1
                }

                totalFound   += peakFound
                totalIndexed += peakIndexed
                batchScanProgress = BatchScanProgress(
                    found:      totalFound,
                    indexed:    totalIndexed,
                    pathsDone:  idx + 1,
                    pathsTotal: paths.count
                )
            }

            let successCount = paths.count - failures
            let resultMsg: String
            if failures == paths.count {
                resultMsg = "Failed to add all \(paths.count) location(s)"
            } else {
                resultMsg = "Scanned \(successCount)/\(paths.count) location(s): " +
                    "\(totalFound) videos found, \(totalIndexed) indexed" +
                    (failures > 0 ? " (\(failures) failed)" : "")
            }
            scanResult = ScanResult(
                success: failures < paths.count,
                message: resultMsg,
                videosFound: totalFound,
                videosIndexed: totalIndexed
            )

            scanStatus = nil
            isLoading = false
            batchScanProgress = nil
            reloadFromTop()
            loadLibraryLocations()
            loadFilterOptions()
        }
    }

    func rescanLibrary(path: String) {
        Task {
            rescanningPaths.insert(path)
            defer {
                rescanningPaths.remove(path)
                scanStatus = nil
            }

            scanStatus = "Rescanning \(URL(fileURLWithPath: path).lastPathComponent)..."
            scanResult = nil

            do {
                var peakFound = 0
                var peakIndexed = 0
                var errorMessage: String?
                var libraryRefreshTick = 0

                for try await progress in repository.scanLibrary(
                    locationPath: path,
                    autoGroup: true,
                    filenameDateFormat: "",
                    filenameDatePosition: ""
                ) {
                    if progress.status == "error" {
                        errorMessage = progress.currentFile
                    } else {
                        peakFound = max(peakFound, progress.videosFound)
                        peakIndexed = max(peakIndexed, progress.videosIndexed)
                        if progress.status == "complete" {
                            scanStatus = "Complete: \(peakFound) found, \(peakIndexed) indexed"
                        } else {
                            scanStatus = "\(progress.status): \(peakIndexed)/\(peakFound) - \(progress.currentFile)"
                        }
                    }
                    libraryRefreshTick += 1
                    if libraryRefreshTick % 12 == 0 {
                        loadVideos()
                        loadLibraryLocations()
                    }
                }

                loadVideos()
                loadLibraryLocations()

                if let errorMessage = errorMessage {
                    scanResult = ScanResult(success: false, message: errorMessage, videosFound: 0, videosIndexed: 0)
                } else {
                    let foundForReport = max(peakFound, peakIndexed)
                    scanResult = ScanResult(
                        success: true,
                        message: foundForReport == 0
                            ? "No videos found in \(path)"
                            : "Rescanned \(path): \(foundForReport) videos found, \(peakIndexed) indexed",
                        videosFound: foundForReport,
                        videosIndexed: peakIndexed
                    )
                }
            } catch {
                scanStatus = nil
                scanResult = ScanResult(
                    success: false,
                    message: "Rescan failed: \(error.localizedDescription)",
                    videosFound: 0,
                    videosIndexed: 0
                )
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
        if thumbnailLoading.contains(videoId) { return }
        thumbnailLoading.insert(videoId)
        Task {
            defer { thumbnailLoading.remove(videoId) }
            // Retry up to 3 times with short back-off. The first attempt can
            // fail with an empty gRPC stream when many cards load simultaneously
            // (connection under load) or when a newly-added video's thumbnail
            // file hasn't been flushed yet.
            for attempt in 0..<3 {
                if thumbnails[videoId] != nil { return }
                await thumbnailSemaphore.acquire()
                let image = try? await repository.getThumbnail(videoId: videoId, size: "medium")
                await thumbnailSemaphore.release()
                if let image {
                    thumbnails[videoId] = image
                    return
                }
                if attempt < 2 {
                    try? await Task.sleep(nanoseconds: UInt64(500_000_000) * UInt64(attempt + 1))
                }
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
            let rawCount = UserDefaults.standard.integer(forKey: "scrubFrameCount")
            let count = rawCount > 0 ? rawCount : 10
            let frames = await repository.getScrubFrames(videoId: videoId, count: count)
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

    /// Refresh every piece of UI state that depends on a stack's
    /// membership: the cached expanded-stack member list, the grid's
    /// representative entries (which carry `groupId` / `groupSize` for
    /// each video), and — if the stack has collapsed to a single video —
    /// the expanded-id set itself. Called after Ungroup This,
    /// Remove-from-stack, or Unstack so the grid catches up with the
    /// daemon's view of the world.
    ///
    /// `groupId` is the *old* group the video was a member of, even if
    /// the ungroup made that group disappear. Pass an empty string when
    /// the operation isn't tied to a specific group (e.g. a bulk
    /// ungroup that's already cleared the local state itself).
    /// Remove a single video from its stack and refresh the grid. Wired
    /// from the right-click "Remove from stack" menu item.
    func removeFromStack(videoId: String, groupId: String) {
        guard !videoId.isEmpty else { return }
        Task {
            do {
                if try await repository.ungroupVideo(videoId: videoId) {
                    refreshAfterStackChange(groupId: groupId)
                } else {
                    error = "Failed to remove video from stack"
                }
            } catch {
                self.error = "Failed to remove from stack: \(error.localizedDescription)"
            }
        }
    }

    /// Promote a video within an existing stack to become the
    /// representative shown when the stack is collapsed. Wired from the
    /// right-click "Set as Stack Master" menu item that appears only when
    /// the right-clicked card is a non-representative member.
    func setStackMaster(videoId: String, groupId: String) {
        guard !videoId.isEmpty, !groupId.isEmpty else { return }
        // Optimistic local update so the badge / grid order updates
        // before the round-trip completes.
        videos = videos.map { v in
            if v.groupId == groupId {
                return VideoSummary(
                    id: v.id, filename: v.filename, path: v.path,
                    width: v.width, height: v.height, durationMs: v.durationMs,
                    fps: v.fps, codecVideo: v.codecVideo, codecAudio: v.codecAudio,
                    bitrateKbps: v.bitrateKbps, sizeBytes: v.sizeBytes,
                    indexedAt: v.indexedAt, creationDate: v.creationDate,
                    tags: v.tags, hasThumbnail: v.hasThumbnail,
                    groupId: v.groupId, groupSize: v.groupSize,
                    groupPreferredId: videoId, groupPreferredPath: v.groupPreferredPath,
                    proxyCount: v.proxyCount, proxyOf: v.proxyOf,
                    playableNatively: v.playableNatively,
                    rating: v.rating, colorLabel: v.colorLabel,
                    cameraModel: v.cameraModel, cameraDisplayName: v.cameraDisplayName,
                    gpsLatitude: v.gpsLatitude, gpsLongitude: v.gpsLongitude
                )
            }
            return v
        }
        Task {
            do {
                _ = try await repository.setGroupPreferred(groupId: groupId, videoId: videoId)
                refreshAfterStackChange(groupId: groupId)
                reloadFromTop()  // representative changed → grid order may shift
            } catch {
                self.error = "Failed to set stack master: \(error.localizedDescription)"
            }
        }
    }

    /// Disband an entire stack — ungroups every member, leaving each
    /// video standalone. Wired from the right-click "Unstack" menu
    /// item. We iterate via individual `UngroupVideo` calls rather than
    /// a bulk RPC because the daemon doesn't currently expose one;
    /// stacks are small (a handful of variants), so the chatter is
    /// fine.
    func unstackGroup(groupId: String) {
        guard !groupId.isEmpty else { return }
        Task {
            do {
                let (members, _) = try await repository.listGroupMembers(groupId: groupId)
                for member in members {
                    _ = try await repository.ungroupVideo(videoId: member.id)
                }
                refreshAfterStackChange(groupId: groupId)
            } catch {
                self.error = "Failed to unstack: \(error.localizedDescription)"
            }
        }
    }

    func refreshAfterStackChange(groupId: String) {
        if !groupId.isEmpty {
            // Re-pull the member list for this group. If the daemon
            // dissolved the group entirely (last member ungrouped), the
            // call will return zero members and we'll drop it from the
            // cache so the stack badge stops trying to expand it.
            Task {
                do {
                    let (members, _) = try await repository.listGroupMembers(groupId: groupId)
                    if members.count < 2 {
                        expandedGroupMembers.removeValue(forKey: groupId)
                        expandedGroupIds.remove(groupId)
                    } else {
                        expandedGroupMembers[groupId] = members
                    }
                } catch {
                    // On error, fall back to dropping the cached entry
                    // so subsequent expansions re-fetch fresh.
                    expandedGroupMembers.removeValue(forKey: groupId)
                }
            }
        }
        // Reload the representative list so each video's `groupId` /
        // `groupSize` reflects the post-ungroup reality.
        reloadFromTop()
    }

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

    /// Tracks groupIds whose members are currently being prefetched, so the
    /// list-row views can fire-and-forget on appear without flooding the
    /// daemon with duplicate ListGroupMembers RPCs.
    private var stackMembersLoading: Set<String> = []

    /// Populate `expandedGroupMembers` for `groupId` without expanding the
    /// stack. Used by the list view to render the names of a collapsed
    /// stack's members in the row's info column alongside the other
    /// details. The cache is shared with `toggleStackExpansion` so a
    /// subsequent expand reuses the already-fetched members.
    func ensureStackMembersLoaded(_ groupId: String) {
        guard !groupId.isEmpty else { return }
        if expandedGroupMembers[groupId] != nil { return }
        if !stackMembersLoading.insert(groupId).inserted { return }
        Task {
            defer { stackMembersLoading.remove(groupId) }
            do {
                let (members, _) = try await repository.listGroupMembers(groupId: groupId)
                expandedGroupMembers[groupId] = members
            } catch {
                NSLog("Failed to preload group members: \(error)")
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

        // Proxies are stand-ins for their masters and must not also belong to a
        // stack — that would let the same file appear in two roles at once.
        let idSet = Set(ids)
        let proxyCount = videos.filter { idSet.contains($0.id) && $0.isProxy }.count
        if proxyCount > 0 {
            let noun = proxyCount == 1 ? "proxy" : "\(proxyCount) proxies"
            error = "Proxy videos cannot be added to a stack. Deselect the \(noun) and try again."
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

    /// Select every video currently visible in the grid: each loaded
    /// representative plus, when its stack is expanded, all of its visible
    /// members. Drives the ⌘A shortcut.
    func selectAllVisible() {
        var seen = Set<String>()
        var ids: [String] = []
        for v in videos {
            if seen.insert(v.id).inserted {
                ids.append(v.id)
            }
            if !v.groupId.isEmpty,
               expandedGroupIds.contains(v.groupId),
               let members = expandedGroupMembers[v.groupId] {
                for m in members where seen.insert(m.id).inserted {
                    ids.append(m.id)
                }
            }
        }
        selectedVideoIds = ids
        selectedVideoId = ids.first
        anchorVideoId = ids.first
    }

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
        filterMinRating = 0
        filterColorLabel = ""
        topSlots = defaultGridTopSlots
        thumbnails = [:]
        scrubFrames = [:]
        scrubLoading = []
        expandedGroupIds = []
        expandedGroupMembers = [:]
        scanStatus = nil
        scanResult = nil
        currentPage = 0
        filterLocation = nil
        videoLocations = []
    }

    // MARK: - User marks (rating + color label)

    /// Apply a 0..5 star rating to one or more videos. Optimistically updates
    /// the cached `videos` list so the UI redraws immediately, then sends the
    /// RPC. The pattern mirrors how tag-add/-remove is handled today.
    func setRating(_ rating: Int, for videoIds: [String]) {
        let clamped = max(0, min(5, rating))
        let ids = Set(videoIds.filter { !$0.isEmpty })
        guard !ids.isEmpty else { return }
        // Optimistic local update so the stars repaint without a round-trip.
        videos = videos.map { v in
            ids.contains(v.id) ? v.withRating(clamped) : v
        }
        Task {
            do {
                try await repository.updateVideoRating(videoIds: Array(ids), rating: clamped)
            } catch {
                self.error = "Failed to set rating: \(error.localizedDescription)"
                // Don't roll back — server is the source of truth on next reload.
            }
        }
    }

    /// Apply a colour label to one or more videos. Empty string clears the
    /// label. Optimistic update like [setRating].
    func setColorLabel(_ label: String, for videoIds: [String]) {
        let ids = Set(videoIds.filter { !$0.isEmpty })
        guard !ids.isEmpty else { return }
        videos = videos.map { v in
            ids.contains(v.id) ? v.withColorLabel(label) : v
        }
        Task {
            do {
                try await repository.updateVideoColorLabel(videoIds: Array(ids), colorLabel: label)
            } catch {
                self.error = "Failed to set color label: \(error.localizedDescription)"
            }
        }
    }

    /// Apply the rating to the current selection (or to the anchor when no
    /// multi-select). Called by the keyboard handler for digits 0..5.
    func setRatingOnSelection(_ rating: Int) {
        let ids = selectedVideoIds.isEmpty
            ? (selectedVideoId.map { [$0] } ?? [])
            : selectedVideoIds
        setRating(rating, for: ids)
    }

    /// Apply the colour label to the current selection. Used by the keyboard
    /// handler for digits 6..9 and the backtick (clear).
    ///
    /// Toggle semantics: pressing a colour hotkey when every selected video
    /// already carries that colour clears the colour from all of them.
    /// Pressing the same hotkey on a mixed (or differently-labelled)
    /// selection applies the colour uniformly. Explicit clear via backtick
    /// (`label == ""`) bypasses the toggle and always clears.
    func setColorLabelOnSelection(_ label: String) {
        let ids = selectedVideoIds.isEmpty
            ? (selectedVideoId.map { [$0] } ?? [])
            : selectedVideoIds
        guard !ids.isEmpty else { return }
        if label.isEmpty {
            setColorLabel("", for: ids)
            return
        }
        let idSet = Set(ids)
        let affected = videos.filter { idSet.contains($0.id) }
        let allAlreadyHaveLabel = !affected.isEmpty
            && affected.allSatisfy { $0.colorLabel == label }
        setColorLabel(allAlreadyHaveLabel ? "" : label, for: ids)
    }

    // MARK: - Grid layout settings (top-of-card stat slots)

    /// Pull the saved 4-slot configuration from the catalog. Called once
    /// after the catalog opens. Defaults survive an RPC failure so the grid
    /// never starts in a broken state.
    func loadGridSettings() {
        Task {
            do {
                let raw = try await repository.getGridSettings()
                let normalised = Self.normaliseSlots(raw)
                await MainActor.run { self.topSlots = normalised }
            } catch {
                // Silent on failure — keep using defaults.
            }
        }
    }

    /// Persist the four-slot configuration. The optimistic local update
    /// already happened when the picker mutated `topSlots`; this just
    /// shoves the value across the wire.
    func saveGridSettings() {
        let slots = Self.normaliseSlots(topSlots)
        Task {
            do {
                try await repository.updateGridSettings(topSlots: slots)
            } catch {
                self.error = "Failed to save grid settings: \(error.localizedDescription)"
            }
        }
    }

    /// Pad / truncate any slot list to exactly four entries. Defends against
    /// malformed server replies and against UI mistakes.
    private static func normaliseSlots(_ raw: [String]) -> [String] {
        var slots = raw
        while slots.count < 4 { slots.append("") }
        if slots.count > 4 { slots = Array(slots.prefix(4)) }
        return slots
    }

    // MARK: - Geolocation

    /// Apply (or clear) the geographic proximity filter and reload the grid.
    /// Called by the global-map view when the user taps a pin.
    func setLocationFilter(latitude: Double?, longitude: Double?, radiusKm: Double = 1.0) {
        if let lat = latitude, let lon = longitude {
            filterLocation = GeoFilter(latitude: lat, longitude: lon, radiusKm: radiusKm)
        } else {
            filterLocation = nil
        }
        Task { await loadCurrentPage(replace: true) }
    }

    /// Refresh the list of geotagged videos used by the global-map view.
    func loadVideoLocations() {
        Task { await loadVideoLocationsAsync() }
    }

    /// Awaitable variant — callers that need the data populated *before*
    /// they take a UI action (opening a sheet, framing a map) can `await`
    /// this. Always reads from the daemon; relies on the catalog-open
    /// pre-load to keep the call fast in steady state.
    func loadVideoLocationsAsync() async {
        videoLocations = await repository.listVideosWithLocations()
    }

    /// Refresh the catalog's named-location list. Called whenever a sheet
    /// needs to display or resolve names — cheap (typically a few hundred
    /// rows at most) so we never paginate.
    func loadNamedLocations() {
        Task { await loadNamedLocationsAsync() }
    }

    /// Awaitable variant of [loadNamedLocations].
    func loadNamedLocationsAsync() async {
        namedLocations = await repository.listNamedLocations()
    }

    /// Resolve a (lat, lon) into the nearest named place within its
    /// `radiusMeters`, or nil if no entry is close enough. Linear scan —
    /// the named-location list is small.
    func nameForLocation(latitude: Double, longitude: Double) -> NamedLocation? {
        guard !namedLocations.isEmpty else { return nil }
        var best: (NamedLocation, Double)? = nil
        for loc in namedLocations {
            let d = Self.haversineMeters(
                lat1: latitude, lon1: longitude,
                lat2: loc.latitude, lon2: loc.longitude
            )
            if d <= loc.radiusMeters {
                if best == nil || d < best!.1 { best = (loc, d) }
            }
        }
        return best?.0
    }

    /// Upsert a named location on the daemon and (on success) merge the
    /// returned row into the local cache so the UI sees it immediately
    /// without a full reload.
    func saveNamedLocation(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 250,
        onComplete: @escaping (NamedLocation?) -> Void = { _ in }
    ) {
        Task {
            let saved = await repository.upsertNamedLocation(
                id: id, name: name,
                latitude: latitude, longitude: longitude,
                radiusMeters: radiusMeters
            )
            if let saved = saved {
                // Replace-or-insert by id so repeat saves don't duplicate.
                if let idx = namedLocations.firstIndex(where: { $0.id == saved.id }) {
                    namedLocations[idx] = saved
                } else {
                    namedLocations.append(saved)
                }
                namedLocations.sort { $0.name.lowercased() < $1.name.lowercased() }
            }
            onComplete(saved)
        }
    }

    /// Great-circle distance in meters. Standard haversine — accurate
    /// enough for sub-kilometer resolution at any latitude we care about.
    private static func haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ) -> Double {
        let earthRadius = 6_371_000.0
        let toRad = Double.pi / 180
        let dLat = (lat2 - lat1) * toRad
        let dLon = (lon2 - lon1) * toRad
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * toRad) * cos(lat2 * toRad)
            * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /// Persist a new GPS location on every video in `videoIds`. After all
    /// writes complete the grid and global-map are reloaded.
    func setVideoLocations(
        videoIds: [String],
        latitude: Double,
        longitude: Double,
        writeToFile: Bool,
        onComplete: @escaping () -> Void = {}
    ) {
        guard !videoIds.isEmpty else { return }
        Task {
            var ok = 0
            for id in videoIds {
                let success = await repository.updateVideoLocation(
                    videoId: id,
                    latitude: latitude,
                    longitude: longitude,
                    altitude: 0,
                    writeToFile: writeToFile
                )
                if success { ok += 1 }
            }
            NSLog("Updated location on \(ok)/\(videoIds.count) video(s)")
            videoLocations = await repository.listVideosWithLocations()
            await loadCurrentPage(replace: true)
            onComplete()
        }
    }

    /// Persist a new capture timestamp (Unix ms, UTC) on every video in
    /// `videoIds`. Reloads the grid + year-filter dropdown afterwards.
    func setVideoCaptureDates(
        videoIds: [String],
        timestampMs: Int64,
        writeToFile: Bool,
        onComplete: @escaping () -> Void = {}
    ) {
        guard !videoIds.isEmpty else { return }
        Task {
            var ok = 0
            for id in videoIds {
                let success = await repository.updateVideoCaptureDate(
                    videoId: id,
                    timestampMs: timestampMs,
                    writeToFile: writeToFile
                )
                if success { ok += 1 }
            }
            NSLog("Updated capture date on \(ok)/\(videoIds.count) video(s)")
            filterOptions = await repository.getFilterOptions()
            await loadCurrentPage(replace: true)
            onComplete()
        }
    }
}

/// Proximity filter — kept as a named struct so SwiftUI can compare-and-redraw
/// reliably, and so the parameter list of [GridViewModel] doesn't fan out a
/// half-typed tuple everywhere.
struct GeoFilter: Equatable {
    let latitude: Double
    let longitude: Double
    let radiusKm: Double
}

/// Async counting semaphore used to cap concurrent thumbnail gRPC streams.
/// Callers `await acquire()` to take a permit and `await release()` to return
/// one. Waiting callers are resumed in FIFO order.
private actor ThumbnailSemaphore {
    private var available: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ count: Int) { self.available = count }

    func acquire() async {
        if available > 0 {
            available -= 1
        } else {
            await withCheckedContinuation { waiters.append($0) }
        }
    }

    func release() {
        if let next = waiters.first {
            waiters.removeFirst()
            next.resume()
        } else {
            available += 1
        }
    }
}
