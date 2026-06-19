// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Sheet that applies bulk rating, color label, keyword, and collection changes
/// to all videos in `grid.selectedVideoIds` — the iOS counterpart of the macOS
/// grid context-menu's multi-selection "Set Rating / Set Color Label / Add to
/// Collection / keyword" actions.
///
/// The sheet is non-destructive by default: each action button fires immediately
/// and closes the sheet so the user can see the result. Keyword add/remove and
/// collection add/remove operate on the full `selectedVideoIds` array, matching
/// the macOS path.
struct BatchOrganizeSheet: View {
    @ObservedObject var grid: GridViewModel
    @Environment(\.dismiss) private var dismiss

    /// The video ids this sheet operates on. Captured at presentation time so
    /// the count reads correctly even if the selection clears underneath us.
    let videoIds: [String]

    @State private var newKeyword: String = ""

    // MARK: Convenience

    private var count: Int { videoIds.count }
    private var countLabel: String { "\(count) video\(count == 1 ? "" : "s")" }

    private var manualCollections: [ReelVaultKit.Collection] {
        grid.collections.filter { !$0.isSmart }
    }

    // MARK: Body

    var body: some View {
        NavigationStack {
            List {
                ratingSection
                colorLabelSection
                keywordsSection
                collectionsSection
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Organize \(countLabel)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear {
            if grid.tags.isEmpty { grid.loadTags() }
            if grid.collections.isEmpty { grid.loadCollections() }
        }
    }

    // MARK: Rating

    private var ratingSection: some View {
        Section("Rating") {
            // Clear rating
            Button {
                grid.setRating(0, for: videoIds)
                dismiss()
            } label: {
                Label("No rating", systemImage: "star.slash")
            }
            // Star ratings 1–5
            ForEach(1...5, id: \.self) { stars in
                Button {
                    grid.setRating(stars, for: videoIds)
                    dismiss()
                } label: {
                    Label(String(repeating: "★", count: stars), systemImage: "star.fill")
                        .foregroundStyle(.yellow)
                }
            }
        }
    }

    // MARK: Color label

    private var colorLabelSection: some View {
        Section("Color Label") {
            ForEach(ColorLabel.allCases) { label in
                Button {
                    grid.setColorLabel(label.rawValue, for: videoIds)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(label == .none ? Color(white: 0.4) : label.swatch)
                            .frame(width: 18, height: 18)
                            .overlay {
                                if label == .none {
                                    Image(systemName: "slash.circle")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        Text(label.displayName)
                            .foregroundStyle(.primary)
                        Spacer(minLength: 0)
                    }
                }
            }
        }
    }

    // MARK: Keywords

    private var keywordsSection: some View {
        Section {
            // Add-keyword row
            HStack {
                TextField("Add a keyword…", text: $newKeyword)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { addKeyword() }
                Button("Add") { addKeyword() }
                    .disabled(newKeyword.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            // Existing keyword rows: tap to apply to all selected; appearance is
            // unambiguous — the row applies a keyword, it doesn't filter by it.
            if !grid.tags.isEmpty {
                ForEach(grid.tags) { tag in
                    Button {
                        grid.applyKeyword(tag.name, to: videoIds)
                    } label: {
                        HStack {
                            Image(systemName: "tag")
                                .foregroundStyle(.secondary)
                                .frame(width: 20)
                            Text(tag.name)
                                .foregroundStyle(.primary)
                            Spacer(minLength: 0)
                            Image(systemName: "plus.circle")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        } header: {
            Text("Keywords")
        } footer: {
            Text("Tap a keyword to add it to all \(countLabel). Keywords that already exist on a video are skipped.")
                .font(.caption)
        }
    }

    // MARK: Collections

    @ViewBuilder
    private var collectionsSection: some View {
        if !manualCollections.isEmpty {
            Section("Add to Collection") {
                ForEach(manualCollections) { col in
                    Button {
                        grid.addToCollection(videoIds: videoIds, collectionId: col.id)
                        dismiss()
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.stack")
                                .foregroundStyle(.secondary)
                                .frame(width: 20)
                            Text(col.name)
                                .foregroundStyle(.primary)
                            Spacer(minLength: 0)
                            Image(systemName: "plus.circle")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    // MARK: Helpers

    private func addKeyword() {
        let name = newKeyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        grid.applyKeyword(name, to: videoIds)
        newKeyword = ""
    }
}
