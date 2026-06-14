// swift-tools-version:6.0
import PackageDescription

// The macOS app is a thin SwiftUI/AppKit shell over the shared ReelVaultKit
// package (models, view-models, the gRPC client, generated stubs). The gRPC and
// SwiftProtobuf dependencies are pulled in transitively through ReelVaultKit;
// no macOS app source imports them directly.
let package = Package(
    name: "ReelVault",
    platforms: [
        .macOS(.v15)
    ],
    products: [
        .executable(name: "ReelVault", targets: ["ReelVault"])
    ],
    dependencies: [
        .package(path: "../kit"),
    ],
    targets: [
        .executableTarget(
            name: "ReelVault",
            dependencies: [
                .product(name: "ReelVaultKit", package: "kit"),
            ],
            path: "ReelVault",
            exclude: ["Info.plist"],
            resources: [
                // Bundle everything under Resources/ — the .icns drives
                // NSApp.applicationIconImage at launch (required for
                // `swift run`, where macOS otherwise shows the generic
                // "exec" Dock icon), and the AppIcon-titlebar-*.png
                // variants drive the in-app brand mark that tracks the
                // accent-color scheme.
                .process("Resources"),
            ],
            swiftSettings: [
                .unsafeFlags(["-suppress-warnings"], .when(configuration: .release))
            ]
        )
    ]
)
