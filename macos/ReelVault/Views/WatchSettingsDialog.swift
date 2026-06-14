// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Sheet that surfaces the daemon's real-time file-watcher knobs.
///
/// Three controls:
///   * "Enable live updates" — master switch. When off the daemon
///     doesn't run a watcher; the user must `Scan Library` manually.
///   * "Wait for writes to settle" — the size-stability gate that
///     keeps the indexer from grabbing a half-written recording. Most
///     users never touch this; default 5 s is a safe baseline.
///   * "Poll fallback" — interval (seconds) at which the daemon does
///     a manual readdir on paths that FSEvents/inotify can't see
///     (NFS / SMB / SAN). Set to 0 to disable.
///
/// Values are clamped on the server too; the UI just clamps to nice
/// ranges so the slider feels right.
struct WatchSettingsDialog: View {
    @Binding var isPresented: Bool

    // Local mirror of the server's current settings. Loaded in `.task`
    // so the dialog never shows stale values from a prior session.
    @State private var settings: WatchSettings = .default
    @State private var loading = true
    @State private var saving = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Live Updates")
                        .font(.headline)
                    Text("Watch your library locations for new and changed files")
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
                Toggle(isOn: $settings.enabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable live updates")
                        Text("When off, ReelVault only sees new files after you run \"Scan Library\".")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .toggleStyle(.switch)

                // Settle slider — only meaningful when the watcher is on.
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Wait \(settleSeconds) s for writes to finish")
                            .font(.body)
                        Spacer()
                        Text(settleHelp)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(settings.writeSettleMs) / 1000.0 },
                            set: { settings.writeSettleMs = Int64($0 * 1000.0) }
                        ),
                        in: 1...60,
                        step: 1
                    )
                    Text("Prevents indexing a recording while the camera or upload tool is still writing it. Higher = safer; lower = files appear sooner. 5 s is fine for most workflows.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .opacity(settings.enabled ? 1 : 0.4)
                .disabled(!settings.enabled)

                // Poll fallback — for SAN / NFS / SMB where the OS doesn't deliver events.
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(pollLabel)
                            .font(.body)
                        Spacer()
                    }
                    Slider(
                        value: Binding(
                            get: { Double(settings.pollIntervalMs) / 1000.0 },
                            set: { settings.pollIntervalMs = Int64($0 * 1000.0) }
                        ),
                        in: 0...300,
                        step: 5
                    )
                    Text("Used for paths where macOS's filesystem events don't fire — typically network mounts (SMB, NFS, SAN). 0 disables the fallback entirely. 30 s is a reasonable default; raise it for very large remote trees.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .opacity(settings.enabled ? 1 : 0.4)
                .disabled(!settings.enabled)
            }

            Spacer()

            HStack {
                Spacer()
                Button("Cancel") {
                    isPresented = false
                }
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
        .frame(width: 520, height: 380)
        .task { await loadSettings() }
    }

    private var settleSeconds: Int {
        max(1, Int(settings.writeSettleMs / 1000))
    }

    private var settleHelp: String {
        switch settleSeconds {
        case 1...2: return "fastest"
        case 3...5: return "balanced"
        case 6...15: return "safer"
        default: return "very conservative"
        }
    }

    private var pollLabel: String {
        let secs = settings.pollIntervalMs / 1000
        if secs == 0 {
            return "Poll fallback: off"
        }
        return "Poll fallback every \(secs) s"
    }

    private func loadSettings() async {
        loading = true
        settings = await VideoRepository.shared.getWatchSettings()
        loading = false
    }

    private func saveAndDismiss() async {
        saving = true
        _ = await VideoRepository.shared.updateWatchSettings(settings)
        saving = false
        isPresented = false
    }
}
