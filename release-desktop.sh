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
# Build and package (platform-specific)
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"

BUILD_MAIN="${DESKTOP_DIR}/build/compose/binaries/main"

# helper: copy glob-matched files into OUT_DIR (used by Linux / Windows)
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

if [[ "$OS" == "Darwin" ]]; then
    # -----------------------------------------------------------------------
    # macOS — two-phase approach:
    #   1. createDistributable → VideoRoom.app  (jpackage --type app-image)
    #   2. codesign the bundle (dylibs → JDK runtime → main exe → bundle)
    #   3. pkgbuild to wrap into a signed installer .pkg
    #
    # We bypass Gradle's packagePkg / packageDistributionForCurrentOS because
    # jpackage's PKG bundler fails on pre-signed app images (missing .package
    # sentinel → "Bundler 'Mac PKG Package' failed to produce a package").
    # -----------------------------------------------------------------------

    echo "==> Building distributable app bundle…"
    (cd "$DESKTOP_DIR" && \
        CARGO_TERM_COLOR=always \
        ./gradlew createDistributable --no-daemon 2>&1)

    APP_BUNDLE="${BUILD_MAIN}/app/VideoRoom.app"
    if [[ ! -d "$APP_BUNDLE" ]]; then
        echo "Error: app bundle not found at ${APP_BUNDLE}" >&2
        exit 1
    fi

    # Code-sign inside-out: dylibs first, then JDK runtime, then main exe, then bundle.
    if [[ -n "$SIGN_IDENTITY" ]]; then
        echo "==> Signing app bundle…"

        # 1. Dynamic libraries / JNI objects anywhere in the bundle
        find "$APP_BUNDLE" -type f \( -name "*.dylib" -o -name "*.so" -o -name "*.jnilib" \) \
          | while IFS= read -r f; do
              codesign --force --options runtime --timestamp \
                  --sign "$SIGN_IDENTITY" "$f" 2>/dev/null || true
            done

        # 2. Executables inside the bundled JDK runtime
        if [[ -d "$APP_BUNDLE/Contents/runtime" ]]; then
            find "$APP_BUNDLE/Contents/runtime" -type f -perm +111 2>/dev/null \
              | while IFS= read -r f; do
                  codesign --force --options runtime --timestamp \
                      --sign "$SIGN_IDENTITY" "$f" 2>/dev/null || true
                done
        fi

        # 3. Main application executable(s)
        find "$APP_BUNDLE/Contents/MacOS" -type f \
          | while IFS= read -r f; do
              codesign --force --options runtime --timestamp \
                  --sign "$SIGN_IDENTITY" "$f"
            done

        # 4. The bundle itself
        codesign --force --options runtime --timestamp \
            --sign "$SIGN_IDENTITY" "$APP_BUNDLE"
        echo "  Signed: $APP_BUNDLE"
    fi

    # pkgbuild: wrap the (optionally signed) .app into an installer .pkg.
    # Signing the pkg with Developer ID Installer happens here so notarytool
    # receives an already-signed pkg (no productsign step needed later).
    SIGN_PKG="${SIGN_IDENTITY/Developer ID Application/Developer ID Installer}"
    PKG_NAME="VideoRoom-${VERSION}.pkg"
    PKG_PATH="${OUT_DIR}/${PKG_NAME}"

    echo "==> Creating installer: ${PKG_NAME}…"
    if [[ -n "$SIGN_IDENTITY" && -n "$SIGN_PKG" ]]; then
        pkgbuild \
            --component        "$APP_BUNDLE" \
            --install-location /Applications \
            --identifier       "com.videoroom.app" \
            --version          "$VERSION" \
            --sign             "$SIGN_PKG" \
            "$PKG_PATH"
    else
        pkgbuild \
            --component        "$APP_BUNDLE" \
            --install-location /Applications \
            --identifier       "com.videoroom.app" \
            --version          "$VERSION" \
            "$PKG_PATH"
    fi
    echo "  -> ${PKG_PATH}"

else
    # -----------------------------------------------------------------------
    # Linux / Windows: standard Gradle native packaging
    # -----------------------------------------------------------------------
    (cd "$DESKTOP_DIR" && \
        CARGO_TERM_COLOR=always \
        ./gradlew packageDistributionForCurrentOS --no-daemon 2>&1)

    case "$OS" in
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
fi

# ---------------------------------------------------------------------------
# Clean up the staging directory
# ---------------------------------------------------------------------------
rm -rf "$RELEASE_BIN_DIR"

# ---------------------------------------------------------------------------
# macOS notarization
# The .pkg is already signed by pkgbuild above; only notarize + staple here.
# ---------------------------------------------------------------------------
if [[ "$OS" == "Darwin" && "$NOTARIZE" -eq 1 ]]; then
    if [[ -z "$SIGN_IDENTITY" ]]; then
        echo "Error: --notarize requires --sign <Developer ID Application Identity>." >&2
        exit 1
    fi

    shopt -s nullglob
    for PKG_PATH in "${OUT_DIR}"/*.pkg; do
        echo "==> Submitting to Apple Notary Service: $(basename "$PKG_PATH")…"
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
