#requires -version 5.1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'cashier-stock-diagnostics.ps1') -LibraryOnly

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAILED: $Message" }
}
function Assert-Rejected([hashtable]$Values, [bool]$LocalServer = $false) {
    $rejected = $false
    try { $null = Get-Endpoint $Values $LocalServer } catch { $rejected = $true }
    Assert-True $rejected 'invalid/ambiguous endpoint must not reach Firebird'
}

$fixture = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($fixture, "[Other]`r`nserver=INKSTDN`r`n[Connect]`r`nServer=MANAGER`r`nbase=C:\Standart-N\base\ztrade.fdb`r`npassword=fixture-not-a-real-secret`r`nlogin=SYSDBA`r`n", [Text.Encoding]::GetEncoding(1251))
    $values = Read-ConnectSection $fixture
    Assert-True ($values['server'] -eq 'MANAGER') 'read Connect section only'
    Assert-True (-not $values.ContainsKey('password') -and -not $values.ContainsKey('login')) 'ignore cashier credentials'
    $authValues = Read-ConnectSection $fixture -IncludeAuthentication
    $auth = Get-ConfiguredAuthentication $authValues
    Assert-True ($auth.UserName -eq 'SYSDBA' -and $auth.Password.Length -gt 0) 'explicit auth uses active INI user without default or guessed credentials'
    $auth.Password.Dispose()
    Assert-True ($null -eq (Get-ConfiguredAuthentication @{login='SYSDBA'})) 'incomplete INI authentication must not invent a password'
    $endpoint = Get-Endpoint $values $false
    Assert-True ($endpoint.Connection -eq 'MANAGER/3050:C:\Standart-N\base\ztrade.fdb' -and -not $endpoint.Blocked) 'explicit TCP connection to pharmacy host'
    $endpoint = Get-Endpoint @{base='MANAGER/3051:C:\db\ztrade.fdb'} $false
    Assert-True ($endpoint.Port -eq 3051) 'host and port in database value'
    $endpoint = Get-Endpoint @{server='MANAGER';base='pharmacy_alias'} $false
    Assert-True ($endpoint.Connection -eq 'MANAGER/3050:pharmacy_alias') 'server-side database alias'
    $endpoint = Get-Endpoint @{base='C:\db\ztrade.fdb'} $true
    Assert-True ($endpoint.HostName -eq 'localhost') 'local path still uses TCP server'
    foreach ($server in @('INKSTDN','inkstdn.inkar.kz','INKSTDN.','10.10.1.94','10.10.1.80','10.10.1.81','10.10.1.76')) {
        $endpoint = Get-Endpoint @{server=$server;base='C:\db\ztrade.fdb'} $false
        Assert-True $endpoint.Blocked ('block central host ' + $server)
    }
    foreach ($database in @('C:\Standart-N\base_g\ztrade.fdb','C:/Standart-N/BASE_G/ZTRADE_G.FDB','C:\db\ZTRADE_G.FDB','ztrade_g')) {
        $endpoint = Get-Endpoint @{server='MANAGER';base=$database} $false
        Assert-True $endpoint.Blocked 'block group database paths/aliases'
    }
    Assert-Rejected @{server='MANAGER';base='INKSTDN:C:\db\ztrade.fdb'}
    Assert-Rejected @{server='MANAGER/3051';port='3050';base='C:\db\ztrade.fdb'}
    Assert-Rejected @{base='C:\db\ztrade.fdb'}
    Assert-Rejected @{server='MANAGER';base='\\MANAGER\share\ztrade.fdb'}
    Assert-Rejected @{server='MANAGER';base='C:\db\ztrade.fdb;password=bad'}
    Assert-Rejected @{server='MANAGER';base='C:\db\ztrade.fdb?pass=fixture-secret'}
    Assert-Rejected @{server='MANAGER';base='C:\db\ztrade.fdb';port='70000'}
    Assert-Rejected @{server='MANAGER';host='INKSTDN';base='C:\db\ztrade.fdb'}
    Assert-Rejected @{server='MANAGER';base='C:\db\ztrade.fdb';dbname='C:\other.fdb'}
    [IO.File]::WriteAllText($fixture, "[Connect]`r`nserver=MANAGER`r`nserver=INKSTDN`r`nbase=C:\db\ztrade.fdb", [Text.Encoding]::ASCII)
    $duplicateRejected = $false
    try { $null = Read-ConnectSection $fixture } catch { $duplicateRejected = $true }
    Assert-True $duplicateRejected 'conflicting repeated INI keys rejected'
    [IO.File]::WriteAllText($fixture, "[Connect]`r`nlogin=PILOT_USER`r`npassword=Abc`r`npassword=abc", [Text.Encoding]::ASCII)
    $passwordConflict = $false
    try { $null = Read-ConnectSection $fixture -IncludeAuthentication } catch { $passwordConflict = $true }
    Assert-True $passwordConflict 'password duplicate conflicts are case-sensitive'
    $passwordConflict = $false
    try { $null = Get-ConfiguredAuthentication @{login='PILOT_USER';password='Abc';pwd='abc'} } catch { $passwordConflict = $true }
    Assert-True $passwordConflict 'password alias conflicts are case-sensitive'
    foreach ($case in @(
        @('Statement failed, SQLSTATE = 28000 Your user name and password are not defined.', 'AUTH_FAILED'),
        @('Statement failed, SQLSTATE = 28000 no permission for SELECT access', 'PERMISSION_DENIED'),
        @('Statement failed, SQLSTATE = 28000', 'AUTH_OR_PERMISSION_FAILED'),
        @('Statement failed, SQLSTATE = 42000 Attempted update during read-only transaction', 'READONLY_TRANSACTION_REJECTED'),
        @('Statement failed, SQLSTATE = 42000 Dynamic SQL Error', 'SQL_OR_CLIENT_ERROR'),
        @('SQLSTATE = 08001 Unable to complete network request', 'CONNECTION_FAILED')
    )) { Assert-True ((Get-MetadataFailure $case[0]) -eq $case[1]) ('correct classification: ' + $case[1]) }
    $script:Secrets.Add('fixture&secret%"value')
    $safe = Protect-Output "connection failed: fixture&secret%`"value`npassword=do-not-print`ntoken=do-not-print"
    Assert-True (-not $safe.Contains('fixture&secret') -and -not $safe.Contains('do-not-print')) 'mask native errors and secret fields'
    $sql = Get-MetadataSql
    Assert-True ($sql.Contains('SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;') -and $sql.Contains('SET BAIL ON;') -and $sql.EndsWith('QUIT;')) 'fail closed readonly transaction and rollback termination'
    Assert-True ($sql -notmatch '(?im)^\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|EXECUTE|EXIT)\b') 'no mutations or stored procedures'
    Assert-True ($sql -notmatch '(?im)^\s*(FROM|JOIN)\s+(?!RDB\$)') 'all tables read are system metadata'
    $stockSql = Get-StockSchemaSql
    Assert-True ($stockSql.Contains('SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;') -and $stockSql.Contains('SET BAIL ON;') -and $stockSql.EndsWith('QUIT;')) 'targeted schema retains readonly/bail/rollback'
    Assert-True ($stockSql -notmatch '(?im)^\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|EXECUTE|EXIT)\b') 'targeted schema contains no mutations or procedures'
    Assert-True ($stockSql -notmatch '(?im)^\s*(FROM|JOIN)\s+(?!RDB\$)') 'targeted schema reads only system tables'
    $completeSchema = @'
