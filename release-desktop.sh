#!/usr/bin/env bash
# VideoRoom Desktop (Kotlin Compose) — release build script
#
# ┌──────────────────────────────────────────────────────────────────────┐
# │ IMPORTANT: Compose Desktop native distributions are platform-bound.  │
# │                                                                      │
# │  macOS   .dmg  — run this script on macOS                           │
# │  Linux   .deb  — run this script on a Debian/Ubuntu host            │
# │  Linux   .rpm  — run this script on a RHEL/Fedora host              │
# │  Windows .msi  — run this script on a Windows host (or CI)          │
# │                                                                      │
# │ You cannot cross-compile Compose Desktop distributions. Use a CI    │
# │ matrix (e.g. GitHub Actions) to build all three in parallel.        │
# └──────────────────────────────────────────────────────────────────────┘
#
# Usage:
#   ./release-desktop.sh [OPTIONS]
#
# Options:
#   --core-bin PATH   Path to the videoroom-core binary to bundle.
#                     If omitted, the script searches the usual build locations
#                     and warns if nothing is found.
#   --sign IDENTITY   Developer ID Application identity for code signing.
#                     (e.g. "Developer ID Application: Acme (TEAMID)")
#                     Omit to skip signing (local / unsigned builds only).
#   --notarize        Submit the DMG to Apple Notary Service after signing.
#                     Requires --sign, and APPLE_ID, APPLE_TEAM_ID, and either
#                     APPLE_APP_PASSWORD env vars (CI) or a "VideoRoom-Notarize"
#                     keychain profile (local).
#   --out DIR         Output directory (default: dist/desktop)
#   --help            Show this message
#
# The resulting package is placed in --out together with the core bundle.
#
# Prerequisites:
#   - JDK 21+ on PATH (JAVA_HOME or jenv)
#   - On Linux: dpkg-deb (for .deb) and/or rpmbuild (for .rpm)
#   - On Windows: WiX Toolset 3.x for .msi (Compose Desktop uses it)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="${SCRIPT_DIR}/desktop"
CORE_DIR="${SCRIPT_DIR}/core"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
CORE_BIN=""
SIGN_IDENTITY=""
NOTARIZE=0
OUT_DIR="${SCRIPT_DIR}/dist/desktop"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --core-bin) CORE_BIN="$2"; shift 2 ;;
        --sign)     SIGN_IDENTITY="$2"; shift 2 ;;
        --notarize) NOTARIZE=1; shift ;;
        --out)      OUT_DIR="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,/^set -/p' "$0" | head -n 50
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

OS="$(uname -s)"

