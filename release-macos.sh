#!/usr/bin/env bash
# VideoRoom macOS (SwiftUI) — release build script
#
# Builds a signed (or ad-hoc) .app bundle from the SwiftPM project, embeds
# the videoroom-core daemon, and packages everything in a .dmg for distribution.
#
# Usage:
#   ./release-macos.sh [OPTIONS]
#
# Options:
#   --core-bin PATH        Path to the videoroom-core binary to embed.
#                          Defaults to searching standard build locations.
#   --sign IDENTITY        Developer ID Application certificate CN for
#                          codesigning (e.g. "Developer ID Application: Acme").
#                          Omit for ad-hoc signing (local use only).
#   --notarize             Submit the .dmg to Apple Notary Service after signing.
#                          Requires --sign, APPLE_ID and APPLE_TEAM_ID env vars,
#                          and an app-specific password in the keychain.
#   --version X.Y.Z        Override the bundle version (default: Package.swift).
#   --out DIR              Output directory (default: dist/macos)
#   --help                 Show this message
#
# Requirements:
#   - macOS with Xcode Command Line Tools (swift, codesign, hdiutil)
#   - Run on macOS only — SwiftUI targets macOS exclusively.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MACOS_DIR="${SCRIPT_DIR}/macos"
CORE_DIR="${SCRIPT_DIR}/core"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
CORE_BIN=""
SIGN_IDENTITY=""
NOTARIZE=0
VERSION=""
OUT_DIR="${SCRIPT_DIR}/dist/macos"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --core-bin)  CORE_BIN="$2"; shift 2 ;;
        --sign)      SIGN_IDENTITY="$2"; shift 2 ;;
        --notarize)  NOTARIZE=1; shift ;;
        --version)   VERSION="$2"; shift 2 ;;
        --out)       OUT_DIR="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,/^set -/p' "$0" | head -n 40
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Platform check
# ---------------------------------------------------------------------------
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "Error: release-macos.sh must be run on macOS." >&2
    exit 1
fi

for cmd in swift codesign hdiutil; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "Error: '$cmd' not found. Install Xcode Command Line Tools." >&2
        exit 1
    fi
done

# ---------------------------------------------------------------------------
# Resolve version
# ---------------------------------------------------------------------------
if [[ -z "$VERSION" ]]; then
    # Pull from Package.swift: `let version = "0.1.0"` or fall back to a stub.
    VERSION="$(grep -E 'version\s*=\s*"[0-9]' "${MACOS_DIR}/Package.swift" \
        | head -1 | sed 's/.*"\([0-9][^"]*\)".*/\1/' 2>/dev/null || echo "0.1.0")"
fi
echo "==> VideoRoom macOS v${VERSION}"

