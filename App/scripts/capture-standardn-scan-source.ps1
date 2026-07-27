#requires -version 5.1
<#
  One-click integration diagnostics for a real Standard-N cash desk.

  It does not write to Standard-N, Firebird or POSM. It captures the POSM/backend environment,
  Standard-N processes and scripts, active user/session evidence, then waits for one real barcode
  scan and reports exactly which file or Firebird rows changed.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$desktop = [Environment]::GetFolderPath("Desktop")
$outputDir = Join-Path $desktop "Epharm-Pharmacy-Integration-Diagnostics-$stamp"
$zipPath = "$outputDir.zip"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$textExtensions = @(".log", ".txt", ".csv", ".json", ".ini", ".xml", ".cfg", ".conf", ".tms", ".pas", ".script")
$dataExtensions = @(".fdb", ".gdb", ".dbf", ".db", ".dat", ".sqlite")
$maxFiles = 100000

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

function Write-Utf8File {
    param([string]$Path, [object]$Value)
    $text = if ($Value -is [string]) { $Value } else { $Value | Out-String -Width 500 }
    [System.IO.File]::WriteAllText($Path, $text, $utf8)
}

function Add-Root {
    param([System.Collections.Generic.List[string]]$Roots, [string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    try {
        $full = [System.IO.Path]::GetFullPath($Path)
        if ((Test-Path -LiteralPath $full -PathType Container) -and -not $Roots.Contains($full)) {
            $Roots.Add($full)
        }
    } catch { }
}

function Test-CashHint {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $false }
    return $Text -match '(?i)standart|standard|kass|kassa|zkassa|apteka|cash|checkout|cheque|managerxp'
}

function Add-ExecutableRoot {
    param([System.Collections.Generic.List[string]]$Roots, [string]$Executable)
    if ([string]::IsNullOrWhiteSpace($Executable)) { return }
    try {
        $exe = $Executable.Trim().Trim('"')
        $index = $exe.IndexOf(".exe", [System.StringComparison]::OrdinalIgnoreCase)
        if ($index -ge 0) { $exe = $exe.Substring(0, $index + 4) }
        $dir = Split-Path -Parent $exe
        Add-Root $Roots $dir
        Add-Root $Roots (Split-Path -Parent $dir)
    } catch { }
}

function Get-SearchRoots {
    $roots = New-Object 'System.Collections.Generic.List[string]'
    foreach ($path in @(
        "C:\Standart-N", "C:\Standart-N_DEMO", "C:\StandartN", "C:\Standart_N",
        "C:\Kassir", "C:\Kassa", "C:\Apteka", "C:\Program Files\Standart-N",
        "C:\Program Files (x86)\Standart-N", "C:\Program Files (x86)\Standart_N"
    )) { Add-Root $roots $path }

    try {
        foreach ($drive in Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue) {
            if ([string]::IsNullOrWhiteSpace($drive.Root)) { continue }
            Get-ChildItem -LiteralPath $drive.Root -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-CashHint $_.Name } |
                ForEach-Object { Add-Root $roots $_.FullName }
        }
    } catch { }

    $cachedPaths = "C:\Epharm\standardn-log-paths.txt"
    if (Test-Path -LiteralPath $cachedPaths -PathType Leaf) {
        Get-Content -LiteralPath $cachedPaths -ErrorAction SilentlyContinue | ForEach-Object {
            Add-Root $roots (Split-Path -Parent $_.Trim())
        }
    }

    $identity = "C:\Epharm\standardn-identity-diagnostics.txt"
    if (Test-Path -LiteralPath $identity -PathType Leaf) {
        Get-Content -LiteralPath $identity -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_ -match '(?i)^Database:.*\bdb=(.+)$') {
                Add-Root $roots (Split-Path -Parent $matches[1].Trim())
            }
        }
    }

    try {
        Get-CimInstance Win32_Process | Where-Object {
            (Test-CashHint $_.Name) -or (Test-CashHint $_.ExecutablePath)
        } | ForEach-Object {
            Add-ExecutableRoot $roots $_.ExecutablePath
        }
    } catch { }

    try {
        $shell = New-Object -ComObject WScript.Shell
        $folders = @(
            [Environment]::GetFolderPath("Desktop"),
            [Environment]::GetFolderPath("CommonDesktopDirectory"),
            [Environment]::GetFolderPath("StartMenu"),
            [Environment]::GetFolderPath("CommonStartMenu")
        ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique
        foreach ($folder in $folders) {
            Get-ChildItem -LiteralPath $folder -Filter "*.lnk" -File -Recurse -ErrorAction SilentlyContinue |
                Select-Object -First 250 | ForEach-Object {
                    try {
                        $shortcut = $shell.CreateShortcut($_.FullName)
                        if ((Test-CashHint $_.Name) -or (Test-CashHint $shortcut.TargetPath)) {
                            Add-ExecutableRoot $roots $shortcut.TargetPath
                        }
                    } catch { }
                }
        }
    } catch { }

    return @($roots | Select-Object -Unique)
}

