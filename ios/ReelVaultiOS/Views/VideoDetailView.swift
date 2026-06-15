// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AVFoundation
import AVKit
import Photos
import SwiftUI
import UIKit
import ReelVaultKit

/// Full-screen detail screen (iPhone / compact width): a streaming player
/// (downscaled to fit) plus metadata. Pushed onto the navigation stack when a
/// card is tapped.
struct VideoDetailView: View {
    let video: VideoSummary
    /// Shared view-model — lets the lower editor section change rating / colour /
    /// keywords (the edits sync to every client).
    @ObservedObject var grid: GridViewModel
    let mediaEndpoint: AppRouter.ConnectionInfo?
    /// Switch to the internal Map mode focused on this video (the "Show on Map"
    /// action). On iPhone the host pops this pushed screen and shows Map mode.
    var onShowOnMap: (() -> Void)? = nil
    @StateObject private var stream = StreamPlayer()
    @State private var fullScreen = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                StreamingPlayerView(stream: stream, video: video, endpoint: mediaEndpoint,
                                    refreshTick: grid.catalogChangeTick,
                                    isFullScreenActive: fullScreen)
                OfflineDownloadButton(video: video, endpoint: mediaEndpoint)
                MetadataEditorSection(grid: grid, videoId: video.id)
                // Prefer the live grid row so the proxy-count badge tracks edits
                // and newly-created proxies; refreshTick re-fetches the list.
                VideoMetadataSection(
                    video: grid.videos.first(where: { $0.id == video.id }) ?? video,
                    refreshTick: grid.catalogChangeTick)
                VideoDetailExtras(grid: grid,
                                  video: grid.videos.first(where: { $0.id == video.id }) ?? video)
                LocationButtonsSection(grid: grid, videoId: video.id, onShowOnMap: onShowOnMap)
                DetailGraphsView(grid: grid, videoId: video.id)
            }
            .padding()
        }
        .navigationTitle(video.filename)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Full-screen playback — available on iPhone too (not just the iPad
            // Detail mode), so compact users aren't stuck with the small inline player.
            ToolbarItem(placement: .topBarTrailing) {
                Button { fullScreen = true } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .help("Full screen")
            }
        }
        .fullScreenCover(isPresented: $fullScreen) {
            // Shares the SAME StreamPlayer as the inline view → one AVPlayer, no
            // doubled/offset audio (the K4 fix).
            FullScreenPlayer(stream: stream, video: video, endpoint: mediaEndpoint)
        }
    }
}

/// Owns a single `AVPlayer` for a streamed rendition. Sharing one instance
/// between the inline detail player and the full-screen cover guarantees the
/// same video never plays twice at once (which produced doubled, offset audio).
@MainActor
final class StreamPlayer: ObservableObject {
    @Published var player: AVPlayer?
    @Published var isPreparing = false
    @Published var error: String?
    /// While preparing a sub-realtime HLS re-encode, an ETA like
    /// "Preparing… ready in ~12s" for the spinner; nil when ready/unknown.
    @Published var preparingDetail: String?
    /// User-chosen playback rendition height: nil = auto (fit the device), 0 = the
    /// original full-resolution file, else a specific proxy height. Streaming is
    /// height-based on iOS (remote mode), so the server serves the proxy closest
    /// to the requested height. Changed via `selectRendition`.
    @Published var renditionOverride: Int?
    /// The vertical resolution actually being played, read from the player item's
    /// `presentationSize` once the video track loads — so the picker can show the
    /// resolved quality (e.g. "Auto (720p)") rather than just "Auto".
    @Published var playingHeight: Int?
    private var preparedVideoId: String?
    /// The height the current player item was prepared at, so changing the
    /// rendition forces a re-prepare instead of a no-op.
    private var preparedHeight: Int?
    /// Security-scoped URL for a Files-imported (`bookmark://`) video; its scope
    /// must stay open while the player reads it, released on teardown/deinit.
    private var scopedPlaybackURL: URL?
    /// Live loopback proxy backing an HLS stream; retained for the player's
    /// lifetime (segments 502 if it deallocs mid-playback).
    private var proxy: LoopbackMediaProxy?
    /// Diagnostics for the current item: the "unable to play" triangle otherwise
    /// fails silently. The HLS error log names the failing segment URI + HTTP
    /// status, which (with the loopback-proxy trace and the daemon log) pins down
    /// intermittent failures.
    private var diagObservers: [NSObjectProtocol] = []
    private var statusObservation: NSKeyValueObservation?
    private var presentationObservation: NSKeyValueObservation?
    /// The in-flight prepare, owned by the player (NOT a transient SwiftUI view
    /// `.task`), so presenting or dismissing the full-screen cover — which mounts
    /// and cancels view tasks — can never cancel a transcode wait mid-flight and
    /// strand a half-built player item (the "unplayable" glyph that never
    /// recovered). Cancelled only by a new rendition / video, never by a view.
    private var prepareTask: Task<Void, Never>?
    /// The (id, height) the in-flight prepare targets, so a duplicate `prepare`
    /// for the same rendition joins it instead of starting a second.
    private var preparingVideoId: String?
    private var preparingHeight: Int?
    /// The video the player is bound to (preparing OR prepared). `resetIfDifferent`
    /// keys teardown off THIS, not `preparedVideoId` (which is nil mid-prepare) —
    /// otherwise the full-screen cover re-running `resetIfDifferent` for the SAME
    /// video would tear down the in-flight loopback proxy + transcode wait.
    private var currentVideoId: String?
    /// Whether to start playback once the in-flight prepare lands. A later play
    /// request (e.g. the full-screen cover's autoPlay) can flip this on while the
    /// prepare is still waiting on the transcode.
    private var autoPlayWhenReady = true
    /// When a slow transcode forces a progressive (EVENT-playlist) start, this task
    /// watches `/status` and — once the server finishes (ENDLIST → seekable VOD) —
    /// rebuilds the player on the now-VOD playlist at the same playhead, so the
    /// scrub bar appears. Without it a capped start plays forever with no scrubber.
    private var vodUpgradeTask: Task<Void, Never>?

