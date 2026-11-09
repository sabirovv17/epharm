#requires -version 5.1
<#
Read-only updater inventory. Emits one JSON document to stdout; writes nothing, sends no
network requests, starts/stops no application and never changes tasks, registry or config.
Run only through separately approved workstation access. No raw logs, secrets, receipt data
or command-line arguments are returned. Dot-source for synthetic tests without inventory.
#>
[CmdletBinding()]
param([string]$PosmRoot = 'C:\Epharm', [string[]]$InstallRoot = @(), [string]$ConfigPath)

function Assert-UpdateDiagnosticPath([string]$Path) {
    if (-not [IO.Path]::IsPathRooted($Path) -or $Path.StartsWith('\\')) { throw 'LOCAL_ABSOLUTE_PATH_REQUIRED' }
    $full = [IO.Path]::GetFullPath($Path)
    $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($full))
    if ($drive.DriveType -notin @([IO.DriveType]::Fixed, [IO.DriveType]::Removable, [IO.DriveType]::Ram)) { throw 'NETWORK_OR_UNKNOWN_DRIVE_REFUSED' }
    for ($current = $full; $current; $current = [IO.Path]::GetDirectoryName($current)) {
        try { $attributes = [IO.File]::GetAttributes($current) }
        catch [IO.FileNotFoundException] { continue }
        catch [IO.DirectoryNotFoundException] { continue }
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_POINT_REFUSED' }
    }
    return $full
}

function Get-UpdateFileMetadata([string]$Path) {
    try {
        $safe = Assert-UpdateDiagnosticPath $Path
        if (-not [IO.File]::Exists($safe)) { return [ordered]@{ Path = $safe; Status = 'MISSING' } }
        $file = [IO.FileInfo]::new($safe)
        return [ordered]@{ Path = $safe; Status = 'PRESENT'; Bytes = $file.Length; ModifiedUtc = $file.LastWriteTimeUtc.ToString('o') }
    } catch { return [ordered]@{ Status = 'UNREADABLE_OR_UNSAFE' } }
}

function Read-UpdateBoundedText([string]$Path, [int]$Limit = 65536, [switch]$Tail) {
    if ($Limit -lt 1 -or $Limit -gt 524288) { throw 'INVALID_READ_LIMIT' }
    $safe = Assert-UpdateDiagnosticPath $Path
    $stream = [IO.File]::Open($safe, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        if (-not $Tail -and $stream.Length -gt $Limit) { throw 'INPUT_TOO_LARGE' }
        if ($Tail -and $stream.Length -gt $Limit) { $null = $stream.Seek(-$Limit, [IO.SeekOrigin]::End) }
        $buffer = New-Object byte[] $Limit
        $count = 0
        while ($count -lt $Limit) {
            $read = $stream.Read($buffer, $count, $Limit - $count)
            if ($read -eq 0) { break }
            $count += $read
        }
        return [Text.Encoding]::UTF8.GetString($buffer, 0, $count).TrimStart([char]0xFEFF)
    } finally { $stream.Dispose() }
}

function Get-UpdateConfigSummary([string]$Path) {
    $result = [ordered]@{ File = (Get-UpdateFileMetadata $Path); Status = 'UNKNOWN' }
    try {
        $config = Read-UpdateBoundedText $Path | ConvertFrom-Json -ErrorAction Stop
        $result.Status = 'PARSED_ALLOWLIST_ONLY'
        if ($config.UpdateEnabled -is [bool]) { $result.UpdateEnabled = $config.UpdateEnabled }
        if ($null -ne $config.UpdatePollSec -and "$($config.UpdatePollSec)" -match '^-?[0-9]{1,9}$') { $result.UpdatePollSec = [int]$config.UpdatePollSec }
        # Origin only: no credentials, query, fragment, arbitrary config fields or key bytes.
        $origin = $null
        if ([Uri]::TryCreate([string]$config.BackendBaseUrl, [UriKind]::Absolute, [ref]$origin) -and $origin.Scheme -in @('https', 'http')) {
            $result.BackendOrigin = $origin.GetLeftPart([UriPartial]::Authority) -replace '://[^/@]+@', '://'
        }
        $result.SigningKeyOverridePresent = -not [string]::IsNullOrWhiteSpace([string]$config.UpdateManifestPublicKeySpki)
    } catch { $result.Status = 'MISSING_INVALID_OR_UNSAFE' }
    return $result
}

