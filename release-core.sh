#!/usr/bin/env bash
# ReelVault Core — multi-platform release build script
#
# Produces release binaries and service-install bundles for all supported
# targets and places them under dist/core/.
#
# ┌─────────────────────────────────────────────────────────────────────┐
# │ Platform notes                                                      │
# │                                                                     │
# │ macOS (aarch64 + x86_64 → universal binary)                        │
# │   Built natively on macOS using `rustup target add …`.             │
# │   Requires Xcode Command Line Tools.                                │
# │                                                                     │
# │ Linux (x86_64, aarch64) and Windows (x86_64)                       │
# │   Require `cross` — a Docker-based cross-compilation tool:         │
# │     cargo install cross --git https://github.com/cross-rs/cross    │
# │   Skip with --skip-linux / --skip-windows if Docker is unavailable.│
# └─────────────────────────────────────────────────────────────────────┘
#
# Usage:
#   ./release-core.sh [OPTIONS]
#
# Options:
#   --native          Build only the current host's platform (used by CI).
#                     macOS → universal binary; Linux → native arch; Windows → x86_64 MSVC.
#                     Does not require cross or Docker.
#   --version X.Y.Z   Override the release version tag (default: Cargo.toml)
#   --skip-linux      Skip Linux cross-compilation targets (full mode only)
#   --skip-windows    Skip Windows cross-compilation target (full mode only)
#   --out DIR         Output directory (default: dist/core)
#   --help            Show this message

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="${SCRIPT_DIR}/core"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
NATIVE=0
VERSION=""
SKIP_LINUX=0
SKIP_WINDOWS=0
OUT_DIR="${SCRIPT_DIR}/dist/core"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --native)       NATIVE=1; shift ;;
        --version)      VERSION="$2"; shift 2 ;;
        --skip-linux)   SKIP_LINUX=1; shift ;;
        --skip-windows) SKIP_WINDOWS=1; shift ;;
        --out)          OUT_DIR="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,/^$/p' "$0"
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Resolve version from Cargo.toml if not overridden
# ---------------------------------------------------------------------------
if [[ -z "$VERSION" ]]; then
    VERSION="$(grep '^version' "${CORE_DIR}/Cargo.toml" \
        | head -1 | sed 's/.*"\(.*\)".*/\1/')"
fi
echo "==> Building reelvault-core v${VERSION}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
require_cmd() {
    if ! command -v "$1" &>/dev/null; then
        echo "Error: '$1' is required but not found." >&2
        [[ -n "${2:-}" ]] && echo "  $2" >&2
        exit 1
    fi
}

check_cross() {
    if ! command -v cross &>/dev/null; then
        echo ""
        echo "WARNING: 'cross' not found — Linux/Windows targets will be skipped."
        echo "  Install: cargo install cross --git https://github.com/cross-rs/cross"
        echo "  Also ensure Docker Desktop is running."
        echo ""
        return 1
    fi
    if ! docker info &>/dev/null 2>&1; then
        echo ""
        echo "WARNING: Docker is not running — Linux/Windows targets will be skipped."
        echo ""
        return 1
    fi
    return 0
}

# Build a single target. Uses `cross` when the host can't build it natively.
build_target() {
    local target="$1"
    local use_cross="${2:-0}"
    local tool="cargo"
    [[ "$use_cross" -eq 1 ]] && tool="cross"

    echo "  [${target}] building…"
    (cd "$CORE_DIR" && \
        CARGO_TERM_COLOR=always \
        "$tool" build --release --target "$target" \
            --bin reelvault-core --bin reelvault-cli 2>&1)
}