    /// Prepare the rendition and (optionally) start playing — idempotent and
    /// view-lifecycle-independent. The work runs in `prepareTask`, owned by the
    /// player, so presenting/dismissing the full-screen cover can't cancel it.
    /// A healthy, already-prepared player just (re)plays; a duplicate call for the
    /// same rendition joins the in-flight prepare (and can upgrade it to autoplay);
    /// otherwise a fresh prepare starts. `autoPlay == false` (a quality change)
    /// loads the rendition but stays paused.
    func prepare(video: VideoSummary, endpoint: AppRouter.ConnectionInfo?, autoPlay: Bool = true) {
        currentVideoId = video.id
        // Effective rendition height: the user's override (0 = original), else a
        // fit-to-device height so big originals don't stream raw over Wi-Fi.
        let height = renditionOverride ?? Self.streamHeight()
        // Already have a *healthy* player at this rendition → just (re)play. A
        // failed item is NOT healthy, so it falls through to a fresh prepare
        // (otherwise the user stayed stuck on the "unplayable" glyph forever).
        if preparedVideoId == video.id, preparedHeight == height,
           let p = player, p.currentItem?.status != .failed {
            if autoPlay { p.play() }
            return
        }
        // A prepare for this exact rendition is already running → join it rather
        // than starting a second (which would race two transcodes / two players).
        if prepareTask != nil, preparingVideoId == video.id, preparingHeight == height {
            if autoPlay { autoPlayWhenReady = true }
            return
        }
        startPrepare(video: video, endpoint: endpoint, height: height, autoPlay: autoPlay)
    }

    /// Launch (or relaunch) the owned prepare task, cancelling any in-flight one.
    private func startPrepare(video: VideoSummary, endpoint: AppRouter.ConnectionInfo?,
                              height: Int, autoPlay: Bool) {
        prepareTask?.cancel()
        vodUpgradeTask?.cancel()   // a fresh prepare invalidates any pending VOD swap
        preparingVideoId = video.id
        preparingHeight = height
        autoPlayWhenReady = autoPlay
        isPreparing = true
        prepareTask = Task { [weak self] in
            await self?.runPrepare(video: video, endpoint: endpoint, height: height)
        }
    }

    /// The actual prepare pipeline — HLS-first, download fallback, or local —
    /// running inside `prepareTask`. Bails WITHOUT building a player if superseded
    /// (`Task.isCancelled`), so a torn-down/superseded prepare never strands a
    /// half-built item on an incomplete transcode.
    private func runPrepare(video: VideoSummary, endpoint: AppRouter.ConnectionInfo?, height: Int) async {
        defer {
            // Clear in-flight state only if THIS run is still the active one — a
            // newer startPrepare may have superseded us and overwritten the
            // targets (then it owns isPreparing / prepareTask, not us).
            if preparingVideoId == video.id, preparingHeight == height {
                isPreparing = false
                preparingDetail = nil
                preparingVideoId = nil
                preparingHeight = nil
                prepareTask = nil
            }
        }
        error = nil
        // On-device (Local Library) mode: no media server / streaming — the
        // original lives on this device, so play it directly with AVPlayer from
        // the Photos asset (or file) the catalog row points at.
        guard let conn = endpoint else {
            await playLocal(video: video)
            return
        }
        let mediaEndpoint = MediaClient.Endpoint(
            host: conn.host, mediaPort: conn.mediaPort,
            fingerprintHex: conn.fingerprintHex, bearerToken: conn.bearerToken)

        // 1) HLS streaming via the loopback proxy. Drive it from /status: wait
        //    (spinner + ETA) until enough is transcoded to play smoothly, THEN
        //    build a fresh player on the now multi-segment playlist and play. We
        //    deliberately do NOT create the player early and hold it — that left
        //    AVPlayer fetching the playlist but never a segment.
        teardownProxy()
        let proxy = LoopbackMediaProxy(endpoint: mediaEndpoint)
        do {
            _ = try await proxy.start()
            // Superseded during startup → don't install our proxy over a newer
            // run's (which already ran teardownProxy + set its own).
            if Task.isCancelled { proxy.stop(); return }
            self.proxy = proxy
            let outcome = await waitUntilReady(proxy: proxy, video: video, height: height)
            // Superseded while we waited (new video / rendition) → abandon without
            // building a player; the newer prepare owns the UI now. Tear down only
            // OUR proxy (===), never a newer run's.
            if Task.isCancelled { abandonProxy(proxy); return }
            if outcome != .failed {
                let asset = AVURLAsset(url: proxy.hlsURL(videoId: video.id, height: height))
                if (try? await asset.load(.isPlayable)) == true {
                    if Task.isCancelled { abandonProxy(proxy); return }
                    let item = AVPlayerItem(asset: asset)
                    attachDiagnostics(to: item)
                    let p = AVPlayer(playerItem: item)
                    player = p
                    preparedVideoId = video.id
                    preparedHeight = height
                    if autoPlayWhenReady { p.play() }
                    NSLog("ReelVault: HLS \(autoPlayWhenReady ? "playing" : "ready (paused)") \(video.id) (h\(height), \(outcome))")
                    // Capped = playing an in-progress EVENT playlist (no scrub bar).
                    // Watch for the transcode to finish and swap to the seekable VOD
                    // so the scrubber appears once it's ready.
                    if outcome == .capped {
                        scheduleVODUpgrade(proxy: proxy, video: video, height: height)
                    }
                    return
                }
                NSLog("ReelVault: HLS asset not playable for \(video.id) (h\(height)) — falling back to download")
            } else {
                NSLog("ReelVault: HLS transcode failed for \(video.id) (h\(height)) — falling back to download")
            }
        } catch {
            NSLog("ReelVault: HLS proxy start failed for \(video.id): \(error) — falling back to download")
        }
        abandonProxy(proxy)
        if Task.isCancelled { return }

        // 2) Fallback: download-then-play (pin-verified, correct but not progressive).
        do {
            let item = try await MediaClient().playerItem(
                videoId: video.id, height: height, ext: "mp4", from: mediaEndpoint)
            if Task.isCancelled { return }
            attachDiagnostics(to: item)
            let p = AVPlayer(playerItem: item)
            player = p
            preparedVideoId = video.id
            preparedHeight = height
            if autoPlayWhenReady { p.play() }
        } catch {
            NSLog("ReelVault: playback prepare failed for \(video.id) (h\(height)): \(error)")
            self.error = "Couldn't play this video: \(error.localizedDescription)"
        }
    }

