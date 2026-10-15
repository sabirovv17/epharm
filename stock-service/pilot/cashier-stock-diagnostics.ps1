#requires -version 5.1
[CmdletBinding()]
param([switch]$LibraryOnly, [switch]$ManualAuthentication, [switch]$StockSchema, [switch]$StockProbe)

# Embedded in the BAT. Metadata modes read no products; StockProbe reads at most five indexed lots.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$script:Lines = New-Object 'System.Collections.Generic.List[string]'
$script:Secrets = New-Object 'System.Collections.Generic.List[string]'

function Protect-Output([string]$Text) {
    foreach ($secret in $script:Secrets) {
        if ($secret.Length -gt 0) { $Text = $Text.Replace($secret, '[REDACTED]') }
    }
    $Text = [regex]::Replace($Text, '(?im)\b(password|passwd|pwd|token|secret)\s*[:=]\s*[^\r\n]*', '$1=[REDACTED]')
    return [regex]::Replace($Text, '[\x00-\x08\x0B\x0C\x0E-\x1F]', '')
}

function Write-Report([string]$Text) {
    $safe = Protect-Output $Text
    $script:Lines.Add($safe)
    Write-Host $safe
}

function Read-ConnectSection([string]$Path, [switch]$IncludeAuthentication) {
    if ((Get-Item -LiteralPath $Path).Length -gt 262144) { throw 'INI_TOO_LARGE' }
    $reader = New-Object System.IO.StreamReader($Path, [Text.Encoding]::GetEncoding(1251), $true)
    try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $allowed = @('server','host','datasource','base','basename','database','databasename','db','dbpath','dbname','port')
    if ($IncludeAuthentication) { $allowed += @('login','user','username','password','pass','pwd') }
    $values = @{}
    $section = ''
    foreach ($raw in ($text -split '\r?\n')) {
        $line = $raw.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith(';') -or $line.StartsWith('#')) { continue }
        if ($line -match '^\[([^\]]+)\]$') { $section = $Matches[1]; continue }
        if ($section -ine 'Connect') { continue }
        $equals = $line.IndexOf('=')
        if ($equals -lt 1) { continue }
        $key = $line.Substring(0, $equals).Trim().ToLowerInvariant()
        if ($allowed -contains $key) {
            $value = $line.Substring($equals + 1).Trim().Trim('"', "'")
            if ($values.ContainsKey($key)) {
                $conflict = $values[$key] -ine $value
                if ($key -in @('password','pass','pwd')) { $conflict = $values[$key] -cne $value }
                if ($conflict) { throw 'CONFLICTING_DUPLICATE_INI_KEY' }
            }
            $values[$key] = $value
        }
    }
    return $values
}

function Get-FirstValue($Values, [string[]]$Keys, [switch]$CaseSensitive) {
    $found = ''
    foreach ($key in $Keys) {
        if ($Values.ContainsKey($key) -and -not [string]::IsNullOrWhiteSpace($Values[$key])) {
            $conflict = $found -and $found -ine $Values[$key]
            if ($CaseSensitive) { $conflict = $found -and $found -cne $Values[$key] }
            if ($conflict) { throw 'CONFLICTING_INI_ALIASES' }
            $found = [string]$Values[$key]
        }
    }
    return $found
}

function Get-ConfiguredAuthentication($Values) {
    $userName = Get-FirstValue $Values @('login','user','username')
    $plain = Get-FirstValue $Values @('password','pass','pwd') -CaseSensitive
    if (-not $userName -or -not $plain) { return $null }
    if ($userName -notmatch '^[A-Za-z0-9_$.-]{1,31}$') { throw 'UNSUPPORTED_AUTH_USERNAME' }
    $script:Secrets.Add($plain)
    return [pscustomobject]@{ UserName = $userName; Password = (ConvertTo-SecureString $plain -AsPlainText -Force) }
}

function Get-MetadataFailure([string]$Output) {
    if ($Output -match '(?i)335544472|Your user name and password are not defined|authentication failed|login failed') { return 'AUTH_FAILED' }
    if ($Output -match '(?i)335544352|no permission for|permission denied') { return 'PERMISSION_DENIED' }
    if ($Output -match '(?i)335544361|attempted update during read-only transaction') { return 'READONLY_TRANSACTION_REJECTED' }
    if ($Output -match 'SQLSTATE\s*=\s*28000') { return 'AUTH_OR_PERMISSION_FAILED' }
    if ($Output -match '(?i)SQLSTATE\s*=\s*08[0-9]{3}|Unable to complete network request') { return 'CONNECTION_FAILED' }
    return 'SQL_OR_CLIENT_ERROR'
}