# Package a built target into a tar.gz (Unix) or zip (Windows).
package_target() {
    local target="$1"
    local platform_label="$2"   # e.g. "linux-x86_64"
    local ext="${3:-}"           # ".exe" for Windows, "" otherwise

    local bin_dir="${CORE_DIR}/target/${target}/release"
    local pkg_name="reelvault-core-v${VERSION}-${platform_label}"
    local pkg_dir="${OUT_DIR}/${pkg_name}"

    rm -rf "$pkg_dir"
    mkdir -p "$pkg_dir"

    cp "${bin_dir}/reelvault-core${ext}"  "${pkg_dir}/"
    cp "${bin_dir}/reelvault-cli${ext}"   "${pkg_dir}/"
    cp -r "${CORE_DIR}/dist/"*            "${pkg_dir}/" 2>/dev/null || true

    # Add a quick-start README.
    cat > "${pkg_dir}/INSTALL.txt" << EOF
ReelVault Core v${VERSION} — ${platform_label}
================================================

Binaries
--------
  reelvault-core${ext}   gRPC daemon
  reelvault-cli${ext}    command-line catalog tool

System Daemon Setup
-------------------
EOF

    if [[ "$ext" == ".exe" ]]; then
        cat >> "${pkg_dir}/INSTALL.txt" << 'EOF'
  Run install-service.ps1 from an elevated PowerShell prompt.
  The daemon will start automatically on boot and log to:
    C:\ProgramData\ReelVault\logs\reelvault-core.log
EOF
    else
        cat >> "${pkg_dir}/INSTALL.txt" << 'EOF'
  Run install-service.sh as root:
    sudo ./install-service.sh
  The daemon will start automatically on boot.

  macOS logs:  /Library/Logs/ReelVault/reelvault-core.log
  Linux logs:  /var/log/reelvault/reelvault-core.log
               journalctl -u reelvault-core -f
EOF
    fi

    # Create archive. Prefer 7z for zip (available on CI); fall back to zip (macOS/Linux).
    if [[ "$ext" == ".exe" ]]; then
        if command -v 7z &>/dev/null; then
            (cd "$OUT_DIR" && 7z a -tzip -y "${pkg_name}.zip" "${pkg_name}/" > /dev/null)
        elif command -v zip &>/dev/null; then
            (cd "$OUT_DIR" && zip -qr "${pkg_name}.zip" "${pkg_name}/")
        else
            echo "Error: 7z or zip is required to package Windows binaries." >&2; exit 1
        fi
        echo "  -> ${OUT_DIR}/${pkg_name}.zip"
    else
        (cd "$OUT_DIR" && tar czf "${pkg_name}.tar.gz" "${pkg_name}/")
        echo "  -> ${OUT_DIR}/${pkg_name}.tar.gz"
    fi

    rm -rf "$pkg_dir"
}

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
require_cmd cargo  "Install Rust: https://rustup.rs"
require_cmd rustup "Install Rust: https://rustup.rs"

mkdir -p "$OUT_DIR"

# ---------------------------------------------------------------------------
# --native: build only the current host's platform, then exit.
# Each GitHub Actions runner calls this; no cross/Docker needed.
# ---------------------------------------------------------------------------
if [[ "$NATIVE" -eq 1 ]]; then
    HOST_OS="$(uname -s)"
    HOST_ARCH="$(uname -m)"
    case "$HOST_OS" in
        Darwin)
            echo ""
            echo "==> macOS universal target (native)"
            rustup target add aarch64-apple-darwin x86_64-apple-darwin 2>/dev/null || true
            require_cmd lipo "lipo ships with Xcode Command Line Tools"
            build_target "aarch64-apple-darwin" 0
            build_target "x86_64-apple-darwin"  0
            echo "  Stitching universal binary with lipo…"
            LIPO_OUT="${CORE_DIR}/target/universal-apple-darwin"
            mkdir -p "${LIPO_OUT}/release"
            for bin in reelvault-core reelvault-cli; do
                lipo -create -output "${LIPO_OUT}/release/${bin}" \
                    "${CORE_DIR}/target/aarch64-apple-darwin/release/${bin}" \
                    "${CORE_DIR}/target/x86_64-apple-darwin/release/${bin}"
                echo "    lipo -> ${bin} (universal)"
            done
            PKG="reelvault-core-v${VERSION}-macos-universal"
            PKG_DIR="${OUT_DIR}/${PKG}"
            mkdir -p "$PKG_DIR"
            cp "${LIPO_OUT}/release/reelvault-core" "${PKG_DIR}/"
            cp "${LIPO_OUT}/release/reelvault-cli"  "${PKG_DIR}/"
            cp -r "${CORE_DIR}/dist/"* "${PKG_DIR}/" 2>/dev/null || true
            cat > "${PKG_DIR}/INSTALL.txt" << EOF
