// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import ReelVaultKit
import SwiftUI

/// Collapsible section wrapper — the iOS counterpart of the macOS detail panel's
/// CollapsibleSection: a tappable header with a chevron; content shows only when
/// expanded. Expanded state is per-section and lives for the session (matching
/// macOS, which uses @State); sections start expanded.
struct CollapsibleSection<Content: View>: View {
    let title: String
    @State private var expanded: Bool
    @ViewBuilder var content: () -> Content

    init(_ title: String, initiallyExpanded: Bool = true,
         @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        _expanded = State(initialValue: initiallyExpanded)
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                    Text(title).font(.headline)
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded { content() }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The macOS detail panel's extra sections that iOS was missing: EXIF (camera /
/// lens / exposure), Collections (membership, editable), and Notes (editable),
/// each in its own CollapsibleSection. Used by both detail views and the
/// inspector. Notes/Collections need the full VideoMetadata, fetched once here.
struct VideoDetailExtras: View {
    @ObservedObject var grid: GridViewModel
    let video: VideoSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            CollapsibleSection("EXIF") { ExifSection(video: video) }
            CollapsibleSection("Collections") { CollectionsSection(grid: grid, videoId: video.id) }
            CollapsibleSection("Notes") { NotesSection(videoId: video.id) }
        }
    }
}

/// Camera / lens / exposure facts, read straight off the VideoSummary (no fetch).
struct ExifSection: View {
    let video: VideoSummary

    var body: some View {
        let rows = rows
        if rows.isEmpty {
            Text("No camera metadata.").font(.callout).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(rows, id: \.0) { label, value in
                    HStack(alignment: .top) {
                        Text(label).foregroundStyle(.secondary).frame(width: 100, alignment: .leading)
                        Text(value).textSelection(.enabled)
                        Spacer(minLength: 0)
                    }
                    .font(.callout)
                }
            }
        }
    }

    private var rows: [(String, String)] {
        var out: [(String, String)] = []
        if !video.cameraDisplayName.isEmpty { out.append(("Camera", video.cameraDisplayName)) }
        if !video.lensModel.isEmpty { out.append(("Lens", video.lensModel)) }
        if video.focalLengthMm > 0 { out.append(("Focal length", String(format: "%.0f mm", video.focalLengthMm))) }
        if video.aperture > 0 { out.append(("Aperture", String(format: "f/%.1f", video.aperture))) }
        if video.exposureTimeS > 0 { out.append(("Shutter", shutter(video.exposureTimeS))) }
        if video.iso > 0 { out.append(("ISO", "\(video.iso)")) }
        return out
    }

    private func shutter(_ s: Double) -> String {
        s >= 1 ? String(format: "%.1f s", s) : "1/\(Int((1 / s).rounded())) s"
    }
}

/// Editable collection membership — toggle the video in/out of each manual
/// collection (smart collections are rule-driven, so they're not listed). Mirrors
/// the macOS CollectionsSection. Writes go through the shared GridViewModel.
struct CollectionsSection: View {
    @ObservedObject var grid: GridViewModel
    let videoId: String
    @State private var memberIds: Set<String> = []

    var body: some View {
        let manual = grid.collections.filter { !$0.isSmart }
        // The load must run regardless of whether `manual` is currently empty —
        // attaching it only to the non-empty branch meant collections never
        // loaded on iPhone (where the sidebar that populates them may never be
        // opened before the detail screen), leaving "No collections yet." stuck.
        Group {
            if manual.isEmpty {
                Text("No collections yet.").font(.callout).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(manual) { col in
                        Button { toggle(col) } label: {
                            HStack {
                                Image(systemName: memberIds.contains(col.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(memberIds.contains(col.id) ? Color.accentColor : .secondary)
                                Text(col.name).lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .font(.callout)
                    }
                }
            }
        }
        .task(id: videoId) {
            grid.loadCollections()
            if let m = try? await VideoRepository.shared.getVideoMetadata(videoId: videoId) {
                memberIds = Set(m.collections)
            }
        }
    }

    private func toggle(_ col: ReelVaultKit.Collection) {
        if memberIds.contains(col.id) {
            memberIds.remove(col.id)
            grid.removeFromCollection(videoIds: [videoId], collectionId: col.id)
        } else {
            memberIds.insert(col.id)
            grid.addToCollection(videoIds: [videoId], collectionId: col.id)
        }
    }
}

/// Free-form notes, fetched per selection and saved back to the catalog (the
/// iOS counterpart of the macOS Notes section). Saved on demand and on disappear.
struct NotesSection: View {
    let videoId: String
    @State private var notes: String = ""
    @State private var savedNotes: String = ""
    @State private var loadedVideoId: String?
    @State private var loaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextEditor(text: $notes)
                .frame(minHeight: 80)
                .scrollContentBackground(.hidden)
                .padding(6)
                .background(Color(white: 0.12))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.3)))
                .disabled(!loaded)
            if notes != savedNotes {
                Button("Save Notes") { save(videoId: videoId) }
                    .font(.caption)
            }
        }
        .task(id: videoId) {
            // On iPad the panel is reused across selections (the instance isn't
            // recreated), so flush the *previous* video's unsaved edits before
            // overwriting with the new one — otherwise switching selection loses
            // them silently.
            if let prev = loadedVideoId, prev != videoId, notes != savedNotes {
                let n = notes
                _ = try? await VideoRepository.shared.updateVideoNotes(videoId: prev, notes: n)
            }
            loaded = false
            let fetched = (try? await VideoRepository.shared.getVideoMetadata(videoId: videoId))?.notes ?? ""
            notes = fetched
            savedNotes = fetched
            loadedVideoId = videoId
            loaded = true
        }
        // Fires on an iPhone detail pop; the .task flush covers the iPad reuse case.
        .onDisappear { save(videoId: videoId) }
    }

    private func save(videoId: String) {
        guard loaded, notes != savedNotes else { return }
        let n = notes
        savedNotes = n
        Task { _ = try? await VideoRepository.shared.updateVideoNotes(videoId: videoId, notes: n) }
    }
}
