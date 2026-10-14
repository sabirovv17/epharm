#!/usr/bin/env python3
"""Build the single-file Windows diagnostic; verify bytes before sharing it."""
import base64
from pathlib import Path

root = Path(__file__).resolve().parent
header = r'''@echo off
setlocal
chcp 65001 >nul
title Epharm stock diagnostics - any pharmacy
set "EPHARM_STOCK_DIAG_SELF=%~f0"
echo Epharm stock source diagnostics. Keep the cashier application running.
echo No software will be installed and no cashier services will be restarted.
where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo ERROR: Windows PowerShell 5.1 is required. Nothing was changed.
  pause
  exit /b 2
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "if($PSVersionTable.PSVersion -lt [version]'5.1'){Write-Host 'ERROR: Windows PowerShell 5.1 is required. Nothing was changed.'; exit 2}; $t=[IO.File]::ReadAllText($env:EPHARM_STOCK_DIAG_SELF); $m=[regex]::Match($t,'(?m)^::EPHARM_PS_BASE64=([A-Za-z0-9+/=]+)\r?$'); if(-not $m.Success){Write-Host 'ERROR: incomplete diagnostic file'; exit 2}; $s=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($m.Groups[1].Value)); & ([scriptblock]::Create($s))"
set "diagnostic_exit_code=%ERRORLEVEL%"
echo.
echo Copy the diagnostic output or send the TXT report from your Desktop.
pause
exit /b %diagnostic_exit_code%
'''
for source_name, target_name, arguments in (
    ("cashier-stock-diagnostics.ps1", "cashier-stock-diagnostics.bat", ""),
    ("cashier-connection-audit.ps1", "cashier-connection-audit.bat", ""),
    ("cashier-stock-diagnostics.ps1", "cashier-stock-metadata-manual.bat", " -ManualAuthentication"),
    ("cashier-stock-diagnostics.ps1", "cashier-stock-schema.bat", " -ManualAuthentication -StockSchema"),
    ("cashier-stock-diagnostics.ps1", "cashier-stock-probe.bat", " -ManualAuthentication -StockProbe"),
):
    source = (root / source_name).read_bytes()
    payload = base64.b64encode(source).decode("ascii")
    launcher = header.replace("& ([scriptblock]::Create($s))", "& ([scriptblock]::Create($s))" + arguments)
    bat = (launcher + "::EPHARM_PS_BASE64=" + payload + "\n").replace("\n", "\r\n").encode("ascii")
    target = root / target_name
    target.write_bytes(bat)
    assert base64.b64decode(bat.split(b"::EPHARM_PS_BASE64=")[-1].strip()) == source
    assert b"ISC_PASSWORD" not in bat.split(b"::EPHARM_PS_BASE64=")[0]
    print(f"Built {target.name}: {len(bat)} bytes; embedded source verified.")
