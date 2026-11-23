# 0999: grpc-swift successor migration is blocked until the successor package can be fetched

**Evidence (kit build, grpc-swift 2.2.3, nio-transport 1.2.3, protobuf 1.3.1).** Every
deprecation in the pinned sources carries one message, with no replacement symbol named:
`@available(*, deprecated, message: "See https://forums.swift.org/t/80177")`
(`GRPCClient`, `GRPCServer`, `withGRPCClient`, and the nio-transport / protobuf types).

**Where the successor lives.** The local SwiftPM cache of the main checkout
(`kit/.build/repositories`) has newer tags: grpc-swift-nio-transport 2.8.0 and
grpc-swift-protobuf 2.4.0. Their `Package.swift` depends on a new package,
`grpc-swift-2` (`.product(name: "GRPCCore", package: "grpc-swift-2")`), not on
`grpc-swift`. grpc-swift itself has no tag past 2.2.3. So the migration is a package
swap (grpc-swift -> grpc-swift-2, nio-transport 1.x -> 2.x, protobuf 1.x -> 2.x, plus
regenerated stubs), not an in-place API rename.

**Why not landed.** `grpc-swift-2` is not in any local cache or checkout, and this
environment has no network (`swift build` fails: `transport 'https' not allowed`). The
successor's API cannot be read, resolved or compiled, so Package.resolved cannot be
updated, stubs cannot be regenerated with the matching plugin, and the kit, macOS and
iOS builds cannot be verified. Migrating blind would risk breaking TLS pinning, bearer
auth and the disconnect path. Per the plan, the change is not landed as a code change.

**Next step.** Run on a machine with network: change Package.swift to grpc-swift-2 plus
nio-transport 2.x and protobuf 2.x, `swift package resolve`, `kit/regen-proto.sh` with
the matching plugin, fix call sites in Networking/ and Sync/, and verify builds.