    /// Stop a loopback proxy this run started, clearing the shared reference only
    /// if it's still ours — a newer prepare may already have installed its own.
    private func abandonProxy(_ proxy: LoopbackMediaProxy) {
        proxy.stop()
        if self.proxy === proxy { self.proxy = nil }
    }

    /// After a progressive (capped) start, watch the server's `/status` until the
    /// transcode completes, then rebuild the player on the now-finished (ENDLIST =
    /// seekable VOD) playlist at the same playhead — so the scrub bar appears.
    private func scheduleVODUpgrade(proxy: LoopbackMediaProxy, video: VideoSummary, height: Int) {
        vodUpgradeTask?.cancel()
        vodUpgradeTask = Task { [weak self] in
            await self?.awaitVODAndUpgrade(proxy: proxy, video: video, height: height)
        }
    }

    private func awaitVODAndUpgrade(proxy: LoopbackMediaProxy, video: VideoSummary, height: Int) async {
        let statusURL = proxy.statusURL(videoId: video.id, height: height)
        // Poll until the transcode finishes (ENDLIST written) or fails. No cap: a
        // very slow transcode just keeps the EVENT player going until it lands.
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let s = await fetchStatus(statusURL) else { continue }
            if s.failed { return }
            if s.complete { break }
        }
        // Still the same rendition playing, on our proxy, and not torn down?
        guard !Task.isCancelled, self.proxy === proxy,
              preparedVideoId == video.id, preparedHeight == height,
              let old = player else { return }
        let resumeAt = old.currentTime()
        let wasPlaying = old.rate > 0
        let asset = AVURLAsset(url: proxy.hlsURL(videoId: video.id, height: height))
        guard (try? await asset.load(.isPlayable)) == true, !Task.isCancelled,
              self.proxy === proxy, preparedVideoId == video.id, preparedHeight == height
        else { return }
        old.pause()
        let item = AVPlayerItem(asset: asset)
        attachDiagnostics(to: item)
        let p = AVPlayer(playerItem: item)
        // Land at the same spot the EVENT player reached, then resume if it was playing.
        await p.seek(to: resumeAt, toleranceBefore: .zero, toleranceAfter: .zero)
        guard !Task.isCancelled, preparedVideoId == video.id, preparedHeight == height else { return }
        player = p
        if wasPlaying { p.play() }
        NSLog("ReelVault: HLS upgraded to seekable VOD \(video.id) (h\(height)) at \(Int(CMTimeGetSeconds(resumeAt)))s")
    }

    /// On-device playback: resolve the catalog row's source to a local
    /// `AVPlayerItem` and play it directly — Photos asset via `PHImageManager`
    /// (handles iCloud download), or a file path. No transcode/stream. Runs inside
    /// `runPrepare` (which owns `isPreparing` / cancellation), so it just builds
    /// and starts the item.
    private func playLocal(video: VideoSummary) async {
        if preparedVideoId == video.id, let p = player, p.currentItem?.status != .failed {
            if autoPlayWhenReady { p.play() }
            return
        }
        // Release any bookmark scope from a prior item first: a rapid switch can
        // re-enter before resetIfDifferent runs, which would otherwise orphan the
        // previous security-scoped URL.
        scopedPlaybackURL?.stopAccessingSecurityScopedResource()
        scopedPlaybackURL = nil

        let path = video.openPath
        let item: AVPlayerItem?
        if let localId = Self.photosLocalIdentifier(from: path) {
            item = await Self.photosPlayerItem(localIdentifier: localId)
        } else if path.hasPrefix("bookmark://") {
            // Files-imported video: re-resolve the security-scoped bookmark and
            // hold the scope open for the player's lifetime (released in
            // resetIfDifferent / deinit).
            let hex = String(path.dropFirst("bookmark://".count))
            if let url = NativeMedia.resolveBookmark(hex: hex) {
                if url.startAccessingSecurityScopedResource() { scopedPlaybackURL = url }
                item = AVPlayerItem(url: url)
            } else {
                item = nil
            }
        } else if path.hasPrefix("file://"), let url = URL(string: path) {
            item = AVPlayerItem(url: url)
        } else if !path.isEmpty {
            item = AVPlayerItem(url: URL(fileURLWithPath: path))
        } else {
            item = nil
        }

        // Superseded (new selection) while resolving the asset → abandon.
        if Task.isCancelled {
            scopedPlaybackURL?.stopAccessingSecurityScopedResource()
            scopedPlaybackURL = nil
            return
        }
        guard let item else {
            error = "Couldn't open this video on-device."
            NSLog("ReelVault: local playback could not resolve \(video.id) (path \(path))")
            return
        }
        attachDiagnostics(to: item)
        let p = AVPlayer(playerItem: item)
        player = p
        preparedVideoId = video.id
        if autoPlayWhenReady { p.play() }
        NSLog("ReelVault: local playback \(video.id) (\(path))")
    }

    /// `photos://<localIdentifier>` → the PHAsset localIdentifier.
    static func photosLocalIdentifier(from path: String) -> String? {
        let prefix = "photos://"
        guard path.hasPrefix(prefix) else { return nil }
        return String(path.dropFirst(prefix.count))
    }

    /// An `AVPlayerItem` for a Photos video, downloading from iCloud if needed.
    static func photosPlayerItem(localIdentifier: String) async -> AVPlayerItem? {
        let fetch = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = fetch.firstObject else { return nil }
        return await withCheckedContinuation { (cont: CheckedContinuation<AVPlayerItem?, Never>) in
            let opts = PHVideoRequestOptions()
            opts.isNetworkAccessAllowed = true
            opts.deliveryMode = .automatic
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: opts) { item, _ in
                cont.resume(returning: item)
            }
        }
    }

    /// Tear down when the view's video changes, so a new selection doesn't keep
    /// playing the previous one. Keys off the bound video (preparing OR prepared),
    /// NOT `preparedVideoId` (nil mid-prepare): re-running this for the SAME video
    /// — e.g. when the full-screen cover mounts and runs its own `.task` — must
    /// not tear down an in-flight prepare (its loopback proxy + transcode wait).
    func resetIfDifferent(_ videoId: String) {
        guard let current = currentVideoId else { currentVideoId = videoId; return }
        if current == videoId { return }
        prepareTask?.cancel()
        prepareTask = nil
        vodUpgradeTask?.cancel()
        vodUpgradeTask = nil
        preparingVideoId = nil
        preparingHeight = nil
        isPreparing = false
        player?.pause()
        player = nil
        preparedVideoId = nil
        preparedHeight = nil
        renditionOverride = nil   // a new clip starts at Auto
        scopedPlaybackURL?.stopAccessingSecurityScopedResource()
        scopedPlaybackURL = nil
        error = nil
        preparingDetail = nil
        teardownProxy()
        clearDiagnostics()
        currentVideoId = videoId
    }

    /// Switch the playback rendition (Auto / Original / a specific proxy height)
    /// and re-stream the current video at that height. No-op in Local mode (the
    /// on-device original is played directly; there are no renditions).
    func selectRendition(_ height: Int?, video: VideoSummary, endpoint: AppRouter.ConnectionInfo?) {
        guard endpoint != nil else { return }
        currentVideoId = video.id
        renditionOverride = height
        // Force a fresh prepare even for the same video; startPrepare cancels any
        // in-flight one, so switching rendition mid-prepare supersedes cleanly.
        preparedVideoId = nil
        preparedHeight = nil
        player?.pause()
        player = nil
        teardownProxy()
        clearDiagnostics()
        // Load the new rendition but leave it paused — changing quality shouldn't
        // auto-start playback; the user presses play.
        startPrepare(video: video, endpoint: endpoint,
                     height: height ?? Self.streamHeight(), autoPlay: false)
    }

    deinit {
        // Release a held Files bookmark scope if the player is torn down without
        // a resetIfDifferent (e.g. the detail view simply disappears).
        scopedPlaybackURL?.stopAccessingSecurityScopedResource()
    }

    enum ReadyOutcome: Equatable, CustomStringConvertible {
        case ready, capped, failed
        var description: String {
            switch self {
            case .ready: return "ready"
            case .capped: return "cap-reached"
            case .failed: return "failed"
            }
        }
    }

    /// Poll the server's HLS `/status` (which also starts the transcode) until the
    /// session is **complete**, then play. A finished playlist (with `ENDLIST`)
    /// loads as a seekable VOD — so the player gets a scrub bar and can replay;
    /// an in-progress EVENT playlist plays "live" with NO scrubber, which is what
    /// users hit on videos that were still transcoding. Copy-muxed renditions
    /// (the common case) complete in ~a second, so the wait is tiny; a re-encode
    /// takes longer and shows an ETA. `.failed` → caller falls back to download;
    /// the wait cap → `.capped` so a very slow transcode still plays (progressively,
    /// without a scrub bar until it finishes — and it promotes a proxy so the next
    /// play is an instant, seekable copy-mux).
    private func waitUntilReady(proxy: LoopbackMediaProxy, video: VideoSummary, height: Int) async -> ReadyOutcome {
        let durationSec = max(1, Double(video.durationMs) / 1000.0)
        let statusURL = proxy.statusURL(videoId: video.id, height: height)
        let start = Date()
        let maxWait: TimeInterval = 60
        preparingDetail = "Preparing…"
        // A short window of samples → a smoothed transcode rate that ignores the
        // 0→1 segment jump (which looked like an infinitely fast encoder).
        var samples: [(buffered: Double, at: Date)] = []
        while Date().timeIntervalSince(start) < maxWait {
            if Task.isCancelled { return .ready }
            guard let status = await fetchStatus(statusURL) else { return .ready }
            if status.failed { return .failed }
            if status.complete { preparingDetail = nil; return .ready }
            // Not complete yet — estimate the time until the whole rendition is
            // transcoded (so the playlist gets its ENDLIST = a seekable VOD).
            let buffered = Double(status.segments * status.segSeconds)
            let now = Date()
            samples.append((buffered, now))
            if samples.count > 6 { samples.removeFirst() }
            var rate = 0.0
            if let first = samples.first, samples.count >= 2 {
                let dt = now.timeIntervalSince(first.at)
                if dt > 0.5 { rate = max(0, (buffered - first.buffered) / dt) }
            }
            if now.timeIntervalSince(start) >= 2.5 {
                if rate > 0.05 {
                    let eta = max(0, (durationSec - buffered) / rate)
                    preparingDetail = "Preparing… ready in ~\(Int(eta.rounded()))s"
                } else {
                    preparingDetail = "Preparing…"
                }
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        NSLog("ReelVault: HLS not complete within cap for \(video.id) — playing progressively (no scrub bar until it finishes)")
        preparingDetail = nil
        return .capped
    }

    private struct HLSStatus { let segments: Int; let complete: Bool; let failed: Bool; let segSeconds: Int }

    private func fetchStatus(_ url: URL) async -> HLSStatus? {
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return HLSStatus(
            segments: (obj["segments"] as? Int) ?? 0,
            complete: (obj["complete"] as? Bool) ?? false,
            failed: (obj["failed"] as? Bool) ?? false,
            segSeconds: (obj["segSeconds"] as? Int) ?? 4)
    }

    private func teardownProxy() {
        proxy?.stop()
        proxy = nil
    }

    /// The detail screen is going away (popped / view-mode switched) — NOT just
    /// covered by the full-screen player. Pause playback; if we're still preparing
    /// (no player built yet), also cancel the owned transcode wait + loopback proxy
    /// so we don't strand a server-side transcode for a screen the user left. A
    /// built player is kept (paused) for instant resume, and its proxy must stay
    /// alive (the HLS segments 502 if it deallocs mid-playback).
    func leaveScreen() {
        player?.pause()
        if player == nil {
            prepareTask?.cancel()
            prepareTask = nil
            vodUpgradeTask?.cancel()
            vodUpgradeTask = nil
            preparingVideoId = nil
            preparingHeight = nil
            isPreparing = false
            preparingDetail = nil
            teardownProxy()
        }
    }

    /// Log a playback failure (the silent "unable to play" triangle) with the
    /// AVPlayer error log — which records the failing segment URI + HTTP status —
    /// plus stalls and the item's terminal error.
    private func attachDiagnostics(to item: AVPlayerItem) {
        clearDiagnostics()
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] obsItem, _ in
            guard obsItem.status == .failed else { return }
            NSLog("ReelVault player: item FAILED — \(obsItem.error?.localizedDescription ?? "unknown error")")
            // Drop the dead player so the poster + play button return and a tap (or
            // a re-prepare) can recover — instead of leaving the user staring at the
            // "unplayable" glyph forever. Guard on the *live* item still being
            // failed so we never clear a freshly-built healthy player.
            Task { @MainActor in
                guard let self, self.player?.currentItem?.status == .failed else { return }
                self.vodUpgradeTask?.cancel()
                self.vodUpgradeTask = nil
                self.player = nil
                self.preparedVideoId = nil
                self.preparedHeight = nil
                self.error = "Couldn't play this video. Tap play to try again."
            }
        }
        // The decoded video size becomes known once the track loads; surface its
        // height so the picker can show the resolved quality ("Auto (720p)").
        playingHeight = nil
        presentationObservation = item.observe(\.presentationSize, options: [.new, .initial]) { [weak self] obsItem, _ in
            let h = Int(obsItem.presentationSize.height.rounded())
            guard h > 0 else { return }
            // Capture the item identity (Sendable) so a write enqueued before a
            // supersede can't stamp a stale height onto the new clip's label.
            let itemID = ObjectIdentifier(obsItem)
            Task { @MainActor [weak self] in
                guard let self, let current = self.player?.currentItem,
                      ObjectIdentifier(current) == itemID else { return }
                self.playingHeight = h
            }
        }
        let nc = NotificationCenter.default
        diagObservers.append(nc.addObserver(
            forName: .AVPlayerItemNewErrorLogEntry, object: item, queue: .main
        ) { [weak item] _ in
            guard let event = item?.errorLog()?.events.last else { return }
            NSLog("ReelVault player: HLS error — status=\(event.errorStatusCode) domain=\(event.errorDomain) uri=\(event.uri ?? "—") comment=\(event.errorComment ?? "—")")
        })
        diagObservers.append(nc.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            NSLog("ReelVault player: failed to play to end — \(err?.localizedDescription ?? "unknown")")
        })
        diagObservers.append(nc.addObserver(
            forName: .AVPlayerItemPlaybackStalled, object: item, queue: .main
        ) { _ in
            NSLog("ReelVault player: playback stalled (buffering / waiting on segments)")
        })
    }

    private func clearDiagnostics() {
        statusObservation?.invalidate()
        statusObservation = nil
        presentationObservation?.invalidate()
        presentationObservation = nil
        playingHeight = nil
        diagObservers.forEach { NotificationCenter.default.removeObserver($0) }
        diagObservers.removeAll()
    }

    func pause() { player?.pause() }

    /// Target playback height: the device's native pixel height, capped at 1440
    /// so a no-proxy fallback still transcodes down to a Wi-Fi-friendly size.
    static func streamHeight() -> Int {
        let native = Int(UIScreen.main.nativeBounds.height)
        return min(max(native, 480), 1440)
    }
}