function Get-Endpoint($Values, [bool]$LocalServerRunning) {
    $server = Get-FirstValue $Values @('server','host','datasource')
    $database = Get-FirstValue $Values @('base','basename','database','databasename','db','dbpath','dbname')
    $portText = Get-FirstValue $Values @('port')
    if ([string]::IsNullOrWhiteSpace($database)) { throw 'NO_DATABASE' }
    if ($database -match '[;?="''\r\n]' -or $database.StartsWith('\\')) { throw 'UNSUPPORTED_DATABASE_FORMAT' }
    # Accept host/port:C:\path, C:\path and aliases; never attach an FDB with the embedded engine.
    if ($database -match '^(?<server>[A-Za-z0-9_.-]+(?:/[0-9]+)?):(?<db>.+)$' -and $database -notmatch '^[A-Za-z]:[\\/]') {
        $embeddedServer = $Matches['server']
        $database = $Matches['db']
        if ($server.Length -gt 0 -and $server -ine $embeddedServer) { throw 'CONFLICTING_SERVER_SETTINGS' }
        $server = $embeddedServer
    }
    if ($server.Length -eq 0) {
        if (-not $LocalServerRunning) { throw 'NO_CONFIRMED_LOCAL_SERVER' }
        $server = 'localhost'
    }
    if ($server -notmatch '^(?<host>[A-Za-z0-9_.-]+)(?:/(?<port>[0-9]+))?$') { throw 'UNSUPPORTED_SERVER_FORMAT' }
    $hostName = $Matches['host'].TrimEnd('.')
    $serverPort = $Matches['port']
    if ($serverPort -and $portText -and $serverPort -ne $portText) { throw 'CONFLICTING_PORT_SETTINGS' }
    if ($serverPort) { $portText = $serverPort }
    if (-not $portText) { $portText = '3050' }
    $port = 0
    if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { throw 'INVALID_PORT' }
    if ($database -notmatch '^[A-Za-z]:[\\/].+' -and $database -notmatch '^[A-Za-z0-9_.-]+$') { throw 'UNSUPPORTED_DATABASE_FORMAT' }
    $dbNormalized = $database.Replace('/', '\').ToUpperInvariant()
    $blocked = $hostName -match '^(?i:INKSTDN)(\.|$)' -or $hostName -in @('10.10.1.94','10.10.1.76','10.10.1.80','10.10.1.81') -or
        $dbNormalized -match '(^|\\)BASE_G(\\|$)|(^|\\)ZTRADE_G(\.|$)'
    return [pscustomobject]@{ HostName = $hostName; Port = $port; Database = $database; Blocked = [bool]$blocked; Connection = "${hostName}/${port}:$database" }
}

function Quote-NativeArgument([string]$Value) {
    if ($Value -match '["\r\n]' -or $Value.EndsWith('\')) { throw 'INVALID_PROCESS_ARGUMENT' }
    return '"' + $Value + '"'
}

function Get-MetadataSql {
    return @'
SET ECHO OFF;
SET AUTODDL OFF;
SET BAIL ON;
SET LIST ON;
ROLLBACK;
SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;
SELECT RDB$GET_CONTEXT('SYSTEM', 'ENGINE_VERSION') AS ENGINE_VERSION,
       RDB$GET_CONTEXT('SYSTEM', 'READ_ONLY') AS DIAGNOSTIC_READ_ONLY,
       CURRENT_USER AS CONNECTED_USER, CURRENT_ROLE AS CONNECTED_ROLE,
       CURRENT_TIMESTAMP AS DATABASE_TIME
FROM RDB$DATABASE;
SELECT FIRST 120 TRIM(r.RDB$RELATION_NAME) AS RELATION_NAME,
       CASE WHEN r.RDB$VIEW_BLR IS NOT NULL THEN 1 ELSE 0 END AS IS_VIEW
FROM RDB$RELATIONS r
WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0
ORDER BY r.RDB$RELATION_NAME;
SELECT COUNT(*) AS USER_RELATIONS_TOTAL FROM RDB$RELATIONS r
WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0;
SELECT FIRST 400 TRIM(rf.RDB$RELATION_NAME) AS RELATION_NAME,
       rf.RDB$FIELD_POSITION AS FIELD_ORDINAL, TRIM(rf.RDB$FIELD_NAME) AS FIELD_NAME,
       f.RDB$FIELD_TYPE AS FIELD_TYPE, f.RDB$FIELD_SUB_TYPE AS FIELD_SUB_TYPE,
       f.RDB$FIELD_SCALE AS FIELD_SCALE, f.RDB$FIELD_LENGTH AS FIELD_LENGTH
FROM RDB$RELATION_FIELDS rf
JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE
WHERE rf.RDB$RELATION_NAME CONTAINING 'WARE' OR rf.RDB$RELATION_NAME CONTAINING 'STOCK'
   OR rf.RDB$RELATION_NAME CONTAINING 'RESERV' OR rf.RDB$RELATION_NAME CONTAINING 'PART'
   OR rf.RDB$RELATION_NAME CONTAINING 'GOOD'
ORDER BY rf.RDB$RELATION_NAME, rf.RDB$FIELD_POSITION;
SELECT FIRST 200 TRIM(i.RDB$RELATION_NAME) AS RELATION_NAME,
       TRIM(i.RDB$INDEX_NAME) AS INDEX_NAME, i.RDB$INDEX_INACTIVE AS INACTIVE,
       s.RDB$FIELD_POSITION AS INDEX_FIELD_ORDINAL, TRIM(s.RDB$FIELD_NAME) AS FIELD_NAME
FROM RDB$INDICES i
JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = i.RDB$INDEX_NAME
WHERE i.RDB$RELATION_NAME CONTAINING 'WARE' OR i.RDB$RELATION_NAME CONTAINING 'STOCK'
   OR i.RDB$RELATION_NAME CONTAINING 'RESERV' OR i.RDB$RELATION_NAME CONTAINING 'PART'
   OR i.RDB$RELATION_NAME CONTAINING 'GOOD'
ORDER BY i.RDB$RELATION_NAME, i.RDB$INDEX_NAME, s.RDB$FIELD_POSITION;
SELECT 'EPHARM_METADATA_COMPLETE_V4' AS DIAGNOSTIC_COMPLETE FROM RDB$DATABASE;
ROLLBACK;
QUIT;
'@
}

function Get-StockSchemaSql {
    return @'
SET ECHO OFF;
SET AUTODDL OFF;
SET BAIL ON;
SET LIST ON;
ROLLBACK;
SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;
SELECT RDB$GET_CONTEXT('SYSTEM', 'ENGINE_VERSION') AS ENGINE_VERSION,
       RDB$GET_CONTEXT('SYSTEM', 'READ_ONLY') AS DIAGNOSTIC_READ_ONLY,
       CURRENT_USER AS CONNECTED_USER, CURRENT_TIMESTAMP AS DATABASE_TIME
FROM RDB$DATABASE;
SELECT TRIM(r.RDB$RELATION_NAME) AS TARGET_RELATION,
       r.RDB$RELATION_TYPE AS RELATION_TYPE,
       CASE WHEN r.RDB$VIEW_BLR IS NOT NULL THEN 1 ELSE 0 END AS IS_VIEW
FROM RDB$RELATIONS r
WHERE r.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS')
ORDER BY r.RDB$RELATION_NAME;
SELECT COUNT(*) AS TARGET_FIELDS_TOTAL FROM RDB$RELATION_FIELDS rf
WHERE rf.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS');
SELECT FIRST 800 TRIM(rf.RDB$RELATION_NAME) AS RELATION_NAME,
       rf.RDB$FIELD_POSITION AS FIELD_ORDINAL, TRIM(rf.RDB$FIELD_NAME) AS FIELD_NAME,
       f.RDB$FIELD_TYPE AS FIELD_TYPE, f.RDB$FIELD_SUB_TYPE AS FIELD_SUB_TYPE,
       f.RDB$FIELD_SCALE AS FIELD_SCALE, f.RDB$FIELD_LENGTH AS FIELD_LENGTH,
       f.RDB$CHARACTER_LENGTH AS CHAR_COUNT, f.RDB$CHARACTER_SET_ID AS CHARACTER_SET_ID,
       COALESCE(rf.RDB$NULL_FLAG, f.RDB$NULL_FLAG, 0) AS NOT_NULL_FLAG,
       CASE WHEN f.RDB$COMPUTED_BLR IS NOT NULL THEN 1 ELSE 0 END AS IS_COMPUTED,
       COALESCE(CHAR_LENGTH(f.RDB$COMPUTED_SOURCE), 0) AS COMPUTED_SOURCE_CHARS,
       CASE WHEN f.RDB$COMPUTED_BLR IS NOT NULL AND COALESCE(CHAR_LENGTH(f.RDB$COMPUTED_SOURCE), 0) = 0
            THEN 1 ELSE 0 END AS SOURCE_UNAVAILABLE,
       CAST(SUBSTRING(f.RDB$COMPUTED_SOURCE FROM 1 FOR 2048) AS VARCHAR(2048)) AS COMPUTED_SQL
FROM RDB$RELATION_FIELDS rf
JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE
WHERE rf.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS')
ORDER BY rf.RDB$RELATION_NAME, rf.RDB$FIELD_POSITION;
SELECT COUNT(*) AS TARGET_INDEX_SEGMENTS_TOTAL FROM RDB$INDICES i
JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = i.RDB$INDEX_NAME
WHERE i.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS');
SELECT FIRST 400 TRIM(i.RDB$RELATION_NAME) AS RELATION_NAME,
       TRIM(i.RDB$INDEX_NAME) AS INDEX_NAME, i.RDB$INDEX_INACTIVE AS INACTIVE,
       i.RDB$UNIQUE_FLAG AS UNIQUE_FLAG, i.RDB$INDEX_TYPE AS INDEX_DIRECTION,
       i.RDB$STATISTICS AS INDEX_SELECTIVITY,
       s.RDB$FIELD_POSITION AS INDEX_FIELD_ORDINAL, TRIM(s.RDB$FIELD_NAME) AS FIELD_NAME
FROM RDB$INDICES i
JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = i.RDB$INDEX_NAME
WHERE i.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS')
ORDER BY i.RDB$RELATION_NAME, i.RDB$INDEX_NAME, s.RDB$FIELD_POSITION;
SELECT COUNT(*) AS TARGET_CONSTRAINTS_TOTAL FROM RDB$RELATION_CONSTRAINTS rc
WHERE rc.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS');
SELECT FIRST 400 TRIM(rc.RDB$RELATION_NAME) AS RELATION_NAME,
       TRIM(rc.RDB$CONSTRAINT_NAME) AS CONSTRAINT_NAME,
       TRIM(rc.RDB$CONSTRAINT_TYPE) AS CONSTRAINT_TYPE, TRIM(rc.RDB$INDEX_NAME) AS CONSTRAINT_INDEX
FROM RDB$RELATION_CONSTRAINTS rc
WHERE rc.RDB$RELATION_NAME IN ('WAREBASE', 'PARTS', 'VW_WAREBASE', 'VW_WAREBASE_KASSA', 'WAREBASEPARTS', 'WAREBASEFOLDERS')
ORDER BY rc.RDB$RELATION_NAME, rc.RDB$CONSTRAINT_NAME;
SELECT TRIM(r.RDB$RELATION_NAME) AS VIEW_NAME,
       COALESCE(CHAR_LENGTH(r.RDB$VIEW_SOURCE), 0) AS VIEW_SOURCE_CHARS,
       CASE WHEN COALESCE(CHAR_LENGTH(r.RDB$VIEW_SOURCE), 0) = 0 THEN 1 ELSE 0 END AS SOURCE_UNAVAILABLE,
       CAST(SUBSTRING(r.RDB$VIEW_SOURCE FROM 1 FOR 8000) AS VARCHAR(8000)) AS VIEW_SQL
FROM RDB$RELATIONS r
WHERE r.RDB$RELATION_NAME IN ('VW_WAREBASE', 'VW_WAREBASE_KASSA') AND r.RDB$VIEW_BLR IS NOT NULL
ORDER BY r.RDB$RELATION_NAME;
SELECT COUNT(*) AS TARGET_DEPENDENCIES_TOTAL FROM RDB$DEPENDENCIES d
WHERE d.RDB$DEPENDENT_NAME IN ('VW_WAREBASE', 'VW_WAREBASE_KASSA') AND d.RDB$DEPENDENT_TYPE = 1;
SELECT FIRST 400 TRIM(d.RDB$DEPENDENT_NAME) AS DEPENDENT_NAME,
       TRIM(d.RDB$DEPENDED_ON_NAME) AS DEPENDENCY_OBJECT,
       d.RDB$DEPENDED_ON_TYPE AS DEPENDENCY_TYPE, TRIM(d.RDB$FIELD_NAME) AS DEPENDENCY_FIELD
FROM RDB$DEPENDENCIES d
WHERE d.RDB$DEPENDENT_NAME IN ('VW_WAREBASE', 'VW_WAREBASE_KASSA') AND d.RDB$DEPENDENT_TYPE = 1
ORDER BY d.RDB$DEPENDENT_NAME, d.RDB$DEPENDED_ON_NAME, d.RDB$FIELD_NAME;
SELECT 'EPHARM_STOCK_SCHEMA_COMPLETE_V7' AS DIAGNOSTIC_COMPLETE FROM RDB$DATABASE;
ROLLBACK;
QUIT;
'@
}

function Get-MetadataRecords([string]$Output) {
    $records = New-Object 'System.Collections.Generic.List[hashtable]'
    $record = $null
    foreach ($line in ($Output -split '\r?\n')) {
        if ($line -cnotmatch '^\s*([A-Z_]+)\s+([^\r\n]*?)\s*$') { continue }
        $key = $Matches[1]; $value = $Matches[2]
        if ($key -in @('TARGET_RELATION', 'RELATION_NAME', 'VIEW_NAME', 'DEPENDENT_NAME')) {
            $record = @{}; $records.Add($record)
        }
        if ($null -ne $record) { $record[$key] = $value }
    }
    return $records.ToArray()
}

function Test-StockSchemaComplete([string]$Output) {
    $Output = $Output -replace '\r\n?', "`n"
    foreach ($required in @('WAREBASE', 'PARTS')) {
        if ($Output -notmatch ('(?im)^\s*TARGET_RELATION\s+' + $required + '\s*$')) { return $false }
    }
    foreach ($check in @(
        @('TARGET_FIELDS_TOTAL', 'FIELD_ORDINAL', 800),
        @('TARGET_INDEX_SEGMENTS_TOTAL', 'INDEX_FIELD_ORDINAL', 400),
        @('TARGET_CONSTRAINTS_TOTAL', 'CONSTRAINT_NAME', 400),
        @('TARGET_DEPENDENCIES_TOTAL', 'DEPENDENT_NAME', 400)
    )) {
        $total = [regex]::Match($Output, '(?im)^\s*' + $check[0] + '\s+(\d+)\s*$')
        if (-not $total.Success) { return $false }
        $count = [int]$total.Groups[1].Value
        if ($count -gt $check[2] -or [regex]::Matches($Output, '(?im)^\s*' + $check[1] + '\s+\S+[^\r\n]*$').Count -ne $count) { return $false }
    }
    $fieldRows = [regex]::Matches($Output, '(?im)^\s*FIELD_ORDINAL\s+\d+\s*$').Count
    $viewRows = [regex]::Matches($Output, '(?im)^\s*VIEW_NAME\s+\S+[^\r\n]*$').Count
    $sourceFlags = [regex]::Matches($Output, '(?im)^\s*SOURCE_UNAVAILABLE\s+([01])\s*$')
    if ($sourceFlags.Count -ne $fieldRows + $viewRows) { return $false }
    if ([regex]::Matches($Output, '(?im)^\s*COMPUTED_SOURCE_CHARS\s+\d+\s*$').Count -ne $fieldRows -or
        [regex]::Matches($Output, '(?im)^\s*VIEW_SOURCE_CHARS\s+\d+\s*$').Count -ne $viewRows) { return $false }
    $relations = @{}; $views = @{}
    $records = @(Get-MetadataRecords $Output)
    foreach ($record in $records) {
        if ($record.ContainsKey('TARGET_RELATION')) { $relations[$record['TARGET_RELATION']] = $record }
        if ($record.ContainsKey('VIEW_NAME')) {
            if ($record['SOURCE_UNAVAILABLE'] -ne '0' -or $record['VIEW_SOURCE_CHARS'] -notmatch '^\d+$' -or
                [int]$record['VIEW_SOURCE_CHARS'] -le 0 -or [int]$record['VIEW_SOURCE_CHARS'] -gt 8000) { return $false }
            $views[$record['VIEW_NAME']] = $record
        }
    }
    foreach ($required in @('WAREBASE', 'PARTS')) {
        if (-not $relations.ContainsKey($required) -or $relations[$required]['IS_VIEW'] -ne '0') { return $false }
    }
    foreach ($record in $records) {
        if (-not $record.ContainsKey('FIELD_ORDINAL')) { continue }
        $relation = $record['RELATION_NAME']
        if (-not $relations.ContainsKey($relation)) { return $false }
        if ($relations[$relation]['IS_VIEW'] -eq '1') {
            # View expressions have BLR without individual source; their full VIEW_SOURCE is authoritative.
            if (-not $views.ContainsKey($relation)) { return $false }
        } elseif ($record['SOURCE_UNAVAILABLE'] -ne '0' -or [int]$record['COMPUTED_SOURCE_CHARS'] -gt 2048) { return $false }
    }
    return $true
}

function Invoke-IsqlBatch([string]$Isql, $Endpoint, [Security.SecureString]$Password, [string]$UserName, [string]$Sql) {
    $sqlFile = [IO.Path]::GetTempFileName()
    $process = New-Object Diagnostics.Process
    $pointer = [IntPtr]::Zero
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        if ($UserName -notmatch '^[A-Za-z0-9_$.-]{1,31}$') { throw 'UNSUPPORTED_AUTH_USERNAME' }
        [IO.File]::WriteAllText($sqlFile, $Sql, [Text.Encoding]::ASCII)
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = $Isql
        $start.WorkingDirectory = Split-Path -Parent $Isql
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.RedirectStandardInput = $true
        $start.StandardOutputEncoding = [Text.Encoding]::UTF8
        $start.StandardErrorEncoding = [Text.Encoding]::UTF8
        $start.Arguments = '-bail -charset UTF8 -input ' + (Quote-NativeArgument $sqlFile) + ' ' + (Quote-NativeArgument $Endpoint.Connection)
        $start.EnvironmentVariables['ISC_USER'] = $UserName
        $start.EnvironmentVariables.Remove('ISC_ROLE')
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        $script:Secrets.Add($plain)
        $start.EnvironmentVariables['ISC_PASSWORD'] = $plain
        $process.StartInfo = $start
        if (-not $process.Start()) { throw 'ISQL_START_FAILED' }
        $start.EnvironmentVariables.Remove('ISC_PASSWORD')
        $plain = $null
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) {
            try { $process.Kill() } catch { }
            $exited = $process.WaitForExit(2000)
            return [pscustomobject]@{ TimedOut=$true; ChildExited=$exited; ExitCode=$null; Output=''; Truncated=$false; ElapsedMs=$clock.ElapsedMilliseconds }
        }
        if (-not $stdout.Wait(2000) -or -not $stderr.Wait(2000)) { throw 'ISQL_OUTPUT_TIMEOUT' }
        $combined = ($stdout.Result + "`n" + $stderr.Result) -replace '\r\n?', "`n"
        $truncated = $combined.Length -gt 524288
        if ($truncated) { $combined = $combined.Substring(0, 524288) + "`r`n[OUTPUT_TRUNCATED]" }
        return [pscustomobject]@{ TimedOut=$false; ChildExited=$true; ExitCode=$process.ExitCode; Output=$combined; Truncated=$truncated; ElapsedMs=$clock.ElapsedMilliseconds }
    } finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $Password.Dispose()
        try { if ($process.Id -and -not $process.HasExited) { $process.Kill() } } catch { }
        $process.Dispose()
        Remove-Item -LiteralPath $sqlFile -Force -ErrorAction SilentlyContinue
    }
}

