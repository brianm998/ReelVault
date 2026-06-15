// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import Combine
import AVFoundation
#if canImport(AppKit)
import AppKit
#endif

/// Arrow-key navigation direction in the grid / list.
public enum MoveDirection { case up, down, left, right }

/// Destination index for an arrow-key move, or -1 for "no move" (edge of the
/// navigable area). Pure so the geometry is testable.
///
/// `cols == 1` means a single-column layout (list mode, or a one-wide grid):
/// every direction collapses to previous/next, with Left/Up == previous and
/// Right/Down == next. For a wider grid, Left/Right walk the flat visual order
/// (wrapping across row boundaries) and Up/Down jump a whole row.
public func navTargetIndex(current: Int, size: Int, cols: Int, dir: MoveDirection) -> Int {
    guard current >= 0, current < size else { return -1 }
    let columns = max(1, cols)
    switch dir {
    // Left/Right walk the flat row-major order, so a row boundary wraps:
    // Right on a row's last card lands on the next row's first card, and
    // Left on a row's first card lands on the previous row's last card.
    case .left:
        return current > 0 ? current - 1 : -1
    case .right:
        return current < size - 1 ? current + 1 : -1
    case .up:
        if columns == 1 { return current > 0 ? current - 1 : -1 }
        return current - columns >= 0 ? current - columns : -1
    case .down:
        if columns == 1 { return current < size - 1 ? current + 1 : -1 }
        return current + columns < size ? current + columns : -1
    }
}

/// Index of the next/previous card that's in [selected], scanning outward from
/// [from] in visual (row-major) order. Drives Left/Right traversal of a
/// multi-row selection: at a row's edge the next selected card is on the
/// following row, so the cursor wraps a line. Returns -1 past the selection's
/// first/last member.
public func nextSelectedIndex(_ order: [VideoSummary], from: Int, selected: Set<String>, forward: Bool) -> Int {
    if forward {
        var j = from + 1
        while j < order.count {
            if selected.contains(order[j].id) { return j }
            j += 1
        }
    } else {
        var j = from - 1
        while j >= 0 {
            if selected.contains(order[j].id) { return j }
            j -= 1
        }
    }
    return -1
}

/// Identifies one removable rule within a smart collection's saved filter, so
/// the details panel can offer a per-rule ✕ that deletes just that constraint.
public enum SmartCriterion: Equatable {
    case column(String)   // a metadata column (incl. the tag-backed "keyword")
    case minRating
    case colorLabel
    case keywords         // the dedicated tagIds filter
    case geo
    case folder           // library-folder selection (locationPaths)
    case hasLocation      // tri-state attribute filters
    case hasKeywords
    case hasProxies
    case fullResolution
    case hasAudio
    case orientation
}

/// One human-readable rule of a smart collection, plus the [SmartCriterion] it
/// maps to so it can be deleted individually.
public struct SmartCriterionRow: Identifiable {
    public let id = UUID()
    public let label: String
    public let value: String
    public let criterion: SmartCriterion
}

@MainActor
public class GridViewModel: ObservableObject {
    // Grid state
    @Published public var videos: [VideoSummary] = []
    @Published public var selectedVideoId: String?
    @Published public var selectedVideoIds: [String] = []
    @Published public var anchorVideoId: String?
    /// Cached summary of the primary selection (the global "selected video"
    /// shared by grid, list, and the map's right panel). Lets the detail loupe
    /// show a video that isn't in the loaded (paginated) `videos` page — e.g.
    /// one picked from the map, whose full geotagged set is loaded separately.
    @Published public var selectedVideo: VideoSummary?
    @Published public var isLoading = false
    /// True once the first video-list load has *completed* (success or failure).
    /// Until then the grid is in an unknown state — the catalog might be empty,
    /// or the first page might still be in flight — so the UI must show a loading
    /// view, NOT "No videos". Only after a completed load that returned zero is an
    /// empty grid genuinely empty. (The initial `loadVideos()` uses
    /// `showSpinner:false`, so `isLoading` alone can't carry this — see
    /// `loadCurrentPage`.) Shared by the iOS + macOS grids.
    @Published public private(set) var hasLoadedOnce = false
    @Published public var error: String?
    @Published public var totalCount: Int64 = 0
    @Published public var hasMore = false
    /// Bumped on every catalog change event (video added/modified/removed, scan
    /// or post-index completion). A detail/inspector view keys its per-video
    /// proxy fetch off this so a proxy created server-side (e.g. an HLS stream
    /// promoted to a durable proxy during playback) shows up without reopening.
    @Published public var catalogChangeTick = 0
    @Published public var searchQuery = ""

    // Playback output volume (0–100), shared across the detail loupe and the
    // inline card players so the level the user picks sticks for the session.
    @Published public var playbackVolume: Int = 100

    // Sort — loaded from UserDefaults so the user's last choice survives a
    // relaunch (written back in `setSort`). Defaults match a fresh install
    // (newest-indexed first).
    @Published public var sortBy: String = SortPrefs.loadField()
    @Published public var sortAscending: Bool = SortPrefs.loadAscending()

    // Library locations / filter
    @Published public var libraryLocations: [LibraryLocation] = []
    @Published public var selectedLocationPath: String = ""  // first selected ("" = all)
    // Multi-select: the set of selected library directories (empty = all). The
    // grid shows the union of their videos; the backend receives them as a
    // '\n'-joined string and matches a video under ANY of them.
    @Published public var selectedLocationPaths: [String] = []
    // Pivot for Shift-click range selection over the library list.
    private var locationAnchorPath: String?
    /// Value sent to the backend's `location_path` (single dir or '\n'-joined).
    private var locationFilterValue: String { selectedLocationPaths.joined(separator: "\n") }
    @Published public var rescanningPaths: Set<String> = []

    // ── Library subdirectory tree ───────────────────────────────────────────
    /// Directories the user has expanded (absolute paths). Children are fetched
    /// lazily into `subdirCache` the first time a directory is expanded.
    private var expandedDirs: Set<String> = []
    /// Cache of fetched children keyed by parent path; cleared on library reload
    /// because the tree is derived from (mutable) indexed video paths.
    private var subdirCache: [String: [Subdirectory]] = [:]
    /// The flattened, display-ordered rows the library panel renders: each
    /// location followed by its expanded subdirectories. Rebuilt by
    /// `rebuildLibraryRows()` whenever the tree changes.
    @Published public var visibleLibraryRows: [LibraryRow] = []

    /// Path of a directory the left panel should scroll into view — set by
    /// "Go to Folder in Library" after it reveals a deep subdirectory.
    @Published public var pendingLibraryScroll: String?

    // Keywords / tag filter
    @Published public var tags: [Tag] = []
    @Published public var filterTagId: String = ""  // "" = no filter

    // Collections
    @Published public var collections: [Collection] = []
    @Published public var selectedCollectionId: String? = nil
    /// The collection ID to pass to listVideos (nil for smart collections
    /// whose filters are applied via individual filter fields instead).
    private var collectionIdFilter: String? = nil

    // Library Filter — "metadata" mode. `metadataColumns` is the ordered list
    // of columns (camera/lens/exposure/iso by default); `facetColumns` holds
    // the server's per-column available values (1:1 with columns by index);
    // `availableMetadataKeys` populates each column's key picker.
    @Published public var libraryFilterMode: LibraryFilterMode = .clear
    @Published public var metadataColumns: [MetadataColumn] = LibraryFilterPrefs.loadColumns()
    @Published public var facetColumns: [MetadataFacetColumn] = []
    @Published public var availableMetadataKeys: [MetadataKeyInfo] = []

    // Lightroom-style user-mark filters.
    //   filterMinRating: 0 = no filter; 1..5 = "show videos with ≥ N stars".
    //   filterColorLabel: "" = no filter; otherwise exact-match the colour.
    @Published public var filterMinRating: Int32 = 0
    @Published public var filterColorLabel: String = ""

    // Tri-state presence filters (Library Filter "attribute" mode). .any = no
    // constraint; .yes = must have; .no = must not have.
    @Published public var filterHasLocation: AttributeFilterState = .any
    @Published public var filterHasKeywords: AttributeFilterState = .any
    @Published public var filterHasProxies: AttributeFilterState = .any
    @Published public var filterFullResolution: AttributeFilterState = .any
    @Published public var filterHasAudio: AttributeFilterState = .any
    @Published public var filterOrientation: OrientationFilterState = .any

    // Lightroom-style top-of-card stat slots. Exactly four entries — empty
    // string means "blank slot". Defaults to a sensible set on first launch;
    // overwritten by `loadGridSettings()` once the daemon answers.
    @Published public var topSlots: [String] = defaultGridTopSlots

    // Geographic proximity filter — set when the user taps a pin on the
    // global map. nil = no proximity filter active.
    @Published public var filterLocation: GeoFilter? = nil
    // Known locations (named places + unnamed coordinate clusters) with video
    // counts, for the Library Filter's "Location" mode. Refreshed via
    // `loadFilterLocations` from the full geotagged set so the list isn't itself
    // narrowed by the active filter.
    @Published public var filterLocationGroups: [LocationFilterGroup] = []

    // Snapshot of every geotagged video, refreshed when the user opens
    // the global map view.
    @Published public var videoLocations: [VideoLocation] = []

    /// Full VideoSummary for every geotagged video in the current filtered
    /// set — captured alongside `videoLocations` so the map view's right panel
    /// can render real video cards for a selected pin without an extra
    /// round-trip. Same filter and order as `videoLocations`.
    @Published public var geotaggedVideos: [VideoSummary] = []

    /// True while a filtered video-locations load is in flight. The map view's
    /// right panel shows a progress indicator (instead of an empty/stale list)
    /// while a clicked location's videos are still being resolved — important on
    /// a slow NAS catalog, where pins can appear before `geotaggedVideos` fills.
    @Published public var isLoadingVideoLocations = false

    // Catalog's user-defined named places (e.g. "Home"). Refreshed by
    // [loadNamedLocations]; used by [nameForLocation] to render named pins
    // on the map and named GPS readouts in the detail panel.
    @Published public var namedLocations: [NamedLocation] = []

    /// One-shot request for the global map to recenter on a coordinate (the
    /// `radiusKm` sets the zoom). Set by the detail/inspector "Show on Map" action
    /// so it focuses the *internal* map instead of opening an external maps app;
    /// the map consumes it (recenters) and clears it back to nil.
    @Published public var mapFocus: GeoFilter?

    // Thumbnails
    @Published public var thumbnails: [String: PlatformImage] = [:]

    // Scrub frames per video, loaded lazily on first hover.
    @Published public var scrubFrames: [String: [PlatformImage?]] = [:]
    private var scrubLoading: Set<String> = []
    /// Audio loudness-over-time series per video (the detail visuals panel's
    /// volume graph), each sample normalized to 0...1. An empty array means the
    /// video has no audio track. Cached for the session, like `scrubFrames`.
    @Published public var audioLoudness: [String: [Float]] = [:]
    private var audioLoudnessLoading: Set<String> = []

    // Higher-resolution detail thumbnails, populated only while the user dwells
    // on the detail view (see startHiResDetail). `hiResScrubFrames` mirrors
    // `scrubFrames` at the render resolution; `hiResPoster` is the upgraded
    // static (non-hover) frame. Filled incrementally and kept across a cancel
    // so returning to the video resumes rather than refetches.
    @Published public var hiResScrubFrames: [String: [PlatformImage?]] = [:]
    @Published public var hiResPoster: [String: PlatformImage] = [:]
    private var hiResTasks: [String: Task<Void, Never>] = [:]
    private var hiResWidth: [String: Int32] = [:]
    private let baseScrubWidth: Int32 = 320

    // Thumbnail fetch concurrency control — mirrors the scrubLoading pattern.
    // Caps simultaneous gRPC thumbnail streams so a large grid entering view
    // at once can't overwhelm the connection and drop some fetches silently.
    private let thumbnailSemaphore = ThumbnailSemaphore(8)
    private var thumbnailLoading: Set<String> = []

    // Stack expansion
    @Published public var expandedGroupIds: Set<String> = []
    @Published public var expandedGroupMembers: [String: [VideoSummary]] = [:]

    // Scan status / result banners
    @Published public var scanStatus: String?
    @Published public var scanResult: ScanResult?

    // Accumulated progress across all paths in a multi-path add+scan batch.
    public struct BatchScanProgress: Equatable {
        /// Total videos found across all paths scanned so far.
        public let found: Int
        /// Total videos indexed across all paths scanned so far.
        public let indexed: Int
        /// How many paths have finished scanning.
        public let pathsDone: Int
        /// Total number of paths in the batch.
        public let pathsTotal: Int
    }
    @Published public var batchScanProgress: BatchScanProgress?

    // Real-time updates from the server's file watcher.
    //
    // `liveUpdatesEnabled` reflects the most recent state we heard about
    // (initial value matches the server default; updated on the first
    // event after `startCatalogEventStream` connects). The toolbar uses
    // it to render the "live" indicator.
    @Published public var liveUpdatesEnabled: Bool = true
    /// Best-effort sticky banner — "Scanning …" — set by SCAN_STARTED and
    /// cleared by SCAN_COMPLETED. Distinct from `scanStatus` (which is
    /// driven by the user's own `addLibraryAndScan` flow) so the watcher
    /// can light up the banner without colliding with that progress
    /// reporter.
    @Published public var watcherBanner: String?

    /// Live progress for the daemon's background post-index pass (proxy
    /// detection / auto-grouping / camera-sensor lookups). `nil` when no
    /// pass is active. Driven by the `.postIndex*` catalog events and
    /// rendered in a background-activity panel so a long, CPU-heavy pass
    /// isn't invisible — including watcher-triggered passes that have no
    /// user-initiated scan banner.
    @Published public var postIndexProgress: PostIndexProgress?

    /// AsyncStream task owning the open `SubscribeCatalogEvents` connection.
    /// Cancelled in `stopCatalogEventStream()`; replaced if the stream
    /// drops and we reconnect.
    private var catalogEventsTask: Task<Void, Never>?

    /// Pending "clear the post-index panel" work item, scheduled on
    /// `.postIndexCompleted` and cancelled if another pass starts within
    /// the linger window. The watcher runs the pass in short waves;
    /// lingering bridges the gap so the panel reads as one continuous
    /// "busy" state instead of flickering.
    private var postIndexClearWorkItem: DispatchWorkItem?

