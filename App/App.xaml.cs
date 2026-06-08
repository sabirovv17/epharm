using System.Threading;
using System.Windows;

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
    private const string MutexName = "Global\\EpharmCustomerDisplay";

    protected override void OnStartup(StartupEventArgs e)
    {
        _instanceMutex = new Mutex(initiallyOwned: true, MutexName, out var createdNew);
        if (!createdNew)
        {
            // Уже запущено — выходим без окна (не плодим копии киоска).
            Shutdown();
            return;
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
