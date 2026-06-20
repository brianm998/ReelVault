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
    @State private var showCardStats = false

    /// Sort fields offered, matching the macOS picker (label, server key).
    private static let sortFields: [(LocalizedStringKey, String)] = [
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
            cardStatsButton
            Spacer(minLength: 8)
            thumbnailSlider
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.bar)
        .sheet(isPresented: $showFilters) {
            LibraryFilterSheet(grid: grid)
        }
        .sheet(isPresented: $showCardStats) {
            TopSlotsConfigSheet(grid: grid)
        }
    }

    private var cardStatsButton: some View {
        Button { showCardStats = true } label: {
            Image(systemName: "slider.horizontal.below.rectangle")
        }
        .help("Configure card stats")
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

                metadataSection
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
            // Load the facet values + available keys for the metadata filters.
            .task { grid.refreshMetadataFacets() }
        }
        .presentationDetents([.medium, .large])
    }

    /// Metadata-facet filtering: one or more columns, each a metadata key
    /// (camera/lens/codec/year/iso/…) with multi-selectable values. Bound to the
    /// shared GridViewModel's metadataColumns/facetColumns (same as macOS).
    /// Each non-location column also exposes an "is / is not" toggle that calls
    /// `setMetadataColumnNegate` — mirroring the macOS MetadataColumnView control.
    @ViewBuilder private var metadataSection: some View {
        Section("Metadata") {
            ForEach(Array(grid.metadataColumns.enumerated()), id: \.element.id) { index, column in
                let isLocation = column.key == locationMetadataKey

                // Field key picker
                Picker("Field", selection: Binding(
                    get: { column.key },
                    set: { grid.setMetadataColumnKey(at: index, key: $0) }
                )) {
                    Text("Choose…").tag("")
                    ForEach(grid.availableMetadataKeys, id: \.key) { info in
                        Text(info.displayName).tag(info.key)
                    }
                }

                // "is / is not" negate toggle — hidden for the Location (geo) column
                // and before a field has been chosen, matching macOS behaviour.
                if !isLocation && !column.key.isEmpty {
                    Picker("Match", selection: Binding(
                        get: { column.negate },
                        set: { grid.setMetadataColumnNegate(at: index, negate: $0) }
                    )) {
                        Text("is").tag(false)
                        Text("is not").tag(true)
                    }
                    .pickerStyle(.menu)
                    .foregroundStyle(column.negate ? Color.accentColor : Color.primary)
                }

                // Value checklist
                if let facet = grid.facetColumn(at: index), !facet.values.isEmpty {
                    ForEach(facet.values, id: \.token) { value in
                        Button {
                            grid.onMetadataValueClicked(at: index, token: value.token, shift: false, toggle: true)
                        } label: {
                            HStack {
                                Image(systemName: column.values.contains(value.token) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(column.values.contains(value.token) ? Color.accentColor : .secondary)
                                Text(value.display).foregroundStyle(.primary)
                                Spacer()
                                Text("\(value.count)").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } else if !column.key.isEmpty {
                    Text("No values").font(.caption).foregroundStyle(.secondary)
                }

                if grid.metadataColumns.count > 1 {
                    Button("Remove Field", role: .destructive) { grid.removeMetadataColumn(at: index) }
                }
            }
            Button { grid.addMetadataColumn(at: .end) } label: {
                Label("Add Metadata Filter", systemImage: "plus")
            }
        }
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

/// Configure which metadata appears in the card's four top stat slots. Writes
/// the catalog-wide `topSlots` and persists via `saveGridSettings()` (shared
/// with the macOS/desktop clients).
///
/// `grid` is held as a plain `let` (not `@ObservedObject`) so that unrelated
/// catalog changes during photo sync (e.g. `videos` updates every few seconds)
/// do not cause this sheet to re-render and reset the Picker list's scroll
/// position. The current slots are snapshotted into `localSlots` on appear and
/// written back to the grid on every user change.
struct TopSlotsConfigSheet: View {
    let grid: GridViewModel
    @State private var localSlots: [String] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Choose the four stats shown at the top of each card.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(0..<4, id: \.self) { i in
                    if i < localSlots.count {
                        Picker("Slot \(i + 1)", selection: $localSlots[i]) {
                            ForEach(GridStatKey.allCases) { key in
                                Text(key.displayName).tag(key.rawValue)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Card Stats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
        .onAppear {
            var slots = grid.topSlots
            while slots.count < 4 { slots.append("") }
            localSlots = slots
        }
        .onChange(of: localSlots) { _, newValue in
            grid.topSlots = newValue
            grid.saveGridSettings()
        }
        .presentationDetents([.medium, .large])
    }
}