function Get-FileSnapshot {
    param([string[]]$Roots)
    $snapshot = @{}
    $seen = 0
    foreach ($root in $Roots) {
        if ($seen -ge $maxFiles) { break }
        try {
            foreach ($file in Get-ChildItem -LiteralPath $root -File -Recurse -ErrorAction SilentlyContinue) {
                if ($seen -ge $maxFiles) { break }
                $snapshot[$file.FullName] = "$($file.Length)|$($file.LastWriteTimeUtc.Ticks)"
                $seen++
            }
        } catch { }
    }
    return $snapshot
}

function Get-TextTail {
    param([string]$Path)
    try {
        return (Get-Content -LiteralPath $Path -Encoding Default -Tail 300 -ErrorAction Stop) -join "`r`n"
    } catch {
        return "READ ERROR: $($_.Exception.Message)"
    }
}

function Test-CashMarker {
    param([string]$Path)
    try {
        $tail = Get-TextTail $Path
        return $tail -match '(?i)Add2Cheque|ChequeList\.OnChange|RunScriptByIndex|iPartID\s*='
    } catch { return $false }
}

function Protect-DiagnosticText {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    $result = [regex]::Replace(
        $Text,
        '(?i)(devicekey|password|passwd|token|secret)\s*[:=]\s*[^;\s,]+',
        '$1=[REDACTED]')
    return [regex]::Replace($result, '(?i)\b[a-f0-9]{40,}\b', '[REDACTED-LONG-HEX]')
}

function Get-RedactedPosmConfig {
    $path = "C:\Epharm\posm.json"
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { return "C:\Epharm\posm.json: NOT FOUND" }
    try {
        $config = Get-Content -LiteralPath $path -Raw -ErrorAction Stop | ConvertFrom-Json
        foreach ($property in @($config.PSObject.Properties)) {
            if ($property.Name -match '(?i)devicekey|password|passwd|token|secret') {
                $property.Value = "[REDACTED]"
            }
        }
        return $config | ConvertTo-Json -Depth 20
    } catch {
        return "CONFIG READ ERROR: $($_.Exception.Message)"
    }
}

