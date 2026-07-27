@echo off
setlocal
chcp 65001 >nul

fltmc >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator rights...
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect-posm-diagnostics.ps1"
set "EXIT_CODE=%errorlevel%"
echo.
if "%EXIT_CODE%"=="0" (
  echo Done. Explorer opened the generated diagnostic ZIP.
) else (
  echo Diagnostic collection failed. Review the message above.
)
pause
exit /b %EXIT_CODE%