    /// Debounce timer for refreshing the grid after a burst of watcher
    /// events. We get one event per file; refreshing the entire grid for
    /// each event would thrash. A 500 ms window collapses bursts into a
    /// single reload.
    private var watcherRefreshWorkItem: DispatchWorkItem?

    public struct ScanResult: Equatable {
        public let success: Bool
        public let message: String
        public let videosFound: Int
        public let videosIndexed: Int
    }

    private let repository = VideoRepository.shared
    private var currentPage = 0
    private let pageSize: Int32 = 50
    private var cancellables = Set<AnyCancellable>()
    private var searchDebounce: AnyCancellable?

    /// In-flight `listVideos` Task for the active page load. Cancelled when
    /// a new reload (filter change or background refresh) starts so a stale
    /// response from a prior selection / filter can't clobber the new one.
    /// Loads check `Task.isCancelled` after the await and bail without
    /// writing state.
    private var listLoadTask: Task<Void, Never>?

    public init() {
        // Debounced search
        searchDebounce = $searchQuery
            .debounce(for: 0.5, scheduler: DispatchQueue.main)
            .removeDuplicates()
            .sink { [weak self] _ in
                self?.reloadForFilterChange()
            }
    }

    // MARK: - Live updates

    /// Open (or re-open) the long-lived `SubscribeCatalogEvents` stream
    /// against the daemon. Idempotent — calling twice cancels the prior
    /// task and starts a fresh one. Cancellation happens automatically
    /// when the grid view-model is deallocated (deinit can't be async, so
    /// we rely on the task's own cleanup path).
    ///
    /// While live updates are enabled, the task retries on failure with
    /// exponential backoff (2, 4, 8, 16, 32, 60 s, then 60 s) and
    /// suppresses duplicate error logs so a daemon that stays offline
    /// doesn't spam the console with the same `Connection refused` error
    /// every two seconds. A single line is logged on reconnect.
    public func startCatalogEventStream() {
        catalogEventsTask?.cancel()
        catalogEventsTask = Task { [weak self] in
            guard let self else { return }
            var attempt = 0
            var lastErrorSignature: String?
            while !Task.isCancelled && self.liveUpdatesEnabled {
                do {
                    let stream = self.repository.subscribeCatalogEvents()
                    for try await event in stream {
                        if Task.isCancelled { break }
                        // First event after a (re)connect — announce we're
                        // back online (if we'd been logging failures) and
                        // reset the retry state.
                        if attempt > 0 || lastErrorSignature != nil {
                            NSLog("Catalog events stream reconnected after \(attempt) attempt(s)")
                            attempt = 0
                            lastErrorSignature = nil
                        }
                        self.handleCatalogEvent(event)
                    }
                    // Stream ended cleanly (server closed it without throwing).
                    lastErrorSignature = nil
                } catch is CancellationError {
                    break
                } catch {
                    if Task.isCancelled { break }
                    // Suppress duplicate log spam when the daemon stays
                    // offline: log the first occurrence of each distinct
                    // error, then stay quiet until either the error type
                    // changes or the stream reconnects.
                    let signature = String(describing: error)
                    if signature != lastErrorSignature {
                        NSLog("Catalog events stream ended: \(signature)")
                        lastErrorSignature = signature
                    }
                }
                if Task.isCancelled || !self.liveUpdatesEnabled { break }
                attempt += 1
                // Exponential backoff capped at 60 s: 2, 4, 8, 16, 32, 60, ...
                let delayMs = min(60_000, 1_000 * (1 << min(attempt, 6)))
                try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            }
        }
    }