ReelVault Core v${VERSION} — macOS Universal (arm64 + x86_64)
=============================================================
sudo ./install-service.sh
Logs: /Library/Logs/ReelVault/reelvault-core.log
EOF
            (cd "$OUT_DIR" && tar czf "${PKG}.tar.gz" "${PKG}/")
            rm -rf "$PKG_DIR"
            echo "  -> ${OUT_DIR}/${PKG}.tar.gz"

            # ── macOS .pkg installer ──────────────────────────────────────────
            echo "  Packaging ${PKG}.pkg…"
            PKG_STAGE="${OUT_DIR}/.pkgroot"
            rm -rf "${PKG_STAGE}"
            mkdir -p "${PKG_STAGE}/usr/local/bin"
            cp "${LIPO_OUT}/release/reelvault-core" "${PKG_STAGE}/usr/local/bin/"
            cp "${LIPO_OUT}/release/reelvault-cli"  "${PKG_STAGE}/usr/local/bin/"
            chmod 755 "${PKG_STAGE}/usr/local/bin/reelvault-core"
            chmod 755 "${PKG_STAGE}/usr/local/bin/reelvault-cli"

            # Codesign binaries with Hardened Runtime before pkgbuild.
            # Apple notarization rejects packages containing Mach-O binaries that
            # are not signed with a Developer ID Application cert + --options runtime
            # + --timestamp, even if the .pkg wrapper is signed with Installer cert.
            if [[ -n "${SIGN_APP:-}" ]]; then
                echo "  Code-signing binaries with hardened runtime…"
                for bin in \
                    "${PKG_STAGE}/usr/local/bin/reelvault-core" \
                    "${PKG_STAGE}/usr/local/bin/reelvault-cli"; do
                    codesign --force --options runtime --timestamp \
                        --sign "${SIGN_APP}" "$bin"
                    echo "    Signed: $(basename "$bin")"
                done
            fi

            PKGBUILD_ARGS=(
                --root             "${PKG_STAGE}"
                --identifier       "com.reelvault.core"
                --version          "${VERSION}"
                --install-location /
            )
            # Sign if a Developer ID Installer identity is available via env var.
            # In CI, SIGN_PKG is set after the keychain is imported.
            [[ -n "${SIGN_PKG:-}" ]] && PKGBUILD_ARGS+=(--sign "${SIGN_PKG}")
            pkgbuild "${PKGBUILD_ARGS[@]}" "${OUT_DIR}/${PKG}.pkg"
            rm -rf "${PKG_STAGE}"
            echo "  -> ${OUT_DIR}/${PKG}.pkg"
            ;;
        Linux)
            case "$HOST_ARCH" in
                x86_64)
                    echo ""
                    echo "==> Linux x86_64 target (native)"
                    rustup target add x86_64-unknown-linux-gnu 2>/dev/null || true
                    build_target "x86_64-unknown-linux-gnu" 0
                    package_target "x86_64-unknown-linux-gnu" "linux-x86_64"

                    # ── Linux .deb package ───────────────────────────────────
                    echo "  Packaging reelvault-core_${VERSION}_amd64.deb…"
                    DEB_STEM="reelvault-core_${VERSION}_amd64"
                    DEB_STAGE="${OUT_DIR}/.debroot"
                    rm -rf "${DEB_STAGE}"
                    mkdir -p "${DEB_STAGE}/DEBIAN"
                    mkdir -p "${DEB_STAGE}/usr/local/bin"
                    cp "${CORE_DIR}/target/x86_64-unknown-linux-gnu/release/reelvault-core" \
                        "${DEB_STAGE}/usr/local/bin/"
                    cp "${CORE_DIR}/target/x86_64-unknown-linux-gnu/release/reelvault-cli" \
                        "${DEB_STAGE}/usr/local/bin/"
                    chmod 755 "${DEB_STAGE}/usr/local/bin/"*
                    cat > "${DEB_STAGE}/DEBIAN/control" << CTRL
Package: reelvault-core
Version: ${VERSION}
Architecture: amd64
Maintainer: Brian Martin
Depends: libc6 (>= 2.17)
Section: video
Priority: optional
Description: ReelVault Core Daemon
 gRPC daemon for the ReelVault video cataloging application.