function Get-SystemReport {
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("Collected: $([DateTimeOffset]::Now.ToString('o'))")
    $lines.Add("Computer: $env:COMPUTERNAME")
    $lines.Add("Interactive user: $([Security.Principal.WindowsIdentity]::GetCurrent().Name)")
    $lines.Add("PowerShell: $($PSVersionTable.PSVersion)")
    $lines.Add("64-bit process: $([Environment]::Is64BitProcess)")
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        $lines.Add("Windows: $($os.Caption), version=$($os.Version), build=$($os.BuildNumber), arch=$($os.OSArchitecture)")
        $lines.Add("Last boot: $($os.LastBootUpTime)")
    } catch { $lines.Add("Windows info error: $($_.Exception.Message)") }
    try {
        $computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
        $lines.Add("Console user: $($computer.UserName)")
        $lines.Add("Hardware: $($computer.Manufacturer) $($computer.Model)")
    } catch { $lines.Add("Computer info error: $($_.Exception.Message)") }
    try {
        $monitors = @(Get-CimInstance Win32_DesktopMonitor -ErrorAction Stop | Where-Object { $_.Status -eq "OK" })
        $lines.Add("Active desktop monitors: $($monitors.Count)")
        foreach ($monitor in $monitors) {
            $lines.Add("  $($monitor.Name); $($monitor.ScreenWidth)x$($monitor.ScreenHeight); device=$($monitor.DeviceID)")
        }
    } catch { $lines.Add("Monitor info error: $($_.Exception.Message)") }
    $lines.Add("")
    $lines.Add("EPHARM ENVIRONMENT:")
    $environmentRows = @(Get-ChildItem Env: | Where-Object { $_.Name -like "EPHARM_*" } | Sort-Object Name)
    if ($environmentRows.Count -eq 0) { $lines.Add("  none") }
    foreach ($row in $environmentRows) {
        $value = if ($row.Name -match '(?i)KEY|PASS|TOKEN|SECRET') { "[REDACTED]" } else { [string]$row.Value }
        $lines.Add("  $($row.Name)=$value")
    }
    $lines.Add("")
    $lines.Add("EPHARM SCHEDULED TASKS:")
    try {
        $tasks = @(Get-ScheduledTask -ErrorAction Stop | Where-Object { $_.TaskName -like "EpharmPOSM*" })
        if ($tasks.Count -eq 0) { $lines.Add("  none") }
        foreach ($task in $tasks) {
            $lines.Add("  $($task.TaskName): state=$($task.State); user=$($task.Principal.UserId); logon=$($task.Principal.LogonType)")
            foreach ($action in $task.Actions) {
                $lines.Add("    action=$($action.Execute) $($action.Arguments); workdir=$($action.WorkingDirectory)")
            }
        }
    } catch { $lines.Add("  task query error: $($_.Exception.Message)") }
    return $lines
}

function Get-BackendHealthReport {
    param([object]$Config)
    $lines = New-Object System.Collections.Generic.List[string]
    $origins = New-Object System.Collections.Generic.List[string]
    if ($null -ne $Config) {
        $primary = [string](Get-DbSetting $Config "BackendBaseUrl" "")
        if (![string]::IsNullOrWhiteSpace($primary)) { $origins.Add($primary.TrimEnd('/')) }
        $fallbackProperty = $Config.PSObject.Properties["BackendFallbackBaseUrls"]
        if ($null -ne $fallbackProperty) {
            foreach ($fallback in @($fallbackProperty.Value)) {
                $value = [string]$fallback
                if (![string]::IsNullOrWhiteSpace($value) -and -not $origins.Contains($value.TrimEnd('/'))) {
                    $origins.Add($value.TrimEnd('/'))
                }
            }
        }
    }
    if ($origins.Count -eq 0) {
        $lines.Add("No backend origins found in C:\Epharm\posm.json")
        return $lines
    }
    foreach ($origin in $origins) {
        $url = "$origin/api/health"
        try {
            $response = Invoke-WebRequest -Uri $url -Method Get -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
            $lines.Add("$url -> HTTP $([int]$response.StatusCode)")
        } catch {
            $status = ""
            try { $status = "HTTP $([int]$_.Exception.Response.StatusCode)" } catch { }
            $lines.Add("$url -> FAILED $status $($_.Exception.GetBaseException().Message)")
        }
    }
    return $lines
}

function Get-TmsScriptReport {
    param([string[]]$Roots)
    $lines = New-Object System.Collections.Generic.List[string]
    $checked = 0
    $hits = 0
    foreach ($root in $Roots) {
        if ($checked -ge 10000) { break }
        try {
            foreach ($file in Get-ChildItem -LiteralPath $root -File -Recurse -ErrorAction SilentlyContinue) {
                if ($checked -ge 10000) { break }
                if ($file.Extension.ToLowerInvariant() -notin @(".pas", ".tms", ".script", ".inc")) { continue }
                $checked++
                if ($file.Length -gt 2MB) { continue }
                try {
                    $content = [System.IO.File]::ReadAllText($file.FullName)
                    $markers = @([regex]::Matches($content, '(?i)ZKassa|ChequeList|P_Name|RunScript|ActiveIID|P_Price|P_Quant') |
                        ForEach-Object { $_.Value } | Select-Object -Unique)
                    if ($markers.Count -gt 0) {
                        $hits++
                        $lines.Add("$($file.FullName) | markers=$($markers -join ',')")
                    }
                } catch { }
            }
        } catch { }
    }
    if ($hits -eq 0) { $lines.Add("No readable TMS/Pascal cash script with known event markers was found.") }
    $lines.Insert(0, "Script files checked: $checked; matching files: $hits")
    return $lines
}

