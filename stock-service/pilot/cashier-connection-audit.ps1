#requires -version 5.1
[CmdletBinding()]
param([switch]$LibraryOnly)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$script:AuditLines = New-Object 'System.Collections.Generic.List[string]'

function Write-Audit([string]$Text) {
    $safe = [regex]::Replace($Text, '[\x00-\x08\x0B\x0C\x0E-\x1F]', '')
    $script:AuditLines.Add($safe)
    Write-Host $safe
}

function Read-AuditIni([string]$Path) {
    if ((Get-Item -LiteralPath $Path).Length -gt 262144) { throw 'INI_TOO_LARGE' }
    $reader = New-Object IO.StreamReader($Path, [Text.Encoding]::GetEncoding(1251), $true)
    try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $values = @{}; $authKeys = New-Object 'System.Collections.Generic.List[string]'; $section = ''
    foreach ($raw in ($text -split '\r?\n')) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith(';') -or $line.StartsWith('#')) { continue }
        if ($line -match '^\[([^\]]+)\]$') { $section = $Matches[1]; continue }
        if ($section -ine 'Connect') { continue }
        $equals = $line.IndexOf('='); if ($equals -lt 1) { continue }
        $key = $line.Substring(0, $equals).Trim().ToLowerInvariant()
        # Do not extract, inspect, hash or report credential values, including usernames.
        if ($key -in @('login','user','username','password','pass','pwd')) { $authKeys.Add($key); continue }
        if ($key -notin @('server','host','datasource','port','base','basename','database','databasename','db','dbpath','dbname')) { continue }
        $value = $line.Substring($equals + 1).Trim().Trim('"', "'")
        if ($value -match '[;?=\r\n]') { $value = '[OMITTED_NON_SIMPLE_VALUE]' }
        if ($value.Length -gt 240) { $value = '[OMITTED_LONG_VALUE]' }
        if ($values.ContainsKey($key) -and $values[$key] -ine $value) { $value = '[AMBIGUOUS]' }
        $values[$key] = $value
    }
    return [pscustomobject]@{ Values = $values; AuthenticationKeyNames = @($authKeys | Select-Object -Unique) }
}

function Read-AuditFirebirdConfig([string]$Path) {
    $values = @{}
    if ((Get-Item -LiteralPath $Path).Length -gt 262144) { throw 'CONFIG_TOO_LARGE' }
    foreach ($raw in [IO.File]::ReadAllLines($Path)) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#') -or $line.StartsWith(';')) { continue }
        $equals = $line.IndexOf('='); if ($equals -lt 1) { continue }
        $key = $line.Substring(0, $equals).Trim().ToLowerInvariant()
        if ($key -notin @('remoteserviceport','remoteservicename','authentication')) { continue }
        $value = ($line.Substring($equals + 1) -split '[#;]', 2)[0].Trim().Trim('"', "'")
        $valid = ($key -eq 'authentication' -and $value -match '^(?i:native|mixed|trusted)$') -or
            ($key -eq 'remoteservicename' -and $value -match '^[A-Za-z0-9_.-]{1,80}$') -or
            ($key -eq 'remoteserviceport' -and $value -match '^\d{1,5}$' -and [int]$value -ge 1 -and [int]$value -le 65535)
        if (-not $valid) { $value = '[INVALID_OR_UNSUPPORTED]' }
        if ($values.ContainsKey($key) -and $values[$key] -ine $value) { $value = '[AMBIGUOUS]' }
        $values[$key] = $value
    }
    return $values
}

function Get-AuditAddress([string]$Value) {
    try {
        $address = [Net.IPAddress]::Parse($Value)
        if ($address.IsIPv4MappedToIPv6) { $address = $address.MapToIPv4() }
        return $address.ToString()
    } catch { return '' }
}

function Select-AuditPeers($Rows, [int]$ProcessId, [string[]]$Addresses) {
    $normalized = @($Addresses | ForEach-Object { Get-AuditAddress $_ } | Where-Object { $_ })
    foreach ($row in $Rows) {
        $remote = Get-AuditAddress ([string]$row.RemoteAddress)
        if ($row.OwningProcess -eq $ProcessId -and $remote -and $normalized -contains $remote) { $row }
    }
}

