// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "VideoRoom",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "VideoRoom", targets: ["VideoRoom"])
    ],
    dependencies: [
        // Note: Proto generation requires compatible versions of protoc and plugins
        // For now, we use stubbed gRPC methods. Full gRPC integration requires:
        // - Matching protoc-gen-grpc-swift version
        // - Compatible grpc-swift Swift package version
    ],
    targets: [
        .executableTarget(
            name: "VideoRoom",
            dependencies: [
                .product(name: "GRPC", package: "grpc-swift"),
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            path: "VideoRoom",
            resources: [],
            swiftSettings: [
                .unsafeFlags(["-suppress-warnings"], .when(configuration: .release))
            ]
        )
    ]
)
