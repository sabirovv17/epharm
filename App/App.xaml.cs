using System;
using System.Threading;
using System.Windows;
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

    protected override void OnExit(ExitEventArgs e)
    {
        try { _instanceMutex?.ReleaseMutex(); } catch { /* ignore */ }
        _instanceMutex?.Dispose();
        base.OnExit(e);
    }
}
