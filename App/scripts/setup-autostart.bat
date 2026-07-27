@echo off
setlocal EnableExtensions
title Epharm POSM - setup

set "__EPHARM_POSM_DIR=%~dp0"
set "__EPHARM_INSTALLER=%~dp0install-tasks.ps1"

if not exist "%__EPHARM_INSTALLER%" goto :package_error
if not exist "%__EPHARM_POSM_DIR%CustomerDisplay.exe" goto :package_error
if not exist "%__EPHARM_POSM_DIR%watchdog.ps1" goto :package_error
if not exist "%__EPHARM_POSM_DIR%posm.json" goto :package_error

powershell.exe -NoLogo -NoProfile -Command "$p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent()); if ($p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 0 } else { exit 1 }"
if not errorlevel 1 goto :install

echo Requesting administrator rights...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop';" ^
  "$quotedScript = [char]34 + $env:__EPHARM_INSTALLER + [char]34;" ^
  "$childArgs = @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',$quotedScript);" ^
  "$p = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -WorkingDirectory $env:__EPHARM_POSM_DIR -ArgumentList $childArgs;" ^
  "exit $p.ExitCode"
set "RC=%ERRORLEVEL%"
goto :result

:install
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%__EPHARM_INSTALLER%"
set "RC=%ERRORLEVEL%"

:result
if not "%RC%"=="0" goto :install_error

echo.
echo [OK] Epharm POSM setup completed.
echo [OK] The application is monitored and will restart automatically.
echo.
timeout /t 4 /nobreak >nul
exit /b 0

:package_error
echo.
echo [ERROR] The ZIP package is incomplete or was not fully extracted.
echo Expected next to this file:
echo   CustomerDisplay.exe
echo   posm.json
echo   install-tasks.ps1
echo   watchdog.ps1
echo.
pause
exit /b 2

:install_error
echo.
echo [ERROR] Epharm POSM installation failed. Exit code: %RC%
if exist "C:\Epharm\install-status.json" (
  echo.
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "$s = Get-Content -LiteralPath 'C:\Epharm\install-status.json' -Raw -Encoding UTF8 | ConvertFrom-Json;" ^
    "Write-Host ('[FAILED PHASE] ' + $s.phase) -ForegroundColor Yellow;" ^
    "Write-Host ('[EXACT CAUSE]  ' + $s.message) -ForegroundColor Red"
  copy /Y "C:\Epharm\install-status.json" "%__EPHARM_POSM_DIR%install-status.json" >nul 2>nul
)
if exist "C:\Epharm\install.log" (
  echo.
  echo Last installer log lines:
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "Get-Content -LiteralPath 'C:\Epharm\install.log' -Tail 30"
  copy /Y "C:\Epharm\install.log" "%__EPHARM_POSM_DIR%install-last.log" >nul 2>nul
)
if exist "C:\Epharm\customerdisplay.log" (
  copy /Y "C:\Epharm\customerdisplay.log" "%__EPHARM_POSM_DIR%customerdisplay-last.log" >nul 2>nul
)
if exist "C:\Epharm\crash.log" (
  copy /Y "C:\Epharm\crash.log" "%__EPHARM_POSM_DIR%crash-last.log" >nul 2>nul
)
echo.
echo Diagnostic copies were saved next to setup-autostart.bat.
echo.
pause
exit /b %RC%
