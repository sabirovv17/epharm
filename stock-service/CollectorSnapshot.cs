using Epharm.StockService.Source;

namespace Epharm.StockService;

public sealed record CollectorStockRow(
    string SourceId, long PartId, string Name, string? ManufacturerBarcode,
    string? Barcode, decimal Quantity, decimal? Price, DateOnly? ExpiryDate,
    string? Series, string? Unit);

public sealed record CollectorSnapshot(
    string HqPharmacyId, DateTimeOffset SourceObservedAt, int RowCount,
    IReadOnlyList<CollectorStockRow>? Items)
{
    public const int MaxRows = 20_000;

    public string? Validate(CollectorIdentity identity, DateTimeOffset now)
    {
        if (!string.Equals(HqPharmacyId, identity.HqPharmacyId, StringComparison.Ordinal))
            return "pharmacy_identity_mismatch";
        if (Items is null || RowCount != Items.Count || RowCount is < 0 or > MaxRows)
            return "invalid_row_count";
        if (SourceObservedAt == default || SourceObservedAt > now.AddSeconds(30) ||
            SourceObservedAt < now.AddMinutes(-10))
            return "invalid_observation_time";
        foreach (var row in Items)
        {
            if (row is null || string.IsNullOrWhiteSpace(row.SourceId) || row.SourceId.Length > 256 ||
                row.PartId <= 0 || string.IsNullOrWhiteSpace(row.Name) || row.Name.Length > 1000 ||
                row.ManufacturerBarcode?.Length > 128 || row.Barcode?.Length > 128 ||
                row.Series?.Length > 256 || row.Unit?.Length > 64 || row.Price < 0)
                return "invalid_stock_row";
        }
        return null;
    }

    public IReadOnlyList<SourceStock> ToSourceStocks(long profileId) =>
        Items!.Select(row => new SourceStock(
            profileId, row.SourceId, row.PartId, row.Name,
            row.ManufacturerBarcode, row.Barcode, row.Quantity, row.Price,
            row.ExpiryDate?.ToDateTime(TimeOnly.MinValue), row.Series, row.Unit)).ToArray();
}
