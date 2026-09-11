using System;
using System.Collections.Generic;
using System.Linq;
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
    private readonly Dictionary<string, DateTimeOffset> _taskKioskSnoozed = new();
    private List<TaskKioskItem> _taskKioskItems = [];
    private bool _taskKioskPolling;
    private bool _taskKioskOnline;
    private readonly HashSet<string> _taskKioskAcknowledged = [];
    private readonly HashSet<string> _taskKioskAcknowledging = [];

    private void StartTaskKiosk()
    {
        var pharmacyId = _posmConfig?.PharmacyId;
        if (_epharm == null || string.IsNullOrWhiteSpace(pharmacyId)) return;

        try
        {
            _taskKiosk = new TaskKioskClient(_epharm, pharmacyId, Environment.MachineName);
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

            _taskKioskTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
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
                foreach (var item in window.CurrentItems)
                    _taskKioskSnoozed[item.NotificationKey] = DateTimeOffset.UtcNow.AddMinutes(10);
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
            var liveLinks = items.Select(item => item.LinkId ?? item.NotificationKey).ToHashSet();
            _taskKioskAcknowledged.RemoveWhere(key => !liveLinks.Contains(key));
            var keys = items.Select(item => item.NotificationKey).ToHashSet();
            foreach (var key in _taskKioskSnoozed.Keys.Where(key => !keys.Contains(key)).ToArray())
                _taskKioskSnoozed.Remove(key);

            if (_taskKioskTray != null) _taskKioskTray.Text = $"Задания аптеки: {items.Count}";
            _taskKioskWindow?.UpdateTasks(items);
            if (_taskKioskWindow == null && items.Any(item =>
                    !_taskKioskSnoozed.TryGetValue(item.NotificationKey, out var until) ||
                    until <= DateTimeOffset.UtcNow))
            {
                OpenTaskKiosk();
            }
        }
        catch (OperationCanceledException) when (_taskKioskStop.IsCancellationRequested)
        {
            // Normal application shutdown.
        }
        catch
        {
            if (_taskKioskStop.IsCancellationRequested) return;
            _taskKioskOnline = false;
            _taskKioskWindow?.SetOffline();
            if (_taskKioskTray != null) _taskKioskTray.Text = "Задания аптеки — нет связи с CRM";
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
            if (await _taskKiosk.AcknowledgeAsync(task, _taskKioskStop.Token))
                _taskKioskAcknowledged.Add(key);
        }
        catch (OperationCanceledException) when (_taskKioskStop.IsCancellationRequested)
        {
            // Normal application shutdown.
        }
        catch
        {
            // Retry after the next successful poll while this QR is actually visible.
        }
        finally
        {
            _taskKioskAcknowledging.Remove(key);
        }
    }
}
