using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Effects;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    internal static class FulfillmentUi
    {
        public static Brush Brush(string value) => (Brush)new BrushConverter().ConvertFrom(value)!;
        public static string Money(decimal value, string currency = "KZT") =>
            value.ToString("#,0.##").Replace(",", " ") + (currency == "KZT" ? " ₸" : " " + currency);
        public static string Status(string status) => status switch
        {
            "submitted" => "Новый",
            "assembling" => "Собирается",
            "ready" => "Готов к выдаче",
            "completed" => "Выдан",
            "cancelled" => "Отменён",
            _ => status,
        };
        public static string Payment(FulfillmentOrder order)
        {
            if (order.Demo) return "Демо, без оплаты";
            var method = order.PaymentMethod.ToLowerInvariant() switch
            {
                "cash" => "Наличные",
                "kaspi" => "Kaspi",
                "halyk" => "Halyk",
                "card" => "Карта",
                _ => order.PaymentMethod,
            };
            var state = order.PaymentStatus switch
            {
                "paid" => "оплачено",
                "cash_collected" => "наличные получены",
                _ => "ожидает оплаты",
            };
            return method + " · " + state;
        }
        public static Button Button(string text, bool primary = false)
        {
            return new Button
            {
                Content = text,
                MinWidth = 112,
                Height = 38,
                Padding = new Thickness(14, 0, 14, 0),
                Margin = new Thickness(6, 0, 0, 0),
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                Background = primary ? Brush("#D97757") : Brushes.White,
                Foreground = primary ? Brushes.White : Brush("#423B32"),
                BorderBrush = primary ? Brush("#D97757") : Brush("#D8D1C8"),
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
            };
        }
        public static TextBlock Text(string text, double size = 13, FontWeight? weight = null) => new()
        {
            Text = text,
            FontSize = size,
            FontWeight = weight ?? FontWeights.Normal,
            Foreground = Brush("#221C16"),
            TextWrapping = TextWrapping.Wrap,
        };
    }

    internal sealed class FulfillmentNoticeWindow : Window
    {
        private const int GwlExStyle = -20;
        private const int WsExNoActivate = 0x08000000;

        public event Action? OpenQueueRequested;
        public event Action? LaterRequested;

        public FulfillmentNoticeWindow(int count, System.Windows.Forms.Screen screen)
        {
            Width = 390;
            Height = 164;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowActivated = false;
            ShowInTaskbar = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            Title = "Epharm — новый заказ";

            var card = new Border
            {
                CornerRadius = new CornerRadius(8),
                Background = Brushes.White,
                BorderBrush = FulfillmentUi.Brush("#D97757"),
                BorderThickness = new Thickness(1.5),
                Padding = new Thickness(18),
                Effect = new DropShadowEffect { BlurRadius = 24, Opacity = 0.25, ShadowDepth = 4 },
            };
            var stack = new StackPanel();
            stack.Children.Add(FulfillmentUi.Text(count == 1 ? "Новый интернет-заказ" : $"Новые интернет-заказы: {count}", 18, FontWeights.Bold));
            stack.Children.Add(new TextBlock
            {
                Text = "Откройте очередь и начните сборку.",
                FontSize = 13,
                Foreground = FulfillmentUi.Brush("#6F665B"),
                Margin = new Thickness(0, 6, 0, 16),
            });
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            var later = FulfillmentUi.Button("Позже");
            later.Click += (_, _) => { LaterRequested?.Invoke(); Close(); };
            var open = FulfillmentUi.Button("Открыть очередь", primary: true);
            open.Click += (_, _) => { OpenQueueRequested?.Invoke(); Close(); };
            buttons.Children.Add(later);
            buttons.Children.Add(open);
            stack.Children.Add(buttons);
            card.Child = stack;
            Content = new Border { Padding = new Thickness(10), Child = card };

            Loaded += (_, _) =>
            {
                var area = screen.WorkingArea;
                Left = area.Right - ActualWidth - 20;
                Top = area.Bottom - ActualHeight - 20;
            };
            SourceInitialized += (_, _) =>
            {
                var hwnd = new WindowInteropHelper(this).Handle;
                var style = NativeMethods.GetWindowLong(hwnd, GwlExStyle);
                NativeMethods.SetWindowLong(hwnd, GwlExStyle, style | WsExNoActivate);
            };
        }

        private static class NativeMethods
        {
            [System.Runtime.InteropServices.DllImport("user32.dll")]
            public static extern int GetWindowLong(IntPtr hwnd, int index);
            [System.Runtime.InteropServices.DllImport("user32.dll")]
            public static extern int SetWindowLong(IntPtr hwnd, int index, int value);
        }
    }

    internal sealed class FulfillmentQueueWindow : Window
    {
        private readonly StackPanel _orders = new();
        private readonly TextBlock _connectivity = FulfillmentUi.Text("", 12, FontWeights.SemiBold);
        private readonly TextBlock _empty = FulfillmentUi.Text("Активных заказов нет", 15, FontWeights.SemiBold);

        public event Action<FulfillmentOrder>? OpenOrderRequested;
        public event Action? RefreshRequested;

        public FulfillmentQueueWindow(System.Windows.Forms.Screen screen)
        {
            Width = 720;
            Height = 620;
            MinWidth = 620;
            MinHeight = 460;
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = screen.WorkingArea.Left + Math.Max(20, (screen.WorkingArea.Width - Width) / 2);
            Top = screen.WorkingArea.Top + Math.Max(20, (screen.WorkingArea.Height - Height) / 2);
            Title = "Epharm — интернет-заказы";
            Background = FulfillmentUi.Brush("#F7F4EF");
            Topmost = true;
            ShowInTaskbar = true;

            var root = new DockPanel { Margin = new Thickness(22) };
            var header = new Grid { Margin = new Thickness(0, 0, 0, 16) };
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var title = new StackPanel();
            title.Children.Add(FulfillmentUi.Text("Очередь интернет-заказов", 23, FontWeights.Bold));
            _connectivity.Margin = new Thickness(0, 4, 0, 0);
            title.Children.Add(_connectivity);
            var refresh = FulfillmentUi.Button("Обновить");
            refresh.Click += (_, _) => RefreshRequested?.Invoke();
            Grid.SetColumn(refresh, 1);
            header.Children.Add(title);
            header.Children.Add(refresh);
            DockPanel.SetDock(header, Dock.Top);
            root.Children.Add(header);

            _empty.HorizontalAlignment = HorizontalAlignment.Center;
            _empty.Margin = new Thickness(0, 70, 0, 0);
            _orders.Children.Add(_empty);
            root.Children.Add(new ScrollViewer
            {
                Content = _orders,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            });
            Content = root;
        }

        public void UpdateState(IReadOnlyList<FulfillmentOrder> orders, bool online)
        {
            _connectivity.Text = online ? "Онлайн · данные актуальны" : "Офлайн · показана последняя сохранённая очередь";
            _connectivity.Foreground = online ? FulfillmentUi.Brush("#397A4C") : FulfillmentUi.Brush("#B45309");
            _orders.Children.Clear();
            if (orders.Count == 0)
            {
                _orders.Children.Add(_empty);
                return;
            }

            foreach (var order in orders.OrderBy(StatusRank).ThenBy(x => x.CreatedAt))
            {
                var card = new Border
                {
                    Background = Brushes.White,
                    BorderBrush = FulfillmentUi.Brush("#E5DED5"),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(7),
                    Padding = new Thickness(16),
                    Margin = new Thickness(0, 0, 0, 10),
                };
                var grid = new Grid();
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var info = new StackPanel();
                info.Children.Add(FulfillmentUi.Text($"Заказ {order.Number}", 17, FontWeights.Bold));
                info.Children.Add(new TextBlock
                {
                    Text = $"{FulfillmentUi.Status(order.Status)} · {order.CreatedAt.ToLocalTime():dd.MM HH:mm}",
                    FontSize = 12,
                    Foreground = FulfillmentUi.Brush("#D06342"),
                    FontWeight = FontWeights.SemiBold,
                    Margin = new Thickness(0, 4, 0, 0),
                });
                info.Children.Add(new TextBlock
                {
                    Text = string.Join(", ", order.Lines.Take(3).Select(x => $"{x.Title} × {x.Quantity}")) +
                        (order.Lines.Count > 3 ? $" и ещё {order.Lines.Count - 3}" : ""),
                    FontSize = 12,
                    Foreground = FulfillmentUi.Brush("#6F665B"),
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(0, 7, 12, 0),
                });
                grid.Children.Add(info);
                var side = new StackPanel { HorizontalAlignment = HorizontalAlignment.Right };
                side.Children.Add(new TextBlock
                {
                    Text = FulfillmentUi.Money(order.Total, order.Currency),
                    FontSize = 17,
                    FontWeight = FontWeights.Bold,
                    HorizontalAlignment = HorizontalAlignment.Right,
                });
                var open = FulfillmentUi.Button("Открыть", primary: true);
                open.Margin = new Thickness(0, 14, 0, 0);
                open.Click += (_, _) => OpenOrderRequested?.Invoke(order);
                side.Children.Add(open);
                Grid.SetColumn(side, 1);
                grid.Children.Add(side);
                card.Child = grid;
                _orders.Children.Add(card);
            }
        }

        private static int StatusRank(FulfillmentOrder order) => order.Status switch
        {
            "ready" => 0,
            "submitted" => 1,
            "assembling" => 2,
            _ => 3,
        };
    }

    internal sealed class FulfillmentOrderWindow : Window
    {
        private FulfillmentOrder _order;
        private bool _online;
        private bool _busy;
        private readonly Func<FulfillmentOrder, FulfillmentActionRequest, Task<FulfillmentApiResult<FulfillmentOrder>>> _action;
        private readonly System.Windows.Forms.Screen _screen;

        public event Action<FulfillmentOrder>? OrderChanged;

        public FulfillmentOrderWindow(
            FulfillmentOrder order,
            bool online,
            System.Windows.Forms.Screen screen,
            Func<FulfillmentOrder, FulfillmentActionRequest, Task<FulfillmentApiResult<FulfillmentOrder>>> action)
        {
            _order = order;
            _online = online;
            _screen = screen;
            _action = action;
            Width = 650;
            Height = 720;
            MinWidth = 560;
            MinHeight = 560;
            Title = $"Epharm — заказ {order.Number}";
            Background = FulfillmentUi.Brush("#F7F4EF");
            Topmost = true;
            ShowInTaskbar = true;
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = screen.WorkingArea.Left + Math.Max(20, (screen.WorkingArea.Width - Width) / 2);
            Top = screen.WorkingArea.Top + Math.Max(20, (screen.WorkingArea.Height - Height) / 2);
            Render();
        }

        public void Update(FulfillmentOrder order, bool online)
        {
            _order = order;
            _online = online;
            Render();
        }

        private void Render(string? message = null, bool error = false)
        {
            var body = new StackPanel { Margin = new Thickness(22) };
            body.Children.Add(FulfillmentUi.Text($"Заказ {_order.Number}", 24, FontWeights.Bold));
            body.Children.Add(new TextBlock
            {
                Text = $"{FulfillmentUi.Status(_order.Status)} · {_order.CreatedAt.ToLocalTime():dd.MM.yyyy HH:mm}",
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                Foreground = FulfillmentUi.Brush("#D06342"),
                Margin = new Thickness(0, 4, 0, 18),
            });

            var payment = FulfillmentUi.Text(FulfillmentUi.Payment(_order), 13, FontWeights.SemiBold);
            payment.Margin = new Thickness(0, 0, 0, 14);
            body.Children.Add(payment);

            foreach (var line in _order.Lines)
            {
                var row = new Grid { Margin = new Thickness(0, 0, 0, 10) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var title = FulfillmentUi.Text(line.Title, 14, FontWeights.SemiBold);
                title.Margin = new Thickness(0, 0, 14, 0);
                row.Children.Add(title);
                var amount = FulfillmentUi.Text(
                    line.UnitPrice.HasValue
                        ? $"{line.Quantity} × {FulfillmentUi.Money(line.UnitPrice.Value)}"
                        : "Цена не указана",
                    13);
                Grid.SetColumn(amount, 1);
                row.Children.Add(amount);
                body.Children.Add(row);
            }

            body.Children.Add(new Border { Height = 1, Background = FulfillmentUi.Brush("#DDD5CB"), Margin = new Thickness(0, 8, 0, 14) });
            var total = FulfillmentUi.Text("Итого: " + FulfillmentUi.Money(_order.Total, _order.Currency), 20, FontWeights.Bold);
            total.HorizontalAlignment = HorizontalAlignment.Right;
            body.Children.Add(total);

            if (!_online)
            {
                body.Children.Add(Message("Нет связи с сервером. Действия временно недоступны.", true));
            }
            else if (!string.IsNullOrWhiteSpace(message))
            {
                body.Children.Add(Message(message!, error));
            }

            var controls = new StackPanel { Margin = new Thickness(0, 20, 0, 0) };
            BuildPrimaryAction(controls);
            if (_order.IsActive) BuildCancelAction(controls);
            body.Children.Add(controls);

            Content = new ScrollViewer
            {
                Content = body,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            };
        }

        private void BuildPrimaryAction(Panel controls)
        {
            if (_order.Status == "submitted")
            {
                var button = FulfillmentUi.Button("Начать сборку", primary: true);
                button.IsEnabled = _online && !_busy;
                button.Click += async (_, _) => await RunAction(new FulfillmentActionRequest { Action = "assemble", ExpectedVersion = _order.Version });
                controls.Children.Add(button);
                return;
            }
            if (_order.Status == "assembling")
            {
                var button = FulfillmentUi.Button("Заказ собран", primary: true);
                button.IsEnabled = _online && !_busy;
                button.Click += async (_, _) => await RunAction(new FulfillmentActionRequest { Action = "ready", ExpectedVersion = _order.Version });
                controls.Children.Add(button);
                return;
            }
            if (_order.Status != "ready") return;

            controls.Children.Add(FulfillmentUi.Text("Код выдачи (6 цифр)", 12, FontWeights.SemiBold));
            var code = new TextBox
            {
                MaxLength = 6,
                FontSize = 22,
                Height = 42,
                Margin = new Thickness(0, 6, 0, 10),
                Padding = new Thickness(9, 4, 9, 4),
            };
            code.PreviewTextInput += (_, e) => e.Handled = e.Text.Any(c => !char.IsDigit(c));
            controls.Children.Add(code);

            CheckBox? cash = null;
            var cashConfirmationRequired = !_order.Demo &&
                _order.PaymentMethod.Equals("cash", StringComparison.OrdinalIgnoreCase) &&
                _order.PaymentStatus is not ("paid" or "cash_collected");
            if (cashConfirmationRequired)
            {
                cash = new CheckBox
                {
                    Content = "Наличные получены полностью",
                    FontSize = 13,
                    Margin = new Thickness(0, 0, 0, 12),
                };
                controls.Children.Add(cash);
            }
            var issue = FulfillmentUi.Button("Выдать заказ", primary: true);
            void UpdateIssueState()
            {
                issue.IsEnabled = _online && !_busy &&
                    FulfillmentRules.CanIssue(_order, code.Text, cash?.IsChecked == true);
            }
            code.TextChanged += (_, _) => UpdateIssueState();
            if (cash != null)
            {
                cash.Checked += (_, _) => UpdateIssueState();
                cash.Unchecked += (_, _) => UpdateIssueState();
            }
            UpdateIssueState();
            issue.Click += async (_, _) => await RunAction(new FulfillmentActionRequest
            {
                Action = "issue",
                ExpectedVersion = _order.Version,
                Code = code.Text,
                CashCollected = cash?.IsChecked == true,
            });
            controls.Children.Add(issue);
            if (!_order.Demo &&
                !_order.PaymentMethod.Equals("cash", StringComparison.OrdinalIgnoreCase) &&
                _order.PaymentStatus != "paid")
                controls.Children.Add(Message("Выдача станет доступна после подтверждения оплаты сайтом.", true));
        }

        private void BuildCancelAction(Panel controls)
        {
            controls.Children.Add(new Border { Height = 1, Background = FulfillmentUi.Brush("#DDD5CB"), Margin = new Thickness(0, 22, 0, 14) });
            controls.Children.Add(FulfillmentUi.Text("Причина отмены", 12, FontWeights.SemiBold));
            var reason = new TextBox
            {
                MaxLength = 500,
                MinHeight = 54,
                TextWrapping = TextWrapping.Wrap,
                AcceptsReturn = true,
                Margin = new Thickness(0, 6, 0, 10),
                Padding = new Thickness(8),
            };
            controls.Children.Add(reason);
            var cancel = FulfillmentUi.Button("Отменить заказ");
            cancel.IsEnabled = _online && !_busy;
            cancel.Click += async (_, _) =>
            {
                if (string.IsNullOrWhiteSpace(reason.Text))
                {
                    Render("Укажите причину отмены.", true);
                    return;
                }
                var confirmation = MessageBox.Show(
                    this,
                    $"Отменить заказ {_order.Number}? Это действие нельзя отменить.",
                    "Подтверждение отмены",
                    MessageBoxButton.YesNo,
                    MessageBoxImage.Warning,
                    MessageBoxResult.No);
                if (confirmation != MessageBoxResult.Yes) return;
                await RunAction(new FulfillmentActionRequest
                {
                    Action = "cancel",
                    ExpectedVersion = _order.Version,
                    Reason = reason.Text.Trim(),
                });
            };
            controls.Children.Add(cancel);
        }

        private async Task RunAction(FulfillmentActionRequest request)
        {
            if (_busy || !_online) return;
            _busy = true;
            Render("Сохраняем изменение…");
            var result = await _action(_order, request);
            _busy = false;
            if (result.IsSuccess && result.Value != null)
            {
                _order = result.Value;
                OrderChanged?.Invoke(_order);
                Render("Изменение сохранено.");
                return;
            }

            if (result.IsUnauthorized) _online = false;
            var message = result.StatusCode == HttpStatusCode.TooManyRequests
                ? "Код временно заблокирован после пяти ошибок. Повторите через 15 минут."
                : result.Message ?? "Не удалось сохранить изменение.";
            Render(message, true);
        }

        private static Border Message(string text, bool error = false) => new()
        {
            Background = FulfillmentUi.Brush(error ? "#FFF1EE" : "#EEF7F0"),
            BorderBrush = FulfillmentUi.Brush(error ? "#E6A08B" : "#A9D2B2"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(10),
            Margin = new Thickness(0, 14, 0, 0),
            Child = new TextBlock
            {
                Text = text,
                Foreground = FulfillmentUi.Brush(error ? "#9A3412" : "#28623A"),
                FontSize = 12,
                TextWrapping = TextWrapping.Wrap,
            },
        };
    }
}