TARGET_RELATION WAREBASE
IS_VIEW 0
TARGET_RELATION PARTS
IS_VIEW 0
TARGET_FIELDS_TOTAL 1
RELATION_NAME WAREBASE
FIELD_ORDINAL 0
COMPUTED_SOURCE_CHARS 0
SOURCE_UNAVAILABLE 0
TARGET_INDEX_SEGMENTS_TOTAL 1
INDEX_FIELD_ORDINAL 0
TARGET_CONSTRAINTS_TOTAL 1
CONSTRAINT_NAME PK_WAREBASE
TARGET_DEPENDENCIES_TOTAL 1
DEPENDENT_NAME VW_WAREBASE
VIEW_NAME VW_WAREBASE
VIEW_SOURCE_CHARS 100
SOURCE_UNAVAILABLE 0
VIEW_SQL SELECT ID FROM WAREBASE
'@
    Assert-True (Test-StockSchemaComplete $completeSchema) 'matching counts prove complete targeted metadata'
    Assert-True (Test-StockSchemaComplete ($completeSchema.Replace("`n", "`r`n"))) 'Windows CRLF output counts match exactly'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('TARGET_FIELDS_TOTAL 1', 'TARGET_FIELDS_TOTAL 2')))) 'SQL row truncation is not complete metadata'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('TARGET_INDEX_SEGMENTS_TOTAL 1', 'TARGET_INDEX_SEGMENTS_TOTAL 401')))) 'index segment limit is enforced'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('VIEW_SOURCE_CHARS 100', 'VIEW_SOURCE_CHARS 8001')))) 'truncated view source is not complete metadata'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('COMPUTED_SOURCE_CHARS 0', 'COMPUTED_SOURCE_CHARS 2049')))) 'truncated computed source is not complete metadata'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('TARGET_RELATION WAREBASE', 'TARGET_RELATION OTHER')))) 'missing warehouse table cannot pass the targeted gate'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('SOURCE_UNAVAILABLE 0', 'SOURCE_UNAVAILABLE 1')))) 'missing original source with surviving BLR cannot pass the targeted gate'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('VIEW_SOURCE_CHARS 100', 'VIEW_SOURCE_CHARS <null>')))) 'null view source length cannot pass the targeted gate'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('COMPUTED_SOURCE_CHARS 0', 'COMPUTED_SOURCE_CHARS <null>')))) 'null computed source length cannot pass the targeted gate'
    Assert-True (-not (Test-StockSchemaComplete ($completeSchema.Replace('SOURCE_UNAVAILABLE 0', 'MISSING_SOURCE_FLAG 0')))) 'omitted source availability flags cannot pass the targeted gate'
    $viewExpressionSchema = $completeSchema.Replace('TARGET_FIELDS_TOTAL 1', 'TARGET_FIELDS_TOTAL 2').Replace('TARGET_INDEX_SEGMENTS_TOTAL 1', @'
RELATION_NAME VW_WAREBASE
FIELD_ORDINAL 0
COMPUTED_SOURCE_CHARS 0
SOURCE_UNAVAILABLE 1
TARGET_INDEX_SEGMENTS_TOTAL 1
'@)
    $viewExpressionSchema = $viewExpressionSchema.Replace('TARGET_FIELDS_TOTAL 2', "TARGET_RELATION VW_WAREBASE`nIS_VIEW 1`nTARGET_FIELDS_TOTAL 2")
    Assert-True (Test-StockSchemaComplete $viewExpressionSchema) 'view-derived expression with full view source does not require individual computed source'
    Assert-True (Test-StockSchemaComplete ($viewExpressionSchema.Replace("`n", "`r`n"))) 'Windows view-derived expression with full view source passes'
    Assert-True (-not (Test-StockSchemaComplete ($viewExpressionSchema.Replace('VIEW_SOURCE_CHARS 100', 'VIEW_SOURCE_CHARS 0')))) 'view-derived expression without full view source is rejected'
    Assert-True (-not (Test-StockSchemaComplete ($viewExpressionSchema.Replace('IS_VIEW 1', 'IS_VIEW 0')))) 'base computed field with no source remains rejected'
    $bat = [IO.File]::ReadAllText((Join-Path $root 'cashier-stock-diagnostics.bat'))
    $match = [regex]::Match($bat, '(?m)^::EPHARM_PS_BASE64=([A-Za-z0-9+/=]+)\r?$')
    Assert-True $match.Success 'BAT payload found with CRLF'
    $decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($match.Groups[1].Value))
    Assert-True ($decoded -eq [IO.File]::ReadAllText((Join-Path $root 'cashier-stock-diagnostics.ps1'))) 'BAT and source match exactly'
    $parseTokens = $null; $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseInput($decoded, [ref]$parseTokens, [ref]$parseErrors)
    Assert-True ($parseErrors.Count -eq 0) 'embedded PowerShell parses'
    # Exercise the actual BAT PowerShell launcher, without running Windows diagnostics.
    $launcher = @($bat -split '\r?\n' | Where-Object { $_.StartsWith('powershell.exe ') })[0]
    $command = $launcher.Substring($launcher.IndexOf('-Command "') + 10).TrimEnd('"')
    $command = $command.Replace('& ([scriptblock]::Create($s))', '& ([scriptblock]::Create($s)) -LibraryOnly')
    $env:EPHARM_STOCK_DIAG_SELF = Join-Path $root 'cashier-stock-diagnostics.bat'
    $null = & ([scriptblock]::Create($command))
    $manualBat = [IO.File]::ReadAllText((Join-Path $root 'cashier-stock-metadata-manual.bat'))
    $manualLauncher = @($manualBat -split '\r?\n' | Where-Object { $_.StartsWith('powershell.exe ') })[0]
    Assert-True ($manualLauncher.Contains('-ManualAuthentication')) 'manual BAT selects manual credentials explicitly'
    $manualCommand = $manualLauncher.Substring($manualLauncher.IndexOf('-Command "') + 10).TrimEnd('"').Replace('& ([scriptblock]::Create($s))', '& ([scriptblock]::Create($s)) -LibraryOnly')
    $env:EPHARM_STOCK_DIAG_SELF = Join-Path $root 'cashier-stock-metadata-manual.bat'
    $null = & ([scriptblock]::Create($manualCommand))
    $schemaBat = [IO.File]::ReadAllText((Join-Path $root 'cashier-stock-schema.bat'))
    $schemaMatch = [regex]::Match($schemaBat, '(?m)^::EPHARM_PS_BASE64=([A-Za-z0-9+/=]+)\r?$')
    Assert-True ($schemaMatch.Success -and [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($schemaMatch.Groups[1].Value)) -eq $decoded) 'targeted BAT embeds the same verified source'
    $schemaLauncher = @($schemaBat -split '\r?\n' | Where-Object { $_.StartsWith('powershell.exe ') })[0]
    Assert-True ($schemaLauncher.Contains('-ManualAuthentication -StockSchema')) 'targeted BAT selects focused metadata and manual credentials'
    $schemaCommand = $schemaLauncher.Substring($schemaLauncher.IndexOf('-Command "') + 10).TrimEnd('"').Replace('& ([scriptblock]::Create($s))', '& ([scriptblock]::Create($s)) -LibraryOnly')
    $env:EPHARM_STOCK_DIAG_SELF = Join-Path $root 'cashier-stock-schema.bat'
    $null = & ([scriptblock]::Create($schemaCommand))
    Write-Host 'PASS: endpoint policy, credential masking, metadata-only SQL, embedded BAT and launcher.'
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        # Native process tests use an isolated mock, never a Firebird client or a network connection.
        $mock = Join-Path ([IO.Path]::GetTempPath()) ('epharm-mock-isql-' + [Guid]::NewGuid().ToString('N') + '.sh')
        try {
            $mockSource = @'
#!/bin/sh
printf 'CHILD_USER=%s\n' "$ISC_USER"
printf 'CHILD_PASSWORD=%s\n' "$ISC_PASSWORD"
printf 'ARG=%s\n' "$@"
printf 'NATIVE_ERROR=%s\n' "$ISC_PASSWORD" >&2
printf '%s' "$ISC_PASSWORD" | shasum -a 256 | awk '{ print "PW_SHA256=" $1 }'
printf 'DIAGNOSTIC_READ_ONLY TRUE\nEPHARM_METADATA_COMPLETE_V4\n'
exit 0
'@
            [IO.File]::WriteAllText($mock, $mockSource + "`n", [Text.Encoding]::ASCII)
            & chmod +x $mock
            $script:Lines.Clear()
            $fixturePassword = 'fixture-native&secret%^$"''тест'
            Invoke-Metadata $mock ([pscustomobject]@{Connection='MANAGER/3050:C:\fake\ztrade.fdb'}) (ConvertTo-SecureString $fixturePassword -AsPlainText -Force) 'PILOT_USER'
            $nativeReport = $script:Lines -join "`n"
            Assert-True ($nativeReport.Contains('METADATA_STATUS=OK') -and $nativeReport.Contains('CHILD_USER=PILOT_USER')) 'native child started with explicitly selected username'
            Assert-True (-not $nativeReport.Contains('fixture-native')) 'native stdout and stderr cannot expose password'
            Assert-True ($nativeReport -notmatch '(?m)^ARG=.*(fixture|PASSWORD)') 'password is absent from native arguments'
            $hasher = [Security.Cryptography.SHA256]::Create()
            try { $expectedHash = -join ($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($fixturePassword)) | ForEach-Object { $_.ToString('x2') }) } finally { $hasher.Dispose() }
            Assert-True ($nativeReport.Contains('PW_SHA256=' + $expectedHash)) 'password reaches child unchanged including metacharacters and Unicode'
            $mockSchema = "#!/bin/sh`ncat <<'REPORT'`nDIAGNOSTIC_READ_ONLY TRUE`nEPHARM_STOCK_SCHEMA_COMPLETE_V7`n" + $completeSchema + "`nREPORT`n"
            [IO.File]::WriteAllText($mock, $mockSchema, [Text.Encoding]::ASCII)
            $script:Lines.Clear()
            Invoke-Metadata $mock ([pscustomobject]@{Connection='MANAGER/3050:C:\fake\ztrade.fdb'}) (ConvertTo-SecureString 'fixture-schema' -AsPlainText -Force) 'PILOT_USER' -StockSchema
            Assert-True (($script:Lines -join "`n").Contains('TARGET_SCHEMA_COMPLETE=TRUE')) 'native targeted metadata requires its own completion marker and row counts'
            [IO.File]::WriteAllText($mock, $mockSchema.Replace('TARGET_FIELDS_TOTAL 1', 'TARGET_FIELDS_TOTAL 2'), [Text.Encoding]::ASCII)
            $script:Lines.Clear()
            Invoke-Metadata $mock ([pscustomobject]@{Connection='MANAGER/3050:C:\fake\ztrade.fdb'}) (ConvertTo-SecureString 'fixture-schema-incomplete' -AsPlainText -Force) 'PILOT_USER' -StockSchema
            Assert-True (($script:Lines -join "`n").Contains('REASON=TARGET_SCHEMA_INCOMPLETE_OR_TRUNCATED')) 'native targeted truncation fails closed even with exit zero and completion marker'
            [IO.File]::WriteAllText($mock, "#!/bin/sh`nprintf 'Statement failed, SQLSTATE = 28000\nYour user name and password are not defined.\n' >&2`nexit 1`n", [Text.Encoding]::ASCII)
            $script:Lines.Clear()
            Invoke-Metadata $mock ([pscustomobject]@{Connection='MANAGER/3050:C:\fake\ztrade.fdb'}) (ConvertTo-SecureString 'fixture-auth-denied' -AsPlainText -Force) 'PILOT_USER'
            Assert-True (($script:Lines -join "`n").Contains('REASON=AUTH_FAILED')) 'native authentication failure is clearly classified'
            [IO.File]::WriteAllText($mock, "#!/bin/sh`nprintf 'metadata without proof\n'`nexit 0`n", [Text.Encoding]::ASCII)
            $script:Lines.Clear()
            Invoke-Metadata $mock ([pscustomobject]@{Connection='MANAGER/3050:C:\fake\ztrade.fdb'}) (ConvertTo-SecureString 'fixture-unverified' -AsPlainText -Force) 'PILOT_USER'
            Assert-True (($script:Lines -join "`n").Contains('REASON=INCOMPLETE_OR_UNVERIFIED_READONLY')) 'zero exit alone is not successful metadata proof'
            [IO.File]::WriteAllText($mock, "#!/bin/sh`nexec sleep 30`n", [Text.Encoding]::ASCII)
            $script:Lines.Clear()
            $clock = [Diagnostics.Stopwatch]::StartNew()
            Invoke-Metadata $mock ([pscustomobject]@{Connection='MANAGER/3050:C:\fake\ztrade.fdb'}) (ConvertTo-SecureString 'fixture-timeout' -AsPlainText -Force) 'PILOT_USER'
            $clock.Stop()
            Assert-True (($script:Lines -join "`n").Contains('METADATA_STATUS=TIMEOUT') -and $clock.Elapsed.TotalSeconds -lt 16) 'native child wait has a bounded timeout'
            Assert-True (($script:Lines -join "`n").Contains('ISQL_CHILD_EXITED=True')) 'only mock diagnostic child exits after timeout'
            Write-Host 'PASS: mock native child credentials, stdout/stderr redaction and 10-second timeout.'
        } finally { Remove-Item -LiteralPath $mock -Force -ErrorAction SilentlyContinue }
    }
} finally {
    Remove-Item -LiteralPath $fixture -Force -ErrorAction SilentlyContinue
}
