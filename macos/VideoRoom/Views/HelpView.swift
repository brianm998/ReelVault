// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import SwiftUI

/// Full-page help reference displayed from Help → VideoRoom Help.
/// Organised into collapsible sections so experienced users can jump straight
/// to what they need while newcomers can read top-to-bottom.
struct HelpView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            // ── Title bar ──────────────────────────────────────────────────
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("VideoRoom Help")
                        .font(.title2.bold())
                    Text("Your video catalog, explained")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("Close help")
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 14)

            Divider()

            // ── Scrollable content ─────────────────────────────────────────
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 0) {

                    HelpSection(icon: "film.stack", title: "What is VideoRoom?") {
                        HelpParagraph("""
                        VideoRoom is a **video catalog manager** — think Adobe Lightroom, but \
                        built exclusively for video files. It organises large collections of footage \
                        so you can find, inspect, tag, and hand off clips to professional editors, \
                        without VideoRoom ever modifying your original files.
                        """)
                        HelpParagraph("VideoRoom stores all metadata, tags, and settings in a small **catalog file** (`.vrcat`). Your video files stay exactly where they are on disk.")
                    }

                    HelpSection(icon: "checkmark.seal", title: "What VideoRoom can do") {
                        HelpBullets([
                            ("rectangle.grid.2x2", "Browse hundreds of thousands of clips at 60 fps in a thumbnail grid"),
                            ("info.circle", "Extract and display codec, resolution, FPS, bitrate, duration, GPS, camera model, and more"),
                            ("magnifyingglass", "Search instantly across filename, notes, and tags"),
                            ("tag", "Apply custom tags to any number of clips at once"),
                            ("square.stack.3d.up", "Group related variants into stacks (e.g. 4K + proxy of the same shot)"),
                            ("line.3.horizontal.decrease.circle", "Filter by camera, lens, codec, year, GPS radius, or library folder"),
                            ("arrow.down.doc", "Detect or generate lower-resolution proxies for oversize footage"),
                            ("play.rectangle", "Play clips inline using VLC (Linux/Windows) or native decoders (macOS)"),
                            ("arrow.up.forward.app", "Drag clips straight from the grid into DaVinci Resolve, Final Cut Pro, Premiere, Finder, and any app that accepts file drops"),
                            ("mappin.and.ellipse", "See geotagged clips on a world map; filter to a radius with one click"),
                            ("eye", "Watch library folders for new footage and update the catalog automatically"),
                        ])
                    }

                    HelpSection(icon: "xmark.seal", title: "What VideoRoom cannot do") {
                        HelpBullets([
                            ("scissors", "Edit, trim, or color-grade video (open in DaVinci Resolve, Premiere, etc. instead)"),
                            ("arrow.triangle.2.circlepath", "Transcode or encode (use Handbrake, FFmpeg, or your NLE's export panel)"),
                            ("icloud", "Sync libraries or manage cloud storage"),
                            ("play.slash", "Play video without a compatible codec — VLC is recommended on non-Apple platforms"),
                        ])
                    }

                    HelpSection(icon: "flag.checkered", title: "Getting started") {
                        HelpStep(number: "1", heading: "Create or open a catalog") {
                            Text("A catalog is a small database file that stores all your metadata, tags, and settings. Use ")
                            + Text("File → Open Catalog… (⌘O)").bold()
                            + Text(" to create a new one or open an existing one. Your video files are never moved or modified.")
                        }
                        HelpStep(number: "2", heading: "Add a library location") {
                            Text("Click the ")
                            + Text("folder+").bold()
                            + Text(" icon in the top bar (or the ")
                            + Text("+").bold()
                            + Text(" button in the left panel). You can add multiple folders in one session. Use ")
                            + Text("$YEAR").italic()
                            + Text(" in a path — for example ")
                            + Text("/Volumes/Footage/$YEAR/Raw").italic()
                            + Text(" — to import every matching year folder at once. VideoRoom scans in the background and the grid fills as files are indexed.")
                        }
                        HelpStep(number: "3", heading: "Browse and inspect") {
                            Text("Click any thumbnail to select it and load its full metadata in the ")
                            + Text("right panel").bold()
                            + Text(". The panel shows codec, resolution, FPS, bitrate, GPS, camera model, notes, tags, and more.")
                        }
                    }

                    HelpSection(icon: "rectangle.3.group", title: "Views") {
                        HelpTable(rows: [
                            ("G — Grid",   "Adaptive thumbnail grid. Drag the slider in the bottom bar to resize cards."),
                            ("L — List",   "Horizontal rows: thumbnail left, metadata columns right. Toggle columns in the right panel."),
                            ("D — Detail", "Full-window video player and inspector. Step through your library with ← / →."),
                        ])
                        HelpParagraph("Switch views with the segment control in the **bottom bar**, or press G, L, or D.")
                    }

                    HelpSection(icon: "cursorarrow.click.2", title: "Selecting clips") {
                        HelpTable(rows: [
                            ("Click",                "Select one clip"),
                            ("Shift-click",          "Extend the selection to include everything between the anchor and the clicked card"),
                            ("⌘-click",              "Add or remove individual clips from the selection"),
                            ("⌘A",                   "Select all currently-visible clips"),
                            ("⌘D",                   "Clear the selection"),
                            ("← ↑ → ↓",              "Navigate the grid one card at a time"),
                        ])
                    }

                    HelpSection(icon: "square.stack.3d.up", title: "Stacks") {
                        HelpParagraph("Stacks let a single card represent a group of related clips — useful for 4K originals paired with 1080p proxies, or multiple takes from the same setup.")
                        HelpBullets([
                            ("plus.square.on.square", "Select 2+ clips and press **⌘G** (or click the layers icon in the top bar) to create a stack"),
                            ("rectangle.expand.vertical", "Click the **N×** badge on a stack card to expand or collapse it inline"),
                            ("square.and.arrow.up", "Right-click → **Remove from stack**: pulls just that clip out; the rest stay grouped"),
                            ("rectangle.stack.badge.minus", "Right-click → **Unstack**: disbands the entire group so every clip stands alone"),
                            ("wand.and.stars", "VideoRoom **auto-stacks** matching variants during import (can be disabled per scan)"),
                        ])
                    }

                    HelpSection(icon: "rectangle.on.rectangle.slash", title: "Proxies") {
                        HelpParagraph("A proxy is a lower-resolution stand-in stored alongside the original and linked automatically. Use them when source footage is too large to play inline.")
                        HelpBullets([
                            ("exclamationmark.triangle", "Videos above the **inline-playback ceiling** (configurable via the playback settings button) show a warning badge"),
                            ("plus.rectangle.on.folder", "Right-click → **Create proxy…** to generate one; choose a target height (720p, 1080p, …)"),
                            ("arrow.triangle.2.circlepath", "Proxies are auto-detected when they appear in the same folder after a rescan"),
                            ("p.square", "The **P×N** badge on a card means N proxies are linked to that clip"),
                            ("slider.horizontal.3", "In Detail/Catalog view you can manually select which proxy to play"),
                        ])
                    }

                    HelpSection(icon: "magnifyingglass", title: "Search & filters") {
                        HelpBullets([
                            ("text.cursor",         "**Search bar**: live search across filename, notes, and tags"),
                            ("chevron.down.circle", "**Filter dropdowns** (Camera · Lens · Keyword · Codec · Year): stack multiple filters; click **Clear** to reset all"),
                            ("map",                 "**Map view** (globe icon in top bar): click a pin to filter to that GPS radius"),
                            ("sidebar.left",        "**Library panel** (left): click a folder to limit the grid to that location"),
                            ("folder.badge.gearshape", "Right-click → **Go to Folder in Library**: jumps the left panel to the containing folder"),
                        ])
                    }

                    HelpSection(icon: "tag", title: "Tags") {
                        HelpParagraph("Tags are catalog-only labels — they are **not** written into the video file. Add or remove tags from the right panel while one or more clips are selected, or filter the grid using the Keyword dropdown.")
                    }

                    HelpSection(icon: "arrow.up.forward.app", title: "External editors") {
                        HelpBullets([
                            ("hand.draw", "**Drag** one or more cards from the grid or list directly into DaVinci Resolve, Final Cut Pro, Premiere Pro, Finder, or any app that accepts file drops"),
                            ("wrench.and.screwdriver", "**Configure editors** via the wrench icon in the top bar — enable specific apps and check installation status"),
                            ("play.rectangle", "**Double-click** a row in List view to open it with the system's default media player"),
                        ])
                    }

                    HelpSection(icon: "plus.circle", title: "Library management") {
                        HelpBullets([
                            ("folder.badge.plus",   "Add as many source folders as you like — each appears as a row in the left panel"),
                            ("calendar",            "Use **$YEAR** in a path (e.g. `/archive/$YEAR/`) to add an entire decade of year folders in one click"),
                            ("arrow.clockwise",     "Hover over a library row in the left panel to reveal the **↺ rescan** button — useful after moving files"),
                            ("trash",               "Right-click or swipe left on a library row to remove it — your video files are not deleted"),
                        ])
                    }

                    HelpSection(icon: "eye", title: "Live updates") {
                        HelpParagraph("VideoRoom can watch your library folders for new footage using the operating system's file-change notifications. Toggle via the **Live** pill in the top bar or from the settings panel.")
                        HelpParagraph("When on, newly-added files appear in the grid within a few seconds of landing on disk. A short settle delay prevents half-written files from being indexed.")
                    }

                    HelpSection(icon: "keyboard", title: "Keyboard shortcuts") {
                        HelpTable(rows: [
                            ("G",       "Grid view"),
                            ("L",       "List view"),
                            ("D",       "Detail / Catalog view"),
                            ("I",       "Cycle info overlay (Detail mode: none → camera → file → …)"),
                            ("Tab",     "Toggle both side panels"),
                            ("Space",   "Play / pause selected clip"),
                            ("⌘G",      "Stack selected clips into a group"),
                            ("⌘A",      "Select all currently-visible clips"),
                            ("⌘D",      "Deselect all"),
                            ("⌘O",      "Open Catalog…"),
                            ("⌘⇧W",     "Close current catalog"),
                            ("← ↑ → ↓", "Navigate the grid"),
                            ("Escape",  "Clear search field focus"),
                        ])
                    }

                    HelpSection(icon: "lightbulb", title: "Tips & tricks") {
                        HelpBullets([
                            ("arrow.up.left.and.arrow.down.right", "The **thumbnail slider** in the bottom bar rescales in real time — find the density that suits your display"),
                            ("clock.arrow.circlepath",             "Rescan a folder from the left panel without re-adding it — hover the row and click **↺**"),
                            ("rectangle.compress.vertical",        "Press **Tab** to hide both panels and give the grid maximum screen space (Lightroom-style)"),
                            ("hand.draw",                          "Hold **Shift** to select a range in the grid, then drag the whole selection into your editor"),
                            ("calendar.badge.plus",                "Using **$YEAR** when adding a library (e.g. `/footage/$YEAR/`) imports decade-scale archives in one click"),
                            ("sparkles",                           "Auto-stacking during import groups 4K + 1080p variants automatically — look for the **N×** badge"),
                        ])
                    }

                    // Bottom padding
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 24)
                .padding(.top, 20)
            }
        }
        .frame(width: 680, height: 700)
        .background(Color(.windowBackgroundColor))
    }
}

