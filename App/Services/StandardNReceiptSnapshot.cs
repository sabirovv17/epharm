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
    decimal DiscountPercent);

public sealed record StandardNReceiptSnapshot(
    long DocumentId,
    long SessionId,
    string? WorkstationName,
    DateTimeOffset? OpenedAt,
    StandardNActivePharmacist Pharmacist,
    IReadOnlyList<StandardNReceiptLine> Lines);
