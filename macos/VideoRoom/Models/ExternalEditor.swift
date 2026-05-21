// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

import Foundation

/// License tier displayed next to each editor in the configuration UI.
enum EditorLicense: String {
    case freeOpenSource
    case paid
    case freeAndPaid

    /// Human-readable label shown in the preferences dialog.
    var display: String {
        switch self {
        case .freeOpenSource: return "Free / Open Source"
        case .paid:           return "Paid"
        case .freeAndPaid:    return "Free + Paid"
        }
    }
}

/// Host OS we're currently running on. The macOS client only ever runs on
/// macOS, but we keep this for API parity with the Kotlin catalog and to make
/// future cross-platform refactors easy.
enum HostPlatform: String {
    case macOS, windows, linux, unknown

    /// Always `.macOS` from this client.
    static var current: HostPlatform { .macOS }

    var displayName: String {
        switch self {
        case .macOS:    return "macOS"
        case .windows:  return "Windows"
        case .linux:    return "Linux"
        case .unknown:  return "this platform"
        }
    }
}

/// A supported external video editor.
///
/// VideoRoom doesn't bundle these apps — it just knows how to find them on
/// disk and how to launch them with one or more video files. The same catalog
/// ships in the Kotlin desktop client too; keep both in sync.
///
/// - `id`: stable identifier (e.g. `"davinci-resolve"`). Used as the
///   `UserDefaults` key for this editor's config; don't rename without a
///   migration.
/// - `name`: human-readable display name.
/// - `license`: free / paid / mixed.
/// - `homepage`: vendor URL the user can visit to download the app.
/// - `platforms`: operating systems this app ships on (for cross-platform
///   parity with the shared catalog spec).
/// - `defaultPaths`: best-guess executable locations per platform. The first
///   one that exists wins. On macOS these point at `.app` bundles, opened via
///   `NSWorkspace.open(_:withApplicationAt:configuration:completionHandler:)`.
/// - `supportsFileArgs`: if `true`, VideoRoom passes the video file paths
///   alongside the app launch. Many pro NLEs are project-based and ignore
///   command-line args, so we just open the app and let the user import.
/// - `notes`: short caveat shown in the preferences dialog.
struct ExternalEditor: Identifiable, Hashable {
    let id: String
    let name: String
    let license: EditorLicense
    let homepage: String
    let platforms: Set<HostPlatform>
    let defaultPaths: [HostPlatform: [String]]
    let supportsFileArgs: Bool
    let notes: String

    init(
        id: String,
        name: String,
        license: EditorLicense,
        homepage: String,
        platforms: Set<HostPlatform>,
        defaultPaths: [HostPlatform: [String]],
        supportsFileArgs: Bool = false,
        notes: String = ""
    ) {
        self.id = id
        self.name = name
        self.license = license
        self.homepage = homepage
        self.platforms = platforms
        self.defaultPaths = defaultPaths
        self.supportsFileArgs = supportsFileArgs
        self.notes = notes
    }

