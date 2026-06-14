// swift-tools-version:6.0
import PackageDescription

// ReelVaultKit — code shared by the SwiftUI clients (macOS app and the iOS app).
// Holds the gRPC client, the data models, the view-models, and (added during the
// iOS work) the discovery / pinned-TLS / streaming / cache / upload primitives.
//
// Platform splits inside the sources use `#if os(macOS)` / `#if canImport(UIKit)`.
// macOS-specific UI (AppKit views, the local daemon launcher, drag-export) stays
// in the macOS app target; iOS-specific UI lives in the iOS app target.
let package = Package(
    name: "ReelVaultKit",
    platforms: [
        // grpc-swift 2.x requires macOS 15 / iOS 18 (its APIs are annotated
        // @available accordingly), so those are the floor for ReelVaultKit.
        .macOS(.v15),
        .iOS(.v18),
    ],
    products: [
        .library(name: "ReelVaultKit", targets: ["ReelVaultKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
        .package(url: "https://github.com/grpc/grpc-swift.git", from: "2.2.1"),
        // Pinned exactly: the generated *.grpc.swift is produced by this plugin
        // version; a mismatch makes the stubs fail to compile (see project memory).
        .package(url: "https://github.com/grpc/grpc-swift-protobuf.git", exact: "1.3.1"),
        .package(url: "https://github.com/grpc/grpc-swift-nio-transport.git", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "ReelVaultKit",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                .product(name: "GRPCCore", package: "grpc-swift"),
                .product(name: "GRPCProtobuf", package: "grpc-swift-protobuf"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
            ]
        ),
        .testTarget(
            name: "ReelVaultKitTests",
            dependencies: ["ReelVaultKit"]
        ),
    ]
)