function Write-TailArtifact {
    param([string]$SourcePath, [string]$OutputName, [int]$Tail = 5000)
    if (!(Test-Path -LiteralPath $SourcePath -PathType Leaf)) { return }
    try {
        $content = (Get-Content -LiteralPath $SourcePath -Encoding Default -Tail $Tail -ErrorAction Stop) -join "`r`n"
        Write-Utf8File (Join-Path $outputDir $OutputName) (Protect-DiagnosticText $content)
    } catch {
        Write-Utf8File (Join-Path $outputDir "$OutputName-error.txt") $_.Exception.Message
    }
}

function Get-DbSetting {
    param([object]$Config, [string]$Name, [object]$Default)
    if ($null -eq $Config) { return $Default }
    $property = $Config.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        return $Default
    }
    return $property.Value
}

function Get-StandardNDbSnapshot {
    param([string]$Label, [string[]]$Roots)

    $report = New-Object System.Collections.Generic.List[string]
    $signature = New-Object System.Collections.Generic.List[string]
    $sectionSignatures = @{}
    $connection = $null
    $reader = $null
    try {
        $config = $null
        if (Test-Path -LiteralPath "C:\Epharm\posm.json" -PathType Leaf) {
            $config = Get-Content -LiteralPath "C:\Epharm\posm.json" -Raw -ErrorAction Stop | ConvertFrom-Json
        }

        $dbPath = [string](Get-DbSetting $config "StandardNDbPath" "")
        if ([string]::IsNullOrWhiteSpace($dbPath)) {
            $defaultDb = "C:\Standart-N\base\ztrade.fdb"
            if (Test-Path -LiteralPath $defaultDb -PathType Leaf) {
                $dbPath = $defaultDb
            } else {
                foreach ($root in $Roots) {
                    $candidate = Get-ChildItem -LiteralPath $root -Filter "ztrade.fdb" -File -Recurse -ErrorAction SilentlyContinue |
                        Select-Object -First 1
                    if ($null -ne $candidate) {
                        $dbPath = $candidate.FullName
                        break
                    }
                }
            }
        }
        if ([string]::IsNullOrWhiteSpace($dbPath)) { throw "ztrade.fdb was not found" }

        $providerCandidates = @(
            (Join-Path $PSScriptRoot "lib\FirebirdSql.Data.FirebirdClient.dll"),
            "C:\Epharm\app\FirebirdSql.Data.FirebirdClient.dll",
            "C:\Epharm\FirebirdSql.Data.FirebirdClient.dll"
        )
        $providerPath = $providerCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if ([string]::IsNullOrWhiteSpace($providerPath)) {
            throw "Firebird provider from the installed POSM was not found"
        }

        $providerDir = Split-Path -Parent $providerPath
        foreach ($dependencyName in @(
            "System.Runtime.CompilerServices.Unsafe.dll",
            "System.Threading.Tasks.Extensions.dll"
        )) {
            $dependencyPath = Join-Path $providerDir $dependencyName
            if (Test-Path -LiteralPath $dependencyPath -PathType Leaf) {
                [void][System.Reflection.Assembly]::LoadFrom($dependencyPath)
            }
        }
        [void][System.Reflection.Assembly]::LoadFrom($providerPath)
        $dbHost = [string](Get-DbSetting $config "StandardNDbHost" "localhost")
        $port = [int](Get-DbSetting $config "StandardNDbPort" 3050)
        $user = [string](Get-DbSetting $config "StandardNDbUser" "SYSDBA")
        $password = [string](Get-DbSetting $config "StandardNDbPassword" "masterkey")
        $connectionString = "DataSource=$dbHost;Port=$port;Database=$dbPath;UserID=$user;Password=$password;Charset=WIN1251;Pooling=false;ConnectionTimeout=2"
        $connection = New-Object FirebirdSql.Data.FirebirdClient.FbConnection($connectionString)
        $connection.Open()

        $report.Add("$Label database snapshot")
        $report.Add("Database: $dbHost`:$port / $dbPath")
        $report.Add("Mode: read-only SELECT queries; credentials are not included in this report")
        $report.Add("")

        $queries = @(
            [PSCustomObject]@{ Name = "RELEVANT_SCHEMA"; Sql = "SELECT TRIM(RDB`$RELATION_NAME) AS TABLE_NAME, TRIM(RDB`$FIELD_NAME) AS FIELD_NAME, RDB`$FIELD_POSITION AS FIELD_POSITION FROM RDB`$RELATION_FIELDS WHERE RDB`$RELATION_NAME IN ('ACTIVEUSERS','SESSIONS','SP`$SESSIONS','USERS','HUMAN_ACTION_LOGS','DOC_DETAIL_LOG','CASH_DOCS','DOCS') ORDER BY RDB`$RELATION_NAME, RDB`$FIELD_POSITION" },
            [PSCustomObject]@{ Name = "ACTIVEUSERS"; Sql = "SELECT FIRST 20 * FROM ACTIVEUSERS" },
            [PSCustomObject]@{ Name = "SESSIONS_WITH_USERS"; Sql = "SELECT FIRST 50 s.ID, s.USER_ID, s.WS_ID, s.STARTDT, s.ENDDT, s.ENDFLAG, s.PROG, u.USERCODE, u.USERNAME_N, u.USERNAME FROM SESSIONS s LEFT JOIN USERS u ON u.ID = s.USER_ID ORDER BY s.STARTDT DESC" },
            [PSCustomObject]@{ Name = "SP_SESSIONS_WITH_USERS"; Sql = 'SELECT FIRST 50 s.ID, s.USER_ID, s.WS_ID, s.STARTDT, s.ENDDT, s.ENDFLAG, s.PROG, u.USERCODE, u.USERNAME_N, u.USERNAME FROM SP$SESSIONS s LEFT JOIN USERS u ON u.ID = s.USER_ID ORDER BY s.STARTDT DESC' },
            [PSCustomObject]@{ Name = "HUMAN_ACTION_LOGS"; Sql = "SELECT FIRST 50 USER_ID, USER_SNAME, SESSION_ID, INSERTDT, ACT_TYPE FROM HUMAN_ACTION_LOGS ORDER BY INSERTDT DESC" },
            [PSCustomObject]@{ Name = "DOC_DETAIL_LOG"; Sql = "SELECT FIRST 50 LOG_ID, LOG_INSERTDT, DOC_ID, PART_ID, PRICE, QUANT, SUMMA, INSERTDT, DOC_COMMITDATE FROM DOC_DETAIL_LOG ORDER BY LOG_INSERTDT DESC" },
            [PSCustomObject]@{ Name = "CASH_DOCS"; Sql = "SELECT FIRST 30 ID, PARENT_ID, DOC_TYPE, STATUS, CREATESESSION_ID, COMMITSESSION_ID, OWNER, INSERTDT, COMMITDATE, SUMMA, DEVICE_NUM, VNUM, DOC_ID, CASH_DOCS_MANAGER_ID FROM CASH_DOCS ORDER BY INSERTDT DESC" },
            [PSCustomObject]@{ Name = "DOCS"; Sql = "SELECT FIRST 30 ID, PARENT_ID, DOC_TYPE, STATUS, CREATER, OWNER, COMMITSESSION_ID, INSERTDT, COMMITDATE, DEVICE_NUM, SUMMA, VNUM FROM DOCS ORDER BY INSERTDT DESC" }
        )

        foreach ($query in $queries) {
            $report.Add("===== $($query.Name) =====")
            $command = $connection.CreateCommand()
            $command.CommandText = $query.Sql
            $command.CommandTimeout = 3
            $sectionRows = New-Object System.Collections.Generic.List[string]
            try {
                $reader = $command.ExecuteReader()
                while ($reader.Read()) {
                    $values = New-Object System.Collections.Generic.List[string]
                    for ($i = 0; $i -lt $reader.FieldCount; $i++) {
                        $value = if ($reader.IsDBNull($i)) { "<null>" } else { [string]$reader.GetValue($i) }
                        $values.Add("$($reader.GetName($i))=$value")
                    }
                    $line = $values -join "; "
                    $report.Add($line)
                    $signature.Add("$($query.Name)|$line")
                    $sectionRows.Add($line)
                }
                if ($sectionRows.Count -eq 0) { $report.Add("<no rows>") }
            } catch {
                $report.Add("QUERY ERROR: $($_.Exception.Message)")
            } finally {
                if ($null -ne $reader) { $reader.Dispose(); $reader = $null }
                if ($null -ne $command) { $command.Dispose() }
            }
            $sectionSignatures[$query.Name] = $sectionRows -join "`n"
            $report.Add("")
        }

        return [PSCustomObject]@{ Available = $true; Report = $report; Signature = $signature; Sections = $sectionSignatures }
    } catch {
        $report.Add("$Label database snapshot unavailable: $($_.Exception.Message)")
        return [PSCustomObject]@{ Available = $false; Report = $report; Signature = $signature; Sections = $sectionSignatures }
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $connection) { $connection.Dispose() }
    }
}

