@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Epharm POSM - autostart setup

rem install-tasks.ps1 needs administrator rights -> self-elevate via UAC.
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Requesting administrator rights ^(confirm the UAC prompt^)...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

if not exist "%~dp0install-tasks.ps1" (
  echo [ERROR] install-tasks.ps1 not found next to this file.
  echo Unpack the whole zip to a local folder ^(e.g. C:\Epharm-POSM^) and run again.
  pause
  exit /b 1
)

echo Installing autostart and starting Epharm POSM...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-tasks.ps1"

echo.
echo Done. Autostart is installed and the app is running.
echo It will start automatically on every login and self-restart on crash/hang.
echo You can close this window.
pause
