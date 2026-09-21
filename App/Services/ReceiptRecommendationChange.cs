using System;

namespace CustomerDisplay.Services;

/// <summary>
/// What a mirrored Standard-N receipt change means for an in-flight recommendation request.
/// Presentation-only changes and duplicate observations must not cancel a request started by the
/// other receipt source (zkassa.log vs Firebird). Identity enrichment and quantity increases need
/// a fresh request; removals/decreases only invalidate the current snapshot.
/// </summary>
internal enum ReceiptRecommendationAction
{
    None,
    CancelPending,
    Refresh,
}

internal static class ReceiptRecommendationChange
{
    public static ReceiptRecommendationAction ClassifyRemoval(bool actuallyRemoved) =>
        actuallyRemoved ? ReceiptRecommendationAction.CancelPending : ReceiptRecommendationAction.None;

    public static ReceiptRecommendationAction ClassifyLine(
        bool existed,
        decimal previousQty,
        string? previousBarcode,
        string? previousName,
        decimal nextQty,
        string? nextBarcode,
        string? nextName)
    {
        if (nextQty <= 0) return ReceiptRecommendationAction.CancelPending;
        if (!existed) return ReceiptRecommendationAction.Refresh;
        if (nextQty > previousQty) return ReceiptRecommendationAction.Refresh;
        if (nextQty < previousQty) return ReceiptRecommendationAction.CancelPending;

        // UpsertItemSetQty preserves a known barcode/name when a secondary source omits it.
        var effectiveBarcode = MergeIdentity(nextBarcode, previousBarcode);
        var effectiveName = MergeIdentity(nextName, previousName);
        return Same(previousBarcode, effectiveBarcode) && Same(previousName, effectiveName)
            ? ReceiptRecommendationAction.None
            : ReceiptRecommendationAction.Refresh;
    }

    public static ReceiptRecommendationAction Combine(
        ReceiptRecommendationAction current,
        ReceiptRecommendationAction next)
    {
        if (current == ReceiptRecommendationAction.Refresh || next == ReceiptRecommendationAction.Refresh)
            return ReceiptRecommendationAction.Refresh;
        if (current == ReceiptRecommendationAction.CancelPending || next == ReceiptRecommendationAction.CancelPending)
            return ReceiptRecommendationAction.CancelPending;
        return ReceiptRecommendationAction.None;
    }

    public static string? MergeIdentity(string? preferred, string? fallback) =>
        !string.IsNullOrWhiteSpace(preferred) ? preferred!.Trim() : fallback?.Trim();

    private static bool Same(string? left, string? right) =>
        string.Equals(left?.Trim(), right?.Trim(), StringComparison.OrdinalIgnoreCase);
}