function Write-IsqlTimeout($Result, [string]$StatusName) {
    Write-Report ($StatusName + '=TIMEOUT; no retry; only this diagnostic child was stopped.')
    Write-Report ('ISQL_CHILD_EXITED=' + $Result.ChildExited)
    Write-Report 'NOTE=Client timeout is not proof of immediate server-side query cancellation.'
}

function Invoke-Metadata([string]$Isql, $Endpoint, [Security.SecureString]$Password, [string]$UserName, [switch]$StockSchema) {
        $sql = if ($StockSchema) { Get-StockSchemaSql } else { Get-MetadataSql }
        $marker = if ($StockSchema) { 'EPHARM_STOCK_SCHEMA_COMPLETE_V7' } else { 'EPHARM_METADATA_COMPLETE_V4' }
        $result = Invoke-IsqlBatch $Isql $Endpoint $Password $UserName $sql
        if ($result.TimedOut) { Write-IsqlTimeout $result 'METADATA_STATUS'; return }
        $combined = $result.Output
        Write-Report ('ISQL_EXIT_CODE=' + $result.ExitCode)
        if ($StockSchema) { Write-Report '--- SYSTEM METADATA BEGIN (targeted schema; row limits: 800 fields/400 index segments/400 constraints/400 dependencies) ---' }
        else { Write-Report '--- SYSTEM METADATA BEGIN (row limits: 120/400/200) ---' }
        Write-Report $combined
        Write-Report '--- SYSTEM METADATA END ---'
        $hasFailure = $result.Truncated -or $combined -match '(?i)SQLSTATE\s*=|Statement failed'
        $schemaComplete = -not $StockSchema -or (Test-StockSchemaComplete $combined)
        if ($result.ExitCode -eq 0 -and -not $hasFailure -and $combined.Contains($marker) -and $schemaComplete -and
            $combined -match '(?im)^\s*DIAGNOSTIC_READ_ONLY\s+TRUE\s*$') {
            Write-Report 'METADATA_STATUS=OK; READONLY_TRANSACTION=VERIFIED; account grants not independently verified.'
            if ($StockSchema) { Write-Report 'TARGET_SCHEMA_COMPLETE=TRUE; scope=requested objects only; warehouse rows not read.' }
            Write-Report 'Структура БД успешно получена. Пришлите этот TXT-отчёт.'
        } else {
            $failure = Get-MetadataFailure $combined
            if ($result.ExitCode -eq 0 -and -not $hasFailure) { $failure = 'INCOMPLETE_OR_UNVERIFIED_READONLY' }
            if ($StockSchema -and -not $schemaComplete -and -not $hasFailure -and $result.ExitCode -eq 0) { $failure = 'TARGET_SCHEMA_INCOMPLETE_OR_TRUNCATED' }
            Write-Report ('METADATA_STATUS=FAILED; REASON=' + $failure + '; no retry and no alternate credentials.')
            switch ($failure) {
                'AUTH_FAILED' { Write-Report 'Причина: Firebird не принял логин/пароль на выбранном сервере. Нужны действующие данные подключения именно к этой кассовой БД.' }
                'PERMISSION_DENIED' { Write-Report 'Причина: учётной записи не хватает прав для чтения метаданных. Права и пользователей этот батник не меняет.' }
                'AUTH_OR_PERMISSION_FAILED' { Write-Report 'Причина: ошибка авторизации или прав доступа; конкретную причину уточним по сообщению Firebird выше.' }
                'READONLY_TRANSACTION_REJECTED' { Write-Report 'Причина: Firebird отклонил работу в транзакции только для чтения. Возможно, мешает серверный триггер; пришлите отчёт.' }
                'CONNECTION_FAILED' { Write-Report 'Причина: не удалось подключиться к серверу Firebird. Пришлите отчёт; настройки кассы не меняйте.' }
                default { Write-Report 'Чтение структуры не подтверждено. Пришлите отчёт с сообщением Firebird выше.' }
            }
        }
}

