#requires -version 5.1
<#
  Read-only diagnostic collector for a real Standard-N/POSM workstation.

  The resulting ZIP intentionally redacts credentials. It captures only the evidence needed to
  determine why the active pharmacist cannot be resolved: POSM logs, Standard-N process paths,
  log tails, configuration locations, database candidates, services and local database ports.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$desktop = [Environment]::GetFolderPath("Desktop")
$outputDir = Join-Path $desktop "Epharm-POSM-Diagnostics-$stamp"
$zipPath = "$outputDir.zip"
$utf8 = New-Object System.Text.UTF8Encoding($false)

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

function Write-Utf8File {
    param([string]$Path, [object]$Value)
    $text = if ($Value -is [string]) { $Value } else { $Value | Out-String -Width 500 }
    [System.IO.File]::WriteAllText($Path, $text, $utf8)
}

function Redact-Text {
    param([string[]]$Lines)
    return $Lines | ForEach-Object {
        $_ -replace '(?i)(password|passwd|pwd|secret|token|device[_-]?key)\s*[:=]\s*("[^"]*"|''[^'']*''|[^;\s]+)', '$1=***REDACTED***'
    }
}

function Add-Root {
    param([System.Collections.Generic.List[string]]$Roots, [string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    try {
        $full = [System.IO.Path]::GetFullPath($Path)
        $driveRoot = [System.IO.Path]::GetPathRoot($full).TrimEnd([char]92)
        if ($full.TrimEnd([char]92) -ieq $driveRoot) { return }
        if ((Test-Path -LiteralPath $full -PathType Container) -and -not $Roots.Contains($full)) {
            $Roots.Add($full)
        }
    } catch { }
}

$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("Epharm POSM / Standard-N diagnostics")
$summary.Add("Collected: $([DateTimeOffset]::Now.ToString('o'))")
$summary.Add("Computer: $env:COMPUTERNAME")
$summary.Add("Collector user: $([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)")
$summary.Add("")

try {
    $os = Get-CimInstance Win32_OperatingSystem
    $cs = Get-CimInstance Win32_ComputerSystem
    $summary.Add("Windows: $($os.Caption) $($os.Version) build $($os.BuildNumber)")
    $summary.Add("Interactive user: $($cs.UserName)")
} catch {
    $summary.Add("Windows inventory error: $($_.Exception.Message)")
}

try {
    Add-Type -AssemblyName System.Windows.Forms
    $screens = [System.Windows.Forms.Screen]::AllScreens
    $summary.Add("Monitors: $($screens.Count)")
    foreach ($screen in $screens) {
        $summary.Add("  $($screen.DeviceName): $($screen.Bounds.Width)x$($screen.Bounds.Height), primary=$($screen.Primary)")
    }
} catch {
    $summary.Add("Monitor inventory error: $($_.Exception.Message)")
}

$posmFiles = @(
    "C:\Epharm\customerdisplay.log",
    "C:\Epharm\crash.log",
    "C:\Epharm\install.log",
    "C:\Epharm\install-status.json",
    "C:\Epharm\heartbeat.txt",
    "C:\Epharm\standardn-identity-diagnostics.txt"
)
foreach ($path in $posmFiles) {
    try {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $raw = Get-Content -LiteralPath $path -Raw -ErrorAction Stop
            $safe = (Redact-Text @($raw)) -join "`r`n"
            Write-Utf8File (Join-Path $outputDir ([System.IO.Path]::GetFileName($path))) $safe
        }
    } catch { }
}

try {
    $posmBinary = $null
    try {
        $task = Get-ScheduledTask -TaskName "EpharmPOSM" -ErrorAction Stop
        $posmBinary = $task.Actions | ForEach-Object {
            [Environment]::ExpandEnvironmentVariables([string]$_.Execute).Trim('"')
        } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    } catch { }
    if (-not $posmBinary) {
        $posmBinary = @(
            foreach ($root in @("C:\Epharm\app-prod", "C:\Epharm\app-dev", "C:\Epharm\app")) {
                if (Test-Path -LiteralPath $root -PathType Container) {
                    Get-ChildItem -LiteralPath $root -Filter "CustomerDisplay.exe" -File -Recurse -ErrorAction SilentlyContinue
                }
            }
        ) | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if ($posmBinary) {
        $version = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($posmBinary)
        $summary.Add("POSM binary: $posmBinary")
        $summary.Add("POSM version: $($version.FileVersion)")
    } else {
        $summary.Add("POSM binary: not found in the scheduled task or versioned app folders")
    }
} catch {
    $summary.Add("POSM version error: $($_.Exception.Message)")
}

$configPath = "C:\Epharm\posm.json"
try {
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($secretName in @("deviceKey", "standardNDbPassword")) {
            if ($null -ne $config.PSObject.Properties[$secretName]) {
                $config.$secretName = "***REDACTED***"
            }
        }
        Write-Utf8File (Join-Path $outputDir "posm.redacted.json") ($config | ConvertTo-Json -Depth 20)
    }
} catch {
    Write-Utf8File (Join-Path $outputDir "posm-config-error.txt") $_.Exception.ToString()
}

try {
    $tasks = Get-ScheduledTask -TaskName "EpharmPOSM*" -ErrorAction SilentlyContinue
    $taskRows = foreach ($task in $tasks) {
        $info = $task | Get-ScheduledTaskInfo
        [PSCustomObject]@{
            TaskName = $task.TaskName
            State = $task.State
            LastRunTime = $info.LastRunTime
            LastTaskResult = $info.LastTaskResult
            NextRunTime = $info.NextRunTime
            Actions = ($task.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join " | "
        }
    }
    $taskText = $taskRows | Format-List * | Out-String -Width 500
    Write-Utf8File (Join-Path $outputDir "scheduled-tasks.txt") ((Redact-Text @($taskText)) -join "`r`n")
} catch {
    Write-Utf8File (Join-Path $outputDir "scheduled-tasks.txt") $_.Exception.ToString()
}

$processes = @()
try {
    $processes = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '(?i)customerdisplay|kass|standart|manager|apteka|firebird|fbserver|fbguard|sqlservr|shtrih|drvfr|fptr|fiscal|kkm|ofd'
    })
    $processText = (
        $processes | Select-Object ProcessId, Name, ExecutablePath, CommandLine |
        Format-List * | Out-String -Width 500
    )
    Write-Utf8File (Join-Path $outputDir "relevant-processes.txt") ((Redact-Text @($processText)) -join "`r`n")
} catch {
    Write-Utf8File (Join-Path $outputDir "relevant-processes.txt") $_.Exception.ToString()
}

