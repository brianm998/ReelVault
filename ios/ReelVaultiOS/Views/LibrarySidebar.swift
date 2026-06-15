// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import UIKit
import ReelVaultKit

/// One selectable *source* in the library sidebar — which videos to show.
/// Orthogonal to the view *mode* (grid/list/detail/map). Lightroom-style: one
/// active source at a time (All Videos, a folder, a collection, or a tag).
enum LibrarySection: Hashable {
    case allVideos
    case location(String)   // library location path
    case collection(String) // collection id
    case tag(String)        // tag id
}

/// The library panel (leading column / iPhone sheet): the view-mode switcher
/// (Grid/List/Detail/Map, Detail gated on a selection) plus the browsable
/// sources (all videos, folders, collections, tags). Bound to the shared
/// `GridViewModel`; the parent owns mode + source application.
struct LibrarySidebar: View {
    @ObservedObject var grid: GridViewModel
    @Binding var viewMode: LibraryViewMode
    @Binding var selection: LibrarySection
    /// Whether a video is selected (gates Detail mode).
    var hasSelection: Bool
    /// Apply the chosen source to the shared view-model (parent owns the logic).
    var onSelect: (LibrarySection) -> Void

    @EnvironmentObject private var router: AppRouter
    @State private var showForgetServerConfirm = false

    var body: some View {
        List(selection: Binding<LibrarySection?>(
            get: { selection },
            set: { newValue in
                if let newValue { selection = newValue; onSelect(newValue) }
            }
        )) {
            librarySourceSection
            viewSection
            sourcesSection
            if !grid.libraryLocations.isEmpty { locationsSection }
            if !grid.collections.isEmpty { collectionsSection }
            if !grid.tags.isEmpty { tagsSection }
        }
        .listStyle(.sidebar)
        .navigationTitle("Library")
        .task {
            grid.loadLibraryLocations()
            grid.loadCollections()
            grid.loadTags()
        }
    }

    /// Where videos come from: the on-device Local Library or a LAN server.
    /// `connection == nil` means we're in Local mode. Both sources are always
    /// listed (like the View switcher above) so the active one reads as
    /// *selected* — prominent + a trailing checkmark — while the other is a muted,
    /// tappable row that switches to it. The switch re-routes the whole app
    /// (AppRouter.phase) and is remembered for next launch.
    private var librarySourceSection: some View {
        let onServer = router.connection != nil
        return Section("Library") {
            sourceRow(Self.thisDeviceLabel, systemImage: Self.thisDeviceIcon,
                      isActive: !onServer) {
                if onServer { router.startLocal() }
            }
            sourceRow("Server", systemImage: "network", isActive: onServer) {
                if !onServer { router.useServerLibrary() }
            }
            // Forget the pairing token for this server (it lives in the Keychain,
            // which survives app reinstall) so the next connect re-pairs. Only
            // meaningful while connected to a server.
            if onServer {
                Button(role: .destructive) {
                    showForgetServerConfirm = true
                } label: {
                    Label("Forget This Server…", systemImage: "key.slash")
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .confirmationDialog(
                    "Forget this server?",
                    isPresented: $showForgetServerConfirm, titleVisibility: .visible
                ) {
                    Button("Forget & Re-pair", role: .destructive) {
                        router.forgetCurrentServer()
                    }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("Clears this device's pairing token. You'll need to enter a new pairing code to reconnect.")
                }
            }
        }
    }

    /// One library-source row, styled like the View-mode rows: the active source
    /// is shown in the prominent (white-in-dark) text colour with a trailing
    /// checkmark; the inactive source is muted and switches to itself on tap. The
    /// active row's `switchTo` is a no-op (guarded by the caller), so it isn't
    /// `.disabled` — disabling a `.plain` button would dim the very row we want to
    /// look prominent.
    @ViewBuilder private func sourceRow(
        _ title: String, systemImage: String, isActive: Bool, switchTo: @escaping () -> Void
    ) -> some View {
        Button(action: switchTo) {
            HStack {
                Label(title, systemImage: systemImage)
                Spacer()
                if isActive {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isActive ? .primary : .secondary)
    }

    /// "On This iPad" on an iPad, "On This iPhone" otherwise.
    private static var thisDeviceLabel: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "On This iPad" : "On This iPhone"
    }

    private static var thisDeviceIcon: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
    }

    private var viewSection: some View {
        Section("View") {
            ForEach(LibraryViewMode.allCases) { mode in
                Button {
                    viewMode = mode
                } label: {
                    HStack {
                        Label(mode.label, systemImage: mode.systemImage)
                        Spacer()
                        if viewMode == mode {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(mode == .detail && !hasSelection)
                .foregroundStyle(viewMode == mode ? Color.accentColor : .primary)
            }
        }
    }

    private var sourcesSection: some View {
        Section {
            Label("All Videos", systemImage: "film.stack").tag(LibrarySection.allVideos)
        }
    }

    private var locationsSection: some View {
        Section("Folders") {
            ForEach(grid.libraryLocations) { loc in
                Label {
                    HStack {
                        Text(folderName(loc.path)).lineLimit(1)
                        Spacer()
                        countBadge(loc.videoCount)
                    }
                } icon: {
                    Image(systemName: "folder")
                }
                .tag(LibrarySection.location(loc.path))
            }
        }
    }

    private var collectionsSection: some View {
        Section("Collections") {
            ForEach(grid.collections) { col in
                Label {
                    HStack {
                        Text(col.name).lineLimit(1)
                        Spacer()
                        // Smart collections have no stored members, so the
                        // server reports 0; the shared view-model resolves their
                        // real count client-side into `smartCollectionCounts`.
                        countBadge(col.isSmart
                            ? (grid.smartCollectionCounts[col.id] ?? 0)
                            : col.videoCount)
                    }
                } icon: {
                    Image(systemName: col.isSmart ? "gearshape.2" : "rectangle.stack")
                }
                .tag(LibrarySection.collection(col.id))
            }
        }
    }

    private var tagsSection: some View {
        Section("Tags") {
            ForEach(grid.tags) { tag in
                Label {
                    HStack {
                        Text(tag.name).lineLimit(1)
                        Spacer()
                        countBadge(tag.videoCount)
                    }
                } icon: {
                    Image(systemName: "tag")
                        .foregroundStyle(tagColor(tag.color))
                }
                .tag(LibrarySection.tag(tag.id))
            }
        }
    }

    @ViewBuilder private func countBadge(_ n: Int64) -> some View {
        Text("\(n)")
            .font(.caption2)
            .foregroundStyle(.secondary)
    }

    private func folderName(_ path: String) -> String {
        let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
        return (trimmed as NSString).lastPathComponent.isEmpty ? path : (trimmed as NSString).lastPathComponent
    }

    private func tagColor(_ hex: String?) -> Color {
        guard let hex, hex.hasPrefix("#"), hex.count == 7,
              let value = Int(hex.dropFirst(), radix: 16) else { return .secondary }
        return Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}