function Get-StockProbeSelector([string]$Identifier) {
    if ($Identifier -ceq 'RECENT') {
        return [pscustomobject]@{ Index='WAREBASE_IDX5'; Field='PART_ID'; Unique=0; Direction=1; Predicate='w.PART_ID > 0'; Limit=5 }
    }
    if ($Identifier -cmatch '^PART:([0-9]{1,19})$') {
        $partId = [long]0
        if (-not [long]::TryParse($Matches[1], [ref]$partId) -or $partId -le 0) { throw 'INVALID_PART_ID' }
        return [pscustomobject]@{ Index='PK_WAREBASE'; Field='PART_ID'; Unique=1; Direction=0; Predicate=('w.PART_ID = ' + $partId.ToString([Globalization.CultureInfo]::InvariantCulture)); Limit=1 }
    }
    if ($Identifier -cnotmatch '^[0-9]{6,32}$') { throw 'INVALID_PRODUCT_BARCODE' }
    return [pscustomobject]@{ Index='WAREBASE_IDX2'; Field='BCODE_IZG'; Unique=0; Direction=0; Predicate=("w.BCODE_IZG = '" + $Identifier + "'"); Limit=5 }
}

function Get-StockProbeSql([string]$Identifier, [switch]$PlanOnly) {
    $selector = Get-StockProbeSelector $Identifier
    $template = @'
SET ECHO OFF;
SET AUTODDL OFF;
SET BAIL ON;
SET LIST ON;
ROLLBACK;
SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;
SELECT RDB$GET_CONTEXT('SYSTEM', 'ENGINE_VERSION') AS ENGINE_VERSION,
       RDB$GET_CONTEXT('SYSTEM', 'READ_ONLY') AS DIAGNOSTIC_READ_ONLY,
       CURRENT_TIMESTAMP AS SOURCE_DATABASE_TIME FROM RDB$DATABASE;
SELECT r.RDB$RELATION_TYPE AS PROBE_TABLE_TYPE FROM RDB$RELATIONS r WHERE r.RDB$RELATION_NAME = 'WAREBASE';
SELECT COUNT(*) AS PROBE_FIELD_COUNT,
       COALESCE(SUM(CASE WHEN f.RDB$COMPUTED_BLR IS NOT NULL THEN 1 ELSE 0 END), 0) AS PROBE_COMPUTED_COUNT
FROM RDB$RELATION_FIELDS rf JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE
WHERE rf.RDB$RELATION_NAME = 'WAREBASE'
  AND rf.RDB$FIELD_NAME IN ('PART_ID', 'WARE_ID', 'BCODE_IZG', 'BARCODE', 'SNAME', 'QUANT', 'REALQUANT', 'BLOCK_QUANT',
                          'BLOCK_COUNT', 'GODENDO', 'ENDDT', 'EDIZM', 'PART_TYPE', 'BASE_AGENT_ID', 'DEP', 'UPDATEDT');
SELECT TRIM(i.RDB$RELATION_NAME) AS PROBE_INDEX_TABLE, TRIM(i.RDB$INDEX_NAME) AS PROBE_INDEX_NAME,
       COALESCE(i.RDB$INDEX_INACTIVE, 0) AS PROBE_INDEX_INACTIVE, s.RDB$FIELD_POSITION AS PROBE_INDEX_ORDINAL,
       TRIM(s.RDB$FIELD_NAME) AS PROBE_INDEX_FIELD
FROM RDB$INDICES i JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = i.RDB$INDEX_NAME
WHERE i.RDB$INDEX_NAME = '__INDEX__' ORDER BY s.RDB$FIELD_POSITION;
-- Abort the batch before preparing/executing a product SELECT if the schema has changed.
SELECT 1 / CASE WHEN
 (SELECT COUNT(*) FROM RDB$RELATIONS r WHERE r.RDB$RELATION_NAME = 'WAREBASE'
    AND r.RDB$RELATION_TYPE = 0 AND r.RDB$VIEW_BLR IS NULL) = 1
 AND (SELECT COUNT(*) FROM RDB$RELATION_FIELDS rf JOIN RDB$FIELDS f ON f.RDB$FIELD_NAME = rf.RDB$FIELD_SOURCE
      WHERE rf.RDB$RELATION_NAME = 'WAREBASE' AND f.RDB$COMPUTED_BLR IS NULL
       AND rf.RDB$FIELD_NAME IN ('PART_ID', 'WARE_ID', 'BCODE_IZG', 'BARCODE', 'SNAME', 'QUANT', 'REALQUANT', 'BLOCK_QUANT',
                               'BLOCK_COUNT', 'GODENDO', 'ENDDT', 'EDIZM', 'PART_TYPE', 'BASE_AGENT_ID', 'DEP', 'UPDATEDT')) = 16
 AND (SELECT COUNT(*) FROM RDB$INDEX_SEGMENTS s WHERE s.RDB$INDEX_NAME = '__INDEX__') = 1
 AND (SELECT COUNT(*) FROM RDB$INDICES i JOIN RDB$INDEX_SEGMENTS s ON s.RDB$INDEX_NAME = i.RDB$INDEX_NAME
      WHERE i.RDB$INDEX_NAME = '__INDEX__' AND i.RDB$RELATION_NAME = 'WAREBASE'
       AND COALESCE(i.RDB$INDEX_INACTIVE, 0) = 0 AND COALESCE(i.RDB$UNIQUE_FLAG, 0) = __INDEX_UNIQUE__
       AND COALESCE(i.RDB$INDEX_TYPE, 0) = __INDEX_DIRECTION__
       AND s.RDB$FIELD_POSITION = 0 AND s.RDB$FIELD_NAME = '__INDEX_FIELD__') = 1
 THEN 1 ELSE 0 END AS PROBE_SCHEMA_GUARD FROM RDB$DATABASE;
SET PLAN ON;
SET PLANONLY __PLAN_ONLY__;
SET STATS ON;
SELECT FIRST __ROW_LIMIT__ w.PART_ID AS SAMPLE_PART_ID, w.WARE_ID AS SAMPLE_WARE_ID,
       w.BCODE_IZG AS SAMPLE_EAN, w.BARCODE AS SAMPLE_INTERNAL_BARCODE, w.SNAME AS SAMPLE_PRODUCT_NAME,
       w.QUANT AS SAMPLE_QUANT, w.REALQUANT AS SAMPLE_REALQUANT,
       w.BLOCK_QUANT AS SAMPLE_BLOCK_QUANT, w.BLOCK_COUNT AS SAMPLE_BLOCK_COUNT,
       w.GODENDO AS SAMPLE_EXPIRY, w.ENDDT AS SAMPLE_ENDDT, w.EDIZM AS SAMPLE_UNIT,
       w.PART_TYPE AS SAMPLE_PART_TYPE, w.BASE_AGENT_ID AS SAMPLE_BASE_AGENT_ID,
       w.DEP AS SAMPLE_DEP, w.UPDATEDT AS SAMPLE_UPDATED_AT
FROM WAREBASE w WHERE __PREDICATE__
PLAN (w __ACCESS__)
__ORDER_BY__;
SET STATS OFF;
SET PLANONLY OFF;
SET PLAN OFF;
SELECT 'EPHARM_STOCK___PHASE___COMPLETE_V8' AS DIAGNOSTIC_COMPLETE FROM RDB$DATABASE;
ROLLBACK;
QUIT;
'@
    $mode = if ($PlanOnly) { 'ON' } else { 'OFF' }
    $phase = if ($PlanOnly) { 'PLAN' } else { 'SAMPLE' }
    # Fetch one ID page without a quantity predicate; no search through zero or positive quantities.
    $access = if ($Identifier -ceq 'RECENT') { 'ORDER ' + $selector.Index } else { 'INDEX (' + $selector.Index + ')' }
    $orderBy = if ($Identifier -ceq 'RECENT') { 'ORDER BY w.PART_ID DESC' } else { '' }
    return $template.Replace('__INDEX__', $selector.Index).Replace('__INDEX_FIELD__', $selector.Field).Replace('__INDEX_UNIQUE__', [string]$selector.Unique).Replace('__INDEX_DIRECTION__', [string]$selector.Direction).Replace('__PREDICATE__', $selector.Predicate).Replace('__ROW_LIMIT__', [string]$selector.Limit).Replace('__PLAN_ONLY__', $mode).Replace('__PHASE__', $phase).Replace('__ACCESS__', $access).Replace('__ORDER_BY__', $orderBy)
}

