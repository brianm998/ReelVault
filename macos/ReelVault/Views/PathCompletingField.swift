// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit
import AppKit

/// A SwiftUI path-input field with bash/zsh-style tab completion against
/// the local filesystem. Three behaviors layered together:
///
///   * **Tab** completes the partial path. If there's a single match, the
///     field becomes that path. If multiple matches share a longer common
///     prefix than what's typed, the field is extended to that prefix and
///     a dropdown appears with the remaining candidates.
///   * **Inline grey ghost** — once the user has typed five characters or
///     more, the "most likely" next match (alphabetically first sibling
///     that starts with what they've typed) is rendered past the cursor
///     in the field's placeholder color so the user can see what Tab
///     would do without committing.
///   * **Dropdown** of every matching sibling whenever there's more than
///     one. Click a row to select it.
///
/// We render via an `NSTextField` subclass rather than SwiftUI's
/// `TextField` because (a) only AppKit exposes the field-editor command
/// selectors needed to capture the Tab key without losing focus, and
/// (b) only `attributedStringValue` reliably lets us paint a user-typed
/// prefix in `labelColor` while extending greyed-out ghost characters
/// past the cursor without text-layout drift between two overlapping
/// views.
struct PathCompletingField: View {
    @Binding var text: String
    var placeholder: String = ""

    /// Sibling paths under the parent directory of the current text that
    /// start with the partial last component. Updated whenever text
    /// changes. Empty when the parent directory doesn't exist or no
    /// children match.
    @State private var matches: [String] = []
    /// The "most likely" match — alphabetically first, by default.
    /// Empty when no matches exist or when there's only one.
    @State private var bestMatch: String = ""
    /// What the field paints after the cursor: the suffix of `bestMatch`
    /// past what the user has typed. Empty when there's no ghost to show.
    @State private var ghostSuffix: String = ""
    /// Whether the dropdown is currently visible. Hidden when there's
    /// 0 or 1 match (a single match is auto-completable via Tab, so the
    /// dropdown is just noise).
    @State private var dropdownOpen: Bool = false
    /// Index hovered in the dropdown — purely visual highlight.
    @State private var dropdownHover: Int? = nil
    /// Minimum total text length before we show the ghost. The product
    /// spec is "five or more chars" — below that, completions are too
    /// ambiguous to bother painting visually.
    private static let ghostThreshold = 5

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            _PathCompletingFieldRep(
                text: $text,
                ghostSuffix: $ghostSuffix,
                placeholder: placeholder,
                onTab: { handleTab() }
            )
            .frame(height: 22)

            if dropdownOpen && matches.count > 1 {
                dropdownView
            }
        }
        .onChange(of: text) { _, newValue in
            recompute(for: newValue)
        }
        .onAppear {
            recompute(for: text)
        }
    }

    /// The list of matching child paths. Plain `ScrollView` + `LazyVStack`
    /// rather than `List` so the dialog's surrounding padding/sizing
    /// stays predictable — `List` introduces its own insets that fight
    /// the dialog's layout.
    private var dropdownView: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(matches.enumerated()), id: \.offset) { idx, match in
                    let display = PathCompletion.displayName(of: match)
                    Text(display)
                        .font(.system(.body, design: .monospaced))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(dropdownHover == idx
                                    ? Color.accentColor.opacity(0.2)
                                    : Color.clear)
                        .contentShape(Rectangle())
                        .onHover { hovering in
                            dropdownHover = hovering ? idx : nil
                        }
                        .onTapGesture {
                            accept(match)
                        }
                }
            }
        }
        .frame(maxHeight: 160)
        .background(Color(.controlBackgroundColor))
        .overlay(
            RoundedRectangle(cornerRadius: 4)
                .stroke(Color(.separatorColor))
        )
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

    // MARK: - Logic

    private func recompute(for input: String) {
        let result = PathCompletion.complete(for: input)
        matches = result.matches
        bestMatch = result.bestMatch ?? ""
        // Ghost is the suffix of bestMatch past whatever the user typed,
        // but only painted when (a) we have a best match, (b) the user
        // has typed enough to make the suggestion meaningful, and (c) the
        // suggestion actually extends what's typed.
        if !bestMatch.isEmpty
            && input.count >= Self.ghostThreshold
            && bestMatch.hasPrefix(input)
            && bestMatch.count > input.count
        {
            ghostSuffix = String(bestMatch.dropFirst(input.count))
        } else {
            ghostSuffix = ""
        }
        // Dropdown auto-opens when there's genuine ambiguity; a single
        // unique match collapses it (Tab finishes the job).
        dropdownOpen = matches.count > 1
    }

    private func handleTab() {
        if matches.count == 1, let only = matches.first {
            accept(only)
            return
        }
        if matches.count > 1 {
            // Bash-style "extend to longest common prefix among the
            // candidates." If that prefix is longer than what's typed,
            // fill the difference; otherwise just surface the dropdown.
            let common = PathCompletion.longestCommonPrefix(matches)
            if common.count > text.count {
                text = common
            } else {
                dropdownOpen = true
            }
            return
        }
        // No matches — nothing to do.
    }

    private func accept(_ path: String) {
        text = path
        ghostSuffix = ""
        dropdownOpen = false
    }
}

