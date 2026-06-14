#!/usr/bin/env bash
# Regenerate the Swift gRPC + protobuf stubs for ReelVaultKit from the core proto.
#
# The generated types MUST be public — ReelVaultKit is a library module, so both
# the macOS app and the iOS app import them across the module boundary. That is
# why both generators are invoked with `Visibility=Public`.
#
# Requirements:
#   - protoc
#   - protoc-gen-swift           (SwiftProtobuf plugin)
#   - protoc-gen-grpc-swift      (grpc-swift **v2** plugin — must match the pinned
#                                  grpc-swift-protobuf 1.3.1 in Package.swift; a v1
#                                  plugin emits `import GRPC` which will not compile)
#
# Verify the grpc plugin is v2 by checking the output imports `GRPCCore`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROTO_DIR="$HERE/../core/proto"
OUT_DIR="$HERE/Sources/ReelVaultKit/Generated"

mkdir -p "$OUT_DIR"

protoc \
  --swift_out="$OUT_DIR" --swift_opt=Visibility=Public \
  --grpc-swift_out="$OUT_DIR" --grpc-swift_opt=Visibility=Public \
  -I "$PROTO_DIR" \
  "$PROTO_DIR/reelvault.proto"

# Sanity check: the gRPC stubs must be the v2 flavor.
if ! grep -q "^import GRPCCore" "$OUT_DIR/reelvault.grpc.swift"; then
  echo "ERROR: generated reelvault.grpc.swift is not grpc-swift v2 (no 'import GRPCCore')." >&2
  echo "       Your protoc-gen-grpc-swift is the wrong version. See Package.swift pin." >&2
  exit 1
fi

echo "Regenerated public stubs into $OUT_DIR"
