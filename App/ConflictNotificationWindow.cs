using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay
{
    /// <summary>
    /// Баннер-уведомление о КОНФЛИКТЕ правила кампании (ТЗ T2): backend нашёл подходящее правило
    /// замены/кросс-селла, но применить его нельзя (товара нет в наличии, кампания на паузе, …).
    /// Фармацевту показываем красный фрейм с причиной (Conflict.Reason) — «подмена/допродажа сейчас
    /// невозможна». Окно frameless/Topmost, на экране ФАРМАЦЕВТА (не клиентский киоск), авто-закрытие
    /// по таймауту — как и popup рекомендаций. Не блокирует кассу; рекомендации (если пришли)
    /// показываются отдельным окном параллельно. Построено целиком в коде (без .xaml), чтобы остаться
    /// аддитивным и не трогать XAML-сборку.
    /// </summary>
    public sealed class ConflictNotificationWindow : Window
    {
        private readonly DispatcherTimer _autoClose;
        private readonly System.Windows.Forms.Screen? _targetScreen;

        public ConflictNotificationWindow(List<Conflict> conflicts, int autoCloseSec = 8,
            System.Windows.Forms.Screen? targetScreen = null)
        {
            _targetScreen = targetScreen;

            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowInTaskbar = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            SizeToContent = SizeToContent.Height;
            Width = 410;
            Title = "Epharm";

            Content = BuildContent(conflicts ?? new List<Conflict>());

            _autoClose = new DispatcherTimer { Interval = TimeSpan.FromSeconds(autoCloseSec) };
            _autoClose.Tick += (_, _) => Close();

            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
            Loaded += OnLoaded;
        }

        private UIElement BuildContent(List<Conflict> conflicts)
        {
            var card = new Border
            {
                CornerRadius = new CornerRadius(14),
                Background = Brushes.White,
                Margin = new Thickness(12),
                BorderBrush = Brush("#C0392B"), // красная рамка — конфликт
                BorderThickness = new Thickness(1.5),
                Effect = new DropShadowEffect
                {
                    Color = Colors.Black,
                    Opacity = 0.26,
                    BlurRadius = 24,
                    ShadowDepth = 5,
                },
            };

            var root = new StackPanel();

            // ШАПКА: значок «!» + заголовок + ✕
            var header = new Grid { Margin = new Thickness(14, 12, 12, 10) };
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var iconBox = new Border
            {
                Background = Brush("#C0392B"),
                CornerRadius = new CornerRadius(6),
                Width = 22,
                Height = 22,
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = "!",
                    Foreground = Brushes.White,
                    FontWeight = FontWeights.Bold,
                    FontSize = 14,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            };
            Grid.SetColumn(iconBox, 0);

            var title = new TextBlock
            {
                Text = "Epharm — рекомендация недоступна",
                Foreground = Brush("#C0392B"),
                FontWeight = FontWeights.Bold,
                FontSize = 16,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(9, 0, 0, 0),
                TextWrapping = TextWrapping.Wrap,
            };
            Grid.SetColumn(title, 1);

            var close = new Button
            {
                Content = "✕",
                Width = 26,
                Height = 26,
                FontSize = 13,
                Foreground = Brush("#6F665B"),
                Background = Brush("#EFEAE2"),
                BorderThickness = new Thickness(0),
                Cursor = Cursors.Hand,
            };
            close.Click += (_, _) => Close();
            Grid.SetColumn(close, 2);

            header.Children.Add(iconBox);
            header.Children.Add(title);
            header.Children.Add(close);
            root.Children.Add(header);

            root.Children.Add(new Border { Height = 1, Background = Brush("#F2D7D2") });

            // По каждому конфликту — строка: «[Замена] <триггер> — <причина>».
            var body = new StackPanel { Margin = new Thickness(14, 11, 14, 13) };
            foreach (var c in conflicts)
            {
                body.Children.Add(BuildConflictRow(c));
            }
            root.Children.Add(body);

            card.Child = root;
            return card;
        }

        private Border BuildConflictRow(Conflict c)
        {
            var kindLabel = c.Kind == "substitution" ? "Замена"
                : c.Kind == "crosssell" ? "Кросс-селл"
                : "Рекомендация";

            var stack = new StackPanel { Margin = new Thickness(0, 6, 0, 6) };

            // Шапка строки: бейдж типа + (опц.) триггер-товар.
            var top = new StackPanel { Orientation = Orientation.Horizontal };
            var badge = new Border
            {
                Background = Brush("#FBE9E7"),
                CornerRadius = new CornerRadius(6),
                Padding = new Thickness(8, 3, 8, 3),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = kindLabel,
                    Foreground = Brush("#C0392B"),
                    FontSize = 10.5,
                    FontWeight = FontWeights.Bold,
                },
            };
            top.Children.Add(badge);

            if (!string.IsNullOrWhiteSpace(c.TriggerName))
            {
                top.Children.Add(new TextBlock
                {
                    Text = c.TriggerName,
                    Foreground = Brush("#221C16"),
                    FontSize = 14,
                    FontWeight = FontWeights.Bold,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(8, 0, 0, 0),
                    TextWrapping = TextWrapping.Wrap,
                });
            }
            stack.Children.Add(top);

            // Причина (показываем фармацевту почему нельзя применить).
            stack.Children.Add(new TextBlock
            {
                Text = string.IsNullOrWhiteSpace(c.Reason) ? "Подмена сейчас невозможна." : c.Reason,
                Foreground = Brush("#7A2F26"),
                FontSize = 13.5,
                FontWeight = FontWeights.Medium,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 5, 0, 0),
            });

            return new Border { Child = stack };
        }

        private static Brush Brush(string hex) => (Brush)new BrushConverter().ConvertFrom(hex)!;

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            // Правый ВЕРХНИЙ угол монитора ФАРМАЦЕВТА — чтобы не перекрывать popup рекомендаций
            // (тот садится в правый нижний угол того же экрана).
            try
            {
                var screen = _targetScreen ?? System.Windows.Forms.Screen.PrimaryScreen!;
                var wa = screen.WorkingArea;
                Left = wa.Right - ActualWidth - 24;
                Top = wa.Top + 24;
            }
            catch
            {
                // если что-то не так с экранами — оставляем дефолтную позицию.
            }
            _autoClose.Start();
        }
    }
}
