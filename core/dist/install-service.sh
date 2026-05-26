#!/usr/bin/env bash
# VideoRoom Core — system daemon installer (macOS + Linux)
#
# Usage:
#   sudo ./install-service.sh [--binary PATH] [--uninstall]
#
# Options:
#   --binary PATH   Path to the videoroom-core binary to install.
#                   Defaults to ./videoroom-core (next to this script).
#   --uninstall     Remove the service and binary instead of installing.
#
# After installation the daemon starts automatically and restarts on crash.
# It listens on 127.0.0.1:50051 and serves all users on this machine from a
# single shared catalog.
#
# Log locations:
#   macOS:  /Library/Logs/VideoRoom/videoroom-core.log
#   Linux:  /var/log/videoroom/videoroom-core.log
#           journalctl -u videoroom-core -f

set -euo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY="${SCRIPT_DIR}/videoroom-core"
UNINSTALL=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --binary)   BINARY="$2"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# OS detection
# ---------------------------------------------------------------------------
OS="$(uname -s)"
case "$OS" in
    Darwin) PLATFORM="macos" ;;
    Linux)  PLATFORM="linux" ;;
    *)      echo "Unsupported platform: $OS" >&2; exit 1 ;;
esac

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Error: this script must be run as root (use sudo)." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# macOS install / uninstall
# ---------------------------------------------------------------------------
install_macos() {
    local plist_src="${SCRIPT_DIR}/com.videoroom.core.plist"
    local plist_dst="/Library/LaunchDaemons/com.videoroom.core.plist"
    local install_bin="/usr/local/bin/videoroom-core"
    local log_dir="/Library/Logs/VideoRoom"
    local data_dir="/Library/Application Support/VideoRoom"

    echo "==> Creating directories…"
    mkdir -p "$log_dir" "$data_dir"
    chmod 755 "$log_dir"
    chmod 750 "$data_dir"

    echo "==> Installing binary to ${install_bin}…"
    cp -f "$BINARY" "$install_bin"
    chmod 755 "$install_bin"

    echo "==> Installing launchd plist…"
    cp -f "$plist_src" "$plist_dst"
    chmod 644 "$plist_dst"
    chown root:wheel "$plist_dst"

    # Unload first in case we're upgrading a running daemon.
    launchctl unload "$plist_dst" 2>/dev/null || true
    launchctl load -w "$plist_dst"

    echo ""
    echo "VideoRoom Core daemon installed and started."
    echo "  Logs:    ${log_dir}/videoroom-core.log"
    echo "  Catalog: ${data_dir}/catalog.db"
    echo "  Port:    127.0.0.1:50051"
}

uninstall_macos() {
    local plist="/Library/LaunchDaemons/com.videoroom.core.plist"
    launchctl unload -w "$plist" 2>/dev/null || true
    rm -f "$plist"
    rm -f "/usr/local/bin/videoroom-core"
    echo "VideoRoom Core daemon removed."
    echo "Catalog and logs left intact in /Library/Logs/VideoRoom and"
    echo "/Library/Application Support/VideoRoom — remove manually if desired."
}

# ---------------------------------------------------------------------------
# Linux install / uninstall
# ---------------------------------------------------------------------------
install_linux() {
    local unit_src="${SCRIPT_DIR}/videoroom-core.service"
    local unit_dst="/etc/systemd/system/videoroom-core.service"
    local install_bin="/usr/local/bin/videoroom-core"

    echo "==> Creating service user 'videoroom'…"
    if ! id videoroom &>/dev/null; then
        useradd --system --no-create-home --shell /usr/sbin/nologin videoroom
    fi

    echo "==> Installing binary to ${install_bin}…"
    cp -f "$BINARY" "$install_bin"
    chmod 755 "$install_bin"

    echo "==> Installing systemd unit…"
    cp -f "$unit_src" "$unit_dst"
    chmod 644 "$unit_dst"

    systemctl daemon-reload
    systemctl enable --now videoroom-core

    echo ""
    echo "VideoRoom Core daemon installed and started."
    echo "  Logs:    journalctl -u videoroom-core -f"
    echo "           /var/log/videoroom/videoroom-core.log"
    echo "  Catalog: /var/lib/videoroom/catalog.db"
    echo "  Port:    127.0.0.1:50051"
}

uninstall_linux() {
    systemctl disable --now videoroom-core 2>/dev/null || true
    rm -f "/etc/systemd/system/videoroom-core.service"
    systemctl daemon-reload
    rm -f "/usr/local/bin/videoroom-core"
    echo "VideoRoom Core daemon removed."
    echo "Catalog and logs in /var/lib/videoroom and /var/log/videoroom left"
    echo "intact — remove manually if desired."
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------
if [[ "$UNINSTALL" -eq 1 ]]; then
    case "$PLATFORM" in
        macos) uninstall_macos ;;
        linux) uninstall_linux ;;
    esac
else
    if [[ ! -f "$BINARY" ]]; then
        echo "Error: binary not found at '${BINARY}'." >&2
        echo "Pass --binary PATH or place videoroom-core next to this script." >&2
        exit 1
    fi
    case "$PLATFORM" in
        macos) install_macos ;;
        linux) install_linux ;;
    esac
fi
