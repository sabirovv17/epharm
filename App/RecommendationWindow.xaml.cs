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
        private int _index;

        /// <summary>Текущая показанная рекомендация (на активном табе).</summary>
        public Recommendation Current => _recs[_index];

        /// <summary>Совместимость: первая рекомендация.</summary>
        public Recommendation Recommendation => _recs[0];

        /// <summary>Все рекомендации, показанные в текущем popup-е.</summary>
        public IReadOnlyList<Recommendation> Recommendations => _recs;

        // null = автозакрытия по таймауту НЕТ (autoCloseSec <= 0). Окно живёт до ✕/таймаута.
        // Карточка информационная: решение (F9/Esc) убрано — факт определяется по реальному чеку.
        private readonly DispatcherTimer? _autoClose;
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
            _targetScreen = targetScreen;

            // Автозакрытие по таймауту — только если autoCloseSec > 0. Для рекомендаций кассы
            // передаём 0: окно висит, пока фармацевт не закроет (✕).
            if (autoCloseSec > 0)
            {
                _autoClose = new DispatcherTimer { Interval = TimeSpan.FromSeconds(autoCloseSec) };
                _autoClose.Tick += (_, _) => Close(); // таймаут закрывает окно (нерешённые не фиксируем)
            }

            ShowAt(0);
            _autoClose?.Start();

            KeyDown += OnKeyDown;
            Loaded += OnLoaded;
        }

        /// <summary>Показать рекомендацию №i: заполнить карточку, перерисовать табы/кнопки, сбросить таймаут.</summary>
        private void ShowAt(int i)
        {
            _index = Math.Max(0, Math.Min(i, _recs.Count - 1));
            Fill(_recs[_index]);
            BuildTabs();
            _autoClose?.Stop();
            _autoClose?.Start();
        }

        /// <summary>Перерисовать табы «Замена | Кросс-селл» (клик/Tab переключают). Скрыты при 1 реко.</summary>
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
                TabsPanel.Children.Add(MakeTab(label, i, i == _index));
            }
        }

        private Border MakeTab(string text, int i, bool active)
        {
            // Цвет таба: активный (текущий просмотр) — коралл; остальные — обычные.
            Brush bg, fg;
            if (active) { bg = Brush("#D97757"); fg = Brushes.White; }
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

        /// <summary>Раскладывает данные рекомендации по карточке; пустые секции прячет.</summary>
        private void Fill(Recommendation rec)
        {
            TbHeader.Text = rec.IsSubstitution ? "Epharm — рекомендация замены" : "Epharm — рекомендация кросс-селла";

            // Что попросил покупатель / что уже в чеке
            TbTriggerLabel.Text = rec.IsSubstitution ? "ПОКУПАТЕЛЬ ПОПРОСИЛ" : "УЖЕ В ЧЕКЕ";
            if (!string.IsNullOrWhiteSpace(rec.TriggerName))
            {
                // Цену в попапе НЕ показываем (ни значение, ни «уточняется») — только товар и объём.
                TbTrigger.Text = JoinDot(rec.TriggerName, rec.TriggerVolume);
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
            // Barcode scanners usually behave as keyboards. The popup must remain topmost but must
            // not steal focus from Standard-N, otherwise the next scan can be lost in this window.
        }

        private void OnKeyDown(object sender, KeyEventArgs e)
        {
            // Tab переключает табы «Замена | Кросс-селл» (если рекомендаций >1). F9 (принять) и
            // Esc (пропустить) убраны: карточка информационная, факт — по реальному чеку (сверка).
            if (e.Key == Key.Tab && _recs.Count > 1)
            {
                ShowAt((_index + 1) % _recs.Count);
                e.Handled = true;
            }
        }

        /// <summary>✕ — закрыть карточку.</summary>
        private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
    }
}
