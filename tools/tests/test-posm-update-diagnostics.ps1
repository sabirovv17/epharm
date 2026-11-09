#requires -version 5.1
# Synthetic file-only contracts. Does not invoke process/task/environment inventory or network.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../collect-posm-update-diagnostics.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('epharm-update-diagnostics-tests-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixture
$passed = 0
function Confirm([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
}
try {
    $configPath = Join-Path $fixture 'posm.json'
    $secret = 'SYNTHETIC_SECRET_MUST_NOT_APPEAR'
    [IO.File]::WriteAllText($configPath, ('{"UpdateEnabled":false,"UpdatePollSec":900,"BackendBaseUrl":"https://user:' + $secret + '@example.invalid/api?token=' + $secret + '","DeviceKey":"' + $secret + '","Nested":{"password":"' + $secret + '"},"UpdateManifestPublicKeySpki":"' + $secret + '"}'))
    $before = [IO.File]::ReadAllText($configPath)
    $config = Get-UpdateConfigSummary $configPath
    $json = $config | ConvertTo-Json -Depth 9
    Confirm ($config.UpdateEnabled -eq $false -and $config.UpdatePollSec -eq 900) 'typed updater allowlist'
    Confirm ($config.BackendOrigin -eq 'https://example.invalid') 'origin excludes URL credential/query/path'
    Confirm (-not $json.Contains($secret)) 'no nested/config/key secret output'
    Confirm ($before -ceq [IO.File]::ReadAllText($configPath)) 'config unchanged'

    $logPath = Join-Path $fixture 'update.log'
    [IO.File]::WriteAllText($logPath, "private-IIN-$secret`napply failed after 5 attempts`nUpdateEnabled=false`nsha256 отсутствует или не совпал")
    $log = Get-UpdateLogSignals $logPath
    $json = $log | ConvertTo-Json -Depth 9
    Confirm ($log.Signals.APPLY_FAILED -eq 1 -and $log.Signals.UPDATE_DISABLED -eq 1 -and $log.Signals.HASH_REJECTED -eq 1) 'fixed log signal codes'
    Confirm (-not $json.Contains($secret) -and -not $json.Contains('private-IIN')) 'no raw log text'

    # ASCII-only Unicode escapes are independent of this script's source encoding. These
    # exact log messages come from baseline 1.0.81 and the local recovery updater.
    # Explicit UTF-8 bytes prevent a matching mojibake fixture from hiding a PS 5.1 parser bug.
    $unicodeCases = @(
        @{ Code = 'MANIFEST_REJECTED'; Log = 'update: \u043f\u043e\u0434\u043f\u0438\u0441\u044c \u043c\u0430\u043d\u0438\u0444\u0435\u0441\u0442\u0430 \u043e\u0442\u0441\u0443\u0442\u0441\u0442\u0432\u0443\u0435\u0442 \u0438\u043b\u0438 \u043d\u0435 \u043f\u0440\u043e\u0448\u043b\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0443 \u2014 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435 \u043e\u0442\u043a\u043b\u043e\u043d\u0435\u043d\u043e' }
        @{ Code = 'MANIFEST_REJECTED'; Log = 'update: \u043f\u043e\u0434\u043f\u0438\u0441\u044c \u043c\u0430\u043d\u0438\u0444\u0435\u0441\u0442\u0430 \u043d\u0435 \u043f\u0440\u043e\u0448\u043b\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0443 \u2014 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435 \u043e\u0442\u043a\u043b\u043e\u043d\u0435\u043d\u043e' }
        @{ Code = 'HASH_REJECTED'; Log = 'update: sha256 \u043e\u0442\u0441\u0443\u0442\u0441\u0442\u0432\u0443\u0435\u0442 \u0438\u043b\u0438 \u043d\u0435 \u0441\u043e\u0432\u043f\u0430\u043b \u2014 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435 \u043e\u0442\u043a\u043b\u043e\u043d\u0435\u043d\u043e (\u0432\u043e\u0437\u043c\u043e\u0436\u043d\u0430 \u043f\u043e\u0440\u0447\u0430/\u043f\u043e\u0434\u043c\u0435\u043d\u0430)' }
        @{ Code = 'HASH_REJECTED'; Log = 'update: SHA-256 \u043d\u0435 \u0441\u043e\u0432\u043f\u0430\u043b; \u043f\u0430\u043a\u0435\u0442 \u043e\u0442\u043a\u043b\u043e\u043d\u0451\u043d' }
        @{ Code = 'DOWNLOAD_FAILURE'; Log = 'update: \u0441\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u0435 \u2192 HTTP 503, \u043f\u043e\u043f\u044b\u0442\u043a\u0430 1/4' }
        @{ Code = 'DOWNLOAD_FAILURE'; Log = 'update: \u0441\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u0435 1/4: IOException' }
        @{ Code = 'DOWNLOAD_DEADLINE'; Log = 'update: \u0441\u0440\u043e\u043a \u0441\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u044f \u0438\u0441\u0442\u0451\u043a \u0438\u043b\u0438 \u043f\u0430\u043a\u0435\u0442 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d; \u0447\u0430\u0441\u0442\u0438\u0447\u043d\u044b\u0439 \u0444\u0430\u0439\u043b \u0441\u043e\u0445\u0440\u0430\u043d\u0451\u043d, \u0441\u043b\u0435\u0434\u0443\u044e\u0449\u0430\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0440\u0430\u0437\u0440\u0435\u0448\u0435\u043d\u0430' }
        @{ Code = 'APPLY_STARTED'; Log = 'update: \u043f\u0440\u0438\u043c\u0435\u043d\u044f\u044e \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435, \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u043a \u0447\u0435\u0440\u0435\u0437 apply-update.cmd. \u0417\u0430\u043a\u0440\u044b\u0432\u0430\u044e \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435.' }
        @{ Code = 'APPLY_STARTED'; Log = 'update: \u043a\u0430\u043d\u0434\u0438\u0434\u0430\u0442 \u0438 \u0440\u0435\u0437\u0435\u0440\u0432\u043d\u0430\u044f \u043a\u043e\u043f\u0438\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u0435\u043d\u044b; \u0437\u0430\u043f\u0443\u0441\u043a \u0441 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u043e\u0439 \u0433\u043e\u0442\u043e\u0432\u043d\u043e\u0441\u0442\u0438 \u0438 \u043e\u0442\u043a\u0430\u0442\u043e\u043c \u043f\u0440\u0438 \u0441\u0431\u043e\u0435' }
        @{ Code = 'RECOVERY_PENDING'; Log = 'update: \u0432\u043e\u0441\u0441\u0442\u0430\u043d\u0430\u0432\u043b\u0438\u0432\u0430\u0435\u0442\u0441\u044f \u043d\u0435\u0437\u0430\u0432\u0435\u0440\u0448\u0451\u043d\u043d\u043e\u0435 \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435; \u043e\u0431\u044b\u0447\u043d\u044b\u0439 \u0437\u0430\u043f\u0443\u0441\u043a \u043e\u0442\u043b\u043e\u0436\u0435\u043d' }
        @{ Code = 'LOCAL_HEALTH_ACK'; Log = 'update: \u043d\u043e\u0432\u044b\u0439 \u043f\u0440\u043e\u0446\u0435\u0441\u0441 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u043b \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u0443\u044e \u0433\u043e\u0442\u043e\u0432\u043d\u043e\u0441\u0442\u044c \u0438 3 \u043f\u0440\u043e\u0445\u043e\u0434\u0430 UI-\u0434\u0438\u0441\u043f\u0435\u0442\u0447\u0435\u0440\u0430' }
        @{ Code = 'PACKAGE_PREVIOUSLY_REJECTED'; Log = 'update: \u0437\u0430\u043f\u0443\u0441\u043a \u044d\u0442\u043e\u0433\u043e \u043f\u0430\u043a\u0435\u0442\u0430 \u0431\u044b\u043b \u043e\u0442\u043c\u0435\u043d\u0451\u043d \u0441 \u0432\u043e\u0437\u0432\u0440\u0430\u0442\u043e\u043c \u043f\u0440\u0435\u0434\u044b\u0434\u0443\u0449\u0435\u0439 \u0432\u0435\u0440\u0441\u0438\u0438; \u043e\u0436\u0438\u0434\u0430\u0435\u0442\u0441\u044f \u0438\u0441\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u043d\u044b\u0439 \u0440\u0435\u043b\u0438\u0437' }
    )
    foreach ($case in $unicodeCases) {
        [IO.File]::WriteAllBytes($logPath, [Text.Encoding]::UTF8.GetBytes([regex]::Unescape($case.Log)))
        $signals = (Get-UpdateLogSignals $logPath).Signals
        Confirm ($signals[$case.Code] -eq 1) ("independent UTF8 log: " + $case.Code)
    }

    [IO.File]::WriteAllText($logPath, ('apply failed' + ('x' * 524288)))
    Confirm ((Get-UpdateLogSignals $logPath).Signals.Count -eq 0) 'bounded tail excludes old prefix'

    [IO.File]::WriteAllText($configPath, ('x' * 65537))
    Confirm ((Get-UpdateConfigSummary $configPath).Status -eq 'MISSING_INVALID_OR_UNSAFE') 'oversized config rejected'
    [IO.File]::WriteAllText($configPath, '{broken')
    Confirm ((Get-UpdateConfigSummary $configPath).Status -eq 'MISSING_INVALID_OR_UNSAFE') 'invalid config rejected'

    $install = Join-Path $fixture 'install'
    $control = Join-Path $install '.epharm-update'
    $id = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    $transaction = Join-Path $control $id
    $null = New-Item -ItemType Directory -Path $transaction -Force
    [IO.File]::WriteAllText((Join-Path $transaction 'state.json'), ('{"Status":"awaiting-health","Token":"' + $secret + '","ProcessId":999}'))
    [IO.File]::WriteAllText((Join-Path $control 'active.json'), ('{"Id":"' + $id + '","Token":"' + $secret + '"}'))
    $snapshot = Get-UpdateInstallSummary $install
    $json = $snapshot | ConvertTo-Json -Depth 9
    Confirm ($snapshot.RecoveryStates[0].Status -eq 'awaiting-health' -and $snapshot.Active.Status -eq 'PRESENT') 'recovery status and metadata only'
    Confirm (-not $json.Contains($secret) -and -not $json.Contains('ProcessId')) 'no plan/ACK token or arbitrary state properties'
    $unsafe = Join-Path $fixture 'linked.log'
    $null = New-Item -ItemType Junction -Path $unsafe -Target $transaction
    try { Confirm ((Get-UpdateLogSignals $unsafe).Status -eq 'MISSING_INVALID_OR_UNSAFE') 'reparse path refused' }
    finally { [IO.Directory]::Delete($unsafe, $false) }
    Confirm ((Get-UpdateFileMetadata (Join-Path $fixture 'absent.log')).Status -eq 'MISSING') 'missing file distinguished'
    Write-Output "PASS: $passed synthetic updater-diagnostics contracts; no workstation inventory or network executed."
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^epharm-update-diagnostics-tests-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup target' }
    $null = Assert-UpdateDiagnosticPath $resolved
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