// MARK: - Section container

private struct HelpSection<Content: View>: View {
    let icon: String
    let title: String
    @ViewBuilder let content: () -> Content

    @State private var isExpanded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Section header — click to collapse/expand
            Button {
                withAnimation(.easeInOut(duration: 0.18)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: icon)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 20)
                    Text(title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Divider()

            if isExpanded {
                VStack(alignment: .leading, spacing: 8) {
                    content()
                }
                .padding(.top, 10)
                .padding(.bottom, 14)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.bottom, 4)
    }
}

// MARK: - Content primitives

/// A paragraph with basic **bold** markdown rendering.
private struct HelpParagraph: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        (try? AttributedString(markdown: text,
                               options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            .map { Text($0).font(.body) }
        ?? Text(text).font(.body)
    }
}

/// A bullet list. Each item is `(sfSymbol, text)` where `text` supports **bold**.
private struct HelpBullets: View {
    let items: [(String, String)]
    init(_ items: [(String, String)]) { self.items = items }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(items.indices, id: \.self) { i in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: items[i].0)
                        .font(.system(size: 12))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 16, height: 18, alignment: .center)
                    (try? AttributedString(
                        markdown: items[i].1,
                        options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
                        .map { Text($0).font(.callout) }
                    ?? Text(items[i].1).font(.callout)
                }
            }
        }
    }
}

/// A two-column key/value table (used for shortcuts and view descriptions).
private struct HelpTable: View {
    let rows: [(String, String)]

    var body: some View {
        Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 4) {
            ForEach(rows.indices, id: \.self) { i in
                GridRow {
                    Text(rows[i].0)
                        .font(.system(.callout, design: .monospaced).bold())
                        .foregroundStyle(.secondary)
                        .gridColumnAlignment(.trailing)
                    Text(rows[i].1)
                        .font(.callout)
                        .gridColumnAlignment(.leading)
                }
            }
        }
    }
}

/// A numbered step with a heading and a `Text`-builder body.
private struct HelpStep: View {
    let number: String
    let heading: String
    let textContent: () -> Text

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(number)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Color.accentColor))
            VStack(alignment: .leading, spacing: 3) {
                Text(heading).font(.callout.bold())
                textContent().font(.callout)
            }
        }
    }
}

#Preview {
    HelpView()
}
