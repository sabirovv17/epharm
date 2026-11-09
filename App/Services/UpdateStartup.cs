using System;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows.Threading;

namespace CustomerDisplay.Services;

/// <summary>Recovery gate runs before ordinary app startup. Readiness is local UI health, not backend/SMS proof.</summary>
public static class UpdateStartup
{
    private static UpdatePlan? _candidate;
    private static DispatcherTimer? _readinessTimer;
    private static bool _initializationCompleted;

    public static void MarkInitializationComplete() => _initializationCompleted = true;

    public static bool MayStart(string[] arguments, Action<string> log)
    {
        try
        {
            var plan = UpdateTransaction.ReadActive(AppContext.BaseDirectory);
            if (plan == null) return true;
            var transaction = Path.Combine(UpdateTransaction.ControlRoot(plan.InstallRoot), plan.Id);
            var statePath = Path.Combine(transaction, "state.json");
            var helperAlive = false;
            var awaitingHealth = false;
            if (File.Exists(statePath))
            {
                UpdateTransaction.EnsurePhysical(statePath);
                using var state = JsonDocument.Parse(File.ReadAllText(statePath));
                awaitingHealth = state.RootElement.GetProperty("Status").GetString() == "awaiting-health";
                try
                {
                    using var helper = Process.GetProcessById(state.RootElement.GetProperty("ProcessId").GetInt32());
                    helperAlive = helper.StartTime.ToUniversalTime().Ticks == state.RootElement.GetProperty("ProcessStartTicks").GetInt64();
                }
                catch (ArgumentException) { }
            }
            if (arguments.Length == 2 && arguments[0] == "--update-candidate" && arguments[1] == plan.Token && helperAlive && awaitingHealth)
            {
                _candidate = plan;
                return true;
            }
            if (!helperAlive)
            {
                // Recreate the helper from this installed assembly, not a remotely supplied script.
                UpdateTransaction.WriteApplierScript(transaction, ReadApplierScript());
                LaunchApplier(Path.Combine(transaction, "plan.json"), recovery: true);
                log("update: восстанавливается незавершённое обновление; обычный запуск отложен");
            }
            return false;
        }
        catch (Exception ex)
        {
            log("update: небезопасное или повреждённое состояние обновления, запуск остановлен: " + ex.Message);
            return false;
        }
    }

    public static void BeginReadinessProbe(Dispatcher dispatcher, Func<bool> localReady, Action<string> log)
    {
        if (_candidate == null || _readinessTimer != null) return;
        var ticks = 0;
        var started = Stopwatch.StartNew();
        _readinessTimer = new DispatcherTimer(DispatcherPriority.Background, dispatcher) { Interval = TimeSpan.FromSeconds(5) };
        _readinessTimer.Tick += (_, _) =>
        {
            if (!_initializationCompleted || !localReady() || dispatcher.HasShutdownStarted || dispatcher.HasShutdownFinished) { ticks = 0; return; }
            if (++ticks < 3 || started.Elapsed < TimeSpan.FromSeconds(14)) return;
            try
            {
                var plan = _candidate!;
                using var process = Process.GetCurrentProcess();
                UpdateTransaction.WriteJson(Path.Combine(UpdateTransaction.ControlRoot(plan.InstallRoot), plan.Id, "ready.json"),
                    new { plan.Token, ProcessId = process.Id, ProcessStartTicks = process.StartTime.ToUniversalTime().Ticks, UiTicks = ticks });
                log("update: новый процесс подтвердил локальную готовность и 3 прохода UI-диспетчера");
                _readinessTimer!.Stop();
            }
            catch (Exception ex) { log("update: не удалось подтвердить готовность: " + ex.Message); }
        };
        _readinessTimer.Start();
    }

    public static string ReadApplierScript()
    {
        using var stream = typeof(UpdateStartup).Assembly.GetManifestResourceStream("CustomerDisplay.apply-update.ps1")
            ?? throw new InvalidOperationException("Missing embedded update recovery helper.");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }

    public static void LaunchApplier(string planPath, bool recovery)
    {
        var start = new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe"),
            UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
            WorkingDirectory = Path.GetDirectoryName(planPath)!,
        };
        foreach (var arg in new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                     Path.Combine(Path.GetDirectoryName(planPath)!, "apply-update.ps1"), "-PlanPath", planPath }) start.ArgumentList.Add(arg);
        if (recovery) start.ArgumentList.Add("-Recovery");
        using var process = Process.Start(start) ?? throw new IOException("Update helper did not start.");
    }
}