try {
    $services = Get-CimInstance Win32_Service | Where-Object {
        $_.Name -match '(?i)firebird|interbase|mssql|sqlserver|standart|kass|shtrih|fptr|fiscal|kkm|ofd' -or
        $_.DisplayName -match '(?i)firebird|interbase|sql server|standart|kass|shtrih|fptr|fiscal|kkm|ofd'
    }
    $serviceText = (
        $services | Select-Object Name, DisplayName, State, StartMode, PathName |
        Format-Table -AutoSize | Out-String -Width 500
    )
    Write-Utf8File (Join-Path $outputDir "relevant-services.txt") ((Redact-Text @($serviceText)) -join "`r`n")
} catch {
    Write-Utf8File (Join-Path $outputDir "relevant-services.txt") $_.Exception.ToString()
}

# Read-only inventory only. Do not pause Spooler, open a COM port or instantiate a fiscal driver.
try {
    $printerRows = Get-CimInstance Win32_Printer | Select-Object `
        Name, DriverName, PortName, PrintProcessor, SpoolEnabled, Direct, WorkOffline, Default, Local, Network
    Write-Utf8File (Join-Path $outputDir "printers-and-ports.txt") `
        ($printerRows | Format-List * | Out-String -Width 500)
} catch {
    Write-Utf8File (Join-Path $outputDir "printers-and-ports.txt") $_.Exception.ToString()
}