/// The player surface, bound to a (possibly shared) `StreamPlayer`.
struct StreamingPlayerView: View {
    @ObservedObject var stream: StreamPlayer
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    /// Begin playing as soon as the view appears (full-screen mode).
    var autoPlay: Bool = false
    /// Fill the available space instead of a 16:9 box (full-screen mode).
    var fill: Bool = false
    /// Bumped on catalog-change events so the rendition (Quality) menu re-fetches
    /// its proxy list — a proxy the server just promoted from this HLS stream then
    /// appears as a quality option live, with no manual refresh.
    var refreshTick: Int = 0
    /// True while the full-screen cover is presented over this inline player. The
    /// cover shares this view's `StreamPlayer`, so when presenting it makes the
    /// inline view disappear — we must NOT treat that as "left the screen" (which
    /// would pause / tear down the playback the cover just took over).
    var isFullScreenActive: Bool = false
    /// A poster frame so the idle player shows the video with a play overlay
    /// instead of a black rectangle.
    @State private var poster: PlatformImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            playerBox
            if let error = stream.error {
                Text(error).font(.footnote).foregroundStyle(.secondary)
            }
            // Rendition chooser — remote mode only (Local plays the original
            // directly), and not in the full-screen cover.
            if !fill, endpoint != nil {
                RenditionPicker(stream: stream, video: video, endpoint: endpoint,
                                refreshTick: refreshTick)
            }
        }
        .task(id: video.id) {
            stream.resetIfDifferent(video.id)
            if autoPlay { stream.prepare(video: video, endpoint: endpoint) }
        }
        .task(id: video.id) {
            // Poster frame for the pre-play state. Prefer a hi-res frame; fall
            // back to the medium thumbnail the grid already caches.
            poster = nil
            if let img = await VideoRepository.shared.getThumbnailHiRes(
                videoId: video.id, size: "large", maxWidth: 1280) {
                poster = img
            } else {
                poster = try? await VideoRepository.shared.getThumbnail(videoId: video.id)
            }
        }
        // Leaving the screen (pop / view-mode switch) pauses + frees an in-flight
        // transcode. Skip when the full-screen cover is presented (it shares this
        // player and is taking over) or in the cover itself (fill).
        .onDisappear { if !fill && !isFullScreenActive { stream.leaveScreen() } }
    }

    @ViewBuilder private var playerBox: some View {
        let box = ZStack {
            if let player = stream.player {
                VideoPlayer(player: player)
                    .clipShape(RoundedRectangle(cornerRadius: fill ? 0 : 10))
            } else {
                // Idle / preparing: show the poster frame (or black) behind the
                // controls so the user sees the clip, not a black box.
                posterBackground
                if stream.isPreparing {
                    VStack(spacing: 8) {
                        ProgressView().tint(.white)
                        if let detail = stream.preparingDetail {
                            Text(detail).font(.caption).foregroundStyle(.white.opacity(0.85))
                        }
                    }
                } else {
                    Button {
                        stream.prepare(video: video, endpoint: endpoint)
                    } label: {
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 54))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .black.opacity(0.35))
                            .shadow(radius: 6)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        if fill {
            box.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            box.aspectRatio(16.0 / 9.0, contentMode: .fit)
        }
    }

    @ViewBuilder private var posterBackground: some View {
        ZStack {
            Color.black
            if let poster {
                Image(uiImage: poster)
                    .resizable()
                    .scaledToFit()
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: fill ? 0 : 10))
    }
}

