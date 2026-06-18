#!/usr/bin/env bash
# ReelVault macOS (SwiftUI) — release build script
#
# Builds a signed (or ad-hoc) .app bundle from the SwiftPM project, embeds
# the reelvault-core daemon, and packages everything in a .dmg for distribution.
#
# Usage:
#   ./release-macos.sh [OPTIONS]
#
# Options:
#   --core-bin PATH        Path to the reelvault-core binary to embed.
#                          Defaults to searching standard build locations.
#   --sign IDENTITY        Developer ID Application identity for app signing.
#                          (e.g. "Developer ID Application: Acme (TEAMID)")
#                          Omit for ad-hoc signing (local / test builds only).
#   --notarize             Sign, package, and notarize a distributable .pkg.
#                          Requires --sign and either:
#                            CI:    APPLE_API_KEY_PATH, APPLE_API_KEY_ID,
#                                   APPLE_API_ISSUER_ID env vars
#                            Local: a "ReelVault-Notarize" keychain profile
#   --standalone           Compile out remote discovery; the app always starts its
#                          own embedded daemon on loopback.  Use for the "standalone"
#                          package that bundles the core daemon and needs no server setup.
#   --version X.Y.Z        Override the bundle version (default: Package.swift).
#   --out DIR              Output directory (default: dist/macos)
#   --help                 Show this message
#
# Requirements:
#   - macOS with Xcode Command Line Tools (swift, codesign, pkgbuild)
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
STANDALONE=0
VERSION=""
OUT_DIR="${SCRIPT_DIR}/dist/macos"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --core-bin)   CORE_BIN="$2"; shift 2 ;;
        --sign)       SIGN_IDENTITY="$2"; shift 2 ;;
        --notarize)   NOTARIZE=1; shift ;;
        --standalone) STANDALONE=1; shift ;;
        --version)    VERSION="$2"; shift 2 ;;
        --out)        OUT_DIR="$2"; shift 2 ;;
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

for cmd in swift codesign pkgbuild; do
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
echo "==> ReelVault macOS v${VERSION}"

SWIFT_STANDALONE_FLAGS=""
if [[ "$STANDALONE" -eq 1 ]]; then
    SWIFT_STANDALONE_FLAGS="-Xswiftc -DSTANDALONE_MODE"
    echo "    Mode: standalone (remote discovery disabled)"
fi

