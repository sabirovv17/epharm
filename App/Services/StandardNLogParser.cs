using System;
using System.Globalization;
using System.Text.RegularExpressions;
using CustomerDisplay.Models;

namespace CustomerDisplay.Services;

/// <summary>
/// Parses the detailed Add2Cheque fallback emitted by some Standard-N builds. Field names are
/// stable, but real installations vary whitespace, separator (: or =) and field order.
/// </summary>
internal static class StandardNLogParser
{
    private const RegexOptions Options =
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled;

    private static readonly Regex PartIdPattern = new(
        @"(?<![\p{L}\p{N}_])iPartID\s*[:=]\s*[""']?(?<value>\d+)",
        Options);

    private static readonly Regex NamePattern = FieldPattern("sname");
    private static readonly Regex PricePattern = FieldPattern("price");
    private static readonly Regex QuantityPattern = FieldPattern("quant");

    private static readonly Regex ExplicitBarcodePattern = new(
        @"(?<![\p{L}\p{N}_])(?:orig_bcode_izg|bcode_izg|barcode1?|bar_?code|ean(?:13)?|bcode|shtrih|штрих(?:код)?)\s*[:=]\s*[""']?(?<value>\d{8,14})",
        Options);

    private static readonly Regex IpartBarcodePattern = new(
        @"iPartID\s*[:=]\s*[""']?\d+[""']?\s*\(\s*(?<value>\d{8,14})\s*\)",
        Options);

    public static ReceiptItem? TryParseAdd2Cheque(string? line)
    {
        if (string.IsNullOrWhiteSpace(line) ||
            !line.Contains("Add2Cheque", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("(delete)", StringComparison.OrdinalIgnoreCase))
            return null;

        var partId = TryExtractPartId(line);
        var quantityText = ExtractField(QuantityPattern, line);
        if (!partId.HasValue || partId.Value <= 0 ||
            string.IsNullOrWhiteSpace(quantityText) ||
            !TryParseDecimal(quantityText, out var quantity) ||
            quantity <= 0)
            return null;

        var price = 0m;
        var priceText = ExtractField(PricePattern, line);
        if (!string.IsNullOrWhiteSpace(priceText))
            _ = TryParseDecimal(priceText, out price);

        return new ReceiptItem
        {
            PartId = partId.Value,
            Barcode = ExtractBarcode(line, partId.Value.ToString(CultureInfo.InvariantCulture)),
            Name = ExtractField(NamePattern, line) ?? "",
            Price = price,
            Qty = quantity,
            DiscountPercent = 0m,
        };
    }

    public static int? TryExtractPartId(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        var match = PartIdPattern.Match(line);
        return match.Success &&
               int.TryParse(match.Groups["value"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var partId)
            ? partId
            : null;
    }

    private static Regex FieldPattern(string names) => new(
        $@"(?<![\p{{L}}\p{{N}}_])(?:{names})\s*[:=]\s*(?<value>[^;\r\n]*)",
        Options);

    private static string? ExtractField(Regex pattern, string line)
    {
        var match = pattern.Match(line);
        if (!match.Success) return null;
        var value = match.Groups["value"].Value.Trim();
        if (value.Length >= 2 &&
            ((value[0] == '"' && value[^1] == '"') ||
             (value[0] == '\'' && value[^1] == '\'')))
            value = value[1..^1].Trim();
        return value;
    }

    private static string? ExtractBarcode(string line, string partId)
    {
        foreach (Match match in ExplicitBarcodePattern.Matches(line))
        {
            var value = match.Groups["value"].Value;
            if (IsBarcode(value)) return value;
        }

        var ipartMatch = IpartBarcodePattern.Match(line);
        var inner = ipartMatch.Success ? ipartMatch.Groups["value"].Value : null;
        return IsBarcode(inner) && !string.Equals(inner, partId, StringComparison.Ordinal)
            ? inner
            : null;
    }

    private static bool TryParseDecimal(string raw, out decimal value)
    {
        var normalized = raw.Trim().Trim('"', '\'').Replace(" ", "").Replace(',', '.');
        return decimal.TryParse(
            normalized,
            NumberStyles.AllowLeadingSign | NumberStyles.AllowDecimalPoint,
            CultureInfo.InvariantCulture,
            out value);
    }

    private static bool IsBarcode(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length is not (8 or 12 or 13 or 14)) return false;
        foreach (var ch in value)
            if (ch is < '0' or > '9') return false;
        return true;
    }
}
