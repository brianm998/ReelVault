// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// The shared "infer a capture date from the filename" picker: a Format menu
/// (component ordering) and a Position menu (where in the name the date sits).
/// Used by the Library settings dialog and the Set-Capture-Date sheet so both
/// offer the identical control (and matches the Add-Library dialog's options).
struct FilenameDateInferenceControls: View {
    @Binding var format: String
    @Binding var position: String
    var disabled: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Format").font(.caption).foregroundColor(.secondary)
                Picker("Format", selection: $format) {
                    ForEach(FilenameDateInference.formats, id: \.value) { opt in
                        Text(opt.label).tag(opt.value)
                    }
                }
                .labelsHidden()
                .disabled(disabled)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Position").font(.caption).foregroundColor(.secondary)
                Picker("Position", selection: $position) {
                    ForEach(FilenameDateInference.positions, id: \.value) { opt in
                        Text(opt.label).tag(opt.value)
                    }
                }
                .labelsHidden()
                .disabled(disabled)
            }
        }
    }
}
