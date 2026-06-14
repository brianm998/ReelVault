#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 ReelVault Contributors
#
# Assemble ReelVaultCore.xcframework from the Rust core staticlib for iOS device
# (arm64) + simulator (arm64 + x86_64). See docs/IOS_CORE_PORT.md §8.1.
#
# Requires the iOS Rust targets:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
#
# Env:
#   PROFILE=debug|release   (default: debug — faster; use release to ship)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CORE_DIR="$(cd "$HERE/../core" && pwd)"
OUT_DIR="$HERE/Frameworks"
HEADERS="$CORE_DIR/include"
PROFILE="${PROFILE:-debug}"
LIB=libreelvault_core.a

build_flag=""
[ "$PROFILE" = "release" ] && build_flag="--release"

echo "Building core staticlib ($PROFILE) for iOS device + simulator…"
(
  cd "$CORE_DIR"
  cargo build --lib $build_flag --target aarch64-apple-ios
  cargo build --lib $build_flag --target aarch64-apple-ios-sim
  cargo build --lib $build_flag --target x86_64-apple-ios
)

TARGETS="$CORE_DIR/target"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# A single XCFramework slice can't hold two libs for the same platform, so the
# two simulator arches are merged into one universal static lib first.
echo "Merging simulator arches (arm64 + x86_64)…"
lipo -create \
  "$TARGETS/aarch64-apple-ios-sim/$PROFILE/$LIB" \
  "$TARGETS/x86_64-apple-ios/$PROFILE/$LIB" \
  -output "$WORK/$LIB"

rm -rf "$OUT_DIR/ReelVaultCore.xcframework"
mkdir -p "$OUT_DIR"
echo "Creating ReelVaultCore.xcframework…"
xcodebuild -create-xcframework \
  -library "$TARGETS/aarch64-apple-ios/$PROFILE/$LIB" -headers "$HEADERS" \
  -library "$WORK/$LIB" -headers "$HEADERS" \
  -output "$OUT_DIR/ReelVaultCore.xcframework"

echo "Wrote $OUT_DIR/ReelVaultCore.xcframework ($PROFILE)"
