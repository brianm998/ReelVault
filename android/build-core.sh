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
if [[ "$PROFILE" == "release" ]]; then
  build_flag=(--release)
  # Keep symbols + line tables in the built .so so we can hand Google Play
  # native debug symbols (so Rust crashes/ANRs symbolicate). This overrides
  # `strip = true` in core/Cargo.toml's [profile.release] for THIS Android build
  # only — we strip the SHIPPED copies ourselves after staging the symbols
  # (below), so the AAB still ships lean, stripped .so.
  export CARGO_PROFILE_RELEASE_STRIP=false
  export CARGO_PROFILE_RELEASE_DEBUG=1
fi

targets=()
for abi in $ABIS; do targets+=( -t "$abi" ); done

cd "$REPO_ROOT/core"
# minSdk for the Android app is 26 (see android/build.gradle.kts); build the core
# against the matching platform API level.
# `${arr[@]+"${arr[@]}"}` expands to nothing when the array is empty without
# tripping `set -u` (matters on macOS's bash 3.2).
cargo ndk "${targets[@]}" --platform 26 -o "$OUT" build --lib ${build_flag[@]+"${build_flag[@]}"}

# For release: stage the unstripped .so as Google Play native debug symbols,
# then strip the copies that ship in the AAB so it stays lean. `--strip-unneeded`
# keeps the exported dynamic symbols (JNI Java_* entry points stay resolvable).
# Debug builds keep their symbols in place and skip this.
if [[ "$PROFILE" == "release" ]]; then
  SYMS_DIR="$SCRIPT_DIR/build/native-debug-symbols"
  rm -rf "$SYMS_DIR"
  strip_bin="$(find "${ANDROID_NDK_HOME:-}" -name 'llvm-strip' -type f 2>/dev/null | head -1)"
  while IFS= read -r so; do
    abi="$(basename "$(dirname "$so")")"
    mkdir -p "$SYMS_DIR/$abi"
    cp "$so" "$SYMS_DIR/$abi/libreelvault_core.so"   # unstripped = symbols for Play
    if [[ -n "$strip_bin" ]]; then
      "$strip_bin" --strip-unneeded "$so"            # shrink the lib shipped in the AAB
    else
      echo "WARNING: llvm-strip not found under ANDROID_NDK_HOME; shipped .so left unstripped." >&2
    fi
  done < <(find "$OUT" -name 'libreelvault_core.so')
  echo "Staged native debug symbols in: $SYMS_DIR"
fi

echo "Done. Produced:"
find "$OUT" -name 'libreelvault_core.so' -exec ls -la {} \;
