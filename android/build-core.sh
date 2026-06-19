#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 ReelVault Contributors
#
# Build the Rust core as libreelvault_core.so for each Android ABI and drop the
# results into android/src/main/jniLibs/<abi>/ so Gradle bundles them into the
# APK/AAB. The Kotlin app loads it via System.loadLibrary("reelvault_core") and
# boots the embedded core on a loopback gRPC port (see core/src/android.rs).
#
# This mirrors ios/build-core-xcframework.sh. The .so files are gitignored build
# artifacts (like the iOS xcframework) — CI and fresh checkouts must run this
# before assembling the app, or the link/load of the native symbols will fail.
#
# Requirements (one-time):
#   rustup target add aarch64-linux-android armv7-linux-androideabi \
#       x86_64-linux-android i686-linux-android
#   cargo install cargo-ndk
#   an Android NDK (set ANDROID_NDK_HOME, or have $ANDROID_HOME/ndk/<version>)
#
# Env overrides:
#   PROFILE=release|debug      (default: debug)
#   ABIS="arm64-v8a x86_64"    (default: all four — override to just the emulator
#                               ABI for a fast local build, e.g. ABIS=x86_64)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROFILE="${PROFILE:-debug}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64 x86}"
OUT="$SCRIPT_DIR/src/main/jniLibs"

# Locate an NDK if ANDROID_NDK_HOME isn't already set (cargo-ndk also auto-detects
# from $ANDROID_HOME/ndk, but be explicit so the chosen version is logged).
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "$sdk/ndk" ]]; then
    ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/* | sort -V | tail -1)"
    export ANDROID_NDK_HOME
  fi
fi
echo "Using NDK: ${ANDROID_NDK_HOME:-<cargo-ndk auto-detect>}"
echo "Profile:   $PROFILE"
echo "ABIs:      $ABIS"

build_flag=()
[[ "$PROFILE" == "release" ]] && build_flag=(--release)

targets=()
for abi in $ABIS; do targets+=( -t "$abi" ); done

cd "$REPO_ROOT/core"
# minSdk for the Android app is 26 (see android/build.gradle.kts); build the core
# against the matching platform API level.
# `${arr[@]+"${arr[@]}"}` expands to nothing when the array is empty without
# tripping `set -u` (matters on macOS's bash 3.2).
cargo ndk "${targets[@]}" --platform 26 -o "$OUT" build --lib ${build_flag[@]+"${build_flag[@]}"}

echo "Done. Produced:"
find "$OUT" -name 'libreelvault_core.so' -exec ls -la {} \;
