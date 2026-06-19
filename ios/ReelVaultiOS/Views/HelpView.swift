// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import SwiftUI
import ReelVaultKit

/// In-app help reference. Pushed from the Settings NavigationStack via a
/// NavigationLink (HelpLinkSection). Must NOT contain its own NavigationStack —
/// the parent already provides nav chrome.
struct HelpView: View {
    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 0) {

                    HelpSection(icon: "film.stack", title: "What is ReelVault?") {
                        HelpParagraph("""
                        ReelVault is a **video catalog manager** — think Adobe Lightroom, but \
                        built exclusively for video files. It organises large collections of footage \
                        so you can find, inspect, tag, and hand off clips to professional editors, \
                        without ReelVault ever modifying your original files.
                        """)
                        HelpParagraph("ReelVault stores all metadata, tags, and settings in a small **catalog file** (`.vrcat`). Your video files stay exactly where they are on disk.")
                    }

                    HelpSection(icon: "checkmark.seal", title: "What ReelVault can do") {
                        HelpBullets([
                            ("rectangle.grid.2x2", "Browse hundreds of thousands of clips in a thumbnail grid"),
                            ("info.circle", "Extract and display codec, resolution, FPS, bitrate, duration, GPS, camera model, and more"),
                            ("magnifyingglass", "Search instantly across filename, notes, and tags"),
                            ("tag", "Apply custom tags to any number of clips at once"),
                            ("rectangle.stack", "Gather clips into manual collections, or let smart collections fill themselves from a filter"),
                            ("star", "Rate clips 0–5 and flag them with color labels, then filter by either"),
                            ("square.stack.3d.up", "Group related variants into stacks (e.g. 4K + proxy of the same shot)"),
                            ("line.3.horizontal.decrease.circle", "Filter by camera, lens, codec, year, GPS radius, or library folder"),
                            ("arrow.down.doc", "Detect, generate, or hand-link lower-resolution proxies for oversize footage"),
                            ("play.rectangle", "Play clips inline using native decoders"),
                            ("square.and.arrow.up", "Hand clips off to external editors via the iOS share sheet"),
                            ("mappin.and.ellipse", "See geotagged clips on a world map; filter to a radius with one tap"),
                            ("mappin", "Drop, move, or name GPS locations by hand — even for clips with no embedded coordinates"),
                            ("eye", "Watch library folders for new footage and update the catalog automatically"),
                        ])
                    }

                    HelpSection(icon: "xmark.seal", title: "What ReelVault cannot do") {
                        HelpBullets([
                            ("scissors", "Edit, trim, or color-grade video (open in DaVinci Resolve, Premiere, etc. instead)"),
                            ("arrow.triangle.2.circlepath", "Transcode or encode (use Handbrake, FFmpeg, or your NLE's export panel)"),
                            ("icloud", "Sync libraries or manage cloud storage"),
                        ])
                    }

                    HelpSection(icon: "flag.checkered", title: "Getting started") {
                        HelpStep(number: "1", heading: "Connect to a server or use Local Library") {
                            Text("On first launch, choose **Local Library** to browse videos already on your device, or tap **Connect to Server** to pair with a ReelVault daemon running on your Mac or PC over the local network.")
                        }
                        HelpStep(number: "2", heading: "Add a library location (server mode)") {
                            Text("On the connected server, add one or more source folders. The grid fills as files are indexed in the background.")
                        }
                        HelpStep(number: "3", heading: "Browse and inspect") {
                            Text("Tap any thumbnail to select it. The **inspector** slides in from the trailing edge on iPad (or push to Detail on iPhone) showing codec, resolution, FPS, bitrate, GPS, camera model, notes, tags, and more.")
                        }
                    }

                    HelpSection(icon: "rectangle.3.group", title: "Views") {
                        HelpTable(rows: [
                            ("Grid",   "Adaptive thumbnail grid. Drag the slider in the top bar to resize cards."),
                            ("List",   "Horizontal rows: thumbnail left, metadata columns right."),
                            ("Detail", "Full-screen video player and inspector."),
                            ("Map",    "Geotagged clips on a world map. Tap a pin to filter to that location."),
                        ])
                        HelpParagraph("Switch views from the **Library sidebar** (tap the sidebar button, or swipe in from the left on iPad).")
                        HelpParagraph("Each grid card carries up to four **info slots** along its top edge — configure them via the stat-slots button in the top bar. Missing or offline files are flagged at a glance.")
                    }

                    HelpSection(icon: "cursorarrow.click.2", title: "Selecting clips") {
                        HelpTable(rows: [
                            ("Tap",          "Select one clip"),
                            ("Long-press",   "Open the context menu (batch actions, share, and more)"),
                        ])
                        HelpParagraph("On iPad with a connected keyboard:")
                        HelpTable(rows: [
                            ("Shift-tap",    "Extend the selection range"),
                            ("⌘-tap",        "Toggle individual clips in or out of the selection"),
                            ("⌘A",           "Select all currently-visible clips"),
                            ("⌘D",           "Clear the selection"),
                            ("← ↑ → ↓",     "Navigate the grid one card at a time"),
                        ])
                    }

                    HelpSection(icon: "square.stack.3d.up", title: "Stacks") {
                        HelpParagraph("Stacks let a single card represent a group of related clips — useful for 4K originals paired with 1080p proxies, or multiple takes from the same setup.")
                        HelpBullets([
                            ("plus.square.on.square", "Long-press → **Stack** to create a stack from the selected clips"),
                            ("rectangle.expand.vertical", "Tap the **N×** badge on a stack card to expand or collapse it inline"),
                            ("square.and.arrow.up", "Long-press a stack member → **Remove from stack** pulls just that clip out"),
                            ("rectangle.stack.badge.minus", "Long-press → **Unstack** disbands the entire group"),
                            ("wand.and.stars", "ReelVault **auto-stacks** matching variants during import"),
                        ])
                    }

                    HelpSection(icon: "rectangle.on.rectangle.slash", title: "Proxies") {
                        HelpParagraph("""
                        A proxy is a lightweight, lower-resolution stand-in for a heavy clip — a small file \
                        ReelVault can play and scrub smoothly while the original (8K, ProRes, RAW, …) stays \
                        untouched on disk.
                        """)
                        HelpBullets([
                            ("p.square", "The **P×N** badge on a card shows how many proxies are linked to that clip."),
                            ("exclamationmark.triangle", "Clips above the inline-playback ceiling show a warning badge; inline playback uses the smallest proxy automatically."),
                            ("slider.horizontal.3", "In **Detail** view the inspector lists every linked proxy — tap one to play it instead of the original."),
                            ("wand.and.stars", "**Auto-detection** links proxies that already exist on the server — ReelVault matches them by folder, frame count, thumbnail content, and filename."),
                        ])
                    }

                    HelpSection(icon: "star.leadinghalf.filled", title: "Ratings & color labels") {
                        HelpParagraph("Mark up your footage Lightroom-style. Ratings and color labels are stored in the catalog only — your files are never touched — and you can filter by either.")
                        HelpBullets([
                            ("star", "Long-press a clip → **Set Rating** to apply 0–5 stars (0 clears)"),
                            ("paintpalette", "Long-press → **Set Color Label** to flag clips red, yellow, green, blue, or purple"),
                            ("line.3.horizontal.decrease.circle", "Filter the grid to a minimum rating or a specific color via the filter sheet (funnel icon)"),
                        ])
                    }

                    HelpSection(icon: "magnifyingglass", title: "Search & filters") {
                        HelpBullets([
                            ("text.cursor",         "**Search bar**: live search across filename, notes, and tags"),
                            ("chevron.down.circle", "**Filter sheet** (funnel icon): stack rating, color, and attribute filters; tap **Clear** to reset all"),
                            ("slider.horizontal.3", "**Metadata filter**: filter on any metadata field — camera, lens, codec, year, and more"),
                            ("map",                 "**Map view**: tap a pin to filter to that GPS radius"),
                            ("sidebar.left",        "**Library sidebar**: tap a folder to limit the grid to that location"),
                        ])
                    }

                    HelpSection(icon: "mappin.and.ellipse", title: "Locations & the map") {
                        HelpParagraph("Clips that carry GPS metadata appear automatically on the **Map** view. You can also place, change, or name locations yourself.")
                        HelpBullets([
                            ("mappin", "Long-press clips → **Add Location…** or **Update Location…** to drop or move them on a pick-a-spot map; **Remove Location** clears it"),
                            ("pencil", "Long-press a map pin to **name** it — that name appears wherever the clip's location is shown"),
                            ("line.3.horizontal.decrease.circle", "Tap a pin, or use the location filter, to narrow the grid to everything shot within a radius of that spot"),
                        ])
                    }

                    HelpSection(icon: "tag", title: "Tags & collections") {
                        HelpParagraph("Tags and collections are catalog-only ways to organise clips — neither is **ever** written into the video file.")
                        HelpBullets([
                            ("tag", "**Tags** are free-form labels. Add or remove them in the inspector; filter by **Keyword** in the filter sheet"),
                            ("folder", "**Collections** are named sets of clips. Add via long-press → **Add to Collection**; browse in the sidebar"),
                            ("gearshape.2", "**Smart collections** fill themselves from a rule — camera, codec, year, rating, or color — and stay current automatically"),
                        ])
                    }

                    HelpSection(icon: "square.and.arrow.up", title: "External editors") {
                        HelpBullets([
                            ("square.and.arrow.up", "**Share** one or more clips via the iOS share sheet — hand them off to any app that accepts video files"),
                            ("arrow.up.forward.app", "**Upload** (Local Library mode) — send on-device clips to the paired server catalog"),
                        ])
                    }

                    HelpSection(icon: "plus.circle", title: "Library management") {
                        HelpBullets([
                            ("folder.badge.plus",   "Add as many source folders as you like (server mode) — each appears as a row in the sidebar"),
                            ("arrow.clockwise",     "Tap the rescan button on any folder row to refresh it after moving files"),
                            ("trash",               "Swipe left on a library row to remove it — your video files are not deleted"),
                        ])
                    }

                    HelpSection(icon: "eye", title: "Live updates") {
                        HelpParagraph("ReelVault can watch your library folders for new footage using the operating system's file-change notifications. Toggle this in **Settings → Live Updates** (server mode).")
                        HelpParagraph("When on, newly-added files appear in the grid within a few seconds of landing on disk. A short settle delay prevents half-written files from being indexed.")
                    }

                    HelpSection(icon: "keyboard", title: "Keyboard shortcuts (iPad)") {
                        HelpTable(rows: [
                            ("G",       "Grid view"),
                            ("L",       "List view"),
                            ("D",       "Detail view"),
                            ("M",       "Map view"),
                            ("Space",   "Play / pause selected clip"),
                            ("0–5",     "Rate the selected clips (0 clears)"),
                            ("6–9",     "Color-label the selection (red / yellow / green / blue)"),
                            ("`",       "Clear the color label"),
                            ("⌘G",      "Stack selected clips"),
                            ("⌘A",      "Select all visible clips"),
                            ("⌘D",      "Deselect all"),
                            ("← ↑ → ↓", "Navigate the grid"),
                            ("Escape",  "Clear search field focus"),
                        ])
                    }

                    HelpSection(icon: "lightbulb", title: "Tips & tricks") {
                        HelpBullets([
                            ("arrow.up.left.and.arrow.down.right", "The **thumbnail slider** in the top bar rescales in real time — find the density that suits your display"),
                            ("clock.arrow.circlepath",             "Rescan a folder from the sidebar without re-adding it — tap the **↺** button on the folder row"),
                            ("rectangle.compress.vertical",        "On iPad, swipe left on the sidebar to collapse it and give the grid maximum screen space"),
                            ("sparkles",                           "Auto-stacking during import groups 4K + 1080p variants automatically — look for the **N×** badge"),
                            ("rectangle.on.rectangle.slash",       "Footage too big to preview? Use a proxy (server mode) — ReelVault links them for you if placed in a **Proxies** subfolder"),
                        ])
                    }

                    // Bottom padding
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            .navigationTitle("ReelVault Help")
            .navigationBarTitleDisplayMode(.inline)
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
            // Section header — tap to collapse/expand
            Button {
                withAnimation(.easeInOut(duration: 0.18)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: icon)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 20)
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Divider()

            if isExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    content()
                }
                .padding(.top, 12)
                .padding(.bottom, 16)
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
        VStack(alignment: .leading, spacing: 7) {
            ForEach(items.indices, id: \.self) { i in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: items[i].0)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 18, height: 20, alignment: .center)
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

/// A two-column key/value table (shortcuts and view descriptions).
private struct HelpTable: View {
    let rows: [(String, String)]

    var body: some View {
        Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 6) {
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

/// A numbered step with a heading and a Text body.
private struct HelpStep: View {
    let number: String
    let heading: String
    let textContent: () -> Text

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Color.accentColor))
            VStack(alignment: .leading, spacing: 4) {
                Text(heading).font(.callout.bold())
                textContent().font(.callout)
            }
        }
    }
}