/// Lets the user pick which rendition to play (the iOS counterpart of the
/// desktop/macOS proxy chooser). iOS streams by height through the media server,
/// so the options are Auto (fit the device), Original (full resolution), and one
/// per available proxy — each re-streams at that height. Reflects the current
/// choice with a checkmark.
struct RenditionPicker: View {
    @ObservedObject var stream: StreamPlayer
    let video: VideoSummary
    let endpoint: AppRouter.ConnectionInfo?
    /// Re-fetch the proxy list when this changes (catalog-change events) so a
    /// server-promoted proxy becomes a quality option without leaving the view.
    var refreshTick: Int = 0
    @State private var proxies: [VideoRepository.ProxyInfo] = []

    var body: some View {
        Menu {
            // Auto first, annotated with the resolution it resolved to (when
            // playing); then Original at the source resolution; then every proxy,
            // largest first (a quality ladder).
            choice(autoMenuLabel, height: nil)
            choice(originalLabel, height: 0)
            if !proxies.isEmpty {
                Divider()
                ForEach(proxies.sorted { $0.height > $1.height }) { p in
                    choice(proxyLabel(p), height: p.height)
                }
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "slider.horizontal.3")
                Text("Quality: \(currentLabel)")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        // Disabled while a prepare/readiness wait runs, so a quality change can't
        // race the in-flight prepare (which would silently no-op).
        .disabled(stream.isPreparing)
        .task(id: "\(video.id)#\(refreshTick)") {
            proxies = (try? await VideoRepository.shared.listProxies(videoId: video.id)) ?? []
        }
    }

    /// The label on the picker button — shows the *actual* resolution playing so
    /// the user knows what "Auto"/"Original" resolved to (issue: Auto gave no hint).
    private var currentLabel: String {
        switch stream.renditionOverride {
        case nil: return stream.playingHeight.map { "Auto (\($0)p)" } ?? "Auto"
        case 0: return stream.playingHeight.map { "Original (\($0)p)" } ?? originalLabel
        case let h?: return "\(h)p"
        }
    }

    /// Auto menu row: annotate with the resolved height only while Auto is the
    /// active choice (that's when `playingHeight` reflects Auto's pick).
    private var autoMenuLabel: String {
        if stream.renditionOverride == nil, let h = stream.playingHeight { return "Auto (\(h)p)" }
        return "Auto"
    }

    /// "Original (2160p)" using the source's own resolution (so the menu names the
    /// full quality), or just "Original" when the height is unknown.
    private var originalLabel: String {
        video.height > 0 ? "Original (\(video.height)p)" : "Original (full)"
    }

    private func proxyLabel(_ p: VideoRepository.ProxyInfo) -> String {
        p.height > 0 ? "\(p.height)p" : p.filename
    }

    @ViewBuilder private func choice(_ title: String, height: Int?) -> some View {
        Button {
            stream.selectRendition(height, video: video, endpoint: endpoint)
        } label: {
            if stream.renditionOverride == height {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
    }
}

/// The read-only metadata list shown in the inspector (iPad), Detail mode, and
/// the compact (iPhone) detail screen: technical details, a named status-badge
/// list, and the proxy list (fetched per selection).
struct VideoMetadataSection: View {
    let video: VideoSummary
    /// Bumped on catalog change events; re-fetches the proxy list so a proxy
    /// created server-side (e.g. promoted from an HLS stream while this video
    /// played) appears without leaving and reopening the view.
    var refreshTick: Int = 0
    /// Drop the "Details" headline when hosted inside a CollapsibleSection (which
    /// supplies its own header) — the inspector does this.
    var showHeader: Bool = true
    @State private var proxies: [VideoRepository.ProxyInfo] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            detailsGroup
            StatusBadgeList(video: video)
            if !proxies.isEmpty { proxiesGroup }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Proxy details aren't on VideoSummary (only a count) — fetch the list
        // for the selected video, same as the macOS inspector. Re-fetch when the
        // catalog changes (refreshTick) so newly-created proxies show live.
        .task(id: "\(video.id)#\(refreshTick)") {
            proxies = (try? await VideoRepository.shared.listProxies(videoId: video.id)) ?? []
        }
    }

    private var detailsGroup: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showHeader { Text("Details").font(.headline) }
            detailRow("File", video.filename)
            if video.width > 0 && video.height > 0 {
                detailRow("Resolution", video.resolution)
            }
            if !video.codecVideo.isEmpty {
                detailRow("Codec", video.codecVideo)
            }
            if video.durationMs > 0 {
                detailRow("Duration", video.durationFormatted)
            }
            if !video.cameraDisplayName.isEmpty {
                detailRow("Camera", video.cameraDisplayName)
            }
            if !video.lensModel.isEmpty {
                detailRow("Lens", video.lensModel)
            }
            if video.hasLocation {
                detailRow("Location", String(format: "%.5f, %.5f", video.gpsLatitude, video.gpsLongitude))
            }
            // The server-side file path is intentionally omitted — it's
            // meaningless on a remote iOS client with no filesystem access.
        }
    }

