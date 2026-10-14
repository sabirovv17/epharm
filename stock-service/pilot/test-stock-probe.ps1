#requires -version 5.1
param([string]$ExportSqlDirectory = '', [string]$NativeOutputDirectory = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'cashier-stock-diagnostics.ps1') -LibraryOnly

function Assert-Probe([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw ('FAILED: ' + $Message) }
}
foreach ($invalid in @('', 'PART:0', 'PART:-1', 'PART:9223372036854775808', 'PART:1;DROP', "123456'", '12345', '１２３４５６７８')) {
    $rejected = $false
    try { $null = Get-StockProbeSelector $invalid } catch { $rejected = $true }
    Assert-Probe $rejected 'invalid identifiers cannot enter SQL'
}
Assert-Probe ((Get-StockProbeSelector 'PART:9223372036854775807').Limit -eq 1) 'INT64 maximum is valid and limited to one lot'
Assert-Probe ((Get-StockProbeSelector '001234567890').Predicate.Contains("'001234567890'")) 'barcode leading zeros are preserved'

function Get-ProbeFixture([string]$Identifier, [switch]$PlanOnly, [int]$RowCount = 0) {
    $selector = Get-StockProbeSelector $Identifier
    $phase = if ($PlanOnly) { 'PLAN' } else { 'SAMPLE' }
    $output = @"
DIAGNOSTIC_READ_ONLY TRUE
PROBE_TABLE_TYPE 0
PROBE_FIELD_COUNT 16
PROBE_COMPUTED_COUNT 0
PROBE_SCHEMA_GUARD 1
PROBE_INDEX_TABLE WAREBASE
PROBE_INDEX_NAME $($selector.Index)
PROBE_INDEX_INACTIVE 0
PROBE_INDEX_ORDINAL 0
PROBE_INDEX_FIELD $($selector.Field)
PLAN (W INDEX ($($selector.Index)))
DIAGNOSTIC_COMPLETE EPHARM_STOCK_$($phase)_COMPLETE_V8
"@
    for ($i = 0; $i -lt $RowCount; $i++) {
        $output += "`nSAMPLE_PART_ID $(123 + $i)`nSAMPLE_EAN 4601234567890`nSAMPLE_QUANT 2.5`nSAMPLE_REALQUANT 3.5`nSAMPLE_BLOCK_QUANT 1.0`nSAMPLE_BLOCK_COUNT 1`n"
    }
    return [pscustomobject]@{TimedOut=$false;Truncated=$false;ExitCode=0;Output=$output;ElapsedMs=25;ChildExited=$true}
}

$identifier = '4601234567890'
$plan = Get-ProbeFixture $identifier -PlanOnly
Assert-Probe (Test-StockProbeResult $plan $identifier -PlanOnly) 'exact indexed plan with zero product rows is accepted'
$windowsPlan = Get-ProbeFixture $identifier -PlanOnly
$windowsPlan.Output = $windowsPlan.Output.Replace("`n", "`r`n")
Assert-Probe (Test-StockProbeResult $windowsPlan $identifier -PlanOnly) 'Windows CRLF plan passes the same strict gate'
$badPlan = Get-ProbeFixture $identifier -PlanOnly
$badPlan.Output = $badPlan.Output.Replace('INDEX (WAREBASE_IDX2)', 'NATURAL')
Assert-Probe (-not (Test-StockProbeResult $badPlan $identifier -PlanOnly)) 'natural scan is rejected'
$badPlan.Output = $plan.Output + "`nPLAN (OTHER NATURAL)`n"
Assert-Probe (-not (Test-StockProbeResult $badPlan $identifier -PlanOnly)) 'additional access plans are rejected'
$badPlan.Output = $plan.Output.Replace('PROBE_COMPUTED_COUNT 0', 'PROBE_COMPUTED_COUNT 1')
Assert-Probe (-not (Test-StockProbeResult $badPlan $identifier -PlanOnly)) 'computed physical columns block sample'
$badPlan.Output = $plan.Output.Replace('PROBE_INDEX_INACTIVE 0', 'PROBE_INDEX_INACTIVE 1')
Assert-Probe (-not (Test-StockProbeResult $badPlan $identifier -PlanOnly)) 'inactive index blocks sample'
Assert-Probe (-not (Test-StockProbeResult (Get-ProbeFixture $identifier -PlanOnly -RowCount 1) $identifier -PlanOnly)) 'PLANONLY cannot contain product rows'
Assert-Probe (Test-StockProbeResult (Get-ProbeFixture $identifier -RowCount 5) $identifier) 'five raw lots are valid'
Assert-Probe (-not (Test-StockProbeResult (Get-ProbeFixture $identifier -RowCount 6) $identifier)) 'sample cannot exceed five lots'
Assert-Probe (Test-StockProbeResult (Get-ProbeFixture $identifier) $identifier) 'confirmed no-match is valid sample, not a full snapshot'
Assert-Probe (Test-StockProbeResult (Get-ProbeFixture 'PART:123' -RowCount 1) 'PART:123') 'exact known lot is valid'
Assert-Probe (-not (Test-StockProbeResult (Get-ProbeFixture 'PART:123' -RowCount 1) 'PART:124')) 'wrong lot is rejected'
$badSample = Get-ProbeFixture $identifier -RowCount 1
$badSample.Output = $badSample.Output.Replace('SAMPLE_EAN 4601234567890', 'SAMPLE_EAN 0000000000000')
Assert-Probe (-not (Test-StockProbeResult $badSample $identifier)) 'wrong barcode is rejected'
$badSample.Output = $badSample.Output.Replace('SAMPLE_QUANT 2.5', 'MISSING_QUANT 2.5')
Assert-Probe (-not (Test-StockProbeResult $badSample $identifier)) 'partial rows cannot pass'

