// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import AppKit
import SwiftUI

/// The Library Filter bar. Sits at the top of the centre content column (below
/// the top bar, between the two side panels) and offers four modes — Text /
/// Attribute / Metadata / Clear. Under COMBINE semantics all three filter
/// editors stay applied at once; the selector only chooses which is shown.
/// "Clear" is a resting mode that resets the filter and shows nothing below.
struct LibraryFilterBar: View {
    @ObservedObject var vm: GridViewModel

    /// Drag-adjustable height for the metadata editor, persisted across sessions.
    @State private var metadataHeight: CGFloat = LibraryFilterBarPrefs.loadHeight()
    /// Height recorded when the current drag began. `nil` between gestures.
    @State private var dragStartHeight: CGFloat? = nil

    var body: some View {
        VStack(spacing: 0) {
            // Top row: "Filter:" pinned left, the mode selector centred.
            ZStack {
                HStack {
                    Text("Filter:")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    Spacer()
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
                    case .attribute: LibraryFilterAttributeEditor(vm: vm)
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
            .frame(maxWidth: 320)
            .padding(.vertical, 8)
            .help("Search videos by filename or notes. Matches as you type.")
    }
}

/// Attribute mode: direct-click star rating + colour swatches, centred.
private struct LibraryFilterAttributeEditor: View {
    @ObservedObject var vm: GridViewModel
    var body: some View {
        HStack(spacing: 24) {
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

/// Metadata mode: a horizontal, cascading set of metadata columns. Centred
/// within the available width (via GeometryReader); scrolls when wider. The
/// `height` is the drag-adjustable editor height.
private struct LibraryFilterMetadataEditor: View {
    @ObservedObject var vm: GridViewModel
    let height: CGFloat

    var body: some View {
        GeometryReader { geo in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 0) {
                    addButton { vm.addMetadataColumn(at: .front) }
                    ForEach(Array(vm.metadataColumns.enumerated()), id: \.element.id) { index, column in
                        MetadataColumnView(
                            column: column,
                            facet: vm.facetColumn(at: index),
                            availableKeys: vm.availableMetadataKeys,
                            canRemove: vm.metadataColumns.count > 1,
                            onPickKey: { vm.setMetadataColumnKey(at: index, key: $0) },
                            onValueClick: { token, shift, toggle in
                                vm.onMetadataValueClicked(at: index, token: token, shift: shift, toggle: toggle)
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
    let onPickKey: (String) -> Void
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
    static let minHeight: CGFloat = 120
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
