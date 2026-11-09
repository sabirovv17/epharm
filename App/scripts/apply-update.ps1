param([string]$PlanPath, [switch]$Recovery)

# Embedded in the application. No scheduled-task, registry, database, config or queue writes.
# Dot-sourcing exposes the same transaction functions to disposable filesystem fault tests.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-EpharmPhysical([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        # Exists follows the link and hides dangling reparse entries. Inspect the entry itself.
        $attributes = $null
        try { $attributes = [IO.File]::GetAttributes($current) }
        catch [IO.FileNotFoundException] { }
        catch [IO.DirectoryNotFoundException] { }
        if ($null -ne $attributes -and ($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Update path contains a reparse point.'
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Get-EpharmChild([string]$Root, [string]$Relative) {
    if ([IO.Path]::IsPathRooted($Relative) -or $Relative -match '(^|[\\/])\.\.?([\\/]|$)|[:*?"<>|]') {
        throw 'Unsafe update relative path.'
    }
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $path = [IO.Path]::GetFullPath([IO.Path]::Combine($rootPath, $Relative))
    if (-not $path.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Escaping update path.' }
    Assert-EpharmPhysical $path
    return $path
}

function Get-EpharmHash([string]$Path) {
    if (-not [IO.File]::Exists($Path)) { return $null }
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}

function Write-EpharmJson([string]$Path, $Value) {
    $temporary = $Path + '.tmp'
    Assert-EpharmPhysical $Path
    Assert-EpharmPhysical $temporary
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 10))
    $stream = [IO.File]::Open($temporary, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
    else { [IO.File]::Move($temporary, $Path) }
}

function Read-EpharmPlan([string]$Path, [bool]$CheckCandidate = $true) {
    Assert-EpharmPhysical $Path
    $plan = [IO.File]::ReadAllText($Path) | ConvertFrom-Json
    if ($plan.Id -notmatch '^[0-9a-f]{32}$' -or $plan.Token -notmatch '^[0-9A-F]{64}$') { throw 'Invalid update identity.' }
    $transaction = [IO.Path]::Combine($plan.InstallRoot, '.epharm-update', $plan.Id)
    if ([IO.Path]::GetFullPath($Path) -ine [IO.Path]::Combine($transaction, 'plan.json')) { throw 'Foreign update plan.' }
    Assert-EpharmPhysical $plan.InstallRoot
    $names = @{}
    foreach ($file in $plan.Files) {
        if ($names.ContainsKey($file.RelativePath)) { throw 'Duplicate update target.' }
        $names[$file.RelativePath] = $true
        # Mirror the runtime-only preparation boundary; never permit a plan to target pharmacy state.
        if ($file.RelativePath -notmatch '^(?:(?:runtimes|libvlc|x64|x86|Assets)[\\/].+[\\/])?[^\\/]+\.(dll|exe|ttf|otf)$' -and
            $file.RelativePath -notmatch '^(?:(?:runtimes|libvlc|x64|x86|Assets)[\\/])?[^\\/]+\.(dll|exe|ttf|otf)$' -and
            $file.RelativePath -notmatch '^CustomerDisplay\.(deps|runtimeconfig)\.json$') { throw 'Non-runtime update target.' }
        if ($file.NewSha256 -notmatch '^[0-9A-F]{64}$' -or ($null -ne $file.OldSha256 -and $file.OldSha256 -notmatch '^[0-9A-F]{64}$')) { throw 'Invalid binary hash.' }
        $null = Get-EpharmChild $plan.InstallRoot $file.RelativePath
        $new = Get-EpharmChild ([IO.Path]::Combine($transaction, 'candidate')) $file.RelativePath
        $old = Get-EpharmChild ([IO.Path]::Combine($transaction, 'backup')) $file.RelativePath
        if ($CheckCandidate -and (Get-EpharmHash $new) -ne $file.NewSha256) { throw 'Candidate integrity failure.' }
        if ($null -ne $file.OldSha256 -and (Get-EpharmHash $old) -ne $file.OldSha256) { throw 'Backup integrity failure.' }
    }
    if (-not $names.ContainsKey('CustomerDisplay.exe') -or -not $names.ContainsKey('CustomerDisplay.dll')) { throw 'Missing application payload.' }
    return $plan
}

function Set-EpharmState($Plan, [string]$Status) {
    $process = [Diagnostics.Process]::GetCurrentProcess()
    Write-EpharmJson ([IO.Path]::Combine($Plan.InstallRoot, '.epharm-update', $Plan.Id, 'state.json')) @{
        Status = $Status; ProcessId = $PID; ProcessStartTicks = $process.StartTime.ToUniversalTime().Ticks
        UpdatedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Copy-EpharmAtomic([string]$Source, [string]$Target, [string]$Hash, [string]$Id) {
    Assert-EpharmPhysical $Target
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Target))
    $temporary = $Target + '.epharm-' + $Id + '.tmp'
    Assert-EpharmPhysical $temporary
    [IO.File]::Copy($Source, $temporary, $true)
    if ((Get-EpharmHash $temporary) -ne $Hash) { throw 'Prepared replacement hash mismatch.' }
    # Same-volume rename/Replace: an individual target is never a partially copied DLL.
    if ([IO.File]::Exists($Target)) { [IO.File]::Replace($temporary, $Target, [NullString]::Value) }
    else { [IO.File]::Move($temporary, $Target) }
}

function Restore-EpharmFiles($Plan) {
    Set-EpharmState $Plan 'rolling-back'
    $transaction = [IO.Path]::Combine($Plan.InstallRoot, '.epharm-update', $Plan.Id)
    foreach ($file in $Plan.Files) {
        $target = Get-EpharmChild $Plan.InstallRoot $file.RelativePath
        $actual = Get-EpharmHash $target
        if ($actual -eq $file.OldSha256) { continue }
        # Unknown third-party bytes must not be overwritten by recovery.
        if ($actual -ne $file.NewSha256) { throw ('Rollback refuses modified binary: ' + $file.RelativePath) }
        if ($null -eq $file.OldSha256) { [IO.File]::Delete($target) }
        else {
            $backup = Get-EpharmChild ([IO.Path]::Combine($transaction, 'backup')) $file.RelativePath
            Copy-EpharmAtomic $backup $target $file.OldSha256 $Plan.Id
        }
    }
    foreach ($file in $Plan.Files) {
        if ((Get-EpharmHash (Get-EpharmChild $Plan.InstallRoot $file.RelativePath)) -ne $file.OldSha256) { throw 'Rollback verification failed.' }
    }
    Write-EpharmJson ([IO.Path]::Combine($Plan.InstallRoot, '.epharm-update', 'rejected.json')) @{
        PackageSha256 = $Plan.PackageSha256; Version = $Plan.Version; FailedAt = [DateTime]::UtcNow.ToString('o')
    }
    Set-EpharmState $Plan 'rolled-back'
    [IO.File]::Delete([IO.Path]::Combine($Plan.InstallRoot, '.epharm-update', 'active.json'))
}

function Invoke-EpharmFileTransaction($Plan, [scriptblock]$HealthProbe, [scriptblock]$BeforeFile = {},
    [scriptblock]$MayRollback = { return $true }) {
    $transaction = [IO.Path]::Combine($Plan.InstallRoot, '.epharm-update', $Plan.Id)
    Set-EpharmState $Plan 'applying'
    $healthy = $false
    try {
        foreach ($file in $Plan.Files) {
            & $BeforeFile $file
            $target = Get-EpharmChild $Plan.InstallRoot $file.RelativePath
            if ((Get-EpharmHash $target) -ne $file.OldSha256) { throw 'Live binary changed after preparation.' }
            Copy-EpharmAtomic (Get-EpharmChild ([IO.Path]::Combine($transaction, 'candidate')) $file.RelativePath) $target $file.NewSha256 $Plan.Id
        }
        Set-EpharmState $Plan 'awaiting-health'
        if (-not (& $HealthProbe $Plan)) { throw 'New application did not acknowledge UI readiness.' }
        $healthy = $true
        Set-EpharmState $Plan 'committed'
        [IO.File]::Delete([IO.Path]::Combine($Plan.InstallRoot, '.epharm-update', 'active.json'))
        # Keep backup + plan for evidence. No recursive removal of installation/data directories.
        return $true
    } catch {
        $originalError = $_
        # Never replace a healthy candidate's loaded binaries after a metadata-only failure.
        # The durable pending marker lets the next cold start recover under the process mutex.
        if ($healthy) { throw }
        if (-not (& $MayRollback)) {
            # A failed child stop/mutex acquisition is NOT permission to replace loaded files.
            Set-EpharmState $Plan 'recovery-required'
            throw $originalError
        }
        try { Restore-EpharmFiles $Plan }
        catch { Set-EpharmState $Plan 'recovery-required'; throw }
        throw $originalError
    }
}

function Test-EpharmProcess([int]$Id, [long]$StartTicks) {
    try {
        $process = Get-Process -Id $Id -ErrorAction Stop
        return $process.StartTime.ToUniversalTime().Ticks -eq $StartTicks
    } catch { return $false }
}

function Enter-EpharmInstanceMutex($Mutex) {
    try { return $Mutex.WaitOne(30000) }
    catch [Threading.AbandonedMutexException] { return $true }
}

function Start-EpharmApply([string]$Path, [bool]$Recover, [scriptblock]$Launch = $null,
    [string]$InstanceMutexName = 'Local\EpharmCustomerDisplay', [int]$HealthTimeoutSeconds = 90,
    [scriptblock]$HealthClock = { return [DateTime]::UtcNow }) {
    if ($HealthTimeoutSeconds -lt 1 -or $HealthTimeoutSeconds -gt 120) { throw 'Invalid readiness timeout.' }
    # Rollback needs the intact last-known-good backup, not an intact failed candidate cache.
    $plan = Read-EpharmPlan $Path (-not $Recover)
    $transaction = [IO.Path]::GetDirectoryName($Path)
    if ($null -eq $Launch) {
        $Launch = {
            param([string]$Root, [string]$Token)
            $options = @{
                FilePath = [IO.Path]::Combine($Root, 'CustomerDisplay.exe')
                # The helper is hidden; POSM itself must retain its native customer-screen/UI mode.
                WorkingDirectory = $Root; WindowStyle = 'Normal'; PassThru = $true
            }
            if ($Token) { $options.ArgumentList = @('--update-candidate', $Token) }
            Start-Process @options
        }
    }
    $exclusive = New-Object Threading.Mutex($false, ('Local\EpharmPosmUpdate-' + $plan.Id))
    $appMutex = New-Object Threading.Mutex($false, $InstanceMutexName)
    $exclusiveOwned = $false
    $script:appOwned = $false
    $script:candidate = $null
    $applyError = $null
    try {
        try { $exclusiveOwned = $exclusive.WaitOne(0) } catch [Threading.AbandonedMutexException] { $exclusiveOwned = $true }
        if (-not $exclusiveOwned) { return }
        Set-EpharmState $plan 'waiting-for-exit'
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while (Test-EpharmProcess $plan.ParentId $plan.ParentStartTicks) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Original process did not exit; files left unchanged.' }
            Start-Sleep -Milliseconds 250
        }
        $script:appOwned = Enter-EpharmInstanceMutex $appMutex
        if (-not $script:appOwned) { throw 'Another POSM process owns installation; update deferred.' }
        if ($Recover) { Restore-EpharmFiles $plan }
        else {
            $null = Invoke-EpharmFileTransaction $plan {
                param($healthPlan)
                $ready = [IO.Path]::Combine($transaction, 'ready.json')
                [IO.File]::Delete($ready)
                $healthAccepted = $false
                $appMutex.ReleaseMutex(); $script:appOwned = $false
                try {
                    $script:candidate = & $Launch $plan.InstallRoot $plan.Token
                    $healthDeadline = (& $HealthClock).AddSeconds($HealthTimeoutSeconds)
                    while ((& $HealthClock) -lt $healthDeadline) {
                        if ($script:candidate.HasExited) { return $false }
                        if ([IO.File]::Exists($ready)) {
                            Assert-EpharmPhysical $ready
                            $ack = [IO.File]::ReadAllText($ready) | ConvertFrom-Json
                            if ($ack.Token -ceq $plan.Token -and $ack.ProcessId -eq $script:candidate.Id -and
                                $ack.ProcessStartTicks -eq $script:candidate.StartTime.ToUniversalTime().Ticks -and
                                $ack.UiTicks -ge 3) { $healthAccepted = $true; return $true }
                        }
                        Start-Sleep -Milliseconds 250
                    }
                    return $false
                } finally {
                    # Use the same latched verdict returned to the file transaction. An ACK
                    # arriving at/after the deadline must not leave a live child during rollback.
                    if (-not $healthAccepted) {
                        if ($null -ne $script:candidate -and -not $script:candidate.HasExited) {
                            $script:candidate.Kill(); $null = $script:candidate.WaitForExit(10000)
                        }
                        $script:appOwned = Enter-EpharmInstanceMutex $appMutex
                        if (-not $script:appOwned) { throw 'Cannot lock failed candidate for recovery.' }
                    }
                }
            } {} {
                return $exclusiveOwned -and $script:appOwned -and
                    ($null -eq $script:candidate -or $script:candidate.HasExited)
            }
        }
    } catch {
        # A pending marker remains if safe recovery was impossible. Bootstrap then fails closed
        # and retries recovery instead of repeatedly starting mixed binaries.
        $applyError = $_
        try {
            $logPath = [IO.Path]::Combine($transaction, 'apply.log')
            Assert-EpharmPhysical $logPath
            Add-Content -LiteralPath $logPath -Value ([DateTime]::UtcNow.ToString('o') + ' ' + $applyError.Exception.Message)
        } catch { } # Logging must not follow links or replace the original recovery failure.
    } finally {
        if ($script:appOwned) { $appMutex.ReleaseMutex() }
        if ($exclusiveOwned) { $exclusive.ReleaseMutex() }
        $appMutex.Dispose(); $exclusive.Dispose()
    }
    $state = [IO.File]::ReadAllText([IO.Path]::Combine($transaction, 'state.json')) | ConvertFrom-Json
    if ($state.Status -eq 'rolled-back') {
        $null = & $Launch $plan.InstallRoot $null
    }
    elseif ($null -ne $applyError) { throw $applyError }
}

if ($MyInvocation.InvocationName -ne '.') {
    if (-not $PlanPath) { throw 'An explicit transaction plan is required.' }
    Start-EpharmApply $PlanPath ([bool]$Recovery)
}
