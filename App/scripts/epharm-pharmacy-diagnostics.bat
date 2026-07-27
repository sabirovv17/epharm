@echo off
setlocal EnableExtensions
set "EPHARM_DIAG_SCRIPT=%~f0"
if not defined EPHARM_DIAG_CALLER_DESKTOP set "EPHARM_DIAG_CALLER_DESKTOP=%USERPROFILE%\Desktop"
title Epharm pharmacy diagnostics

fltmc >nul 2>&1
if errorlevel 1 (
  echo Requesting administrator rights...
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath $env:EPHARM_DIAG_SCRIPT -Verb RunAs"
  exit /b
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$source=[IO.File]::ReadAllText($env:EPHARM_DIAG_SCRIPT);$marker='# EPHARM_'+'POWERSHELL_PAYLOAD';$index=$source.LastIndexOf($marker);if($index -lt 0){throw 'Embedded diagnostic payload was not found.'};& ([ScriptBlock]::Create($source.Substring($index+$marker.Length)))"
set "EPHARM_DIAG_EXIT=%ERRORLEVEL%"
echo.
if "%EPHARM_DIAG_EXIT%"=="0" (
  echo Diagnostic ZIP is ready. Explorer should show it now.
) else (
  echo Diagnostics finished with an error. Partial evidence was preserved in C:\Epharm\diagnostics.
)
pause
exit /b %EPHARM_DIAG_EXIT%

# EPHARM_POWERSHELL_PAYLOAD
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:Summary = New-Object 'System.Collections.Generic.List[string]'
$script:Errors = New-Object 'System.Collections.Generic.List[string]'
$script:Config = $null
$script:PosmExe = $null
$script:DeviceKey = $null
$script:PharmacyId = $null
$script:AppLogPath = "C:\Epharm\customerdisplay.log"
$script:HeartbeatPath = "C:\Epharm\heartbeat.txt"
$script:StandardRoots = @()
$script:CashLogs = @()
$script:OutputDir = $null

function Write-Utf8File {
    param([string]$Path, [object]$Value)
    $parent = Split-Path -Parent $Path
    if ($parent -and !(Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $text = if ($Value -is [string]) { $Value } else { $Value | Out-String -Width 700 }
    [System.IO.File]::WriteAllText($Path, [string]$text, $script:Utf8NoBom)
}

function Add-Summary {
    param([string]$Line = "")
    [void]$script:Summary.Add($Line)
}

function Add-DiagnosticError {
    param([string]$Area, [object]$ErrorObject)
    $message = "${Area}: " + $ErrorObject.Exception.GetBaseException().Message
    [void]$script:Errors.Add($message)
}

function Protect-Text {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    $safe = [regex]::Replace(
        $Text,
        '(?im)(device[_-]?key|api[_-]?key|access[_-]?key|standardndbpassword|password|passwd|pwd|token|secret|signature|credential|authorization|x-posm-key)(\s*["'']?\s*[:=]\s*["'']?)([^\r\n,;"'']+)',
        '$1$2[REDACTED]')
    $safe = [regex]::Replace($safe, '(?im)(DataSource|Database|UserID|Charset|Port)=[^;\r\n]*(;|$)', '$1=[PRESENT]$2')
    return $safe
}

function Redact-Object {
    param([object]$Value)
    if ($null -eq $Value) { return }
    if ($Value -is [string] -or $Value.GetType().IsPrimitive) { return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in @($Value.Keys)) {
            if ([string]$key -match '(?i)key|password|passwd|pwd|token|secret|signature|credential|authorization') {
                $Value[$key] = "[REDACTED]"
            } else {
                Redact-Object $Value[$key]
            }
        }
        return
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        foreach ($entry in $Value) { Redact-Object $entry }
        return
    }
    foreach ($property in @($Value.PSObject.Properties)) {
        if ($property.Name -match '(?i)key|password|passwd|pwd|token|secret|signature|credential|authorization') {
            try { $property.Value = "[REDACTED]" } catch { }
        } else {
            Redact-Object $property.Value
        }
    }
}

function Format-Bool {
    param([bool]$Value)
    if ($Value) { return "YES" }
    return "NO"
}

function Get-ConfigProperty {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Add-UniquePath {
    param([System.Collections.Generic.List[string]]$List, [string]$Path, [switch]$RequireFile, [switch]$RequireDirectory)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    try {
        $expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
        $full = [System.IO.Path]::GetFullPath($expanded)
        if ($RequireFile -and !(Test-Path -LiteralPath $full -PathType Leaf)) { return }
        if ($RequireDirectory -and !(Test-Path -LiteralPath $full -PathType Container)) { return }
        foreach ($existing in $List) {
            if ([string]::Equals($existing, $full, [StringComparison]::OrdinalIgnoreCase)) { return }
        }
        [void]$List.Add($full)
    } catch { }
}

function Get-SharedBytes {
    param([string]$Path, [long]$Offset = 0, [int]$MaximumBytes = 2097152)
    $stream = $null
    $reader = $null
    try {
        $stream = New-Object System.IO.FileStream(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
        if ($Offset -lt 0) { $Offset = 0 }
        if ($Offset -gt $stream.Length) { $Offset = 0 }
        [void]$stream.Seek($Offset, [System.IO.SeekOrigin]::Begin)
        $count = [int][Math]::Min([long]$MaximumBytes, $stream.Length - $Offset)
        $reader = New-Object System.IO.BinaryReader($stream)
        return $reader.ReadBytes($count)
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        elseif ($null -ne $stream) { $stream.Dispose() }
    }
}

function Decode-Bytes {
    param([byte[]]$Bytes, [string]$EncodingName = "windows-1251")
    if ($null -eq $Bytes -or $Bytes.Length -eq 0) { return "" }
    try { return [Text.Encoding]::GetEncoding($EncodingName).GetString($Bytes) }
    catch { return [Text.Encoding]::UTF8.GetString($Bytes) }
}

function Get-FileState {
    param([string]$Path)
    try {
        $item = Get-Item -LiteralPath $Path -ErrorAction Stop
        return [pscustomobject]@{
            Path = $item.FullName
            Exists = $true
            Length = [long]$item.Length
            CreatedUtc = $item.CreationTimeUtc
            LastWriteUtc = $item.LastWriteTimeUtc
        }
    } catch {
        return [pscustomobject]@{ Path = $Path; Exists = $false; Length = 0L; CreatedUtc = $null; LastWriteUtc = $null }
    }
}

function Get-FileDelta {
    param([object]$Before, [object]$After, [string]$EncodingName, [int]$MaximumBytes = 4194304)
    if ($null -eq $After -or !$After.Exists) { return "" }
    $offset = 0L
    if ($null -ne $Before -and $Before.Exists -and $After.Length -gt $Before.Length) {
        $offset = [long]$Before.Length
    } elseif ($After.Length -gt $MaximumBytes) {
        $offset = $After.Length - $MaximumBytes
    }
    try {
        return Decode-Bytes (Get-SharedBytes -Path $After.Path -Offset $offset -MaximumBytes $MaximumBytes) $EncodingName
    } catch {
        return "READ ERROR: " + $_.Exception.GetBaseException().Message
    }
}

function Get-FileAccessReport {
    param([string[]]$Paths)
    $rows = New-Object 'System.Collections.Generic.List[string]'
    foreach ($path in $Paths) {
        [void]$rows.Add("PATH: $path")
        try {
            $item = Get-Item -LiteralPath $path -ErrorAction Stop
            [void]$rows.Add("  exists=YES; length=$($item.Length); createdUtc=$($item.CreationTimeUtc.ToString('o')); lastWriteUtc=$($item.LastWriteTimeUtc.ToString('o'))")
            try {
                $acl = Get-Acl -LiteralPath $path -ErrorAction Stop
                [void]$rows.Add("  owner=$($acl.Owner)")
            } catch { [void]$rows.Add("  owner=ERROR $($_.Exception.GetBaseException().Message)") }
            try {
                $bytes = Get-SharedBytes -Path $path -Offset ([Math]::Max(0L, $item.Length - 262144L)) -MaximumBytes 262144
                [void]$rows.Add("  sharedRead=YES; sampledBytes=$($bytes.Length)")
            } catch { [void]$rows.Add("  sharedRead=NO; error=$($_.Exception.GetBaseException().Message)") }
        } catch {
            [void]$rows.Add("  exists=NO; error=$($_.Exception.GetBaseException().Message)")
        }
        [void]$rows.Add("")
    }
    return $rows
}

function Get-RelevantProcesses {
    try {
        return @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -match '(?i)customerdisplay|epharm|zkassa|kass|standart|standard|managerxp|apteka|firebird|fbserver|fbguard'
        } | Select-Object ProcessId, ParentProcessId, Name, ExecutablePath, CommandLine, CreationDate)
    } catch {
        Add-DiagnosticError "process inventory" $_
        return @()
    }
}

function Get-PosmExecutable {
    param([object[]]$Processes)
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    # The scheduled task is the deployment contract. Prefer its exact action over an arbitrary
    # leftover process, then report every running path separately in posm-runtime.txt.
    try {
        $task = Get-ScheduledTask -TaskName "EpharmPOSM" -ErrorAction Stop
        foreach ($action in $task.Actions) {
            Add-UniquePath $candidates ([string]$action.Execute) -RequireFile
        }
    } catch { }
    foreach ($process in $Processes) {
        if ($process.Name -match '(?i)^CustomerDisplay(?:\.exe)?$|^Epharm') {
            Add-UniquePath $candidates ([string]$process.ExecutablePath) -RequireFile
        }
    }
    foreach ($folder in @("C:\Epharm\app-prod", "C:\Epharm\app", "C:\Epharm\app-dev")) {
        if (!(Test-Path -LiteralPath $folder -PathType Container)) { continue }
        try {
            Get-ChildItem -LiteralPath $folder -Filter "CustomerDisplay.exe" -File -Recurse -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTimeUtc -Descending | ForEach-Object {
                    Add-UniquePath $candidates $_.FullName -RequireFile
                }
        } catch { }
    }
    if ($candidates.Count -gt 0) { return $candidates[0] }
    return $null
}

function Get-StandardRoots {
    param([object[]]$Processes, [object]$Config)
    $roots = New-Object 'System.Collections.Generic.List[string]'
    foreach ($path in @(
        "C:\STANDART-N", "C:\Standart-N", "C:\STANDART-N_DEMO", "C:\Standart-N_DEMO",
        "C:\StandartN", "C:\Standart_N", "C:\Kassir", "C:\Kassa", "C:\Apteka",
        "C:\Program Files\Standart-N", "C:\Program Files (x86)\Standart-N"
    )) { Add-UniquePath $roots $path -RequireDirectory }

    foreach ($process in $Processes) {
        try {
            if ([string]::IsNullOrWhiteSpace([string]$process.ExecutablePath)) { continue }
            $dir = Split-Path -Parent $process.ExecutablePath
            Add-UniquePath $roots $dir -RequireDirectory
            Add-UniquePath $roots (Split-Path -Parent $dir) -RequireDirectory
        } catch { }
    }

    foreach ($configured in @(Get-ConfigProperty $Config "standardNLogPaths" @())) {
        try { Add-UniquePath $roots (Split-Path -Parent ([string]$configured)) -RequireDirectory } catch { }
    }

    if (Test-Path -LiteralPath "C:\Epharm\standardn-log-paths.txt" -PathType Leaf) {
        foreach ($cached in Get-Content -LiteralPath "C:\Epharm\standardn-log-paths.txt" -ErrorAction SilentlyContinue) {
            try { Add-UniquePath $roots (Split-Path -Parent $cached.Trim()) -RequireDirectory } catch { }
        }
    }

    try {
        foreach ($drive in Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" -ErrorAction Stop) {
            foreach ($directory in Get-ChildItem -LiteralPath ($drive.DeviceID + "\") -Directory -ErrorAction SilentlyContinue) {
                if ($directory.Name -match '(?i)standart|standard|kass|kassa|apteka') {
                    Add-UniquePath $roots $directory.FullName -RequireDirectory
                }
            }
        }
    } catch { }
    return @($roots)
}

function Find-FilesBounded {
    param(
        [string[]]$Roots,
        [string]$NamePattern,
        [int]$MaxDepth = 6,
        [int]$MaxDirectories = 2500,
        [int]$MaxResults = 100
    )
    $results = New-Object 'System.Collections.Generic.List[string]'
    $visited = New-Object 'System.Collections.Generic.List[string]'
    $queue = New-Object System.Collections.Queue
    foreach ($root in $Roots) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            $queue.Enqueue([pscustomobject]@{ Path = $root; Depth = 0 })
        }
    }
    $directories = 0
    while ($queue.Count -gt 0 -and $directories -lt $MaxDirectories -and $results.Count -lt $MaxResults) {
        $entry = $queue.Dequeue()
        $alreadyVisited = $false
        foreach ($known in $visited) {
            if ([string]::Equals($known, $entry.Path, [StringComparison]::OrdinalIgnoreCase)) { $alreadyVisited = $true; break }
        }
        if ($alreadyVisited) { continue }
        [void]$visited.Add($entry.Path)
        $directories++
        try {
            foreach ($item in Get-ChildItem -LiteralPath $entry.Path -Force -ErrorAction SilentlyContinue) {
                if ($results.Count -ge $MaxResults) { break }
                if ($item.PSIsContainer) {
                    if ($entry.Depth -lt $MaxDepth -and (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)) {
                        $queue.Enqueue([pscustomobject]@{ Path = $item.FullName; Depth = $entry.Depth + 1 })
                    }
                } elseif ($item.Name -match $NamePattern) {
                    Add-UniquePath $results $item.FullName -RequireFile
                }
            }
        } catch { }
    }
    return @($results)
}

function Find-CashLogs {
    param([string[]]$Roots, [object]$Config)
    $logs = New-Object 'System.Collections.Generic.List[string]'
    foreach ($known in @(
        "C:\STANDART-N\Kassir\zkassa.log",
        "C:\STANDART-N\Kassir\zkassa",
        "C:\STANDART-N\Kassir\zkassa.txt",
        "C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log"
    )) { Add-UniquePath $logs $known -RequireFile }
    foreach ($configured in @(Get-ConfigProperty $Config "standardNLogPaths" @())) {
        Add-UniquePath $logs ([string]$configured) -RequireFile
    }
    if (Test-Path -LiteralPath "C:\Epharm\standardn-log-paths.txt" -PathType Leaf) {
        foreach ($cached in Get-Content -LiteralPath "C:\Epharm\standardn-log-paths.txt" -ErrorAction SilentlyContinue) {
            Add-UniquePath $logs $cached.Trim() -RequireFile
        }
    }
    foreach ($found in Find-FilesBounded -Roots $Roots -NamePattern '(?i)^zkassa(?:\.(?:log|txt))?$') {
        Add-UniquePath $logs $found -RequireFile
    }
    return @($logs)
}

function Save-SystemInventory {
    param([object[]]$Processes)
    $lines = New-Object 'System.Collections.Generic.List[string]'
    [void]$lines.Add("Collected: $([DateTimeOffset]::Now.ToString('o'))")
    [void]$lines.Add("Computer: $env:COMPUTERNAME")
    [void]$lines.Add("Collector: $([Security.Principal.WindowsIdentity]::GetCurrent().Name)")
    [void]$lines.Add("PowerShell: $($PSVersionTable.PSVersion); 64bit=$([Environment]::Is64BitProcess)")
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        [void]$lines.Add("Windows: $($os.Caption); version=$($os.Version); build=$($os.BuildNumber); arch=$($os.OSArchitecture)")
        [void]$lines.Add("Last boot: $($os.LastBootUpTime)")
        [void]$lines.Add("Locale: $($os.Locale); timezone=$([TimeZoneInfo]::Local.Id)")
    } catch { [void]$lines.Add("Windows inventory error: $($_.Exception.GetBaseException().Message)") }
    try {
        $computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
        [void]$lines.Add("Interactive user: $($computer.UserName)")
        [void]$lines.Add("Hardware: $($computer.Manufacturer) $($computer.Model); RAM=$([Math]::Round($computer.TotalPhysicalMemory / 1GB, 1)) GB")
    } catch { }
    [void]$lines.Add("")
    [void]$lines.Add("DISPLAYS:")
    try {
        Add-Type -AssemblyName System.Windows.Forms
        foreach ($screen in [System.Windows.Forms.Screen]::AllScreens) {
            [void]$lines.Add("  device=$($screen.DeviceName); primary=$($screen.Primary); bounds=$($screen.Bounds); workingArea=$($screen.WorkingArea)")
        }
    } catch { [void]$lines.Add("  error=$($_.Exception.GetBaseException().Message)") }
    try {
        foreach ($gpu in Get-CimInstance Win32_VideoController -ErrorAction Stop) {
            [void]$lines.Add("  GPU=$($gpu.Name); driver=$($gpu.DriverVersion); status=$($gpu.Status); mode=$($gpu.VideoModeDescription)")
        }
    } catch { }
    [void]$lines.Add("")
    [void]$lines.Add("EPHARM ENVIRONMENT:")
    foreach ($variable in Get-ChildItem Env: | Where-Object { $_.Name -like "EPHARM_*" } | Sort-Object Name) {
        $value = if ($variable.Name -match '(?i)key|pass|token|secret') { "[REDACTED]" } else { [string]$variable.Value }
        [void]$lines.Add("  $($variable.Name)=$value")
    }
    Write-Utf8File (Join-Path $script:OutputDir "system.txt") (Protect-Text ($lines -join "`r`n"))

    $processText = $Processes | Format-List * | Out-String -Width 700
    Write-Utf8File (Join-Path $script:OutputDir "processes.txt") (Protect-Text $processText)
}

