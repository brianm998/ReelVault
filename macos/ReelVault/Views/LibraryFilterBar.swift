// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AppKit
import SwiftUI

/// The Library Filter bar. Sits at the top of the centre content column (below
/// the top bar, between the two side panels) and offers four modes — Text /
/// Attribute / Metadata / Clear. Under COMBINE semantics all three filter
/// editors stay applied at once; the selector only chooses which is shown.
/// "Clear" is a resting mode that resets the filter and shows nothing below.
/// A video's place is one of the Metadata fields ("Location"); there is no
/// separate Location mode.
struct LibraryFilterBar: View {
    @ObservedObject var vm: GridViewModel
    /// Hide the "Location" presence option from the attribute filter — set in
    /// map mode, where every video is located and the location filter is forced
    /// on.
    var hideLocationOption: Bool = false

    /// Drag-adjustable height for the metadata editor, persisted across sessions.
    @State private var metadataHeight: CGFloat = LibraryFilterBarPrefs.loadHeight()
    /// Height recorded when the current drag began. `nil` between gestures.
    @State private var dragStartHeight: CGFloat? = nil

    var body: some View {
        VStack(spacing: 0) {
            // Top row: "Filter:" pinned left, the mode selector centred, the
            // sort controls pinned right.
            ZStack {
                HStack {
                    Text("Filter:")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    Spacer()
                    sortControls
                }
                modeSelector
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            if vm.libraryFilterMode != .clear {
                // Border between the two views (selector ↑ / editor ↓).
                Divider()
                Group {
                    switch vm.libraryFilterMode {
                    case .text:      LibraryFilterTextEditor(vm: vm)
                    case .attribute: LibraryFilterAttributeEditor(vm: vm, hideLocationOption: hideLocationOption)
                    case .metadata:  LibraryFilterMetadataEditor(vm: vm, height: metadataHeight)
                    case .clear:     EmptyView()
                    }
                }
                .frame(maxWidth: .infinity)
            }

            // Bottom border of the bar. In metadata mode it doubles as a
            // vertical-resize drag handle for the editor's height.
            if vm.libraryFilterMode == .metadata {
                metadataResizeHandle
            } else {
                Divider()
            }
        }
        .frame(maxWidth: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    /// Segmented Text / Attribute / Metadata / Clear selector (centred).
    private var modeSelector: some View {
        HStack(spacing: 1) {
            ForEach(LibraryFilterMode.allCases) { mode in
                Button {
                    vm.setLibraryFilterMode(mode)
                } label: {
                    Text(mode.displayName)
                        .font(.system(size: 11, weight: .medium))
                        .padding(.vertical, 4)
                        .padding(.horizontal, 12)
                        .background(vm.libraryFilterMode == mode ? Color.accentColor : Color.clear)
                        .foregroundColor(vm.libraryFilterMode == mode ? .white : .primary)
                }
                .buttonStyle(.plain)
            }
        }
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(nsColor: .separatorColor), lineWidth: 1))
        .fixedSize()
    }

    /// Sort controls — a "sort by" field menu on the left and an ascending /
    /// descending direction arrow on the right. Pinned to the far right of the
    /// top row, mirroring the desktop filter bar.
    private var sortControls: some View {
        HStack(spacing: 6) {
            Menu {
                sortMenuItem(label: "Filename",       key: "filename")
                sortMenuItem(label: "Date Added",     key: "indexed_at")
                sortMenuItem(label: "Date Captured",  key: "creation_date")
                sortMenuItem(label: "Duration",       key: "duration")
                sortMenuItem(label: "File Size",      key: "size")
                sortMenuItem(label: "Resolution",     key: "resolution")
                sortMenuItem(label: "Frame Rate",     key: "fps")
                sortMenuItem(label: "Codec",          key: "codec")
                sortMenuItem(label: "Bitrate",        key: "bitrate")
                sortMenuItem(label: "Camera",         key: "camera")
                sortMenuItem(label: "Lens",           key: "lens")
                sortMenuItem(label: "ISO",            key: "iso")
                sortMenuItem(label: "Aperture",       key: "aperture")
                sortMenuItem(label: "Exposure Time",  key: "exposure_time")
                sortMenuItem(label: "Focal Length",   key: "focal_length")
                sortMenuItem(label: "Keyword",        key: "keyword")
            } label: {
                HStack(spacing: 4) {
                    Text(sortFieldLabel(vm.sortBy))
                        .font(.system(size: 11))
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 9))
                }
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help("Sort the video grid. Pick a field; choose the same field again to reverse direction.")

            // Ascending / descending direction toggle
            Button {
                vm.setSort(vm.sortBy, ascending: !vm.sortAscending)
            } label: {
                Image(systemName: vm.sortAscending ? "arrow.up" : "arrow.down")
            }
            .buttonStyle(.borderless)
            .help(vm.sortAscending ? "Sorted ascending — click to reverse" : "Sorted descending — click to reverse")
        }
    }

