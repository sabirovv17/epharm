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
    // Only an adapter with a proven native unit contract may set full_package.
    public string UnitSemantics { get; init; } = "unknown";
    public string? HumanQuantity { get; init; }
    public long? ObservedSourceLineId { get; init; }
    public string? LineLinkSource { get; init; }
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
    public int DocumentType { get; init; } = 3;
    public long? ParentDocumentId { get; init; }
    public DateTimeOffset? PostedAt { get; init; }
    public DateTimeOffset? CommittedAt { get; init; }
    public StandardNOriginalReceipt? OriginalReceipt { get; init; }
    public bool FinalLineLinkRequired { get; init; }
}

public sealed record StandardNOriginalReceipt(long DocumentId, int DocumentType, int Status,
    string? DeviceNumber, long? FiscalNumber, long? FiscalShift);

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
    public string? WorkstationId { get; init; }
    // Provenance of the exact connection used to read this snapshot; absent in legacy observations.
    public string? ObservedSourceNamespace { get; init; }
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
    // Captured once; legacy observations without source provenance are never auto-archived.
    public string? SourceNamespace { get; init; }
    public StandardNReceiptPrintCue? PendingPrint { get; init; }
    public IReadOnlyDictionary<long, DateTimeOffset> PrintDocumentTimes { get; init; } = new Dictionary<long, DateTimeOffset>();
    public IReadOnlyList<long> ResolvedEmptyDocumentIds { get; init; } = Array.Empty<long>();
    public IReadOnlyList<long> ObservedActiveDocumentIds { get; init; } = Array.Empty<long>();
    public IReadOnlyList<string> AnchoredPrintCueIds { get; init; } = Array.Empty<string>();
    public IReadOnlyList<long> InitiallyPostedDocumentIds { get; init; } = Array.Empty<long>();
}

public sealed record StandardNReceiptPrintCue(DateTimeOffset ObservedAt, IReadOnlyList<long> CandidateDocumentIds, string CueId);