try {
    $serialRows = Get-CimInstance Win32_SerialPort | Select-Object `
        DeviceID, Name, Description, ProviderType, PNPDeviceID, Status
    $fiscalDevices = Get-CimInstance Win32_PnPEntity | Where-Object {
        $_.Name -match '(?i)shtrih|штрих|fiscal|фиск|kkm|ккм|ofd|атол|usb.serial|virtual com'
    } | Select-Object Name, Manufacturer, PNPDeviceID, Status
    $deviceText = @(
        "SERIAL PORTS:",
        ($serialRows | Format-List * | Out-String -Width 500),
        "FISCAL-LIKE PNP DEVICES:",
        ($fiscalDevices | Format-List * | Out-String -Width 500)
    ) -join "`r`n"
    Write-Utf8File (Join-Path $outputDir "fiscal-devices.txt") $deviceText
} catch {
    Write-Utf8File (Join-Path $outputDir "fiscal-devices.txt") $_.Exception.ToString()
}

try {
    $comRows = foreach ($view in @("Registry::HKEY_CLASSES_ROOT", "HKLM:\SOFTWARE\Classes", "HKLM:\SOFTWARE\WOW6432Node\Classes")) {
        if (!(Test-Path -LiteralPath $view)) { continue }
        Get-ChildItem -LiteralPath $view -ErrorAction SilentlyContinue | Where-Object {
            $_.PSChildName -match '(?i)shtrih|drvfr|fptr|fiscal|kkm|ofd'
        } | ForEach-Object {
            $defaultValue = $null
            $clsid = $null
            try { $defaultValue = $_.GetValue("") } catch { }
            try { $clsid = (Get-Item -LiteralPath (Join-Path $_.PSPath "CLSID") -ErrorAction Stop).GetValue("") } catch { }
            [PSCustomObject]@{
                RegistryView = $view
                ProgId = $_.PSChildName
                Description = $defaultValue
                Clsid = $clsid
            }
        }
    }
    Write-Utf8File (Join-Path $outputDir "fiscal-com-registrations.txt") `
        ($comRows | Sort-Object ProgId -Unique | Format-Table -AutoSize | Out-String -Width 500)
} catch {
    Write-Utf8File (Join-Path $outputDir "fiscal-com-registrations.txt") $_.Exception.ToString()
}

try {
    $ports = foreach ($port in @(3050, 1433, 1434)) {
        try {
            $test = Test-NetConnection -ComputerName "localhost" -Port $port -WarningAction SilentlyContinue
            [PSCustomObject]@{ Port = $port; Open = $test.TcpTestSucceeded; RemoteAddress = $test.RemoteAddress }
        } catch {
            [PSCustomObject]@{ Port = $port; Open = $false; RemoteAddress = "test error" }
        }
    }
    Write-Utf8File (Join-Path $outputDir "database-ports.txt") ($ports | Format-Table -AutoSize | Out-String -Width 500)
} catch { }

$roots = New-Object 'System.Collections.Generic.List[string]'
foreach ($known in @(
    "C:\Standart-N", "C:\Standart-N_DEMO", "C:\StandartN", "C:\Standart_N",
    "C:\Program Files\Standart-N", "C:\Program Files (x86)\Standart-N",
    "C:\Program Files (x86)\Standart_N"
)) { Add-Root $roots $known }

foreach ($process in $processes) {
    try {
        if (-not [string]::IsNullOrWhiteSpace($process.ExecutablePath)) {
            $dir = Split-Path -Parent $process.ExecutablePath
            Add-Root $roots $dir
            Add-Root $roots (Split-Path -Parent $dir)
        }
    } catch { }
}

