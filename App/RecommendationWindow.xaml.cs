using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// Compact informational popup over Standard-N. Replacement and cross-sell suggestions are
    /// shown together as mouse-scrollable lists (up to five rows per kind); there are no tabs or
    /// accept/reject controls because the actual receipt remains the source of truth.
    /// </summary>
    public partial class RecommendationWindow : Window
    {
        private readonly List<Recommendation> _recs;

        public Recommendation Current => _recs[0];
        public Recommendation Recommendation => _recs[0];
        public IReadOnlyList<Recommendation> Recommendations => _recs;

        private readonly DispatcherTimer? _autoClose;
        private readonly System.Windows.Forms.Screen? _targetScreen;
        private readonly DispatcherTimer _topmostGuard = new() { Interval = TimeSpan.FromSeconds(1) };
        private IntPtr _hwnd;
        private bool _contentRendered;
        private bool _displayAnnounced;

        /// <summary>
        /// Fired only after the popup is rendered, made topmost through Win32 and verified to be
        /// fully inside the pharmacist monitor. Consumers may safely use this as displayed_at.
        /// </summary>
        public event Action<string>? Displayed;

        public RecommendationWindow(
            Recommendation rec,
            int autoCloseSec = 30,
            System.Windows.Forms.Screen? targetScreen = null)
            : this(new List<Recommendation> { rec }, autoCloseSec, targetScreen)
        {
        }

        /// <param name="recs">Up to five replacements and five cross-sell suggestions.</param>
        /// <param name="targetScreen">Pharmacist monitor (never the customer display).</param>
        public RecommendationWindow(
            List<Recommendation> recs,
            int autoCloseSec = 30,
            System.Windows.Forms.Screen? targetScreen = null)
        {
            InitializeComponent();

            var input = recs ?? new List<Recommendation>();
            _recs = input
                .Where(rec => rec != null && rec.IsSubstitution)
                .Take(RecommendationPopupModelBuilder.MaxPerKind)
                .Concat(input
                    .Where(rec => rec != null && !rec.IsSubstitution)
                    .Take(RecommendationPopupModelBuilder.MaxPerKind))
                .ToList();
            if (_recs.Count == 0) _recs.Add(new Recommendation());

            _targetScreen = targetScreen;
            Bind(RecommendationPopupModelBuilder.Build(_recs));

            if (autoCloseSec > 0)
            {
                _autoClose = new DispatcherTimer { Interval = TimeSpan.FromSeconds(autoCloseSec) };
                _autoClose.Tick += (_, _) => Close();
                _autoClose.Start();
            }

            SourceInitialized += OnSourceInitialized;
            Loaded += OnLoaded;
            ContentRendered += OnContentRendered;
            SizeChanged += OnSizeChanged;
            Closed += OnClosed;
            _topmostGuard.Tick += OnTopmostGuardTick;
        }

        private void Bind(RecommendationPopupModel model)
        {
            TbHeaderCount.Text = model.TotalCount == 1
                ? "1 предложение · цена в тенге"
                : $"{model.TotalCount} предложений · цены в тенге";

            BindSection(
                model.Substitutions,
                PanelSubstitutions,
                SubstitutionTrigger,
                TbSubstitutionTrigger,
                TbSubstitutionCount,
                SubstitutionList);
            BindSection(
                model.CrossSells,
                PanelCrossSells,
                CrossSellTrigger,
                TbCrossSellTrigger,
                TbCrossSellCount,
                CrossSellList);
        }

        private static void BindSection(
            RecommendationPopupSection section,
            FrameworkElement panel,
            FrameworkElement triggerPanel,
            System.Windows.Controls.TextBlock triggerText,
            System.Windows.Controls.TextBlock countText,
            System.Windows.Controls.ItemsControl list)
        {
            panel.Visibility = section.Rows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            triggerPanel.Visibility = section.HasSharedTrigger ? Visibility.Visible : Visibility.Collapsed;
            triggerText.Text = section.TriggerText;
            countText.Text = $"{section.Rows.Count}/5";
            list.ItemsSource = section.Rows;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            try
            {
                ApplyTargetScreenConstraints();
                UpdateLayout();
                PositionAndElevate();
            }
            catch
            {
                // The cash application must keep running. The guard started below retries native
                // placement after the first render even if this initial attempt failed.
            }
            finally
            {
                // Never leave the XAML's startup opacity at zero: native visibility alone is not
                // proof that a pharmacist can see the card.
                Opacity = 1;
                _topmostGuard.Start();
            }
        }

        private void OnSourceInitialized(object? sender, EventArgs e)
        {
            _hwnd = new WindowInteropHelper(this).Handle;
            if (_hwnd == IntPtr.Zero) return;

            // ShowActivated=false covers normal WPF activation. WS_EX_NOACTIVATE also prevents the
            // native topmost reassertion from taking keyboard/scanner focus away from Standard-N.
            var style = NativeMethods.GetWindowLongPtr(_hwnd, NativeMethods.GwlExStyle);
            NativeMethods.SetWindowLongPtr(
                _hwnd,
                NativeMethods.GwlExStyle,
                new IntPtr(style.ToInt64() | NativeMethods.WsExNoActivate | NativeMethods.WsExToolWindow));
            ApplyTargetScreenConstraints();
        }

        private void OnContentRendered(object? sender, EventArgs e)
        {
            _contentRendered = true;
            Dispatcher.BeginInvoke(DispatcherPriority.Render, new Action(TryAnnounceDisplayed));
        }

        private void OnSizeChanged(object sender, SizeChangedEventArgs e)
        {
            if (!_contentRendered || !e.HeightChanged) return;
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => PositionAndElevate()));
        }

        private void OnTopmostGuardTick(object? sender, EventArgs e)
        {
            if (!_contentRendered) return;
            if (_displayAnnounced)
                ReassertTopmost();
            else
                TryAnnounceDisplayed();
        }

        private void OnClosed(object? sender, EventArgs e)
        {
            _topmostGuard.Stop();
            _autoClose?.Stop();
        }

        private void ApplyTargetScreenConstraints()
        {
            var screen = TargetScreen();
            var dpi = TargetDpiScale(screen);
            var area = screen.WorkingArea;
            var constraints = PopupWindowPlacement.ConstrainDipSize(
                new PixelRect(area.Left, area.Top, area.Width, area.Height),
                dpi.X,
                dpi.Y,
                desiredWidthDip: 392,
                desiredMaxHeightDip: 700);
            Width = constraints.Width;
            MaxHeight = constraints.MaxHeight;
        }

        private void TryAnnounceDisplayed()
        {
            if (_displayAnnounced || !PositionAndElevate(out var diagnostics)) return;
            _displayAnnounced = true;
            Displayed?.Invoke(diagnostics);
        }

        private bool PositionAndElevate() => PositionAndElevate(out _);

        private bool PositionAndElevate(out string diagnostics)
        {
            diagnostics = "native window handle is unavailable";
            if (_hwnd == IntPtr.Zero || !IsVisible) return false;

            var screen = TargetScreen();
            var area = screen.WorkingArea;
            if (!NativeMethods.GetWindowRect(_hwnd, out var before))
            {
                diagnostics = $"GetWindowRect failed: {Marshal.GetLastWin32Error()}";
                return false;
            }

            var point = PopupWindowPlacement.BottomRight(
                new PixelRect(area.Left, area.Top, area.Width, area.Height),
                before.Width,
                before.Height);
            if (!NativeMethods.SetWindowPos(
                    _hwnd,
                    NativeMethods.HwndTopmost,
                    point.X,
                    point.Y,
                    0,
                    0,
                    NativeMethods.SwpNoSize |
                    NativeMethods.SwpNoActivate |
                    NativeMethods.SwpShowWindow))
            {
                diagnostics = $"SetWindowPos failed: {Marshal.GetLastWin32Error()}";
                return false;
            }

            if (!NativeMethods.GetWindowRect(_hwnd, out var after))
            {
                diagnostics = $"GetWindowRect verification failed: {Marshal.GetLastWin32Error()}";
                return false;
            }

            var visible = Opacity > 0.01 &&
                NativeMethods.IsWindowVisible(_hwnd) && PopupWindowPlacement.IsFullyVisible(
                new PixelRect(after.Left, after.Top, after.Width, after.Height),
                new PixelRect(area.Left, area.Top, area.Width, area.Height));
            var dpi = TargetDpiScale(screen);
            diagnostics = $"monitor={screen.DeviceName}, primary={screen.Primary}, " +
                $"workArea={area}, dpi={dpi.X:0.##}x{dpi.Y:0.##}, " +
                $"window=({after.Left},{after.Top},{after.Width}x{after.Height}), " +
                $"nativeTopmost=true, visible={visible}";
            return visible;
        }

        private void ReassertTopmost()
        {
            if (_hwnd == IntPtr.Zero || !IsVisible) return;
            NativeMethods.SetWindowPos(
                _hwnd,
                NativeMethods.HwndTopmost,
                0,
                0,
                0,
                0,
                NativeMethods.SwpNoMove |
                NativeMethods.SwpNoSize |
                NativeMethods.SwpNoActivate |
                NativeMethods.SwpShowWindow);
        }

        private System.Windows.Forms.Screen TargetScreen() =>
            _targetScreen ?? System.Windows.Forms.Screen.PrimaryScreen
            ?? throw new InvalidOperationException("Windows did not report a primary monitor.");

        private static (double X, double Y) TargetDpiScale(System.Windows.Forms.Screen screen)
        {
            try
            {
                var center = new NativeMethods.Point(
                    screen.Bounds.Left + screen.Bounds.Width / 2,
                    screen.Bounds.Top + screen.Bounds.Height / 2);
                var monitor = NativeMethods.MonitorFromPoint(center, NativeMethods.MonitorDefaultToNearest);
                if (monitor != IntPtr.Zero &&
                    NativeMethods.GetDpiForMonitor(monitor, 0, out var dpiX, out var dpiY) == 0 &&
                    dpiX > 0 && dpiY > 0)
                    return (dpiX / 96d, dpiY / 96d);
            }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }

            return (1, 1);
        }

        private static class NativeMethods
        {
            internal const int GwlExStyle = -20;
            internal const long WsExNoActivate = 0x08000000L;
            internal const long WsExToolWindow = 0x00000080L;
            internal const uint SwpNoSize = 0x0001;
            internal const uint SwpNoMove = 0x0002;
            internal const uint SwpNoActivate = 0x0010;
            internal const uint SwpShowWindow = 0x0040;
            internal const uint MonitorDefaultToNearest = 0x00000002;
            internal static readonly IntPtr HwndTopmost = new(-1);

            [StructLayout(LayoutKind.Sequential)]
            internal readonly struct Point
            {
                internal readonly int X;
                internal readonly int Y;
                internal Point(int x, int y) { X = x; Y = y; }
            }

            [StructLayout(LayoutKind.Sequential)]
            internal struct Rect
            {
                internal int Left;
                internal int Top;
                internal int Right;
                internal int Bottom;
                internal readonly int Width => Right - Left;
                internal readonly int Height => Bottom - Top;
            }

            [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
            internal static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);

            [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
            internal static extern IntPtr SetWindowLongPtr(IntPtr hwnd, int index, IntPtr newStyle);

            [DllImport("user32.dll", SetLastError = true)]
            [return: MarshalAs(UnmanagedType.Bool)]
            internal static extern bool SetWindowPos(
                IntPtr hwnd,
                IntPtr insertAfter,
                int x,
                int y,
                int width,
                int height,
                uint flags);

            [DllImport("user32.dll", SetLastError = true)]
            [return: MarshalAs(UnmanagedType.Bool)]
            internal static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);

            [DllImport("user32.dll")]
            [return: MarshalAs(UnmanagedType.Bool)]
            internal static extern bool IsWindowVisible(IntPtr hwnd);

            [DllImport("user32.dll")]
            internal static extern IntPtr MonitorFromPoint(Point point, uint flags);

            [DllImport("shcore.dll")]
            internal static extern int GetDpiForMonitor(
                IntPtr monitor,
                int dpiType,
                out uint dpiX,
                out uint dpiY);
        }

        private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
    }
}