CTRL
                    dpkg-deb --build --root-owner-group \
                        "${DEB_STAGE}" "${OUT_DIR}/${DEB_STEM}.deb"
                    rm -rf "${DEB_STAGE}"
                    echo "  -> ${OUT_DIR}/${DEB_STEM}.deb"
                    ;;
                aarch64)
                    echo ""
                    echo "==> Linux aarch64 target (native)"
                    rustup target add aarch64-unknown-linux-gnu 2>/dev/null || true
                    build_target "aarch64-unknown-linux-gnu" 0
                    package_target "aarch64-unknown-linux-gnu" "linux-aarch64"

                    # ── Linux .deb package ───────────────────────────────────
                    echo "  Packaging reelvault-core_${VERSION}_arm64.deb…"
                    DEB_STEM="reelvault-core_${VERSION}_arm64"
                    DEB_STAGE="${OUT_DIR}/.debroot"
                    rm -rf "${DEB_STAGE}"
                    mkdir -p "${DEB_STAGE}/DEBIAN"
                    mkdir -p "${DEB_STAGE}/usr/local/bin"
                    cp "${CORE_DIR}/target/aarch64-unknown-linux-gnu/release/reelvault-core" \
                        "${DEB_STAGE}/usr/local/bin/"
                    cp "${CORE_DIR}/target/aarch64-unknown-linux-gnu/release/reelvault-cli" \
                        "${DEB_STAGE}/usr/local/bin/"
                    chmod 755 "${DEB_STAGE}/usr/local/bin/"*
                    cat > "${DEB_STAGE}/DEBIAN/control" << CTRL
Package: reelvault-core
Version: ${VERSION}
Architecture: arm64
Maintainer: Brian Martin
Depends: libc6 (>= 2.17)
Section: video
Priority: optional
Description: ReelVault Core Daemon
 gRPC daemon for the ReelVault video cataloging application.