function Save-TaskInventory {
    try {
        $rows = New-Object 'System.Collections.Generic.List[string]'
        $tasks = @(Get-ScheduledTask -ErrorAction Stop | Where-Object { $_.TaskName -like "EpharmPOSM*" })
        foreach ($task in $tasks) {
            $info = $task | Get-ScheduledTaskInfo
            [void]$rows.Add("TASK: $($task.TaskName)")
            [void]$rows.Add("  state=$($task.State); user=$($task.Principal.UserId); logonType=$($task.Principal.LogonType); runLevel=$($task.Principal.RunLevel)")
            [void]$rows.Add("  lastRun=$($info.LastRunTime); lastResult=$($info.LastTaskResult); nextRun=$($info.NextRunTime); missed=$($info.NumberOfMissedRuns)")
            foreach ($action in $task.Actions) {
                [void]$rows.Add("  action=$($action.Execute) $($action.Arguments); workdir=$($action.WorkingDirectory)")
            }
            [void]$rows.Add("")
        }
        if ($tasks.Count -eq 0) { [void]$rows.Add("No EpharmPOSM scheduled tasks found.") }
        Write-Utf8File (Join-Path $script:OutputDir "scheduled-tasks.txt") (Protect-Text ($rows -join "`r`n"))
    } catch {
        Write-Utf8File (Join-Path $script:OutputDir "scheduled-tasks.txt") $_.Exception.ToString()
    }
}

