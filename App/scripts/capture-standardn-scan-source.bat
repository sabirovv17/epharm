@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Epharm - Standard-N scan source capture

if not exist "%~dp0capture-standardn-scan-source.ps1" (
  echo [ERROR] capture-standardn-scan-source.ps1 is missing.
  pause
  exit /b 2
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0capture-standardn-scan-source.ps1"
if errorlevel 1 pause
exit /b %ERRORLEVEL%
