using System.Linq;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

internal static class StandardNSellerSource
{
    public static SaleSellerSource? FromObservedGroup(StandardNObservedReceiptGroup? group,
        CheckoutSession checkout, StandardNReceiptDocument document, StandardNReceiptSnapshot? completion)
    {
        if (group == null || completion == null || group.SourceNamespace is not { Length: 64 } source ||
            source.Any(c => c is not (>= 'a' and <= 'f') and not (>= '0' and <= '9')) ||
            source != group.Snapshot.ObservedSourceNamespace || source != completion.ObservedSourceNamespace ||
            !ValidId(group.PharmacistId) ||
            !ValidId(group.Snapshot.WorkstationId) || group.Snapshot.SessionId <= 0 ||
            group.CheckoutSessionId != checkout.SessionId || group.PharmacistId != checkout.PharmacistId ||
            group.PharmacistId != group.Snapshot.Pharmacist.Id || group.PharmacistId != completion.Pharmacist.Id ||
            group.Snapshot.SessionId != checkout.StandardNSessionId ||
            group.Snapshot.SessionId != group.Snapshot.Pharmacist.SessionId ||
            group.Snapshot.SessionId != completion.SessionId || group.Snapshot.SessionId != completion.Pharmacist.SessionId ||
            group.Snapshot.SessionId != document.SessionId || group.Snapshot.WorkstationId != completion.WorkstationId ||
            group.Snapshot.DocumentId != completion.DocumentId ||
            group.Snapshot.Documents?.Any(d => d.DocumentId == document.DocumentId && d.SessionId == document.SessionId) != true ||
            completion.Documents?.Contains(document) != true) return null;
        return new(source, group.PharmacistId, group.Snapshot.SessionId, group.Snapshot.WorkstationId!);
    }

    private static bool ValidId(string? value) => !string.IsNullOrWhiteSpace(value) && value.Length <= 128 &&
        value == value.Trim() && !value.Any(char.IsControl);
}
