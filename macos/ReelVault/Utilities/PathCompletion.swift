// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import ReelVaultKit

/// Filesystem helpers for the path-completion text field. Pure functions:
/// no actor isolation, no caching, no asynchronous work — the calls go
/// straight to `FileManager.default.contentsOfDirectory(atPath:)`, which
/// is fast enough for the dozens-of-entries typical of a user's home
/// directory and avoids the complication of background work invalidating
/// the field's local state.
enum PathCompletion {
    /// Snapshot of what the completion engine wants the UI to render.
    /// `matches` is every sibling whose name extends what the user typed;
    /// `bestMatch` is whichever of those we'd nominate as the most likely
    /// next pick (alphabetically first, today). `bestMatch` is nil
    /// exactly when `matches` is empty.
    struct Result {
        let matches: [String]
        let bestMatch: String?
    }

    /// Resolve `input` into a list of candidate child paths. Caller is
    /// responsible for the UI side (ghost rendering, dropdown, Tab).
    ///
    /// Special cases:
    ///   * `~/foo` → expanded to the user's home dir.
    ///   * Trailing `/` → list every direct child of that directory.
    ///   * Empty → no matches (no point completing nothing).
    static func complete(for input: String) -> Result {
        if input.isEmpty {
            return Result(matches: [], bestMatch: nil)
        }
        let expanded = expandTilde(input)

        // Split into "parent directory" + "partial child name." When the
        // user has typed a trailing slash they're asking for *all*
        // children, so partial is empty.
        let (parentDir, partial) = splitParentAndPartial(expanded)

        let children = directoryChildren(at: parentDir)
        let prefix = partial
        // Hide dotfiles unless the user is explicitly probing them by
        // typing a leading dot. Matches shell behavior and dramatically
        // cuts the noise in the home directory (.Trash, .DS_Store, …).
        let wantsDotfiles = prefix.hasPrefix(".")
        let matched: [String] = children.compactMap { child in
            if !wantsDotfiles && child.hasPrefix(".") { return nil }
            // Case-sensitive — filesystems are case-preserving but
            // case-sensitivity varies, so we match case-sensitively and
            // let the user's literal typing drive the result.
            guard prefix.isEmpty || child.hasPrefix(prefix) else { return nil }
            let full = parentDir.hasSuffix("/")
                ? parentDir + child
                : parentDir + "/" + child
            // Append "/" to directories so the user knows tabbing into
            // one carries them inside, matching shell behavior.
            return isDirectory(full) ? full + "/" : full
        }.sorted { $0.localizedCompare($1) == .orderedAscending }

        let best = matched.first
        return Result(matches: matched, bestMatch: best)
    }

    /// Trim `path` to just the leaf name for display in the dropdown —
    /// e.g. `/Users/brian/Downloads/` → `Downloads/`. Keeps trailing slash
    /// when present so directories stay distinguishable from files.
    static func displayName(of path: String) -> String {
        let stripped: Substring = path.hasSuffix("/")
            ? path.dropLast()
            : path[...]
        let withoutTrailing = String(stripped)
        let leaf = (withoutTrailing as NSString).lastPathComponent
        return path.hasSuffix("/") ? leaf + "/" : leaf
    }

    /// Longest common prefix across `paths`. Used by Tab when there are
    /// multiple matches — we extend the user's input to the LCP, then
    /// surface the dropdown for the remaining ambiguity.
    static func longestCommonPrefix(_ paths: [String]) -> String {
        guard let first = paths.first else { return "" }
        var prefix = first
        for path in paths.dropFirst() {
            while !path.hasPrefix(prefix) {
                prefix = String(prefix.dropLast())
                if prefix.isEmpty { return "" }
            }
        }
        return prefix
    }

    // MARK: - Private helpers

    private static func expandTilde(_ input: String) -> String {
        if input == "~" {
            return FileManager.default.homeDirectoryForCurrentUser.path
        }
        if input.hasPrefix("~/") {
            let home = FileManager.default.homeDirectoryForCurrentUser.path
            return home + String(input.dropFirst(1))
        }
        return input
    }

    /// Decide where the filesystem lookup should happen, and what the
    /// partial leaf name to filter by is. The boundary is the last "/"
    /// in the input — everything before is the directory we list, every
    /// thing after is the prefix we filter by.
    private static func splitParentAndPartial(_ path: String) -> (parent: String, partial: String) {
        if path.hasSuffix("/") {
            return (path, "")
        }
        if let lastSlash = path.lastIndex(of: "/") {
            let parent = String(path[...lastSlash])
            let partial = String(path[path.index(after: lastSlash)...])
            return (parent, partial)
        }
        // No slash at all — treat as relative to root for safety. This
        // matches typing "Vide" → no completions; the user has to start
        // with `/` or `~`.
        return ("/", path)
    }

    private static func directoryChildren(at dir: String) -> [String] {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: dir, isDirectory: &isDir), isDir.boolValue else {
            return []
        }
        // Skip dotfiles unless the user explicitly typed a dot — most
        // path-completion UIs do this and it cuts the noise on the home
        // directory dramatically.
        return (try? fm.contentsOfDirectory(atPath: dir)) ?? []
    }

    private static func isDirectory(_ path: String) -> Bool {
        var isDir: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: path, isDirectory: &isDir)
        return exists && isDir.boolValue
    }
}