CTRL
                    dpkg-deb --build --root-owner-group \
                        "${DEB_STAGE}" "${OUT_DIR}/${DEB_STEM}.deb"
                    rm -rf "${DEB_STAGE}"
                    echo "  -> ${OUT_DIR}/${DEB_STEM}.deb"
                    ;;
                *)
                    echo "Error: unsupported Linux arch: ${HOST_ARCH}" >&2; exit 1 ;;
            esac
            ;;
        MINGW*|CYGWIN*|MSYS*)
            echo ""
            echo "==> Windows x86_64 target (native, MSVC)"
            # CI Windows runners default to MSVC; use it directly instead of cross/GNU.
            rustup target add x86_64-pc-windows-msvc 2>/dev/null || true
            build_target "x86_64-pc-windows-msvc" 0
            package_target "x86_64-pc-windows-msvc" "windows-x86_64" ".exe"

            # ── Windows Setup.exe ─────────────────────────────────────────────
            MAKENSIS_CMD="$(command -v makensis.exe 2>/dev/null \
                || command -v makensis 2>/dev/null || true)"
            if [[ -n "$MAKENSIS_CMD" ]]; then
                echo "  Packaging Windows Setup.exe…"
                WIN_STEM="reelvault-core-v${VERSION}-windows-x86_64"
                WIN_STAGE="${OUT_DIR}/${WIN_STEM}"
                mkdir -p "${WIN_STAGE}"
                cp "${CORE_DIR}/target/x86_64-pc-windows-msvc/release/reelvault-core.exe" \
                    "${WIN_STAGE}/"
                cp "${CORE_DIR}/target/x86_64-pc-windows-msvc/release/reelvault-cli.exe" \
                    "${WIN_STAGE}/"
                SETUP_OUT="${OUT_DIR}/${WIN_STEM}-Setup.exe"
                NSI_SCRIPT="${SCRIPT_DIR}/releases/reelvault_core_installer.nsi"
                "$MAKENSIS_CMD" \
                    "-DAPP_VERSION=${VERSION}" \
                    "-DARCH=x64" \
                    "-DPKG_DIR=$(cygpath -wa "${WIN_STAGE}")" \
                    "-DOUTPUT_FILE=$(cygpath -wa "${SETUP_OUT}")" \
                    "$(cygpath -w "${NSI_SCRIPT}")"
                rm -rf "${WIN_STAGE}"
                echo "  -> ${SETUP_OUT}"
            else
                echo "  WARNING: makensis not found — skipping Setup.exe" \
                     "(install NSIS to build it)"
            fi
            ;;
        *)
            echo "Error: unrecognised host OS '${HOST_OS}' for --native mode." >&2; exit 1 ;;
    esac
    echo ""
    echo "==> Release artifacts in ${OUT_DIR}:"
    ls -lh "${OUT_DIR}"/*.tar.gz "${OUT_DIR}"/*.zip \
           "${OUT_DIR}"/*.pkg    "${OUT_DIR}"/*.deb \
           "${OUT_DIR}"/*-Setup.exe 2>/dev/null || true
    echo ""
    echo "Done."
    exit 0
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "Warning: macOS universal binary builds require a macOS host." >&2
    echo "  Linux/Windows targets can still be built via 'cross'." >&2
fi

# ---------------------------------------------------------------------------
# macOS targets (native — requires macOS)
# ---------------------------------------------------------------------------
if [[ "$(uname -s)" == "Darwin" ]]; then
    echo ""
    echo "==> macOS targets"

    echo "  Adding Rust targets…"
    rustup target add aarch64-apple-darwin x86_64-apple-darwin 2>/dev/null || true

    build_target "aarch64-apple-darwin"
    build_target "x86_64-apple-darwin"

    echo "  Stitching universal binary with lipo…"
    require_cmd lipo "lipo ships with Xcode Command Line Tools"

    LIPO_OUT="${CORE_DIR}/target/universal-apple-darwin"
    mkdir -p "${LIPO_OUT}/release"

    for bin in reelvault-core reelvault-cli; do
        lipo -create -output "${LIPO_OUT}/release/${bin}" \
            "${CORE_DIR}/target/aarch64-apple-darwin/release/${bin}" \
            "${CORE_DIR}/target/x86_64-apple-darwin/release/${bin}"
        echo "    lipo -> ${bin} (universal)"
    done

    # Package: universal (preferred) + individual arches for pinned installs.
    (
        PKG="reelvault-core-v${VERSION}-macos-universal"
        PKG_DIR="${OUT_DIR}/${PKG}"
        mkdir -p "$PKG_DIR"
        cp "${LIPO_OUT}/release/reelvault-core"  "${PKG_DIR}/"
        cp "${LIPO_OUT}/release/reelvault-cli"   "${PKG_DIR}/"
        cp -r "${CORE_DIR}/dist/"*               "${PKG_DIR}/" 2>/dev/null || true
        cat > "${PKG_DIR}/INSTALL.txt" << EOF
ReelVault Core v${VERSION} — macOS Universal (arm64 + x86_64)
=============================================================
sudo ./install-service.sh
Logs: /Library/Logs/ReelVault/reelvault-core.log
EOF
        (cd "$OUT_DIR" && tar czf "${PKG}.tar.gz" "${PKG}/")
        rm -rf "$PKG_DIR"
        echo "  -> ${OUT_DIR}/${PKG}.tar.gz"
    )
fi

# ---------------------------------------------------------------------------
# Linux targets (requires `cross` + Docker)
# ---------------------------------------------------------------------------
if [[ "$SKIP_LINUX" -eq 0 ]]; then
    if check_cross; then
        echo ""
        echo "==> Linux targets"

        rustup target add x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu 2>/dev/null || true

        build_target "x86_64-unknown-linux-gnu"  1
        package_target "x86_64-unknown-linux-gnu"  "linux-x86_64"

        build_target "aarch64-unknown-linux-gnu" 1
        package_target "aarch64-unknown-linux-gnu" "linux-aarch64"
    else
        echo "  (skipped — run on a Linux host or install 'cross' + Docker)"
    fi
else
    echo ""
    echo "==> Linux targets skipped (--skip-linux)"
fi

# ---------------------------------------------------------------------------
# Windows target (requires `cross` + Docker)
# ---------------------------------------------------------------------------
if [[ "$SKIP_WINDOWS" -eq 0 ]]; then
    if check_cross; then
        echo ""
        echo "==> Windows target"

        rustup target add x86_64-pc-windows-gnu 2>/dev/null || true
        build_target "x86_64-pc-windows-gnu" 1
        package_target "x86_64-pc-windows-gnu" "windows-x86_64" ".exe"
    else
        echo "  (skipped — run on a Windows host or install 'cross' + Docker)"
    fi
else
    echo ""
    echo "==> Windows target skipped (--skip-windows)"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "==> Release artifacts in ${OUT_DIR}:"
ls -lh "${OUT_DIR}"/*.tar.gz "${OUT_DIR}"/*.zip 2>/dev/null || true
echo ""
echo "Done."