$roots = @(Get-SearchRoots)
$processRows = @()
try {
    $processRows = Get-CimInstance Win32_Process | Where-Object {
        (Test-CashHint $_.Name) -or (Test-CashHint $_.ExecutablePath)
    } | Select-Object ProcessId, Name, ExecutablePath, CreationDate
} catch { }

Write-Utf8File (Join-Path $outputDir "search-roots.txt") ($roots -join "`r`n")
Write-Utf8File (Join-Path $outputDir "relevant-processes.txt") ($processRows | Format-List * | Out-String -Width 500)
Write-Utf8File (Join-Path $outputDir "system-and-autostart.txt") ((Get-SystemReport) -join "`r`n")
Write-Utf8File (Join-Path $outputDir "posm-config-redacted.json") (Get-RedactedPosmConfig)
Write-Utf8File (Join-Path $outputDir "standardn-tms-scripts.txt") ((Get-TmsScriptReport -Roots $roots) -join "`r`n")

$posmConfig = $null
try {
    if (Test-Path -LiteralPath "C:\Epharm\posm.json" -PathType Leaf) {
        $posmConfig = Get-Content -LiteralPath "C:\Epharm\posm.json" -Raw -ErrorAction Stop | ConvertFrom-Json
    }
} catch { }
Write-Utf8File (Join-Path $outputDir "backend-health.txt") ((Get-BackendHealthReport -Config $posmConfig) -join "`r`n")