    /// Human-readable label for a sort field key.
    private func sortFieldLabel(_ key: String) -> String {
        switch key {
        case "filename":      return "Filename"
        case "indexed_at":    return "Date Added"
        case "creation_date": return "Date Captured"
        case "duration":      return "Duration"
        case "size":          return "File Size"
        case "resolution":    return "Resolution"
        case "fps":           return "Frame Rate"
        case "codec":         return "Codec"
        case "bitrate":       return "Bitrate"
        case "camera":        return "Camera"
        case "lens":          return "Lens"
        case "iso":           return "ISO"
        case "aperture":      return "Aperture"
        case "exposure_time": return "Exposure Time"
        case "focal_length":  return "Focal Length"
        case "keyword":       return "Keyword"
        default:              return key
        }
    }

    @ViewBuilder
    private func sortMenuItem(label: String, key: String) -> some View {
        let selected = vm.sortBy == key
        Button {
            if selected {
                vm.setSort(key, ascending: !vm.sortAscending)
            } else {
                let defaultAsc = (key == "filename" || key == "camera" || key == "codec")
                vm.setSort(key, ascending: defaultAsc)
            }
        } label: {
            HStack {
                Text(label)
                if selected {
                    Image(systemName: vm.sortAscending
                        ? "arrow.up" : "arrow.down")
                }
            }
        }
    }

    /// The bar's bottom border in metadata mode — a vertical-resize handle.
    /// Mirrors `PanelResizeHandle` (NSCursor + global-space DragGesture).
    private var metadataResizeHandle: some View {
        Divider()
            .frame(maxWidth: .infinity)
            .padding(.vertical, 3)
            .contentShape(Rectangle())
            .onHover { hovered in
                if hovered { NSCursor.resizeUpDown.push() } else { NSCursor.pop() }
            }
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { value in
                        if dragStartHeight == nil { dragStartHeight = metadataHeight }
                        guard let start = dragStartHeight else { return }
                        let proposed = start + value.translation.height
                        metadataHeight = min(max(proposed, LibraryFilterBarPrefs.minHeight),
                                             LibraryFilterBarPrefs.maxHeight)
                    }
                    .onEnded { _ in
                        dragStartHeight = nil
                        LibraryFilterBarPrefs.saveHeight(metadataHeight)
                    }
            )
    }
}

/// Text mode: the search box (filename / notes), centred by the parent.
private struct LibraryFilterTextEditor: View {
    @ObservedObject var vm: GridViewModel
    var body: some View {
        TextField("Search videos…", text: $vm.searchQuery)
            .textFieldStyle(.roundedBorder)
            .frame(maxWidth: 180)
            .padding(.vertical, 8)
            .help("Search videos by filename or notes. Matches as you type.")
    }
}

/// Attribute mode: a single row leading with the presence selectors
/// (location / keywords / proxies / full resolution), then rating + colour.
/// Each presence selector shows only its current value and opens a pop-up
/// menu with the other choices on click.