    static func == (lhs: ExternalEditor, rhs: ExternalEditor) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// Hard-coded catalog of every supported external video editor. The macOS
/// client only ever sees the macOS-shipped entries (see
/// `editorsForCurrentPlatform()`), but we keep cross-platform metadata so the
/// catalog stays identical to the Kotlin client.
enum EditorCatalog {
    static let all: [ExternalEditor] = [
        ExternalEditor(
            id: "davinci-resolve",
            name: "DaVinci Resolve",
            license: .freeAndPaid,
            homepage: "https://www.blackmagicdesign.com/products/davinciresolve",
            platforms: [.macOS, .windows, .linux],
            defaultPaths: [
                // The free Resolve installer drops the bundle in
                // `/Applications/DaVinci Resolve/` (subdirectory!); the
                // paid Studio variant uses a sibling subdirectory and
                // bundle name. We check both, plus the legacy
                // flat-Applications install some users have kept around.
                .macOS: [
                    "/Applications/DaVinci Resolve/DaVinci Resolve.app",
                    "/Applications/DaVinci Resolve Studio/DaVinci Resolve Studio.app",
                    "/Applications/DaVinci Resolve.app",
                ],
                .windows: [
                    "C:\\Program Files\\Blackmagic Design\\DaVinci Resolve\\Resolve.exe"
                ],
                .linux: [
                    "/opt/resolve/bin/resolve",
                    "/usr/bin/resolve",
                ],
            ],
            supportsFileArgs: false,
            notes: "Project-based — VideoRoom launches the app; import the file from the Media pool inside Resolve."
        ),
        ExternalEditor(
            id: "kdenlive",
            name: "Kdenlive",
            license: .freeOpenSource,
            homepage: "https://kdenlive.org",
            platforms: [.macOS, .windows, .linux],
            defaultPaths: [
                // Kdenlive's macOS bundle shipped as `kdenlive.app` in
                // older releases and `Kdenlive.app` in newer ones (KDE's
                // naming standardised on capitalised). Probe both.
                .macOS: [
                    "/Applications/Kdenlive.app",
                    "/Applications/kdenlive.app",
                ],
                .windows: ["C:\\Program Files\\kdenlive\\bin\\kdenlive.exe"],
                .linux: [
                    "/usr/bin/kdenlive",
                    "/var/lib/flatpak/exports/bin/org.kde.kdenlive",
                    "/snap/bin/kdenlive",
                ],
            ],
            supportsFileArgs: false,
            notes: "Expects a `.kdenlive` project; passing a raw video on the CLI is unreliable, so VideoRoom just launches the app."
        ),
        ExternalEditor(
            id: "shotcut",
            name: "Shotcut",
            license: .freeOpenSource,
            homepage: "https://www.shotcut.org",
            platforms: [.macOS, .windows, .linux],
            defaultPaths: [
                .macOS: ["/Applications/Shotcut.app"],
                .windows: ["C:\\Program Files\\Shotcut\\shotcut.exe"],
                .linux: [
                    "/usr/bin/shotcut",
                    "/var/lib/flatpak/exports/bin/org.shotcut.Shotcut",
                    "/snap/bin/shotcut",
                ],
            ],
            // Shotcut's CLI accepts one or more media files directly.
            supportsFileArgs: true,
            notes: "Opens video files passed on the command line directly."
        ),
        ExternalEditor(
            id: "lightworks",
            name: "Lightworks",
            license: .freeAndPaid,
            homepage: "https://lwks.com",
            platforms: [.macOS, .windows, .linux],
            defaultPaths: [
                .macOS: ["/Applications/Lightworks.app"],
                .windows: [
                    "C:\\Program Files\\Lightworks\\lightworks.exe",
                    "C:\\Program Files (x86)\\Lightworks\\lightworks.exe",
                ],
                .linux: ["/usr/bin/lightworks"],
            ],
            supportsFileArgs: false,
            notes: "Project-based. Requires sign-in. VideoRoom just launches the app."
        ),
        ExternalEditor(
            id: "openshot",
            name: "OpenShot",
            license: .freeOpenSource,
            homepage: "https://www.openshot.org",
            platforms: [.macOS, .windows, .linux],
            defaultPaths: [
                .macOS: ["/Applications/OpenShot Video Editor.app"],
                .windows: ["C:\\Program Files\\OpenShot Video Editor\\openshot-qt.exe"],
                .linux: [
                    "/usr/bin/openshot-qt",
                    "/usr/local/bin/openshot-qt",
                ],
            ],
            supportsFileArgs: false,
            notes: "Known to reject arbitrary CLI paths — VideoRoom launches the app and you drag the file into the timeline."
        ),
        ExternalEditor(
            id: "blender",
            name: "Blender",
            license: .freeOpenSource,
            homepage: "https://www.blender.org",
            platforms: [.macOS, .windows, .linux],
            defaultPaths: [
                .macOS: ["/Applications/Blender.app"],
                .windows: [
                    "C:\\Program Files\\Blender Foundation\\Blender 4.2\\blender.exe",
                    "C:\\Program Files\\Blender Foundation\\Blender 4.1\\blender.exe",
                    "C:\\Program Files\\Blender Foundation\\Blender 4.0\\blender.exe",
                    "C:\\Program Files\\Blender Foundation\\Blender 3.6\\blender.exe",
                ],
                .linux: [
                    "/usr/bin/blender",
                    "/var/lib/flatpak/exports/bin/org.blender.Blender",
                    "/snap/bin/blender",
                ],
            ],
            supportsFileArgs: false,
            notes: "Blender's CLI accepts `.blend` files, not raw video. VideoRoom launches Blender; import the video into the VSE manually."
        ),
        ExternalEditor(
            id: "premiere-pro",
            name: "Adobe Premiere Pro",
            license: .paid,
            homepage: "https://www.adobe.com/products/premiere.html",
            platforms: [.macOS, .windows],
            defaultPaths: [
                // Adobe installs each Creative Cloud release in its own
                // versioned folder — newest first so we find the user's
                // current install before stumbling onto an older copy
                // they kept around. 2025 + Beta added so people on the
                // current shipping version + early-access channels are
                // detected automatically.
                .macOS: [
                    "/Applications/Adobe Premiere Pro 2025/Adobe Premiere Pro 2025.app",
                    "/Applications/Adobe Premiere Pro (Beta)/Adobe Premiere Pro (Beta).app",
                    "/Applications/Adobe Premiere Pro 2024/Adobe Premiere Pro 2024.app",
                    "/Applications/Adobe Premiere Pro 2023/Adobe Premiere Pro 2023.app",
                    "/Applications/Adobe Premiere Pro 2022/Adobe Premiere Pro 2022.app",
                ],
                .windows: [
                    "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2025\\Adobe Premiere Pro.exe",
                    "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2024\\Adobe Premiere Pro.exe",
                    "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2023\\Adobe Premiere Pro.exe",
                    "C:\\Program Files\\Adobe\\Adobe Premiere Pro 2022\\Adobe Premiere Pro.exe",
                ],
            ],
            // Premiere accepts a media file via `open -a` only when it's
            // already running and a project is open; with no project,
            // the file is silently dropped on the Home screen. Cold
            // launches take 30–90 s on most machines. Don't pass the
            // file — let the user import via Media Browser once they're
            // in the project they actually want it in.
            supportsFileArgs: false,
            notes: "Project-based; Creative Cloud subscription required. Cold launch can take a minute or more — VideoRoom just opens the app, import the file via the Media Browser inside Premiere."
        ),
        ExternalEditor(
            id: "final-cut-pro",
            name: "Final Cut Pro",
            license: .paid,
            homepage: "https://www.apple.com/final-cut-pro/",
            platforms: [.macOS],
            defaultPaths: [
                .macOS: ["/Applications/Final Cut Pro.app"],
            ],
            // FCP on macOS can import media when passed via `open -a`.
            supportsFileArgs: true,
            notes: "Final Cut imports media when opened via `open -a` on macOS, though the sandbox may prompt for permission."
        ),
        ExternalEditor(
            id: "capcut",
            name: "CapCut",
            license: .freeAndPaid,
            homepage: "https://www.capcut.com",
            platforms: [.macOS, .windows],
            defaultPaths: [
                .macOS: ["/Applications/CapCut.app"],
                .windows: ["C:\\Program Files\\CapCut\\CapCut.exe"],
            ],
            supportsFileArgs: false,
            notes: "Project-based; sign-in required for most features."
        ),
        ExternalEditor(
            id: "filmora",
            name: "Filmora",
            license: .paid,
            homepage: "https://filmora.wondershare.com",
            platforms: [.macOS, .windows],
            defaultPaths: [
                // Wondershare publishes Filmora with the version in the
                // bundle name; newer first so we pick the current install.
                // The unversioned `Wondershare Filmora.app` is what newer
                // updaters use; the numbered variants are legacy.
                .macOS: [
                    "/Applications/Wondershare Filmora.app",
                    "/Applications/Wondershare Filmora 14.app",
                    "/Applications/Wondershare Filmora 13.app",
                    "/Applications/Wondershare Filmora 12.app",
                ],
                .windows: [
                    "C:\\Program Files\\Wondershare\\Wondershare Filmora 14\\Filmora.exe",
                    "C:\\Program Files\\Wondershare\\Wondershare Filmora 13\\Filmora.exe",
                    "C:\\Program Files\\Wondershare\\Wondershare Filmora 12\\Filmora.exe",
                ],
            ],
            supportsFileArgs: false,
            notes: "Watermarks exports on the free tier. VideoRoom launches the app; import manually."
        ),
    ]

    /// Editors filtered to those that ship on the current host.
    static var forCurrentPlatform: [ExternalEditor] {
        let host = HostPlatform.current
        return all.filter { $0.platforms.contains(host) }
    }
}
