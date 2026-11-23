# 0999: grpc-swift successor migration (done in the #922 retry)

**Status: migrated.** The first attempt stopped because its sandbox was offline. The retry had network and landed the migration (below). The sections that follow record the evidence and the original block.

**Evidence: the real kit build log** (`/tmp/rvkit.log`, a `swift build` of kit on the
pinned versions grpc-swift 2.2.3, nio-transport 1.2.3, protobuf 1.3.1, built in
worktree 915; 87 grpc deprecation warnings in that log). Every deprecation
is the single message `'X' is deprecated: See https://forums.swift.org/t/80177
[#DeprecatedDeclaration]`. It names no replacement symbol; the thread announces the
move to the new `grpc-swift-2` package. By symbol (that log):

| symbol | count |
|---|---|
| `Metadata` | 68 |
| `HTTP2ClientTransport` | 12 |
| `GRPCClient` | 4 |
| `ServerTransport` | 1 |
| `RegistrableRPCService` | 1 |
| `ClientTransport` | 1 |

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

**Migration (retry).** Package.swift now uses grpc-swift-2 from 2.4.0 (resolved 2.4.4),
grpc-swift-protobuf exact 2.4.0, grpc-swift-nio-transport from 2.8.0 (resolved 2.13.1).
Stubs were regenerated with `kit/regen-proto.sh` using the new `protoc-gen-grpc-swift-2`
plugin (the 2.x plugin has a new name, so the script now calls it); output has
`import GRPCCore`. The module and type names are unchanged, but the new transport deprecates the target
initialisers: `'ipv4(host:port:)' is deprecated: renamed to 'ipv4(address:port:)'` and
`'ipv6(host:port:)' is deprecated: renamed to 'ipv6(address:port:)'` (6 call sites in
VideoRepository, SyncManager, SyncProfileStore). Labels were renamed to `address:`;
 TLS pinning, bearer
auth, host-type target choice and the force-cancel disconnect path are untouched.
Result: kit `swift build` has 0 grpc deprecation warnings (was 87).

**Why it first stopped (historical).** `grpc-swift-2` was not in any local cache and the
first environment had no network (`swift build` failed: `transport 'https' not allowed`),
so this was an environment block, not a missing release.
