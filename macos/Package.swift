// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "ReelVault",
    platforms: [
        .macOS(.v15)
    ],
    products: [
        .executable(name: "ReelVault", targets: ["ReelVault"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
        .package(url: "https://github.com/grpc/grpc-swift.git", from: "2.2.1"),
        .package(url: "https://github.com/grpc/grpc-swift-protobuf.git", from: "1.0.0"),
        .package(url: "https://github.com/grpc/grpc-swift-nio-transport.git", from: "1.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "ReelVault",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                .product(name: "GRPCCore", package: "grpc-swift"),
                .product(name: "GRPCProtobuf", package: "grpc-swift-protobuf"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
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
