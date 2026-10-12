namespace Epharm.StockService.Source;

public sealed record SourcePharmacy(
    long ProfileId,
    string Name,
    string City,
    string Address,
    string PharmacyNumber);

// Keep warehouse batches separate: product/part IDs can repeat with different
// series or expiry dates. The pair (ProfileId, SourceId) identifies a row.
public sealed record SourceStock(
    long ProfileId,
    string SourceId,
    long PartId,
    string Name,
    string? ManufacturerBarcode,
    string? Barcode,
    decimal Quantity,
    decimal? Price,
    DateTime? ExpiryDate,
    string? Series,
    string? Unit);