function Get-UpdateLogSignals([string]$Path) {
    $result = [ordered]@{ File = (Get-UpdateFileMetadata $Path); InspectedTailLimitBytes = 524288; Signals = [ordered]@{} }
    $patterns = [ordered]@{
        UPDATE_DISABLED = 'UpdateEnabled=false|UpdatePollSec<=0'
        MANIFEST_REJECTED = 'подпись манифеста.*(не прошла|отклон)|signature.*(invalid|reject)'
        HASH_REJECTED = 'sha-?256.*(не совп|отсутств|mismatch)'
        DOWNLOAD_FAILURE = 'ошибка скачивания|скачивание.*(HTTP [45][0-9][0-9]|Exception)|пакет недоступен'
        DOWNLOAD_DEADLINE = 'срок скачивания истёк|скачивание.*(Timeout|Canceled)|таймаут.*скачив'
        APPLY_STARTED = 'apply start|применяю обновление|запуск с проверкой готовности'
        APPLY_SUCCEEDED = 'apply success'
        APPLY_FAILED = 'apply failed|recovery-required'
        RECOVERY_PENDING = 'восстанавливается незавершённое обновление|повреждённое состояние обновления'
        LOCAL_HEALTH_ACK = 'подтвердил локальную готовность'
        PACKAGE_PREVIOUSLY_REJECTED = 'ожидается исправленный релиз'
    }
    try {
        $text = Read-UpdateBoundedText $Path -Limit 524288 -Tail
        foreach ($code in $patterns.Keys) {
            $count = [regex]::Matches($text, $patterns[$code], [Text.RegularExpressions.RegexOptions]::IgnoreCase).Count
            if ($count) { $result.Signals[$code] = $count }
        }
        $result.Status = 'SIGNALS_ONLY_NO_RAW_TEXT'
    } catch { $result.Status = 'MISSING_INVALID_OR_UNSAFE' }
    return $result
}

