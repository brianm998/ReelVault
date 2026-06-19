// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import ReelVaultKit
import SwiftUI

// MARK: - Filename date inference (iOS-local mirror of macOS FilenameDateInference)

/// Client-side mirror of the core scanner's filename → capture-date inference
/// (`core/src/indexing.rs`, `FilenameDateRule`). Lets the UI preview and apply
/// a date parsed from a video's filename without a round-trip.
///
/// Mirrors the macOS version in macos/ReelVault/Utilities/FilenameDateInference.swift —
/// kept iOS-local so the macOS file is not displaced (they share the same
/// UserDefaults keys so a default set on one client is honored on the other).
enum FilenameDateInference {
    /// (proto value, human label) for the component ordering.
    static let formats: [(value: String, label: String)] = [
        ("YYYY-MM-DD", "Year-Month-Day  (2024-04-24)"),
        ("MM-DD-YYYY", "Month-Day-Year  (04-24-2024)"),
        ("DD-MM-YYYY", "Day-Month-Year  (24-04-2024)"),
    ]

    /// (proto value, human label) for where in the name the date must appear.
    static let positions: [(value: String, label: String)] = [
        ("anywhere", "Anywhere in the name"),
        ("beginning", "At the start of the name"),
        ("end", "At the end of the name"),
    ]

    static let defaultFormatValue = "YYYY-MM-DD"
    static let defaultPositionValue = "anywhere"

    private static let kHasSaved = "reelvault.scanDefaults.hasSavedDefaults"
    private static let kInferDate = "reelvault.scanDefaults.inferDate"
    private static let kDateFormat = "reelvault.scanDefaults.dateFormat"
    private static let kDatePos = "reelvault.scanDefaults.datePosition"

    static func defaultEnabled() -> Bool {
        let ud = UserDefaults.standard
        return ud.bool(forKey: kHasSaved) && ud.bool(forKey: kInferDate)
    }

    static func savedFormat() -> String {
        UserDefaults.standard.string(forKey: kDateFormat) ?? defaultFormatValue
    }

    static func savedPosition() -> String {
        UserDefaults.standard.string(forKey: kDatePos) ?? defaultPositionValue
    }

    static func saveDefault(enabled: Bool, format: String, position: String) {
        let ud = UserDefaults.standard
        ud.set(true, forKey: kHasSaved)
        ud.set(enabled, forKey: kInferDate)
        ud.set(format, forKey: kDateFormat)
        ud.set(position, forKey: kDatePos)
    }

    /// Parse a date out of `filename` using `format` + `position`, returning it
    /// at noon local, or nil when no match. Mirrors the macOS implementation.
    static func inferDate(filename: String, format: String, position: String) -> Date? {
        let stem = (filename as NSString).deletingPathExtension
        let groups: (String, String, String)
        switch format {
        case "MM-DD-YYYY", "DD-MM-YYYY": groups = ("(\\d{1,2})", "(\\d{1,2})", "(\\d{4})")
        case "YYYY-MM-DD": groups = ("(\\d{4})", "(\\d{1,2})", "(\\d{1,2})")
        default: return nil
        }
        let body = "\(groups.0)\\D\(groups.1)\\D\(groups.2)"
        let pattern: String
        switch position {
        case "beginning": pattern = "^" + body
        case "end": pattern = body + "$"
        default: pattern = body
        }
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let nsRange = NSRange(stem.startIndex..<stem.endIndex, in: stem)
        guard let match = re.firstMatch(in: stem, range: nsRange), match.numberOfRanges >= 4 else {
            return nil
        }
        func intGroup(_ i: Int) -> Int? {
            guard let r = Range(match.range(at: i), in: stem) else { return nil }
            return Int(stem[r])
        }
        guard let a = intGroup(1), let b = intGroup(2), let c = intGroup(3) else { return nil }
        let year: Int, month: Int, day: Int
        switch format {
        case "MM-DD-YYYY": (year, month, day) = (c, a, b)
        case "DD-MM-YYYY": (year, month, day) = (c, b, a)
        case "YYYY-MM-DD": (year, month, day) = (a, b, c)
        default: return nil
        }
        guard (1...12).contains(month), (1...31).contains(day), (1900...2999).contains(year) else {
            return nil
        }
        var comps = DateComponents()
        comps.year = year; comps.month = month; comps.day = day
        comps.hour = 12; comps.minute = 0; comps.second = 0
        let cal = Calendar.current
        guard let date = cal.date(from: comps),
              cal.component(.year, from: date) == year,
              cal.component(.month, from: date) == month,
              cal.component(.day, from: date) == day
        else { return nil }
        return date
    }
}

// MARK: - CaptureDateButtonSection

/// "Set / Change capture date" controls for a video — the iOS counterpart of the
/// macOS detail panel's capture-date button. Shown at the bottom of the detail
/// view and in the inspector panel. Reads the live grid row so the label tracks
/// edits; sheet presentation is owned internally (matching the LocationButtonsSection
/// pattern) so this view is self-contained and usable in both contexts.
struct CaptureDateButtonSection: View {
    @ObservedObject var grid: GridViewModel
    let videoId: String
    /// Drop the "Capture Date" headline when hosted inside a CollapsibleSection
    /// (which supplies its own header) — the inspector does this.
    var showHeader: Bool = true
    @State private var showSheet = false

    private var video: VideoSummary? {
        grid.videos.first(where: { $0.id == videoId })
            ?? (grid.selectedVideo?.id == videoId ? grid.selectedVideo : nil)
    }

