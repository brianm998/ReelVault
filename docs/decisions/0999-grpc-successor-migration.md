# 0999: grpc-swift successor migration is blocked until the successor package can be fetched

**Evidence: the real kit build log** (`/tmp/rvkit.log`, a `swift build` of kit on the
pinned versions grpc-swift 2.2.3, nio-transport 1.2.3, protobuf 1.3.1; 174 distinct
deprecation lines in that log, ~930 across the full per-target build). Every deprecation
is the single message `'X' is deprecated: See https://forums.swift.org/t/80177
[#DeprecatedDeclaration]`. It names no replacement symbol; the thread announces the
move to the new `grpc-swift-2` package. By symbol (that log):

| symbol | count |
|---|---|
| `Metadata` | 136 |
| `HTTP2ClientTransport` | 24 |
| `GRPCClient` | 8 |
| `ServerTransport` | 2 |
| `RegistrableRPCService` | 2 |
| `ClientTransport` | 2 |

By file: Generated/reelvault.grpc.swift 73, Sync/SyncManager.swift 7,
Networking/VideoRepository.swift 6, Networking/PinnedTLS.swift 1. In the pinned sources
there are 16 `@available(*, deprecated, message: "See .../80177")` declarations (9 files in
grpc-swift, 2 in nio-transport, 2 in protobuf) plus 2 unrelated "Use ServiceName/MethodName".
The separate `swift build` failure `transport 'https' not allowed` comes from this
worktree's offline sandbox, not from the log above.

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
auth and the disconnect path. A successor release does exist, so this is an environment block, not a no-successor stop: `swift package resolve` with the `grpc-swift-2` URL fails the same way, and no copy of its sources is on disk. Nothing was changed in Package.swift.

**Next step.** Run on a machine with network: change Package.swift to grpc-swift-2 plus
nio-transport 2.x and protobuf 2.x, `swift package resolve`, `kit/regen-proto.sh` with
the matching plugin, fix call sites in Networking/ and Sync/, and verify builds.
