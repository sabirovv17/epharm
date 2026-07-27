@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Epharm POSM Standard-N diagnostics

if not exist "%~dp0capture-standardn-scan-source.ps1" (
  echo ERROR: capture-standardn-scan-source.ps1 is missing.
  pause
  exit /b 2
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0capture-standardn-scan-source.ps1"
set "diagnostic_exit_code=%ERRORLEVEL%"
if not "%diagnostic_exit_code%"=="0" pause
exit /b %diagnostic_exit_code%