# ---------------------------------------------------------------------------
# Locate the reelvault-core binary
# ---------------------------------------------------------------------------
locate_core_bin() {
    local candidates=(
        "${CORE_BIN}"
        "${CORE_DIR}/target/universal-apple-darwin/release/reelvault-core"
        "${CORE_DIR}/target/aarch64-apple-darwin/release/reelvault-core"
        "${CORE_DIR}/target/x86_64-apple-darwin/release/reelvault-core"
        "${CORE_DIR}/target/release/reelvault-core"
        "${SCRIPT_DIR}/dist/core/reelvault-core"
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
    echo "WARNING: reelvault-core binary not found — the .app will launch without"
    echo "  a bundled daemon.  Run ./release-core.sh first, or pass --core-bin."
    echo ""
fi

# ---------------------------------------------------------------------------
# Build the Swift binary in release mode (universal: arm64 + x86_64)
# ---------------------------------------------------------------------------
echo "==> Building ReelVault (release, arm64)…"
(cd "$MACOS_DIR" && swift build -c release --arch arm64 --product ReelVault $SWIFT_STANDALONE_FLAGS 2>&1)

echo "==> Building ReelVault (release, x86_64)…"
(cd "$MACOS_DIR" && swift build -c release --arch x86_64 --product ReelVault $SWIFT_STANDALONE_FLAGS 2>&1)

ARM_BIN="${MACOS_DIR}/.build/arm64-apple-macosx/release/ReelVault"
X86_BIN="${MACOS_DIR}/.build/x86_64-apple-macosx/release/ReelVault"
SWIFT_BIN="${MACOS_DIR}/.build/release/ReelVault"

if [[ ! -f "$ARM_BIN" || ! -f "$X86_BIN" ]]; then
    echo "Error: one or both architecture builds failed." >&2
    exit 1
fi

echo "==> Linking universal binary (arm64 + x86_64)…"
mkdir -p "$(dirname "$SWIFT_BIN")"
lipo -create "$ARM_BIN" "$X86_BIN" -output "$SWIFT_BIN"
lipo -info "$SWIFT_BIN"

# ---------------------------------------------------------------------------
# Assemble the .app bundle
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"

APP_NAME="ReelVault"
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
    cp "$DAEMON_BIN" "${APP_BUNDLE}/Contents/Resources/reelvault-core"
    chmod +x "${APP_BUNDLE}/Contents/Resources/reelvault-core"
    echo "  Embedded daemon: Contents/Resources/reelvault-core"
fi

# Copy app icon if it exists
for ICON in \
    "${MACOS_DIR}/ReelVault/Assets.xcassets/AppIcon.appiconset"/*.icns \
    "${MACOS_DIR}/ReelVault/Resources/AppIcon.icns"; do
    if [[ -f "$ICON" ]]; then
        cp "$ICON" "${APP_BUNDLE}/Contents/Resources/AppIcon.icns"
        break
    fi
done

# Copy SPM's per-target resource bundle (ReelVault_ReelVault.bundle).
# Swift's synthesized Bundle.module looks for it next to the executable
# inside Contents/Resources of the .app — without it, any code that
# touches Bundle.module fatalErrors at first access (AppDelegate
# reads AppIcon.icns from there to stamp the Dock tile during dev runs).
RES_BUNDLE_NAME="ReelVault_ReelVault.bundle"
RES_BUNDLE_SRC=""
for candidate in \
    "${MACOS_DIR}/.build/arm64-apple-macosx/release/${RES_BUNDLE_NAME}" \
    "${MACOS_DIR}/.build/release/${RES_BUNDLE_NAME}"; do
    if [[ -d "$candidate" ]]; then
        RES_BUNDLE_SRC="$candidate"
        break
    fi
done
if [[ -n "$RES_BUNDLE_SRC" ]]; then
    cp -R "$RES_BUNDLE_SRC" "${APP_BUNDLE}/Contents/Resources/${RES_BUNDLE_NAME}"
    echo "  Embedded SPM resources: Contents/Resources/${RES_BUNDLE_NAME}"
else
    echo "WARNING: ${RES_BUNDLE_NAME} not found — Bundle.module lookups will fail." >&2
fi

# Info.plist
BUNDLE_ID="com.reelvault.app"
cat > "${APP_BUNDLE}/Contents/Info.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>${APP_NAME}</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
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
    <string>13.0</string>
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
# Derive the Developer ID Installer identity from the Application identity.
SIGN_PKG="${SIGN_IDENTITY/Developer ID Application/Developer ID Installer}"

sign_app() {
    local bundle="$1"
    local identity="${2:--}"   # "-" means ad-hoc
    local label="ad-hoc"
    [[ "$identity" != "-" ]] && label="${identity}"

    echo "==> Signing app bundle (${label})…"
    # Sign nested binaries inside-out before signing the outer bundle.
    # --options runtime enables Hardened Runtime (required for notarization).
    # --timestamp embeds a secure timestamp (also required by Apple's notary).
    if [[ -f "${bundle}/Contents/Resources/reelvault-core" ]]; then
        codesign --force --options runtime --timestamp \
            --sign "$identity" \
            "${bundle}/Contents/Resources/reelvault-core"
    fi
    codesign --force --options runtime --timestamp \
        --sign "$identity" \
        "$bundle"
}

if [[ -n "$SIGN_IDENTITY" ]]; then
    sign_app "$APP_BUNDLE" "$SIGN_IDENTITY"
else
    echo "==> Ad-hoc signing (no --sign provided; local/test use only)…"
    sign_app "$APP_BUNDLE" "-"
fi

# ---------------------------------------------------------------------------
# Create .pkg
# ---------------------------------------------------------------------------
PKG_NAME="${APP_NAME}-v${VERSION}-macOS.pkg"
PKG_PATH="${OUT_DIR}/${PKG_NAME}"

echo "==> Creating ${PKG_NAME}…"
# pkgbuild --component signs the installer component with the Developer ID
# Installer identity (different cert from the app's Application identity).
if [[ -n "$SIGN_PKG" && "$SIGN_PKG" != "-" ]]; then
    pkgbuild \
        --component  "$APP_BUNDLE" \
        --install-location /Applications \
        --identifier "com.reelvault.app" \
        --version    "${VERSION}" \
        --sign       "$SIGN_PKG" \
        "$PKG_PATH"
else
    pkgbuild \
        --component  "$APP_BUNDLE" \
        --install-location /Applications \
        --identifier "com.reelvault.app" \
        --version    "${VERSION}" \
        "$PKG_PATH"
fi
echo "  -> ${PKG_PATH}"

# ---------------------------------------------------------------------------
# Notarization (optional — requires Apple Developer account credentials)
# ---------------------------------------------------------------------------
notarize_submit() {
    local artifact="$1"
    # CI: App Store Connect API key (APPLE_API_KEY_PATH / _KEY_ID / _ISSUER_ID).
    # Local fallback: pre-configured "ReelVault-Notarize" keychain profile.
    if [[ -n "${APPLE_API_KEY_PATH:-}" ]]; then
        xcrun notarytool submit "$artifact" \
            --key        "$APPLE_API_KEY_PATH" \
            --key-id     "$APPLE_API_KEY_ID" \
            --issuer     "$APPLE_API_ISSUER_ID" \
            --wait
    else
        xcrun notarytool submit "$artifact" \
            --keychain-profile "ReelVault-Notarize" \
            --wait
    fi
}

if [[ "$NOTARIZE" -eq 1 ]]; then
    if [[ -z "$SIGN_IDENTITY" ]]; then
        echo "Error: --notarize requires --sign <Developer ID Application Identity>." >&2
        exit 1
    fi
    echo "==> Submitting to Apple Notary Service…"
    notarize_submit "$PKG_PATH"
    echo "==> Stapling notarization ticket…"
    xcrun stapler staple "$PKG_PATH"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "==> Artifacts in ${OUT_DIR}:"
ls -lh "$OUT_DIR"
echo ""
echo "Done."
