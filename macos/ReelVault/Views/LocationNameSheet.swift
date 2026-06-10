// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI

/// Name / rename a map location. Presented as a sheet when the user picks
/// "Name / Rename location" from the map view's right-click menu. On save the
/// caller upserts a named location (re-using the existing row for a rename) so
/// the spot resolves to the new name everywhere.
struct LocationNameSheet: View {
    /// Pre-filled name — empty for a brand-new name, the current name for a rename.
    let initialName: String
    let onCancel: () -> Void
    let onSave: (String) -> Void

    @State private var name: String

    init(initialName: String, onCancel: @escaping () -> Void, onSave: @escaping (String) -> Void) {
        self.initialName = initialName
        self.onCancel = onCancel
        self.onSave = onSave
        _name = State(initialValue: initialName)
    }

    private var isRename: Bool {
        !initialName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Image(systemName: "mappin.circle.fill")
                    .foregroundColor(.accentColor)
                Text(isRename ? "Rename this location" : "Name this location")
                    .font(.headline)
            }
            Text("Videos at this spot (within the place's radius) will show this name.")
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            TextField("Location name", text: $name)
                .textFieldStyle(.roundedBorder)
                .onSubmit { commit() }

            HStack {
                Spacer()
                Button("Cancel") { onCancel() }
                    .keyboardShortcut(.cancelAction)
                Button("Save") { commit() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 360)
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { onSave(trimmed) }
    }
}
