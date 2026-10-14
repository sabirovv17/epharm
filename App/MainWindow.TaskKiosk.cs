using System;
using System.Collections.Generic;
using System.Linq;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using CustomerDisplay.Services;

namespace CustomerDisplay;

public partial class MainWindow
{
    private TaskKioskClient? _taskKiosk;
    private TaskKioskWindow? _taskKioskWindow;
    private System.Windows.Forms.NotifyIcon? _taskKioskTray;
    private DispatcherTimer? _taskKioskTimer;
    private readonly CancellationTokenSource _taskKioskStop = new();
    private readonly TaskKioskSnooze _taskKioskSnoozed = new();
    private MerchShownOutbox? _merchShownOutbox;
    private DispatcherTimer? _merchShownRetryTimer;
    private List<TaskKioskItem> _taskKioskItems = [];
    private bool _taskKioskPolling;
    private bool _taskKioskOnline;
    private bool _taskKioskHadFailure;
    private readonly HashSet<string> _taskKioskAcknowledged = [];
    private readonly HashSet<string> _taskKioskAcknowledging = [];
    private readonly TaskKioskPollSchedule _taskKioskPollSchedule = new();

    private void StartTaskKiosk()
    {
        var config = _posmConfig;
        var pharmacyId = config?.PharmacyId;
        if (_epharm == null || config == null || string.IsNullOrWhiteSpace(pharmacyId)) return;

        try
        {
            _taskKiosk = new TaskKioskClient(_epharm, pharmacyId, config.ResolveDeviceId());
            var deviceId = config.ResolveDeviceId();
            var scope = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(pharmacyId + "\n" + deviceId)));
            _merchShownOutbox = new MerchShownOutbox(Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Epharm",
                "merch-shown-" + scope + ".dpapi"), pharmacyId, deviceId, log: Log);
            _merchShownRetryTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
            _merchShownRetryTimer.Tick += async (_, _) => await FlushMerchShownOutbox();
            _merchShownRetryTimer.Start();
            _ = FlushMerchShownOutbox();
            _taskKioskTray = new System.Windows.Forms.NotifyIcon
            {
                Icon = System.Drawing.SystemIcons.Information,
                Text = "Задания аптеки",
                Visible = true,
            };
            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("Задания аптеки", null, (_, _) => OpenTaskKiosk(activate: true));
            _taskKioskTray.ContextMenuStrip = menu;
            _taskKioskTray.DoubleClick += (_, _) => OpenTaskKiosk(activate: true);

            _taskKioskTimer = new DispatcherTimer { Interval = _taskKioskPollSchedule.Initial };
            _taskKioskTimer.Tick += async (_, _) => await PollTaskKiosk();
            _taskKioskTimer.Start();
            _ = PollTaskKiosk();
        }
        catch (Exception ex)
        {
            Log($"Задания аптек: не удалось запустить фоновый опрос ({ex.Message}).");
        }
    }

    private void OpenTaskKiosk(bool activate = false)
    {
        if (_taskKioskStop.IsCancellationRequested || _taskKiosk == null) return;
        if (_taskKioskWindow == null)
        {
            var window = new TaskKioskWindow(PharmacistScreen());
            window.TaskDisplayed += task => _ = AcknowledgeTaskKiosk(task);
            _taskKioskWindow = window;
            window.UpdateTasks(_taskKioskItems);
            if (!_taskKioskOnline) window.SetOffline();
            window.Closed += (_, _) =>
            {
                _taskKioskSnoozed.Defer(window.CurrentItems, DateTimeOffset.UtcNow);
                _taskKioskWindow = null;
            };
            window.Show();
        }

        if (activate)
        {
            _taskKioskWindow.WindowState = System.Windows.WindowState.Normal;
            _taskKioskWindow.Activate();
        }
    }

    private async Task PollTaskKiosk()
    {
        if (_taskKioskPolling || _taskKiosk == null || _taskKioskStop.IsCancellationRequested) return;
        _taskKioskPolling = true;
        try
        {
            var items = await _taskKiosk.ListAsync(_taskKioskStop.Token);
            if (_taskKioskStop.IsCancellationRequested) return;

            _taskKioskItems = items;
            _taskKioskOnline = true;
            if (_taskKioskTimer != null)
                _taskKioskTimer.Interval = _taskKioskPollSchedule.RecordSuccess();
            if (_taskKioskHadFailure)
            {
                Log("Задания аптек: связь восстановлена.");
                _taskKioskHadFailure = false;
            }
            var liveLinks = items.Select(item => item.LinkId ?? item.NotificationKey).ToHashSet();
            _taskKioskAcknowledged.RemoveWhere(key => !liveLinks.Contains(key));

            if (_taskKioskTray != null) _taskKioskTray.Text = $"Задания аптеки: {items.Count}";
            _taskKioskWindow?.UpdateTasks(items);
            if (_taskKioskWindow == null && _taskKioskSnoozed.ShouldShow(items, DateTimeOffset.UtcNow))
            {
                OpenTaskKiosk();
            }
        }
        catch (OperationCanceledException) when (_taskKioskStop.IsCancellationRequested)
        {
            // Normal application shutdown.
        }
        catch (Exception ex)
        {
            if (_taskKioskStop.IsCancellationRequested) return;
            _taskKioskOnline = false;
            _taskKioskHadFailure = true;
            var retryAfter = _taskKioskPollSchedule.RecordFailure();
            if (_taskKioskTimer != null) _taskKioskTimer.Interval = retryAfter;
            _taskKioskWindow?.SetOffline();
            if (_taskKioskTray != null) _taskKioskTray.Text = "Задания аптеки — нет связи с CRM";
            Log($"Задания аптек: временно недоступны ({ex.Message}); повтор через {retryAfter.TotalSeconds:0} с.");
        }
        finally
        {
            _taskKioskPolling = false;
        }
    }

    private void StopTaskKiosk()
    {
        if (_taskKioskStop.IsCancellationRequested) return;
        _taskKioskStop.Cancel();
        _taskKioskTimer?.Stop();
        _merchShownRetryTimer?.Stop();
        _taskKioskWindow?.Close();
        _taskKioskTray?.Dispose();
        _taskKiosk?.Dispose();
    }

    private async Task AcknowledgeTaskKiosk(TaskKioskItem task)
    {
        var key = task.LinkId ?? task.NotificationKey;
        if (_taskKiosk == null ||
            _taskKioskStop.IsCancellationRequested ||
            _taskKioskAcknowledged.Contains(key) ||
            !_taskKioskAcknowledging.Add(key))
        {
            return;
        }

        try
        {
            _merchShownOutbox?.Enqueue(task);
            await FlushMerchShownOutbox();
        }
        catch (OperationCanceledException) when (_taskKioskStop.IsCancellationRequested)
        {
            // Normal application shutdown.
        }
        catch (Exception ex)
        {
            // Never log the task URL or bearer token. A visible QR will retry enqueue on the next poll.
            Log($"Задания аптек: подтверждение показа пока не сохранено ({ex.GetType().Name}).");
        }
        finally
        {
            _taskKioskAcknowledging.Remove(key);
        }
    }

    private async Task FlushMerchShownOutbox()
    {
        if (_merchShownOutbox == null || _epharm == null || _taskKioskStop.IsCancellationRequested) return;
        try
        {
            await _merchShownOutbox.FlushAsync(_epharm,
                token => Dispatcher.BeginInvoke(new Action(() => _taskKioskAcknowledged.Add(token))),
                _taskKioskStop.Token);
        }
        catch (OperationCanceledException) when (_taskKioskStop.IsCancellationRequested) { }
        catch (Exception ex)
        {
            Log($"Задания аптек: очередь подтверждений временно недоступна ({ex.GetType().Name}).");
        }
    }
}
