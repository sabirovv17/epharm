using System.Diagnostics;
using System.IO.Compression;
using System.Text;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class UpdateTransactionTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-update-transaction-tests-" + Guid.NewGuid().ToString("N"));
    private string Install => Path.Combine(_root, "install");
    private string Zip => Path.Combine(_root, "candidate.zip");
    private string Script => Path.Combine(AppContext.BaseDirectory, "apply-update.ps1");
    public UpdateTransactionTests()
    {
        Directory.CreateDirectory(Install);
        File.WriteAllText(Path.Combine(Install, "CustomerDisplay.exe"), "previous-executable");
        File.WriteAllText(Path.Combine(Install, "CustomerDisplay.dll"), "previous-assembly");
        File.WriteAllText(Path.Combine(Install, "posm.json"), "pharmacy-private-config");
        File.WriteAllText(Path.Combine(Install, "outbox.db"), "undelivered-sales");
    }

    private string Prepare(params (string Name, string Body)[] extra)
    {
        using (var archive = ZipFile.Open(Zip, ZipArchiveMode.Create))
            foreach (var entry in new[] { ("CustomerDisplay.exe", "candidate-executable"), ("CustomerDisplay.dll", "candidate-assembly"), ("NewDependency.dll", "candidate-dependency") }.Concat(extra))
            {
                using var writer = new StreamWriter(archive.CreateEntry(entry.Item1).Open());
                writer.Write(entry.Item2);
            }
        return UpdateTransaction.Prepare(Install, Zip, "1.2.3", UpdateTransaction.Hash(Zip), File.ReadAllText(Script));
    }

    [Fact]
    public void PreparationDoesNotMutateLiveInstallationAndRetainsVerifiedBackup()
    {
        var path = Prepare();
        AssertPrevious();
        var plan = UpdateTransaction.ReadActive(Install)!;
        Assert.Equal(3, plan.Files.Length);
        Assert.Equal("previous-executable", File.ReadAllText(Path.Combine(Path.GetDirectoryName(path)!, "backup", "CustomerDisplay.exe")));
        Assert.DoesNotContain(plan.Files, f => f.RelativePath == "posm.json" || f.RelativePath == "outbox.db");
    }

    [Theory]
    [InlineData("apply-update.ps1", false)]
    [InlineData("apply-update.ps1.tmp", false)]
    [InlineData("apply-update.ps1", true)]
    [InlineData("apply-update.ps1.tmp", true)]
    public void HelperPublicationRejectsExistingReparsePointWithoutTouchingItsTarget(string name, bool dangling)
    {
        var transaction = Path.GetDirectoryName(Prepare())!;
        var helper = Path.Combine(transaction, "apply-update.ps1");
        var original = File.ReadAllBytes(helper);
        var foreign = Path.Combine(_root, "foreign-helper-target");
        Directory.CreateDirectory(foreign);
        var sentinel = Path.Combine(foreign, "do-not-touch.txt");
        File.WriteAllText(sentinel, "foreign-data");
        var link = Path.Combine(transaction, name);
        if (File.Exists(link)) File.Delete(link);
        var target = dangling ? Path.Combine(_root, "absent-helper-target") : foreign;
        TestDirectoryLink.Create(link, target);
        try
        {
            Assert.Throws<InvalidDataException>(() => UpdateTransaction.WriteApplierScript(transaction, "replacement"));
            Assert.Equal("foreign-data", File.ReadAllText(sentinel));
            Assert.Equal(new[] { sentinel }, Directory.GetFiles(foreign));
            Assert.True((File.GetAttributes(link) & FileAttributes.ReparsePoint) != 0);
            if (dangling) Assert.False(Directory.Exists(target));
            if (name.EndsWith(".tmp", StringComparison.Ordinal)) Assert.Equal(original, File.ReadAllBytes(helper));
        }
        finally { TestDirectoryLink.Remove(link); }
        AssertPrevious();
    }

    [Fact]
    public void HelperPublicationReplacesExistingScriptAndInterruptedTemporaryFile()
    {
        var transaction = Path.GetDirectoryName(Prepare())!;
        var helper = Path.Combine(transaction, "apply-update.ps1");
        File.WriteAllText(helper + ".tmp", "incomplete previous write");
        UpdateTransaction.WriteApplierScript(transaction, "# embedded recovery helper\n");
        Assert.Equal("# embedded recovery helper\n", File.ReadAllText(helper));
        Assert.True(File.ReadAllBytes(helper).AsSpan().StartsWith(new byte[] { 0xEF, 0xBB, 0xBF }));
        Assert.False(File.Exists(helper + ".tmp"));
        Assert.NotNull(UpdateTransaction.ReadActive(Install));
        AssertPrevious();
    }

    [Fact]
    public void ReleasedUpdaterMutexHandleDoesNotPreventCandidateAcquiringOwnership()
    {
        var name = "Local\\EpharmUpdateTest-" + Guid.NewGuid().ToString("N");
        using var helper = new Mutex(true, name);
        helper.ReleaseMutex(); // Helper still keeps its handle open for later rollback.
        using var candidate = new Mutex(false, name, out var createdNew);
        Assert.False(createdNew);
        Assert.True(UpdateTransaction.TryAcquireInstance(candidate));
        candidate.ReleaseMutex();
    }

    [Fact]
    public async Task HeldUpdaterMutexStillRejectsCompetingStartup()
    {
        var name = "Local\\EpharmUpdateTest-" + Guid.NewGuid().ToString("N");
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var owner = Task.Run(() =>
        {
            using var helper = new Mutex(true, name);
            entered.Set();
            try { Assert.True(release.Wait(TimeSpan.FromSeconds(5))); }
            finally { helper.ReleaseMutex(); }
        }, TestContext.Current.CancellationToken);
        Assert.True(entered.Wait(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        try
        {
            using var candidate = new Mutex(false, name);
            Assert.False(UpdateTransaction.TryAcquireInstance(candidate));
        }
        finally { release.Set(); }
        await owner;
    }

    [Theory]
    [InlineData("../outside.dll")]
    [InlineData("posm.json")]
    [InlineData("outbox.db")]
    [InlineData("CUSTOMERDISPLAY.DLL")]
    [InlineData("CustomerDisplay.exe:stream")]
    public void UnsafeOrDuplicateArchiveEntryIsRejectedBeforePublishingActiveState(string name)
    {
        Assert.Throws<InvalidDataException>(() => Prepare((name, "unsafe")));
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
        Assert.Empty(Directory.EnumerateDirectories(UpdateTransaction.ControlRoot(Install)));
        AssertPrevious();
    }

    [Fact]
    public void RetentionKeepsTwoNewestCompletedBundlesAndNeverDeletesForeignFiles()
    {
        var first = Prepare();
        CompleteFixture(first, DateTime.UtcNow.AddMinutes(-4));
        var second = UpdateTransaction.Prepare(Install, Zip, "1.2.4", UpdateTransaction.Hash(Zip), File.ReadAllText(Script));
        CompleteFixture(second, DateTime.UtcNow.AddMinutes(-3));
        var third = UpdateTransaction.Prepare(Install, Zip, "1.2.5", UpdateTransaction.Hash(Zip), File.ReadAllText(Script));
        CompleteFixture(third, DateTime.UtcNow.AddMinutes(-2));
        var fourth = UpdateTransaction.Prepare(Install, Zip, "1.2.6", UpdateTransaction.Hash(Zip), File.ReadAllText(Script));
        Assert.False(Directory.Exists(Path.GetDirectoryName(first)));
        Assert.True(Directory.Exists(Path.GetDirectoryName(second)));
        Assert.True(Directory.Exists(Path.GetDirectoryName(third)));
        Assert.True(File.Exists(fourth));
        AssertStatePreserved();
    }

    private void CompleteFixture(string plan, DateTime changed)
    {
        var state = Path.Combine(Path.GetDirectoryName(plan)!, "state.json");
        UpdateTransaction.WriteJson(state, new { Status = "committed" });
        File.SetLastWriteTimeUtc(state, changed);
        File.Delete(UpdateTransaction.ActivePath(Install));
    }

    [Fact]
    public async Task SuccessfulTransactionRequiresHealthAndPreservesAllPharmacyState()
    {
        var plan = Prepare();
        await RunScript(plan, "$null = Invoke-EpharmFileTransaction $plan { return $true }");
        Assert.Equal("candidate-executable", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.exe")));
        Assert.Equal("candidate-assembly", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.dll")));
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
        AssertStatePreserved();
    }

    [Fact]
    public async Task ApplyOrchestrationReleasesMutexAndAcceptsOnlyMatchingCandidateAck()
    {
        var path = Prepare();
        var plan = UpdateTransaction.ReadActive(Install)!;
        UpdateTransaction.WriteJson(path, plan with { ParentId = -1, ParentStartTicks = 0 });
        await RunScript(path, """
            $script:launches = 0
            $testMutex = 'Local\EpharmUpdateIntegration-' + $plan.Id
            $launch = {
                param($root, $token)
                $script:launches++
                $check = New-Object Threading.Mutex($false, $testMutex)
                if (-not $check.WaitOne(0)) { throw 'Applier did not release instance mutex for candidate' }
                $check.ReleaseMutex(); $check.Dispose()
                $born = [DateTime]::Now
                Write-EpharmJson ([IO.Path]::Combine($root, '.epharm-update', $plan.Id, 'ready.json')) @{
                    Token = $token; ProcessId = 123456; ProcessStartTicks = $born.ToUniversalTime().Ticks; UiTicks = 3
                }
                return [pscustomobject]@{ Id = 123456; StartTime = $born; HasExited = $false }
            }
            Start-EpharmApply $pathForApply $false $launch $testMutex
            if ($script:launches -ne 1) { throw 'Expected one candidate launch and no rollback restart' }
            """.Replace("$pathForApply", "'" + path.Replace("'", "''") + "'"));
        Assert.Equal("candidate-executable", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.exe")));
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
    }

    [Fact]
    public async Task ApplyOrchestrationRestoresAfterActualLaunchCallbackThrows()
    {
        var path = Prepare();
        UpdateTransaction.WriteJson(path, UpdateTransaction.ReadActive(Install)! with { ParentId = -1, ParentStartTicks = 0 });
        await RunScript(path, """
            $script:launches = 0
            $launch = {
                param($root, $token)
                $script:launches++
                if ($token) { throw 'Synthetic Win32 process creation failure' }
                return [pscustomobject]@{ HasExited = $true }
            }
            Start-EpharmApply $pathForApply $false $launch ('Local\EpharmUpdateIntegration-' + $plan.Id)
            if ($script:launches -ne 2) { throw 'Expected failed launch followed by previous-version restart' }
            """.Replace("$pathForApply", "'" + path.Replace("'", "''") + "'"));
        AssertPrevious();
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
    }

    [Fact]
    public async Task WrongReadinessTokenTimesOutAndStopsCandidateBeforeRollback()
    {
        var path = Prepare();
        UpdateTransaction.WriteJson(path, UpdateTransaction.ReadActive(Install)! with { ParentId = -1, ParentStartTicks = 0 });
        await RunScript(path, """
            $script:fakeKilled = $false
            $launch = {
                param($root, $token)
                if (-not $token) { return [pscustomobject]@{ HasExited = $true } }
                $born = [DateTime]::Now
                Write-EpharmJson ([IO.Path]::Combine($root, '.epharm-update', $plan.Id, 'ready.json')) @{
                    Token = 'wrong-token'; ProcessId = 123456; ProcessStartTicks = $born.ToUniversalTime().Ticks; UiTicks = 3
                }
                $fake = [pscustomobject]@{ Id = 123456; StartTime = $born; HasExited = $false }
                $fake | Add-Member ScriptMethod Kill { $this.HasExited = $true; $script:fakeKilled = $true }
                $fake | Add-Member ScriptMethod WaitForExit { param($milliseconds) return $true }
                return $fake
            }
            Start-EpharmApply $pathForApply $false $launch ('Local\EpharmUpdateIntegration-' + $plan.Id) 1
            if (-not $script:fakeKilled) { throw 'Unhealthy candidate was not stopped before rollback' }
            """.Replace("$pathForApply", "'" + path.Replace("'", "''") + "'"));
        AssertPrevious();
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
    }

    [Fact]
    public async Task AckArrivingAtDeadlineCannotLeaveLiveCandidateDuringRollback()
    {
        var path = Prepare();
        UpdateTransaction.WriteJson(path, UpdateTransaction.ReadActive(Install)! with { ParentId = -1, ParentStartTicks = 0 });
        await RunScript(path, """
            $script:fakeKilled = $false
            $script:launches = 0
            $script:clockCalls = 0
            $launch = {
                param($root, $token)
                $script:launches++
                if (-not $token) { return [pscustomobject]@{ HasExited = $true } }
                $fake = [pscustomobject]@{ Id = 123456; StartTime = [DateTime]::UtcNow; HasExited = $false }
                $fake | Add-Member ScriptMethod Kill { $this.HasExited = $true; $script:fakeKilled = $true }
                $fake | Add-Member ScriptMethod WaitForExit { param($milliseconds) return $true }
                return $fake
            }
            $clock = {
                $script:clockCalls++
                $epoch = [DateTime]::Parse('2026-01-01T00:00:00Z').ToUniversalTime()
                if ($script:clockCalls -eq 1) { return $epoch }
                # Publish a genuine matching ACK exactly at the failed loop condition.
                Write-EpharmJson ([IO.Path]::Combine($plan.InstallRoot, '.epharm-update', $plan.Id, 'ready.json')) @{
                    Token = $plan.Token; ProcessId = $script:candidate.Id
                    ProcessStartTicks = $script:candidate.StartTime.ToUniversalTime().Ticks; UiTicks = 3
                }
                return $epoch.AddSeconds(1)
            }
            Start-EpharmApply $pathForApply $false $launch ('Local\EpharmUpdateIntegration-' + $plan.Id) 1 $clock
            if (-not $script:fakeKilled -or $script:launches -ne 2) { throw 'Late ACK bypassed child stop/rollback restart' }
            """.Replace("$pathForApply", "'" + path.Replace("'", "''") + "'"));
        AssertPrevious();
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
    }

    [Fact]
    public async Task FailedMutexReacquisitionPreservesCandidateAndPendingRecoveryInsteadOfRollingBack()
    {
        var path = Prepare();
        UpdateTransaction.WriteJson(path, UpdateTransaction.ReadActive(Install)! with { ParentId = -1, ParentStartTicks = 0 });
        var foreign = Path.Combine(_root, "foreign-log-target");
        Directory.CreateDirectory(foreign);
        var sentinel = Path.Combine(foreign, "keep.txt");
        File.WriteAllText(sentinel, "foreign-data");
        var logLink = Path.Combine(Path.GetDirectoryName(path)!, "apply.log");
        TestDirectoryLink.Create(logLink, foreign);
        try
        {
            await RunScript(path, """
            $script:mutexAttempts = 0
            $script:clockCalls = 0
            $script:fakeKilled = $false
            function Enter-EpharmInstanceMutex($Mutex) {
                $script:mutexAttempts++
                if ($script:mutexAttempts -eq 1) { return $Mutex.WaitOne(0) }
                return $false # Deterministic failed reacquisition, no 30-second wait.
            }
            $launch = {
                param($root, $token)
                if (-not $token) { throw 'Must not restart without a safe rollback' }
                $fake = [pscustomobject]@{ Id = 123456; StartTime = [DateTime]::UtcNow; HasExited = $false }
                $fake | Add-Member ScriptMethod Kill { $this.HasExited = $true; $script:fakeKilled = $true }
                $fake | Add-Member ScriptMethod WaitForExit { param($milliseconds) return $true }
                return $fake
            }
            $clock = {
                $script:clockCalls++
                return [DateTime]::Parse('2026-01-01T00:00:00Z').ToUniversalTime().AddSeconds($script:clockCalls - 1)
            }
            $failed = $false
            try { Start-EpharmApply $pathForApply $false $launch ('Local\EpharmUpdateIntegration-' + $plan.Id) 1 $clock }
            catch {
                if ($_.Exception.Message -notlike '*Cannot lock failed candidate for recovery*') { throw }
                $failed = $true
            }
            if (-not $failed -or -not $script:fakeKilled -or $script:mutexAttempts -ne 2) { throw 'Failed lock recovery was hidden' }
            $state = [IO.File]::ReadAllText([IO.Path]::Combine($plan.InstallRoot, '.epharm-update', $plan.Id, 'state.json')) | ConvertFrom-Json
            if ($state.Status -ne 'recovery-required') { throw 'Pending recovery was not recorded' }
            """.Replace("$pathForApply", "'" + path.Replace("'", "''") + "'"));
            Assert.Equal("foreign-data", File.ReadAllText(sentinel));
            Assert.Equal(new[] { sentinel }, Directory.GetFiles(foreign));
        }
        finally { TestDirectoryLink.Remove(logLink); }
        Assert.Equal("candidate-executable", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.exe")));
        Assert.Equal("candidate-assembly", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.dll")));
        Assert.True(File.Exists(UpdateTransaction.ActivePath(Install)));
        AssertStatePreserved();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ApplierRejectsDanglingReparseForMetadataAndRuntimeTemporaryFiles(bool runtimeTemporary)
    {
        var path = Prepare();
        var plan = UpdateTransaction.ReadActive(Install)!;
        var link = runtimeTemporary
            ? Path.Combine(Install, "CustomerDisplay.exe.epharm-" + plan.Id + ".tmp")
            : Path.Combine(Path.GetDirectoryName(path)!, "state.json.tmp");
        var absent = Path.Combine(_root, "absent-target");
        TestDirectoryLink.Create(link, absent);
        try
        {
            var write = runtimeTemporary
                ? "Copy-EpharmAtomic ([IO.Path]::Combine($plan.InstallRoot, '.epharm-update', $plan.Id, 'candidate', 'CustomerDisplay.exe')) ([IO.Path]::Combine($plan.InstallRoot, 'CustomerDisplay.exe')) ($plan.Files | Where-Object RelativePath -eq 'CustomerDisplay.exe').NewSha256 $plan.Id"
                : "Set-EpharmState $plan 'applying'";
            await RunScript(path, "$failed = $false; try { " + write + " } catch { if ($_.Exception.Message -notlike '*reparse point*') { throw }; $failed = $true }; if (-not $failed) { throw 'Dangling reparse point was followed' }");
            Assert.False(Directory.Exists(absent));
            Assert.True((File.GetAttributes(link) & FileAttributes.ReparsePoint) != 0);
            AssertPrevious();
        }
        finally { TestDirectoryLink.Remove(link); }
    }

    [Fact]
    public async Task FailureAfterFirstReplacementRestoresCompletePreviousVersion()
    {
        var plan = Prepare();
        await RunScript(plan, "$script:copied = 0; $failed = $false; try { $null = Invoke-EpharmFileTransaction $plan { return $true } { $script:copied++; if ($script:copied -eq 2) { throw 'injected disk/copy failure' } } } catch { $failed = $true }; if (-not $failed) { throw 'Failure was not injected' }");
        AssertPrevious();
        Assert.True(UpdateTransaction.IsRejected(Install, UpdateTransaction.Hash(Zip)));
    }

    [Fact]
    public async Task LockedDllPreventsCommitAndRestoresAlreadyReplacedFiles()
    {
        var plan = Prepare();
        // Real Windows sharing violation: readable for integrity checks, not deletable/replacable.
        using var locked = new FileStream(Path.Combine(Install, "CustomerDisplay.exe"), FileMode.Open, FileAccess.Read, FileShare.Read);
        await RunScript(plan, "$failed = $false; try { $null = Invoke-EpharmFileTransaction $plan { return $true } } catch { $failed = $true }; if (-not $failed) { throw 'Locked file was not rejected' }");
        AssertPrevious();
    }

    [Theory]
    [InlineData("throw 'injected process start failure'")]
    [InlineData("Start-Sleep -Milliseconds 40; return $false")]
    public async Task StartFailureOrReadinessTimeoutRestoresPreviousVersion(string probe)
    {
        var plan = Prepare();
        await RunScript(plan, "$failed = $false; try { $null = Invoke-EpharmFileTransaction $plan { " + probe + " } } catch { $failed = $true }; if (-not $failed) { throw 'Health failure was not rejected' }");
        AssertPrevious();
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
    }

    [Fact]
    public async Task InterruptedApplyCanBeRecoveredFromDurablePlan()
    {
        var plan = Prepare();
        File.Copy(Path.Combine(Path.GetDirectoryName(plan)!, "candidate", "CustomerDisplay.dll"), Path.Combine(Install, "CustomerDisplay.dll"), true);
        await RunScript(plan, "Restore-EpharmFiles $plan");
        AssertPrevious();
        Assert.False(File.Exists(UpdateTransaction.ActivePath(Install)));
    }

    [Fact]
    public async Task DamagedBackupStopsApplyBeforeAnyTargetIsChanged()
    {
        var plan = Prepare();
        File.WriteAllText(Path.Combine(Path.GetDirectoryName(plan)!, "backup", "CustomerDisplay.dll"), "damaged");
        await RunScript(plan, "throw 'unexpected validation success'", expectedExit: 1, expectedError: "Backup integrity failure");
        AssertPrevious();
    }

    [Fact]
    public async Task TimedOutScriptReleasesWorkingDirectoryBeforeFixtureCleanup()
    {
        Assert.SkipWhen(!OperatingSystem.IsWindows(), "Windows PowerShell/working-directory cleanup requires Windows.");
        var plan = Prepare();
        var error = await Assert.ThrowsAsync<TimeoutException>(() => RunScript(plan, "Start-Sleep -Seconds 30", timeout: TimeSpan.FromMilliseconds(100)));
        Assert.Contains("PowerShell timed out during command", error.Message);
        Assert.Contains("EPHARM_TEST_STAGE=running-command", error.Message);
        AssertPrevious();
        // Windows refuses this while the owned child still holds its working directory.
        Directory.Delete(_root, true);
        Directory.CreateDirectory(_root);
    }

    private async Task RunScript(string plan, string command, int expectedExit = 0, string? expectedError = null, TimeSpan? timeout = null)
    {
        Assert.SkipWhen(!OperatingSystem.IsWindows(), "Windows PowerShell/NTFS transaction tests require Windows.");
        static string Quote(string value) => "'" + value.Replace("'", "''") + "'";
        var program = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; $ErrorActionPreference = 'Stop'; [Console]::Error.WriteLine('EPHARM_TEST_STAGE=started'); try { [Console]::Error.WriteLine('EPHARM_TEST_STAGE=loading-helper'); . " + Quote(Script) + "; [Console]::Error.WriteLine('EPHARM_TEST_STAGE=reading-plan'); $plan = Read-EpharmPlan " + Quote(plan) + "; [Console]::Error.WriteLine('EPHARM_TEST_STAGE=running-command'); " + command + "; [Console]::Error.WriteLine('EPHARM_TEST_STAGE=completed'); exit 0 } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }";
        var start = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = _root };
        start.ArgumentList.Add("-NoProfile"); start.ArgumentList.Add("-NonInteractive");
        start.ArgumentList.Add("-ExecutionPolicy"); start.ArgumentList.Add("Bypass"); start.ArgumentList.Add("-EncodedCommand");
        start.ArgumentList.Add(Convert.ToBase64String(Encoding.Unicode.GetBytes(program)));
        using var process = Process.Start(start)!;
        var commandStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        async Task<string> ReadError()
        {
            var text = new StringBuilder();
            while (await process.StandardError.ReadLineAsync() is { } line)
            {
                text.AppendLine(line);
                // PowerShell can prepend CLIXML progress to the first ordinary stderr line.
                if (line.Contains("EPHARM_TEST_STAGE=running-command", StringComparison.Ordinal)) commandStarted.TrySetResult();
            }
            return text.ToString();
        }
        // Keep drains alive during test cancellation so cleanup can await actual child exit.
        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = ReadError();
        var exited = process.WaitForExitAsync(TestContext.Current.CancellationToken);
        var budget = timeout ?? TimeSpan.FromSeconds(20);
        var phase = "bootstrap";
        var phaseBudget = TimeSpan.FromSeconds(60);
        try
        {
            // Process startup and plan loading are outside the tested command's budget.
            await Task.WhenAny(commandStarted.Task, exited).WaitAsync(phaseBudget, TestContext.Current.CancellationToken);
            if (commandStarted.Task.IsCompleted)
            {
                phase = "command";
                phaseBudget = budget;
                await exited.WaitAsync(budget, TestContext.Current.CancellationToken);
            }
            else await exited; // Validation can intentionally reject the plan before the command.
            phase = "stream-drain";
            phaseBudget = TimeSpan.FromSeconds(5);
            await Task.WhenAll(stdout, stderr).WaitAsync(phaseBudget);
        }
        catch (Exception failure)
        {
            try
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) when (process.HasExited) { }
                // Kill is asynchronous; a cancelled test token must not skip this wait.
                await process.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5));
                await Task.WhenAll(stdout, stderr).WaitAsync(TimeSpan.FromSeconds(5));
            }
            catch (Exception cleanupFailure) { throw new AggregateException("Owned PowerShell cleanup failed.", failure, cleanupFailure); }
            if (failure is TimeoutException)
                throw new TimeoutException($"PowerShell timed out during {phase} after {phaseBudget.TotalSeconds}s (PID {process.Id}). stdout: {await stdout} stderr: {await stderr}", failure);
            throw;
        }
        Assert.True(process.ExitCode == expectedExit, $"PowerShell exit {process.ExitCode}: {await stdout} {await stderr}");
        if (expectedError != null) Assert.Contains(expectedError, await stderr);
    }

    private void AssertPrevious()
    {
        Assert.Equal("previous-executable", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.exe")));
        Assert.Equal("previous-assembly", File.ReadAllText(Path.Combine(Install, "CustomerDisplay.dll")));
        Assert.False(File.Exists(Path.Combine(Install, "NewDependency.dll")));
        AssertStatePreserved();
    }
    private void AssertStatePreserved()
    {
        Assert.Equal("pharmacy-private-config", File.ReadAllText(Path.Combine(Install, "posm.json")));
        Assert.Equal("undelivered-sales", File.ReadAllText(Path.Combine(Install, "outbox.db")));
    }
    public void Dispose() => Directory.Delete(_root, true);
}