private struct LibraryFilterAttributeEditor: View {
    @ObservedObject var vm: GridViewModel
    var hideLocationOption: Bool = false
    /// Available width, read via a background GeometryReader, so the row stays
    /// centred while everything fits and scrolls once the selectors overflow —
    /// rather than compacting or clipping them. Mirrors the metadata editor.
    @State private var availableWidth: CGFloat = 0
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                if !hideLocationOption {
                    AttributeTriState(
                        label: "Location",
                        help: "Filter by whether a video has a known GPS location",
                        state: Binding(get: { vm.filterHasLocation }, set: { vm.setHasLocationFilter($0) })
                    )
                }
                AttributeTriState(
                    label: "Keywords",
                    help: "Filter by whether a video has any keywords",
                    state: Binding(get: { vm.filterHasKeywords }, set: { vm.setHasKeywordsFilter($0) })
                )
                AttributeTriState(
                    label: "Proxies",
                    help: "Filter by whether a video has any proxies",
                    state: Binding(get: { vm.filterHasProxies }, set: { vm.setHasProxiesFilter($0) })
                )
                AttributeTriState(
                    label: "Full Res",
                    help: "Filter by whether a video is full resolution",
                    state: Binding(get: { vm.filterFullResolution }, set: { vm.setFullResolutionFilter($0) })
                )
                HStack(spacing: 6) {
                    Text("Rating").font(.system(size: 11)).foregroundColor(.secondary)
                    RatingPickerRow(minRating: vm.filterMinRating) { vm.setMinRatingFilter($0) }
                }
                HStack(spacing: 6) {
                    Text("Color").font(.system(size: 11)).foregroundColor(.secondary)
                    ColorSwatchRow(selected: ColorLabel(vm.filterColorLabel)) { vm.setColorLabelFilter($0.rawValue) }
                }
            }
            .padding(.vertical, 8)
            .frame(minWidth: availableWidth, alignment: .center)
        }
        .background(GeometryReader { geo in
            Color.clear
                .onAppear { availableWidth = geo.size.width }
                .onChange(of: geo.size.width) { _, w in availableWidth = w }
        })
    }
}

/// A labelled pop-up menu for one presence attribute: the button shows only
/// the current Any / Yes / No value, and the other choices appear on click.
private struct AttributeTriState: View {
    let label: String
    let help: String
    @Binding var state: AttributeFilterState
    var body: some View {
        HStack(spacing: 6) {
            Text(label).font(.system(size: 11)).foregroundColor(.secondary)
            Picker("", selection: $state) {
                ForEach(AttributeFilterState.allCases) { s in
                    Text(s.displayName).tag(s)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .fixedSize()
            .help(help)
        }
    }
}

/// Five tappable stars. Clicking star N sets "≥ N"; re-clicking N clears to 0.
private struct RatingPickerRow: View {
    let minRating: Int32
    let onPick: (Int32) -> Void
    var body: some View {
        HStack(spacing: 4) {
            ForEach(1...5, id: \.self) { n in
                Image(systemName: Int32(n) <= minRating ? "star.fill" : "star")
                    .font(.system(size: 14))
                    .foregroundColor(Int32(n) <= minRating ? .accentColor : .secondary)
                    .onTapGesture { onPick(Int32(n) == minRating ? 0 : Int32(n)) }
                    .help("At least \(n) star(s)")
            }
        }
    }
}

/// Colour squares: filled with the label colour, narrow black border, accent
/// ring when selected. Re-clicking the active swatch clears the filter.
private struct ColorSwatchRow: View {
    let selected: ColorLabel
    let onPick: (ColorLabel) -> Void
    private let labels = ColorLabel.allCases.filter { $0 != .none }
    var body: some View {
        HStack(spacing: 6) {
            ForEach(labels) { label in
                let isSelected = label == selected
                RoundedRectangle(cornerRadius: 3)
                    .fill(label.swatch)
                    .frame(width: 18, height: 18)
                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color.black, lineWidth: 1))
                    .overlay(
                        RoundedRectangle(cornerRadius: 5)
                            .stroke(Color.accentColor, lineWidth: isSelected ? 2 : 0)
                            .padding(-2)
                    )
                    .onTapGesture { onPick(isSelected ? .none : label) }
                    .help(label.displayName)
            }
        }
    }
}

/// Stable token for a "Location" facet value / the active geo filter: the
/// centre coordinates at fixed precision, so a place's token equals the active
/// filter's token iff they denote the same spot.
private func locationFacetToken(_ latitude: Double, _ longitude: Double) -> String {
    String(format: "%.6f,%.6f", latitude, longitude)
}

