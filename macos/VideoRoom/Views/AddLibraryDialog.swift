// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit

/// Sheet for adding a library location. Lets the user enter a path manually
/// or pick one via NSOpenPanel, choose whether to recurse into subdirectories,
/// and whether to auto-group variants.
struct AddLibraryDialog: View {
    @Binding var isPresented: Bool
    let onConfirm: (_ path: String, _ recursive: Bool, _ autoGroup: Bool) -> Void

    @State private var path: String = ""
    @State private var recursive: Bool = true
    @State private var autoGroup: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add Library Location")
                .font(.headline)

            Text("Choose a directory containing videos.")
                .font(.caption)
                .foregroundColor(.secondary)

            HStack(alignment: .top) {
                // PathCompletingField provides Tab-to-complete + inline
                // grey ghost (after 5 chars) + a dropdown of matching
                // child directories. The Choose… button remains for users
                // who'd rather navigate to the folder with a file picker.
                PathCompletingField(text: $path, placeholder: "/Users/you/Videos")
                    .help("Full filesystem path to the folder containing your videos. Press Tab to complete, type more to narrow the suggestions, or pick from the dropdown.")
                Button("Choose…") {
                    chooseDirectory()
                }
                .help("Open a file picker to choose a folder.")
            }

            Toggle(isOn: $recursive) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Scan subdirectories")
                        .font(.body)
                    Text("When off, only video files directly in this folder are indexed.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .help("When on, VideoRoom walks into every subdirectory. When off, only files directly inside the chosen folder are indexed.")

            Toggle(isOn: $autoGroup) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Auto-group similar variants")
                        .font(.body)
                    Text("Stack videos that share a base name, duration, and frame rate.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .help("When on, videos that share a base filename, duration, and frame rate are automatically stacked together (e.g. 4K + 1080p exports of the same clip). You can always group/ungroup manually later.")

            HStack {
                Spacer()
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)
                .help("Close this dialog without adding the location.")

                Button("Add & Scan") {
                    if !path.trimmingCharacters(in: .whitespaces).isEmpty {
                        onConfirm(path.trimmingCharacters(in: .whitespaces), recursive, autoGroup)
                        isPresented = false
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(path.trimmingCharacters(in: .whitespaces).isEmpty)
                .help("Save this location and start scanning. Indexing runs in the background — you can keep using VideoRoom while it works.")
            }
        }
        .padding(20)
        .frame(width: 480)
    }

    private func chooseDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose"
        if panel.runModal() == .OK, let url = panel.url {
            path = url.path
        }
    }
}
