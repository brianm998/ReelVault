// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import Combine

@MainActor
class DetailViewModel: ObservableObject {
    @Published var metadata: VideoMetadata?
    @Published var thumbnail: NSImage?
    @Published var isLoading = false
    @Published var error: String?
    @Published var notes = ""

    @Published var groupMembers: [VideoSummary] = []
    @Published var groupPreferredId: String = ""

    /// Proxies attached to the currently-selected video. Sorted by
    /// pixel count descending (server contract). UI sites can read
    /// `.last` for the smallest proxy (used by inline grid/list
    /// playback) or `.first` for the largest.
    @Published var proxies: [VideoRepository.ProxyInfo] = []

    /// ID of the proxy the user explicitly picked for full-screen
    /// detail-view playback. `nil` means "play the master" (with the
    /// implicit unplayable-master → smallest-proxy fallback applied by
    /// `playbackPath(for:)`).
    @Published var selectedProxyId: String?

    /// Content for the top-bar proxy-playback indicator.
    struct ProxyBanner: Equatable {
        /// True when the user explicitly picked this proxy in the right
        /// panel; false when it's the automatic unplayable-master fallback.
        var selected: Bool
        /// "filename • 1080p"-style detail, or nil when the proxy row is
        /// unknown. Surfaced in the indicator's tooltip.
        var detail: String?
    }

    /// Drives the proxy-playback indicator that now lives in the top bar
    /// (see `ContentView.topBar`) rather than as an overlay on the video.
    /// `nil` hides it — the master is playing, or the loupe isn't on screen.
    /// `DetailLoupeView` sets this as it resolves the effective playback path
    /// and clears it when the loupe is left.
    @Published var proxyBanner: ProxyBanner?

    /// The proxy the loupe is actually playing right now (auto-chosen or the
    /// user's pick), so the right-panel list can highlight which one is playing
    /// by default — without pinning `selectedProxyId` (which stays the user's
    /// explicit choice so they can still revert to the master).
    @Published var playingProxyId: String?

    /// The summary backing the currently-selected card. Exposed so the
    /// right panel's proxy section can decide whether to render itself
    /// (keys off `hasProxies` and `playableNatively`, neither of which
    /// is carried by `VideoMetadata`).
    @Published var currentSummary: VideoSummary?

    private var currentVideoSummary: VideoSummary? { currentSummary }
    private let repository = VideoRepository.shared

    /// Called by the grid when the user selects a video — drives the group/stack
    /// section in addition to the regular metadata load.
    func setCurrentVideo(_ video: VideoSummary) {
        currentSummary = video
        if video.isInGroup {
            loadGroupMembers(groupId: video.groupId)
        } else {
            groupMembers = []
            groupPreferredId = ""
        }
        // Reset proxy selection on each new video, then lazily load the
        // proxy list when the summary advertises any. Servers send
        // `proxyCount == 0` for most rows, so the conditional saves a
        // round trip on the common case.
        selectedProxyId = nil
        if video.hasProxies {
            loadProxies(videoId: video.id)
        } else {
            proxies = []
        }
    }

    private func loadProxies(videoId: String) {
        Task {
            do {
                proxies = try await repository.listProxies(videoId: videoId)
            } catch {
                NSLog("Failed to load proxies for \(videoId): \(error)")
                proxies = []
            }
        }
    }

    /// User picked a specific proxy in the detail-view right panel.
    /// Pass `nil` to revert to playing the master.
    func setSelectedProxy(_ proxyId: String?) {
        selectedProxyId = proxyId
    }

    /// Recompute the top-bar proxy indicator from the current proxy
    /// selection and the player's render height. Called by `DetailLoupeView`
    /// whenever the effective path could change (selection, proxy list,
    /// render-area resize). Sets `proxyBanner` to nil when the master plays.
    func updateProxyBanner(for video: VideoSummary, areaHeightPx: Int) {
        let effective = playbackPath(for: video, areaHeightPx: areaHeightPx) ?? video.path
        if effective != video.path {
            let active = proxies.first(where: { $0.path == effective })
            proxyBanner = ProxyBanner(
                selected: selectedProxyId != nil,
                detail: active.map { "\($0.filename) • \($0.height)p" }
            )
            playingProxyId = active?.id
        } else {
            proxyBanner = nil
            playingProxyId = nil
        }
    }

    /// Hide the top-bar proxy indicator (loupe left, or selection cleared).
    func clearProxyBanner() {
        proxyBanner = nil
        playingProxyId = nil
    }

    /// Break the link between the currently-displayed master and one
    /// of its proxies. Refreshes the proxy list on success.
    func breakProxyLink(proxyId: String, onChanged: @escaping () -> Void = {}) {
        guard let masterId = currentSummary?.id else { return }
        Task {
            let ok = await repository.removeProxyLink(masterId: masterId, proxyId: proxyId)
            if ok {
                do {
                    proxies = try await repository.listProxies(videoId: masterId)
                } catch {
                    NSLog("Reload proxies after break failed: \(error)")
                }
                if selectedProxyId == proxyId { selectedProxyId = nil }
                // A detached proxy becomes a standalone video again — let the
                // grid + left-panel counts catch up.
                onChanged()
            } else {
                error = "Failed to remove proxy link"
            }
        }
    }