$shortcutRows = New-Object System.Collections.Generic.List[object]
try {
    $shell = New-Object -ComObject WScript.Shell
    $shortcutRoots = @(
        [Environment]::GetFolderPath("Desktop"),
        [Environment]::GetFolderPath("CommonDesktopDirectory"),
        [Environment]::GetFolderPath("StartMenu"),
        [Environment]::GetFolderPath("CommonStartMenu")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique

    foreach ($shortcutRoot in $shortcutRoots) {
        foreach ($link in Get-ChildItem -LiteralPath $shortcutRoot -Filter "*.lnk" -File -Recurse -ErrorAction SilentlyContinue) {
            try {
                $shortcut = $shell.CreateShortcut($link.FullName)
                if ($link.Name -match '(?i)kass|standart|manager|apteka' -or
                    $shortcut.TargetPath -match '(?i)kass|standart|manager|apteka') {
                    $shortcutRows.Add([PSCustomObject]@{
                        Link = $link.FullName
                        Target = $shortcut.TargetPath
                        Arguments = $shortcut.Arguments
                        WorkingDirectory = $shortcut.WorkingDirectory
                    })
                    $targetDir = Split-Path -Parent $shortcut.TargetPath
                    Add-Root $roots $targetDir
                    Add-Root $roots (Split-Path -Parent $targetDir)
                }
            } catch { }
        }
    }
} catch { }
$shortcutText = (
    $shortcutRows | Format-List * | Out-String -Width 500
)
Write-Utf8File (Join-Path $outputDir "standardn-shortcuts.txt") ((Redact-Text @($shortcutText)) -join "`r`n")

$knownLogs = @(
    "C:\Standart-N\Kassir\zkassa.log",
    "C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log"
)
foreach ($knownLog in $knownLogs) {
    if (Test-Path -LiteralPath $knownLog -PathType Leaf) {
        Add-Root $roots (Split-Path -Parent $knownLog)
        Add-Root $roots (Split-Path -Parent (Split-Path -Parent $knownLog))
    }
}

$rootList = @($roots | Select-Object -Unique)
Write-Utf8File (Join-Path $outputDir "standardn-search-roots.txt") ($rootList -join "`r`n")

$optionsFiles = New-Object System.Collections.Generic.List[string]
$cashLogs = New-Object System.Collections.Generic.List[string]
$databaseFiles = New-Object System.Collections.Generic.List[string]
$fiscalDriverFiles = New-Object System.Collections.Generic.List[string]
foreach ($root in $rootList) {
    try {
        foreach ($file in Get-ChildItem -LiteralPath $root -File -Recurse -ErrorAction SilentlyContinue) {
            if ($file.Name -ieq "options.ini" -and -not $optionsFiles.Contains($file.FullName)) {
                $optionsFiles.Add($file.FullName)
            }
            if ($file.Name -ieq "zkassa.log" -and -not $cashLogs.Contains($file.FullName)) {
                $cashLogs.Add($file.FullName)
            }
            if ($file.Name -match '(?i)^ztrade(?:\.(?:fdb|gdb))?$|\.(?:fdb|gdb|mdf|sdf)$') {
                if (-not $databaseFiles.Contains($file.FullName)) { $databaseFiles.Add($file.FullName) }
            }
            if ($file.Name -match '(?i)shtrih|drvfr|fptr|fiscal|kkm|ofd' -and
                $file.Extension -match '(?i)^\.(dll|exe|ocx|tlb|ini|json|xml)$') {
                if (-not $fiscalDriverFiles.Contains($file.FullName)) { $fiscalDriverFiles.Add($file.FullName) }
            }
        }
    } catch { }
}

try {
    $driverRows = foreach ($path in @($fiscalDriverFiles | Select-Object -First 300)) {
        try {
            $item = Get-Item -LiteralPath $path -ErrorAction Stop
            $version = $item.VersionInfo
            $hash = if ($item.Length -le 100MB) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash } else { "SKIPPED_GT_100MB" }
            [PSCustomObject]@{
                Path = $path
                Size = $item.Length
                Product = $version.ProductName
                ProductVersion = $version.ProductVersion
                FileVersion = $version.FileVersion
                Company = $version.CompanyName
                Sha256 = $hash
            }
        } catch {
            [PSCustomObject]@{ Path = $path; Error = $_.Exception.Message }
        }
    }
    Write-Utf8File (Join-Path $outputDir "fiscal-driver-files.txt") `
        ($driverRows | Format-List * | Out-String -Width 500)
} catch {
    Write-Utf8File (Join-Path $outputDir "fiscal-driver-files.txt") $_.Exception.ToString()
}

$optionsDir = Join-Path $outputDir "options"
New-Item -ItemType Directory -Path $optionsDir -Force | Out-Null
$optionIndex = 0
foreach ($optionsPath in $optionsFiles) {
    $optionIndex++
    try {
        $lines = Get-Content -LiteralPath $optionsPath -Encoding Default
        $redacted = @("SOURCE: $optionsPath", "") + @(Redact-Text $lines)
        Write-Utf8File (Join-Path $optionsDir ("options-{0:D2}.txt" -f $optionIndex)) ($redacted -join "`r`n")
    } catch {
        Write-Utf8File (Join-Path $optionsDir ("options-{0:D2}-error.txt" -f $optionIndex)) "$optionsPath`r`n$($_.Exception)"
    }
}

$logsDir = Join-Path $outputDir "cash-logs"
New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
$logIndex = 0
foreach ($cashLog in $cashLogs) {
    $logIndex++
    try {
        $tail = Get-Content -LiteralPath $cashLog -Encoding Default -Tail 500
        $content = @("SOURCE: $cashLog", "LAST WRITE: $((Get-Item -LiteralPath $cashLog).LastWriteTime.ToString('o'))", "") + @($tail)
        Write-Utf8File (Join-Path $logsDir ("zkassa-tail-{0:D2}.txt" -f $logIndex)) ($content -join "`r`n")
    } catch {
        Write-Utf8File (Join-Path $logsDir ("zkassa-tail-{0:D2}-error.txt" -f $logIndex)) "$cashLog`r`n$($_.Exception)"
    }
}

$identityPattern = '(?i)фармацевт|кассир|продавец|пользователь|cashier|kassir|operator|seller|user[_ -]?id|id[_ -]?user|user[_ -]?name|activeusers|login'
$identityEvidence = New-Object System.Collections.Generic.List[string]
foreach ($identityLog in @("C:\Epharm\customerdisplay.log") + @($cashLogs)) {
    try {
        if (!(Test-Path -LiteralPath $identityLog -PathType Leaf)) { continue }
        $identityEvidence.Add("===== SOURCE: $identityLog =====")
        $matches = Get-Content -LiteralPath $identityLog -Encoding Default -Tail 5000 |
            Select-String -Pattern $identityPattern |
            Select-Object -Last 500
        if ($matches.Count -eq 0) {
            $identityEvidence.Add("No identity-related lines found in the last 5000 lines.")
        } else {
            foreach ($match in $matches) { $identityEvidence.Add($match.Line) }
        }
        $identityEvidence.Add("")
    } catch {
        $identityEvidence.Add("ERROR: $identityLog :: $($_.Exception.Message)")
    }
}
Write-Utf8File (Join-Path $outputDir "identity-evidence.txt") ((Redact-Text @($identityEvidence)) -join "`r`n")

# Focused end-to-end evidence for the latest product scans. This makes one failed scan enough to
# distinguish: wrong log path -> parse/enrichment failure -> backend no-match/network -> popup failure.
$scanPattern = '(?i)Add2Cheque|POSM готов принимать сканы|Скан товара:|POSM scan parse failed|POSM recommend request|POSM recommend response|POSM recommend candidate|recommend: HTTP|recommend: таймаут|recommend: временно|POSM popup|Чтение zkassa\.log|Автопоиск zkassa\.log'
$scanEvidence = New-Object System.Collections.Generic.List[string]
foreach ($scanLog in @("C:\Epharm\customerdisplay.log") + @($cashLogs)) {
    try {
        if (!(Test-Path -LiteralPath $scanLog -PathType Leaf)) { continue }
        $scanEvidence.Add("===== SOURCE: $scanLog =====")
        $matches = Get-Content -LiteralPath $scanLog -Encoding Default -Tail 20000 |
            Select-String -Pattern $scanPattern |
            Select-Object -Last 1000
        if ($matches.Count -eq 0) {
            $scanEvidence.Add("No scan/recommendation evidence found in the inspected tail.")
        } else {
            foreach ($match in $matches) { $scanEvidence.Add($match.Line) }
        }
        $scanEvidence.Add("")
    } catch {
        $scanEvidence.Add("ERROR: $scanLog :: $($_.Exception.Message)")
    }
}
Write-Utf8File (Join-Path $outputDir "scan-recommendation-evidence.txt") ((Redact-Text @($scanEvidence)) -join "`r`n")

$fiscalPattern = '(?i)PrintCheque|Cheque[01]|GetFiscal|GetFDNumber|GetFNNumber|GetRNM|fiscal|фиск|ofd|оФД|QR|payment|оплат|Before cheque|After cheque'
$fiscalEvidence = New-Object System.Collections.Generic.List[string]
foreach ($fiscalLog in @("C:\Epharm\customerdisplay.log") + @($cashLogs)) {
    try {
        if (!(Test-Path -LiteralPath $fiscalLog -PathType Leaf)) { continue }
        $fiscalEvidence.Add("===== SOURCE: $fiscalLog =====")
        $matches = Get-Content -LiteralPath $fiscalLog -Encoding Default -Tail 50000 |
            Select-String -Pattern $fiscalPattern |
            Select-Object -Last 3000
        foreach ($match in $matches) { $fiscalEvidence.Add($match.Line) }
        $fiscalEvidence.Add("")
    } catch {
        $fiscalEvidence.Add("ERROR: $fiscalLog :: $($_.Exception.Message)")
    }
}
Write-Utf8File (Join-Path $outputDir "fiscal-log-evidence.txt") ((Redact-Text @($fiscalEvidence)) -join "`r`n")

try {
    $receiptRows = foreach ($receiptRoot in @("C:\Epharm\receipts", "C:\Epharm\fiscal-inbox")) {
        if (!(Test-Path -LiteralPath $receiptRoot -PathType Container)) { continue }
        Get-ChildItem -LiteralPath $receiptRoot -File -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1000 |
            Select-Object @{Name="Root";Expression={$receiptRoot}}, FullName, Length, LastWriteTimeUtc
    }
    Write-Utf8File (Join-Path $outputDir "fiscal-artifact-inventory.txt") `
        ($receiptRows | Format-Table -AutoSize | Out-String -Width 500)
} catch {
    Write-Utf8File (Join-Path $outputDir "fiscal-artifact-inventory.txt") $_.Exception.ToString()
}

