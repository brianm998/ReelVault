// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI
import AppKit

/// Sheet for adding one or more library locations in a single session.
///
/// The user starts with one path row and can click "Add another path" to
/// append more rows. Each row uses the existing `PathCompletingField` for
/// Tab-completion and directory browsing.
///
/// When a path contains `$YEAR` the dialog shows a local preview hint (e.g.
/// "Will expand to 12 directories…"). The authoritative expansion happens in
/// the Rust backend; this is UX only.
struct AddLibraryDialog: View {
    @Binding var isPresented: Bool
    /// Called when the user confirms. `paths` may contain more than one entry
    /// when the user added multiple rows or when a `$YEAR` template is used.
    /// `dateFormat` is empty when the filename-date feature is off.
    let onConfirm: (
        _ paths: [String],
        _ recursive: Bool,
        _ autoGroup: Bool,
        _ dateFormat: String,
        _ datePosition: String
    ) -> Void

    // Each element is the text in one path row.
    @State private var pathEntries: [String] = [""]
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

    private var hasAnyNonBlank: Bool {
        pathEntries.contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private var nonBlankCount: Int {
        pathEntries.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add Library Locations")
                .font(.headline)

            Text("Enter one or more directories. Use $YEAR in a path (e.g. /Volumes/Media/$YEAR/Raw) to add every matching year folder at once.")
                .font(.caption)
                .foregroundColor(.secondary)

            // ── Path list ────────────────────────────────────────────────
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(pathEntries.indices, id: \.self) { idx in
                        pathRow(idx: idx)
                    }
                }
            }
            .frame(maxHeight: 200)

            // "+  Add another path" button
            Button {
                pathEntries.append("")
            } label: {
                Label("Add another path", systemImage: "plus")
                    .font(.caption)
            }
            .buttonStyle(.borderless)
            .help("Add a second (or further) path to scan in the same session.")

            Divider()

            // ── Scan options ─────────────────────────────────────────────
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
                .help("Close this dialog without adding any locations.")

                Button(nonBlankCount > 1 ? "Add & Scan All" : "Add & Scan") {
                    let trimmed = pathEntries
                        .map { $0.trimmingCharacters(in: .whitespaces) }
                        .filter { !$0.isEmpty }
                    guard !trimmed.isEmpty else { return }

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
                .keyboardShortcut(.defaultAction)
                .disabled(!hasAnyNonBlank)
                .help("Save these locations and start scanning. Indexing runs in the background — you can keep using VideoRoom while it works.")
            }
        }
        .padding(20)
        .frame(width: 520)
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

    // MARK: - Path row

    @ViewBuilder
    private func pathRow(idx: Int) -> some View {
        // Client-side $YEAR preview: count how many year directories the
        // user's path template would match on the local filesystem.
        // The backend does the authoritative expansion; this is UX only.
        let pathValue = pathEntries[idx]
        let yearHint: String? = {
            guard pathValue.contains("$YEAR") else { return nil }
            let yearNow = Calendar.current.component(.year, from: Date())
            let matches = (1970...yearNow)
                .map { y in pathValue.replacingOccurrences(of: "$YEAR", with: String(y)) }
                .filter { FileManager.default.fileExists(atPath: $0) }
            if matches.isEmpty {
                return "No matching directories found on disk yet"
            }
            let example = matches.first ?? ""
            return "Will expand to \(matches.count) director\(matches.count == 1 ? "y" : "ies") (e.g. \(example))"
        }()

        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top) {
                // PathCompletingField provides Tab-to-complete + inline
                // grey ghost (after 5 chars) + a dropdown of matching
                // child directories.
                PathCompletingField(
                    text: Binding(
                        get: { pathEntries.indices.contains(idx) ? pathEntries[idx] : "" },
                        set: { if pathEntries.indices.contains(idx) { pathEntries[idx] = $0 } }
                    ),
                    placeholder: idx == 0 ? "/Users/you/Videos" : "Another path…"
                )
                .help("Full filesystem path to the folder containing your videos. Press Tab to complete, type more to narrow the suggestions, or pick from the dropdown. Use $YEAR to expand to every matching year folder.")

                Button("Choose…") {
                    chooseDirectory(for: idx)
                }
                .help("Open a file picker to choose a folder.")

                // Remove button — only shown when there are multiple rows.
                if pathEntries.count > 1 {
                    Button {
                        pathEntries.remove(at: idx)
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .help("Remove this path from the list.")
                }
            }

            if let hint = yearHint {
                Text(hint)
                    .font(.caption)
                    .foregroundColor(.accentColor)
            }
        }
    }

    // MARK: - Helpers

    private func chooseDirectory(for idx: Int) {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose"
        if panel.runModal() == .OK, let url = panel.url {
            if pathEntries.indices.contains(idx) {
                pathEntries[idx] = url.path
            }
        }
    }
}
