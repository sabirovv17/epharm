using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ReceiptCaptureTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-receipt-tests-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void FirebirdDocumentIdProducesStableBoundedSaleId()
    {
        var first = ReceiptSaleId.Create("pharmacy-1", 12345, "session-a");
        var retry = ReceiptSaleId.Create("pharmacy-1", 12345, "session-b");
        var otherPharmacy = ReceiptSaleId.Create("pharmacy-2", 12345, "session-a");

        Assert.Equal(first, retry);
        Assert.NotEqual(first, otherPharmacy);
        Assert.StartsWith("sale_", first);
        Assert.True(first.Length <= 64);
    }

    [Fact]
    public void PendingReceiptIsRecoveredAndDeletedOnlyAfterAck()
    {
        var renderer = new FakeRenderer();
        var store = new ReceiptArtifactStore(Path.Combine(_root, "receipts"), renderer);
        var sale = Sale("sale_ack_test");

        store.SaveDraft(sale);
        Assert.True(store.Complete(sale));

        var pending = Path.Combine(_root, "receipts", "pending", sale.SaleId);
        Assert.True(File.Exists(Path.Combine(pending, "sale.json")));
        Assert.True(File.Exists(Path.Combine(pending, "receipt.png")));

        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(1, store.RecoverPending(outbox));
        var queued = Assert.Single(outbox.DequeueReady());
        Assert.Equal(sale.SaleId, queued.Id);
        Assert.Equal("sale", queued.Kind);
        var recovered = JsonSerializer.Deserialize<SaleReport>(queued.Payload, EpharmJson.Options);
        Assert.Equal(91234, recovered?.SourceDocumentId);

        // До ACK папка существует. Удаление вызывается только callback-ом успешного flusher-а.
        Assert.True(Directory.Exists(pending));
        store.DeletePending(sale.SaleId);
        Assert.False(Directory.Exists(pending));
    }

    [Fact]
    public void CorruptPendingReceiptIsQuarantinedWithoutBlockingOthers()
    {
        var receiptRoot = Path.Combine(_root, "receipts");
        var corrupt = Path.Combine(receiptRoot, "pending", "sale_corrupt");
        Directory.CreateDirectory(corrupt);
        File.WriteAllText(Path.Combine(corrupt, "sale.json"), "{broken");

        var store = new ReceiptArtifactStore(receiptRoot, new FakeRenderer());
        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(0, store.RecoverPending(outbox));
        Assert.False(Directory.Exists(corrupt));
        Assert.Single(Directory.EnumerateDirectories(Path.Combine(receiptRoot, "quarantine")));
    }

    [Fact]
    public void CrashStagingDirectoryIsPromotedAndRecovered()
    {
        var receiptRoot = Path.Combine(_root, "receipts");
        var store = new ReceiptArtifactStore(receiptRoot, new FakeRenderer());
        var sale = Sale("sale_staging_test");
        var staging = Path.Combine(receiptRoot, "pending", ".sale_staging_test.tmp-crash");
        Directory.CreateDirectory(staging);
        File.WriteAllText(
            Path.Combine(staging, "sale.json"),
            JsonSerializer.Serialize(sale, EpharmJson.Options));

        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(1, store.RecoverPending(outbox));
        Assert.False(Directory.Exists(staging));
        Assert.True(Directory.Exists(Path.Combine(receiptRoot, "pending", sale.SaleId)));
        Assert.Equal(sale.SaleId, Assert.Single(outbox.DequeueReady()).Id);
    }

    private static SaleReport Sale(string id) => new()
    {
        SaleId = id,
        PharmacyId = "pharmacy-1",
        SessionId = "session-1",
        SourceDocumentId = 91234,
        CaptureSource = "standardn-firebird-close",
        ArtifactFormat = "png",
        TotalAmount = 1500,
        PrintedAt = DateTimeOffset.UtcNow,
        Items =
        {
            new SaleReportItem
            {
                Sku = "42",
                Barcode = "4870000000001",
                Name = "Тестовый товар",
                Qty = 1,
                Price = 1500,
                Total = 1500,
            },
        },
    };

    public void Dispose()
    {
        try { if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true); }
        catch { }
    }

    private sealed class FakeRenderer : IReceiptArtifactRenderer
    {
        public void Render(SaleReport sale, string outputPath)
        {
            File.WriteAllBytes(outputPath, new byte[] { 0x89, 0x50, 0x4e, 0x47 });
        }
    }
}