function Test-StockProbeResult($Result, [string]$Identifier, [switch]$PlanOnly) {
    if ($Result.TimedOut -or $Result.Truncated -or $Result.ExitCode -ne 0) { return $false }
    $output = $Result.Output -replace '\r\n?', "`n"
    $selector = Get-StockProbeSelector $Identifier
    $phase = if ($PlanOnly) { 'PLAN' } else { 'SAMPLE' }
    if ($output -match '(?i)SQLSTATE\s*=|Statement failed' -or
        $output -notmatch ('(?m)^\s*DIAGNOSTIC_COMPLETE\s+EPHARM_STOCK_' + $phase + '_COMPLETE_V8\s*$')) { return $false }
    foreach ($pair in @(@('DIAGNOSTIC_READ_ONLY','TRUE'), @('PROBE_TABLE_TYPE','0'), @('PROBE_FIELD_COUNT','16'),
        @('PROBE_COMPUTED_COUNT','0'), @('PROBE_SCHEMA_GUARD','1'), @('PROBE_INDEX_TABLE','WAREBASE'), @('PROBE_INDEX_NAME',$selector.Index),
        @('PROBE_INDEX_INACTIVE','0'), @('PROBE_INDEX_ORDINAL','0'), @('PROBE_INDEX_FIELD',$selector.Field))) {
        $values = [regex]::Matches($output, '(?m)^\s*' + $pair[0] + '\s+(\S+)\s*$')
        if ($values.Count -ne 1 -or $values[0].Groups[1].Value -cne $pair[1]) { return $false }
    }
    $plans = [regex]::Matches($output, '(?im)^\s*PLAN\s+([^\r\n]+)\s*$')
    $expectedPlan = if ($Identifier -ceq 'RECENT') { '(?i)^\(W\s+ORDER\s+' + $selector.Index + '\)\s*$' } else { '(?i)^\(W\s+INDEX\s*\(' + $selector.Index + '\)\)\s*$' }
    if ($plans.Count -ne 1 -or $plans[0].Groups[1].Value -notmatch $expectedPlan) { return $false }
    $rows = [regex]::Matches($output, '(?m)^\s*SAMPLE_PART_ID\s+(\S+)\s*$')
    if ($rows.Count -gt $selector.Limit -or ($PlanOnly -and $rows.Count -ne 0)) { return $false }
    $seen = @{}
    foreach ($row in $rows) {
        $partId = [long]0
        if (-not [long]::TryParse($row.Groups[1].Value, [ref]$partId) -or $partId -le 0 -or $seen.ContainsKey($partId)) { return $false }
        if ($selector.Field -eq 'PART_ID' -and $selector.Limit -eq 1 -and ('w.PART_ID = ' + $partId.ToString([Globalization.CultureInfo]::InvariantCulture)) -cne $selector.Predicate) { return $false }
        $seen[$partId] = $true
    }
    foreach ($column in @('SAMPLE_QUANT', 'SAMPLE_REALQUANT', 'SAMPLE_BLOCK_QUANT', 'SAMPLE_BLOCK_COUNT')) {
        if ([regex]::Matches($output, '(?m)^\s*' + $column + '\s+\S+[^\r\n]*$').Count -ne $rows.Count) { return $false }
    }
    if ($selector.Field -eq 'BCODE_IZG') {
        $barcodes = [regex]::Matches($output, '(?m)^\s*SAMPLE_EAN\s+(\S+)\s*$')
        if ($barcodes.Count -ne $rows.Count) { return $false }
        foreach ($barcode in $barcodes) { if ($barcode.Groups[1].Value -cne $Identifier) { return $false } }
    }
    return $true
}

