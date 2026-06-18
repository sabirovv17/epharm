using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay
{
    /// <summary>
    /// Popup-рекомендация поверх кассы (ТЗ §4, шаблон Figure 11). Принимает СПИСОК рекомендаций
    /// (замена и/или кросс-селл): если их >1 — под шапкой табы «Замена | Кросс-селл», переключение
    /// клик/Tab; на экране одна карточка. КАЖДАЯ рекомендация решается независимо — фармацевт может
    /// принять и замену, и кросс-селл (или одну). Окно живёт, пока есть нерешённые; закрывается,
    /// когда по всем принято решение, либо ✕/таймаут. ВСЕ поля приходят из данных (задаются в
    /// админке, пуллятся в клиент) — пустые секции скрываются.
    /// </summary>
    public partial class RecommendationWindow : Window
    {
        private readonly List<Recommendation> _recs;
        private readonly int[] _status; // 0 = не решено, 1 = принято, 2 = пропущено
        private int _index;

        /// <summary>Текущая показанная рекомендация (на активном табе).</summary>
        public Recommendation Current => _recs[_index];

        /// <summary>Совместимость: первая рекомендация.</summary>
        public Recommendation Recommendation => _recs[0];

        /// <summary>Фармацевт принял рекомендацию (F9) — передаётся именно принятая.</summary>
        public event EventHandler<Recommendation>? Accepted;

        /// <summary>Фармацевт пропустил рекомендацию (Esc/кнопка) — передаётся именно пропущенная.</summary>
        public event EventHandler<Recommendation>? Skipped;

        private readonly DispatcherTimer _autoClose;
        private readonly System.Windows.Forms.Screen? _targetScreen;

        /// <summary>Одна рекомендация (обратная совместимость).</summary>
        public RecommendationWindow(Recommendation rec, int autoCloseSec = 30, System.Windows.Forms.Screen? targetScreen = null)
            : this(new List<Recommendation> { rec }, autoCloseSec, targetScreen)
        {
        }

        /// <param name="recs">Список рекомендаций (замена и/или кросс-селл), 1–2 шт.</param>
        /// <param name="targetScreen">Монитор ФАРМАЦЕВТА (НЕ клиентский). null — основной.</param>
        public RecommendationWindow(List<Recommendation> recs, int autoCloseSec = 30, System.Windows.Forms.Screen? targetScreen = null)
        {
            InitializeComponent();
            _recs = (recs != null && recs.Count > 0) ? recs : new List<Recommendation> { new Recommendation() };
            _status = new int[_recs.Count];
            _targetScreen = targetScreen;

            _autoClose = new DispatcherTimer { Interval = TimeSpan.FromSeconds(autoCloseSec) };
            _autoClose.Tick += (_, _) => Close(); // таймаут закрывает окно (нерешённые не фиксируем)

            ShowAt(0);
            _autoClose.Start();

            KeyDown += OnKeyDown;
            Loaded += OnLoaded;
        }

        /// <summary>Показать рекомендацию №i: заполнить карточку, перерисовать табы/кнопки, сбросить таймаут.</summary>
        private void ShowAt(int i)
        {
            _index = Math.Max(0, Math.Min(i, _recs.Count - 1));
            Fill(_recs[_index]);
            BuildTabs();
            UpdateDecisionUI();
            _autoClose?.Stop();
            _autoClose?.Start();
        }

        /// <summary>Перерисовать табы «Замена | Кросс-селл» со статусом (✓ принято / ✕ пропущено). Скрыты при 1 реко.</summary>
        private void BuildTabs()
        {
            if (_recs.Count <= 1)
            {
                PanelTabs.Visibility = Visibility.Collapsed;
                return;
            }
            PanelTabs.Visibility = Visibility.Visible;
            TabsPanel.Children.Clear();
            for (int i = 0; i < _recs.Count; i++)
            {
                var label = _recs[i].IsSubstitution ? "Замена" : "Кросс-селл";
                if (_status[i] == 1) label += " ✓";
                else if (_status[i] == 2) label += " ✕";
                TabsPanel.Children.Add(MakeTab(label, i, i == _index, _status[i]));
            }
        }

        private Border MakeTab(string text, int i, bool active, int status)
        {
            // Цвет таба: активный (текущий просмотр) — коралл; принятый — кремовый с ✓;
            // пропущенный — серый; ожидающий — обычный. Так видно, что замена/кросс-селл применились.
            Brush bg, fg;
            if (active) { bg = Brush("#D97757"); fg = Brushes.White; }
            else if (status == 1) { bg = Brush("#F8E7DD"); fg = Brush("#BE5A38"); }
            else if (status == 2) { bg = Brushes.Transparent; fg = Brush("#9D9388"); }
            else { bg = Brushes.Transparent; fg = Brush("#423B32"); }

            var tb = new TextBlock
            {
                Text = text,
                FontSize = 13,
                FontWeight = active ? FontWeights.Bold : FontWeights.SemiBold,
                Foreground = fg,
                HorizontalAlignment = HorizontalAlignment.Center, // текст по центру таба
                VerticalAlignment = VerticalAlignment.Center,
            };
            var border = new Border
            {
                CornerRadius = new CornerRadius(7),
                Background = bg,
                Padding = new Thickness(0, 7, 0, 7),
                Margin = new Thickness(2, 0, 2, 0),
                HorizontalAlignment = HorizontalAlignment.Stretch, // равная ширина (UniformGrid-ячейка)
                Cursor = Cursors.Hand,
                Child = tb,
            };
            border.MouseLeftButtonUp += (_, _) => ShowAt(i);
            return border;
        }

        /// <summary>
        /// Низ карточки: для нерешённой реко — кнопки; для решённой — заметная плашка-подтверждение
        /// «✓ Замена применена» / «✓ Кросс-селл применён» (зелёная) либо «Пропущено» (серая).
        /// </summary>
        private void UpdateDecisionUI()
        {
            int st = _status[_index];
            if (st == 0)
            {
                PanelButtons.Visibility = Visibility.Visible;
                PanelStatus.Visibility = Visibility.Collapsed;
                BtnAccept.IsEnabled = true;
                BtnSkip.IsEnabled = true;
                BtnAccept.Content = Current.IsSubstitution ? "Заменить (F9)" : "Добавить (F9)";
                BtnSkip.Content = "Пропустить (Esc)";
            }
            else
            {
                PanelButtons.Visibility = Visibility.Collapsed;
                PanelStatus.Visibility = Visibility.Visible;
                if (st == 1)
                {
                    PanelStatus.Background = Brush("#D97757");
                    TbStatus.Foreground = Brushes.White;
                    TbStatus.Text = Current.IsSubstitution ? "✓ Замена применена" : "✓ Кросс-селл применён";
                }
                else
                {
                    PanelStatus.Background = Brush("#EFEAE2");
                    TbStatus.Foreground = Brush("#6F665B");
                    TbStatus.Text = "Пропущено";
                }
            }
        }

        /// <summary>Раскладывает данные рекомендации по карточке; пустые секции прячет.</summary>
        private void Fill(Recommendation rec)
        {
            TbHeader.Text = rec.IsSubstitution ? "Epharm — рекомендация замены" : "Epharm — рекомендация кросс-селла";

            // Что попросил покупатель / что уже в чеке
            TbTriggerLabel.Text = rec.IsSubstitution ? "ПОКУПАТЕЛЬ ПОПРОСИЛ" : "УЖЕ В ЧЕКЕ";
            if (!string.IsNullOrWhiteSpace(rec.TriggerName))
            {
                TbTrigger.Text = JoinDot(rec.TriggerName, rec.TriggerVolume, Money(rec.TriggerPrice));
                PanelTrigger.Visibility = Visibility.Visible;
            }
            else
            {
                PanelTrigger.Visibility = Visibility.Collapsed;
            }

            // Что предложить
            TbOfferLabel.Text = rec.IsSubstitution ? "ПРЕДЛОЖИТЕ ВМЕСТО" : "ДОБАВЬТЕ К ПОКУПКЕ";
            if (!string.IsNullOrWhiteSpace(rec.PartnerLabel))
            {
                TbPartner.Text = rec.PartnerLabel;
                PartnerBadge.Visibility = Visibility.Visible;
            }
            else
            {
                PartnerBadge.Visibility = Visibility.Collapsed;
            }

            TbRecommend.Text = rec.RecommendName;
            TbPrice.Text = Money(rec.RecommendPrice);

            // Сравнение vs запасной список преимуществ
            if (rec.Comparison != null && rec.Comparison.Count > 0)
            {
                ComparisonList.ItemsSource = rec.Comparison;
                PanelComparison.Visibility = Visibility.Visible;
                AdvantagesList.Visibility = Visibility.Collapsed;
            }
            else if (rec.Advantages != null && rec.Advantages.Count > 0)
            {
                AdvantagesList.ItemsSource = rec.Advantages;
                AdvantagesList.Visibility = Visibility.Visible;
                PanelComparison.Visibility = Visibility.Collapsed;
            }
            else
            {
                PanelComparison.Visibility = Visibility.Collapsed;
                AdvantagesList.Visibility = Visibility.Collapsed;
            }

            // Скрипт
            if (!string.IsNullOrWhiteSpace(rec.Script))
            {
                TbScript.Text = $"«{rec.Script}»";
                PanelScript.Visibility = Visibility.Visible;
            }
            else
            {
                PanelScript.Visibility = Visibility.Collapsed;
            }

            // Бонус + цель
            TbBonus.Text = $"+{Money(rec.Bonus)}";
            if (!string.IsNullOrWhiteSpace(rec.GoalText))
            {
                TbGoal.Text = rec.GoalText;
                if (rec.GoalBonus.HasValue)
                {
                    TbGoalBonus.Text = $"+{Money(rec.GoalBonus.Value)} за выполнение цели";
                    TbGoalBonus.Visibility = Visibility.Visible;
                }
                else
                {
                    TbGoalBonus.Visibility = Visibility.Collapsed;
                }
                PanelGoal.Visibility = Visibility.Visible;
            }
            else
            {
                PanelGoal.Visibility = Visibility.Collapsed;
            }
        }

        private static Brush Brush(string hex) => (Brush)new BrushConverter().ConvertFrom(hex)!;

        /// <summary>«2 890 ₸» из числа (пробел-разделитель тысяч). Пусто для null.</summary>
        private static string Money(int? value)
        {
            if (!value.HasValue) return "";
            return value.Value.ToString("#,0").Replace(",", " ") + " ₸";
        }

        /// <summary>Склеивает непустые части через « · ».</summary>
        private static string JoinDot(params string?[] parts)
            => string.Join(" · ", parts.Where(s => !string.IsNullOrWhiteSpace(s)));

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            // Правый нижний угол монитора ФАРМАЦЕВТА (не клиентского!).
            try
            {
                var screen = _targetScreen ?? System.Windows.Forms.Screen.PrimaryScreen!;
                var wa = screen.WorkingArea;
                Left = wa.Right - ActualWidth - 24;
                Top = wa.Bottom - ActualHeight - 24;
            }
            catch
            {
                // если что-то не так с экранами — оставляем дефолтную позицию.
            }
            Activate();
            Focus();
        }

        private void OnKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.F9) DecideCurrent(accepted: true);
            else if (e.Key == Key.Escape) DecideCurrent(accepted: false);
            else if (e.Key == Key.Tab && _recs.Count > 1)
            {
                ShowAt((_index + 1) % _recs.Count); // Tab переключает между табами
                e.Handled = true;
            }
        }

        private void OnAcceptClick(object sender, RoutedEventArgs e) => DecideCurrent(accepted: true);

        private void OnSkipClick(object sender, RoutedEventArgs e) => DecideCurrent(accepted: false);

        /// <summary>✕ — закрыть всю карточку (нерешённые рекомендации не фиксируем).</summary>
        private void OnCloseClick(object sender, RoutedEventArgs e) => Close();

        /// <summary>
        /// Решение по ТЕКУЩЕЙ рекомендации (принять/пропустить). Фиксирует её результат и переходит
        /// к следующей нерешённой; если решены все — закрывает окно. Так фармацевт может принять и
        /// замену, и кросс-селл по очереди.
        /// </summary>
        private void DecideCurrent(bool accepted)
        {
            if (_status[_index] == 0)
            {
                _status[_index] = accepted ? 1 : 2;
                var rec = _recs[_index];
                if (accepted) Accepted?.Invoke(this, rec);
                else Skipped?.Invoke(this, rec);
            }

            int next = FirstPending();
            if (next >= 0)
            {
                ShowAt(next); // ещё есть нерешённые — переходим к ним
            }
            else
            {
                // решены все — показываем финальное подтверждение текущей, затем закрываем с
                // задержкой, чтобы фармацевт успел увидеть, что применилось.
                BuildTabs();
                UpdateDecisionUI();
                _autoClose.Stop();
                var closeTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1200) };
                closeTimer.Tick += (_, _) => { closeTimer.Stop(); Close(); };
                closeTimer.Start();
            }
        }

        /// <summary>Индекс первой нерешённой рекомендации, или -1 если решены все.</summary>
        private int FirstPending()
        {
            for (int i = 0; i < _status.Length; i++)
                if (_status[i] == 0) return i;
            return -1;
        }
    }
}
