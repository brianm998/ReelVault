// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// Sheet for setting the capture date/time on one or more videos. Uses
/// SwiftUI's native `DatePicker` for both the calendar and the time picker,
/// with a toggle that decides whether the time component is applied.
///
/// All persistence goes through the backend as Unix milliseconds (UTC).
/// The picker shows local-time wall-clock to match the user's mental model;
/// conversion happens at commit time.
struct CaptureDateView: View {
    let targetVideoIds: [String]
    /// Existing capture timestamp (Unix ms, UTC), if any.
    let initialTimestampMs: Int64?
    /// Filename of the single target video, when exactly one is selected — used
    /// to offer filename-based date inference. Nil for multi-select (per-file
    /// inference would differ) so the infer controls hide.
    let primaryFilename: String?
    let onCancel: () -> Void
    let onApply: (_ timestampMs: Int64, _ writeToFile: Bool) -> Void

    @State private var date: Date
    @State private var includeTime: Bool
    @State private var writeToFile: Bool = false

    // Filename-based capture-date inference.
    @State private var showInfer = false
    @State private var inferFormat: String = FilenameDateInference.savedFormat()
    @State private var inferPosition: String = FilenameDateInference.savedPosition()
    @State private var setInferAsDefault = false

    init(
        targetVideoIds: [String],
        initialTimestampMs: Int64?,
        primaryFilename: String? = nil,
        onCancel: @escaping () -> Void,
        onApply: @escaping (Int64, Bool) -> Void
    ) {
        self.targetVideoIds = targetVideoIds
        self.initialTimestampMs = initialTimestampMs
        self.primaryFilename = primaryFilename
        self.onCancel = onCancel
        self.onApply = onApply
        let initial: Date
        if let ms = initialTimestampMs {
            initial = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
        } else {
            // Default to noon today so timezone-shift effects on the day
            // boundary don't surprise the user.
            let now = Date()
            var comps = Calendar.current.dateComponents([.year, .month, .day], from: now)
            comps.hour = 12
            comps.minute = 0
            initial = Calendar.current.date(from: comps) ?? now
        }
        _date = State(initialValue: initial)
        _includeTime = State(initialValue: initialTimestampMs != nil)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "calendar")
                Text(targetVideoIds.count == 1
                     ? "Set Capture Date"
                     : "Set Capture Date for \(targetVideoIds.count) Videos")
                    .font(.headline)
                Spacer()
            }

            Text("Pick the day the video was recorded. Add a specific time only if you know it — otherwise the day defaults to noon local time so timezone math behaves sensibly.")
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            DatePicker(
                "Capture date",
                selection: $date,
                displayedComponents: includeTime ? [.date, .hourAndMinute] : [.date]
            )
            .datePickerStyle(.graphical)
            .labelsHidden()

            Toggle("Also set a specific time", isOn: $includeTime)
                .toggleStyle(.checkbox)

            // Live readout of the resolved timestamp.
            let fmt: DateFormatter = {
                let f = DateFormatter()
                f.dateStyle = .medium
                f.timeStyle = includeTime ? .short : .none
                return f
            }()
            Text("Will save as: \(fmt.string(from: resolvedDate))")
                .font(.caption)
                .foregroundColor(.accentColor)

            Toggle(isOn: $writeToFile) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Also embed in video file")
                        .font(.body)
                    Text("Uses ffmpeg to rewrite the file's creation_time metadata without re-encoding.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            // Infer the capture date from the filename — single target only
            // (per-file inference would differ for a multi-selection).
            if let filename = primaryFilename {
                Divider()
                HStack(spacing: 8) {
                    // Quick-apply the default method — only when a default is
                    // configured AND it matches this filename.
                    if let d = defaultInferred {
                        Button("Set as \(Self.dateLabel(d))") { applyInferred(d) }
                            .help("Apply \(Self.dateLabel(d)), read from the filename with your default method.")
                    }
                    Button(showInfer ? "Hide filename options" : "Infer from filename…") {
                        showInfer.toggle()
                    }
                    Spacer()
                }
                if showInfer {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Filename: \(filename)")
                            .font(.caption).foregroundColor(.secondary)
                            .lineLimit(1).truncationMode(.middle)
                        FilenameDateInferenceControls(format: $inferFormat, position: $inferPosition)
                        if let p = previewInferred {
                            Text("Detected: \(Self.dateLabel(p))").foregroundColor(.accentColor)
                        } else {
                            Text("No date matches this pattern in the filename.")
                                .font(.caption).foregroundColor(.red)
                        }
                        Toggle("Remember this as my default capture-date method", isOn: $setInferAsDefault)
                            .toggleStyle(.checkbox)
                        Button("Use detected date") {
                            if setInferAsDefault {
                                FilenameDateInference.saveDefault(
                                    enabled: true, format: inferFormat, position: inferPosition)
                            }
                            if let p = previewInferred { applyInferred(p) }
                        }
                        .disabled(previewInferred == nil)
                    }
                }
            }

            HStack {
                Spacer()
                Button("Cancel") { onCancel() }
                    .keyboardShortcut(.cancelAction)
                Button("Save") {
                    let ms = Int64(resolvedDate.timeIntervalSince1970 * 1000.0)
                    onApply(ms, writeToFile)
                }
                .keyboardShortcut(.defaultAction)
                .help(targetVideoIds.count == 1
                      ? "Apply the selected date to this video."
                      : "Apply the selected date to all \(targetVideoIds.count) selected videos.")
            }
        }
        .padding(20)
        .frame(width: 480)
    }

    /// The saved-default method's result for this filename — drives the quick
    /// "Set as <date>" button (only when a default is configured and matches).
    private var defaultInferred: Date? {
        guard let f = primaryFilename, FilenameDateInference.defaultEnabled() else { return nil }
        return FilenameDateInference.inferDate(
            filename: f,
            format: FilenameDateInference.savedFormat(),
            position: FilenameDateInference.savedPosition())
    }

    /// The current picker's result — the live preview in the infer section.
    private var previewInferred: Date? {
        guard let f = primaryFilename else { return nil }
        return FilenameDateInference.inferDate(filename: f, format: inferFormat, position: inferPosition)
    }

    /// Apply an inferred (noon-local) date and close.
    private func applyInferred(_ date: Date) {
        onApply(Int64(date.timeIntervalSince1970 * 1000.0), writeToFile)
    }

    private static func dateLabel(_ d: Date) -> String {
        d.formatted(date: .abbreviated, time: .omitted)
    }

    /// The date the user will actually save. When `includeTime` is off, we
    /// normalize to noon local — matches the dialog's stated promise and
    /// avoids midnight-boundary surprises.
    private var resolvedDate: Date {
        if includeTime { return date }
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        comps.hour = 12
        comps.minute = 0
        comps.second = 0
        return Calendar.current.date(from: comps) ?? date
    }
}
