// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Displays the filter criteria (rules) of a smart collection, mirroring the
/// macOS DetailView `smartCollectionCriteriaView`. Shown in two contexts:
///
/// - **iPad inspector**: as the "no selection" placeholder when a smart
///   collection is the active source (trailing inspector panel).
/// - **iPhone / iPad sidebar**: as a sheet presented from the "View Rules"
///   swipe action on a smart collection row in the library sidebar.
///
/// Read-only viewing is always shown. Criterion removal requires a
/// confirmation alert, matching the macOS pattern.
struct SmartCollectionCriteriaView: View {
    @ObservedObject var grid: GridViewModel
    /// The collection as it existed when the sheet/panel was opened. Used only as
    /// a seed: `liveCollection` re-looks it up from `grid.collections` by name on
    /// every render so subsequent removals (which delete + re-create the collection
    /// with a new ID) operate on the current object rather than the stale one.
    let collection: Collection

    @State private var pendingCriterion: SmartCriterionRow?

    /// The current version of the collection from the live list, keyed by name.
    /// Falls back to the seed `collection` while the reload is in flight.
    private var liveCollection: Collection {
        grid.collections.first(where: { $0.name == collection.name && $0.isSmart }) ?? collection
    }

    var body: some View {
        let criteria = grid.smartCollectionCriteria(liveCollection)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(liveCollection.name)
                        .font(.headline)
                    Text("Smart collection — videos are gathered automatically by these rules:")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if criteria.isEmpty {
                    Text("No rules set — this collection matches every video.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(criteria) { row in
                            HStack(alignment: .top, spacing: 10) {
                                Text(row.label)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .frame(minWidth: 72, alignment: .leading)
                                if row.criterion == .colorLabel {
                                    HStack(spacing: 6) {
                                        RoundedRectangle(cornerRadius: 3)
                                            .fill(ColorLabel(row.value.lowercased()).swatch)
                                            .frame(width: 12, height: 12)
                                        Text(row.value)
                                            .font(.callout)
                                    }
                                } else {
                                    Text(row.value)
                                        .font(.callout)
                                }
                                Spacer()
                                Button {
                                    pendingCriterion = row
                                } label: {
                                    Image(systemName: "xmark.circle")
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Remove rule: \(row.label)")
                            }
                            Divider()
                        }
                    }
                    .padding(.top, 4)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .alert("Remove rule?",
               isPresented: Binding(
                   get: { pendingCriterion != nil },
                   set: { if !$0 { pendingCriterion = nil } }
               ),
               presenting: pendingCriterion
        ) { row in
            Button("Remove", role: .destructive) {
                grid.removeSmartCollectionCriterion(liveCollection, row.criterion)
                pendingCriterion = nil
            }
            Button("Cancel", role: .cancel) { pendingCriterion = nil }
        } message: { row in
            Text("Remove \"\(row.label): \(row.value)\" from smart collection \"\(liveCollection.name)\"? This changes what the collection gathers.")
        }
    }
}

/// Standalone sheet wrapper used by LibrarySidebar (and anywhere else that
/// presents criteria as a full sheet rather than an inline panel section).
struct SmartCollectionCriteriaSheet: View {
    @ObservedObject var grid: GridViewModel
    let collection: Collection
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            SmartCollectionCriteriaView(grid: grid, collection: collection)
                .navigationTitle("Smart Collection Rules")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done", action: onDismiss)
                    }
                }
        }
        .presentationDetents([.medium, .large])
    }
}
