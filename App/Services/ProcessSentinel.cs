using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Глобальная защита процесса от падений. Касса должна слушать лог Стандарт-Н «всегда»,
    /// поэтому мягкие (восстановимые) исключения НЕ должны ронять приложение:
    ///  - UI-исключения (Dispatcher) — логируем и гасим (Handled=true), окно живёт дальше;
    ///  - необработанные исключения фоновых Task — логируем и помечаем observed;
    ///  - фатальные (AppDomain) — логируем; процесс всё равно завершится, но его поднимет
    ///    restart-on-failure задачи планировщика + внешний watchdog (см. scripts/watchdog.ps1).
    ///
    /// Это первый из двух уровней живучести (второй — внешний watchdog по heartbeat).
    /// </summary>
    public static class CrashGuard
    {
        private static string _logPath = @"C:\Epharm\crash.log";
        private static readonly object _lock = new();

        public static void Install(string logPath)
        {
            if (!string.IsNullOrWhiteSpace(logPath)) _logPath = logPath;

            // UI-поток: НЕ роняем кассу из-за восстановимой ошибки рендера/обработчика.
            if (Application.Current != null)
                Application.Current.DispatcherUnhandledException += OnDispatcherException;

            // Необработанные исключения фоновых Task (TailLogLoop, поллинги) — не финализируем процесс.
            TaskScheduler.UnobservedTaskException += (_, e) =>
            {
                Write($"UnobservedTaskException: {e.Exception?.GetBaseException().Message}");
                e.SetObserved();
            };

            // Фатальные — процесс завершится; логируем для разбора. Поднимет watchdog/scheduler.
            AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            {
                var ex = e.ExceptionObject as Exception;
                Write($"FATAL UnhandledException (terminating={e.IsTerminating}): {ex?.Message}\n{ex?.StackTrace}");
            };
        }

        private static void OnDispatcherException(object? sender, DispatcherUnhandledExceptionEventArgs e)
        {
            Write($"DispatcherUnhandledException: {e.Exception.Message}\n{e.Exception.StackTrace}");
            // Гасим — касса продолжает работать и слушать лог. Если ошибка реально фатальна
            // (повредила состояние) и повторится — её поймает watchdog по зависшему heartbeat.
            e.Handled = true;
        }

        public static void Write(string msg)
        {
            try
            {
                lock (_lock)
                {
                    var dir = Path.GetDirectoryName(_logPath);
                    if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                    File.AppendAllText(_logPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {msg}{Environment.NewLine}");
                }
            }
            catch { /* лог не должен ронять кассу */ }
        }
    }

    /// <summary>
    /// Heartbeat живости главного UI-потока. Пишет метку времени в файл с заданным периодом
    /// через DispatcherTimer — значит, файл «свежий» ровно тогда, когда UI-поток жив и не завис.
    /// Внешний watchdog (scripts/watchdog.ps1) сравнивает возраст файла с порогом и перезапускает
    /// кассу, если процесс упал ИЛИ завис (deadlock UI). Это второй уровень живучести.
    /// </summary>
    public sealed class Heartbeat
    {
        private readonly string _path;
        private readonly DispatcherTimer _timer;
        private readonly Action<string>? _log;

        public Heartbeat(string path, int periodSec, Action<string>? log = null)
        {
            _path = path;
            _log = log;
            _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(Math.Max(5, periodSec)) };
            _timer.Tick += (_, __) => Beat();
        }

        public void Start()
        {
            try
            {
                var dir = Path.GetDirectoryName(_path);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            }
            catch { /* ignore */ }
            Beat();            // сразу пишем первую метку
            _timer.Start();
            _log?.Invoke($"heartbeat запущен: {_path} каждые {_timer.Interval.TotalSeconds:0}с");
        }

        public void Stop() => _timer.Stop();

        private void Beat()
        {
            try { File.WriteAllText(_path, DateTimeOffset.Now.ToUnixTimeSeconds().ToString()); }
            catch { /* недоступность файла не должна ронять кассу */ }
        }
    }
}