function Save-PosmInventory {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    if ([string]::IsNullOrWhiteSpace($script:PosmExe)) {
        [void]$lines.Add("POSM executable: NOT FOUND")
        Write-Utf8File (Join-Path $script:OutputDir "posm-build.txt") ($lines -join "`r`n")
        return
    }
    [void]$lines.Add("POSM executable: $script:PosmExe")
    try {
        $version = [Diagnostics.FileVersionInfo]::GetVersionInfo($script:PosmExe)
        [void]$lines.Add("File version: $($version.FileVersion)")
        [void]$lines.Add("Product version: $($version.ProductVersion)")
        [void]$lines.Add("SHA256: $((Get-FileHash -LiteralPath $script:PosmExe -Algorithm SHA256).Hash)")
        $appDir = Split-Path -Parent $script:PosmExe
        [void]$lines.Add("Application directory: $appDir")
        [void]$lines.Add("")
        [void]$lines.Add("TOP-LEVEL FILES:")
        foreach ($file in Get-ChildItem -LiteralPath $appDir -File -ErrorAction SilentlyContinue | Sort-Object Name) {
            [void]$lines.Add("  $($file.Name); bytes=$($file.Length); modifiedUtc=$($file.LastWriteTimeUtc.ToString('o'))")
        }
        [void]$lines.Add("")
        [void]$lines.Add("CORE HASHES:")
        foreach ($name in @("CustomerDisplay.exe", "CustomerDisplay.dll", "CustomerDisplay.deps.json", "CustomerDisplay.runtimeconfig.json", "install-tasks.ps1", "watchdog.ps1", "setup-autostart.bat")) {
            $path = Join-Path $appDir $name
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                [void]$lines.Add("  $name=$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)")
            }
        }
    } catch { [void]$lines.Add("Inventory error: $($_.Exception.GetBaseException().Message)") }
    Write-Utf8File (Join-Path $script:OutputDir "posm-build.txt") ($lines -join "`r`n")

    $runtimeRows = New-Object 'System.Collections.Generic.List[string]'
    foreach ($process in Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '(?i)CustomerDisplay|Epharm' }) {
        try {
            [void]$runtimeRows.Add("pid=$($process.Id); name=$($process.ProcessName); started=$($process.StartTime); path=$($process.Path); title=$($process.MainWindowTitle); responding=$($process.Responding)")
        } catch { [void]$runtimeRows.Add("pid=$($process.Id); name=$($process.ProcessName); details unavailable") }
    }
    if ($runtimeRows.Count -eq 0) { [void]$runtimeRows.Add("No running POSM process found.") }
    Write-Utf8File (Join-Path $script:OutputDir "posm-runtime.txt") ($runtimeRows -join "`r`n")

    $cacheRows = New-Object 'System.Collections.Generic.List[string]'
    foreach ($path in @("C:\Epharm\media-cache", "C:\Epharm\outbox.db", "C:\Epharm\heartbeat.txt")) {
        if (Test-Path -LiteralPath $path) {
            $item = Get-Item -LiteralPath $path
            [void]$cacheRows.Add("$path; type=$(if($item.PSIsContainer){'directory'}else{'file'}); modifiedUtc=$($item.LastWriteTimeUtc.ToString('o')); bytes=$(if($item.PSIsContainer){''}else{$item.Length})")
            if ($item.PSIsContainer) {
                foreach ($child in Get-ChildItem -LiteralPath $path -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 50) {
                    [void]$cacheRows.Add("  $($child.Name); bytes=$($child.Length); modifiedUtc=$($child.LastWriteTimeUtc.ToString('o'))")
                }
            }
        } else { [void]$cacheRows.Add("$path; NOT FOUND") }
    }
    Write-Utf8File (Join-Path $script:OutputDir "posm-storage.txt") ($cacheRows -join "`r`n")
}

