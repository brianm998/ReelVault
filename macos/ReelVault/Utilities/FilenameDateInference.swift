// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import ReelVaultKit

/// Client-side mirror of the core scanner's filename → capture-date inference
/// (`core/src/indexing.rs`, `FilenameDateRule`). Lets the UI preview and apply
/// a date parsed from a video's filename without a round-trip, using the exact
/// same format/position vocabulary the scanner accepts over gRPC.
///
/// Also owns the persisted *default* inference method, stored under the same
/// `reelvault.scanDefaults.*` UserDefaults keys the Add-Library dialog writes,
/// so a default set in one place (Library settings, the capture-date dialog, or
/// while adding a location) is honored everywhere.
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

    /// True when the user has saved a default and left inference enabled.
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

    /// Persist (or clear) the default inference method. Mirrors the keys the
    /// Add-Library dialog reads, so the two stay in sync.
    static func saveDefault(enabled: Bool, format: String, position: String) {
        let ud = UserDefaults.standard
        ud.set(true, forKey: kHasSaved)
        ud.set(enabled, forKey: kInferDate)
        ud.set(format, forKey: kDateFormat)
        ud.set(position, forKey: kDatePos)
    }

    /// Parse a date out of `filename` using `format` + `position`, returning it
    /// at noon local (matching the capture-date dialog's date-only commit), or
    /// nil when nothing valid matches. Mirrors `FilenameDateRule::parse_filename`:
    /// the extension is stripped, the year is always 4 digits, month/day 1–2,
    /// components separated by a single non-digit, ranges validated (month 1–12,
    /// day 1–31, year 1900–2999) and the date checked for real-ness (no Feb 30).
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
        else { return nil }  // rolled-over invalid date (e.g. Feb 30)
        return date
    }

    /// For the saved *default* method: the inferred capture timestamp (Unix ms,
    /// noon local) and a short label, or nil when no default is configured or
    /// `filename` has no match. Drives the grid/list right-click
    /// "Set Capture Date to …".
    static func inferDefault(filename: String) -> (timestampMs: Int64, label: String)? {
        guard defaultEnabled() else { return nil }
        guard let date = inferDate(filename: filename,
                                   format: savedFormat(), position: savedPosition())
        else { return nil }
        let ms = Int64(date.timeIntervalSince1970 * 1000.0)
        return (ms, date.formatted(date: .abbreviated, time: .omitted))
    }
}