/// Metadata mode: a horizontal, cascading set of metadata columns. Centred
/// within the available width (via GeometryReader); scrolls when wider. The
/// `height` is the drag-adjustable editor height. One of the offered fields is
/// "Location" (`locationMetadataKey`): a client-side virtual column whose values
/// are the catalog's known places and whose selection drives the geographic
/// proximity filter rather than a metadata filter.
private struct LibraryFilterMetadataEditor: View {
    @ObservedObject var vm: GridViewModel
    let height: CGFloat

    var body: some View {
        // The client-side "Location" field: its facet is the known places, a
        // token → place lookup drives clicks, and the active place's token marks
        // the highlighted row. Offered in the picker only when there's geodata.
        let groups = vm.filterLocationGroups
        let locationFacet = MetadataFacetColumn(
            key: locationMetadataKey,
            displayName: "Location",
            isNumeric: false,
            values: groups.map { g in
                FacetValue(token: locationFacetToken(g.latitude, g.longitude),
                           display: g.label, count: Int64(g.count))
            }
        )
        let placeByToken = Dictionary(
            groups.map { (locationFacetToken($0.latitude, $0.longitude), $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let activeToken: String? = vm.filterLocation.map { locationFacetToken($0.latitude, $0.longitude) }
        let availableKeys: [MetadataKeyInfo] =
            (!groups.isEmpty && !vm.availableMetadataKeys.contains { $0.key == locationMetadataKey })
                ? vm.availableMetadataKeys
                    + [MetadataKeyInfo(key: locationMetadataKey, displayName: "Location", isNumeric: false)]
                : vm.availableMetadataKeys

        return GeometryReader { geo in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 0) {
                    addButton { vm.addMetadataColumn(at: .front) }
                    ForEach(Array(vm.metadataColumns.enumerated()), id: \.element.id) { index, column in
                        let isLocation = column.key == locationMetadataKey
                        // A "Location" column mirrors the active geo filter (not
                        // a stored value set) and routes clicks to
                        // setLocationFilter; others use the server facet and the
                        // metadata click path.
                        let displayColumn: MetadataColumn = {
                            guard isLocation else { return column }
                            var c = column
                            c.values = activeToken.map { Set([$0]) } ?? []
                            return c
                        }()
                        MetadataColumnView(
                            column: displayColumn,
                            facet: isLocation ? locationFacet : vm.facetColumn(at: index),
                            availableKeys: availableKeys,
                            canRemove: vm.metadataColumns.count > 1,
                            // "Location" is a geo filter, not a value match, so it
                            // can't be inverted; every other column offers is/is not.
                            negatable: !isLocation,
                            onPickKey: { vm.setMetadataColumnKey(at: index, key: $0) },
                            onSetNegate: { vm.setMetadataColumnNegate(at: index, negate: $0) },
                            onValueClick: { token, shift, toggle in
                                if isLocation {
                                    if token.isEmpty {
                                        vm.setLocationFilter(latitude: nil, longitude: nil)
                                    } else if token == activeToken {
                                        // Already active; "All" clears it.
                                    } else if let g = placeByToken[token] {
                                        vm.setLocationFilter(latitude: g.latitude,
                                                             longitude: g.longitude,
                                                             radiusKm: g.radiusKm)
                                    }
                                } else {
                                    vm.onMetadataValueClicked(at: index, token: token, shift: shift, toggle: toggle)
                                }
                            },
                            onRemove: { vm.removeMetadataColumn(at: index) }
                        )
                        Divider()
                    }
                    addButton { vm.addMetadataColumn(at: .end) }
                }
                .frame(minWidth: geo.size.width, alignment: .center)
            }
        }
        .frame(height: height)
        .task { vm.loadFilterLocations() }
    }

    private func addButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "plus")
                .font(.system(size: 12))
                .frame(width: 28, height: 28)
        }
        .buttonStyle(.borderless)
        .help("Add a metadata column")
    }
}