    private var proxiesGroup: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Proxies (\(proxies.count))").font(.headline)
            ForEach(proxies) { proxy in
                VStack(alignment: .leading, spacing: 2) {
                    Text(proxy.filename).font(.callout).lineLimit(1)
                    Text(Self.proxyDetail(proxy)).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    /// "720p • 125 MB • auto-detected" — matches the macOS proxy row.
    private static func proxyDetail(_ proxy: VideoRepository.ProxyInfo) -> String {
        var parts: [String] = []
        if proxy.height > 0 { parts.append("\(proxy.height)p") }
        if proxy.sizeBytes > 0 {
            parts.append(ByteCountFormatter.string(fromByteCount: proxy.sizeBytes, countStyle: .file))
        }
        if proxy.autoDetected { parts.append("auto-detected") }
        return parts.joined(separator: " • ")
    }

    @ViewBuilder private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .foregroundStyle(.secondary)
                .frame(width: 90, alignment: .leading)
            Text(value).textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .font(.callout)
    }
}

/// Editable metadata for the detail view: rating (stars), colour label, and
/// keywords — the iOS counterpart of the macOS inspector's editing controls.
/// Reads the *live* row from the shared view-model so optimistic edits repaint
/// immediately, and the changes sync to every client.
struct MetadataEditorSection: View {
    @ObservedObject var grid: GridViewModel
    let videoId: String
    @State private var newKeyword: String = ""