# ---------------------------------------------------------------------------
# Locate the videoroom-core binary
# ---------------------------------------------------------------------------
locate_core_bin() {
    local candidates=(
        "${CORE_BIN}"
        "${CORE_DIR}/target/universal-apple-darwin/release/videoroom-core"
        "${CORE_DIR}/target/aarch64-apple-darwin/release/videoroom-core"
        "${CORE_DIR}/target/x86_64-apple-darwin/release/videoroom-core"
        "${CORE_DIR}/target/release/videoroom-core"
        "${SCRIPT_DIR}/dist/core/videoroom-core"
    )
    for c in "${candidates[@]}"; do
        [[ -z "$c" ]] && continue
        if [[ -f "$c" && -x "$c" ]]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

DAEMON_BIN=""
if DAEMON_BIN="$(locate_core_bin 2>/dev/null)"; then
    echo "==> Found daemon binary: ${DAEMON_BIN}"
else
    echo ""
    echo "WARNING: videoroom-core binary not found — the .app will launch without"
    echo "  a bundled daemon.  Run ./release-core.sh first, or pass --core-bin."
    echo ""
fi

# ---------------------------------------------------------------------------
# Build the Swift binary in release mode
# ---------------------------------------------------------------------------
echo "==> Building VideoRoom (release)…"
(cd "$MACOS_DIR" && swift build -c release --product VideoRoom 2>&1)

SWIFT_BIN="${MACOS_DIR}/.build/release/VideoRoom"
if [[ ! -f "$SWIFT_BIN" ]]; then
    echo "Error: swift build succeeded but binary not found at ${SWIFT_BIN}." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Assemble the .app bundle
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"

APP_NAME="VideoRoom"
APP_BUNDLE="${OUT_DIR}/${APP_NAME}.app"

echo "==> Assembling ${APP_BUNDLE}…"
rm -rf "$APP_BUNDLE"
mkdir -p "${APP_BUNDLE}/Contents/MacOS"
mkdir -p "${APP_BUNDLE}/Contents/Resources"

# Main executable
cp "$SWIFT_BIN" "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"
chmod +x "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"

# Bundle the daemon inside Resources/ — ServerLauncher.swift looks there first.
if [[ -n "$DAEMON_BIN" ]]; then
    cp "$DAEMON_BIN" "${APP_BUNDLE}/Contents/Resources/videoroom-core"
    chmod +x "${APP_BUNDLE}/Contents/Resources/videoroom-core"
    echo "  Embedded daemon: Contents/Resources/videoroom-core"
fi

# Copy app icon if it exists
for ICON in \
    "${MACOS_DIR}/VideoRoom/Assets.xcassets/AppIcon.appiconset"/*.icns \
    "${MACOS_DIR}/VideoRoom/Resources/AppIcon.icns"; do
    if [[ -f "$ICON" ]]; then
        cp "$ICON" "${APP_BUNDLE}/Contents/Resources/AppIcon.icns"
        break
    fi
done

# Info.plist
BUNDLE_ID="com.videoroom.app"
cat > "${APP_BUNDLE}/Contents/Info.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key>
    <string>${BUNDLE_ID}</string>
    <key>CFBundleName</key>
    <string>${APP_NAME}</string>
    <key>CFBundleDisplayName</key>
    <string>${APP_NAME}</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleSignature</key>
    <string>????</string>
    <key>LSMinimumSystemVersion</key>
    <string>15.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSPrincipalClass</key>
    <string>NSApplication</string>
</dict>
</plist>
EOF

echo "  Bundle: ${APP_BUNDLE}"

# ---------------------------------------------------------------------------
# Code signing
# ---------------------------------------------------------------------------
sign_app() {
    local bundle="$1"
    local identity="${2:--}"   # "-" means ad-hoc
    local label="ad-hoc"
    [[ "$identity" != "-" ]] && label="Developer ID: ${identity}"

    echo "==> Signing (${label})…"
    # Sign the daemon first, then the outer bundle.
    if [[ -f "${bundle}/Contents/Resources/videoroom-core" ]]; then
        codesign --force --options runtime \
            --sign "$identity" \
            "${bundle}/Contents/Resources/videoroom-core"
    fi
    codesign --force --options runtime \
        --entitlements /dev/null \
        --sign "$identity" \
        --deep \
        "$bundle"
}

if [[ -n "$SIGN_IDENTITY" ]]; then
    sign_app "$APP_BUNDLE" "$SIGN_IDENTITY"
else
    echo "==> Ad-hoc signing (no --sign provided; distributable only within macOS)…"
    sign_app "$APP_BUNDLE" "-"
fi

# ---------------------------------------------------------------------------
# Create .dmg
# ---------------------------------------------------------------------------
DMG_NAME="${APP_NAME}-v${VERSION}-macOS.dmg"
DMG_PATH="${OUT_DIR}/${DMG_NAME}"
STAGING="$(mktemp -d)"

echo "==> Creating ${DMG_NAME}…"
cp -r "$APP_BUNDLE" "${STAGING}/"
ln -s /Applications "${STAGING}/Applications"

hdiutil create \
    -volname "$APP_NAME" \
    -srcfolder "$STAGING" \
    -ov \
    -format UDZO \
    "$DMG_PATH"

rm -rf "$STAGING"
echo "  -> ${DMG_PATH}"

# ---------------------------------------------------------------------------
# Notarization (optional — requires Apple Developer account credentials)
# ---------------------------------------------------------------------------
if [[ "$NOTARIZE" -eq 1 ]]; then
    if [[ -z "$SIGN_IDENTITY" ]]; then
        echo "Error: --notarize requires --sign <Developer ID Identity>." >&2
        exit 1
    fi
    if [[ -z "${APPLE_ID:-}" || -z "${APPLE_TEAM_ID:-}" ]]; then
        echo "Error: set APPLE_ID and APPLE_TEAM_ID env vars for notarization." >&2
        exit 1
    fi
    echo "==> Submitting to Apple Notary Service…"
    xcrun notarytool submit "$DMG_PATH" \
        --apple-id "$APPLE_ID" \
        --team-id  "$APPLE_TEAM_ID" \
        --keychain-profile "VideoRoom-Notarize" \
        --wait
    echo "==> Stapling notarization ticket…"
    xcrun stapler staple "$DMG_PATH"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "==> Artifacts in ${OUT_DIR}:"
ls -lh "$OUT_DIR"
echo ""
echo "Done."