function Save-RedactedConfig {
    $configPath = "C:\Epharm\posm.json"
    if (!(Test-Path -LiteralPath $configPath -PathType Leaf)) {
        Write-Utf8File (Join-Path $script:OutputDir "posm-config-redacted.json") "C:\Epharm\posm.json: NOT FOUND"
        return
    }
    try {
        $rawConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 -ErrorAction Stop
        $script:Config = $rawConfig | ConvertFrom-Json -ErrorAction Stop
        $script:DeviceKey = [string](Get-ConfigProperty $script:Config "deviceKey" "")
        $script:PharmacyId = [string](Get-ConfigProperty $script:Config "pharmacyId" "")
        $configuredLog = [string](Get-ConfigProperty $script:Config "appLogPath" $script:AppLogPath)
        if (![string]::IsNullOrWhiteSpace($configuredLog)) { $script:AppLogPath = $configuredLog }
        $configuredHeartbeat = [string](Get-ConfigProperty $script:Config "heartbeatPath" $script:HeartbeatPath)
        if (![string]::IsNullOrWhiteSpace($configuredHeartbeat)) { $script:HeartbeatPath = $configuredHeartbeat }
        $copy = $rawConfig | ConvertFrom-Json
        Redact-Object $copy
        Write-Utf8File (Join-Path $script:OutputDir "posm-config-redacted.json") ($copy | ConvertTo-Json -Depth 30)
    } catch {
        Add-DiagnosticError "POSM config" $_
        Write-Utf8File (Join-Path $script:OutputDir "posm-config-redacted.json") ("CONFIG ERROR: " + $_.Exception.GetBaseException().Message)
    }
}

