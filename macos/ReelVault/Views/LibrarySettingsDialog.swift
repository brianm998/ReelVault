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
struct LibrarySettingsDialog: View {
    @Binding var isPresented: Bool

    @State private var autoTagTimelapses: Bool = false
    @State private var loading = true
    @State private var saving = false

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

            if loading {
                HStack {
                    ProgressView().scaleEffect(0.7)
                    Text("Loading current settings…")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                timelapseSection
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
                .disabled(loading || saving)
            }
        }
        .padding(20)
        .frame(width: 540, height: 280)
        .task { await loadSettings() }
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

    private func loadSettings() async {
        loading = true
        if let cfg = await VideoRepository.shared.getConfig() {
            autoTagTimelapses = cfg.autoTagTimelapses
        }
        loading = false
    }

    private func saveAndDismiss() async {
        saving = true
        _ = await VideoRepository.shared.updateConfig(
            autoTagTimelapses: autoTagTimelapses
        )
        saving = false
        isPresented = false
    }
}