private struct MetadataColumnView: View {
    let column: MetadataColumn
    let facet: MetadataFacetColumn?
    let availableKeys: [MetadataKeyInfo]
    let canRemove: Bool
    let negatable: Bool
    let onPickKey: (String) -> Void
    let onSetNegate: (Bool) -> Void
    let onValueClick: (_ token: String, _ shift: Bool, _ toggle: Bool) -> Void
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 2) {
                Menu {
                    if availableKeys.isEmpty {
                        Text("No metadata available")
                    }
                    ForEach(availableKeys, id: \.key) { info in
                        Button(info.displayName) { onPickKey(info.key) }
                    }
                } label: {
                    HStack(spacing: 2) {
                        Text(titleText)
                            .font(.system(size: 11, weight: .semibold))
                            .lineLimit(1)
                        Image(systemName: "chevron.down").font(.system(size: 8))
                    }
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                // "is" / "is not" — inverts this column's match. Hidden for the
                // non-invertible Location (geo) field and before a field is picked.
                if negatable && !column.key.isEmpty {
                    Menu {
                        Button("is") { onSetNegate(false) }
                        Button("is not") { onSetNegate(true) }
                    } label: {
                        HStack(spacing: 2) {
                            Text(column.negate ? "is not" : "is")
                                .font(.system(size: 11))
                                .foregroundColor(column.negate ? .accentColor : .secondary)
                            Image(systemName: "chevron.down").font(.system(size: 8))
                        }
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }
                Spacer()
                if canRemove {
                    Button { onRemove() } label: { Image(systemName: "xmark").font(.system(size: 9)) }
                        .buttonStyle(.borderless)
                        .help("Remove this column")
                }
            }

            if column.key.isEmpty {
                Text("Pick a metadata field")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.vertical, 4)
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 1) {
                        valueRow(token: "", label: "All", count: nil)
                        ForEach(facet?.values ?? [], id: \.token) { v in
                            valueRow(token: v.token, label: v.display.isEmpty ? v.token : v.display, count: v.count)
                        }
                    }
                }
                .frame(maxHeight: .infinity)
            }
        }
        .frame(width: 168, alignment: .leading)
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.horizontal, 8)
    }

    private var titleText: String {
        if column.key.isEmpty { return "Choose field" }
        if let name = facet?.displayName, !name.isEmpty { return name }
        return column.key
    }

    @ViewBuilder
    private func valueRow(token: String, label: String, count: Int64?) -> some View {
        // The "All" row (token == "") is selected when no values are chosen.
        let isSelected = token.isEmpty ? column.values.isEmpty : column.values.contains(token)
        HStack {
            Text(label)
                .font(.system(size: 11))
                .fontWeight(isSelected ? .semibold : .regular)
                .foregroundColor(isSelected ? .accentColor : .primary)
                .lineLimit(1)
            Spacer()
            if let count {
                Text("\(count)")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 1)
        .contentShape(Rectangle())
        // Read the modifiers captured at mouse-DOWN (see ModifierSnapshot) so
        // shift extends a range and Cmd/Ctrl toggles a single value. Mirrors
        // the grid's multi-select.
        .onTapGesture {
            let mods = ModifierSnapshot.lastMouseDownModifiers
            let shift = mods.contains(.shift)
            let toggle = mods.contains(.command) || mods.contains(.control)
            onValueClick(token, shift, toggle)
        }
    }
}

/// Persists the metadata editor's drag-adjusted height across sessions.
private enum LibraryFilterBarPrefs {
    static let defaultHeight: CGFloat = 240
    // Can shrink to ~half of the old floor so the editor tucks away when only
    // a couple of metadata rows are in use.
    static let minHeight: CGFloat = 60
    static let maxHeight: CGFloat = 600
    private static let key = "reelvault.libraryFilter.metadataHeight"

    static func loadHeight() -> CGFloat {
        guard let raw = UserDefaults.standard.object(forKey: key) as? Double, raw > 0 else {
            return defaultHeight
        }
        return min(max(CGFloat(raw), minHeight), maxHeight)
    }

    static func saveHeight(_ value: CGFloat) {
        UserDefaults.standard.set(Double(value), forKey: key)
    }
}
