using System;
using System.Collections.Generic;
using System.Linq;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

/// <summary>Reconcile an open popup without reopening offers dismissed earlier in this receipt.</summary>
public static class RecommendationVisibility
{
    public static List<Recommendation> Reconcile(
        IEnumerable<Recommendation>? visible,
        IEnumerable<Recommendation>? authoritative,
        Func<Recommendation, bool> wasShown)
    {
        // A failed refresh is not confirmation that an old offer is still available.
        if (authoritative == null) return new();
        var open = (visible ?? Array.Empty<Recommendation>())
            .Where(r => !string.IsNullOrWhiteSpace(r.EventId))
            .GroupBy(r => r.EventId, StringComparer.Ordinal).ToDictionary(g => g.Key, g => g.First(), StringComparer.Ordinal);
        return Limit(authoritative).Where(r => open.ContainsKey(r.EventId) || !wasShown(r))
            .Select(r =>
            {
                if (!open.TryGetValue(r.EventId, out var prior)) return r;
                // The event's financial quote stays immutable. Only availability is refreshed.
                prior.Balance = r.Balance;
                return prior;
            }).ToList();
    }

    public static List<Recommendation> ForCart(IEnumerable<Recommendation> visible, Func<Recommendation, bool> applies) =>
        visible.Where(applies).ToList();

    private static IEnumerable<Recommendation> Limit(IEnumerable<Recommendation> source)
    {
        var all = source.Where(r => r != null).ToList();
        return all.Where(r => r.IsSubstitution).Take(RecommendationPopupModelBuilder.MaxPerKind)
            .Concat(all.Where(r => !r.IsSubstitution).Take(RecommendationPopupModelBuilder.MaxPerKind));
    }
}
