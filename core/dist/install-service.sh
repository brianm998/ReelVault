#!/usr/bin/env bash
# ReelVault Core — system daemon installer (macOS + Linux)
#
# Usage:
#   sudo ./install-service.sh [--binary PATH] [--catalog PATH] [--import-dir PATH] [--uninstall]
#
# Options:
#   --binary PATH      Path to the reelvault-core binary to install.
#                      Defaults to ./reelvault-core (next to this script).
#   --catalog PATH     SQLite catalog the daemon opens at startup (templated into
#                      --db-path). Defaults to the platform system catalog.
#   --import-dir PATH  Directory uploads are written to and indexed from
#                      (templated into --import-dir). Set this up front to enable
#                      uploads from remote clients (e.g. the iOS app).
#   --uninstall        Remove the service and binary instead of installing.
#
# After installation the daemon starts automatically and restarts on crash. It
# listens on 127.0.0.1:50051 (loopback, plaintext) and — because the service
# definition passes --remote — on the LAN IP over TLS + mDNS, so remote clients
# can discover and connect. It serves all users on this machine from a single
# shared catalog.
#
# Log locations:
#   macOS:  /Library/Logs/ReelVault/reelvault-core.log
#   Linux:  /var/log/reelvault/reelvault-core.log
#           journalctl -u reelvault-core -f

set -euo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY="${SCRIPT_DIR}/reelvault-core"
CATALOG=""
IMPORT_DIR=""
UNINSTALL=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --binary)     BINARY="$2"; shift 2 ;;
        --catalog)    CATALOG="$2"; shift 2 ;;
        --import-dir) IMPORT_DIR="$2"; shift 2 ;;
        --uninstall)  UNINSTALL=1; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Service-definition templating
# ---------------------------------------------------------------------------
# The shipped plist / unit files run `--system-daemon --port 50051 --remote`.
# When the operator chose a catalog or import dir, splice the matching flags in.

# Append `--db-path`/`--import-dir` <string> entries before </array> in a plist.
inject_plist_args() {
    local file="$1"
    local extra=""
    [[ -n "$CATALOG" ]] && extra+="        <string>--db-path</string>\n        <string>${CATALOG}</string>\n"
    [[ -n "$IMPORT_DIR" ]] && extra+="        <string>--import-dir</string>\n        <string>${IMPORT_DIR}</string>\n"
    [[ -z "$extra" ]] && return 0
    awk -v ins="$extra" '!done && /<\/array>/ { printf "%s", ins; done=1 } { print }' \
        "$file" > "${file}.tmp" && mv "${file}.tmp" "$file"
}

# Append `--db-path`/`--import-dir` to a systemd ExecStart= line.
inject_unit_args() {
    local file="$1"
    local extra=""
    [[ -n "$CATALOG" ]] && extra+=" --db-path \"${CATALOG}\""
    [[ -n "$IMPORT_DIR" ]] && extra+=" --import-dir \"${IMPORT_DIR}\""
    [[ -z "$extra" ]] && return 0
    awk -v extra="$extra" '/^ExecStart=/ { print $0 extra; next } { print }' \
        "$file" > "${file}.tmp" && mv "${file}.tmp" "$file"
}

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
    local plist_src="${SCRIPT_DIR}/com.reelvault.core.plist"
    local plist_dst="/Library/LaunchDaemons/com.reelvault.core.plist"
    local install_bin="/usr/local/bin/reelvault-core"
    local log_dir="/Library/Logs/ReelVault"
    local data_dir="/Library/Application Support/ReelVault"

    echo "==> Creating directories…"
    mkdir -p "$log_dir" "$data_dir"
    chmod 755 "$log_dir"
    chmod 750 "$data_dir"

    echo "==> Installing binary to ${install_bin}…"
    cp -f "$BINARY" "$install_bin"
    chmod 755 "$install_bin"

    echo "==> Installing launchd plist…"
    cp -f "$plist_src" "$plist_dst"
    inject_plist_args "$plist_dst"
    chmod 644 "$plist_dst"
    chown root:wheel "$plist_dst"

    # Unload first in case we're upgrading a running daemon.
    launchctl unload "$plist_dst" 2>/dev/null || true
    launchctl load -w "$plist_dst"

    echo ""
    echo "ReelVault Core daemon installed and started."
    echo "  Logs:    ${log_dir}/reelvault-core.log"
    echo "  Catalog: ${data_dir}/catalog.db"
    echo "  Port:    127.0.0.1:50051"
}

uninstall_macos() {
    local plist="/Library/LaunchDaemons/com.reelvault.core.plist"
    launchctl unload -w "$plist" 2>/dev/null || true
    rm -f "$plist"
    rm -f "/usr/local/bin/reelvault-core"
    echo "ReelVault Core daemon removed."
    echo "Catalog and logs left intact in /Library/Logs/ReelVault and"
    echo "/Library/Application Support/ReelVault — remove manually if desired."
}

# ---------------------------------------------------------------------------
# Linux install / uninstall
# ---------------------------------------------------------------------------
install_linux() {
    local unit_src="${SCRIPT_DIR}/reelvault-core.service"
    local unit_dst="/etc/systemd/system/reelvault-core.service"
    local install_bin="/usr/local/bin/reelvault-core"

    echo "==> Creating service user 'reelvault'…"
    if ! id reelvault &>/dev/null; then
        useradd --system --no-create-home --shell /usr/sbin/nologin reelvault
    fi

    echo "==> Installing binary to ${install_bin}…"
    cp -f "$BINARY" "$install_bin"
    chmod 755 "$install_bin"

    echo "==> Installing systemd unit…"
    cp -f "$unit_src" "$unit_dst"
    inject_unit_args "$unit_dst"
    # The import dir must be writable by the dedicated 'reelvault' service user.
    if [[ -n "$IMPORT_DIR" ]]; then
        mkdir -p "$IMPORT_DIR"
        chown reelvault:reelvault "$IMPORT_DIR" 2>/dev/null || true
    fi
    chmod 644 "$unit_dst"

    systemctl daemon-reload
    systemctl enable --now reelvault-core

    echo ""
    echo "ReelVault Core daemon installed and started."
    echo "  Logs:    journalctl -u reelvault-core -f"
    echo "           /var/log/reelvault/reelvault-core.log"
    echo "  Catalog: /var/lib/reelvault/catalog.db"
    echo "  Port:    127.0.0.1:50051"
}

uninstall_linux() {
    systemctl disable --now reelvault-core 2>/dev/null || true
    rm -f "/etc/systemd/system/reelvault-core.service"
    systemctl daemon-reload
    rm -f "/usr/local/bin/reelvault-core"
    echo "ReelVault Core daemon removed."
    echo "Catalog and logs in /var/lib/reelvault and /var/log/reelvault left"
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
        echo "Pass --binary PATH or place reelvault-core next to this script." >&2
        exit 1
    fi
    case "$PLATFORM" in
        macos) install_macos ;;
        linux) install_linux ;;
    esac
fi