    /// The live row (preferring the loaded grid row so optimistic edits show),
    /// falling back to the current selection.
    private var video: VideoSummary? {
        grid.videos.first(where: { $0.id == videoId })
            ?? (grid.selectedVideo?.id == videoId ? grid.selectedVideo : nil)
    }

    var body: some View {
        if let video {
            VStack(alignment: .leading, spacing: 20) {
                ratingRow(video)
                colorRow(video)
                keywordsBlock(video)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Keywords removal resolves a tag name → id from grid.tags, so make
            // sure the tag list is loaded even if the sidebar hasn't been opened.
            .onAppear { if grid.tags.isEmpty { grid.loadTags() } }
        }
    }

    private func ratingRow(_ video: VideoSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Rating").font(.headline)
            HStack(spacing: 12) {
                ForEach(1...5, id: \.self) { position in
                    Image(systemName: position <= video.rating ? "star.fill" : "star")
                        .font(.title2)
                        .foregroundStyle(position <= video.rating ? .yellow : .secondary)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            // Tap a set star again to clear (Lightroom-style).
                            grid.setRating(video.rating == position ? 0 : position, for: [video.id])
                        }
                }
            }
        }
    }

    private func colorRow(_ video: VideoSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Color Label").font(.headline)
            HStack(spacing: 14) {
                ForEach(ColorLabel.allCases) { label in
                    Button {
                        // Tap the active colour again to clear it.
                        grid.setColorLabel(video.colorLabel == label.rawValue ? "" : label.rawValue,
                                           for: [video.id])
                    } label: {
                        Circle()
                            .fill(label == .none ? Color(white: 0.25) : label.swatch)
                            .frame(width: 28, height: 28)
                            .overlay {
                                if label == .none {
                                    Image(systemName: "slash.circle")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .overlay {
                                if video.colorLabel == label.rawValue {
                                    Circle().strokeBorder(.white, lineWidth: 2.5)
                                }
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(label.displayName)
                }
            }
        }
    }

    private func keywordsBlock(_ video: VideoSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Keywords").font(.headline)
            if !video.tags.isEmpty {
                FlowLayout(spacing: 8) {
                    ForEach(video.tags, id: \.self) { tag in
                        keywordChip(tag, video: video)
                    }
                }
            }
            HStack {
                TextField("Add a keyword…", text: $newKeyword)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { addKeyword(video) }
                Button("Add") { addKeyword(video) }
                    .disabled(newKeyword.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func keywordChip(_ tag: String, video: VideoSummary) -> some View {
        HStack(spacing: 4) {
            Text(tag).font(.caption)
            Button {
                if let tagId = grid.tags.first(where: { $0.name == tag })?.id {
                    grid.removeKeyword(tagId: tagId, from: [video.id])
                }
            } label: {
                Image(systemName: "xmark.circle.fill").font(.caption2)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Color(white: 0.22), in: Capsule())
    }

    private func addKeyword(_ video: VideoSummary) {
        let name = newKeyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        grid.applyKeyword(name, to: [video.id])
        newKeyword = ""
    }
}

/// Minimal wrapping (flow) layout for keyword chips — fills each row left-to-right
/// and wraps when the next subview would overflow the available width.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth.isFinite ? maxWidth : x, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            sub.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// A named, vertical list of the same status badges shown on grid cards
/// (keywords / proxies / full-resolution / audio / location) — for the detail
/// panel, where each badge gets a label instead of just an icon.
struct StatusBadgeList: View {
    let video: VideoSummary

    var body: some View {
        let rows = rows
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Status").font(.headline)
                ForEach(rows) { row in
                    HStack(spacing: 8) {
                        Image(systemName: row.icon)
                            .foregroundStyle(row.tint)
                            .frame(width: 22)
                        Text(row.label).font(.callout)
                        Spacer(minLength: 0)
                    }
                }
            }
        }
    }

    private struct Row: Identifiable { let id: String; let icon: String; let tint: Color; let label: String }

    private var rows: [Row] {
        var out: [Row] = []
        if !video.tags.isEmpty {
            out.append(Row(id: "tags", icon: "tag.fill", tint: Color(red: 0.39, green: 0.71, blue: 0.96),
                           label: "\(video.tags.count) keyword\(video.tags.count == 1 ? "" : "s")"))
        }
        if video.hasProxies {
            out.append(Row(id: "proxies", icon: "rectangle.on.rectangle.angled", tint: .primary,
                           label: "\(video.proxyCount) prox\(video.proxyCount == 1 ? "y" : "ies") available"))
        }
        switch video.fullResolution {
        case .full:
            out.append(Row(id: "res", icon: "checkmark.seal.fill", tint: Color(red: 0.51, green: 0.78, blue: 0.52),
                           label: "Full resolution"))
        case .notFull:
            out.append(Row(id: "res", icon: "crop", tint: .primary, label: "Not full resolution"))
        case .unspecified:
            break
        }
        if video.hasAudio {
            out.append(Row(id: "audio", icon: "waveform", tint: .primary, label: "Has audio"))
        }
        if video.hasLocation {
            out.append(Row(id: "loc", icon: "mappin.circle.fill", tint: .red, label: "Has location"))
        }
        return out
    }
}