$databaseSummary = @("OPTIONS.INI:") + @($optionsFiles) +
    @("", "DATABASE FILES:") + @($databaseFiles) +
    @("", "CASH LOGS:") + @($cashLogs)
Write-Utf8File (Join-Path $outputDir "database-candidates.txt") ($databaseSummary -join "`r`n")

try {
    $sqlInstances = Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL" -ErrorAction SilentlyContinue
    Write-Utf8File (Join-Path $outputDir "sql-instances.txt") ($sqlInstances | Format-List * | Out-String -Width 500)
} catch { }

try {
    $events = Get-WinEvent -FilterHashtable @{ LogName = "Application"; StartTime = (Get-Date).AddHours(-24) } -ErrorAction SilentlyContinue |
        Where-Object { $_.ProviderName -match '(?i)application error|\.net runtime|customerdisplay|firebird|mssql' } |
        Select-Object -First 100 TimeCreated, LevelDisplayName, ProviderName, Id, Message
    Write-Utf8File (Join-Path $outputDir "application-events.txt") ($events | Format-List * | Out-String -Width 500)
} catch { }

$summary.Add("")
$summary.Add("POSM log present: $(Test-Path -LiteralPath 'C:\Epharm\customerdisplay.log')")
$summary.Add("Standard-N candidate processes: $($processes.Count)")
$summary.Add("Search roots: $($rootList.Count)")
$summary.Add("options.ini files: $($optionsFiles.Count)")
$summary.Add("zkassa.log files: $($cashLogs.Count)")
$summary.Add("database candidates: $($databaseFiles.Count)")
$summary.Add("fiscal driver candidates: $($fiscalDriverFiles.Count)")
$summary.Add("")
$summary.Add("Send the generated ZIP to the Epharm developer. Secrets are redacted by the collector.")
Write-Utf8File (Join-Path $outputDir "SUMMARY.txt") ($summary -join "`r`n")

try {
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Compress-Archive -LiteralPath $outputDir -DestinationPath $zipPath -CompressionLevel Optimal -Force
    Write-Host ""
    Write-Host "Diagnostics collected successfully:" -ForegroundColor Green
    Write-Host $zipPath -ForegroundColor Green
    Start-Process explorer.exe -ArgumentList "/select,`"$zipPath`""
    exit 0
} catch {
    Write-Host "Diagnostic collection failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Partial files remain in: $outputDir" -ForegroundColor Yellow
    exit 1
}
