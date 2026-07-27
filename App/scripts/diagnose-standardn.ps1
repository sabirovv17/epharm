#requires -version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

function Find-InstalledPosm {
    try {
        $task = Get-ScheduledTask -TaskName "EpharmPOSM" -ErrorAction Stop
        foreach ($action in @($task.Actions)) {
            $candidate = [Environment]::ExpandEnvironmentVariables([string]$action.Execute).Trim('"')
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return [System.IO.Path]::GetFullPath($candidate)
            }
        }
    } catch { }

    $candidates = foreach ($root in @("C:\Epharm\app-prod", "C:\Epharm\app-dev", "C:\Epharm\app")) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            Get-ChildItem -LiteralPath $root -Filter "CustomerDisplay.exe" -File -Recurse -ErrorAction SilentlyContinue
        }
    }
    return @($candidates | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName
}

$exe = Find-InstalledPosm
if ([string]::IsNullOrWhiteSpace($exe) -or -not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw "POSM is not installed. Run setup-autostart.bat first."
}

Write-Host "POSM: $exe" -ForegroundColor DarkGray
$process = Start-Process -FilePath $exe -ArgumentList "--diagnose-standardn" -WorkingDirectory (Split-Path -Parent $exe) -PassThru -Wait
if ($process.ExitCode -ne 0) {
    throw "Standard-N diagnostics exited with code $($process.ExitCode)."
}

$report = "C:\Epharm\standardn-identity-diagnostics.txt"
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) {
    throw "Diagnostics finished without creating $report"
}

Write-Host "Result: $report" -ForegroundColor Green
Start-Process -FilePath "notepad.exe" -ArgumentList $report
