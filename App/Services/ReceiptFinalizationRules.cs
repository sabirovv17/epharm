using System;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

public static class ReceiptFinalizationRules
{
    public const string ConfirmedPrintSource = "standardn-print-log";
    public const string UnconfirmedDocumentSwitchSource = "standardn-document-switch";
    public const string UnconfirmedDisappearanceSource = "standardn-firebird-close";

    // A document switch, disappearance or held state is never evidence of payment.
    public static bool IsConfirmedPrint(string? source) =>
        string.Equals(source, ConfirmedPrintSource, StringComparison.Ordinal);

    // Only these known legacy bugs are withheld during replay. Other older capture
    // formats retain compatibility; a verified stored fiscal artifact has its own path.
    public static bool IsKnownUnconfirmedLegacySource(string? source) =>
        string.Equals(source, UnconfirmedDocumentSwitchSource, StringComparison.Ordinal) ||
        string.Equals(source, UnconfirmedDisappearanceSource, StringComparison.Ordinal);

    public static bool IsSamePhysicalSaleConfirmedAfterLegacyFailure(SaleReport preserved, SaleReport confirmed) =>
        IsKnownUnconfirmedLegacySource(preserved.CaptureSource) && IsConfirmedPrint(confirmed.CaptureSource) &&
        !string.IsNullOrWhiteSpace(preserved.SaleId) &&
        string.Equals(preserved.SaleId, confirmed.SaleId, StringComparison.Ordinal) &&
        !string.IsNullOrWhiteSpace(preserved.PharmacyId) &&
        string.Equals(preserved.PharmacyId, confirmed.PharmacyId, StringComparison.Ordinal) &&
        preserved.SourceDocumentId is > 0 && preserved.SourceDocumentId == confirmed.SourceDocumentId;
}