Write-TailArtifact "C:\Epharm\customerdisplay.log" "posm-log-tail.txt" 10000
Write-TailArtifact "C:\Epharm\install.log" "install-log-tail.txt" 3000
Write-TailArtifact "C:\Epharm\crash.log" "crash-log-tail.txt" 3000
Write-TailArtifact "C:\Epharm\install-status.json" "install-status.txt" 500

$legacyPathReport = New-Object System.Collections.Generic.List[string]
$legacyPathReport.Add("POSM 1.0.23 decompilation result:")
$legacyPathReport.Add("  Scan source: Add2Cheque lines from exactly two built-in zkassa.log paths.")
$legacyPathReport.Add("  Active pharmacist: ACTIVEUSERS joined with USERS; Firebird was not the scan trigger.")
$legacyPathReport.Add("")
$legacyIndex = 0
foreach ($legacyPath in @(
    "C:\Standart-N\Kassir\zkassa.log",
    "C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log"
)) {
    $legacyIndex++
    if (Test-Path -LiteralPath $legacyPath -PathType Leaf) {
        $file = Get-Item -LiteralPath $legacyPath
        $legacyPathReport.Add("FOUND: $legacyPath | size=$($file.Length) | modified=$($file.LastWriteTime) | cashMarkers=$(Test-CashMarker $legacyPath)")
        Write-TailArtifact $legacyPath ("legacy-zkassa-{0:D2}-tail.txt" -f $legacyIndex) 3000
    } else {
        $legacyPathReport.Add("NOT FOUND: $legacyPath")
    }
}
Write-Utf8File (Join-Path $outputDir "comparison-with-posm-1.0.23.txt") ($legacyPathReport -join "`r`n")