function Initialize-WindowApi {
    if ("EpharmDiagnostics.Native" -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace EpharmDiagnostics {
    public delegate bool EnumWindowsDelegate(IntPtr hWnd, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
    public static class Native {
        [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsDelegate callback, IntPtr extraData);
        [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
        [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
        [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out Rect rect);
        [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    }
}
'@
}

function Save-WindowInventory {
    param([string]$Name)
    try {
        Initialize-WindowApi
        $targetIds = @{}
        foreach ($process in Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -match '(?i)CustomerDisplay|Epharm|zkassa|kass'
        }) { $targetIds[[int]$process.ProcessId] = $process.Name }
        $rows = New-Object 'System.Collections.Generic.List[string]'
        $callback = [EpharmDiagnostics.EnumWindowsDelegate]{
            param([IntPtr]$handle, [IntPtr]$extra)
            [uint32]$windowProcessId = 0
            [void][EpharmDiagnostics.Native]::GetWindowThreadProcessId($handle, [ref]$windowProcessId)
            if ($targetIds.ContainsKey([int]$windowProcessId)) {
                $title = New-Object Text.StringBuilder 1024
                [void][EpharmDiagnostics.Native]::GetWindowText($handle, $title, $title.Capacity)
                $rect = New-Object EpharmDiagnostics.Rect
                [void][EpharmDiagnostics.Native]::GetWindowRect($handle, [ref]$rect)
                $visible = [EpharmDiagnostics.Native]::IsWindowVisible($handle)
                [void]$rows.Add("pid=$windowProcessId; process=$($targetIds[[int]$windowProcessId]); hwnd=$handle; visible=$visible; rect=$($rect.Left),$($rect.Top),$($rect.Right),$($rect.Bottom); title=$title")
            }
            return $true
        }
        [void][EpharmDiagnostics.Native]::EnumWindows($callback, [IntPtr]::Zero)
        if ($rows.Count -eq 0) { [void]$rows.Add("No top-level POSM/Standard-N windows found.") }
        Write-Utf8File (Join-Path $script:OutputDir $Name) ($rows -join "`r`n")
    } catch {
        Write-Utf8File (Join-Path $script:OutputDir $Name) ("WINDOW ENUMERATION ERROR: " + $_.Exception.GetBaseException().Message)
    }
}

function Save-Screenshots {
    param([string]$FolderName)
    try {
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        $folder = Join-Path $script:OutputDir $FolderName
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
        $index = 0
        foreach ($screen in [System.Windows.Forms.Screen]::AllScreens) {
            $index++
            $bitmap = New-Object Drawing.Bitmap($screen.Bounds.Width, $screen.Bounds.Height, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
            $graphics = [Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.CopyFromScreen($screen.Bounds.Left, $screen.Bounds.Top, 0, 0, $screen.Bounds.Size)
                $bitmap.Save((Join-Path $folder ("screen-{0:D2}.png" -f $index)), [Drawing.Imaging.ImageFormat]::Png)
            } finally {
                $graphics.Dispose()
                $bitmap.Dispose()
            }
        }
    } catch {
        Write-Utf8File (Join-Path $script:OutputDir "$FolderName-error.txt") $_.Exception.ToString()
    }
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 3000)
    $client = New-Object Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (!$async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return "TIMEOUT" }
        $client.EndConnect($async)
        return "OPEN"
    } catch { return "FAILED: " + $_.Exception.GetBaseException().Message }
    finally { $client.Close() }
}

function Get-TlsCertificateReport {
    param([string]$HostName, [int]$Port = 443)
    $client = $null
    $ssl = $null
    $validationErrors = "unknown"
    try {
        $callback = { param($sender, $certificate, $chain, $errors) $script:tlsErrors = [string]$errors; return $true }
        $script:tlsErrors = "unknown"
        $client = New-Object Net.Sockets.TcpClient
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        $client.Connect($HostName, $Port)
        $ssl = New-Object Net.Security.SslStream($client.GetStream(), $false, $callback)
        $ssl.ReadTimeout = 5000
        $ssl.WriteTimeout = 5000
        $ssl.AuthenticateAsClient($HostName)
        $cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
        return "host=$HostName`:$Port; subject=$($cert.Subject); issuer=$($cert.Issuer); notBefore=$($cert.NotBefore.ToString('o')); notAfter=$($cert.NotAfter.ToString('o')); thumbprint=$($cert.Thumbprint); policyErrors=$script:tlsErrors; protocol=$($ssl.SslProtocol)"
    } catch { return "host=$HostName`:$Port; TLS FAILED: " + $_.Exception.GetBaseException().Message }
    finally {
        if ($null -ne $ssl) { $ssl.Dispose() }
        if ($null -ne $client) { $client.Close() }
    }
}

function Invoke-DiagnosticGet {
    param([string]$Url, [string]$DeviceKey = "", [int]$TimeoutSec = 8)
    $started = Get-Date
    try {
        $headers = @{}
        if (![string]::IsNullOrWhiteSpace($DeviceKey)) { $headers["X-Posm-Key"] = $DeviceKey }
        $response = Invoke-WebRequest -Uri $Url -Method Get -Headers $headers -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
        $elapsed = [int]((Get-Date) - $started).TotalMilliseconds
        $body = Protect-Text ([string]$response.Content)
        if ($body.Length -gt 12000) { $body = $body.Substring(0, 12000) + "`r`n[TRUNCATED]" }
        return [pscustomobject]@{ Url = $Url; Status = "HTTP $([int]$response.StatusCode)"; ElapsedMs = $elapsed; Body = $body }
    } catch {
        $elapsed = [int]((Get-Date) - $started).TotalMilliseconds
        $status = ""
        try { $status = "HTTP $([int]$_.Exception.Response.StatusCode)" } catch { }
        return [pscustomobject]@{ Url = $Url; Status = "FAILED $status"; ElapsedMs = $elapsed; Body = Protect-Text $_.Exception.GetBaseException().Message }
    }
}

function Save-NetworkReport {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    try {
        [void]$lines.Add("DNS:")
        foreach ($address in [Net.Dns]::GetHostAddresses("epharm.inkar.kz")) { [void]$lines.Add("  epharm.inkar.kz -> $address") }
    } catch { [void]$lines.Add("  DNS FAILED: $($_.Exception.GetBaseException().Message)") }
    [void]$lines.Add("TCP 443: $(Test-TcpPort 'epharm.inkar.kz' 443)")
    [void]$lines.Add("TCP 8060: $(Test-TcpPort 'epharm.inkar.kz' 8060)")
    [void]$lines.Add("TLS: $(Get-TlsCertificateReport 'epharm.inkar.kz' 443)")
    [void]$lines.Add("")

    $origins = New-Object 'System.Collections.Generic.List[string]'
    $primary = [string](Get-ConfigProperty $script:Config "backendBaseUrl" "")
    if (![string]::IsNullOrWhiteSpace($primary)) { [void]$origins.Add($primary.TrimEnd('/')) }
    foreach ($fallback in @(Get-ConfigProperty $script:Config "backendFallbackBaseUrls" @())) {
        $origin = ([string]$fallback).TrimEnd('/')
        if (![string]::IsNullOrWhiteSpace($origin) -and $origins -notcontains $origin) { [void]$origins.Add($origin) }
    }
    if ($origins.Count -eq 0) {
        [void]$origins.Add("https://epharm.inkar.kz")
        [void]$origins.Add("http://epharm.inkar.kz:8060")
    }
    foreach ($origin in $origins) {
        foreach ($request in @(
            [pscustomobject]@{ Path = "/api/health"; Protected = $false },
            [pscustomobject]@{ Path = "/api/posm/app/version?platform=win-x64"; Protected = $true },
            [pscustomobject]@{ Path = "/api/posm/playlists/active?pharmacyId=$([Uri]::EscapeDataString([string]$script:PharmacyId))"; Protected = $true }
        )) {
            $key = if ($request.Protected) { $script:DeviceKey } else { "" }
            $result = Invoke-DiagnosticGet -Url ($origin + $request.Path) -DeviceKey $key
            [void]$lines.Add("GET $($result.Url) -> $($result.Status); elapsedMs=$($result.ElapsedMs)")
            if (![string]::IsNullOrWhiteSpace($result.Body)) { [void]$lines.Add("  $($result.Body -replace "`r?`n", " ")") }
        }
        [void]$lines.Add("")
    }
    Write-Utf8File (Join-Path $script:OutputDir "network-and-backend.txt") (Protect-Text ($lines -join "`r`n"))
}

function Save-RecentEvents {
    try {
        $events = Get-WinEvent -FilterHashtable @{ LogName = "Application"; StartTime = (Get-Date).AddHours(-48) } -ErrorAction SilentlyContinue |
            Where-Object { $_.ProviderName -match '(?i)application error|\.net runtime|windows error reporting|customerdisplay|epharm' -or $_.Message -match '(?i)customerdisplay|epharm' } |
            Select-Object -First 200 TimeCreated, LevelDisplayName, ProviderName, Id, Message
        Write-Utf8File (Join-Path $script:OutputDir "windows-application-events.txt") (Protect-Text ($events | Format-List * | Out-String -Width 700))
    } catch { Add-DiagnosticError "Windows Application events" $_ }
    try {
        $events = Get-WinEvent -FilterHashtable @{ LogName = "Microsoft-Windows-TaskScheduler/Operational"; StartTime = (Get-Date).AddHours(-24) } -ErrorAction SilentlyContinue |
            Where-Object { $_.Message -match '(?i)EpharmPOSM' } |
            Select-Object -First 200 TimeCreated, LevelDisplayName, Id, Message
        Write-Utf8File (Join-Path $script:OutputDir "task-scheduler-events.txt") (Protect-Text ($events | Format-List * | Out-String -Width 700))
    } catch { }
    try {
        $detections = Get-MpThreatDetection -ErrorAction SilentlyContinue | Where-Object {
            $_.Resources -match '(?i)Epharm|CustomerDisplay'
        } | Select-Object -First 50 *
        Write-Utf8File (Join-Path $script:OutputDir "defender-events.txt") (Protect-Text ($detections | Format-List * | Out-String -Width 700))
    } catch { }
}

function Save-SanitizedSupportingFiles {
    foreach ($source in @(
        $script:AppLogPath,
        "C:\Epharm\install.log", "C:\Epharm\install-status.json", "C:\Epharm\watchdog.log",
        "C:\Epharm\crash.log", "C:\Epharm\standardn-log-paths.txt", "C:\Epharm\standardn-identity-diagnostics.txt"
    )) {
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        try {
            $item = Get-Item -LiteralPath $source
            $offset = [Math]::Max(0L, $item.Length - 2097152L)
            $content = Decode-Bytes (Get-SharedBytes $source $offset 2097152) "utf-8"
            Write-Utf8File (Join-Path $script:OutputDir ("support-" + [IO.Path]::GetFileName($source) + ".txt")) (Protect-Text $content)
        } catch { Add-DiagnosticError ("support file " + $source) $_ }
    }

    $options = Find-FilesBounded -Roots $script:StandardRoots -NamePattern '(?i)^options\.ini$' -MaxDepth 6 -MaxResults 20
    $index = 0
    foreach ($source in $options) {
        $index++
        try {
            $item = Get-Item -LiteralPath $source
            $content = Decode-Bytes (Get-SharedBytes $source 0 ([int][Math]::Min(524288L, $item.Length))) "windows-1251"
            Write-Utf8File (Join-Path $script:OutputDir ("standardn-options-{0:D2}.txt" -f $index)) (Protect-Text ("SOURCE: $source`r`n`r`n$content"))
        } catch { }
    }
}

function Get-FirebirdSnapshot {
    param([string]$Label)
    $lines = New-Object 'System.Collections.Generic.List[string]'
    [void]$lines.Add("$Label Standard-N Firebird snapshot")
    [void]$lines.Add("Read-only SELECT queries. Credentials are not included.")
    $connection = $null
    $reader = $null
    try {
        $appDir = if ($script:PosmExe) { Split-Path -Parent $script:PosmExe } else { "C:\Epharm\app" }
        $provider = Find-FilesBounded -Roots @($appDir) -NamePattern '(?i)^FirebirdSql\.Data\.FirebirdClient\.dll$' -MaxDepth 3 -MaxDirectories 200 -MaxResults 5 | Select-Object -First 1
        if ([string]::IsNullOrWhiteSpace($provider)) { throw "Firebird .NET provider was not found in the installed POSM directory" }
        $providerDir = Split-Path -Parent $provider
        foreach ($dependency in @("System.Runtime.CompilerServices.Unsafe.dll", "System.Threading.Tasks.Extensions.dll", "System.Memory.dll")) {
            $dependencyPath = Join-Path $providerDir $dependency
            if (Test-Path -LiteralPath $dependencyPath -PathType Leaf) {
                try { [void][Reflection.Assembly]::LoadFrom($dependencyPath) } catch { }
            }
        }
        [void][Reflection.Assembly]::LoadFrom($provider)

        $dbPath = [string](Get-ConfigProperty $script:Config "standardNDbPath" "")
        if ([string]::IsNullOrWhiteSpace($dbPath)) {
            $dbPath = Find-FilesBounded -Roots $script:StandardRoots -NamePattern '(?i)^ztrade(?:\.(?:fdb|gdb))?$' -MaxDepth 7 -MaxDirectories 3000 -MaxResults 10 | Select-Object -First 1
        }
        if ([string]::IsNullOrWhiteSpace($dbPath)) { throw "ztrade Firebird database path was not found" }

        $hostName = [string](Get-ConfigProperty $script:Config "standardNDbHost" "localhost")
        $port = [int](Get-ConfigProperty $script:Config "standardNDbPort" 3050)
        $user = [string](Get-ConfigProperty $script:Config "standardNDbUser" "SYSDBA")
        $password = [string](Get-ConfigProperty $script:Config "standardNDbPassword" "masterkey")
        $connectionString = "DataSource=$hostName;Port=$port;Database=$dbPath;UserID=$user;Password=$password;Charset=WIN1251;Pooling=false;ConnectionTimeout=3"
        $connection = New-Object FirebirdSql.Data.FirebirdClient.FbConnection($connectionString)
        $connection.Open()
        [void]$lines.Add("Connection: OK; host=$hostName; port=$port; database=$dbPath")
        [void]$lines.Add("")

        $queries = @(
            [pscustomobject]@{ Name = "RELEVANT_SCHEMA"; Sql = "SELECT TRIM(RDB`$RELATION_NAME) AS TABLE_NAME, TRIM(RDB`$FIELD_NAME) AS FIELD_NAME, RDB`$FIELD_POSITION AS FIELD_POSITION FROM RDB`$RELATION_FIELDS WHERE RDB`$RELATION_NAME IN ('ACTIVEUSERS','USERS','SESSIONS','SP`$SESSIONS','HUMAN_ACTION_LOGS','DOC_DETAIL_LOG','CASH_DOCS','DOCS') ORDER BY RDB`$RELATION_NAME, RDB`$FIELD_POSITION" },
            [pscustomobject]@{ Name = "ACTIVEUSERS"; Sql = "SELECT FIRST 50 * FROM ACTIVEUSERS" },
            [pscustomobject]@{ Name = "USERS"; Sql = "SELECT FIRST 100 * FROM USERS" },
            [pscustomobject]@{ Name = "SESSIONS"; Sql = "SELECT FIRST 50 * FROM SESSIONS" },
            [pscustomobject]@{ Name = "SP_SESSIONS"; Sql = 'SELECT FIRST 50 * FROM SP$SESSIONS' },
            [pscustomobject]@{ Name = "HUMAN_ACTION_LOGS"; Sql = "SELECT FIRST 50 * FROM HUMAN_ACTION_LOGS" },
            [pscustomobject]@{ Name = "DOC_DETAIL_LOG"; Sql = "SELECT FIRST 50 * FROM DOC_DETAIL_LOG" }
        )
        foreach ($query in $queries) {
            [void]$lines.Add("===== $($query.Name) =====")
            $command = $connection.CreateCommand()
            $command.CommandText = $query.Sql
            $command.CommandTimeout = 4
            try {
                $reader = $command.ExecuteReader()
                $rowCount = 0
                while ($reader.Read() -and $rowCount -lt 100) {
                    $rowCount++
                    $values = New-Object 'System.Collections.Generic.List[string]'
                    for ($i = 0; $i -lt $reader.FieldCount; $i++) {
                        $value = if ($reader.IsDBNull($i)) { "<null>" } else { [string]$reader.GetValue($i) }
                        if ($reader.GetName($i) -match '(?i)password|passwd|pwd|token|secret|signature|credential|key') { $value = "[REDACTED]" }
                        [void]$values.Add("$($reader.GetName($i))=$value")
                    }
                    [void]$lines.Add($values -join "; ")
                }
                if ($rowCount -eq 0) { [void]$lines.Add("<no rows>") }
            } catch { [void]$lines.Add("QUERY ERROR: $($_.Exception.GetBaseException().Message)") }
            finally {
                if ($null -ne $reader) { $reader.Dispose(); $reader = $null }
                if ($null -ne $command) { $command.Dispose() }
            }
            [void]$lines.Add("")
        }
    } catch {
        [void]$lines.Add("SNAPSHOT UNAVAILABLE: $($_.Exception.GetBaseException().Message)")
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $connection) { $connection.Dispose() }
    }
    return Protect-Text ($lines -join "`r`n")
}

function Save-CashLogTails {
    param([string[]]$CashLogs)
    $folder = Join-Path $script:OutputDir "cash-log-tails-before"
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $index = 0
    foreach ($path in $CashLogs) {
        $index++
        try {
            $item = Get-Item -LiteralPath $path
            $offset = [Math]::Max(0L, $item.Length - 1048576L)
            $text = Decode-Bytes (Get-SharedBytes $path $offset 1048576) "windows-1251"
            Write-Utf8File (Join-Path $folder ("zkassa-{0:D2}.txt" -f $index)) (Protect-Text ("SOURCE: $path`r`nLENGTH: $($item.Length)`r`nLAST WRITE UTC: $($item.LastWriteTimeUtc.ToString('o'))`r`n`r`n$text"))
        } catch {
            Write-Utf8File (Join-Path $folder ("zkassa-{0:D2}-error.txt" -f $index)) ("SOURCE: $path`r`nERROR: $($_.Exception.GetBaseException().Message)")
        }
    }
}

function Get-MatchedLines {
    param([string]$Text, [string]$Pattern, [int]$Maximum = 1000)
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    return @($Text -split "`r?`n" | Where-Object { $_ -match $Pattern } | Select-Object -Last $Maximum)
}

function Save-ScanDeltasAndSummary {
    param(
        [hashtable]$BeforeCash,
        [object]$BeforePosm,
        [string[]]$AfterCashLogs,
        [object]$AfterPosm
    )
    $deltaFolder = Join-Path $script:OutputDir "scan-delta"
    New-Item -ItemType Directory -Path $deltaFolder -Force | Out-Null
    $cashChanged = $false
    $addLineSeen = $false
    $deleteLineSeen = $false
    $allCashDelta = New-Object 'System.Collections.Generic.List[string]'
    $index = 0
    foreach ($path in $AfterCashLogs) {
        $index++
        $before = if ($BeforeCash.ContainsKey($path.ToLowerInvariant())) { $BeforeCash[$path.ToLowerInvariant()] } else { $null }
        $after = Get-FileState $path
        $changed = $null -eq $before -or !$before.Exists -or $after.Length -ne $before.Length -or $after.LastWriteUtc -ne $before.LastWriteUtc
        if ($changed) { $cashChanged = $true }
        $delta = if ($changed) { Get-FileDelta $before $after "windows-1251" } else { "" }
        $safeDelta = Protect-Text $delta
        if ($safeDelta -match '(?i)Add2Cheque') { $addLineSeen = $true }
        if ($safeDelta -match '(?i)Add2Cheque.*\(delete\)') { $deleteLineSeen = $true }
        [void]$allCashDelta.Add($safeDelta)
        Write-Utf8File (Join-Path $deltaFolder ("cash-{0:D2}.txt" -f $index)) ("SOURCE: $path`r`nCHANGED: $(Format-Bool $changed)`r`nBEFORE LENGTH: $(if($before){$before.Length}else{'n/a'})`r`nAFTER LENGTH: $($after.Length)`r`n`r`n$safeDelta")
    }

    $posmChanged = $AfterPosm.Exists -and ($null -eq $BeforePosm -or !$BeforePosm.Exists -or $AfterPosm.Length -ne $BeforePosm.Length -or $AfterPosm.LastWriteUtc -ne $BeforePosm.LastWriteUtc)
    $posmDelta = if ($posmChanged) { Get-FileDelta $BeforePosm $AfterPosm "utf-8" } else { "" }
    $posmDelta = Protect-Text $posmDelta
    Write-Utf8File (Join-Path $deltaFolder "posm-log.txt") ("SOURCE: $script:AppLogPath`r`nCHANGED: $(Format-Bool $posmChanged)`r`nBEFORE LENGTH: $(if($BeforePosm){$BeforePosm.Length}else{'n/a'})`r`nAFTER LENGTH: $($AfterPosm.Length)`r`n`r`n$posmDelta")

    $markerPattern = '(?i)Считана строка:|POSM готов принимать сканы|POSM scan parse failed|Скан товара:|Добавили новую позицию|Обновили позицию|POSM recommend request|POSM recommend response|POSM recommend candidate|POSM popup|recommend:|heartbeat:|Backend-плейлист|клиентский экран|Мониторов:'
    $markers = Get-MatchedLines $posmDelta $markerPattern
    Write-Utf8File (Join-Path $deltaFolder "posm-chain-only.txt") ($markers -join "`r`n")

    $posmRead = $posmDelta -match '(?i)Считана строка:.*Add2Cheque'
    $parseFailed = $posmDelta -match '(?i)POSM scan parse failed'
    $receiptUpdated = $posmDelta -match '(?i)Добавили новую позицию|Обновили позицию'
    $recommendRequest = $posmDelta -match '(?i)POSM recommend request'
    $recommendResponse = $posmDelta -match '(?i)POSM recommend response'
    $recommendCandidate = $posmDelta -match '(?i)POSM recommend candidate'
    $popupShown = $posmDelta -match '(?i)POSM popup показан'
    $popupFailed = $posmDelta -match '(?i)POSM popup не удалось открыть'
    $networkError = $posmDelta -match '(?i)recommend: (HTTP|таймаут|временно|TLS|SSL|нет связи)'
    $responseZero = $posmDelta -match '(?i)POSM recommend response.*recs=0'
    $nothingToShow = $posmDelta -match '(?i)POSM recommend: показывать нечего'
    $requestSkipped = $posmDelta -match '(?i)POSM recommend skipped'

    Add-Summary "CONTROLLED SCAN CHAIN"
    Add-Summary "Cash log changed: $(Format-Bool $cashChanged)"
    Add-Summary "Add2Cheque appeared in cash-log delta: $(Format-Bool $addLineSeen)"
    Add-Summary "Delete event appeared in cash-log delta: $(Format-Bool $deleteLineSeen)"
    Add-Summary "POSM application log changed: $(Format-Bool $posmChanged)"
    Add-Summary "POSM read the Add2Cheque line: $(Format-Bool $posmRead)"
    Add-Summary "POSM parser failed: $(Format-Bool $parseFailed)"
    Add-Summary "POSM receipt model updated: $(Format-Bool $receiptUpdated)"
    Add-Summary "Recommendation request sent: $(Format-Bool $recommendRequest)"
    Add-Summary "Recommendation response received: $(Format-Bool $recommendResponse)"
    Add-Summary "Recommendation candidate received: $(Format-Bool $recommendCandidate)"
    Add-Summary "Recommendation popup loaded: $(Format-Bool $popupShown)"
    Add-Summary "Recommendation popup failed to open: $(Format-Bool $popupFailed)"
    Add-Summary "Recommendation network error logged: $(Format-Bool $networkError)"
    Add-Summary "Backend returned zero recommendations: $(Format-Bool $responseZero)"
    Add-Summary "POSM suppressed duplicate/empty UI result: $(Format-Bool $nothingToShow)"
    Add-Summary "Recommendation request was skipped as already running: $(Format-Bool $requestSkipped)"
    Add-Summary ""
    Add-Summary "LIKELY BREAKPOINT"
    if (!$cashChanged -or !$addLineSeen) {
        Add-Summary "Standard-N did not append a recognizable Add2Cheque event to any discovered zkassa log. Inspect cash-log paths and Standard-N logging/TMS scripts."
    } elseif (!$posmRead) {
        Add-Summary "Standard-N wrote the event, but the running POSM process did not read it. Verify the exact running build, listener path, file permissions, and duplicate processes."
    } elseif ($parseFailed -or !$receiptUpdated) {
        Add-Summary "POSM read the event but did not convert it into a receipt item. The captured Add2Cheque format must be supported by the parser."
    } elseif ($requestSkipped) {
        Add-Summary "POSM received the scan while an earlier recommendation request was still running. The full delta shows whether this is a transient overlap or a stuck request."
    } elseif (!$recommendRequest) {
        Add-Summary "The receipt updated, but POSM did not start a recommendation request. Inspect trigger/debounce/session logic."
    } elseif ($networkError -or !$recommendResponse) {
        Add-Summary "POSM sent a recommendation request but did not receive a usable response. Inspect network, TLS, device authentication, and backend availability."
    } elseif ($responseZero -or !$recommendCandidate) {
        Add-Summary "Backend answered but did not return a matching rule. Compare the captured iPartID/barcode/name with the active campaign."
    } elseif ($nothingToShow) {
        Add-Summary "POSM received recommendations but suppressed the popup as already shown/empty for the current receipt. Repeat with a new empty receipt and no trigger item present before the scan."
    } elseif (!$popupShown -or $popupFailed) {
        Add-Summary "Backend returned a recommendation, but the WPF popup did not load. Inspect window creation, desktop session, screen selection, and coordinates."
    } else {
        Add-Summary "The logged end-to-end chain completed. Use after-scan screenshots and window coordinates to verify visibility on the physical displays."
    }
    Add-Summary ""
}

function Write-FinalSummary {
    Add-Summary "STATIC STATE"
    Add-Summary "POSM executable found: $(Format-Bool (![string]::IsNullOrWhiteSpace($script:PosmExe)))"
    Add-Summary "POSM process count: $(@(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '(?i)CustomerDisplay|Epharm' }).Count)"
    Add-Summary "Pharmacy ID present: $(Format-Bool (![string]::IsNullOrWhiteSpace($script:PharmacyId)))"
    Add-Summary "Discovered Standard-N roots: $($script:StandardRoots.Count)"
    Add-Summary "Discovered zkassa logs: $($script:CashLogs.Count)"
    try {
        Add-Type -AssemblyName System.Windows.Forms
        Add-Summary "Windows display count: $([System.Windows.Forms.Screen]::AllScreens.Count)"
    } catch { Add-Summary "Windows display count: unavailable" }
    $heartbeat = Get-FileState $script:HeartbeatPath
    if ($heartbeat.Exists) {
        $age = [Math]::Round(((Get-Date).ToUniversalTime() - $heartbeat.LastWriteUtc).TotalSeconds, 1)
        Add-Summary "UI heartbeat present: YES; ageSeconds=$age"
    } else { Add-Summary "UI heartbeat present: NO" }
    Add-Summary ""
    if ($script:Errors.Count -gt 0) {
        Add-Summary "COLLECTOR WARNINGS"
        foreach ($errorLine in $script:Errors) { Add-Summary $errorLine }
        Add-Summary ""
    }
    Add-Summary "The ZIP contains screen captures from the controlled test and may show the visible Standard-N test receipt."
    Add-Summary "POSM device keys and database passwords are redacted. The collector did not restart or modify POSM or Standard-N."
    Write-Utf8File (Join-Path $script:OutputDir "SUMMARY.txt") ($script:Summary -join "`r`n")
}

try {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $root = "C:\Epharm\diagnostics"
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $script:OutputDir = Join-Path $root "Epharm-Auezova134-Diagnostics-$stamp"
    New-Item -ItemType Directory -Path $script:OutputDir -Force | Out-Null
    $zipWorking = "$script:OutputDir.zip"

    Write-Host ""
    Write-Host "Epharm pharmacy diagnostics" -ForegroundColor Cyan
    Write-Host "Read-only mode: POSM and Standard-N will not be restarted or changed." -ForegroundColor Green
    Write-Host "Output: $script:OutputDir" -ForegroundColor DarkGray
    Write-Host ""

    Add-Summary "Epharm pharmacy integration diagnostics"
    Add-Summary "Collected: $([DateTimeOffset]::Now.ToString('o'))"
    Add-Summary "Computer: $env:COMPUTERNAME"
    Add-Summary "Collector: $([Security.Principal.WindowsIdentity]::GetCurrent().Name)"
    Add-Summary ""

    Write-Host "[1/6] Reading POSM configuration, processes, tasks and displays..." -ForegroundColor Cyan
    Save-RedactedConfig
    $processes = Get-RelevantProcesses
    $script:PosmExe = Get-PosmExecutable $processes
    $script:StandardRoots = @(Get-StandardRoots $processes $script:Config)
    $script:CashLogs = @(Find-CashLogs $script:StandardRoots $script:Config)
    Save-SystemInventory $processes
    Save-TaskInventory
    Save-PosmInventory
    Save-WindowInventory "windows-before-scan.txt"
    Save-Screenshots "screens-before-scan"

    Write-Host "[2/6] Checking zkassa access and collecting safe log/config evidence..." -ForegroundColor Cyan
    Write-Utf8File (Join-Path $script:OutputDir "standardn-search-roots.txt") ($script:StandardRoots -join "`r`n")
    Write-Utf8File (Join-Path $script:OutputDir "cash-log-access.txt") ((Get-FileAccessReport $script:CashLogs) -join "`r`n")
    Save-CashLogTails $script:CashLogs
    Save-SanitizedSupportingFiles
    Save-RecentEvents

    Write-Host "[3/6] Checking DNS, TLS and both backend addresses..." -ForegroundColor Cyan
    Save-NetworkReport

    Write-Host "[4/6] Capturing optional read-only Standard-N identity/database snapshot..." -ForegroundColor Cyan
    $dbBefore = Get-FirebirdSnapshot "Before scan"
    Write-Utf8File (Join-Path $script:OutputDir "standardn-database-before.txt") $dbBefore

    $beforeCash = @{}
    foreach ($path in $script:CashLogs) { $beforeCash[$path.ToLowerInvariant()] = Get-FileState $path }
    $beforePosm = Get-FileState $script:AppLogPath

    Write-Host ""
    Write-Host "CONTROLLED TEST" -ForegroundColor Yellow
    Write-Host "1. Keep Standard-N open and use an empty/new test receipt." -ForegroundColor Yellow
    Write-Host "2. Scan or add exactly ONE trigger product (for example Ivatherm)." -ForegroundColor Yellow
    Write-Host "3. Wait 5 seconds, return to this window, then press ENTER." -ForegroundColor Yellow
    Write-Host "Do not close POSM or Standard-N." -ForegroundColor Yellow
    [void](Read-Host "Press ENTER only after the product was added")
    Write-Host "Waiting 12 seconds for POSM/backend/UI completion..." -ForegroundColor Cyan
    Start-Sleep -Seconds 12

    Write-Host "[5/6] Capturing the exact post-scan delta and screen state..." -ForegroundColor Cyan
    $script:CashLogs = @(Find-CashLogs $script:StandardRoots $script:Config)
    $afterPosm = Get-FileState $script:AppLogPath
    Save-ScanDeltasAndSummary $beforeCash $beforePosm $script:CashLogs $afterPosm
    Save-WindowInventory "windows-after-scan.txt"
    Save-Screenshots "screens-after-scan"
    $dbAfter = Get-FirebirdSnapshot "After scan"
    Write-Utf8File (Join-Path $script:OutputDir "standardn-database-after.txt") $dbAfter

    Write-Host "[6/6] Building the final ZIP..." -ForegroundColor Cyan
    Write-FinalSummary
    if (Test-Path -LiteralPath $zipWorking) { Remove-Item -LiteralPath $zipWorking -Force }
    Compress-Archive -LiteralPath $script:OutputDir -DestinationPath $zipWorking -CompressionLevel Optimal -Force

    $desktop = $env:EPHARM_DIAG_CALLER_DESKTOP
    if ([string]::IsNullOrWhiteSpace($desktop) -or !(Test-Path -LiteralPath $desktop -PathType Container)) {
        $desktop = [Environment]::GetFolderPath("Desktop")
    }
    $finalZip = $zipWorking
    if (![string]::IsNullOrWhiteSpace($desktop) -and (Test-Path -LiteralPath $desktop -PathType Container)) {
        $finalZip = Join-Path $desktop ([IO.Path]::GetFileName($zipWorking))
        Copy-Item -LiteralPath $zipWorking -Destination $finalZip -Force
    }
    Write-Host ""
    Write-Host "DONE. Send this ZIP unchanged to the developer:" -ForegroundColor Green
    Write-Host $finalZip -ForegroundColor Green
    Write-Host "The ZIP contains before/after screenshots from the controlled test." -ForegroundColor Yellow
    try { Start-Process explorer.exe -ArgumentList "/select,`"$finalZip`"" } catch { }
    exit 0
} catch {
    $fatal = $_.Exception.ToString()
    try {
        if ($script:OutputDir) { Write-Utf8File (Join-Path $script:OutputDir "FATAL-ERROR.txt") (Protect-Text $fatal) }
    } catch { }
    Write-Host ""
    Write-Host "DIAGNOSTICS FAILED: $($_.Exception.GetBaseException().Message)" -ForegroundColor Red
    if ($script:OutputDir) { Write-Host "Partial evidence: $script:OutputDir" -ForegroundColor Yellow }
    exit 1
}
