// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The browse top bar (grid/list modes): full-text search, a filter sheet
/// (rating / color / attribute toggles), a sort menu, and a thumbnail-size
/// slider. Binds to the shared `GridViewModel` filter state (same as the macOS
/// filter bar); thumbnail size is iOS-local (`thumbnailWidth`).
struct LibraryTopBar: View {
    @ObservedObject var grid: GridViewModel
    @Binding var thumbnailWidth: Double
    @State private var showFilters = false

    /// Sort fields offered, matching the macOS picker (label, server key).
    private static let sortFields: [(String, String)] = [
        ("Filename", "filename"), ("Date Added", "indexed_at"),
        ("Date Captured", "creation_date"), ("Duration", "duration"),
        ("File Size", "size"), ("Resolution", "resolution"),
        ("Frame Rate", "fps"), ("Codec", "codec"), ("Bitrate", "bitrate"),
        ("Camera", "camera"), ("Lens", "lens"), ("ISO", "iso"),
        ("Aperture", "aperture"), ("Exposure Time", "exposure_time"),
        ("Focal Length", "focal_length"), ("Keyword", "keyword"),
    ]

    var body: some View {
        HStack(spacing: 10) {
            searchField
            filterButton
            sortMenu
            Spacer(minLength: 8)
            thumbnailSlider
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.bar)
        .sheet(isPresented: $showFilters) {
            LibraryFilterSheet(grid: grid)
        }
    }

    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Search", text: $grid.searchQuery)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
            if !grid.searchQuery.isEmpty {
                Button { grid.searchQuery = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(.quaternary, in: Capsule())
        .frame(maxWidth: 280)
    }

    private var filterButton: some View {
        Button { showFilters = true } label: {
            Image(systemName: anyFilterActive
                  ? "line.3.horizontal.decrease.circle.fill"
                  : "line.3.horizontal.decrease.circle")
        }
        .help("Filters")
    }

    private var sortMenu: some View {
        Menu {
            ForEach(Self.sortFields, id: \.1) { field in
                Button {
                    if grid.sortBy == field.1 {
                        grid.setSort(field.1, ascending: !grid.sortAscending)
                    } else {
                        grid.setSort(field.1, ascending: true)
                    }
                } label: {
                    if grid.sortBy == field.1 {
                        Label(field.0, systemImage: grid.sortAscending ? "chevron.up" : "chevron.down")
                    } else {
                        Text(field.0)
                    }
                }
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
        }
        .help("Sort")
    }

    private var thumbnailSlider: some View {
        HStack(spacing: 6) {
            Image(systemName: "photo").font(.system(size: 10)).foregroundStyle(.secondary)
            Slider(value: $thumbnailWidth, in: 130...340).frame(width: 120)
            Image(systemName: "photo").font(.system(size: 15)).foregroundStyle(.secondary)
        }
    }

    private var anyFilterActive: Bool {
        grid.filterMinRating > 0
        || !grid.filterColorLabel.isEmpty
        || grid.filterHasLocation != .any
        || grid.filterHasKeywords != .any
        || grid.filterHasProxies != .any
        || grid.filterFullResolution != .any
        || grid.filterHasAudio != .any
        || grid.filterOrientation != .any
    }
}

/// The filter editor presented from the top bar: rating, color label, and the
/// tri-state attribute toggles — the common subset of the macOS filter bar.
/// (Metadata-facet column filtering is a follow-up.)
struct LibraryFilterSheet: View {
    @ObservedObject var grid: GridViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Rating") {
                    HStack(spacing: 6) {
                        ForEach(0...5, id: \.self) { n in
                            Button {
                                grid.setMinRatingFilter(Int32(n))
                            } label: {
                                Image(systemName: starName(for: n))
                                    .foregroundStyle(Int32(n) <= grid.filterMinRating && n > 0 ? .yellow : .secondary)
                            }
                            .buttonStyle(.plain)
                        }
                        Spacer()
                        if grid.filterMinRating > 0 {
                            Text("≥ \(grid.filterMinRating)").foregroundStyle(.secondary)
                        }
                    }
                }

                Section("Color") {
                    HStack(spacing: 10) {
                        ForEach(ColorLabel.allCases) { label in
                            Button {
                                grid.setColorLabelFilter(
                                    grid.filterColorLabel == label.rawValue ? "" : label.rawValue)
                            } label: {
                                Circle()
                                    .fill(label == .none ? Color.gray.opacity(0.3) : label.swatch)
                                    .frame(width: 24, height: 24)
                                    .overlay(
                                        Circle().strokeBorder(
                                            .primary,
                                            lineWidth: grid.filterColorLabel == label.rawValue ? 2 : 0)
                                    )
                                    .overlay {
                                        if label == .none {
                                            Image(systemName: "slash.circle").font(.caption2).foregroundStyle(.secondary)
                                        }
                                    }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Section("Attributes") {
                    attributeRow("Location", grid.filterHasLocation) { grid.setHasLocationFilter($0) }
                    attributeRow("Keywords", grid.filterHasKeywords) { grid.setHasKeywordsFilter($0) }
                    attributeRow("Proxies", grid.filterHasProxies) { grid.setHasProxiesFilter($0) }
                    attributeRow("Full Resolution", grid.filterFullResolution) { grid.setFullResolutionFilter($0) }
                    attributeRow("Audio", grid.filterHasAudio) { grid.setHasAudioFilter($0) }
                    orientationRow
                }
            }
            .navigationTitle("Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Clear") { grid.clearLibraryFilter() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func starName(for n: Int) -> String {
        n == 0 ? "star.slash" : (Int32(n) <= grid.filterMinRating ? "star.fill" : "star")
    }

    private func attributeRow(
        _ label: String, _ state: AttributeFilterState,
        _ set: @escaping (AttributeFilterState) -> Void
    ) -> some View {
        Picker(label, selection: Binding(get: { state }, set: { set($0) })) {
            ForEach(AttributeFilterState.allCases) { s in
                Text(s.displayName).tag(s)
            }
        }
        .pickerStyle(.menu)
    }

    private var orientationRow: some View {
        Picker("Orientation", selection: Binding(
            get: { grid.filterOrientation }, set: { grid.setOrientationFilter($0) }
        )) {
            ForEach(OrientationFilterState.allCases) { o in
                Text(o.displayName).tag(o)
            }
        }
        .pickerStyle(.menu)
    }
}