// MARK: - NSViewRepresentable

/// Inner NSViewRepresentable that owns the `NSTextField`. Wrapped by the
/// public `PathCompletingField` so callers see only the friendly SwiftUI
/// surface plus the dropdown.
private struct _PathCompletingFieldRep: NSViewRepresentable {
    @Binding var text: String
    @Binding var ghostSuffix: String
    var placeholder: String
    var onTab: () -> Void

    func makeNSView(context: Context) -> _PathCompletingNSTextField {
        let field = _PathCompletingNSTextField()
        field.placeholderString = placeholder
        field.delegate = context.coordinator
        field.tabHandler = onTab
        field.userText = text
        field.ghostSuffix = ghostSuffix
        field.refreshAttributedDisplay()
        field.isBezeled = true
        field.bezelStyle = .roundedBezel
        field.focusRingType = .default
        return field
    }

    func updateNSView(_ nsView: _PathCompletingNSTextField, context: Context) {
        nsView.tabHandler = onTab
        // Only push state into the field when the values genuinely changed
        // — assigning identical values still resets the field editor's
        // selection, which yanks the cursor mid-type.
        if nsView.userText != text || nsView.ghostSuffix != ghostSuffix {
            nsView.userText = text
            nsView.ghostSuffix = ghostSuffix
            nsView.refreshAttributedDisplay()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    /// Bridges the AppKit delegate callbacks back into SwiftUI state.
    final class Coordinator: NSObject, NSTextFieldDelegate {
        var parent: _PathCompletingFieldRep
        init(_ parent: _PathCompletingFieldRep) {
            self.parent = parent
        }

        /// Fired as the user types or deletes. The field editor's current
        /// `stringValue` includes the previously-painted ghost suffix as
        /// a literal string in its buffer (because we set the field's
        /// attributedStringValue to userText+ghost), so we strip the
        /// trailing ghost off before publishing what the user "really"
        /// typed. The recomputed ghost will be repainted on the next
        /// `updateNSView` pass.
        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? _PathCompletingNSTextField,
                  !field.isApplyingProgrammaticChange
            else { return }
            var raw = field.stringValue
            if !field.ghostSuffix.isEmpty && raw.hasSuffix(field.ghostSuffix) {
                raw = String(raw.dropLast(field.ghostSuffix.count))
            }
            field.userText = raw
            field.ghostSuffix = ""
            parent.text = raw
        }

        /// Intercepts special-key command selectors. The field editor
        /// hands us each non-character keystroke; returning `true` says
        /// "we handled it." Tab (insertTab:) is the only one we claim;
        /// everything else (return, escape, arrow keys) falls back to
        /// AppKit defaults.
        func control(
            _ control: NSControl,
            textView: NSTextView,
            doCommandBy commandSelector: Selector
        ) -> Bool {
            if commandSelector == #selector(NSResponder.insertTab(_:)) {
                parent.onTab()
                return true
            }
            return false
        }
    }
}

/// NSTextField subclass that knows how to paint a ghosted completion
/// past the user's typed text. The "ghost" is purely visual — the field
/// editor's actual buffer is `userText`, so cursor movement and editing
/// stay sane.
private final class _PathCompletingNSTextField: NSTextField {
    /// What the user has actually typed (also the field editor's
    /// stringValue when there's no ghost).
    var userText: String = ""
    /// The greyed-out characters drawn past the cursor.
    var ghostSuffix: String = ""
    /// Closure to fire when the field editor reports a Tab keystroke.
    var tabHandler: (() -> Void)?
    /// Guard against re-entrant `controlTextDidChange` from our own
    /// attributed-string assignments.
    fileprivate var isApplyingProgrammaticChange = false

    /// Re-paint the field with `userText` in `labelColor` followed by
    /// `ghostSuffix` in `placeholderTextColor`. Cursor is restored to
    /// sit between the two so the user can keep typing without the ghost
    /// getting in the way.
    func refreshAttributedDisplay() {
        isApplyingProgrammaticChange = true
        defer { isApplyingProgrammaticChange = false }

        // SwiftUI's TextField on macOS uses the system font at the
        // current text size by default; we mirror that here so the field
        // looks at home in dialogs that intersperse other inputs.
        let font = self.font ?? NSFont.systemFont(ofSize: NSFont.systemFontSize)
        let attr = NSMutableAttributedString(
            string: userText,
            attributes: [
                .foregroundColor: NSColor.labelColor,
                .font: font,
            ]
        )
        if !ghostSuffix.isEmpty {
            attr.append(NSAttributedString(
                string: ghostSuffix,
                attributes: [
                    .foregroundColor: NSColor.placeholderTextColor,
                    .font: font,
                ]
            ))
        }
        // Setting `attributedStringValue` blows away the field editor's
        // selection, so we restore the cursor right after to feel like
        // the user never lost their place.
        self.attributedStringValue = attr
        if let editor = self.currentEditor() {
            let cursor = (userText as NSString).length
            editor.selectedRange = NSRange(location: cursor, length: 0)
        }
    }
}