Write-Host ""
Write-Host "Epharm / полная диагностика интеграции Standard-N" -ForegroundColor Cyan
Write-Host "Найдено корневых папок: $($roots.Count). Снимок может занять несколько секунд." -ForegroundColor Cyan
Write-Host "Не закрывайте это окно." -ForegroundColor Yellow
$before = Get-FileSnapshot -Roots $roots
$dbBefore = Get-StandardNDbSnapshot -Label "Before scan" -Roots $roots
Write-Utf8File (Join-Path $outputDir "database-before-scan.txt") ($dbBefore.Report -join "`r`n")
Write-Host "Первый снимок готов: $($before.Count) файлов." -ForegroundColor Green
Write-Host ""
Write-Host "Теперь добавьте в открытый чек Standard-N РОВНО ОДИН товар реальным сканером." -ForegroundColor Yellow
Write-Host "Подождите две секунды, вернитесь в это окно и нажмите Enter." -ForegroundColor Yellow
[void](Read-Host "Нажмите Enter после сканирования")
Start-Sleep -Seconds 2
$after = Get-FileSnapshot -Roots $roots
$dbAfter = Get-StandardNDbSnapshot -Label "After scan" -Roots $roots
Write-Utf8File (Join-Path $outputDir "database-after-scan.txt") ($dbAfter.Report -join "`r`n")

$changed = New-Object System.Collections.Generic.List[object]
foreach ($entry in $after.GetEnumerator()) {
    if (-not $before.ContainsKey($entry.Key) -or $before[$entry.Key] -ne $entry.Value) {
        try {
            $item = Get-Item -LiteralPath $entry.Key -ErrorAction Stop
            $changed.Add([PSCustomObject]@{
                Path = $item.FullName
                Extension = $item.Extension
                Size = $item.Length
                LastWrite = $item.LastWriteTime
                CashMarker = if (
                    $dataExtensions -notcontains $item.Extension.ToLowerInvariant() -and
                    $item.Length -le 20MB
                ) { Test-CashMarker $item.FullName } else { $false }
            })
        } catch { }
    }
}

$changedRows = $changed | Sort-Object @{ Expression = 'CashMarker'; Descending = $true }, @{ Expression = 'LastWrite'; Descending = $true }
Write-Utf8File (Join-Path $outputDir "changed-files.txt") ($changedRows | Format-Table -AutoSize | Out-String -Width 600)

$tailDir = Join-Path $outputDir "changed-text-tails"
New-Item -ItemType Directory -Path $tailDir -Force | Out-Null
$index = 0
foreach ($row in $changedRows | Where-Object {
    ($textExtensions -contains $_.Extension.ToLowerInvariant()) -or $_.CashMarker
}) {
    $index++
    $safeName = ($row.Path -replace '[:\\/]', '_')
    Write-Utf8File (Join-Path $tailDir ("{0:D2}-{1}.txt" -f $index, $safeName)) (
        "SOURCE: $($row.Path)`r`nCASH MARKER: $($row.CashMarker)`r`n`r`n" + (Get-TextTail $row.Path)
    )
}

