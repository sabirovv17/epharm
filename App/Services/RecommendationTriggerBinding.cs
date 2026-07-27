using System;
using System.Collections.Generic;
using System.Linq;
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
        private RecommendationTriggerBinding(int partId, string? barcode, string name)
        {
            PartId = partId;
            Barcode = NormalizeBarcode(barcode);
            Name = NormalizeName(name);
        }

        public int PartId { get; }
        public string? Barcode { get; }
        public string Name { get; }

        public static RecommendationTriggerBinding? FromReceiptItem(ReceiptItem? item) =>
            item is { PartId: > 0 }
                ? new RecommendationTriggerBinding(item.PartId, item.Barcode, item.Name)
                : null;

        public bool IsPresent(IEnumerable<ReceiptItem> items)
        {
            return items.Where(item => item.PartId > 0).Any(item =>
                item.PartId == PartId ||
                (Barcode != null && string.Equals(
                    NormalizeBarcode(item.Barcode), Barcode, StringComparison.OrdinalIgnoreCase)) ||
                NamesLikelyMatch(NormalizeName(item.Name), Name));
        }

        private static string? NormalizeBarcode(string? value)
        {
            var normalized = value?.Trim();
            return string.IsNullOrWhiteSpace(normalized) ? null : normalized;
        }

        private static bool NamesLikelyMatch(string left, string right) =>
            left.Length > 0 && right.Length > 0 &&
            (left == right ||
             left.Contains(right, StringComparison.OrdinalIgnoreCase) ||
             right.Contains(left, StringComparison.OrdinalIgnoreCase));

        private static string NormalizeName(string? raw)
        {
            if (string.IsNullOrWhiteSpace(raw)) return "";
            var chars = raw.ToLowerInvariant()
                .Select(ch => char.IsLetterOrDigit(ch) || char.IsWhiteSpace(ch) ? ch : ' ')
                .ToArray();
            return string.Join(" ", new string(chars).Split(' ', StringSplitOptions.RemoveEmptyEntries));
        }
    }
}
