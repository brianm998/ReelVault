; NSIS installer script for the VideoRoom Core daemon.
;
; Called by release-core.sh after staging the binaries in PKG_DIR.
; Required /D defines (all passed on the makensis command line):
;   APP_VERSION — e.g. 0.1.0
;   ARCH        — e.g. x64
;   PKG_DIR     — Windows path to the staging dir (contains *.exe)
;   OUTPUT_FILE — Windows path for the generated setup .exe

!define APP_NAME      "VideoRoom Core"
!define APP_KEY       "VideoRoomCore"
!define APP_PUBLISHER "Brian Martin"
!define REG_UNINSTALL "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_KEY}"

Name    "${APP_NAME} ${APP_VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$PROGRAMFILES64\VideoRoom"
InstallDirRegKey HKLM "${REG_UNINSTALL}" "InstallLocation"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
Unicode true

!include "LogicLib.nsh"
!include "MUI2.nsh"

!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

; ── Install ───────────────────────────────────────────────────────────────────
Section "Install"
    SetOutPath "$INSTDIR"
    File "${PKG_DIR}\videoroom-core.exe"
    File "${PKG_DIR}\videoroom-cli.exe"

    WriteUninstaller "$INSTDIR\uninstall.exe"

    ; Add $INSTDIR to the system PATH idempotently via PowerShell.
    FileOpen  $0 "$TEMP\vr_add_path.ps1" w
    FileWrite $0 "$$d = '$INSTDIR'$\n"
    FileWrite $0 "$$k = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment'$\n"
    FileWrite $0 "$$p = (Get-ItemProperty $$k Path).Path$\n"
    FileWrite $0 "if (($$p -split ';') -notcontains $$d) { Set-ItemProperty $$k Path ($$p + ';' + $$d) }$\n"
    FileClose $0
    nsExec::ExecToLog 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$TEMP\vr_add_path.ps1"'
    Delete "$TEMP\vr_add_path.ps1"
    SendMessage ${HWND_BROADCAST} ${WM_WININICHANGE} 0 "STR:Environment" /TIMEOUT=5000

    WriteRegStr   HKLM "${REG_UNINSTALL}" "DisplayName"     "${APP_NAME} ${APP_VERSION} (${ARCH})"
    WriteRegStr   HKLM "${REG_UNINSTALL}" "DisplayVersion"  "${APP_VERSION}"
    WriteRegStr   HKLM "${REG_UNINSTALL}" "Publisher"       "${APP_PUBLISHER}"
    WriteRegStr   HKLM "${REG_UNINSTALL}" "InstallLocation" "$INSTDIR"
    WriteRegStr   HKLM "${REG_UNINSTALL}" "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegStr   HKLM "${REG_UNINSTALL}" "DisplayIcon"     "$INSTDIR\videoroom-core.exe,0"
    WriteRegDWORD HKLM "${REG_UNINSTALL}" "NoModify"        1
    WriteRegDWORD HKLM "${REG_UNINSTALL}" "NoRepair"        1
SectionEnd

; ── Uninstall ─────────────────────────────────────────────────────────────────
Section "Uninstall"
    ; Remove $INSTDIR from the system PATH via PowerShell.
    FileOpen  $0 "$TEMP\vr_remove_path.ps1" w
    FileWrite $0 "$$d = '$INSTDIR'$\n"
    FileWrite $0 "$$k = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment'$\n"
    FileWrite $0 "$$p = (Get-ItemProperty $$k Path).Path$\n"
    FileWrite $0 "$$n = ($$p -split ';' | Where-Object { $$_ -ne $$d }) -join ';'$\n"
    FileWrite $0 "Set-ItemProperty $$k Path $$n$\n"
    FileClose $0
    nsExec::ExecToLog 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$TEMP\vr_remove_path.ps1"'
    Delete "$TEMP\vr_remove_path.ps1"
    SendMessage ${HWND_BROADCAST} ${WM_WININICHANGE} 0 "STR:Environment" /TIMEOUT=5000

    Delete "$INSTDIR\videoroom-core.exe"
    Delete "$INSTDIR\videoroom-cli.exe"
    Delete "$INSTDIR\uninstall.exe"
    RMDir  "$INSTDIR"
    DeleteRegKey HKLM "${REG_UNINSTALL}"
SectionEnd
