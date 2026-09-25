using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// Presentation model for the pharmacist popup. Keeping grouping and formatting outside WPF
    /// makes the 5+5 contract testable on every CI runner, including Linux and macOS.
    /// </summary>
    public sealed class RecommendationPopupModel
    {
        public RecommendationPopupSection Substitutions { get; init; } = new();
        public RecommendationPopupSection CrossSells { get; init; } = new();
        public int TotalCount => Substitutions.Rows.Count + CrossSells.Rows.Count;
    }

    public sealed class RecommendationPopupSection
    {
        public string TriggerText { get; init; } = "";
        public bool HasSharedTrigger => !string.IsNullOrWhiteSpace(TriggerText);
        public IReadOnlyList<RecommendationPopupRow> Rows { get; init; } = Array.Empty<RecommendationPopupRow>();
    }

    public sealed class RecommendationPopupRow
    {
        public string Number { get; init; } = "";
        public string Name { get; init; } = "";
        public string Meta { get; init; } = "";
        public string Detail { get; init; } = "";
        public string DetailLabel { get; init; } = "";
        public bool HasDetail => !string.IsNullOrWhiteSpace(Detail);
        public string TriggerContext { get; init; } = "";
        public string Price { get; init; } = "—";
        public string Bonus { get; init; } = "Без вознаграждения";
        public bool HasBonus { get; init; }
        public string PartnerLabel { get; init; } = "";
    }

    public static class RecommendationPopupModelBuilder
    {
        public const int MaxPerKind = 5;

        public static RecommendationPopupModel Build(IEnumerable<Recommendation>? recommendations)
        {
            var all = recommendations?.Where(r => r != null).ToList() ?? new List<Recommendation>();
            return new RecommendationPopupModel
            {
                Substitutions = BuildSection(all.Where(r => r.IsSubstitution).Take(MaxPerKind), true),
                CrossSells = BuildSection(all.Where(r => !r.IsSubstitution).Take(MaxPerKind), false),
            };
        }

        private static RecommendationPopupSection BuildSection(
            IEnumerable<Recommendation> source,
            bool substitution)
        {
            var recommendations = source.ToList();
            var triggers = recommendations
                .Select(TriggerName)
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
            var sharedTrigger = triggers.Count == 1 ? triggers[0] : "";

            var rows = recommendations.Select((recommendation, index) => new RecommendationPopupRow
            {
                Number = (index + 1).ToString(CultureInfo.InvariantCulture),
                Name = recommendation.RecommendName,
                Meta = JoinDot(recommendation.RecommendVendor, recommendation.RecommendVolume),
                Detail = FirstNonBlank(
                    recommendation.Script,
                    recommendation.Advantages?.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))),
                DetailLabel = substitution ? "Почему: " : "Скажите: ",
                TriggerContext = string.IsNullOrWhiteSpace(sharedTrigger)
                    ? TriggerPrefix(substitution) + TriggerName(recommendation)
                    : "",
                Price = Money(recommendation.RecommendPrice),
                Bonus = recommendation.Bonus > 0
                    ? $"Вознаграждение +{Money(recommendation.Bonus)}"
                    : "Без вознаграждения",
                HasBonus = recommendation.Bonus > 0,
                PartnerLabel = recommendation.PartnerLabel?.Trim() ?? "",
            }).ToList();

            return new RecommendationPopupSection { TriggerText = sharedTrigger, Rows = rows };
        }

        private static string TriggerPrefix(bool substitution) =>
            substitution ? "Вместо: " : "К товару: ";

        private static string TriggerName(Recommendation recommendation) =>
            JoinDot(recommendation.TriggerName, recommendation.TriggerVolume);

        private static string FirstNonBlank(params string?[] values) =>
            values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))?.Trim() ?? "";

        public static string Money(int? value)
        {
            if (!value.HasValue) return "—";
            return value.Value.ToString("#,0", CultureInfo.InvariantCulture).Replace(",", " ") + " ₸";
        }

        private static string JoinDot(params string?[] values) =>
            string.Join(" · ", values.Where(value => !string.IsNullOrWhiteSpace(value)).Select(value => value!.Trim()));
    }
}
