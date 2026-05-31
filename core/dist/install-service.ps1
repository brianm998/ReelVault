# ReelVault Core — Windows service installer
#
# Run from an elevated (Administrator) PowerShell prompt:
#   .\install-service.ps1
#   .\install-service.ps1 -Binary "C:\path\to\reelvault-core.exe"
#   .\install-service.ps1 -Uninstall
#
# The daemon installs to C:\Program Files\ReelVault\ and listens on
# 127.0.0.1:50051, serving all users on this machine from a single catalog.
#
# Log location:  C:\ProgramData\ReelVault\logs\reelvault-core.log
# Catalog:       C:\ProgramData\ReelVault\catalog.db

param(
    [string]$Binary = "$PSScriptRoot\reelvault-core.exe",
    [switch]$Uninstall
)

$ServiceName   = "ReelVaultCore"
$DisplayName   = "ReelVault Core Daemon"
$Description   = "Serves the ReelVault gRPC catalog API on 127.0.0.1:50051."
$InstallDir    = "C:\Program Files\ReelVault"
$InstallBin    = "$InstallDir\reelvault-core.exe"
$DataDir       = "C:\ProgramData\ReelVault"
$LogDir        = "$DataDir\logs"

# Require elevation.
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error "This script must be run as Administrator."
    exit 1
}

function Install-Service {
    # Stop and remove any previous installation.
    if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
        Write-Host "==> Stopping existing service..."
        Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
        & sc.exe delete $ServiceName | Out-Null
        Start-Sleep -Seconds 1
    }

    if (-not (Test-Path $Binary)) {
        Write-Error "Binary not found at '$Binary'."
        Write-Error "Pass -Binary <path> or place reelvault-core.exe next to this script."
        exit 1
    }

    Write-Host "==> Creating directories..."
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    New-Item -ItemType Directory -Force -Path $DataDir    | Out-Null
    New-Item -ItemType Directory -Force -Path $LogDir     | Out-Null

    Write-Host "==> Installing binary to $InstallBin..."
    Copy-Item -Force $Binary $InstallBin

    Write-Host "==> Registering Windows service..."
    $binPath = "`"$InstallBin`" --system-daemon --port 50051"
    & sc.exe create $ServiceName `
        binPath= $binPath `
        start=   auto      `
        DisplayName= $DisplayName | Out-Null

    & sc.exe description $ServiceName $Description | Out-Null

    # Configure failure recovery: restart after 5 s, up to 3 times per day.
    & sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/5000/restart/5000 | Out-Null

    Write-Host "==> Starting service..."
    Start-Service -Name $ServiceName

    $svc = Get-Service -Name $ServiceName
    Write-Host ""
    Write-Host "ReelVault Core daemon installed and running (status: $($svc.Status))."
    Write-Host "  Logs:    $LogDir\reelvault-core.log"
    Write-Host "  Catalog: $DataDir\catalog.db"
    Write-Host "  Port:    127.0.0.1:50051"
    Write-Host ""
    Write-Host "To view logs:"
    Write-Host "  Get-Content '$LogDir\reelvault-core.log' -Wait"
}

function Uninstall-Service {
    Write-Host "==> Stopping service..."
    Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
    & sc.exe delete $ServiceName | Out-Null
    Remove-Item -Force $InstallBin -ErrorAction SilentlyContinue
    Write-Host "ReelVault Core service removed."
    Write-Host "Catalog and logs in $DataDir left intact — remove manually if desired."
}

if ($Uninstall) {
    Uninstall-Service
} else {
    Install-Service
}
