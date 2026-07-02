@echo off
setlocal
title Epharm POSM - setup autostart

set "__EPHARM_POSM_DIR=%~dp0"

echo.
echo Epharm POSM autostart setup
echo Folder: %__EPHARM_POSM_DIR%
echo.

if not exist "%__EPHARM_POSM_DIR%install-tasks.ps1" (
  echo [ERROR] install-tasks.ps1 was not found next to setup-autostart.bat.
  echo Unpack the whole zip to a local folder and run setup-autostart.bat again.
  echo.
  pause
  exit /b 1
)

if not exist "%__EPHARM_POSM_DIR%CustomerDisplay.exe" (
  echo [ERROR] CustomerDisplay.exe was not found next to setup-autostart.bat.
  echo The package is incomplete. Rebuild or unzip the full archive.
  echo.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop';" ^
  "$dir = $env:__EPHARM_POSM_DIR;" ^
  "$script = Join-Path $dir 'install-tasks.ps1';" ^
  "$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent());" ^
  "$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator);" ^
  "if ($isAdmin) { & $script; exit $LASTEXITCODE }" ^
  "else { Write-Host 'Requesting administrator rights...'; Start-Process powershell.exe -Verb RunAs -Wait -ArgumentList @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File',$script); exit $LASTEXITCODE }"

set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
  echo [ERROR] Autostart setup failed. Exit code: %RC%
  echo Check the messages above.
  echo.
  pause
  exit /b %RC%
)

echo Done. Autostart is installed and Epharm POSM should be running.
echo.
pause
exit /b 0
