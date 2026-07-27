<#
.SYNOPSIS
  Process and UI-heartbeat watchdog for the Epharm POSM Windows client.

.DESCRIPTION
  Runs every minute from the EpharmPOSM-Watchdog scheduled task. It repairs:
    - a missing process;
    - a process whose UI heartbeat stopped;
    - a process that never created its first heartbeat.

  Restarts are routed through the main EpharmPOSM scheduled task so Task
  Scheduler remains the owner of the visible application process.
#>
param(
    [string]$ExePath = "C:\Epharm\app\CustomerDisplay.exe",
    [string]$ConfigPath = "C:\Epharm\posm.json",
    [string]$ScreenMode = "prod",
    [string]$AppLogPath = "C:\Epharm\customerdisplay.log",
    [string]$HeartbeatPath = "C:\Epharm\heartbeat.txt",
    [int]$MaxAgeSec = 90,
    [int]$StartupGraceSec = 120,
    [string]$TaskName = "EpharmPOSM",
    [string]$LogPath = "C:\Epharm\watchdog.log"
)

$ErrorActionPreference = "Stop"

function Write-WatchdogLog {
    param([string]$Message)

    try {
        $directory = Split-Path -Parent $LogPath
        if ($directory -and -not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        Add-Content -LiteralPath $LogPath -Value ("{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message)
    } catch { }
}

function Get-PosmProcess {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($ExePath)
    $expectedPath = [System.IO.Path]::GetFullPath($ExePath)
    foreach ($process in @(Get-Process -Name $name -ErrorAction SilentlyContinue)) {
        try {
            if ([System.IO.Path]::GetFullPath($process.Path) -ieq $expectedPath) {
                return $process
            }
        } catch { }
    }
    return $null
}

function Start-Posm {
    param([string]$Reason)

    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($null -ne $task) {
            Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
            Start-ScheduledTask -TaskName $TaskName
            Start-Sleep -Seconds 3
            if ($null -ne (Get-PosmProcess)) {
                Write-WatchdogLog "restarted through task '$TaskName': $Reason"
                return
            }
        }
    } catch {
        Write-WatchdogLog "task restart failed: $($_.Exception.Message); trying direct fallback"
    }

    try {
        $env:EPHARM_POSM_CONFIG = $ConfigPath
        $env:EPHARM_SCREEN_MODE = $ScreenMode
        $env:EPHARM_APP_LOG = $AppLogPath
        Remove-Item Env:\EPHARM_DEBUG -ErrorAction SilentlyContinue
        Start-Process -FilePath $ExePath -WorkingDirectory (Split-Path -Parent $ExePath)
        Write-WatchdogLog "restarted directly: $Reason"
    } catch {
        Write-WatchdogLog "restart FAILED: $($_.Exception.Message)"
    }
}

function Restart-Posm {
    param(
        [object]$Process,
        [string]$Reason
    )

    try {
        if ($null -ne $Process) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        }
    } catch { }
    Start-Sleep -Seconds 2
    Start-Posm -Reason $Reason
}

try {
    if (-not (Test-Path -LiteralPath $ExePath -PathType Leaf)) {
        Write-WatchdogLog "executable missing: $ExePath"
        exit 1
    }

    $process = Get-PosmProcess
    if ($null -eq $process) {
        # At user logon the application and watchdog tasks can fire together.
        # Give the direct application task time to create its process before repairing it.
        Start-Sleep -Seconds 15
        $process = Get-PosmProcess
    }
    if ($null -eq $process) {
        Start-Posm -Reason "process was not running"
        exit 0
    }

    try {
        $processAgeSec = ((Get-Date) - $process.StartTime).TotalSeconds
    } catch {
        $processAgeSec = $StartupGraceSec + 1
    }

    if ($processAgeSec -lt $StartupGraceSec) {
        exit 0
    }

    if (-not (Test-Path -LiteralPath $HeartbeatPath -PathType Leaf)) {
        Restart-Posm -Process $process -Reason "heartbeat was never created after $([math]::Round($processAgeSec)) seconds"
        exit 0
    }

    $heartbeatAgeSec = ((Get-Date) - (Get-Item -LiteralPath $HeartbeatPath).LastWriteTime).TotalSeconds
    if ($heartbeatAgeSec -gt $MaxAgeSec) {
        Restart-Posm -Process $process -Reason "UI heartbeat was stale for $([math]::Round($heartbeatAgeSec)) seconds"
    }
} catch {
    Write-WatchdogLog "watchdog error (fail-safe): $($_.Exception.Message)"
    exit 1
}

exit 0
