// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit

/// Sheet for adding a library location. Lets the user enter a path manually
/// or pick one via NSOpenPanel, choose whether to recurse into subdirectories,
/// and whether to auto-group variants.
struct AddLibraryDialog: View {
    @Binding var isPresented: Bool
    /// `dateFormat` is empty when the filename-date feature is off. When non-
    /// empty it is one of "MM-DD-YYYY" / "DD-MM-YYYY" / "YYYY-MM-DD"; in that
    /// case `datePosition` is one of "anywhere" / "beginning" / "end".
    let onConfirm: (
        _ path: String,
        _ recursive: Bool,
        _ autoGroup: Bool,
        _ dateFormat: String,
        _ datePosition: String
    ) -> Void

    @State private var path: String = ""
    @State private var recursive: Bool = true
    @State private var autoGroup: Bool = true
    @State private var inferDate: Bool = false
    @State private var dateFormat: String = "YYYY-MM-DD"
    @State private var datePosition: String = "anywhere"
    @State private var alwaysApply: Bool = false

    // UserDefaults keys for saved scan defaults.
    private static let ud = UserDefaults.standard
    private static let kHasSaved   = "videoroom.scanDefaults.hasSavedDefaults"
    private static let kInferDate  = "videoroom.scanDefaults.inferDate"
    private static let kDateFormat = "videoroom.scanDefaults.dateFormat"
    private static let kDatePos    = "videoroom.scanDefaults.datePosition"

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

            Toggle(isOn: $inferDate) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Infer capture date from filename")
                        .font(.body)
                    Text("Only applied when the file has no capture date in its metadata.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .help("When on, VideoRoom parses each video's filename for a date and uses it as the capture date if the file itself doesn't already have one. Useful for camera exports whose internal metadata lacks a capture time.")

            if inferDate {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Format")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Picker("", selection: $dateFormat) {
                            Text("YYYY-MM-DD").tag("YYYY-MM-DD")
                            Text("MM-DD-YYYY").tag("MM-DD-YYYY")
                            Text("DD-MM-YYYY").tag("DD-MM-YYYY")
                        }
                        .labelsHidden()
                        .help("Component ordering of the date as it appears in the filename. Separators (-, _, .) are matched automatically.")
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Position")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Picker("", selection: $datePosition) {
                            Text("Anywhere in filename").tag("anywhere")
                            Text("At the beginning").tag("beginning")
                            Text("At the end").tag("end")
                        }
                        .labelsHidden()
                        .help("Where the date must appear within the filename (without extension).")
                    }
                }

                Toggle(isOn: $alwaysApply) {
                    Text("Always apply these settings")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .help("Remember these date-inference settings and pre-fill them the next time you add a library location.")
            }

            HStack {
                Spacer()
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)
                .help("Close this dialog without adding the location.")

                Button("Add & Scan") {
                    let trimmed = path.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty {
                        // Persist date-inference defaults when requested.
                        if alwaysApply {
                            Self.ud.set(true,        forKey: Self.kHasSaved)
                            Self.ud.set(inferDate,   forKey: Self.kInferDate)
                            Self.ud.set(dateFormat,  forKey: Self.kDateFormat)
                            Self.ud.set(datePosition, forKey: Self.kDatePos)
                        }
                        onConfirm(
                            trimmed,
                            recursive,
                            autoGroup,
                            inferDate ? dateFormat : "",
                            inferDate ? datePosition : ""
                        )
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
        .onAppear {
            // Restore saved date-inference defaults (if the user previously
            // checked "Always apply these settings").
            if Self.ud.bool(forKey: Self.kHasSaved) {
                inferDate    = Self.ud.bool(forKey: Self.kInferDate)
                dateFormat   = Self.ud.string(forKey: Self.kDateFormat) ?? "YYYY-MM-DD"
                datePosition = Self.ud.string(forKey: Self.kDatePos)    ?? "anywhere"
            }
        }
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