    public func stopCatalogEventStream() {
        catalogEventsTask?.cancel()
        catalogEventsTask = nil
        postIndexClearWorkItem?.cancel()
        postIndexClearWorkItem = nil
        postIndexProgress = nil
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
            scheduleWatcherRefresh()  // also bumps catalogChangeTick (coalesced)
        case .videoAdded, .videoModified, .videoRemoved:
            scheduleWatcherRefresh()  // also bumps catalogChangeTick (coalesced)
        case .postIndexStarted, .postIndexProgress:
            // A background pass is running — cancel any pending clear and
            // show the latest snapshot. The backend now coalesces overlapping
            // passes into one stream, but still skip the @Published write when
            // the snapshot is unchanged so an identical event can't trigger a
            // needless banner re-render (@Published fires even on equal values).
            postIndexClearWorkItem?.cancel()
            postIndexClearWorkItem = nil
            if postIndexProgress != event.postIndex {
                postIndexProgress = event.postIndex
            }
        case .postIndexCompleted:
            // Refresh so newly-linked proxies / groups appear, then clear
            // the panel after a short linger to bridge consecutive watcher
            // waves into one continuous "busy" state.
            scheduleWatcherRefresh()
            postIndexClearWorkItem?.cancel()
            let work = DispatchWorkItem { [weak self] in
                self?.postIndexProgress = nil
            }
            postIndexClearWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.postIndexLingerSeconds, execute: work)
        case .unknown:
            break
        }
    }

    /// How long the post-index panel lingers after a pass completes before
    /// clearing — see `postIndexClearWorkItem`.
    private static let postIndexLingerSeconds: TimeInterval = 3.5

    // MARK: - Proxy management

    public struct ProxyCreationState {
        public let videoId: String
        public let progressPercent: Double
        public let status: String
        public let message: String
    }

    /// Surfaces the "Create proxy" sheet for `videoId`. The actual sheet
    /// is hosted by ContentView; we just publish the request via this
    /// `@Published` property and clear it once acknowledged.
    @Published public var proxyCreationVideoId: String?
    /// Per-video progress state for active proxy generation jobs.
    @Published public var activeProxyCreations: [String: ProxyCreationState] = [:]

    /// Called by the grid's right-click menu. Just publishes the
    /// request — the sheet hosted by ContentView observes
    /// `proxyCreationVideoId` and presents a resolution picker; once
    /// the user confirms, it calls `startProxyCreation`.
    public func requestCreateProxy(videoId: String) {
        proxyCreationVideoId = videoId
    }

    /// Dismiss the proxy picker without starting a job. Bound to the
    /// sheet's Cancel button.
    public func cancelProxyCreation() {
        proxyCreationVideoId = nil
    }

    /// Kick off proxy generation against the server. Awaits the stream
    /// to completion and refreshes the grid so the new proxy badge
    /// appears on the source. Surfaces per-video progress through
    /// `activeProxyCreations`. Also clears `proxyCreationVideoId` so
    /// the picker sheet dismisses.
    public func startProxyCreation(videoId: String, targetHeight: Int) {
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
    @Published public var playingVideoId: String? = nil
    /// Filesystem path to play — set to a proxy path when the video is
    /// oversize and a proxy exists. nil means "use video.openPath".
    @Published public var playingVideoPath: String? = nil

    /// Begin inline playback for the given video. Replaces any currently
    /// playing card.
    public func playVideo(videoId: String) {
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
    public func playVideoPreferProxy(videoId: String) {
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
    public func stopPlayback() {
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
            // Bump the per-video proxy-refresh tick here (coalesced) rather than
            // per event — during a big ingest/scan that fires one VideoAdded per
            // asset, an un-debounced tick would storm listProxies for any open
            // detail/inspector. One bump per quiet window is enough.
            self?.catalogChangeTick &+= 1
        }
        watcherRefreshWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    // MARK: - Loading

    /// Background refresh — used by scan-tick progress and watcher events.
    /// Re-fetches the first page and replaces in place; does NOT clear the
    /// current grid or show a full-page spinner, so periodic scan ticks
    /// don't make the UI flicker every few seconds.
    public func loadVideos() {
        reloadFromTop(showSpinner: false)
    }

    /// User-initiated filter / sort / selection change. Cancels any in-flight
    /// load, clears the grid, and shows a spinner so the user sees the change
    /// took effect immediately instead of staring at stale data while the new
    /// fetch is in flight.
    private func reloadForFilterChange() {
        // If the active selection no longer passes the (just-changed) filters,
        // drop it now — before reloadFromTop clears `videos` — so the loupe and
        // inspector don't strand the now-hidden video's details on screen.
        clearSelectionIfFilteredOut()
        reloadFromTop(showSpinner: true)
        // Every filter change also re-narrows the available metadata facets.
        scheduleFacetRefresh()
        // If we're viewing a smart collection, note whether the user has now
        // edited the filter away from what's saved (drives the Update banner).
        updateSmartDivergence()
    }

    /// - Parameter showSpinner: When true, clear the current grid and flip
    ///   into the loading state synchronously (filter change path). When
    ///   false, leave the existing rows in place and only swap them out once
    ///   the fetch returns (background refresh path).
    private func reloadFromTop(showSpinner: Bool) {
        if !showSpinner && isLoading {
            // A user-initiated filter change is still in flight (it set
            // isLoading = true). Don't let a scan-tick / watcher background
            // refresh interrupt it — that would re-cancel the user's load
            // and risk a spinner that never resolves under a busy scan.
            return
        }
        // Cancel any in-flight list load so a late response from the prior
        // selection / filter can't clobber the new one.
        listLoadTask?.cancel()
        currentPage = 0
        expandedGroupIds = []
        if showSpinner {
            videos = []
            totalCount = 0
            hasMore = false
            isLoading = true
        }
        error = nil
        // A spinner reload is user-initiated (filter / sort / collection change),
        // so re-center the selection once the new page lands; a silent background
        // refresh must leave the scroll position alone.
        let scrollToSelection = showSpinner
        listLoadTask = Task { [weak self] in
            await self?.loadCurrentPage(replace: true, scrollToSelection: scrollToSelection)
        }
    }

    public func loadMore() {
        guard hasMore && !isLoading else { return }
        currentPage += 1
        isLoading = true
        error = nil
        listLoadTask = Task { [weak self] in
            await self?.loadCurrentPage(replace: false, scrollToSelection: false)
        }
    }

    private func loadCurrentPage(replace: Bool, scrollToSelection: Bool = false) async {
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
                locationPath: locationFilterValue,
                filterTagIds: filterTagIds,
                geoFilter: geo,
                filterMinRating: filterMinRating,
                filterColorLabel: filterColorLabel,
                metadataFilters: activeMetadataFilters(),
                collectionId: collectionIdFilter,
                hasLocation: filterHasLocation,
                hasKeywords: filterHasKeywords,
                hasProxies: filterHasProxies,
                fullResolution: filterFullResolution
            )
            // A newer reload may have superseded us while listVideos was in
            // flight; if so, drop the result on the floor so it can't overwrite
            // the fresh selection's data.
            if Task.isCancelled { return }
            NSLog("[GridViewModel] listVideos returned \(results.count) of \(total) (page \(currentPage), replace=\(replace))")
            if replace {
                videos = results
            } else {
                videos.append(contentsOf: results)
            }
            totalCount = total
            hasMore = Int64(videos.count) < total
            isLoading = false
            hasLoadedOnce = true

            // An empty result means nothing is selectable. Drop any lingering
            // selection — even one pinned by detail mode, which
            // clearSelectionIfFilteredOut deliberately skips — so the inspector
            // and loupe don't keep showing the previously selected video after,
            // e.g., clicking an empty smart collection ("no videos match" in
            // the middle, stale details on the right).
            if replace && videos.isEmpty && selectedVideoId != nil {
                clearSelection()
            }

            // A user filter/collection reload that kept a selection: ask the grid
            // to re-center it, since its row usually moved under the new filter.
            if replace, scrollToSelection, let id = selectedVideoId,
               videos.contains(where: { $0.id == id }) {
                scrollToSelectionTick += 1
            }

            // Keep map locations in sync with the active grid filters.
            // Debounced (see scheduleVideoLocationsRefresh) so a burst of
            // filter/selection changes doesn't kick off a full-library
            // re-pagination per change.
            scheduleVideoLocationsRefresh()
        } catch {
            if Task.isCancelled { return }
            NSLog("[GridViewModel] listVideos FAILED: \(error)")
            self.error = "Failed to load videos: \(error.localizedDescription)"
            isLoading = false
            hasLoadedOnce = true
        }
    }

    // MARK: - Sort / filter

    public func setSort(_ field: String, ascending: Bool) {
        sortBy = field
        sortAscending = ascending
        // Persist so the next launch restores this sort (see property init).
        SortPrefs.save(field: field, ascending: ascending)
        reloadForFilterChange()
    }

    /// Plain click: replace the selection with this single directory (or clear
    /// it for the "All Videos" entry, path == "").
    public func setLocationFilter(_ path: String) {
        let paths = path.isEmpty ? [] : [path]
        applyLocationSelection(paths, anchor: path.isEmpty ? nil : path)
    }

    /// "Go to Folder in Library" for a specific video: select the *deepest*
    /// directory that contains it — its own parent folder, the lowest node in
    /// the tree — rather than just the top-level library location. Every
    /// ancestor is expanded in the left panel so that row is revealed (fetching
    /// children as needed), and the panel is asked to scroll to it. Falls back
    /// to a plain filter when the video isn't under a known library location.
    public func goToFolderForVideo(_ videoPath: String) {
        guard let slash = videoPath.lastIndex(of: "/") else { return }
        let dir = String(videoPath[..<slash])
        guard !dir.isEmpty else { return }
        // Containing top-level location = longest path that `dir` sits under.
        let loc = libraryLocations
            .filter { dir == $0.path || dir.hasPrefix($0.path.hasSuffix("/") ? $0.path : $0.path + "/") }
            .max(by: { $0.path.count < $1.path.count })
        guard let loc = loc else {
            setLocationFilter(dir)
            pendingLibraryScroll = dir
            return
        }
        Task {
            // Expand every node from the location down to `dir`'s parent so the
            // `dir` row is revealed (expanding a node shows its children).
            let chain = directoryChain(loc.path, dir)
            for ancestor in chain.dropLast() {
                expandedDirs.insert(ancestor)
                if subdirCache[ancestor] == nil {
                    do {
                        subdirCache[ancestor] = try await repository.listSubdirectories(ancestor)
                    } catch {
                        NSLog("Failed to list subdirectories of \(ancestor): \(error)")
                    }
                }
            }
            rebuildLibraryRows()
            setLocationFilter(dir)
            pendingLibraryScroll = dir
        }
    }

    /// Paths from `locPath` down to `dir` inclusive — `[loc, loc/a, loc/a/b, …,
    /// dir]`. `dir` must equal `locPath` or sit beneath it.
    private func directoryChain(_ locPath: String, _ dir: String) -> [String] {
        let base = locPath.hasSuffix("/") ? String(locPath.dropLast()) : locPath
        if dir == base || dir == locPath { return [locPath] }
        var rel = String(dir.dropFirst(base.count))
        while rel.hasPrefix("/") { rel.removeFirst() }
        var chain = [locPath]
        var cur = base
        for seg in rel.split(separator: "/") where !seg.isEmpty {
            cur += "/" + seg
            chain.append(cur)
        }
        return chain
    }

    /// Cmd-click: toggle this directory's membership in the selection.
    public func toggleLocationFilter(_ path: String) {
        guard !path.isEmpty else { setLocationFilter(""); return }
        var next = selectedLocationPaths
        if let i = next.firstIndex(of: path) { next.remove(at: i) } else { next.append(path) }
        applyLocationSelection(next, anchor: path)
    }

    /// Shift-click: select every directory between the anchor and [path]
    /// (inclusive) in the library's display order.
    public func selectLocationRange(_ path: String) {
        guard !path.isEmpty else { setLocationFilter(""); return }
        // Range over the flattened *visible* tree (locations + expanded
        // subdirs), so Shift-click spans whatever is on screen.
        let order = visibleLibraryRows.map { $0.path }
        let anchor = locationAnchorPath ?? selectedLocationPaths.first ?? path
        guard let ai = order.firstIndex(of: anchor), let ti = order.firstIndex(of: path) else {
            setLocationFilter(path); return
        }
        let slice = ai <= ti ? order[ai...ti] : order[ti...ai]
        // Anchor preserved so successive Shift-clicks pivot from the same start.
        applyLocationSelection(Array(slice), anchor: anchor, keepAnchor: true)
    }

    private func applyLocationSelection(_ paths: [String], anchor: String?, keepAnchor: Bool = false) {
        guard paths != selectedLocationPaths else { return }
        selectedLocationPaths = paths
        selectedLocationPath = paths.first ?? ""
        if !keepAnchor { locationAnchorPath = anchor }
        reloadForFilterChange()
    }

    // MARK: - Collections

    public func loadCollections() {
        Task {
            do {
                collections = try await repository.listCollections().sorted { $0.name.lowercased() < $1.name.lowercased() }
                refreshSmartCollectionCounts()
            } catch {
                NSLog("Failed to load collections: \(error)")
            }
        }
    }

    /// Match counts for smart collections, keyed by collection id. Normal
    /// collections carry their own `videoCount` from the members join; smart
    /// collections have no members, so the left panel always showed 0 — we
    /// compute their count by running their saved filter (limit=1, read total).
    @Published public var smartCollectionCounts: [String: Int64] = [:]

    /// Re-count every smart collection. Runs sequentially (gentle on the NAS)
    /// and publishes each count as it lands so badges fill in progressively.
    private func refreshSmartCollectionCounts() {
        let smarts = collections.filter { $0.isSmart && !$0.filterJson.isEmpty }
        guard !smarts.isEmpty else { smartCollectionCounts = [:]; return }
        Task {
            var counts: [String: Int64] = [:]
            for c in smarts {
                guard let f = SmartCollectionFilters.from(json: c.filterJson) else { continue }
                do {
                    let result = try await repository.listVideos(
                        limit: 1,
                        offset: 0,
                        searchQuery: f.searchQuery,
                        locationPath: f.locationPaths.joined(separator: "\n"),
                        filterTagIds: f.tagIds,
                        geoFilter: f.hasGeo ? (latitude: f.geoLat, longitude: f.geoLon, radiusKm: f.geoRadiusKm) : nil,
                        filterMinRating: f.minRating,
                        filterColorLabel: f.colorLabel,
                        metadataFilters: f.columns
                            .filter { !$0.values.isEmpty }
                            .map { (key: $0.key, value: $0.values.joined(separator: metadataValueSeparator)) }
                            + derivedAttributeMetadataFilters(hasAudio: f.hasAudio, orientation: f.orientation),
                        hasLocation: f.hasLocation,
                        hasKeywords: f.hasKeywords,
                        hasProxies: f.hasProxies,
                        fullResolution: f.fullResolution
                    )
                    counts[c.id] = result.totalCount
                    smartCollectionCounts = counts
                } catch {
                    NSLog("Failed to count smart collection \(c.id): \(error)")
                }
            }
            smartCollectionCounts = counts
        }
    }

    /// Id of the smart collection whose filters are currently expanded into the
    /// live filter fields, or nil. A smart collection isn't a `collection_id`
    /// constraint — its saved filters are written into the bar — so we must
    /// remember it to undo those writes when the user navigates away.
    private var activeSmartCollectionId: String?

    /// Undo the filter fields a smart collection expanded into the bar, so they
    /// don't strand the user on an empty grid after they leave it (e.g. after
    /// deleting a smart collection that matched nothing — the bug this fixes).
    /// No-op when no smart collection is currently applied.
    /// Snapshot of the live filter bar taken just before a smart collection
    /// overwrote it, so leaving the smart collection restores the user's
    /// previous filter rather than clearing everything.
    private struct FilterSnapshot {
        var columns: [MetadataColumn]
        var minRating: Int32
        var colorLabel: String
        var tagId: String
        var searchQuery: String
        /// Map-proximity filter, or nil when none.
        var geo: GeoFilter?
        var locationPaths: [String]
        var hasLocation: AttributeFilterState
        var hasKeywords: AttributeFilterState
        var hasProxies: AttributeFilterState
        var fullResolution: AttributeFilterState
        var hasAudio: AttributeFilterState
        var orientation: OrientationFilterState
    }
    private var preSmartFilterSnapshot: FilterSnapshot?

    private func clearActiveSmartCollectionFilters() {
        guard activeSmartCollectionId != nil else { return }
        activeSmartCollectionId = nil
        if let snap = preSmartFilterSnapshot {
            preSmartFilterSnapshot = nil
            filterMinRating = snap.minRating
            filterColorLabel = snap.colorLabel
            filterTagId = snap.tagId
            searchQuery = snap.searchQuery
            metadataColumns = snap.columns
            filterLocation = snap.geo
            selectedLocationPaths = snap.locationPaths
            selectedLocationPath = snap.locationPaths.first ?? ""
            filterHasLocation = snap.hasLocation
            filterHasKeywords = snap.hasKeywords
            filterHasProxies = snap.hasProxies
            filterFullResolution = snap.fullResolution
            filterHasAudio = snap.hasAudio
            filterOrientation = snap.orientation
            LibraryFilterPrefs.saveColumns(metadataColumns)
            return
        }
        filterMinRating = 0
        filterColorLabel = ""
        filterTagId = ""
        searchQuery = ""
        filterLocation = nil
        selectedLocationPaths = []
        selectedLocationPath = ""
        filterHasLocation = .any
        filterHasKeywords = .any
        filterHasProxies = .any
        filterFullResolution = .any
        filterHasAudio = .any
        filterOrientation = .any
        for i in metadataColumns.indices where !metadataColumns[i].values.isEmpty {
            metadataColumns[i].values = []
            metadataColumns[i].anchor = ""
        }
        LibraryFilterPrefs.saveColumns(metadataColumns)
    }

    public func setCollectionFilter(_ id: String?) {
        // Already viewing this collection (or already cleared) — keep the
        // current results rather than reloading and flashing the spinner.
        // Library rows clear the collection on every click, so without this
        // guard re-selecting the current location would still reload.
        guard id != selectedCollectionId else { return }
        // Leaving whatever we were viewing: if it was a smart collection, undo
        // the filters it injected so they don't linger into the next view.
        clearActiveSmartCollectionFilters()
        selectedCollectionId = id
        guard let id = id, let col = collections.first(where: { $0.id == id }) else {
            collectionIdFilter = nil
            reloadForFilterChange()
            return
        }
        if col.isSmart, !col.filterJson.isEmpty,
           let f = SmartCollectionFilters.from(json: col.filterJson) {
            // Snapshot the current (pre-smart-collection) filter so leaving the
            // smart collection restores it. clearActiveSmartCollectionFilters
            // ran above, so the live state here is the user's own filter.
            preSmartFilterSnapshot = FilterSnapshot(
                columns: metadataColumns,
                minRating: filterMinRating,
                colorLabel: filterColorLabel,
                tagId: filterTagId,
                searchQuery: searchQuery,
                geo: filterLocation,
                locationPaths: selectedLocationPaths,
                hasLocation: filterHasLocation,
                hasKeywords: filterHasKeywords,
                hasProxies: filterHasProxies,
                fullResolution: filterFullResolution,
                hasAudio: filterHasAudio,
                orientation: filterOrientation
            )
            // Smart collection: apply its saved filters. Metadata columns map
            // onto the bar's columns; the rest stay as dedicated fields.
            collectionIdFilter = nil
            applySmartFiltersToBar(f)
            activeSmartCollectionId = id
        } else {
            collectionIdFilter = id
        }
        reloadForFilterChange()
    }

    public func createCollection(name: String, isSmart: Bool, filterJson: String = "") {
        Task {
            do {
                _ = try await repository.createCollection(name: name, isSmart: isSmart, filterJson: filterJson)
                loadCollections()
            } catch {
                NSLog("Failed to create collection: \(error)")
            }
        }
    }

    public func deleteCollection(id: String) {
        Task {
            do {
                _ = try await repository.deleteCollection(id: id)
                if selectedCollectionId == id { setCollectionFilter(nil) }
                loadCollections()
            } catch {
                NSLog("Failed to delete collection: \(error)")
            }
        }
    }

    public func addToCollection(videoIds: [String], collectionId: String) {
        Task {
            do {
                _ = try await repository.addToCollection(videoIds: videoIds, collectionId: collectionId)
                loadCollections()
            } catch {
                NSLog("Failed to add to collection: \(error)")
            }
        }
    }

    public func removeFromCollection(videoIds: [String], collectionId: String) {
        Task {
            do {
                _ = try await repository.removeFromCollection(videoIds: videoIds, collectionId: collectionId)
                loadCollections()
            } catch {
                NSLog("Failed to remove from collection: \(error)")
            }
        }
    }

    /// The live Library Filter as a `SmartCollectionFilters` — what would be
    /// saved if the user created/updated a smart collection right now.
    private func currentSmartFilters() -> SmartCollectionFilters {
        // Capture every narrowed metadata column (camera, lens, codec, year,
        // iso, exposure, … — not just the four the old format knew). The
        // "location" virtual key is captured as geo below, not as a column.
        let columns: [SmartCollectionColumn] = metadataColumns
            .filter { !$0.key.isEmpty && $0.key != locationMetadataKey && !$0.values.isEmpty }
            .map { SmartCollectionColumn(key: $0.key, values: $0.values.sorted(), negate: $0.negate) }
        return SmartCollectionFilters(
            columns: columns,
            minRating: filterMinRating,
            colorLabel: filterColorLabel,
            tagIds: filterTagId.isEmpty ? [] : [filterTagId],
            searchQuery: searchQuery,
            geoLat: filterLocation?.latitude ?? 0.0,
            geoLon: filterLocation?.longitude ?? 0.0,
            geoRadiusKm: filterLocation?.radiusKm ?? 0.0,
            locationPaths: selectedLocationPaths,
            hasLocation: filterHasLocation,
            hasKeywords: filterHasKeywords,
            hasProxies: filterHasProxies,
            fullResolution: filterFullResolution,
            hasAudio: filterHasAudio,
            orientation: filterOrientation
        )
    }

    public func buildSmartCollectionFilterJson() -> String { currentSmartFilters().toJson() }

    // MARK: Edit-while-viewing-a-smart-collection ("ask before modifying")

    /// Name of the active smart collection whose live filter the user has since
    /// edited (so it no longer matches what's saved), or nil. Drives the
    /// "Update / Revert" banner. Switching away still reverts via the snapshot.
    @Published public var divergedSmartCollection: String?

    /// Order-independent signature of a filter set, for comparing the live bar
    /// to a smart collection's saved spec without depending on column order.
    private func smartSig(_ f: SmartCollectionFilters) -> String {
        let cols = f.columns
            .map { "\($0.key)=\($0.values.sorted().joined(separator: ","))" }
            .sorted()
            .joined(separator: ";")
        return "\(cols)|\(f.minRating)|\(f.colorLabel)|\(f.searchQuery)|\(f.tagIds.sorted())|\(f.geoLat)|\(f.geoLon)|\(f.geoRadiusKm)|\(f.locationPaths.sorted())|\(f.hasLocation.rawValue)|\(f.hasKeywords.rawValue)|\(f.hasProxies.rawValue)|\(f.fullResolution.rawValue)|\(f.hasAudio.rawValue)|\(f.orientation.rawValue)"
    }

    /// Recompute whether the live filter has diverged from the active smart
    /// collection. Called after every filter change (via reloadForFilterChange).
    private func updateSmartDivergence() {
        guard let id = activeSmartCollectionId,
              let col = collections.first(where: { $0.id == id }) else {
            divergedSmartCollection = nil
            return
        }
        let saved = SmartCollectionFilters.from(json: col.filterJson) ?? SmartCollectionFilters()
        divergedSmartCollection = smartSig(currentSmartFilters()) != smartSig(saved) ? col.name : nil
    }

    /// Persist the live filter into the active smart collection. The core has no
    /// UpdateCollection RPC, so this re-creates the collection (smart collections
    /// have no members to preserve) and re-points the active/selected id.
    public func updateActiveSmartCollection() {
        guard let id = activeSmartCollectionId,
              let name = collections.first(where: { $0.id == id })?.name else { return }
        let json = buildSmartCollectionFilterJson()
        Task {
            do {
                _ = try await repository.deleteCollection(id: id)
                let created = try await repository.createCollection(name: name, isSmart: true, filterJson: json)
                collections = try await repository.listCollections().sorted { $0.name.lowercased() < $1.name.lowercased() }
                let newId = created?.id ?? collections.first(where: { $0.name == name && $0.isSmart })?.id
                activeSmartCollectionId = newId
                selectedCollectionId = newId
                refreshSmartCollectionCounts()
                divergedSmartCollection = nil
            } catch {
                NSLog("Failed to update smart collection: \(error)")
            }
        }
    }

    /// Discard the user's edits and restore the active smart collection's saved
    /// filter.
    public func revertActiveSmartCollection() {
        guard let id = activeSmartCollectionId,
              let col = collections.first(where: { $0.id == id }),
              let f = SmartCollectionFilters.from(json: col.filterJson) else { return }
        applySmartFiltersToBar(f)
        reloadForFilterChange()
    }

    /// Write a saved filter set into the live Library Filter bar (metadata
    /// columns + dedicated rating/color/search/tag/geo fields). Shared by
    /// entering a smart collection, reverting edits, and deleting a criterion.
    private func applySmartFiltersToBar(_ f: SmartCollectionFilters) {
        metadataColumns = Self.metadataColumns(from: f)
        LibraryFilterPrefs.saveColumns(metadataColumns)
        filterMinRating = f.minRating
        filterColorLabel = f.colorLabel
        searchQuery = f.searchQuery
        filterTagId = f.tagIds.first ?? ""
        filterLocation = f.hasGeo ? GeoFilter(latitude: f.geoLat, longitude: f.geoLon, radiusKm: f.geoRadiusKm) : nil
        // Library-folder selection and the tri-state attribute filters are part
        // of the collection too, so a smart collection fully restores the bar.
        selectedLocationPaths = f.locationPaths
        selectedLocationPath = f.locationPaths.first ?? ""
        filterHasLocation = f.hasLocation
        filterHasKeywords = f.hasKeywords
        filterHasProxies = f.hasProxies
        filterFullResolution = f.fullResolution
        filterHasAudio = f.hasAudio
        filterOrientation = f.orientation
        // Reveal whichever editor holds the collection's filters so the bar isn't
        // stuck on "Clear" (which hides everything).
        libraryFilterMode = Self.smartFilterMode(f)
    }

    /// Pick the Library Filter bar mode that surfaces a smart collection's
    /// filters: metadata columns, then attributes, then text, else Clear.
    private static func smartFilterMode(_ f: SmartCollectionFilters) -> LibraryFilterMode {
        if !f.columns.isEmpty { return .metadata }
        if f.hasLocation != .any || f.hasKeywords != .any || f.hasProxies != .any || f.fullResolution != .any
            || f.hasAudio != .any || f.orientation != .any {
            return .attribute
        }
        if !f.searchQuery.isEmpty { return .text }
        return .clear
    }

    /// Leave the active smart collection and clear every filter so the grid
    /// shows the whole catalog. Backs the smart-collection banner's ✕ button.
    /// Unlike navigating away (which restores the pre-collection filter), this
    /// is an explicit "show everything" reset, so it drops the snapshot too.
    public func clearSmartCollectionShowAll() {
        preSmartFilterSnapshot = nil
        activeSmartCollectionId = nil
        divergedSmartCollection = nil
        selectedCollectionId = nil
        collectionIdFilter = nil
        searchQuery = ""
        filterMinRating = 0
        filterColorLabel = ""
        filterLocation = nil
        filterHasLocation = .any
        filterHasKeywords = .any
        filterHasProxies = .any
        filterFullResolution = .any
        filterHasAudio = .any
        filterOrientation = .any
        filterTagId = ""
        selectedLocationPaths = []
        selectedLocationPath = ""
        for i in metadataColumns.indices where !metadataColumns[i].values.isEmpty {
            metadataColumns[i].values = []
            metadataColumns[i].anchor = ""
        }
        LibraryFilterPrefs.saveColumns(metadataColumns)
        libraryFilterMode = .clear
        reloadForFilterChange()
    }

    /// Remove a single rule from a smart collection's *saved* definition (after
    /// the user confirms), re-create it without that rule, and — if it's the one
    /// being viewed — re-apply the broadened filter to the live bar. The core has
    /// no UpdateCollection RPC, so this re-creates and re-points ids, like
    /// `updateActiveSmartCollection`.
    public func removeSmartCollectionCriterion(_ col: Collection, _ criterion: SmartCriterion) {
        guard var f = SmartCollectionFilters.from(json: col.filterJson) else { return }
        switch criterion {
        case .column(let key): f.columns.removeAll { $0.key == key }
        case .minRating: f.minRating = 0
        case .colorLabel: f.colorLabel = ""
        case .keywords: f.tagIds = []
        case .geo: f.geoLat = 0; f.geoLon = 0; f.geoRadiusKm = 0
        case .folder: f.locationPaths = []
        case .hasLocation: f.hasLocation = .any
        case .hasKeywords: f.hasKeywords = .any
        case .hasProxies: f.hasProxies = .any
        case .fullResolution: f.fullResolution = .any
        case .hasAudio: f.hasAudio = .any
        case .orientation: f.orientation = .any
        }
        let name = col.name
        let oldId = col.id
        let json = f.toJson()
        Task {
            do {
                _ = try await repository.deleteCollection(id: oldId)
                let created = try await repository.createCollection(name: name, isSmart: true, filterJson: json)
                collections = try await repository.listCollections().sorted { $0.name.lowercased() < $1.name.lowercased() }
                let newId = created?.id ?? collections.first(where: { $0.name == name && $0.isSmart })?.id
                if selectedCollectionId == oldId || activeSmartCollectionId == oldId {
                    activeSmartCollectionId = newId
                    selectedCollectionId = newId
                    applySmartFiltersToBar(f)
                    reloadForFilterChange()
                }
                refreshSmartCollectionCounts()
                divergedSmartCollection = nil
            } catch {
                NSLog("Failed to remove smart collection criterion: \(error)")
            }
        }
    }

    /// Build metadata columns from a smart collection's saved column filters.
    /// Falls back to the defaults when the saved filter set is empty.
    private static func metadataColumns(from f: SmartCollectionFilters) -> [MetadataColumn] {
        let cols = f.columns.map { MetadataColumn(key: $0.key, values: Set($0.values), negate: $0.negate) }
        return cols.isEmpty ? defaultMetadataColumns : cols
    }

    /// Human-readable selection criteria for a smart `collection`, resolving
    /// tag IDs to names. An empty array means the collection constrains nothing
    /// (it would match every video). Shown in the details panel when no card is
    /// selected, so the user can see why a smart collection gathers what it does.
    public func smartCollectionCriteria(_ collection: Collection) -> [SmartCriterionRow] {
        guard let f = SmartCollectionFilters.from(json: collection.filterJson) else { return [] }
        // Friendly label for a metadata key; falls back to a capitalised key
        // for registry keys we don't special-case.
        func label(_ key: String) -> String {
            switch key {
            case "camera": return "Camera"; case "lens": return "Lens"; case "codec": return "Codec"
            case "year": return "Year"; case "iso": return "ISO"; case "exposure": return "Exposure"
            case "fps": return "FPS"; case "resolution": return "Resolution"; case "colorspace": return "Color space"
            case "aspect": return "Aspect ratio"
            default: return key.prefix(1).uppercased() + key.dropFirst()
            }
        }
        var out: [SmartCriterionRow] = []
        for c in f.columns where !c.values.isEmpty {
            // "is not" columns read "not <values>" so the inversion is visible.
            let prefix = c.negate ? "not " : ""
            // A "keyword" column holds tag ids; resolve them to names so the
            // panel reads "Keywords: astro", not the raw tag uuid.
            if c.key == "keyword" {
                let names = c.values.map { id in tags.first(where: { $0.id == id })?.name ?? id }
                out.append(SmartCriterionRow(label: "Keywords", value: prefix + names.joined(separator: ", "), criterion: .column("keyword")))
            } else {
                out.append(SmartCriterionRow(label: label(c.key), value: prefix + c.values.joined(separator: ", "), criterion: .column(c.key)))
            }
        }
        if f.minRating > 0 { out.append(SmartCriterionRow(label: "Rating", value: "\(f.minRating)+ stars", criterion: .minRating)) }
        if !f.colorLabel.isEmpty { out.append(SmartCriterionRow(label: "Color", value: f.colorLabel.capitalized, criterion: .colorLabel)) }
        if !f.tagIds.isEmpty {
            let names = f.tagIds.map { id in tags.first(where: { $0.id == id })?.name ?? id }
            out.append(SmartCriterionRow(label: "Keywords", value: names.joined(separator: ", "), criterion: .keywords))
        }
        if f.hasGeo {
            out.append(SmartCriterionRow(label: "Location",
                        value: String(format: "within %.1f km of %.4f, %.4f", f.geoRadiusKm, f.geoLat, f.geoLon),
                        criterion: .geo))
        }
        if !f.locationPaths.isEmpty {
            let names = f.locationPaths.map { ($0 as NSString).lastPathComponent }
            out.append(SmartCriterionRow(label: "Folder", value: names.joined(separator: ", "), criterion: .folder))
        }
        // Tri-state attribute filters — shown only when constrained (Yes / No).
        func attrValue(_ s: AttributeFilterState) -> String? {
            switch s { case .yes: return "Yes"; case .no: return "No"; case .any: return nil }
        }
        if let v = attrValue(f.hasLocation) { out.append(SmartCriterionRow(label: "Has location", value: v, criterion: .hasLocation)) }
        if let v = attrValue(f.hasKeywords) { out.append(SmartCriterionRow(label: "Has keywords", value: v, criterion: .hasKeywords)) }
        if let v = attrValue(f.hasProxies) { out.append(SmartCriterionRow(label: "Has proxies", value: v, criterion: .hasProxies)) }
        if let v = attrValue(f.fullResolution) { out.append(SmartCriterionRow(label: "Full resolution", value: v, criterion: .fullResolution)) }
        if let v = attrValue(f.hasAudio) { out.append(SmartCriterionRow(label: "Has audio", value: v, criterion: .hasAudio)) }
        if f.orientation != .any {
            out.append(SmartCriterionRow(label: "Orientation", value: f.orientation.displayName, criterion: .orientation))
        }
        return out
    }

    /// Title + detail for the grid/list empty state, tailored to *why* the grid
    /// is empty. Keeps grid and list in sync and avoids the old advice to "add a
    /// library location", which is wrong inside an empty collection.
    public func emptyStateMessage() -> (title: String, detail: String) {
        let col = selectedCollectionId.flatMap { id in collections.first(where: { $0.id == id }) }
        if let col = col, col.isSmart {
            return ("No videos match this smart collection",
                    "Its selection rules are listed in the details panel. Edit the collection to change what it gathers.")
        }
        if col != nil {
            return ("This collection is empty",
                    "Add videos by selecting them in the grid and choosing “Add to Collection”.")
        }
        if hasActiveLibraryFilter() {
            return ("No videos match the current filter",
                    "Choose “Clear” in the filter bar to show all videos again.")
        }
        if libraryLocations.isEmpty {
            return ("Your library is empty",
                    "Click the + button at the top of the Library panel to add a folder.")
        }
        return ("No videos found", "")
    }

    /// Whether any Library Filter constraint (text / attribute / metadata /
    /// keyword / map proximity) is currently narrowing the grid.
    private func hasActiveLibraryFilter() -> Bool {
        !searchQuery.isEmpty ||
            filterMinRating > 0 ||
            !filterColorLabel.isEmpty ||
            filterHasLocation != .any ||
            filterHasKeywords != .any ||
            filterHasProxies != .any ||
            filterFullResolution != .any ||
            filterHasAudio != .any ||
            filterOrientation != .any ||
            !filterTagId.isEmpty ||
            filterLocation != nil ||
            metadataColumns.contains { !$0.values.isEmpty }
    }

    // MARK: - Keywords / tags

    public func loadTags() {
        Task {
            do {
                tags = try await repository.listTags().sorted { $0.name.lowercased() < $1.name.lowercased() }
            } catch {
                NSLog("Failed to load tags: \(error)")
            }
        }
    }

    public func setTagFilter(_ tagId: String) {
        guard filterTagId != tagId else { return }
        filterTagId = tagId
        reloadForFilterChange()
    }

    // MARK: - Library Filter: attribute mode (rating + colour)

    public func setMinRatingFilter(_ n: Int32) {
        guard filterMinRating != n else { return }
        filterMinRating = n
        reloadForFilterChange()
    }

    public func setColorLabelFilter(_ label: String) {
        guard filterColorLabel != label else { return }
        filterColorLabel = label
        reloadForFilterChange()
    }

    public func setHasLocationFilter(_ state: AttributeFilterState) {
        guard filterHasLocation != state else { return }
        filterHasLocation = state
        reloadForFilterChange()
    }

    public func setHasKeywordsFilter(_ state: AttributeFilterState) {
        guard filterHasKeywords != state else { return }
        filterHasKeywords = state
        reloadForFilterChange()
    }

    public func setHasProxiesFilter(_ state: AttributeFilterState) {
        guard filterHasProxies != state else { return }
        filterHasProxies = state
        reloadForFilterChange()
    }

    public func setFullResolutionFilter(_ state: AttributeFilterState) {
        guard filterFullResolution != state else { return }
        filterFullResolution = state
        reloadForFilterChange()
    }

    public func setHasAudioFilter(_ state: AttributeFilterState) {
        guard filterHasAudio != state else { return }
        filterHasAudio = state
        reloadForFilterChange()
    }

    public func setOrientationFilter(_ state: OrientationFilterState) {
        guard filterOrientation != state else { return }
        filterOrientation = state
        reloadForFilterChange()
    }

    // MARK: - Library Filter: mode + metadata columns

    /// Switch which Library Filter editor is visible. The Clear button calls
    /// `clearLibraryFilter()` directly (it's a momentary action, not a mode).
    public func setLibraryFilterMode(_ mode: LibraryFilterMode) {
        if mode == .clear { clearLibraryFilter(); return }
        guard libraryFilterMode != mode else { return }
        libraryFilterMode = mode
        if mode == .metadata { scheduleFacetRefresh() }
    }

    /// The active metadata-column constraints sent to the daemon. Each column
    /// with a non-empty selection becomes one filter whose value is its selected
    /// tokens joined by `metadataValueSeparator`; the daemon OR-matches them.
    private func activeMetadataFilters() -> [(key: String, value: String)] {
        metadataColumns
            // "location" is a client-side virtual key applied via the geo
            // filter, not a MetadataFilter; never send it over the wire.
            .filter { !$0.key.isEmpty && $0.key != locationMetadataKey && !$0.values.isEmpty }
            .map { col -> (key: String, value: String) in
                let joined = col.values.sorted().joined(separator: metadataValueSeparator)
                // "is not" columns prefix the value so the daemon inverts the match.
                return (key: col.key, value: col.negate ? metadataNegatePrefix + joined : joined)
            }
            // has-audio / orientation have no proto field; they ride here as
            // "has_audio" / "orientation" metadata filters (see the daemon's
            // build_filter_clauses).
            + derivedAttributeMetadataFilters(hasAudio: filterHasAudio, orientation: filterOrientation)
    }

    /// The wire metadata filters that encode the has-audio and orientation
    /// attribute choices. Appended to the column-derived filters in every
    /// ListVideos request path so the grid and smart-collection counts agree.
    private func derivedAttributeMetadataFilters(
        hasAudio: AttributeFilterState, orientation: OrientationFilterState
    ) -> [(key: String, value: String)] {
        var out: [(key: String, value: String)] = []
        switch hasAudio {
        case .yes: out.append((key: "has_audio", value: "yes"))
        case .no:  out.append((key: "has_audio", value: "no"))
        case .any: break
        }
        switch orientation {
        case .portrait:  out.append((key: "orientation", value: "portrait"))
        case .landscape: out.append((key: "orientation", value: "landscape"))
        case .any: break
        }
        return out
    }

    /// Facet column matched to `metadataColumns[index]` by position (nil while
    /// a refresh is in flight or for placeholder columns).
    public func facetColumn(at index: Int) -> MetadataFacetColumn? {
        facetColumns.indices.contains(index) ? facetColumns[index] : nil
    }

    /// Handle a click on facet `token` in metadata column `index`. `token` == ""
    /// is the "All" row (clears the column). `shift` / `toggle` carry the
    /// keyboard modifiers (toggle = Cmd or Ctrl on macOS).
    ///
    /// Lightroom / Finder multi-select semantics:
    ///  - "All" selected (empty set) + any value → select only that value,
    ///    regardless of modifiers.
    ///  - toggle-click → flip that value's membership; empties back to "All".
    ///  - shift-click → select the contiguous range from the anchor to the
    ///    clicked value (in the displayed facet order).
    ///  - plain click → select only that value.
    /// Selected values within one column are OR-ed by the daemon.
    public func onMetadataValueClicked(at index: Int, token: String, shift: Bool, toggle: Bool) {
        guard metadataColumns.indices.contains(index) else { return }
        var col = metadataColumns[index]
        let before = col.values
        if token.isEmpty {
            col.values = []
            col.anchor = ""
        } else if col.values.isEmpty {
            col.values = [token]
            col.anchor = token
        } else if toggle {
            if col.values.contains(token) { col.values.remove(token) } else { col.values.insert(token) }
            col.anchor = token
        } else if shift {
            let order = facetColumn(at: index)?.values.map { $0.token } ?? []
            let anchorTok = col.anchor.isEmpty ? (col.values.first ?? token) : col.anchor
            if let ai = order.firstIndex(of: anchorTok), let ci = order.firstIndex(of: token) {
                col.values = Set(order[min(ai, ci)...max(ai, ci)])
                col.anchor = anchorTok
            } else {
                col.values = [token]
                col.anchor = token
            }
        } else {
            col.values = [token]
            col.anchor = token
        }
        guard col.values != before else { return }
        metadataColumns[index] = col
        reloadForFilterChange()
    }

    /// Change the metadata key of column `index`; resets its selected values.
    public func setMetadataColumnKey(at index: Int, key: String) {
        guard metadataColumns.indices.contains(index),
              metadataColumns[index].key != key else { return }
        let hadActiveValue = !metadataColumns[index].key.isEmpty && !metadataColumns[index].values.isEmpty
        metadataColumns[index] = MetadataColumn(key: key)
        LibraryFilterPrefs.saveColumns(metadataColumns)
        if hadActiveValue { reloadForFilterChange() } else { scheduleFacetRefresh() }
    }

    /// Flip column `index` between "is" (match the selected values) and "is not"
    /// (exclude them). Only re-queries when the column actually has values
    /// selected — an empty column doesn't filter either way.
    public func setMetadataColumnNegate(at index: Int, negate: Bool) {
        guard metadataColumns.indices.contains(index),
              metadataColumns[index].negate != negate else { return }
        metadataColumns[index].negate = negate
        LibraryFilterPrefs.saveColumns(metadataColumns)
        if !metadataColumns[index].values.isEmpty { reloadForFilterChange() }
    }

    public enum ColumnInsertPosition { case front, end }

    /// Insert a new (empty) metadata column at the front or the end.
    public func addMetadataColumn(at position: ColumnInsertPosition) {
        switch position {
        case .front: metadataColumns.insert(MetadataColumn(), at: 0)
        case .end:   metadataColumns.append(MetadataColumn())
        }
        LibraryFilterPrefs.saveColumns(metadataColumns)
        scheduleFacetRefresh()
    }

    /// Remove metadata column `index`. No-op when only one column remains.
    public func removeMetadataColumn(at index: Int) {
        guard metadataColumns.count > 1, metadataColumns.indices.contains(index) else { return }
        let removed = metadataColumns.remove(at: index)
        LibraryFilterPrefs.saveColumns(metadataColumns)
        if !removed.key.isEmpty && !removed.values.isEmpty { reloadForFilterChange() }
        else { scheduleFacetRefresh() }
    }

    /// Reset the Library Filter (search + attribute + metadata values) so all
    /// videos show, subject to the higher-level location / keyword filters. The
    /// metadata column layout (keys/order) is preserved.
    public func clearLibraryFilter() {
        var changed = false
        if !searchQuery.isEmpty { searchQuery = ""; changed = true }
        if filterMinRating != 0 { filterMinRating = 0; changed = true }
        if !filterColorLabel.isEmpty { filterColorLabel = ""; changed = true }
        if filterLocation != nil { filterLocation = nil; changed = true }
        if filterHasLocation != .any { filterHasLocation = .any; changed = true }
        if filterHasKeywords != .any { filterHasKeywords = .any; changed = true }
        if filterHasProxies != .any { filterHasProxies = .any; changed = true }
        if filterFullResolution != .any { filterFullResolution = .any; changed = true }
        if filterHasAudio != .any { filterHasAudio = .any; changed = true }
        if filterOrientation != .any { filterOrientation = .any; changed = true }
        for i in metadataColumns.indices where !metadataColumns[i].values.isEmpty {
            metadataColumns[i].values = []
            metadataColumns[i].anchor = ""
            changed = true
        }
        // Clear is a resting mode: it stays selected and shows nothing below.
        libraryFilterMode = .clear
        if changed { reloadForFilterChange() } else { scheduleFacetRefresh() }
    }

    /// Trigger an initial facet load (e.g. right after a catalog opens).
    public func refreshMetadataFacets() { scheduleFacetRefresh() }

    private var facetLoadTask: Task<Void, Never>?

    /// Recompute the metadata facets (cancel-in-flight). The server owns the
    /// cascade, so we always send the full ordered column list and replace the
    /// results wholesale. Mirrors `loadCurrentPage`'s stale-guard pattern.
    private func scheduleFacetRefresh() {
        facetLoadTask?.cancel()
        facetLoadTask = Task { [weak self] in
            await self?.performFacetLoad()
        }
    }

    private func performFacetLoad() async {
        let geo: (latitude: Double, longitude: Double, radiusKm: Double)? = filterLocation.map {
            (latitude: $0.latitude, longitude: $0.longitude, radiusKm: $0.radiusKm)
        }
        let result = await repository.getMetadataFacets(
            locationPath: locationFilterValue,
            filterTagIds: filterTagId.isEmpty ? [] : [filterTagId],
            collectionId: collectionIdFilter,
            geoFilter: geo,
            filterMinRating: filterMinRating,
            filterColorLabel: filterColorLabel,
            searchQuery: searchQuery,
            columns: metadataColumns.map {
                (key: $0.key, value: $0.values.sorted().joined(separator: metadataValueSeparator))
            },
            hasLocation: filterHasLocation,
            hasKeywords: filterHasKeywords,
            hasProxies: filterHasProxies,
            fullResolution: filterFullResolution
        )
        if Task.isCancelled { return }
        facetColumns = result.columns
        availableMetadataKeys = result.availableKeys
    }

    /// Expand `videoIds` so that any video in a COLLAPSED stack is replaced
    /// by all of its stack members. Videos in an expanded stack or not in a
    /// stack are left as-is. Used by keyword and location operations.
    private func expandForCollapsedStacks(_ videoIds: [String]) async -> [String] {
        var result = Set<String>()
        for id in videoIds {
            guard let video = videos.first(where: { $0.id == id }),
                  video.isInGroup,
                  !expandedGroupIds.contains(video.groupId) else {
                result.insert(id)
                continue
            }
            let groupId = video.groupId
            if let cached = expandedGroupMembers[groupId] {
                cached.forEach { result.insert($0.id) }
            } else {
                do {
                    let (members, _) = try await repository.listGroupMembers(groupId: groupId)
                    expandedGroupMembers[groupId] = members
                    members.forEach { result.insert($0.id) }
                } catch {
                    result.insert(id)
                }
            }
        }
        return Array(result)
    }

    /// Apply [keyword] to all videos in [videoIds]. Creates the tag if it
    /// doesn't exist. Refreshes the tag list (for new counts) and the
    /// optional [onComplete] handler runs afterwards. When a target video
    /// belongs to a collapsed stack the keyword is applied to all members.
    public func applyKeyword(_ keyword: String, to videoIds: [String], onComplete: @escaping () -> Void = {}) {
        let name = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !videoIds.isEmpty else { return }
        Task {
            do {
                let finalIds = await expandForCollapsedStacks(videoIds)
                // Optimistic update so the keyword badge appears immediately —
                // mirrored into the cached stack members so the badge also
                // shows on every member when the stack is expanded next.
                let idSet = Set(finalIds)
                videos = videos.map { v in
                    idSet.contains(v.id) && !v.tags.contains(name)
                        ? v.withTags(v.tags + [name]) : v
                }
                expandedGroupMembers = expandedGroupMembers.mapValues { members in
                    members.map { v in
                        idSet.contains(v.id) && !v.tags.contains(name)
                            ? v.withTags(v.tags + [name]) : v
                    }
                }
                guard let tag = try await repository.createTag(name: name) else {
                    error = "Failed to create or find tag '\(name)'"
                    return
                }
                if !(try await repository.tagVideos(videoIds: finalIds, tagId: tag.id)) {
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

    public func removeKeyword(tagId: String, from videoIds: [String], onComplete: @escaping () -> Void = {}) {
        guard !tagId.isEmpty, !videoIds.isEmpty else { return }
        Task {
            do {
                let finalIds = await expandForCollapsedStacks(videoIds)
                // Optimistic update so the badge clears immediately — also
                // applied to the cached stack members so the change reflects
                // when the stack is expanded.
                let tagName = tags.first(where: { $0.id == tagId })?.name
                if let tagName {
                    let idSet = Set(finalIds)
                    videos = videos.map { v in
                        idSet.contains(v.id) ? v.withTags(v.tags.filter { $0 != tagName }) : v
                    }
                    expandedGroupMembers = expandedGroupMembers.mapValues { members in
                        members.map { v in
                            idSet.contains(v.id) ? v.withTags(v.tags.filter { $0 != tagName }) : v
                        }
                    }
                }
                if !(try await repository.untagVideos(videoIds: finalIds, tagId: tagId)) {
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

    public func loadLibraryLocations() {
        Task {
            do {
                libraryLocations = try await repository.listLibraryLocations()
                // The subdirectory tree is derived from indexed video paths;
                // after a (re)scan or removal those may have changed. Drop the
                // cache and re-fetch the currently-expanded directories so the
                // tree's counts and shape stay fresh, dropping any directory
                // that no longer has children.
                subdirCache.removeAll()
                var stillExpanded: Set<String> = []
                for dir in expandedDirs {
                    let children = try await repository.listSubdirectories(dir)
                    if !children.isEmpty {
                        subdirCache[dir] = children
                        stillExpanded.insert(dir)
                    }
                }
                expandedDirs = stillExpanded
                rebuildLibraryRows()
            } catch {
                NSLog("Failed to load library locations: \(error)")
            }
        }
    }

    /// Recompute `visibleLibraryRows` — the flattened, display-ordered list of
    /// library locations and their expanded subdirectories — from the current
    /// locations, expanded set, and fetched children. Cheap; called after any
    /// change to the tree.
    private func rebuildLibraryRows() {
        var rows: [LibraryRow] = []

        func addNode(path: String, depth: Int, videoCount: Int64, expandable: Bool, topLevel: Bool) {
            let isExpanded = expandable && expandedDirs.contains(path)
            rows.append(LibraryRow(
                path: path,
                depth: depth,
                videoCount: videoCount,
                isExpandable: expandable,
                isExpanded: isExpanded,
                isTopLevel: topLevel
            ))
            if isExpanded, let children = subdirCache[path] {
                for child in children {
                    addNode(
                        path: child.path,
                        depth: depth + 1,
                        videoCount: child.videoCount,
                        expandable: child.hasSubdirectories,
                        topLevel: false
                    )
                }
            }
        }

        for loc in libraryLocations {
            addNode(
                path: loc.path,
                depth: 0,
                videoCount: loc.videoCount,
                // Only recursive locations with children can expand.
                expandable: loc.recursive && loc.hasSubdirectories,
                topLevel: true
            )
        }
        visibleLibraryRows = rows
    }

    /// Expand a collapsed directory or collapse an expanded one.
    public func toggleExpand(_ path: String) {
        if expandedDirs.contains(path) {
            collapseDir(path)
            return
        }
        // Optimistic: rotate the chevron and show the row as expanded now; the
        // children splice in when the fetch returns (or are already cached).
        expandedDirs.insert(path)
        if subdirCache[path] != nil {
            rebuildLibraryRows()
        } else {
            rebuildLibraryRows()
            Task {
                do {
                    let children = try await repository.listSubdirectories(path)
                    subdirCache[path] = children
                    // The user may have collapsed it again while we fetched.
                    if expandedDirs.contains(path) { rebuildLibraryRows() }
                } catch {
                    NSLog("Failed to list subdirectories of \(path): \(error)")
                }
            }
        }
    }

    /// Collapse `path`, folding away its whole subtree. Any expanded descendant
    /// is pruned too. If the current selection points at a now-hidden
    /// subdirectory, it is promoted to `path` — the nearest still-visible
    /// ancestor — so the grid widens to the collapsed folder rather than
    /// silently filtering by an invisible directory.
    public func collapseDir(_ path: String) {
        let prefix = path + "/"
        expandedDirs = expandedDirs.filter { $0 != path && !$0.hasPrefix(prefix) }

        if selectedLocationPaths.contains(where: { $0.hasPrefix(prefix) }) {
            // Roll hidden selections up to `path`, de-duplicating while keeping
            // display order.
            var promoted: [String] = []
            for sel in selectedLocationPaths {
                let next = sel.hasPrefix(prefix) ? path : sel
                if !promoted.contains(next) { promoted.append(next) }
            }
            let anchor = locationAnchorPath.map { $0.hasPrefix(prefix) ? path : $0 }
            applyLocationSelection(promoted, anchor: anchor)
        }
        rebuildLibraryRows()
    }

    /// Remove a library location and all its indexed videos from the catalog.
    /// The video files on disk are not touched.
    public func removeLibraryLocation(path: String) {
        Task {
            do {
                let success = try await repository.removeLibraryLocation(path: path)
                if success {
                    // Drop the removed directory from the selection if present.
                    if let i = selectedLocationPaths.firstIndex(of: path) {
                        var next = selectedLocationPaths
                        next.remove(at: i)
                        applyLocationSelection(next, anchor: nil)
                    }
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

    public func addLibraryAndScan(
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
                        // Background refresh — keep current rows visible,
                        // just swap them out when the fetch returns. Using
                        // `loadVideos()` (not `reloadForFilterChange`) avoids
                        // a full-page spinner every 12 progress ticks.
                        loadVideos()
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
                loadVideos()
                loadLibraryLocations()
                refreshMetadataFacets()
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
    public func addLibraryAndScanMultiple(
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
                            // Background refresh — see comment in addLibraryAndScan.
                            loadVideos()
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
            loadVideos()
            loadLibraryLocations()
            refreshMetadataFacets()
        }
    }

    public func rescanLibrary(path: String) {
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

    public func clearScanResult() { scanResult = nil }

    // MARK: - Selection (plain / shift / cmd-click semantics)

    public func selectVideo(_ video: VideoSummary) {
        selectedVideoIds = [video.id]
        anchorVideoId = video.id
        selectedVideoId = video.id
        selectedVideo = video
    }

    public func toggleVideoSelection(_ video: VideoSummary) {
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
        selectedVideo = video
    }

    /// Shift-click range selection. The caller computes [rangeIds] in visual order.
    public func selectRange(_ target: VideoSummary, rangeIds: [String]) {
        if rangeIds.isEmpty {
            selectVideo(target)
            return
        }
        selectedVideoIds = rangeIds
        selectedVideoId = target.id
        selectedVideo = target
        // anchor stays
    }

    public func clearSelection() {
        selectedVideoIds = []
        selectedVideoId = nil
        anchorVideoId = nil
        selectedVideo = nil
    }

    // MARK: - Arrow-key navigation

    /// Visual order + column count pushed by whichever of grid / list is on
    /// screen. `moveSelection` reads these; nothing binds to them so plain
    /// vars suffice. (`navColumns` is 1 in list mode.)
    private var navVideos: [VideoSummary] = []
    private var navColumns: Int = 1
    /// Set when arrow navigation moves the active card, so the scrolling view
    /// can bring it on screen without re-centering on every mouse click.
    @Published public var pendingScrollVideoId: String?
    /// Bumped after a user filter/collection reload finishes loading, so the
    /// grid re-centers the (surviving) selection — its row usually moves under
    /// the new filter, and it shouldn't be left off-screen. Not bumped by
    /// background refreshes or pagination, which must not yank the scroll.
    @Published public var scrollToSelectionTick: Int = 0

    /// Whichever of grid / list is on screen reports its layout here.
    public func setNavContext(_ orderedVideos: [VideoSummary], columns: Int) {
        navVideos = orderedVideos
        navColumns = max(1, columns)
    }

    /// Move the active card to [video] without disturbing the multi-selection
    /// or anchor — the highlighted card steps through the selection while every
    /// selected card stays selected (Lightroom-style).
    public func setActiveVideo(_ video: VideoSummary) {
        selectedVideoId = video.id
        selectedVideo = video
    }

    /// Arrow-key navigation. Moves the active card one step in [dir] over the
    /// visual order from `setNavContext`. With a multi-selection the move is
    /// confined to the selected cards (stops at their edges); otherwise it
    /// replace-selects the neighbouring card. Returns the card moved to so the
    /// caller can sync the detail panel, or nil on a no-op.
    @discardableResult
    public func moveSelection(_ dir: MoveDirection) -> VideoSummary? {
        let order = navVideos
        guard !order.isEmpty else { return nil }
        let curIdx = selectedVideoId.flatMap { id in order.firstIndex { $0.id == id } } ?? -1
        if curIdx < 0 {
            let first = order[0]
            selectVideo(first)
            pendingScrollVideoId = first.id
            return first
        }
        if selectedVideoIds.count > 1 {
            // Confined to the selection. Left/Right step through the whole
            // selection in visual order — wrapping to the prev/next row at a
            // row boundary — so a multi-row selection is fully traversable
            // without Up/Down. Up/Down stay geometric (±one row) within it.
            let target: Int
            switch dir {
            case .left, .right:
                target = nextSelectedIndex(order, from: curIdx,
                                           selected: Set(selectedVideoIds),
                                           forward: dir == .right)
            case .up, .down:
                let t = navTargetIndex(current: curIdx, size: order.count, cols: navColumns, dir: dir)
                target = (t >= 0 && selectedVideoIds.contains(order[t].id)) ? t : -1
            }
            guard target >= 0 else { return nil }
            setActiveVideo(order[target])
            pendingScrollVideoId = order[target].id
            return order[target]
        }
        // Single / no selection: move over the whole grid and replace-select.
        let target = navTargetIndex(current: curIdx, size: order.count, cols: navColumns, dir: dir)
        guard target >= 0 else { return nil }
        selectVideo(order[target])
        pendingScrollVideoId = order[target].id
        return order[target]
    }

    /// Shift+arrow: extend the selection from the anchor (the originally-clicked
    /// / last plain-selected card) to where the arrow moves the active end —
    /// the same flat range a shift-click produces. The anchor stays put so
    /// repeated Shift+arrows pivot from it. Returns the new active card, or nil
    /// on a no-op.
    @discardableResult
    public func extendSelection(_ dir: MoveDirection) -> VideoSummary? {
        let order = navVideos
        guard !order.isEmpty else { return nil }
        let curIdx = selectedVideoId.flatMap { id in order.firstIndex { $0.id == id } } ?? -1
        if curIdx < 0 {
            let first = order[0]
            selectVideo(first)
            pendingScrollVideoId = first.id
            return first
        }
        let target = navTargetIndex(current: curIdx, size: order.count, cols: navColumns, dir: dir)
        guard target >= 0 else { return nil }
        let anchorId = anchorVideoId ?? selectedVideoId
        let anchorIdx = anchorId.flatMap { id in order.firstIndex { $0.id == id } } ?? curIdx
        let lo = min(anchorIdx, target), hi = max(anchorIdx, target)
        let rangeIds = order[lo...hi].map { $0.id }
        // selectRange keeps the anchor and sets the active end to [target].
        selectRange(order[target], rangeIds: rangeIds)
        pendingScrollVideoId = order[target].id
        return order[target]
    }

    // MARK: - Thumbnails

    public func loadThumbnail(videoId: String) {
        if thumbnails[videoId] != nil { return }
        if thumbnailLoading.contains(videoId) { return }
        thumbnailLoading.insert(videoId)
        Task {
            defer { thumbnailLoading.remove(videoId) }
            // The daemon distinguishes a definitive miss (NOT_FOUND → nil:
            // stop asking) from a transient failure (throws). Only the
            // transient case is retried, with short jittered delays — a
            // cards-mount-storm hiccup clears in milliseconds, where the old
            // 0.5/1/10/30/60 s ladder quantized every hiccup into a
            // multi-second blank card.
            let delaysNs: [UInt64] = [0, 250_000_000, 750_000_000, 2_000_000_000]
            for delay in delaysNs {
                if thumbnails[videoId] != nil { return }
                if delay > 0 {
                    let jitter = UInt64.random(in: 0..<100_000_000)
                    try? await Task.sleep(nanoseconds: delay + jitter)
                }
                await thumbnailSemaphore.acquire()
                do {
                    let image = try await repository.getThumbnail(videoId: videoId, size: "medium")
                    await thumbnailSemaphore.release()
                    if let image {
                        thumbnails[videoId] = image
                    }
                    // Success stored above; nil is a definitive miss. Either
                    // way, stop.
                    return
                } catch {
                    await thumbnailSemaphore.release()
                    NSLog(
                        "[GridViewModel] thumbnail fetch for %@ failed (will retry): %@",
                        videoId,
                        String(describing: error)
                    )
                }
            }
        }
    }

    /// Lazy-load all scrub frames for a video on first hover. No-ops if already
    /// loaded or in-flight.
    /// Find a loaded summary by id, across the grid rows and any expanded
    /// stack's members — enough to detect codec/path/duration for local scrub.
    private func summaryForScrub(_ videoId: String) -> VideoSummary? {
        if let v = videos.first(where: { $0.id == videoId }) { return v }
        for members in expandedGroupMembers.values {
            if let v = members.first(where: { $0.id == videoId }) { return v }
        }
        return nil
    }

    /// Extract real scrub frames for ProRes RAW via AVFoundation — the same OS
    /// decoder AVPlayer uses (and the one that renders these clips correctly).
    /// ffmpeg can't decode Atomos S-Log3 ProRes RAW, so the daemon falls back to
    /// a single QuickLook poster for every scrub position; this gives the macOS
    /// client a true per-position strip instead. A little seek tolerance keeps
    /// it responsive over the NAS — exact frames aren't needed for a hover strip.
    private func proResRawScrubFrames(path: String, count: Int, durationMs: Int) async -> [PlatformImage?] {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.requestedTimeToleranceBefore = CMTime(seconds: 0.5, preferredTimescale: 600)
        gen.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)
        gen.maximumSize = CGSize(width: 480, height: 0)
        let durSec = max(0.1, Double(durationMs) / 1000.0)
        var out: [PlatformImage?] = []
        out.reserveCapacity(count)
        for i in 0..<count {
            let frac = (Double(i) + 0.5) / Double(count)
            let t = CMTime(seconds: durSec * frac, preferredTimescale: 600)
            do {
                let result = try await gen.image(at: t)
                out.append(PlatformImage.fromCGImage(result.image))
            } catch {
                out.append(nil)
            }
        }
        return out
    }

    /// Fetch (once) the audio loudness series for `videoId` and cache it.
    public func loadAudioLoudness(videoId: String) {
        if audioLoudness[videoId] != nil { return }
        if audioLoudnessLoading.contains(videoId) { return }
        audioLoudnessLoading.insert(videoId)
        Task {
            defer { audioLoudnessLoading.remove(videoId) }
            audioLoudness[videoId] = await repository.getAudioLoudness(videoId: videoId)
        }
    }

    public func loadScrubFrames(videoId: String) {
        if scrubFrames[videoId] != nil { return }
        if scrubLoading.contains(videoId) { return }
        scrubLoading.insert(videoId)
        NSLog("[GridViewModel] loadScrubFrames begin video=%@", videoId)
        Task {
            defer { scrubLoading.remove(videoId) }
            let rawCount = UserDefaults.standard.integer(forKey: "scrubFrameCount")
            let count = rawCount > 0 ? rawCount : 10
            // ProRes RAW: generate frames locally with AVFoundation (the daemon
            // can only return one repeated QuickLook poster). Fall back to the
            // daemon strip if local generation yields nothing.
            if let s = summaryForScrub(videoId), s.codecVideo == "prores_raw" {
                let local = await proResRawScrubFrames(path: s.path, count: count, durationMs: s.durationMs)
                if local.contains(where: { $0 != nil }) {
                    scrubFrames[videoId] = local
                    NSLog("[GridViewModel] loadScrubFrames video=%@ used local AVFoundation frames", videoId)
                    return
                }
            }
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

    /// Begin (or resume) fetching detail-resolution thumbnails for `videoId`,
    /// sized to the `targetWidth` px render area — the dwell action behind the
    /// detail view's higher-res scrubbing. Fetches the upgraded poster first
    /// (the frame the user is staring at), then every scrub frame, updating the
    /// caches incrementally. Idempotent at a given width; resumes (skips frames
    /// already fetched) after a cancel. No-op when the area is no wider than the
    /// base scrub resolution.
    public func startHiResDetail(videoId: String, targetWidth: Int32) {
        guard targetWidth > baseScrubWidth else { return }
        if hiResWidth[videoId] == targetWidth && hiResTasks[videoId] != nil { return }
        if let w = hiResWidth[videoId], w != targetWidth {
            hiResScrubFrames[videoId] = nil
            hiResPoster[videoId] = nil
        }
        hiResWidth[videoId] = targetWidth
        hiResTasks[videoId]?.cancel()
        let rawCount = UserDefaults.standard.integer(forKey: "scrubFrameCount")
        let count = scrubFrames[videoId]?.count ?? (rawCount > 0 ? rawCount : 10)
        hiResTasks[videoId] = Task { [weak self] in
            guard let self else { return }
            // 1) The static (non-hover) frame the user is currently seeing.
            if self.hiResPoster[videoId] == nil {
                if let img = await self.repository.getThumbnailHiRes(videoId: videoId, size: "large", maxWidth: targetWidth) {
                    if Task.isCancelled { return }
                    self.hiResPoster[videoId] = img
                }
            }
            // 2) Every scrub frame, so scrubbing shows hi-res too. Filled in
            //    place so each location upgrades as soon as its frame lands.
            var acc = self.hiResScrubFrames[videoId] ?? Array(repeating: nil, count: count)
            for i in 0..<count {
                if Task.isCancelled { return }
                if acc.indices.contains(i), acc[i] != nil { continue }
                if let img = await self.repository.getThumbnailHiRes(videoId: videoId, size: "scrub_\(i)", maxWidth: targetWidth) {
                    if Task.isCancelled { return }
                    if acc.indices.contains(i) { acc[i] = img }
                    self.hiResScrubFrames[videoId] = acc
                }
            }
            self.hiResTasks[videoId] = nil
        }
    }

    /// Stop any in-flight hi-res detail fetch for `videoId` (the user left the
    /// detail view). Frames already fetched are kept so a return resumes.
    public func cancelHiResDetail(videoId: String) {
        hiResTasks[videoId]?.cancel()
        hiResTasks[videoId] = nil
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
    public func removeFromStack(videoId: String, groupId: String) {
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
    /// right-click "Promote to leader" menu item that appears only when
    /// the right-clicked card is a non-representative member.
    public func setStackMaster(videoId: String, groupId: String) {
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
                    gpsLatitude: v.gpsLatitude, gpsLongitude: v.gpsLongitude,
                    lensModel: v.lensModel, iso: v.iso, aperture: v.aperture,
                    exposureTimeS: v.exposureTimeS, focalLengthMm: v.focalLengthMm,
                    fullResolution: v.fullResolution,
                    isOnline: v.isOnline,
                    frameCount: v.frameCount
                )
            }
            return v
        }
        Task {
            do {
                _ = try await repository.setGroupPreferred(groupId: groupId, videoId: videoId)
                refreshAfterStackChange(groupId: groupId)
                loadVideos()  // representative changed → grid order may shift; background refresh keeps rows visible
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
    public func unstackGroup(groupId: String) {
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

    public func refreshAfterStackChange(groupId: String) {
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
        // `groupSize` reflects the post-ungroup reality. Background refresh —
        // the filter hasn't changed, so we keep the existing rows visible
        // and just swap them out when the fetch returns.
        loadVideos()
    }

    public func toggleStackExpansion(_ groupId: String) {
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
    public func ensureStackMembersLoaded(_ groupId: String) {
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

    public func groupSelectedVideos() {
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
        NSLog("combine: groupSelectedVideos sending ids=\(ids) preferred=\(preferred)")
        Task {
            isLoading = true
            do {
                let info = try await repository.createGroup(videoIds: ids, name: "", preferredVideoId: preferred)
                NSLog("combine: createGroup RPC returned id=\(info?.id ?? "nil") size=\(info?.size ?? -1)")
                clearSelection()
                // Release the loading guard *before* refreshing: loadVideos() runs a
                // background (no-spinner) reload that bails out while isLoading is
                // still true (see reloadFromTop), leaving the just-merged stack
                // un-rendered until a manual refresh.
                isLoading = false
                loadVideos()  // background refresh — no filter change, just structural update
                // Once the refreshed list is in, make the merged stack's
                // representative the active selection (and reveal it): the combine
                // result is what the user just acted on, so it should be selected
                // and ready for a follow-up rename/rate rather than leaving an
                // empty selection. await listLoadTask waits for reloadFromTop.
                let repId: String = {
                    if let p = info?.preferredVideoId, !p.isEmpty { return p }
                    return preferred
                }()
                await listLoadTask?.value
                if let rep = videos.first(where: { $0.id == repId }) {
                    selectVideo(rep)
                    pendingScrollVideoId = rep.id
                }
            } catch {
                NSLog("combine: createGroup RPC failed: \(error.localizedDescription)")
                self.error = "Group failed: \(error.localizedDescription)"
            }
            isLoading = false
        }
    }

    /// Manually attach the current multi-selection as proxies — the
    /// proxy-world analogue of `groupSelectedVideos`. The daemon picks the
    /// highest-resolution selection as the master and links every other
    /// selection as a manual proxy of it; for mopping up the master/proxy
    /// pairs auto-detection missed.
    public func attachProxiesToSelection() {
        let ids = selectedVideoIds
        if ids.count < 2 {
            error = "Select at least 2 videos (Shift+click or Cmd+click) to attach proxies"
            return
        }
        NSLog("attach: attachProxiesToSelection sending ids=\(ids)")
        Task {
            isLoading = true
            do {
                let result = try await repository.attachProxies(videoIds: ids)
                NSLog("attach: attachProxies RPC returned master=\(result.masterVideoId) attached=\(result.proxiesAttached)")
                clearSelection()
                // Release the loading guard before refreshing, same as
                // groupSelectedVideos — newly-hidden proxies won't fold under
                // their master otherwise until a manual refresh.
                isLoading = false
                loadVideos()  // background refresh — structural update only
                // Refresh the left panel too: an attached proxy stops counting
                // as a standalone video, so its folder's count must drop.
                loadLibraryLocations()
            } catch {
                NSLog("attach: attachProxies RPC failed: \(error.localizedDescription)")
                self.error = "Attach proxies failed: \(error.localizedDescription)"
            }
            isLoading = false
        }
    }

    // MARK: - External app

    public func openVideoInExternal(path: String) {
        #if os(macOS)
        let url = URL(fileURLWithPath: path)
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.open(url)
        } else {
            error = "File not found: \(path)"
        }
        #endif
    }

    public func clearError() { error = nil }

    /// Select every video currently visible in the grid: each loaded
    /// representative plus, when its stack is expanded, all of its visible
    /// members. Drives the ⌘A shortcut.
    public func selectAllVisible() {
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
    public func clearState() {
        videos = []
        selectedVideoId = nil
        selectedVideo = nil
        selectedVideoIds = []
        anchorVideoId = nil
        isLoading = false
        error = nil
        totalCount = 0
        hasMore = false
        searchQuery = ""
        libraryLocations = []
        selectedLocationPath = ""
        selectedLocationPaths = []
        locationAnchorPath = nil
        expandedDirs = []
        subdirCache = [:]
        visibleLibraryRows = []
        tags = []
        filterTagId = ""
        metadataColumns = defaultMetadataColumns
        facetColumns = []
        availableMetadataKeys = []
        libraryFilterMode = .clear
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

    /// After an optimistic rating/colour change, drop any cached video that no
    /// longer satisfies the active Lightroom mark-filters so it leaves the view
    /// immediately, instead of lingering until the next watcher-driven refresh
    /// (tens of seconds out on a slow NAS). Additions — a video that now matches
    /// the filter — still surface on the next reload, since they aren't in the
    /// loaded page to begin with.
    private func pruneVideosFailingMarkFilters() {
        let colorFilter = filterColorLabel
        let minRating = filterMinRating
        guard !colorFilter.isEmpty || minRating > 0 else { return }
        let before = videos.count
        videos = videos.filter { v in
            (colorFilter.isEmpty || v.colorLabel == colorFilter)
                && (minRating <= 0 || Int32(v.rating) >= minRating)
        }
        let removed = before - videos.count
        if removed > 0 {
            totalCount = max(0, totalCount - Int64(removed))
        }
    }

    /// Does [s] still satisfy the attribute + Lightroom-mark filters we can
    /// evaluate client-side? Mirrors the daemon's `build_filter_clauses`
    /// (core/src/db.rs) for exactly those filters, so a filter change can tell
    /// when the current selection has just been filtered out of the grid
    /// without waiting for the reload to come back. Search / metadata /
    /// location-path / tag / collection filters need the server; the geo/area
    /// box IS checked here (it's computable client-side). `true` means "not
    /// filtered out by anything we can see locally".
    private func summaryMatchesLocalFilters(_ s: VideoSummary) -> Bool {
        switch filterHasLocation {
        case .yes: if !s.hasLocation { return false }
        case .no:  if s.hasLocation  { return false }
        case .any: break
        }
        switch filterHasKeywords {
        case .yes: if s.tags.isEmpty  { return false }
        case .no:  if !s.tags.isEmpty { return false }
        case .any: break
        }
        switch filterHasProxies {
        case .yes: if !s.hasProxies { return false }
        case .no:  if s.hasProxies  { return false }
        case .any: break
        }
        // The daemon classifies "full resolution" via a (camera, w, h) tuple
        // set; the summary's already-classified status is the client-side
        // mirror, so `.full` ⇔ in that set and anything else (incl.
        // `.unspecified`) counts as "not full".
        switch filterFullResolution {
        case .yes: if s.fullResolution != .full { return false }
        case .no:  if s.fullResolution == .full { return false }
        case .any: break
        }
        switch filterHasAudio {
        case .yes: if !s.hasAudio { return false }
        case .no:  if s.hasAudio  { return false }
        case .any: break
        }
        // Mirror the daemon's orientation SQL (build_filter_clauses): portrait ⇔
        // height > width; landscape ⇔ has real dims and width ≥ height.
        switch filterOrientation {
        case .portrait:  if !s.isPortrait  { return false }
        case .landscape: if !s.isLandscape { return false }
        case .any: break
        }
        if filterMinRating > 0 && Int32(s.rating) < filterMinRating { return false }
        if !filterColorLabel.isEmpty && s.colorLabel != filterColorLabel { return false }
        // Geo/area filter: mirror the daemon's bounding-box test (see
        // build_filter_clauses in core/src/db.rs) so an optimistic relocate
        // drops a card out of an active Location filter the instant it leaves
        // the box — matching what the reload returns, instead of letting it
        // linger then vanish. 1° lat ≈ 111 km; 1° lon ≈ 111·cos(lat) km.
        if let geo = filterLocation {
            guard s.hasLocation else { return false }
            let latDelta = abs(geo.radiusKm / 111.0)
            let cosLat = max(0.01, abs(cos(geo.latitude * .pi / 180.0)))
            let lonDelta = abs(geo.radiusKm / (111.0 * cosLat))
            if s.gpsLatitude < geo.latitude - latDelta || s.gpsLatitude > geo.latitude + latDelta { return false }
            if s.gpsLongitude < geo.longitude - lonDelta || s.gpsLongitude > geo.longitude + lonDelta { return false }
        }
        return true
    }

    /// True while the DETAIL loupe is the active view. In detail mode the loupe
    /// deliberately ignores the library filter — editing the shown video's
    /// metadata so it no longer matches must NOT yank it off screen. Set via
    /// `onViewModeChanged`; grid/list still drop a filtered-out selection on return.
    private var detailModeActive = false

    /// Called by the UI whenever the top-level view mode changes. While DETAIL
    /// is active the current video is pinned (the filter is ignored for the
    /// loupe); on returning to GRID/LIST/MAP we re-check and drop the selection
    /// if the (possibly just-edited) video no longer matches the active filter,
    /// so no stale details linger in the inspector.
    public func onViewModeChanged(isDetail: Bool) {
        detailModeActive = isDetail
        if !isDetail { forceClearSelectionIfFilteredOut() }
    }

    /// Drop the primary selection when it no longer passes the active filters —
    /// e.g. the user sets "location: no" while a geotagged video is selected, so
    /// its card is about to leave the grid. The loupe keys off `selectedVideoId`
    /// and the inspector is gated on it, so clearing here sends both back to
    /// their "select a video" placeholder instead of stranding the now-hidden
    /// video on screen. Call before the reload empties `videos` so the
    /// selection's summary is still resolvable.
    ///
    /// Suppressed while `detailModeActive` (the loupe pins its video); the
    /// deferred check then runs on the return to grid/list via `onViewModeChanged`.
    private func clearSelectionIfFilteredOut() {
        if detailModeActive { return }
        forceClearSelectionIfFilteredOut()
    }

    /// Unconditional form of `clearSelectionIfFilteredOut` — ignores
    /// `detailModeActive`. Used when leaving detail mode for grid/list, where a
    /// now-filtered-out selection must be dropped even though it had been pinned.
    private func forceClearSelectionIfFilteredOut() {
        guard let id = selectedVideoId else { return }
        guard let summary = videos.first(where: { $0.id == id }) ?? selectedVideo else { return }
        if !summaryMatchesLocalFilters(summary) {
            clearSelection()
        }
    }

    /// Apply a 0..5 star rating to one or more videos. Optimistically updates
    /// the cached `videos` list so the UI redraws immediately, then sends the
    /// RPC. The pattern mirrors how tag-add/-remove is handled today.
    public func setRating(_ rating: Int, for videoIds: [String]) {
        let clamped = max(0, min(5, rating))
        let ids = Set(videoIds.filter { !$0.isEmpty })
        guard !ids.isEmpty else { return }
        // Optimistic local update so the stars repaint without a round-trip.
        videos = videos.map { v in
            ids.contains(v.id) ? v.withRating(clamped) : v
        }
        pruneVideosFailingMarkFilters()
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
    public func setColorLabel(_ label: String, for videoIds: [String]) {
        let ids = Set(videoIds.filter { !$0.isEmpty })
        guard !ids.isEmpty else { return }
        videos = videos.map { v in
            ids.contains(v.id) ? v.withColorLabel(label) : v
        }
        pruneVideosFailingMarkFilters()
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
    public func setRatingOnSelection(_ rating: Int) {
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
    public func setColorLabelOnSelection(_ label: String) {
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
    public func loadGridSettings() {
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

    private var gridSettingsSaveTask: Task<Void, Never>?

    /// Persist the four-slot configuration. The optimistic local update already
    /// happened when the picker mutated `topSlots`; the persistence write is
    /// debounced so reconfiguring several slots in quick succession collapses to
    /// a single round-trip instead of one DB write per click — which on a slow
    /// NAS-backed catalog could otherwise back up behind each other.
    public func saveGridSettings() {
        let slots = Self.normaliseSlots(topSlots)
        gridSettingsSaveTask?.cancel()
        gridSettingsSaveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled { return }
            do {
                try await self?.repository.updateGridSettings(topSlots: slots)
            } catch is CancellationError {
                // Superseded by a newer edit.
            } catch {
                self?.error = "Failed to save grid settings: \(error.localizedDescription)"
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
    public func setLocationFilter(latitude: Double?, longitude: Double?, radiusKm: Double = 1.0) {
        if let lat = latitude, let lon = longitude {
            filterLocation = GeoFilter(latitude: lat, longitude: lon, radiusKm: radiusKm)
            // A geographic filter means "videos at this place", which inherently
            // have a location — drop a stale "location: no" (or "yes") attribute
            // filter that would otherwise contradict it and blank the grid.
            filterHasLocation = .any
        } else {
            filterLocation = nil
        }
        reloadForFilterChange()
    }

    /// Refresh the list of known locations (named places + unnamed coordinate
    /// clusters) with per-location video counts, for the Library Filter's
    /// "Location" mode. Always fetches the full geotagged set so the list isn't
    /// itself narrowed by whatever filter is currently active.
    public func loadFilterLocations() {
        Task {
            let locs = await repository.listVideosWithLocations()
            filterLocationGroups = Self.buildLocationFilterGroups(locs, named: namedLocations)
        }
    }

    /// Cluster the map's geotagged videos into named places + ~110 m coordinate
    /// buckets — the SAME grouping the Library Filter and the macOS/desktop maps
    /// use — each with a count and a place-name label. The iOS global map renders
    /// one marker per group (a circle with the count and, when known, the place
    /// name), instead of one pin per video. Recomputed from the current
    /// `videoLocations` + `namedLocations`.
    public func mapLocationClusters() -> [LocationFilterGroup] {
        Self.buildLocationFilterGroups(videoLocations, named: namedLocations)
    }

    /// The geotagged videos inside `cluster` (for the map's iPad side panel),
    /// using the same radius the cluster was built with. Drawn from
    /// `geotaggedVideos` so the rows are full `VideoSummary`s the panel can open.
    public func mapClusterMembers(_ cluster: LocationFilterGroup) -> [VideoSummary] {
        geotaggedVideos.filter { v in
            v.hasLocation && Self.haversineMeters(
                lat1: cluster.latitude, lon1: cluster.longitude,
                lat2: v.gpsLatitude, lon2: v.gpsLongitude) <= cluster.radiusKm * 1000.0
        }
    }

    /// Apply a proximity filter that frames exactly `videoIds` — used by the map
    /// view's "open these here in grid/list". Centres on their centroid with a
    /// radius covering the farthest member (plus a small margin), so only that
    /// spot's videos show; unlocated videos lack coordinates and never match. A
    /// named place containing the centroid lends its own radius instead.
    public func filterToVideosLocation(_ videoIds: [String]) {
        let idSet = Set(videoIds)
        var seen = Set<String>()
        let pts: [(Double, Double)] = (geotaggedVideos + videos).compactMap { v in
            guard idSet.contains(v.id), v.hasLocation, !seen.contains(v.id) else { return nil }
            seen.insert(v.id)
            return (v.gpsLatitude, v.gpsLongitude)
        }
        guard !pts.isEmpty else { return }
        let cLat = pts.map(\.0).reduce(0, +) / Double(pts.count)
        let cLon = pts.map(\.1).reduce(0, +) / Double(pts.count)
        let radiusKm: Double
        if let named = nameForLocation(latitude: cLat, longitude: cLon) {
            radiusKm = named.radiusMeters / 1000.0
        } else {
            let maxMeters = pts.map {
                Self.haversineMeters(lat1: cLat, lon1: cLon, lat2: $0.0, lon2: $0.1)
            }.max() ?? 0
            radiusKm = max(0.1, maxMeters / 1000.0 + 0.1)
        }
        setLocationFilter(latitude: cLat, longitude: cLon, radiusKm: radiusKm)
    }

    /// Group the catalog's geotagged videos into named places (counted within
    /// each place's radius) and unnamed coordinate clusters (~110 m buckets),
    /// with counts, sorted by popularity then label.
    private static func buildLocationFilterGroups(
        _ locs: [VideoLocation], named: [NamedLocation]
    ) -> [LocationFilterGroup] {
        var groups: [LocationFilterGroup] = []
        var claimed = Set<String>()
        for n in named {
            let members = locs.filter {
                !claimed.contains($0.id) &&
                    haversineMeters(lat1: n.latitude, lon1: n.longitude,
                                    lat2: $0.latitude, lon2: $0.longitude) <= n.radiusMeters
            }
            if members.isEmpty { continue }
            members.forEach { claimed.insert($0.id) }
            groups.append(LocationFilterGroup(
                label: n.name, latitude: n.latitude, longitude: n.longitude,
                radiusKm: n.radiusMeters / 1000.0, count: members.count, isNamed: true))
        }
        let remaining = locs.filter { !claimed.contains($0.id) }
        let buckets = Dictionary(grouping: remaining) {
            String(format: "%.3f,%.3f", $0.latitude, $0.longitude)
        }
        for (_, members) in buckets {
            let cLat = members.map(\.latitude).reduce(0, +) / Double(members.count)
            let cLon = members.map(\.longitude).reduce(0, +) / Double(members.count)
            groups.append(LocationFilterGroup(
                label: String(format: "%.4f, %.4f", cLat, cLon),
                latitude: cLat, longitude: cLon,
                radiusKm: 0.2, count: members.count, isNamed: false))
        }
        return groups.sorted {
            $0.count != $1.count ? $0.count > $1.count
                : $0.label.lowercased() < $1.label.lowercased()
        }
    }

    /// Refresh the list of geotagged videos used by the global-map view.
    public func loadVideoLocations() {
        Task { await loadVideoLocationsAsync() }
    }

    /// Awaitable variant — callers that need the data populated *before*
    /// they take a UI action (opening a sheet, framing a map) can `await`
    /// this. Always reads from the daemon; relies on the catalog-open
    /// pre-load to keep the call fast in steady state.
    public func loadVideoLocationsAsync() async {
        videoLocations = await repository.listVideosWithLocations()
    }

    /// Load GPS-tagged videos matching the current grid filters and update `videoLocations`.
    /// Uses a large page size to minimise round-trips. Does NOT apply `filterLocation`
    /// so the pin list is not constrained by an active proximity circle.
    public func loadVideoLocationsFiltered() {
        Task { await loadVideoLocationsFilteredAsync() }
    }

    private var locationsRefreshTask: Task<Void, Never>?

    /// Debounced trigger for `loadVideoLocationsFilteredAsync` (mirrors the
    /// Compose client). Collapses a burst of grid reloads into a single
    /// full-library pagination once activity settles. The map / location-picker
    /// dialogs that consume `videoLocations` are opened on demand, so a short
    /// delay before they're warm is harmless.
    private func scheduleVideoLocationsRefresh() {
        locationsRefreshTask?.cancel()
        locationsRefreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            await self?.loadVideoLocationsFilteredAsync()
        }
    }

    public func loadVideoLocationsFilteredAsync() async {
        isLoadingVideoLocations = true
        defer { isLoadingVideoLocations = false }
        let batchSize: Int32 = 500
        let filterTagIds = filterTagId.isEmpty ? [] : [filterTagId]
        var accumulated: [VideoLocation] = []
        var accumulatedVideos: [VideoSummary] = []
        var offset: Int32 = 0
        do {
            while true {
                let (page, total) = try await repository.listVideos(
                    limit: batchSize,
                    offset: offset,
                    searchQuery: searchQuery,
                    sortBy: sortBy,
                    sortAscending: sortAscending,
                    // Mirror the grid's full filter so the map honors it too:
                    // the multi-select library paths, collection, and the
                    // attribute filters (keywords/proxies/full-res) — not just
                    // search/tags/rating/metadata as before.
                    locationPath: locationFilterValue,
                    filterTagIds: filterTagIds,
                    geoFilter: nil,
                    filterMinRating: filterMinRating,
                    filterColorLabel: filterColorLabel,
                    metadataFilters: activeMetadataFilters(),
                    collectionId: collectionIdFilter,
                    // Force has-location: the map only plots located videos and
                    // the option is hidden in map mode (a stale "no" would empty it).
                    hasLocation: .yes,
                    hasKeywords: filterHasKeywords,
                    hasProxies: filterHasProxies,
                    fullResolution: filterFullResolution
                )
                for v in page where v.hasLocation {
                    accumulated.append(VideoLocation(
                        id: v.id,
                        filename: v.filename,
                        path: v.path,
                        latitude: v.gpsLatitude,
                        longitude: v.gpsLongitude,
                        altitude: 0.0,
                        hasThumbnail: v.hasThumbnail
                    ))
                    accumulatedVideos.append(v)
                }
                offset += Int32(page.count)
                if page.isEmpty || Int64(offset) >= total { break }
            }
            videoLocations = accumulated
            geotaggedVideos = accumulatedVideos
        } catch {
            // Silently ignore — the existing locations remain in place.
        }
    }

    /// Refresh the catalog's named-location list. Called whenever a sheet
    /// needs to display or resolve names — cheap (typically a few hundred
    /// rows at most) so we never paginate.
    public func loadNamedLocations() {
        Task { await loadNamedLocationsAsync() }
    }

    /// Awaitable variant of [loadNamedLocations].
    public func loadNamedLocationsAsync() async {
        namedLocations = await repository.listNamedLocations()
    }

    /// Resolve a (lat, lon) into the nearest named place within its
    /// `radiusMeters`, or nil if no entry is close enough. Linear scan —
    /// the named-location list is small.
    public func nameForLocation(latitude: Double, longitude: Double) -> NamedLocation? {
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
    public func saveNamedLocation(
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
    /// writes complete the grid and global-map are reloaded. When a target
    /// video belongs to a collapsed stack the location is applied to all
    /// members of that stack.
    public func setVideoLocations(
        videoIds: [String],
        latitude: Double,
        longitude: Double,
        writeToFile: Bool,
        onComplete: @escaping () -> Void = {}
    ) {
        guard !videoIds.isEmpty else { return }
        Task {
            let finalIds = await expandForCollapsedStacks(videoIds)
            // Optimistic update so the location badge appears immediately —
            // both on the grid representatives and on cached stack members
            // (so the badge shows on every card when the stack is expanded).
            let idSet = Set(finalIds)
            // Optimistically apply the new GPS, then drop any video the change
            // pushes out of the active filter (e.g. it gained a location under
            // "location: no", or lost one under "location: yes"). Doing it here
            // — rather than leaning on the background reload — keeps the grid
            // correct the instant the location is set: the located card leaves
            // and the section's remaining unlocated videos stay put, instead of
            // the whole grid blanking until the user re-picks the library
            // section. The reload below still reconciles against the server.
            let relocated = videos.map { v in
                idSet.contains(v.id) ? v.withLocation(latitude: latitude, longitude: longitude) : v
            }
            let keptVideos = relocated.filter { summaryMatchesLocalFilters($0) }
            let droppedCount = relocated.count - keptVideos.count
            videos = keptVideos
            if droppedCount > 0 {
                totalCount = max(0, totalCount - Int64(droppedCount))
            }
            expandedGroupMembers = expandedGroupMembers.mapValues { members in
                members
                    .map { v in
                        idSet.contains(v.id) ? v.withLocation(latitude: latitude, longitude: longitude) : v
                    }
                    .filter { summaryMatchesLocalFilters($0) }
            }
            // Mirror the optimistic GPS change onto the cached selection summary,
            // then drop the selection if the just-changed video no longer passes
            // the active filter, so the inspector doesn't strand its stale
            // details. Same intent as the filter-change clearSelectionIfFilteredOut().
            if let sel = selectedVideo, idSet.contains(sel.id) {
                selectedVideo = sel.withLocation(latitude: latitude, longitude: longitude)
            }
            clearSelectionIfFilteredOut()
            var ok = 0
            for id in finalIds {
                let success = await repository.updateVideoLocation(
                    videoId: id,
                    latitude: latitude,
                    longitude: longitude,
                    altitude: 0,
                    writeToFile: writeToFile
                )
                if success { ok += 1 }
            }
            NSLog("Updated location on \(ok)/\(finalIds.count) video(s)")
            videoLocations = await repository.listVideosWithLocations()
            // Reload from the first page. Without resetting currentPage, a
            // stale page offset (advanced by earlier loadMore() calls) would
            // query past the end of the now-smaller filtered result set and
            // come back empty — blanking the whole grid instead of just
            // dropping the relocated cards. Mirrors Kotlin's reloadFromTop.
            currentPage = 0
            await loadCurrentPage(replace: true)
            onComplete()
        }
    }

    /// Remove the GPS location from every video in `videoIds`. Clearing is
    /// done by writing lat/lon 0.0 which the catalog treats as "no location".
    public func clearVideoLocations(videoIds: [String], onComplete: @escaping () -> Void = {}) {
        setVideoLocations(videoIds: videoIds, latitude: 0.0, longitude: 0.0,
                          writeToFile: false, onComplete: onComplete)
    }

    /// Persist a new capture timestamp (Unix ms, UTC) on every video in
    /// `videoIds`. Reloads the grid + year-filter dropdown afterwards.
    public func setVideoCaptureDates(
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
            refreshMetadataFacets()
            // Reset to the first page before reloading (see setVideoLocations):
            // a capture-date edit can push a video out of an active year filter,
            // and a stale page offset would otherwise blank the grid.
            currentPage = 0
            await loadCurrentPage(replace: true)
            onComplete()
        }
    }

    /// Apply per-video capture dates (Unix ms), each inferred from that video's
    /// filename — used by the grid/list right-click "Set Capture Date to …" /
    /// "from filename" action, where every selected card can resolve to a
    /// different date. Reloads the grid + facets afterward.
    public func setInferredCaptureDates(
        _ perVideo: [(id: String, timestampMs: Int64)],
        onComplete: @escaping () -> Void = {}
    ) {
        guard !perVideo.isEmpty else { return }
        Task {
            var ok = 0
            for entry in perVideo {
                let success = await repository.updateVideoCaptureDate(
                    videoId: entry.id, timestampMs: entry.timestampMs, writeToFile: false)
                if success { ok += 1 }
            }
            NSLog("Set inferred capture date on \(ok)/\(perVideo.count) video(s)")
            if ok > 0 {
                refreshMetadataFacets()
                // Reset to the first page before reloading (see setVideoLocations).
                currentPage = 0
                await loadCurrentPage(replace: true)
            }
            onComplete()
        }
    }
}

/// Proximity filter — kept as a named struct so SwiftUI can compare-and-redraw
/// reliably, and so the parameter list of [GridViewModel] doesn't fan out a
/// half-typed tuple everywhere.
public struct GeoFilter: Equatable {
    public let latitude: Double
    public let longitude: Double
    public let radiusKm: Double

    public init(latitude: Double, longitude: Double, radiusKm: Double) {
        self.latitude = latitude
        self.longitude = longitude
        self.radiusKm = radiusKm
    }
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

/// Persists only the metadata column *layout* (the ordered keys) across
/// sessions; selected values are intentionally transient (reset on relaunch).
private enum LibraryFilterPrefs {
    private static let key = "reelvault.libraryFilter.metadataColumns"

    static func loadColumns() -> [MetadataColumn] {
        let raw = UserDefaults.standard.string(forKey: key) ?? ""
        let cols = raw.split(separator: ",").map { MetadataColumn(key: String($0)) }
        return cols.isEmpty ? defaultMetadataColumns : cols
    }

    static func saveColumns(_ cols: [MetadataColumn]) {
        let joined = cols.filter { !$0.key.isEmpty }.map(\.key).joined(separator: ",")
        UserDefaults.standard.set(joined, forKey: key)
    }
}

/// Persists the grid sort (field + direction) across sessions, so the app
/// reopens with the same ordering the user last chose. Mirrors the desktop
/// client, which stores the same two values in Java `Preferences`.
private enum SortPrefs {
    private static let fieldKey = "reelvault.sort.field"
    private static let ascendingKey = "reelvault.sort.ascending"

    static func loadField() -> String {
        let raw = UserDefaults.standard.string(forKey: fieldKey) ?? ""
        return raw.isEmpty ? "indexed_at" : raw
    }

    static func loadAscending() -> Bool {
        // Absent key → false (descending), matching a fresh install.
        UserDefaults.standard.bool(forKey: ascendingKey)
    }

    static func save(field: String, ascending: Bool) {
        UserDefaults.standard.set(field, forKey: fieldKey)
        UserDefaults.standard.set(ascending, forKey: ascendingKey)
    }
}