    var body: some View {
        if let video {
            VStack(alignment: .leading, spacing: 8) {
                if showHeader { Text("Capture Date").font(.headline) }
                if video.creationDate > 0 {
                    Text(captureLabel(video.creationDate))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Button {
                    showSheet = true
                } label: {
                    Label(
                        video.creationDate > 0 ? "Change capture date…" : "Set capture date…",
                        systemImage: "calendar"
                    )
                }
                .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .sheet(isPresented: $showSheet) {
                CaptureDateSheet(
                    videoId: video.id,
                    filename: video.filename,
                    initialTimestampMs: video.creationDate > 0 ? video.creationDate : nil,
                    grid: grid)
            }
        }
    }

    private func captureLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
        return date.formatted(date: .long, time: .shortened)
    }
}

// MARK: - CaptureDateSheet

/// iOS sheet for setting the capture date/time on a video. The iOS counterpart
/// of the macOS CaptureDateView — uses a native DatePicker in compact (wheel)
/// style, a toggle for including the time component, a "write into file" toggle,
/// and an optional filename-based date inference section.
///
/// All persistence goes through the shared `GridViewModel.setVideoCaptureDates`,
/// matching the macOS path exactly.
struct CaptureDateSheet: View {
    let videoId: String
    let filename: String
    /// Existing capture timestamp (Unix ms, UTC), if any.
    let initialTimestampMs: Int64?
    @ObservedObject var grid: GridViewModel

    @Environment(\.dismiss) private var dismiss

    @State private var date: Date
    @State private var includeTime: Bool
    @State private var writeToFile: Bool = false

    // Filename-based capture-date inference.
    @State private var showInfer = false
    @State private var inferFormat: String = FilenameDateInference.savedFormat()
    @State private var inferPosition: String = FilenameDateInference.savedPosition()
    @State private var setInferAsDefault = false
    @State private var isSaving = false

    init(videoId: String, filename: String, initialTimestampMs: Int64?,
         grid: GridViewModel) {
        self.videoId = videoId
        self.filename = filename
        self.initialTimestampMs = initialTimestampMs
        self.grid = grid
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
        NavigationStack {
            Form {
                // Date / time picker section
                Section {
                    DatePicker(
                        "Date",
                        selection: $date,
                        displayedComponents: [.date]
                    )
                    Toggle("Set a specific time", isOn: $includeTime)
                    if includeTime {
                        DatePicker(
                            "Time",
                            selection: $date,
                            displayedComponents: [.hourAndMinute]
                        )
                    }
                } header: {
                    Text("Capture Date")
                } footer: {
                    Text("Will save as: \(resolvedDateLabel)")
                        .foregroundStyle(.secondary)
                }

                // Write-to-file option
                Section {
                    Toggle("Also embed in video file", isOn: $writeToFile)
                } footer: {
                    Text("Uses ffmpeg to rewrite the file's creation_time metadata without re-encoding.")
                }

                // Filename-based date inference
                Section {
                    if let d = defaultInferred {
                        Button("Set as \(Self.dateLabel(d))") {
                            applyInferred(d)
                        }
                    }
                    Button(showInfer ? "Hide filename options" : "Infer from filename…") {
                        withAnimation { showInfer.toggle() }
                    }

                    if showInfer {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Filename: \(filename)")
                                .font(.caption).foregroundStyle(.secondary)
                                .lineLimit(1).truncationMode(.middle)
                            Picker("Format", selection: $inferFormat) {
                                ForEach(FilenameDateInference.formats, id: \.value) { opt in
                                    Text(opt.label).tag(opt.value)
                                }
                            }
                            Picker("Position", selection: $inferPosition) {
                                ForEach(FilenameDateInference.positions, id: \.value) { opt in
                                    Text(opt.label).tag(opt.value)
                                }
                            }
                            if let p = previewInferred {
                                Text("Detected: \(Self.dateLabel(p))")
                                    .foregroundStyle(Color.accentColor)
                                    .font(.callout)
                            } else {
                                Text("No date matches this pattern in the filename.")
                                    .font(.caption).foregroundStyle(.red)
                            }
                            Toggle("Remember as my default method", isOn: $setInferAsDefault)
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
                } header: {
                    Text("Infer from Filename")
                }
            }
            .navigationTitle("Capture Date")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(isSaving)
                }
            }
        }
    }

    // MARK: - Computed helpers

    private var resolvedDate: Date {
        if includeTime { return date }
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        comps.hour = 12
        comps.minute = 0
        comps.second = 0
        return Calendar.current.date(from: comps) ?? date
    }

    private var resolvedDateLabel: String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = includeTime ? .short : .none
        return f.string(from: resolvedDate)
    }

    private var defaultInferred: Date? {
        guard FilenameDateInference.defaultEnabled() else { return nil }
        return FilenameDateInference.inferDate(
            filename: filename,
            format: FilenameDateInference.savedFormat(),
            position: FilenameDateInference.savedPosition())
    }

    private var previewInferred: Date? {
        FilenameDateInference.inferDate(filename: filename, format: inferFormat, position: inferPosition)
    }

    private static func dateLabel(_ d: Date) -> String {
        d.formatted(date: .abbreviated, time: .omitted)
    }

    // MARK: - Actions

    private func applyInferred(_ inferred: Date) {
        // Apply the inferred date to the picker and close — mirrors macOS behaviour.
        date = inferred
        includeTime = false
        showInfer = false
    }

    private func save() {
        isSaving = true
        let ms = Int64(resolvedDate.timeIntervalSince1970 * 1000.0)
        grid.setVideoCaptureDates(
            videoIds: [videoId],
            timestampMs: ms,
            writeToFile: writeToFile
        ) {
            dismiss()
        }
    }
}
