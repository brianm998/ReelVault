// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI

/// Library-wide auto-tagging / detection settings. Currently a single
/// toggle — auto-tag timelapses — but the dialog is the home for any
/// future library-scoped knobs (auto-tag faces, auto-tag locations,
/// etc.).
///
/// Backs onto the daemon's `auto_tag_timelapses` config key. When on,
/// the post-index pipeline applies a "timelapse" tag to videos whose
/// recorded resolution exceeds their camera's max in-camera video
/// resolution. The catalog remembers which videos were auto-tagged —
/// removing the tag manually is permanent, even on subsequent scans.
///
/// The dialog seeds its initial state from
/// `VideoRepository.shared.cachedConfig`, which is populated by any
/// earlier `getConfig` call (typically the first time the user opens
/// any settings dialog after launch). After that the dialog opens
/// instantly — no spinner — and only the very first open of any
/// settings dialog pays a network round-trip. A background refresh
/// still runs so a setting changed via CLI or another client lands
/// within a second or two.
struct LibrarySettingsDialog: View {
    @Binding var isPresented: Bool

    @State private var autoTagTimelapses: Bool
    @State private var loading: Bool
    @State private var saving = false

    // Default filename → capture-date inference. Stored locally (UserDefaults),
    // not in the catalog config — seeded directly and saved alongside the
    // catalog config on Save.
    @State private var inferEnabled: Bool
    @State private var inferFormat: String
    @State private var inferPosition: String

    init(isPresented: Binding<Bool>) {
        self._isPresented = isPresented
        // Seed from the repository's in-memory cache. If we've never
        // fetched (very first dialog open after a cold start), the
        // cache is nil and we fall back to the same default the daemon
        // uses — on. The background `.task` below replaces this with
        // the real server value as soon as it arrives.
        let cached = VideoRepository.shared.cachedConfig
        self._autoTagTimelapses = State(initialValue: cached?.autoTagTimelapses ?? true)
        // Only show the "refreshing…" affordance when we genuinely
        // don't have any value to render yet. Cached opens skip it.
        self._loading = State(initialValue: cached == nil)
        self._inferEnabled = State(initialValue: FilenameDateInference.defaultEnabled())
        self._inferFormat = State(initialValue: FilenameDateInference.savedFormat())
        self._inferPosition = State(initialValue: FilenameDateInference.savedPosition())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Image(systemName: "books.vertical.fill")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Library")
                        .font(.headline)
                    Text("Catalog-wide auto-tagging and detection settings")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }

            Divider()

            timelapseSection

            Divider()

            inferenceSection

            // The "refreshing…" affordance only renders on a true cold
            // start (no cached config yet). After the first fetch it
            // never reappears within this app session.
            if loading {
                HStack(spacing: 6) {
                    ProgressView().scaleEffect(0.6)
                    Text("Refreshing…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()

            HStack {
                Spacer()
                Button("Cancel") { isPresented = false }
                    .keyboardShortcut(.escape, modifiers: [])
                Button(saving ? "Saving…" : "Save") {
                    Task { await saveAndDismiss() }
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(saving)
            }
        }
        .padding(20)
        .frame(width: 540, height: 460)
        .task { await refreshFromServer() }
    }

    private var inferenceSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Infer capture date from filename")
                        .fontWeight(.medium)
                    Text(
                        "When a video has no embedded capture date, read one from its " +
                        "filename with this method — applied to new library scans, and " +
                        "offered as the default in the Set Capture Date dialog."
                    )
                    .font(.caption)
                    .foregroundColor(.secondary)
                }
                Spacer()
                Toggle("", isOn: $inferEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .disabled(saving)
            }
            if inferEnabled {
                FilenameDateInferenceControls(
                    format: $inferFormat, position: $inferPosition, disabled: saving)
            }
        }
    }

    private var timelapseSection: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Auto-tag timelapses")
                    .fontWeight(.medium)
                Text(
                    "When a video's recorded resolution exceeds the camera's max " +
                    "in-camera video resolution, ReelVault tags it as \"timelapse\" — " +
                    "by definition such files can only be assembled from stills. " +
                    "Removing the tag manually is permanent: the auto-tagger won't " +
                    "re-apply it on future scans, even if the same heuristic fires again."
                )
                .font(.caption)
                .foregroundColor(.secondary)
            }
            Spacer()
            Toggle("", isOn: $autoTagTimelapses)
                .labelsHidden()
                .toggleStyle(.switch)
                .disabled(saving)
        }
    }

    private func refreshFromServer() async {
        // Always refresh in the background so the cache is current —
        // catches the case where the value changed via CLI or another
        // client since the cache was last filled. When the cache was
        // already populated this is essentially free UX-wise: the
        // toggle re-snaps to the freshly-fetched value (almost always
        // identical to the cached one).
        if let fresh = await VideoRepository.shared.getConfig() {
            autoTagTimelapses = fresh.autoTagTimelapses
        }
        loading = false
    }

    private func saveAndDismiss() async {
        saving = true
        // Local pref — persist the default inference method.
        FilenameDateInference.saveDefault(
            enabled: inferEnabled, format: inferFormat, position: inferPosition)
        _ = await VideoRepository.shared.updateConfig(
            autoTagTimelapses: autoTagTimelapses
        )
        saving = false
        isPresented = false
    }
}
