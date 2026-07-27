@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo Collecting read-only Standard-N identity diagnostics...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0diagnose-standardn.ps1"
if errorlevel 1 pause
exit /b %ERRORLEVEL%
