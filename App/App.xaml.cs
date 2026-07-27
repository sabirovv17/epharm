using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Windows;
using CustomerDisplay.Config;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// Interaction logic for App.xaml.
/// Single-instance guard: касса должна быть «всегда запущена» (автостарт при входе + ручной
/// запуск + перезапуск апдейтером). Именованный мьютекс не даёт открыться второй копии на том же
/// экране. Если копия уже есть — новый процесс тихо выходит.
/// </summary>
public partial class App : Application
{
    private static Mutex? _instanceMutex;
    private const string MutexName = "Local\\EpharmCustomerDisplay";

    protected override void OnStartup(StartupEventArgs e)
    {
        // Ставим crash guard до всего остального: если проблема случится на раннем старте,
        // разработчик увидит её в C:\Epharm\crash.log, а не "приложение раз через раз не открылось".
        CrashGuard.Install(@"C:\Epharm\crash.log");

        if (e.Args.Any(arg => string.Equals(arg, "--diagnose-standardn", StringComparison.OrdinalIgnoreCase)))
        {
            RunStandardNDiagnostics();
            Shutdown();
            return;
        }

        try
        {
            _instanceMutex = new Mutex(initiallyOwned: true, MutexName, out var createdNew);
            if (!createdNew)
            {
                CrashGuard.Write("Startup skipped: another Epharm POSM instance is already running.");
                // Уже запущено — выходим без окна (не плодим копии киоска).
                Shutdown();
                return;
            }
        }
        catch (Exception ex)
        {
            CrashGuard.Write($"Startup mutex error: {ex.Message}. Continuing without single-instance guard.");
        }

        base.OnStartup(e);
    }

    private static void RunStandardNDiagnostics()
    {
        const string outputPath = @"C:\Epharm\standardn-identity-diagnostics.txt";
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
            var cfg = EpharmConfig.Load();
            using var lookup = new StandardNDbLookup(cfg, _ => { });
            var bootstrap = StandardNLogLocator.BootstrapCandidates();
            var discovered = StandardNLogLocator.DiscoverExisting();
            var logDiagnostics = new StringBuilder()
                .AppendLine("STANDARD-N CASH LOG DISCOVERY:")
                .AppendLine("  Bootstrap candidates:");
            foreach (var path in bootstrap) logDiagnostics.AppendLine("    " + path);
            logDiagnostics.AppendLine("  Existing discovered logs:");
            if (discovered.Count == 0) logDiagnostics.AppendLine("    none");
            foreach (var path in discovered) logDiagnostics.AppendLine("    " + path);
            logDiagnostics.AppendLine();
            File.WriteAllText(
                outputPath,
                logDiagnostics + lookup.BuildIdentityDiagnostics(),
                new UTF8Encoding(false));
        }
        catch (Exception ex)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
            File.WriteAllText(outputPath, ex.ToString(), new UTF8Encoding(false));
        }

        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{outputPath}\"")
            {
                UseShellExecute = true,
            });
        }
        catch { }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { _instanceMutex?.ReleaseMutex(); } catch { /* ignore */ }
        _instanceMutex?.Dispose();
        base.OnExit(e);
    }
}
