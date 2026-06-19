// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The iOS Settings screen — a sheet with sections that mirror the macOS
/// preference dialogs. Reachable via the gear toolbar button in both the
/// sidebar (iPad) and the compact top-bar (iPhone).
///
/// Sections:
///  - Appearance — accent color + scrub-frame count (always shown)
///  - Library — auto-tag timelapses toggle + default filename→capture-date
///    inference method; shown only when connected; mirrors macOS
///    `LibrarySettingsDialog` via `VideoRepository.getConfig` / `updateConfig`.
///  - Playback & Proxies — inline-playback ceiling + default proxy height
///    (shown only when connected to a remote daemon; backed by the server's
///    `max_native_playback_height` / `proxy_target_height` config keys via
///    `VideoRepository.getConfig` / `updateConfig`)
///  - Live Updates — file-watcher knobs (enabled toggle, write-settle ms,
///    poll-interval ms); shown only when connected; mirrors macOS
///    `WatchSettingsDialog` via `VideoRepository.getWatchSettings` /
///    `updateWatchSettings`.
///  - Metadata — Camera Names and Lens Names editors (shown only when connected;
///    mirrors macOS `CameraNamesView` / `LensNamesView` via
///    `VideoRepository.listCameraNameMappings` / `listLensNameMappings`).
struct SettingsView: View {
    /// Non-nil while connected to a remote daemon. Nil in local/offline mode,
    /// which means the Library, Playback & Proxies, Live Updates, and Metadata
    /// sections are hidden — there is no server config to read or write.
    var connection: AppRouter.ConnectionInfo? = nil

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                AppearanceSection()
                if connection != nil {
                    LibrarySettingsSection()
                    PlaybackSection()
                    LiveUpdatesSection()
                    MetadataNamesSection()
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Library section

/// Catalog-wide auto-tagging and filename → capture-date inference settings,
/// mirroring macOS `LibrarySettingsDialog`. Shown only when connected to a
/// daemon; relies on `VideoRepository.getConfig` / `updateConfig` for the
/// `auto_tag_timelapses` flag, and `FilenameDateInference` (UserDefaults) for
/// the default capture-date inference method.
///
/// Changes to the inference prefs are saved immediately when the user toggles
/// or changes the pickers (no explicit Save button, matching the iOS pattern).
/// The timelapse toggle also saves immediately. A brief "Saving…" spinner
/// appears in the section header during any server round-trip.
private struct LibrarySettingsSection: View {
    // --- daemon config ---
    @State private var autoTagTimelapses: Bool = true
    @State private var loading = true
    @State private var saving = false

    // --- local inference prefs ---
    @State private var inferEnabled: Bool = FilenameDateInference.defaultEnabled()
    @State private var inferFormat: String = FilenameDateInference.savedFormat()
    @State private var inferPosition: String = FilenameDateInference.savedPosition()

    var body: some View {
        Section {
            if loading {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading…")
                        .foregroundStyle(.secondary)
                }
            } else {
                // Auto-tag timelapses
                Toggle(isOn: $autoTagTimelapses) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Auto-tag timelapses")
                        Text(
                            "Tags videos whose recorded resolution exceeds the " +
                            "camera's max in-camera video resolution as \"timelapse\". " +
                            "Removing the tag manually is permanent."
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: autoTagTimelapses) { _, newValue in
                    Task { await saveTimelapse(newValue) }
                }
                .disabled(saving)

                // Default filename → capture-date inference
                Toggle(isOn: $inferEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Infer capture date from filename")
                        Text(
                            "When a video has no embedded date, read one from its " +
                            "filename. Applied on library scans and offered as the " +
                            "default in the Set Capture Date sheet."
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: inferEnabled) { _, _ in saveInferencePref() }

                if inferEnabled {
                    // Format picker
                    Picker("Date format", selection: $inferFormat) {
                        ForEach(FilenameDateInference.formats, id: \.value) { opt in
                            Text(opt.label).tag(opt.value)
                        }
                    }
                    .pickerStyle(.menu)
                    .onChange(of: inferFormat) { _, _ in saveInferencePref() }

                    // Position picker
                    Picker("Position in name", selection: $inferPosition) {
                        ForEach(FilenameDateInference.positions, id: \.value) { opt in
                            Text(opt.label).tag(opt.value)
                        }
                    }
                    .pickerStyle(.menu)
                    .onChange(of: inferPosition) { _, _ in saveInferencePref() }
                }
            }
        } header: {
            HStack {
                Text("Library")
                if saving {
                    Spacer()
                    ProgressView()
                        .controlSize(.mini)
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Auto-tagging and detection settings apply catalog-wide.")
                Text("The filename inference default is shared with the Set Capture Date sheet and the Add Library dialog.")
            }
            .foregroundStyle(.secondary)
        }
        .task { await loadSettings() }
    }

    private func loadSettings() async {
        loading = true
        if let cfg = await VideoRepository.shared.getConfig() {
            autoTagTimelapses = cfg.autoTagTimelapses
        }
        loading = false
    }

    private func saveTimelapse(_ value: Bool) async {
        saving = true
        _ = await VideoRepository.shared.updateConfig(autoTagTimelapses: value)
        saving = false
    }

    private func saveInferencePref() {
        FilenameDateInference.saveDefault(
            enabled: inferEnabled,
            format: inferFormat,
            position: inferPosition
        )
    }
}

// MARK: - Playback & Proxies section

/// Mirrors macOS `PlaybackSettingsDialog` — two server-config pickers:
///  • Maximum inline-playback height (`max_native_playback_height`)
///  • Default proxy height (`proxy_target_height`)
///
/// Settings are loaded from the daemon via `VideoRepository.getConfig` when the
/// section appears and saved back via `updateConfig` when either picker changes.
/// A brief "Saving…" overlay is shown during the round-trip.
private struct PlaybackSection: View {
    @State private var maxNativeHeight: Int = 2160
    @State private var proxyTargetHeight: Int = 720
    @State private var loading = true
    @State private var saving = false

    private let nativePresets: [Int] = [720, 1080, 1440, 2160, 4320]
    private let proxyPresets: [Int] = [540, 720, 1080, 1440]

    var body: some View {
        Section {
            if loading {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading…")
                        .foregroundStyle(.secondary)
                }
            } else {
                // Inline-playback ceiling
                Picker("Max inline-playback height", selection: $maxNativeHeight) {
                    ForEach(nativePresets, id: \.self) { h in
                        Text(heightLabel(h)).tag(h)
                    }
                }
                .pickerStyle(.menu)
                .onChange(of: maxNativeHeight) { _, newValue in
                    Task { await save(maxNative: newValue, proxy: nil) }
                }

                // Default proxy height
                Picker("Default proxy height", selection: $proxyTargetHeight) {
                    ForEach(proxyPresets, id: \.self) { h in
                        Text(heightLabel(h)).tag(h)
                    }
                }
                .pickerStyle(.menu)
                .onChange(of: proxyTargetHeight) { _, newValue in
                    Task { await save(maxNative: nil, proxy: newValue) }
                }
            }
        } header: {
            HStack {
                Text("Playback & Proxies")
                if saving {
                    Spacer()
                    ProgressView()
                        .controlSize(.mini)
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Videos taller than the inline-playback ceiling are marked \"Too large to play here\" and offered a one-click proxy.")
                Text("Newly-generated proxies default to the proxy height. Smaller = faster and smaller files.")
            }
            .foregroundStyle(.secondary)
        }
        .task { await loadSettings() }
    }

    private func heightLabel(_ h: Int) -> String {
        switch h {
        case 540:  return "540p"
        case 720:  return "720p"
        case 1080: return "1080p"
        case 1440: return "1440p"
        case 2160: return "2160p (4K)"
        case 4320: return "4320p (8K)"
        default:   return "\(h)p"
        }
    }

    private func loadSettings() async {
        loading = true
        if let cfg = await VideoRepository.shared.getConfig() {
            maxNativeHeight = cfg.maxNativePlaybackHeight
            proxyTargetHeight = cfg.proxyTargetHeight
        }
        loading = false
    }

    private func save(maxNative: Int?, proxy: Int?) async {
        saving = true
        _ = await VideoRepository.shared.updateConfig(
            maxNativePlaybackHeight: maxNative,
            proxyTargetHeight: proxy
        )
        saving = false
    }
}

// MARK: - Live Updates section

/// File-watcher knobs that mirror macOS `WatchSettingsDialog`. Three controls:
///  - "Enable live updates" master toggle. When off the daemon doesn't run a
///    watcher and the user must `Scan Library` manually.
///  - "Write-settle delay" — size-stability gate keeping the indexer from
///    grabbing a half-written recording (1–60 s).
///  - "Poll fallback interval" — manual readdir cadence for NFS / SMB paths
///    where OS events don't fire (0–300 s; 0 = disabled).
///
/// Values are loaded from the daemon when the section appears and saved back
/// immediately when a control changes. A brief "Saving…" spinner appears in
/// the section header during the round-trip.
private struct LiveUpdatesSection: View {
    @State private var settings: WatchSettings = .default
    @State private var loading = true
    @State private var saving = false

    var body: some View {
        Section {
            if loading {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading…")
                        .foregroundStyle(.secondary)
                }
            } else {
                // Master enable toggle
                Toggle(isOn: $settings.enabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable live updates")
                        Text("When off, new files appear only after \"Scan Library\".")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .onChange(of: settings.enabled) { _, _ in
                    Task { await save() }
                }
                .disabled(saving)

                // Write-settle slider (1–60 s)
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Write-settle delay")
                        Spacer()
                        Text(settleLabel)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Slider(
                        value: Binding(
                            get: { Double(settings.writeSettleMs) / 1000.0 },
                            set: { settings.writeSettleMs = Int64($0 * 1000.0) }
                        ),
                        in: 1...60,
                        step: 1
                    )
                    .onChange(of: settings.writeSettleMs) { _, _ in
                        Task { await save() }
                    }
                    .disabled(!settings.enabled || saving)
                }
                .opacity(settings.enabled ? 1 : 0.4)

                // Poll-fallback slider (0–300 s)
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Poll fallback interval")
                        Spacer()
                        Text(pollLabel)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Slider(
                        value: Binding(
                            get: { Double(settings.pollIntervalMs) / 1000.0 },
                            set: { settings.pollIntervalMs = Int64($0 * 1000.0) }
                        ),
                        in: 0...300,
                        step: 5
                    )
                    .onChange(of: settings.pollIntervalMs) { _, _ in
                        Task { await save() }
                    }
                    .disabled(!settings.enabled || saving)
                }
                .opacity(settings.enabled ? 1 : 0.4)
            }
        } header: {
            HStack {
                Text("Live Updates")
                if saving {
                    Spacer()
                    ProgressView()
                        .controlSize(.mini)
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Write-settle delay prevents indexing a recording while the camera or upload tool is still writing it. 5 s is fine for most workflows.")
                Text("Poll fallback is used for network mounts (SMB, NFS, SAN) where filesystem events don't fire. 0 disables it.")
            }
            .foregroundStyle(.secondary)
        }
        .task { await loadSettings() }
    }

    private var settleLabel: String {
        let s = max(1, Int(settings.writeSettleMs / 1000))
        return "\(s) s"
    }

    private var pollLabel: String {
        let s = Int(settings.pollIntervalMs / 1000)
        return s == 0 ? "Off" : "\(s) s"
    }

    private func loadSettings() async {
        loading = true
        settings = await VideoRepository.shared.getWatchSettings()
        loading = false
    }

    private func save() async {
        saving = true
        _ = await VideoRepository.shared.updateWatchSettings(settings)
        saving = false
    }
}

// MARK: - Metadata Names section

/// Navigation links to the Camera Names and Lens Names editors, mirroring
/// macOS `CameraNamesView` / `LensNamesView`. Only shown when connected to a
/// daemon (the data lives in the catalog, not on-device).
private struct MetadataNamesSection: View {
    var body: some View {
        Section {
            NavigationLink {
                CameraNamesView()
            } label: {
                Label("Camera Names", systemImage: "camera.metering.matrix")
            }

            NavigationLink {
                LensNamesView()
            } label: {
                Label("Lens Names", systemImage: "camera.aperture")
            }
        } header: {
            Text("Metadata")
        } footer: {
            Text("Rename internal camera codes to marketing-friendly names, and create short aliases for verbose lens strings. Changes apply to every metadata surface in ReelVault.")
        }
    }
}

// MARK: - Appearance section

/// Accent color scheme and scrub-frame-count settings — mirrors
/// `AppearanceSettingsDialog` on macOS. Written to `UserDefaults` immediately
/// via `@AppStorage`; no Save button needed.
private struct AppearanceSection: View {
    /// Raw string persisted in UserDefaults: "blue" or "purple".
    @AppStorage("accentScheme") private var accentScheme: String = "blue"