foreach ($kind in @('barcode','part')) {
    $value = if ($kind -eq 'barcode') { $identifier } else { 'PART:123' }
    foreach ($phase in @('plan','sample')) {
        $sql = Get-StockProbeSql $value -PlanOnly:($phase -eq 'plan')
        Assert-Probe ($sql -notmatch '(?im)^\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|EXECUTE)\b' -and $sql -notmatch 'SELECT\s+\*') 'probe is fixed SELECT without mutations or SELECT star'
        Assert-Probe ($sql.Contains('SET TRANSACTION READ ONLY READ COMMITTED RECORD_VERSION NO WAIT;') -and $sql.Contains('SET BAIL ON;')) 'readonly and bail are retained'
        if ($phase -eq 'plan') { Assert-Probe ($sql.IndexOf('SET PLANONLY ON;') -lt $sql.IndexOf('SELECT FIRST')) 'plan-only enabled before product query'
        } else { Assert-Probe ($sql.IndexOf('SET PLANONLY OFF;') -lt $sql.IndexOf('SELECT FIRST')) 'sample mode is explicit' }
        if ($ExportSqlDirectory) {
            $null = New-Item -ItemType Directory -Path $ExportSqlDirectory -Force
            [IO.File]::WriteAllText((Join-Path $ExportSqlDirectory ($kind + '-' + $phase + '.sql')), $sql, [Text.Encoding]::ASCII)
        }
        if ($NativeOutputDirectory) {
            $native = [pscustomobject]@{TimedOut=$false;Truncated=$false;ExitCode=0;Output=[IO.File]::ReadAllText((Join-Path $NativeOutputDirectory ($kind + '-' + $phase + '.output')))}
            Assert-Probe (Test-StockProbeResult $native $value -PlanOnly:($phase -eq 'plan')) ('actual Firebird output passes ' + $kind + '/' + $phase + ' gate')
        }
    }
}
$bat = [IO.File]::ReadAllText((Join-Path $root 'cashier-stock-probe.bat'))
$payload = [regex]::Match($bat, '(?m)^::EPHARM_PS_BASE64=([A-Za-z0-9+/=]+)\r?$')
Assert-Probe ($payload.Success -and [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload.Groups[1].Value)) -eq [IO.File]::ReadAllText((Join-Path $root 'cashier-stock-diagnostics.ps1'))) 'probe BAT contains exact verified source'
Assert-Probe ($bat.Contains('-ManualAuthentication -StockProbe')) 'probe BAT selects manual credentials and probe mode'

# Simulate the operator and native runner; these tests never connect to a database.
function Read-Host([string]$Prompt) { return $script:answers.Dequeue() }
function Invoke-IsqlBatch([string]$Isql, $Endpoint, [Security.SecureString]$Password, [string]$UserName, [string]$Sql) {
    $script:calls.Add($Sql); $Password.Dispose()
    return $script:results.Dequeue()
}
foreach ($case in @('success','bad-plan','cancel')) {
    $script:answers = New-Object 'System.Collections.Generic.Queue[string]'
    $script:answers.Enqueue($identifier); $script:answers.Enqueue($(if ($case -eq 'cancel') { '' } else { 'READ' }))
    $script:results = New-Object 'System.Collections.Generic.Queue[object]'
    $first = Get-ProbeFixture $identifier -PlanOnly
    if ($case -eq 'bad-plan') { $first.Output = $first.Output.Replace('INDEX (WAREBASE_IDX2)', 'NATURAL') }
    $script:results.Enqueue($first); $script:results.Enqueue((Get-ProbeFixture $identifier -RowCount 2))
    $script:calls = New-Object 'System.Collections.Generic.List[string]'
    $script:Lines.Clear()
    Invoke-StockProbe '/fake/isql' ([pscustomobject]@{Connection='127.0.0.1/3050:fake'}) (ConvertTo-SecureString 'fixture-probe' -AsPlainText -Force) 'PILOT_USER'
    $report = $script:Lines -join "`n"
    if ($case -eq 'success') { Assert-Probe ($script:calls.Count -eq 2 -and $report.Contains('STOCK_SAMPLE_STATUS=OK; RETURNED_ROWS=2')) 'READ performs exactly one bounded sample after validated plan'
    } else { Assert-Probe ($script:calls.Count -eq 1 -and -not $report.Contains('STOCK_SAMPLE_STATUS=OK')) 'invalid plan or operator cancel never executes product sample' }
}
Write-Host 'PASS: strict selectors, bounded readonly SQL, exact index plan, sample identity/completeness, operator gate and BAT packaging.'
