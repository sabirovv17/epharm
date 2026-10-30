using System;
using System.Collections.Generic;

namespace CustomerDisplay.Services;

public sealed record StandardNActivePharmacist(string Id, string? Name, long? SessionId);

public sealed record StandardNProductInfo(
    int PartId,
    string? Barcode,
    string? Name,
    decimal Price,
    string Source);

public sealed record StandardNReceiptLine(
    long LineId,
    int PartId,
    string? Barcode,
    string Name,
    decimal Price,
    decimal Qty,
    decimal DiscountPercent)
{
    public long? SourceDocumentId { get; init; }
    public decimal? SourceTotal { get; init; }
    public decimal? SignedQuantity { get; init; }
    public decimal? SignedAmount { get; init; }
    public decimal? SignedPrice { get; init; }
}

public sealed record StandardNReceiptDocument(
    long DocumentId,
    long SessionId,
    long? LinkId,
    int Status,
    decimal SignedTotal,
    string? DeviceNumber,
    long? FiscalNumber,
    long? FiscalShift,
    IReadOnlyList<StandardNReceiptLine> Lines)
{
    public bool FinalDetailsRead { get; init; }
}

public sealed record StandardNReceiptSnapshot(
    long DocumentId,
    long SessionId,
    string? WorkstationName,
    DateTimeOffset? OpenedAt,
    StandardNActivePharmacist Pharmacist,
    IReadOnlyList<StandardNReceiptLine> Lines)
{
    public long? LinkId { get; init; }
    public IReadOnlyList<StandardNReceiptDocument>? Documents { get; init; }
}

// Private on-device observation, never an authorization token or a public sale payload.
public sealed record StandardNObservedReceiptGroup(
    int SchemaVersion,
    string PharmacyId,
    StandardNReceiptSnapshot Snapshot,
    string CheckoutSessionId,
    string PharmacistId,
    string? PharmacistName,
    bool PrintObserved,
    IReadOnlyList<long> QueuedDocumentIds)
{
    public StandardNReceiptPrintCue? PendingPrint { get; init; }
    public IReadOnlyDictionary<long, DateTimeOffset> PrintDocumentTimes { get; init; } = new Dictionary<long, DateTimeOffset>();
    public IReadOnlyList<long> ResolvedEmptyDocumentIds { get; init; } = Array.Empty<long>();
    public IReadOnlyList<long> ObservedActiveDocumentIds { get; init; } = Array.Empty<long>();
    public IReadOnlyList<string> AnchoredPrintCueIds { get; init; } = Array.Empty<string>();
    public IReadOnlyList<long> InitiallyPostedDocumentIds { get; init; } = Array.Empty<long>();
}

public sealed record StandardNReceiptPrintCue(DateTimeOffset ObservedAt, IReadOnlyList<long> CandidateDocumentIds, string CueId);
