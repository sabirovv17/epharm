using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using CustomerDisplay.Models;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Identity of the exact Standard-N cart line that caused a recommendation request.
    /// Backend product ids and local Standard-N PARTS.ID values belong to different namespaces,
    /// so popup lifetime must be tied to the local line rather than re-derived from the response.
    /// </summary>
    internal sealed class RecommendationTriggerBinding
    {
        private RecommendationTriggerBinding(int partId)
        {
            PartId = partId;
        }

        public int PartId { get; }

        public static RecommendationTriggerBinding? FromReceiptItem(ReceiptItem? item) =>
            item is { PartId: > 0 }
                ? new RecommendationTriggerBinding(item.PartId)
                : null;

        /// <summary>
        /// Bind a backend recommendation to the actual local Standard-N line that triggered it.
        /// The last scanned line is only a fallback when an older backend omitted trigger fields;
        /// one response can contain rules for several items already present in the cart.
        /// </summary>
        public static RecommendationTriggerBinding? Resolve(
            IEnumerable<ReceiptItem> items,
            ReceiptItem? lastScanned,
            string? triggerIpartId,
            string? triggerBarcode,
            string? triggerName)
        {
            var cart = items.Where(item => item.PartId > 0).ToList();

            var barcode = NormalizeBarcode(triggerBarcode);
            if (barcode != null)
            {
                var byBarcode = cart.Where(item => string.Equals(
                    NormalizeBarcode(item.Barcode), barcode, StringComparison.OrdinalIgnoreCase)).Take(2).ToList();
                if (byBarcode.Count == 1) return FromReceiptItem(byBarcode[0]);
            }

            if (int.TryParse(triggerIpartId?.Trim(), out var partId))
            {
                var byPart = cart.Where(item => item.PartId == partId).Take(2).ToList();
                if (byPart.Count == 1) return FromReceiptItem(byPart[0]);
            }

            if (!string.IsNullOrWhiteSpace(triggerName))
            {
                var normalizedTriggerName = NormalizeName(triggerName);
                var exactByName = cart.Where(item => string.Equals(
                    NormalizeName(item.Name), normalizedTriggerName, StringComparison.OrdinalIgnoreCase)).Take(2).ToList();
                if (exactByName.Count == 1) return FromReceiptItem(exactByName[0]);

                var ranked = cart
                    .Select(item => (Item: item, Score: NameScore(item.Name, triggerName)))
                    .Where(match => match.Score >= MinimumNameScore)
                    .OrderByDescending(match => match.Score)
                    .ThenBy(match => match.Item.PartId)
                    .Take(2)
                    .ToList();
                if (ranked.Count > 0 &&
                    (ranked.Count == 1 || ranked[0].Score - ranked[1].Score >= MinimumNameMargin))
                    return FromReceiptItem(ranked[0].Item);
            }

            var responseHasIdentity = !string.IsNullOrWhiteSpace(triggerIpartId) ||
                barcode != null || !string.IsNullOrWhiteSpace(triggerName);
            return responseHasIdentity ? null : FromReceiptItem(lastScanned);
        }

        public bool IsPresent(IEnumerable<ReceiptItem> items)
        {
            // PARTS.ID is the stable local product key for the life of a Standard-N receipt. Once
            // a response is resolved to one line, similarly named products must not keep it alive.
            return items.Any(item => item.PartId > 0 && item.PartId == PartId);
        }

        private static string? NormalizeBarcode(string? value)
        {
            var normalized = value?.Trim();
            return string.IsNullOrWhiteSpace(normalized) ? null : normalized;
        }

        private static double NameScore(string? localName, string? backendName)
        {
            var left = Fingerprint(localName);
            var right = Fingerprint(backendName);
            if (left.Tokens.Count < 3 || right.Tokens.Count < 3 ||
                !left.Numbers.SetEquals(right.Numbers) ||
                !left.Qualifiers.SetEquals(right.Qualifiers))
                return 0;

            var intersection = left.Tokens.Intersect(right.Tokens).Count();
            var union = left.Tokens.Union(right.Tokens).Count();
            return union == 0 ? 0 : intersection / (double)union;
        }

        private static NameFingerprint Fingerprint(string? raw)
        {
            var tokens = WordOrNumber.Matches((raw ?? string.Empty).ToLowerInvariant())
                .Select(match => match.Value.Replace(',', '.'))
                .Select(token => TokenAliases.TryGetValue(token, out var alias) ? alias : token)
                .Where(token => !NameStopWords.Contains(token))
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            var numbers = Number.Matches((raw ?? string.Empty).ToLowerInvariant())
                .Select(match => match.Value.Replace(',', '.'))
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            var qualifiers = tokens
                .Where(SafetyQualifiers.Contains)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            return new NameFingerprint(tokens, numbers, qualifiers);
        }

        private static string NormalizeName(string? raw)
        {
            if (string.IsNullOrWhiteSpace(raw)) return "";
            var chars = raw.ToLowerInvariant()
                .Select(ch => char.IsLetterOrDigit(ch) || char.IsWhiteSpace(ch) ? ch : ' ')
                .ToArray();
            return string.Join(" ", new string(chars).Split(' ', StringSplitOptions.RemoveEmptyEntries));
        }

        private const double MinimumNameScore = 0.82;
        private const double MinimumNameMargin = 0.08;
        private static readonly Regex Number = new(@"\d+(?:[.,]\d+)?", RegexOptions.Compiled);
        private static readonly Regex WordOrNumber = new(@"[\p{L}]+|\d+(?:[.,]\d+)?", RegexOptions.Compiled);
        private static readonly HashSet<string> NameStopWords = new(
            new[] { "для", "применения", "прим", "лица" },
            StringComparer.OrdinalIgnoreCase);
        private static readonly HashSet<string> SafetyQualifiers = new(
            new[] { "детский", "форте", "плюс", "макс", "лайт", "ночной", "дневной" },
            StringComparer.OrdinalIgnoreCase);
        private static readonly Dictionary<string, string> TokenAliases = new(StringComparer.OrdinalIgnoreCase)
        {
            ["дет"] = "детский",
            ["детс"] = "детский",
            ["детей"] = "детский",
            ["детская"] = "детский",
            ["детское"] = "детский",
            ["таб"] = "таблетка",
            ["табл"] = "таблетка",
            ["таблетки"] = "таблетка",
            ["капс"] = "капсула",
            ["капсулы"] = "капсула",
        };

        private sealed record NameFingerprint(
            HashSet<string> Tokens,
            HashSet<string> Numbers,
            HashSet<string> Qualifiers);
    }
}