    /// Number of scrub frames fetched per video. 0 means "use the default (10)".
    @AppStorage("scrubFrameCount") private var scrubFrameCount: Int = 10

    private let scrubOptions = [4, 6, 8, 10, 15, 20]

    var body: some View {
        Section {
            ForEach(AccentSchemeOption.allCases) { option in
                Button {
                    accentScheme = option.rawValue
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(option.swatch)
                            .frame(width: 22, height: 22)
                            .shadow(radius: 1)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.label)
                                .foregroundStyle(.primary)
                            Text(option.description)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if accentScheme == option.rawValue {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                                .fontWeight(.semibold)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("Accent Color")
        } footer: {
            Text("Changes apply immediately across the whole app.")
                .foregroundStyle(.secondary)
        }

        Section {
            Picker("Scrub frames per video", selection: $scrubFrameCount) {
                ForEach(scrubOptions, id: \.self) { n in
                    Text("\(n)").tag(n)
                }
            }
            .pickerStyle(.menu)
        } header: {
            Text("Browse")
        } footer: {
            Text("How many still frames ReelVault samples per video for the hover scrub preview. Changes take effect the next time an un-sampled card is hovered.")
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Supporting types

/// The available accent-color schemes. Raw value matches the `accentScheme`
/// `UserDefaults` key used on macOS, so the preference roams correctly if
/// `UserDefaults` is ever synced.
enum AccentSchemeOption: String, CaseIterable, Identifiable {
    case blue   = "blue"
    case purple = "purple"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .blue:   return "Blue"
        case .purple: return "Purple"
        }
    }

    var description: String {
        switch self {
        case .blue:   return "Blue accents matching the iOS system style"
        case .purple: return "Classic ReelVault palette — deep purple accents"
        }
    }

    var swatch: Color {
        switch self {
        case .blue:   return .blue
        case .purple: return .purple
        }
    }
}