    /// Mark `proxyId` as a manual proxy of the currently-displayed
    /// master. Surfaces via the inspector's "Add selected video as
    /// proxy" affordance.
    func forceProxyLink(proxyId: String, onChanged: @escaping () -> Void = {}) {
        guard let masterId = currentSummary?.id else { return }
        guard masterId != proxyId else {
            error = "A video can't be a proxy of itself"
            return
        }
        Task {
            let ok = await repository.setProxyOf(proxyId: proxyId, originalId: masterId)
            if ok {
                do {
                    proxies = try await repository.listProxies(videoId: masterId)
                } catch {
                    NSLog("Reload proxies after force failed: \(error)")
                }
                // A newly-attached proxy stops counting as a standalone video —
                // refresh the grid + left-panel counts.
                onChanged()
            } else {
                error = "Failed to add proxy link"
            }
        }
    }

    /// The on-disk path the detail-view player should load, based on the
    /// current proxy selection and the player's render height `areaHeightPx`
    /// (in px):
    ///   * User explicitly picked a proxy → that proxy's path.
    ///   * A proxy exists → the proxy whose resolution best matches the render
    ///     area (see `chooseProxy(areaHeightPx:)`). We prefer a proxy even for
    ///     a natively-playable master — detail playback defaults to a proxy,
    ///     with the master one click away via the right-panel picker.
    ///   * Otherwise → nil; the caller falls back to the master path.
    func playbackPath(for video: VideoSummary, areaHeightPx: Int = 0) -> String? {
        if let picked = selectedProxyId,
           let row = proxies.first(where: { $0.id == picked }) {
            return row.path
        }
        guard !proxies.isEmpty else { return nil }
        // When the master itself can't play locally (above the configured
        // playable height), restrict to proxies that *can* play — so detail
        // playback defaults to something that actually decodes. If none
        // qualifies, fall back to all (chooseProxy's smallest).
        var candidates = proxies
        if !video.playableNatively {
            let playable = proxies.filter { $0.playableNatively }
            if !playable.isEmpty { candidates = playable }
        }
        return chooseProxy(from: candidates, areaHeightPx: areaHeightPx)?.path
    }

    /// Pick the proxy that best fills a render area `areaHeightPx` px tall: the
    /// smallest proxy still at least as tall as the area (so it isn't upscaled),
    /// or — when the area is taller than every proxy — the largest. Before the
    /// area is measured (`areaHeightPx <= 0`) we fall back to the smallest.
    /// Returns nil when `candidates` is empty. `proxies` is sorted descending by
    /// pixel count (first = largest, last = smallest); `candidates` preserves
    /// that order.
    private func chooseProxy(
        from candidates: [VideoRepository.ProxyInfo],
        areaHeightPx: Int
    ) -> VideoRepository.ProxyInfo? {
        guard !candidates.isEmpty else { return nil }
        if areaHeightPx <= 0 { return candidates.last }
        return candidates.filter { $0.height >= areaHeightPx }.min(by: { $0.height < $1.height })
            ?? candidates.first
    }

    private func loadGroupMembers(groupId: String) {
        Task {
            do {
                let (members, preferred) = try await repository.listGroupMembers(groupId: groupId)
                groupMembers = members
                groupPreferredId = preferred
            } catch {
                NSLog("Failed to load group members: \(error)")
            }
        }
    }

    func setGroupPreferred(videoId: String) {
        guard let groupId = currentVideoSummary?.groupId, !groupId.isEmpty else { return }
        Task {
            do {
                if try await repository.setGroupPreferred(groupId: groupId, videoId: videoId) {
                    groupPreferredId = videoId
                } else {
                    error = "Failed to set preferred video"
                }
            } catch {
                self.error = "Failed to set preferred: \(error.localizedDescription)"
            }
        }
    }

    /// Remove the currently-displayed video from its stack. `onComplete`
    /// fires after the daemon call succeeds — callers wire it to
    /// `GridViewModel.refreshAfterStackChange(groupId:)` so the grid's
    /// representative + expanded-member caches catch up.
    func ungroupCurrent(onComplete: ((_ groupId: String) -> Void)? = nil) {
        guard let videoId = metadata?.id else { return }
        // Capture the *current* group id before we tell the daemon to
        // drop it — `VideoMetadata` doesn't track groupId, so we read it
        // from the `VideoSummary` we cached on selection.
        let oldGroupId = currentVideoSummary?.groupId ?? ""
        Task {
            do {
                if try await repository.ungroupVideo(videoId: videoId) {
                    groupMembers = []
                    groupPreferredId = ""
                    onComplete?(oldGroupId)
                } else {
                    error = "Failed to ungroup"
                }
            } catch {
                self.error = "Failed to ungroup: \(error.localizedDescription)"
            }
        }
    }

    func loadMetadata(videoId: String) {
        isLoading = true
        error = nil

        Task {
            do {
                let meta = try await repository.getVideoMetadata(videoId: videoId)
                self.metadata = meta
                self.notes = meta.notes
                self.isLoading = false
                await loadThumbnail(videoId: videoId)
            } catch {
                self.error = "Failed to load metadata: \(error.localizedDescription)"
                self.isLoading = false
            }
        }
    }

    private func loadThumbnail(videoId: String) async {
        do {
            thumbnail = try await repository.getThumbnail(videoId: videoId, size: "large")
        } catch {
            NSLog("Failed to load thumbnail: \(error)")
        }
    }

    func updateNotes(_ newNotes: String) {
        notes = newNotes
        guard let videoId = metadata?.id else { return }
        Task {
            do {
                let ok = try await repository.updateVideoNotes(videoId: videoId, notes: newNotes)
                if !ok { error = "Failed to update notes" }
            } catch {
                self.error = "Failed to update notes: \(error.localizedDescription)"
            }
        }
    }

    func clearError() { error = nil }

    func clear() {
        metadata = nil
        thumbnail = nil
        notes = ""
        error = nil
        groupMembers = []
        groupPreferredId = ""
        currentSummary = nil
        proxies = []
        selectedProxyId = nil
    }
}