$markerSources = @($changedRows | Where-Object { $_.CashMarker })
$dataChanges = @($changedRows | Where-Object { $dataExtensions -contains $_.Extension.ToLowerInvariant() })
$databaseRowsChanged = $dbBefore.Available -and $dbAfter.Available -and (($dbBefore.Signature -join "`n") -ne ($dbAfter.Signature -join "`n"))
$changedDbSections = New-Object System.Collections.Generic.List[string]
if ($dbBefore.Available -and $dbAfter.Available) {
    $sectionNames = @($dbBefore.Sections.Keys) + @($dbAfter.Sections.Keys) | Select-Object -Unique
    foreach ($sectionName in $sectionNames) {
        $beforeSection = [string]$dbBefore.Sections[$sectionName]
        $afterSection = [string]$dbAfter.Sections[$sectionName]
        if ($beforeSection -ne $afterSection) { $changedDbSections.Add([string]$sectionName) }
    }
}
$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("Epharm pharmacy integration diagnostics")
$summary.Add("Collected: $([DateTimeOffset]::Now.ToString('o'))")
$summary.Add("Roots inspected: $($roots.Count)")
$summary.Add("Snapshot files before: $($before.Count)")
$summary.Add("Snapshot files after: $($after.Count)")
$summary.Add("Changed files after one scan: $($changedRows.Count)")
$summary.Add("Firebird snapshots captured: $($dbBefore.Available -and $dbAfter.Available)")
$summary.Add("Firebird selected rows changed after scan: $databaseRowsChanged")
$summary.Add("Changed Firebird sections: $(if ($changedDbSections.Count -gt 0) { $changedDbSections -join ', ' } else { 'none' })")
$summary.Add("")
if ($markerSources.Count -gt 0) {
    $summary.Add("VERDICT: CASH_LOG_FOUND")
    $summary.Add("The following changed file contains Standard-N cash-event markers:")
    foreach ($source in $markerSources) { $summary.Add("  $($source.Path)") }
} elseif (@($changedDbSections | Where-Object { $_ -in @("DOC_DETAIL_LOG", "CASH_DOCS", "DOCS") }).Count -gt 0) {
    $summary.Add("VERDICT: FIREBIRD_CASH_ROWS_CHANGED")
    $summary.Add("Cash tables changed after the scan. The exact changed sections are listed above and can be used as the production scan source.")
} elseif ($databaseRowsChanged) {
    $summary.Add("VERDICT: FIREBIRD_SESSION_OR_ACTION_CHANGED")
    $summary.Add("Firebird changed after the scan, but the selected cash tables did not. Inspect the changed sections and TMS evidence before choosing the event source.")
} elseif ($dataChanges.Count -gt 0) {
    $summary.Add("VERDICT: DATABASE_OR_BINARY_STATE_CHANGED")
    $summary.Add("No readable cash log marker was found. Standard-N changed data files, so the integration must use a database query or native TMS hook.")
    foreach ($source in $dataChanges) { $summary.Add("  $($source.Path)") }
} elseif ($changedRows.Count -gt 0) {
    $summary.Add("VERDICT: NONSTANDARD_FILE_CHANGED")
    $summary.Add("Files changed, but none has a known cash marker. Their tails are included for format analysis.")
} else {
    $summary.Add("VERDICT: NO_LOCAL_FILE_CHANGE_OBSERVED")
    $summary.Add("The scan did not change an observable local file. Standard-N is likely sending events through its database/service, and the next POSM implementation must use that channel.")
}
$summary.Add("")
$summary.Add("Send the ZIP unchanged to the Epharm developer. It contains no passwords or POSM device keys.")
Write-Utf8File (Join-Path $outputDir "SUMMARY.txt") ($summary -join "`r`n")

try {
    Compress-Archive -LiteralPath $outputDir -DestinationPath $zipPath -CompressionLevel Optimal -Force
    Write-Host ""
    Write-Host "Диагностика завершена. Готовый ZIP:" -ForegroundColor Green
    Write-Host $zipPath -ForegroundColor Green
    Start-Process explorer.exe -ArgumentList "/select,`"$zipPath`""
    exit 0
} catch {
    Write-Host "Не удалось создать ZIP: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Несжатый отчет сохранен здесь: $outputDir" -ForegroundColor Yellow
    exit 1
}