function Get-PositiveProbeRows([string]$Output) {
    $count = 0
    foreach ($quantity in [regex]::Matches($Output, '(?m)^\s*SAMPLE_QUANT\s+(\S+)\s*$')) {
        $value = [double]0
        if ([double]::TryParse($quantity.Groups[1].Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$value) -and
            -not [double]::IsNaN($value) -and -not [double]::IsInfinity($value) -and $value -gt 0) { $count++ }
    }
    return $count
}

function Invoke-StockProbe([string]$Isql, $Endpoint, [Security.SecureString]$Password, [string]$UserName) {
    try {
        Write-Report 'Введите RECENT для одной страницы до 5 партий с наибольшими ID. Также можно ввести штрихкод производителя (6–32 цифры) либо PART:123. Enter — пропустить. Положительный QUANT ещё не означает доступность для продажи.'
        $identifier = (Read-Host 'RECENT, штрихкод или PART:ID').Trim()
        if (-not $identifier) { Write-Report 'STOCK_PROBE_STATUS=SKIPPED_NO_PRODUCT'; return }
        try { $selector = Get-StockProbeSelector $identifier } catch { Write-Report 'STOCK_PROBE_STATUS=SKIPPED_INVALID_IDENTIFIER'; return }
        Write-Report ('PROBE_IDENTIFIER=' + $identifier + '; ROW_LIMIT=' + $selector.Limit + '; FULL_SNAPSHOT=false; SELLABLE_VERIFIED=false')
        Write-Report ('PLAN_CLIENT_STARTED_UTC=' + [DateTime]::UtcNow.ToString('o'))
        $plan = Invoke-IsqlBatch $Isql $Endpoint ($Password.Copy()) $UserName (Get-StockProbeSql $identifier -PlanOnly)
        Write-Report ('PLAN_CLIENT_FINISHED_UTC=' + [DateTime]::UtcNow.ToString('o'))
        if ($plan.TimedOut) { Write-IsqlTimeout $plan 'STOCK_PLAN_STATUS'; return }
        Write-Report ('--- STOCK PLAN BEGIN; ELAPSED_MS=' + $plan.ElapsedMs + ' ---')
        Write-Report $plan.Output
        Write-Report '--- STOCK PLAN END ---'
        if (-not (Test-StockProbeResult $plan $identifier -PlanOnly)) { Write-Report 'STOCK_PLAN_STATUS=REJECTED; no product rows read; no retries.'; return }
        Write-Report 'STOCK_PLAN_STATUS=VERIFIED; fixed index; no full scan, join or sort.'
        Write-Report ('План проверен. Для разового чтения максимум ' + $selector.Limit + ' партий пробы введите READ; Enter — завершить с отчётом плана.')
        if ((Read-Host 'Чтение пробы') -cne 'READ') { Write-Report 'STOCK_SAMPLE_STATUS=SKIPPED_BY_OPERATOR'; return }
        Write-Report ('SAMPLE_CLIENT_STARTED_UTC=' + [DateTime]::UtcNow.ToString('o'))
        $sample = Invoke-IsqlBatch $Isql $Endpoint ($Password.Copy()) $UserName (Get-StockProbeSql $identifier)
        Write-Report ('SAMPLE_CLIENT_FINISHED_UTC=' + [DateTime]::UtcNow.ToString('o'))
        if ($sample.TimedOut) { Write-IsqlTimeout $sample 'STOCK_SAMPLE_STATUS'; return }
        Write-Report ('--- STOCK SAMPLE BEGIN; ELAPSED_MS=' + $sample.ElapsedMs + ' ---')
        Write-Report $sample.Output
        Write-Report '--- STOCK SAMPLE END ---'
        if (-not (Test-StockProbeResult $sample $identifier)) { Write-Report 'STOCK_SAMPLE_STATUS=FAILED_OR_INCOMPLETE; no upload and no retries.'; return }
        $rowCount = [regex]::Matches($sample.Output, '(?m)^\s*SAMPLE_PART_ID\s+\S+\s*$').Count
        Write-Report ('STOCK_SAMPLE_STATUS=OK; RETURNED_ROWS=' + $rowCount + '; QUANTITY_BASIS=UNVERIFIED; SELLABLE_VERIFIED=false; FULL_SNAPSHOT=false')
        if ($identifier -ceq 'RECENT') { Write-Report ('POSITIVE_QUANT_ROWS_IN_SAMPLE=' + (Get-PositiveProbeRows $sample.Output) + '; no more pages requested; not the total for the pharmacy.') }
        if ($rowCount -eq 0) { Write-Report 'По выбранному идентификатору партий не найдено. Это не означает отсутствие всех остатков в аптеке; запасных запросов нет.' }
        else { Write-Report 'Сверьте SAMPLE_QUANT, SAMPLE_REALQUANT и SAMPLE_BLOCK_QUANT с экраном кассы по той же партии. Отчёт не определяет доступность для продажи.' }
        Write-Report 'Пришлите TXT и количество по выбранному товару с экрана кассы; постоянный сбор не запущен.'
    } finally { $Password.Dispose() }
}

function Find-FirebirdIsql([string[]]$CashierDirs, $Services) {
    $dirs = New-Object 'System.Collections.Generic.List[string]'
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, 'C:\Firebird', 'C:\Standart-N', 'C:\StandartN')) {
        if (-not $base -or -not (Test-Path -LiteralPath $base -PathType Container)) { continue }
        $dirs.Add($base)
        if ($base -match 'Program Files') {
            $fb = Join-Path $base 'Firebird'
            if (Test-Path -LiteralPath $fb -PathType Container) {
                $dirs.Add($fb)
                foreach ($dir in @(Get-ChildItem -LiteralPath $fb -Directory -ErrorAction SilentlyContinue | Select-Object -First 12)) { $dirs.Add($dir.FullName) }
            }
        }
    }
    foreach ($dir in $CashierDirs) { $dirs.Add($dir); $dirs.Add((Split-Path -Parent $dir)) }
    foreach ($service in $Services) {
        if ($service.PathName -match '^"([^"]+\.exe)"' -or $service.PathName -match '^([^\r\n]+?\.exe)(?:\s|$)') {
            $dirs.Add((Split-Path -Parent $Matches[1]))
        }
    }
    foreach ($dir in @($dirs | Select-Object -Unique)) {
        foreach ($relative in @('isql.exe','bin\isql.exe','Firebird\isql.exe','Firebird\bin\isql.exe')) {
            $path = Join-Path $dir $relative
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
            $version = [Diagnostics.FileVersionInfo]::GetVersionInfo($path)
            if (($version.ProductName + ' ' + $version.FileDescription + ' ' + $version.CompanyName) -match 'Firebird') { return $path }
        }
    }
    return ''
}

