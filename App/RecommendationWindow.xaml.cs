using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;

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

            Loaded += OnLoaded;
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
                var screen = _targetScreen ?? System.Windows.Forms.Screen.PrimaryScreen!;
                var workArea = screen.WorkingArea;
                Left = workArea.Right - ActualWidth - 20;
                Top = workArea.Bottom - ActualHeight - 20;
            }
            catch
            {
                // If monitor discovery fails, WPF keeps the default position.
            }
        }

        private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
    }
}
