using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using CustomerDisplay.Services;
using QRCoder;

namespace CustomerDisplay;

public partial class TaskKioskWindow : Window
{
    private readonly DispatcherTimer _expiryTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private bool _online = true;
    private string? _renderedUrl;
    private IntPtr _hwnd;

    public IReadOnlyList<TaskKioskItem> CurrentItems { get; private set; } = Array.Empty<TaskKioskItem>();
    public TaskKioskItem? SelectedTask => Tasks.SelectedItem as TaskKioskItem;
    public event Action<TaskKioskItem>? TaskDisplayed;

    public TaskKioskWindow(System.Windows.Forms.Screen screen)
    {
        InitializeComponent();
        SourceInitialized += (_, _) =>
        {
            _hwnd = new WindowInteropHelper(this).Handle;
            var style = Native.GetWindowLongPtr(_hwnd, -20).ToInt64();
            Native.SetWindowLongPtr(_hwnd, -20, new IntPtr(style | 0x08000000L | 0x00000080L));
            var dpi = VisualTreeHelper.GetDpi(this);
            MaxHeight = Math.Max(320, screen.WorkingArea.Height / dpi.DpiScaleY - 24);
            MaxWidth = Math.Max(320, screen.WorkingArea.Width / dpi.DpiScaleX - 24);
            Left = screen.WorkingArea.Left / dpi.DpiScaleX + 12;
            Top = screen.WorkingArea.Top / dpi.DpiScaleY + 12;
        };
        ContentRendered += (_, _) => AnnounceDisplayed();
        StateChanged += (_, _) =>
        {
            if (WindowState == WindowState.Normal) AnnounceDisplayed();
        };
        _expiryTimer.Tick += (_, _) =>
        {
            ElevateWithoutActivation();
            if (SelectedTask is { } task && task.ExpiresAt <= DateTimeOffset.UtcNow)
                RenderSelected();
        };
        _expiryTimer.Start();
        Closed += (_, _) => _expiryTimer.Stop();
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape && !Tasks.IsDropDownOpen) Close();
        };
    }

    public void UpdateTasks(IReadOnlyList<TaskKioskItem> items)
    {
        var selected = SelectedTask?.Id;
        CurrentItems = items;
        _online = true;
        Tasks.ItemsSource = items;
        Tasks.SelectedItem = items.FirstOrDefault(item => item.Id == selected) ?? items.FirstOrDefault();
        RenderSelected();
    }

    public void SetOffline()
    {
        _online = false;
        ClearQr();
        Headline.Text = "Нет связи с CRM";
        HelpText.Text = "Касса продолжает работать.";
        ConnectionText.Text = "QR появится после восстановления связи. Задание не завершено.";
        ConnectionText.Visibility = Visibility.Visible;
    }

    private void TaskSelectionChanged(object sender, SelectionChangedEventArgs e) => RenderSelected();
    private void LaterClick(object sender, RoutedEventArgs e) => Close();
    // Minimize to tray uses the same bounded per-delivery reminder as Later. A new delivery opens
    // a fresh non-activating window instead of silently updating an indefinitely minimized window.
    private void MinimizeClick(object sender, RoutedEventArgs e) => Close();

    private void TitleBarDrag(object sender, MouseButtonEventArgs e)
    {
        for (var node = e.OriginalSource as DependencyObject;
             node != null;
             node = VisualTreeHelper.GetParent(node))
        {
            if (node is System.Windows.Controls.Primitives.ButtonBase) return;
        }

        if (e.LeftButton != MouseButtonState.Pressed) return;
        try
        {
            DragMove();
        }
        catch (InvalidOperationException)
        {
            // The mouse was released between the event and DragMove.
        }
    }

    private void ClearQr()
    {
        QrImage.Source = null;
        _renderedUrl = null;
        QrFrame.Visibility = Visibility.Collapsed;
    }

    private void RenderSelected()
    {
        if (!_online || QrFrame == null) return;
        ConnectionText.Visibility = Visibility.Collapsed;
        if (SelectedTask is not { } task)
        {
            ClearQr();
            TaskDetails.Visibility = Visibility.Collapsed;
            Headline.Text = "Нет новых заданий";
            HelpText.Text = "Новые задания появятся здесь автоматически.";
            return;
        }

        TaskDetails.Visibility = Visibility.Visible;
        BranchText.Text = task.BranchName;
        Headline.Text = "Сканируйте и выполните";
        HelpText.Text = "Задание откроется на телефоне.";
        CountText.Text = TaskCount(CurrentItems.Count);
        PriorityText.Text = task.Priority switch
        {
            "urgent" => "Срочное задание",
            "high" => "Высокий приоритет",
            "low" => "Низкий приоритет",
            _ => "Обычный приоритет",
        };
        PriorityDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString(
            task.Priority == "urgent" ? "#FF837A" : task.Priority == "high" ? "#FFB020" : "#65DEAD"));
        DueText.Text = task.DueAt is DateTimeOffset due
            ? "До " + due.ToLocalTime().ToString("d MMMM, HH:mm", CultureInfo.GetCultureInfo("ru-RU"))
            : "Без срока";

        if (task.ExpiresAt <= DateTimeOffset.UtcNow)
        {
            ClearQr();
            HelpText.Text = "Обновляем QR-код…";
            return;
        }

        QrFrame.Visibility = Visibility.Visible;
        if (_renderedUrl != task.QrUrl)
        {
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(task.QrUrl, QRCodeGenerator.ECCLevel.Q);
            using var png = new PngByteQRCode(data);
            using var stream = new MemoryStream(png.GetGraphic(8));
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = stream;
            bitmap.EndInit();
            bitmap.Freeze();
            QrImage.Source = bitmap;
            _renderedUrl = task.QrUrl;
        }
        if (new Uri(task.QrUrl).IsLoopback)
            HelpText.Text = "Локальная проверка · ссылка для этого ПК.";
        AnnounceDisplayed();
    }

    private void AnnounceDisplayed()
    {
        if (_online &&
            IsVisible &&
            WindowState == WindowState.Normal &&
            QrImage.Source != null &&
            SelectedTask is { } task &&
            task.ExpiresAt > DateTimeOffset.UtcNow)
        {
            if (ElevateWithoutActivation()) TaskDisplayed?.Invoke(task);
        }
    }

    private bool ElevateWithoutActivation()
    {
        if (_hwnd == IntPtr.Zero || !IsVisible || WindowState != WindowState.Normal) return false;
        return Native.SetWindowPos(_hwnd, new IntPtr(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010 | 0x0040)
            && Native.IsWindowVisible(_hwnd) && !Native.IsIconic(_hwnd)
            && Native.GetWindowRect(_hwnd, out var rect) && rect.Right > rect.Left && rect.Bottom > rect.Top;
    }

    private static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        internal struct Rect { internal int Left, Top, Right, Bottom; }
        [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
        internal static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
        [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
        internal static extern IntPtr SetWindowLongPtr(IntPtr hwnd, int index, IntPtr style);
        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool SetWindowPos(IntPtr hwnd, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    }

    private static string TaskCount(int count)
    {
        var remainder = count % 100;
        var noun = remainder is >= 11 and <= 14
            ? "заданий"
            : (count % 10) switch
            {
                1 => "задание",
                2 or 3 or 4 => "задания",
                _ => "заданий",
            };
        return $"{count} {noun}";
    }
}