function Invoke-Diagnostic {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'WINDOWS_REQUIRED' }
    Write-Report '=== EPHARM STOCK DIAGNOSTICS BEGIN ==='
    Write-Report 'VERSION=9; MODE=UNIVERSAL_PHARMACY_DIAGNOSTIC'
    if ($StockProbe -and $StockSchema) { throw 'CONFLICTING_DIAGNOSTIC_MODES' }
    if ($StockSchema) { Write-Report 'METADATA_SCOPE=TARGETED_STOCK_SCHEMA; no warehouse rows or query execution.' }
    if ($StockProbe) { Write-Report 'STOCK_PROBE_SCOPE=PLAN_THEN_OPTIONAL_SAMPLE; maximum 5 lots; no periodic collection, uploads or installs.' }
    Write-Report ('MANUAL_FIREBIRD_AUTHENTICATION=' + [bool]$ManualAuthentication)
    Write-Report ('UTC_TIME=' + [DateTime]::UtcNow.ToString('o'))
    Write-Report ('COMPUTER=' + $env:COMPUTERNAME)
    Write-Report ('POWERSHELL=' + $PSVersionTable.PSVersion.ToString() + '; OS=' + [Environment]::OSVersion.VersionString)
    if ($StockProbe) { Write-Report 'SCOPE=Verified local source; one indexed sample after PLANONLY and READ; no service changes or uploads.' }
    else { Write-Report 'SCOPE=Configuration, versions, optional system metadata. No warehouse rows, installs, service changes or uploads.' }
    $label = (Read-Host 'Название или адрес аптеки (необязательно; Enter — пропустить)').Trim()
    $label = [regex]::Replace($label, '[\x00-\x1F]', ' ')
    if ($label.Length -gt 160) { $label = $label.Substring(0, 160) }
    if (-not $label) { $label = 'UNSPECIFIED' }
    Write-Report ('PHARMACY_LABEL=' + $label + '; IDENTITY=NOT_VERIFIED; SOURCE=OPERATOR_LABEL_ONLY')
    $services = @(Get-CimInstance Win32_Service -Filter "Name LIKE '%Firebird%' OR Name LIKE '%InterBase%'" -OperationTimeoutSec 5 -ErrorAction SilentlyContinue)
    foreach ($service in $services) { Write-Report ('DB_SERVICE=' + $service.Name + '; STATE=' + $service.State) }
    $localServer = @($services | Where-Object { $_.State -eq 'Running' -and $_.Name -match 'Firebird' -and $_.Name -notmatch 'Guardian' }).Count -gt 0
    $allProcesses = @(Get-Process -ErrorAction Stop)
    $processes = @($allProcesses | Where-Object { $_.ProcessName -like 'zkassa*' })
    Write-Report ('RUNNING_ZKASSA_COUNT=' + $processes.Count)
    Write-Report 'RELATED_PROCESS_SCOPE=Name and executable path only; not command lines or window titles; not proof of an active database.'
    $relatedCount = 0
    foreach ($process in @($allProcesses | Sort-Object ProcessName)) {
        $path = ''
        try { $path = [string]$process.Path } catch { }
        if ($process.ProcessName -match '(?i)zkassa|ztrade|kassir|kassa|standar?t|customerdisplay|epharm' -or
            $path -match '(?i)[\\/](Standart-N|StandartN|Standart_N|Kassir|Kassa)[\\/]') {
            $relatedCount++
            if ($relatedCount -le 40) { Write-Report ('RELATED_PROCESS=' + $process.ProcessName + '; EXE=' + $path) }
        }
    }
    if ($relatedCount -gt 40) { Write-Report 'RELATED_PROCESS_LIST=TRUNCATED_AFTER_40' }
    $cashierDirs = New-Object 'System.Collections.Generic.List[string]'
    $candidates = New-Object 'System.Collections.Generic.List[object]'
    $activeSourceUncertainty = $false
    foreach ($process in $processes) {
        try {
            $exe = $process.Path
            if (-not $exe) { $activeSourceUncertainty = $true; Write-Report 'RUNNING_CASHIER_PATH=UNAVAILABLE'; continue }
            $dir = Split-Path -Parent $exe
            $cashierDirs.Add($dir)
            Write-Report ('RUNNING_CASHIER=' + $exe + '; VERSION=' + [Diagnostics.FileVersionInfo]::GetVersionInfo($exe).FileVersion)
            $foundIni = $false
            foreach ($iniDir in @($dir, (Split-Path -Parent $dir))) {
                $path = Join-Path $iniDir 'options.ini'
                if (Test-Path -LiteralPath $path -PathType Leaf) { $foundIni = $true; $candidates.Add([pscustomobject]@{ Path = $path; Running = $true }) }
            }
            if (-not $foundIni) { $activeSourceUncertainty = $true; Write-Report 'RUNNING_CASHIER_CONFIG=NOT_FOUND' }
        } catch { $activeSourceUncertainty = $true; Write-Report 'RUNNING_CASHIER_PATH=ACCESS_DENIED_OR_UNAVAILABLE' }
    }
    foreach ($root in @('C:\Standart-N','C:\StandartN','C:\Standart_N','C:\Program Files\Standart-N','C:\Program Files (x86)\Standart-N')) {
        foreach ($relative in @('options.ini','Kassir\options.ini','Kassa\options.ini')) {
            $path = Join-Path $root $relative
            if (Test-Path -LiteralPath $path -PathType Leaf) { $candidates.Add([pscustomobject]@{ Path = $path; Running = $false }) }
        }
    }
    $sources = New-Object 'System.Collections.Generic.List[object]'
    foreach ($candidateGroup in @($candidates | Group-Object -Property Path)) {
        $candidate = @($candidateGroup.Group | Sort-Object -Property @{ Expression = 'Running'; Descending = $true })[0]
        try {
            $values = Read-ConnectSection $candidate.Path
            $endpoint = Get-Endpoint $values $localServer
            Write-Report ('CONFIG=' + $candidate.Path + '; FROM_RUNNING_CASHIER=' + $candidate.Running)
            Write-Report ('DB_HOST=' + $endpoint.HostName + '; PORT=' + $endpoint.Port + '; DB_PATH_OR_ALIAS=' + $endpoint.Database + '; CENTRAL_BLOCKED=' + $endpoint.Blocked)
            $sources.Add([pscustomobject]@{ Path = $candidate.Path; Running = $candidate.Running; Endpoint = $endpoint })
        } catch {
            if ($candidate.Running) { $activeSourceUncertainty = $true }
            $reason = 'UNREADABLE_OR_INVALID_CONFIG'
            if ($_.Exception.Message -match '^(INI_TOO_LARGE|NO_DATABASE|UNSUPPORTED_DATABASE_FORMAT|CONFLICTING_SERVER_SETTINGS|NO_CONFIRMED_LOCAL_SERVER|UNSUPPORTED_SERVER_FORMAT|CONFLICTING_PORT_SETTINGS|INVALID_PORT|CONFLICTING_DUPLICATE_INI_KEY|CONFLICTING_INI_ALIASES)$') { $reason = $_.Exception.Message }
            Write-Report ('CONFIG_SKIPPED=' + $candidate.Path + '; REASON=' + $reason)
        }
    }
    $active = @($sources | Where-Object { $_.Running } | Group-Object -Property { $_.Endpoint.Connection.ToUpperInvariant() })
    if ($activeSourceUncertainty -or $active.Count -ne 1) {
        Write-Report 'METADATA_STATUS=SKIPPED; no unique fully resolved source from ALL running zkassa processes.'
        if ($processes.Count -eq 0) {
            Write-Report 'Подсказка: процесс zkassa не найден. Откройте обычную кассовую программу и повторите запуск. Если она уже открыта, пришлите этот отчёт: проверим имя процесса.'
        } else {
            Write-Report 'Подсказка: настройки работающей кассы недоступны или неоднозначны. Повторите запуск батника от имени администратора и пришлите отчёт; настройки кассы не меняйте.'
        }
        return
    }
    $activeSources = @($active[0].Group)
    $endpoint = $activeSources[0].Endpoint
    if ($endpoint.Blocked) { Write-Report 'METADATA_STATUS=BLOCKED_CENTRAL_SOURCE'; return }
    # Fail closed on DNS failure or any known central address, including aliases of INKSTDN.
    try {
        $resolve = [Net.Dns]::GetHostAddressesAsync($endpoint.HostName)
        if (-not $resolve.Wait(3000)) { Write-Report 'METADATA_STATUS=SKIPPED_DNS_TIMEOUT'; return }
        $addresses = @($resolve.Result | ForEach-Object { $_.ToString() })
    } catch { Write-Report 'METADATA_STATUS=SKIPPED_DNS_FAILED'; return }
    Write-Report ('DB_RESOLVED_ADDRESSES=' + ($addresses -join ','))
    if ($addresses.Count -eq 0 -or @($addresses | Where-Object { $_ -in @('10.10.1.94','10.10.1.76','10.10.1.80','10.10.1.81') -or $_ -match '^::ffff:10\.10\.1\.(94|76|80|81)$' }).Count -gt 0) {
        Write-Report 'METADATA_STATUS=BLOCKED_CENTRAL_OR_EMPTY_ADDRESS'; return
    }
    $confirmedAddresses = @($addresses | Sort-Object)
    $isql = Find-FirebirdIsql $cashierDirs.ToArray() $services
    if (-not $isql) {
        Write-Report 'METADATA_STATUS=SKIPPED_NO_INSTALLED_FIREBIRD_ISQL'
        Write-Report 'Подсказка: утилита Firebird isql не найдена. Пришлите этот отчёт; ничего устанавливать на кассу пока не требуется.'
        return
    }
    Write-Report ('FIREBIRD_ISQL=' + $isql + '; VERSION=' + [Diagnostics.FileVersionInfo]::GetVersionInfo($isql).FileVersion)
    if ($StockProbe) {
        Write-Report 'Убедитесь, что сервер и база принадлежат этой аптеке. Пилот проверит план запроса выбранного товара; чтение до 5 партий будет отдельным шагом после READ. Логин и скрытый пароль введёте вручную.'
    } elseif ($ManualAuthentication) {
        Write-Report 'Убедитесь, что сервер и база принадлежат этой аптеке. Логин и пароль Firebird введёте вручную; данные входа из options.ini не используются. Пароль в отчёт не попадёт.'
    } else {
        Write-Report 'Убедитесь, что сервер и база принадлежат этой аптеке. Для разового чтения структуры используем данные подключения из активной options.ini, если она однозначна. Пароль в отчёт не попадёт.'
    }
    if ($StockProbe) { Write-Report 'Введите LOCAL для проверки плана на кассовой БД; Enter — пропустить. Это не включает постоянный сбор остатков.' }
    else { Write-Report 'Введите LOCAL для чтения только структуры БД; Enter — пропустить. Это не включает постоянный сбор остатков.' }
    if ((Read-Host 'Подтверждение') -cne 'LOCAL') { Write-Report 'METADATA_STATUS=SKIPPED_BY_OPERATOR'; return }
    $authentication = $null
    $authValues = $null
    try {
        # Multiple active files for the same endpoint do not justify choosing an arbitrary password.
        if ($activeSources.Count -eq 1) {
            $authValues = Read-ConnectSection $activeSources[0].Path -IncludeAuthentication:(-not $ManualAuthentication)
            $currentEndpoint = Get-Endpoint $authValues $localServer
            if ($currentEndpoint.Blocked -or $currentEndpoint.Connection -ine $endpoint.Connection) {
                Write-Report 'METADATA_STATUS=SKIPPED_SOURCE_CHANGED; параметры подключения изменились после диагностики. Запрос не выполнен.'; return
            }
            if (-not $ManualAuthentication) { $authentication = Get-ConfiguredAuthentication $authValues }
        }
    } catch {
        Write-Report 'METADATA_STATUS=SKIPPED_AMBIGUOUS_AUTH_CONFIG; данные подключения неоднозначны или недоступны. Пришлите отчёт.'; return
    } finally {
        if ($null -ne $authValues) { $authValues.Clear() }
    }
    if ($null -ne $authentication) {
        Write-Report 'AUTH_SOURCE=ACTIVE_CASHIER_CONFIG; ACCOUNT_GRANTS=NOT_VERIFIED; PURPOSE=ONE_TIME_SYSTEM_METADATA'
        Write-Report 'Читаем только структуру БД под учётной записью из настроек кассы. Дополнительный пароль вводить не требуется.'
    } else {
        if ($StockProbe) { Write-Report 'Введите действующий логин и скрытый пароль Firebird. Пилот выполнит план и, после READ, одну ограниченную выборку товара. Повторных попыток после ошибки нет.' }
        elseif ($ManualAuthentication) { Write-Report 'Введите предоставленные вам логин и пароль Firebird. Будет одна попытка входа и только чтение системных метаданных; Enter — пропустить.' }
        else { Write-Report 'Полные однозначные данные входа из кассовой конфигурации не получены. Укажите действующую учётную запись для чтения этой БД; Enter — пропустить.' }
        $userName = (Read-Host 'Логин').Trim()
        if (-not $userName) { Write-Report 'METADATA_STATUS=SKIPPED_NO_CREDENTIALS'; return }
        if ($userName -notmatch '^[A-Za-z0-9_$.-]{1,31}$') { Write-Report 'METADATA_STATUS=SKIPPED_UNSUPPORTED_USERNAME'; return }
        $password = Read-Host 'Пароль (ввод скрыт)' -AsSecureString
        if ($password.Length -eq 0) { $password.Dispose(); Write-Report 'METADATA_STATUS=SKIPPED_NO_CREDENTIALS'; return }
        $authentication = [pscustomobject]@{ UserName = $userName; Password = $password }
        if ($StockProbe) { Write-Report 'AUTH_SOURCE=OPERATOR_INPUT; ACCOUNT_GRANTS=NOT_VERIFIED; PURPOSE=ONE_TIME_INDEXED_STOCK_PROBE' }
        else { Write-Report 'AUTH_SOURCE=OPERATOR_INPUT; ACCOUNT_GRANTS=NOT_VERIFIED; PURPOSE=ONE_TIME_SYSTEM_METADATA' }
    }
    # Recheck DNS after interactive confirmation; never use a newly resolved known central address.
    try {
        $resolve = [Net.Dns]::GetHostAddressesAsync($endpoint.HostName)
        if (-not $resolve.Wait(3000)) { $authentication.Password.Dispose(); Write-Report 'METADATA_STATUS=SKIPPED_DNS_TIMEOUT'; return }
        $addresses = @($resolve.Result | ForEach-Object { $_.ToString() })
    } catch { $authentication.Password.Dispose(); Write-Report 'METADATA_STATUS=SKIPPED_DNS_FAILED'; return }
    if ($addresses.Count -eq 0 -or @($addresses | Where-Object { $_ -in @('10.10.1.94','10.10.1.76','10.10.1.80','10.10.1.81') -or $_ -match '^::ffff:10\.10\.1\.(94|76|80|81)$' }).Count -gt 0) {
        $authentication.Password.Dispose(); Write-Report 'METADATA_STATUS=BLOCKED_CENTRAL_OR_EMPTY_ADDRESS'; return
    }
    if (@(Compare-Object $confirmedAddresses @($addresses | Sort-Object)).Count -gt 0) {
        $authentication.Password.Dispose(); Write-Report 'METADATA_STATUS=SKIPPED_DNS_SOURCE_CHANGED; адрес сервера изменился после подтверждения. Запрос не выполнен.'; return
    }
    if ($StockProbe) {
        $address = $null
        if ($addresses.Count -ne 1 -or -not [Net.IPAddress]::TryParse($addresses[0], [ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
            $authentication.Password.Dispose(); Write-Report 'STOCK_PROBE_STATUS=SKIPPED_AMBIGUOUS_IPV4_ENDPOINT'; return
        }
        # Keep both stages on the verified pharmacy IP even while the operator reviews the plan.
        $pinnedEndpoint = [pscustomobject]@{ Connection=($addresses[0] + '/' + $endpoint.Port + ':' + $endpoint.Database) }
        Write-Report ('PROBE_PINNED_SERVER=' + $addresses[0] + '; PORT=' + $endpoint.Port)
        Invoke-StockProbe $isql $pinnedEndpoint $authentication.Password $authentication.UserName
    } else { Invoke-Metadata $isql $endpoint $authentication.Password $authentication.UserName -StockSchema:$StockSchema }
}

if (-not $LibraryOnly) {
    $exitCode = 0
    try { Invoke-Diagnostic } catch {
        $exitCode = 1
        Write-Report ('DIAGNOSTIC_ERROR_TYPE=' + $_.Exception.GetType().Name + '; LINE=' + $_.InvocationInfo.ScriptLineNumber)
        Write-Report 'DIAGNOSTIC_STATUS=FAILED; no automatic retries or alternate source.'
    } finally {
        Write-Report '=== EPHARM STOCK DIAGNOSTICS END ==='
        $folder = [Environment]::GetFolderPath('DesktopDirectory')
        if (-not $folder -or -not (Test-Path -LiteralPath $folder -PathType Container)) { $folder = [IO.Path]::GetTempPath() }
        $reportPath = Join-Path $folder ('epharm-stock-diagnostics-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '.txt')
        try {
            [IO.File]::WriteAllLines($reportPath, $script:Lines.ToArray(), (New-Object Text.UTF8Encoding($true)))
            Write-Host ('REPORT_FILE=' + $reportPath)
        } catch { Write-Host 'REPORT_FILE_SAVE_FAILED; copy the text between BEGIN and END.'; $exitCode = 1 }
        $script:Secrets.Clear()
    }
    exit $exitCode
}
