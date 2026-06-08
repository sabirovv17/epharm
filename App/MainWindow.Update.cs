using System;
using System.Threading.Tasks;
using System.Windows.Threading;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// Авто-обновление клиента (Windows). Периодически проверяет релиз в админке и сам
    /// обновляется. Fail-safe: ошибки апдейта не влияют на работу кассы.
    /// </summary>
    public partial class MainWindow
    {
        private AppUpdater? _appUpdater;
        private DispatcherTimer? _updateTimer;

        /// <summary>
        /// Запускает проверки обновлений: первая — через короткую паузу (чтобы касса успела
        /// показать экран), далее периодически (UpdatePollSec). Работает только при включённой
        /// POSM-интеграции и UpdateEnabled.
        /// </summary>
        private void StartUpdateChecks()
        {
            if (_epharm == null || _posmConfig == null) return;
            if (!_posmConfig.UpdateEnabled) { Log("Авто-обновление выключено (UpdateEnabled=false)"); return; }
            var sec = _posmConfig.UpdatePollSec;
            if (sec <= 0) { Log("Авто-обновление выключено (UpdatePollSec<=0)"); return; }

            _appUpdater = new AppUpdater(_posmConfig, _epharm, Log);
            Log($"Авто-обновление включено: текущая версия {AppUpdater.CurrentVersion()}, проверка каждые {sec}с");

            // Стартовая проверка с задержкой ~15с — не мешаем первичной отрисовке.
            _ = Task.Run(async () =>
            {
                await Task.Delay(TimeSpan.FromSeconds(15)).ConfigureAwait(false);
                await SafeCheckAsync().ConfigureAwait(false);
            });

            _updateTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(sec) };
            _updateTimer.Tick += async (_, __) => await SafeCheckAsync();
            _updateTimer.Start();
        }

        private async Task SafeCheckAsync()
        {
            try
            {
                if (_appUpdater == null) return;
                await _appUpdater.CheckAndApplyAsync().ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log($"update check error (fail-safe): {ex.Message}");
            }
        }
    }
}
