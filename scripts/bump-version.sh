#!/usr/bin/env bash
# Bump the version across all ReelVault components.
#
# Usage: scripts/bump-version.sh [new-version]
#   If called with no arguments, auto-increments the patch version.
#   If called with an argument (e.g. 0.2.0), uses that exact version.
#   To increment major or minor, you must specify the version explicitly.
#
# Updates in one shot:
#   VERSION                        — root source of truth
#   core/Cargo.toml                — Rust crate version
#   macos/ReelVault/Info.plist     — CFBundleShortVersionString
#   ios/project.yml                — MARKETING_VERSION
#
# Kotlin AppVersion.kt is generated at build time from VERSION (no edit needed).
# Android versionCode is auto-computed from VERSION as MAJOR*10000+MINOR*100+PATCH
#   (e.g. 1.2.3 → 10203); no manual update is needed for Android.
# Rust code uses env!("CARGO_PKG_VERSION") which Cargo sets from Cargo.toml.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

NEW_VERSION="${1:-}"
if [[ -z "$NEW_VERSION" ]]; then
    # Read current VERSION and auto-increment patch
    CURRENT_VERSION=$(cat "$ROOT/VERSION")
    if ! [[ "$CURRENT_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-[A-Za-z0-9.]+)?$ ]]; then
        echo "Error: current VERSION file has invalid format: $CURRENT_VERSION" >&2
        exit 1
    fi

    MAJOR="${BASH_REMATCH[1]}"
    MINOR="${BASH_REMATCH[2]}"
    PATCH="${BASH_REMATCH[3]}"
    PRERELEASE="${BASH_REMATCH[4]:-}"

    # Increment patch, drop any pre-release suffix
    PATCH=$((PATCH + 1))
    NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    echo "Auto-bumping patch version: $CURRENT_VERSION → $NEW_VERSION"
fi

if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
    echo "Error: version must be X.Y.Z or X.Y.Z-pre.release" >&2
    exit 1
fi

# 1. Root VERSION file
printf '%s\n' "$NEW_VERSION" > "$ROOT/VERSION"

# 2. Rust core Cargo.toml — only the [package] version line (^version = "..."),
#    not dependency versions inside { version = "..." } inline tables.
perl -i -pe "s|^version = \"[^\"]+\"|version = \"$NEW_VERSION\"|" \
    "$ROOT/core/Cargo.toml"

# 3. macOS app Info.plist — replace the <string> on the line after CFBundleShortVersionString.
#    $a is set on the key line, consumed on the value line; avoids capture-group pitfalls.
perl -i -pe \
    'our $a; if($a){s|<string>[^<]+</string>|<string>'"$NEW_VERSION"'</string>|;$a=0}$a=1 if /CFBundleShortVersionString/' \
    "$ROOT/macos/ReelVault/Info.plist"

# 4. iOS XcodeGen project.yml — MARKETING_VERSION setting.
perl -i -pe "s|MARKETING_VERSION: \"[^\"]+\"|MARKETING_VERSION: \"$NEW_VERSION\"|" \
    "$ROOT/ios/project.yml"

echo "Bumped to $NEW_VERSION — updated:"
echo "  VERSION"
echo "  core/Cargo.toml"
echo "  macos/ReelVault/Info.plist"
echo "  ios/project.yml"
echo ""
echo "Next: commit all changed files, then tag and push:"
echo "  git add VERSION core/Cargo.toml macos/ReelVault/Info.plist ios/project.yml"
echo "  git commit -m \"chore: bump version to $NEW_VERSION\""
echo "  git tag v$NEW_VERSION && git push --tags"