function Get-UpdateInstallSummary([string]$Path) {
    $result = [ordered]@{ Status = 'UNKNOWN' }
    try {
        $safe = Assert-UpdateDiagnosticPath $Path
        if ($safe.TrimEnd('\') -eq [IO.Path]::GetPathRoot($safe).TrimEnd('\')) { throw 'DRIVE_ROOT_REFUSED' }
        $result.Path = $safe
        $result.Status = 'INSPECTED'
        $result.Binaries = @(foreach ($name in @('CustomerDisplay.exe', 'CustomerDisplay.dll')) {
            $binary = Join-Path $safe $name
            $meta = Get-UpdateFileMetadata $binary
            if ($meta.Status -eq 'PRESENT') {
                try { $meta.FileVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($binary).FileVersion }
                catch { $meta.VersionStatus = 'UNREADABLE' }
            }
            $meta
        })
        $control = Join-Path $safe '.epharm-update'
        $result.Active = Get-UpdateFileMetadata (Join-Path $control 'active.json')
        $result.Rejected = Get-UpdateFileMetadata (Join-Path $control 'rejected.json')
        $result.RecoveryStates = @()
        $null = Assert-UpdateDiagnosticPath $control
        if ([IO.Directory]::Exists($control)) {
            $result.RecoveryStates = @(Get-ChildItem -LiteralPath $control -Directory -ErrorAction Stop |
                Where-Object { $_.Name -match '^[0-9a-fA-F]{32}$' } | Select-Object -First 8 | ForEach-Object {
                    $state = [ordered]@{ Id = $_.Name; Status = 'UNKNOWN' }
                    try {
                        $data = Read-UpdateBoundedText (Join-Path $_.FullName 'state.json') -Limit 16384 | ConvertFrom-Json -ErrorAction Stop
                        if ($data.Status -in @('waiting-for-exit', 'applying', 'awaiting-health', 'committed', 'rolling-back', 'rolled-back', 'recovery-required')) { $state.Status = $data.Status }
                    } catch { }
                    $state
                })
        }
        $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($safe))
        $result.FreeBytes = $drive.AvailableFreeSpace
    } catch { $result.Status = 'UNREADABLE_OR_UNSAFE' }
    return $result
}

function Invoke-UpdateDiagnosticInventory([string]$Root, [string[]]$ExplicitInstalls, [string]$ExplicitConfig) {
    $selectedConfig = if ($ExplicitConfig) { $ExplicitConfig } else { Join-Path $Root 'posm.json' }
    $report = [ordered]@{
        Schema = 'epharm-posm-update-diagnostics-v1'
        CollectedUtc = [DateTime]::UtcNow.ToString('o')
        Scope = 'LOCAL_READ_ONLY_NO_NETWORK_NO_RAW_LOGS'
        Conclusions = 'Evidence only; publication, file presence and collector environment do not prove running version or effective process configuration.'
        Config = (Get-UpdateConfigSummary $selectedConfig)
        Logs = @((Get-UpdateLogSignals (Join-Path $Root 'update.log')), (Get-UpdateLogSignals (Join-Path $Root 'customerdisplay.log')))
        Processes = @(); Tasks = @(); Environment = @(); Installations = @(); Cache = @()
    }
    $paths = @($ExplicitInstalls | Select-Object -First 8)
    try {
        $report.Processes = @(Get-CimInstance Win32_Process -Filter "Name='CustomerDisplay.exe'" -OperationTimeoutSec 5 -ErrorAction Stop |
            Select-Object -First 8 | ForEach-Object {
                if ($_.ExecutablePath) { $paths += [IO.Path]::GetDirectoryName($_.ExecutablePath) }
                [ordered]@{ ProcessId = $_.ProcessId; ExecutablePath = $_.ExecutablePath; Started = $_.CreationDate }
            })
    } catch { $report.ProcessInventoryStatus = 'UNAVAILABLE' }
    try {
        $report.Tasks = @(Get-ScheduledTask -TaskName 'EpharmPOSM*' -ErrorAction Stop | Select-Object -First 8 | ForEach-Object {
            $task = $_
            $info = $task | Get-ScheduledTaskInfo -ErrorAction Stop
            [ordered]@{ TaskName = $task.TaskName; State = [string]$task.State; LastTaskResult = $info.LastTaskResult; LastRunTime = $info.LastRunTime
                Executables = @($task.Actions | Select-Object -First 4 | ForEach-Object { [string]$_.Execute }) }
        })
    } catch { $report.TaskInventoryStatus = 'UNAVAILABLE' }
    foreach ($scope in @('Process', 'User', 'Machine')) {
        $entry = [ordered]@{ Scope = $scope; Meaning = 'Collector scope, not the running POSM environment' }
        try { $entry.ConfigPathOverridePresent = -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable('EPHARM_POSM_CONFIG', $scope)) }
        catch { $entry.ConfigPathOverridePresent = 'UNKNOWN' }
        foreach ($name in @('EPHARM_UPDATE_ENABLED', 'EPHARM_UPDATE_POLL_SEC')) {
            try {
                $value = [Environment]::GetEnvironmentVariable($name, $scope)
                $entry[$name] = if ([string]::IsNullOrEmpty($value)) { 'UNSET' } elseif ($value -match '^(true|false|-?[0-9]{1,9})$') { $value } else { 'INVALID_VALUE_REDACTED' }
            } catch { $entry[$name] = 'UNAVAILABLE' }
        }
        $report.Environment += $entry
    }
    $report.Installations = @($paths | Where-Object { $_ } | Select-Object -Unique -First 8 | ForEach-Object { Get-UpdateInstallSummary $_ })
    try {
        $cache = Assert-UpdateDiagnosticPath (Join-Path ([IO.Path]::GetTempPath()) 'epharm-update')
        $report.CacheScope = 'Collector TEMP only; other Windows users may have separate updater caches'
        if ([IO.Directory]::Exists($cache)) {
            $report.Cache = @(Get-ChildItem -LiteralPath $cache -File -ErrorAction Stop |
                Where-Object { $_.Name -match '^epharm-[0-9][0-9A-Za-z._-]*\.zip(\.part)?$' } |
                Select-Object -First 24 | ForEach-Object { Get-UpdateFileMetadata $_.FullName })
        }
    } catch { $report.CacheStatus = 'UNAVAILABLE_OR_UNSAFE' }
    return $report
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-UpdateDiagnosticInventory -Root $PosmRoot -ExplicitInstalls $InstallRoot -ExplicitConfig $ConfigPath | ConvertTo-Json -Depth 9
}