# ---------------------------------------------------------------------------
# Locate the videoroom-core binary to bundle
# ---------------------------------------------------------------------------
locate_core_bin() {
    local candidates=(
        "${CORE_BIN}"
        "${CORE_DIR}/target/release/videoroom-core"
        "${SCRIPT_DIR}/dist/core/videoroom-core"
    )
    # Also check the universal macOS binary
    if [[ "$OS" == "Darwin" ]]; then
        candidates+=(
            "${CORE_DIR}/target/universal-apple-darwin/release/videoroom-core"
        )
    fi
    for c in "${candidates[@]}"; do
        [[ -z "$c" ]] && continue
        if [[ -f "$c" && -x "$c" ]]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

echo "==> VideoRoom Desktop release build"
echo "    Platform: ${OS}"

# ---------------------------------------------------------------------------
# Bundle the core daemon binary into desktop/release-bin/
# This directory is picked up by build.gradle.kts appResourcesRootDir.
# ---------------------------------------------------------------------------
RELEASE_BIN_DIR="${DESKTOP_DIR}/release-bin"
mkdir -p "$RELEASE_BIN_DIR"

if FOUND_BIN="$(locate_core_bin 2>/dev/null)"; then
    echo "==> Bundling daemon binary: ${FOUND_BIN}"
    # On Windows the binary has a .exe suffix; on Unix it doesn't.
    if [[ "$OS" == "MINGW"* || "$OS" == "CYGWIN"* || "$OS" == "MSYS"* ]]; then
        cp -f "$FOUND_BIN" "${RELEASE_BIN_DIR}/videoroom-core.exe"
    else
        cp -f "$FOUND_BIN" "${RELEASE_BIN_DIR}/videoroom-core"
        chmod +x "${RELEASE_BIN_DIR}/videoroom-core"
    fi
else
    echo ""
    echo "WARNING: videoroom-core binary not found — the desktop app will be"
    echo "  packaged without a bundled daemon. Users will need to install or"
    echo "  run videoroom-core separately."
    echo "  To bundle it, run ./release-core.sh first, then re-run this script."
    echo "  Or pass --core-bin <path> explicitly."
    echo ""
fi

# ---------------------------------------------------------------------------
# Read version from Gradle
# ---------------------------------------------------------------------------
VERSION="$(grep 'packageVersion' "${DESKTOP_DIR}/build.gradle.kts" \
    | head -1 | sed 's/.*"\(.*\)".*/\1/')"
echo "==> Package version: ${VERSION}"

# ---------------------------------------------------------------------------
# Build the native distribution for the current platform
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"

# Export for build.gradle.kts signing block
[[ -n "$SIGN_IDENTITY" ]] && export APPLE_SIGN_IDENTITY="$SIGN_IDENTITY"

(cd "$DESKTOP_DIR" && \
    CARGO_TERM_COLOR=always \
    ./gradlew packageDistributionForCurrentOS --no-daemon 2>&1)

# ---------------------------------------------------------------------------
# Copy the output artifact(s) into OUT_DIR
# ---------------------------------------------------------------------------
copy_artifacts() {
    local src_dir="$1"
    local glob="$2"
    shopt -s nullglob
    local files=( ${src_dir}/${glob} )
    shopt -u nullglob
    if [[ ${#files[@]} -eq 0 ]]; then
        echo "Warning: no artifact matching '${glob}' found in ${src_dir}" >&2
        return
    fi
    for f in "${files[@]}"; do
        cp -f "$f" "$OUT_DIR/"
        echo "  -> ${OUT_DIR}/$(basename "$f")"
    done
}

BUILD_MAIN="${DESKTOP_DIR}/build/compose/binaries/main"

case "$OS" in
    Darwin)
        copy_artifacts "${BUILD_MAIN}/pkg" "*.pkg"
        ;;
    Linux)
        copy_artifacts "${BUILD_MAIN}/deb" "*.deb"
        copy_artifacts "${BUILD_MAIN}/rpm" "*.rpm" 2>/dev/null || true
        ;;
    MINGW*|CYGWIN*|MSYS*)
        copy_artifacts "${BUILD_MAIN}/msi" "*.msi"
        copy_artifacts "${BUILD_MAIN}/exe" "*.exe" 2>/dev/null || true
        ;;
    *)
        echo "Unexpected platform '${OS}'; copying everything from ${BUILD_MAIN}" >&2
        cp -r "${BUILD_MAIN}"/* "$OUT_DIR/" 2>/dev/null || true
        ;;
esac

# ---------------------------------------------------------------------------
# Clean up the staging directory
# ---------------------------------------------------------------------------
rm -rf "$RELEASE_BIN_DIR"

# ---------------------------------------------------------------------------
# macOS .pkg signing + notarization
# ---------------------------------------------------------------------------
if [[ "$OS" == "Darwin" && "$NOTARIZE" -eq 1 ]]; then
    if [[ -z "$SIGN_IDENTITY" ]]; then
        echo "Error: --notarize requires --sign <Developer ID Application Identity>." >&2
        exit 1
    fi
    # Derive the Developer ID Installer identity from the Application identity.
    SIGN_PKG="${SIGN_IDENTITY/Developer ID Application/Developer ID Installer}"

    shopt -s nullglob
    for PKG_PATH in "${OUT_DIR}"/*.pkg; do
        echo "==> Re-signing pkg with Developer ID Installer: $(basename "$PKG_PATH")…"
        # jpackage's pkg contains a signed .app but the pkg wrapper itself is
        # unsigned. productsign adds the Developer ID Installer signature.
        SIGNED="${PKG_PATH%.pkg}-signed.pkg"
        productsign --sign "$SIGN_PKG" "$PKG_PATH" "$SIGNED"
        mv -f "$SIGNED" "$PKG_PATH"

        echo "==> Submitting to Apple Notary Service…"
        # CI: App Store Connect API key.
        # Local fallback: "VideoRoom-Notarize" keychain profile.
        if [[ -n "${APPLE_API_KEY_PATH:-}" ]]; then
            xcrun notarytool submit "$PKG_PATH" \
                --key    "$APPLE_API_KEY_PATH" \
                --key-id "$APPLE_API_KEY_ID" \
                --issuer "$APPLE_API_ISSUER_ID" \
                --wait
        else
            xcrun notarytool submit "$PKG_PATH" \
                --keychain-profile "VideoRoom-Notarize" \
                --wait
        fi
        echo "==> Stapling notarization ticket…"
        xcrun stapler staple "$PKG_PATH"
    done
    shopt -u nullglob
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "==> Artifacts in ${OUT_DIR}:"
ls -lh "$OUT_DIR" 2>/dev/null || true
echo ""
echo "Done."