function Write-AuditVersion([string]$Kind, [string]$Path) {
    try {
        $info = [Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
        Write-Audit ($Kind + '=' + $Path + '; FILE_VERSION=' + $info.FileVersion + '; PRODUCT=' + $info.ProductName)
    } catch { Write-Audit ($Kind + '=UNAVAILABLE') }
}

function Invoke-ConnectionAudit {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'WINDOWS_REQUIRED' }
    Write-Audit '=== EPHARM CONNECTION AUDIT BEGIN ==='
    Write-Audit 'VERSION=1; MODE=CONNECTION_AUDIT_ONLY; DATABASE_CONNECTION_ATTEMPTS=0'
    Write-Audit ('UTC_TIME=' + [DateTime]::UtcNow.ToString('o') + '; COMPUTER=' + $env:COMPUTERNAME)
    Write-Audit 'Проверяем настройки и существующие соединения кассы. Пароли не запрашиваются; запросов к БД нет.'
    $label = [regex]::Replace((Read-Host 'Название/адрес аптеки (Enter — пропустить)').Trim(), '[\x00-\x1F]', ' ')
    if ($label.Length -gt 160) { $label = $label.Substring(0,160) }
    Write-Audit ('PHARMACY_LABEL=' + $label + '; IDENTITY=NOT_VERIFIED')
    $cashiers = @(Get-Process -Name 'zkassa*' -ErrorAction SilentlyContinue)
    Write-Audit ('RUNNING_ZKASSA_COUNT=' + $cashiers.Count)
    foreach ($cashier in $cashiers) {
        Write-Audit ('CASHIER_PID=' + $cashier.Id)
        try { $exe = $cashier.Path; if (-not $exe) { throw 'NO_PATH' }; Write-AuditVersion 'CASHIER_EXE' $exe }
        catch { Write-Audit 'CASHIER_PATH=UNAVAILABLE; повторите от имени администратора, если путь недоступен.'; continue }
        try {
            $modules = @($cashier.Modules | Where-Object { $_.ModuleName -match '^(?i:fbclient|gds32|fbembed|ibclient)\.dll$' })
            Write-Audit ('CASHIER_DB_CLIENT_MODULE_COUNT=' + $modules.Count)
            foreach ($module in $modules) { Write-AuditVersion 'CASHIER_DB_CLIENT_MODULE' $module.FileName }
        } catch { Write-Audit 'CASHIER_DB_CLIENT_MODULES=UNAVAILABLE; возможны ограничения прав или разрядности.' }
        $cashierDir = Split-Path -Parent $exe
        foreach ($dir in @($cashierDir, (Split-Path -Parent $cashierDir)) | Select-Object -Unique) {
            $ini = Join-Path $dir 'options.ini'
            if (-not (Test-Path -LiteralPath $ini -PathType Leaf)) { continue }
            try {
                $summary = Read-AuditIni $ini
                Write-Audit ('ACTIVE_INI_CANDIDATE=' + $ini + '; UPDATED_UTC=' + (Get-Item -LiteralPath $ini).LastWriteTimeUtc.ToString('o'))
                Write-Audit ('AUTHENTICATION_KEY_NAMES=' + ($summary.AuthenticationKeyNames -join ',') + '; VALUES=NOT_COLLECTED')
                foreach ($key in @($summary.Values.Keys | Sort-Object)) { Write-Audit ('INI_' + $key.ToUpperInvariant() + '=' + $summary.Values[$key]) }
                $hosts = New-Object 'System.Collections.Generic.List[string]'
                foreach ($key in @('server','host','datasource')) {
                    if ($summary.Values.ContainsKey($key) -and $summary.Values[$key] -match '^([A-Za-z0-9_.-]+)(?:/\d+)?$') { $hosts.Add($Matches[1]) }
                }
                foreach ($key in @('base','basename','database','databasename','db','dbpath','dbname')) {
                    if ($summary.Values.ContainsKey($key) -and $summary.Values[$key] -notmatch '^[A-Za-z]:[\\/]' -and
                        $summary.Values[$key] -match '^([A-Za-z0-9_.-]+)(?:/\d+)?:') { $hosts.Add($Matches[1]) }
                }
                $addresses = New-Object 'System.Collections.Generic.List[string]'
                foreach ($targetHost in @($hosts | Select-Object -Unique)) {
                    try {
                        $resolve = [Net.Dns]::GetHostAddressesAsync($targetHost)
                        if (-not $resolve.Wait(3000)) { Write-Audit ('DNS=' + $targetHost + '; STATUS=TIMEOUT'); continue }
                        foreach ($address in $resolve.Result) { $addresses.Add($address.ToString()) }
                        Write-Audit ('DNS=' + $targetHost + '; ADDRESSES=' + (($resolve.Result | ForEach-Object { $_.ToString() }) -join ','))
                    } catch { Write-Audit ('DNS=' + $targetHost + '; STATUS=UNAVAILABLE') }
                }
                try {
                    $rows = @(Get-NetTCPConnection -OwningProcess $cashier.Id -State Established -ErrorAction Stop)
                    $peers = @(Select-AuditPeers $rows $cashier.Id $addresses.ToArray())
                    Write-Audit ('CASHIER_TCP_MATCHES=' + $peers.Count + '; NOTE=IP_MATCH_DOES_NOT_PROVE_DATABASE_IDENTITY')
                    foreach ($peer in $peers) { Write-Audit ('CASHIER_TCP=PID:' + $cashier.Id + '; LOCAL=' + $peer.LocalAddress + ':' + $peer.LocalPort + '; REMOTE=' + $peer.RemoteAddress + ':' + $peer.RemotePort) }
                } catch { Write-Audit 'CASHIER_TCP=UNAVAILABLE_OR_NO_CONNECTION; отсутствие TCP не доказывает отсутствие доступа к БД (возможен XNET).' }
            } catch { Write-Audit ('INI_AUDIT=UNAVAILABLE; PATH=' + $ini) }
        }
    }
    $services = @(Get-CimInstance Win32_Service -Filter "Name LIKE '%Firebird%'" -OperationTimeoutSec 5 -ErrorAction SilentlyContinue)
    foreach ($service in $services) {
        Write-Audit ('FIREBIRD_SERVICE=' + $service.Name + '; STATE=' + $service.State + '; PID=' + $service.ProcessId)
        if ($service.Name -match 'Guardian') { continue }
        if ($service.PathName -match '^"([^"]+\.exe)"' -or $service.PathName -match '^([^\r\n]+?\.exe)(?:\s|$)') {
            $serverExe = $Matches[1]; Write-AuditVersion 'FIREBIRD_SERVICE_EXE' $serverExe
            $serverDir = Split-Path -Parent $serverExe
            foreach ($dir in @($serverDir, (Split-Path -Parent $serverDir)) | Select-Object -Unique) {
                $config = Join-Path $dir 'firebird.conf'
                if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { continue }
                try {
                    $values = Read-AuditFirebirdConfig $config
                    Write-Audit ('FIREBIRD_CONFIG_CANDIDATE=' + $config + '; NOTE=FILE_PRESENCE_DOES_NOT_PROVE_EFFECTIVE_SERVER_CONFIGURATION')
                    foreach ($key in @('remoteserviceport','remoteservicename','authentication')) {
                        $value = 'NOT_SET'
                        if ($values.ContainsKey($key)) { $value = $values[$key] }
                        Write-Audit ('FIREBIRD_CONFIG_' + $key.ToUpperInvariant() + '=' + $value)
                    }
                } catch { Write-Audit 'FIREBIRD_CONFIG=UNAVAILABLE' }
            }
        }
        if ($service.State -eq 'Running' -and $service.ProcessId -gt 0) {
            try {
                $listeners = @(Get-NetTCPConnection -OwningProcess $service.ProcessId -State Listen -ErrorAction Stop)
                foreach ($listener in $listeners) { Write-Audit ('FIREBIRD_LISTENER=PID:' + $service.ProcessId + '; LOCAL=' + $listener.LocalAddress + ':' + $listener.LocalPort) }
            } catch { Write-Audit 'FIREBIRD_LISTENER=UNAVAILABLE_OR_NONE' }
        }
    }
    try {
        $servicesFile = Join-Path $env:windir 'System32\drivers\etc\services'
        foreach ($line in [IO.File]::ReadAllLines($servicesFile)) {
            if ($line -match '^\s*(gds[-_]db)\s+(\d{1,5})/tcp\b') { Write-Audit ('SYSTEM_SERVICE_NAME=' + $Matches[1] + '; TCP_PORT=' + $Matches[2]) }
        }
    } catch { Write-Audit 'SYSTEM_GDS_DB_SERVICE=UNAVAILABLE' }
    Write-Audit 'AUDIT_COMPLETE; DATABASE_CONNECTION_ATTEMPTS=0'
    Write-Audit 'Пришлите этот TXT-отчёт. Батник не подключался к Firebird и не проверял пароли.'
}

if (-not $LibraryOnly) {
    $exitCode = 0
    try { Invoke-ConnectionAudit } catch {
        $exitCode = 1
        Write-Audit ('AUDIT_ERROR_TYPE=' + $_.Exception.GetType().Name + '; LINE=' + $_.InvocationInfo.ScriptLineNumber)
    } finally {
        Write-Audit '=== EPHARM CONNECTION AUDIT END ==='
        $folder = [Environment]::GetFolderPath('DesktopDirectory')
        if (-not $folder -or -not (Test-Path -LiteralPath $folder -PathType Container)) { $folder = [IO.Path]::GetTempPath() }
        $report = Join-Path $folder ('epharm-stock-connection-audit-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '.txt')
        try { [IO.File]::WriteAllLines($report, $script:AuditLines.ToArray(), (New-Object Text.UTF8Encoding($true))); Write-Host ('REPORT_FILE=' + $report) }
        catch { Write-Host 'Не удалось сохранить файл: скопируйте текст окна.'; $exitCode = 1 }
    }
    exit $exitCode
}
