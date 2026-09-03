using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ReceiptCaptureTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-receipt-tests-" + Guid.NewGuid().ToString("N"));
    private DateTimeOffset _now = DateTimeOffset.UtcNow;

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
    public void ExactPdfIsCopiedByteForByteAndEnrichesQueuedSale()
    {
        var sale = Sale("sale_exact_pdf");
        var store = CreateStore();
        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        outbox.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(sale, EpharmJson.Options));

        var completed = store.Complete(sale);
        Assert.Equal(FiscalReceiptCaptureStatus.WaitingForAuthoritativeSource, completed.Status);
        var original = PublishPdf(sale);

        var refresh = store.RefreshFiscalArtifacts(outbox);

        Assert.Equal(1, refresh.Captured);
        var pending = Pending(sale);
        var storedPath = Path.Combine(pending, "fiscal-receipt.pdf");
        Assert.Equal(original, File.ReadAllBytes(storedPath));
        Assert.False(File.Exists(Path.Combine(pending, "receipt.png")));
        Assert.False(File.Exists(Path.Combine(_root, "fiscal-inbox", $"doc-{sale.SourceDocumentId}.pdf")));
        Assert.False(File.Exists(Path.Combine(_root, "fiscal-inbox", $"doc-{sale.SourceDocumentId}.fiscal.json")));

        var metadata = JsonSerializer.Deserialize<StoredFiscalReceiptMetadata>(
            File.ReadAllText(Path.Combine(pending, "fiscal-receipt.json")), EpharmJson.Options);
        Assert.NotNull(metadata);
        Assert.Equal(Sha(original), metadata!.Sha256);
        Assert.Equal("ofd-api", metadata.Manifest.SourceSystem);

        // Fiscal enrichment is independent, so an in-flight initial sale cannot erase it.
        var queued = outbox.DequeueReady();
        Assert.Equal(2, queued.Count);
        var fiscalQueued = Assert.Single(queued, item => item.Kind == "fiscal-sale");
        var enriched = JsonSerializer.Deserialize<SaleReport>(fiscalQueued.Payload, EpharmJson.Options);
        Assert.Equal("pdf", enriched?.ArtifactFormat);
        Assert.Equal(Sha(original), enriched?.ArtifactSha256);
        Assert.Equal("ofd-api", enriched?.ArtifactSource);
        Assert.Equal("fiscal-777", enriched?.FiscalId);
        Assert.Equal("sign-777", enriched?.FiscalSign);
        Assert.Equal("kkm-registration-1", enriched?.CashRegisterRegistrationNumber);
    }

    [Fact]
    public void ExactPngIsAcceptedWithoutReRendering()
    {
        var sale = Sale("sale_exact_png");
        var store = CreateStore();
        store.Complete(sale);
        var original = PublishPng(sale);

        Assert.Equal(1, store.RefreshFiscalArtifacts().Captured);

        var storedPath = Path.Combine(Pending(sale), "fiscal-receipt.png");
        Assert.Equal(original, File.ReadAllBytes(storedPath));
        Assert.Equal("png", ReadPendingSale(sale).ArtifactFormat);
    }

    [Fact]
    public void ManifestForAnotherPharmacyIsRejectedAndNoArtifactIsClaimed()
    {
        var sale = Sale("sale_wrong_pharmacy");
        var store = CreateStore();
        store.Complete(sale);
        PublishPdf(sale, manifest => manifest.PharmacyId = "pharmacy-2");

        var refresh = store.RefreshFiscalArtifacts();

        Assert.Equal(1, refresh.Rejected);
        Assert.False(File.Exists(Path.Combine(Pending(sale), "fiscal-receipt.pdf")));
        Assert.Null(ReadPendingSale(sale).ArtifactFormat);
        Assert.Contains("pharmacyId", File.ReadAllText(Path.Combine(Pending(sale), "fiscal-capture-status.json")));
    }

    [Fact]
    public void TamperedArtifactIsRejectedBySha256()
    {
        var sale = Sale("sale_bad_hash");
        var store = CreateStore();
        store.Complete(sale);
        PublishPdf(sale, manifest => manifest.Sha256 = new string('0', 64));

        var refresh = store.RefreshFiscalArtifacts();

        Assert.Equal(1, refresh.Rejected);
        Assert.False(File.Exists(Path.Combine(Pending(sale), "fiscal-receipt.pdf")));
        Assert.Contains("SHA-256", File.ReadAllText(Path.Combine(Pending(sale), "fiscal-capture-status.json")));
    }

    [Fact]
    public void UntrustedProducerIsRejectedEvenWhenArtifactAndHashAreValid()
    {
        var sale = Sale("sale_untrusted_source");
        var store = CreateStore();
        store.Complete(sale);
        PublishPdf(sale, manifest => manifest.SourceSystem = "cashier-renderer");

        var refresh = store.RefreshFiscalArtifacts();

        Assert.Equal(1, refresh.Rejected);
        Assert.False(File.Exists(Path.Combine(Pending(sale), "fiscal-receipt.pdf")));
        Assert.Contains("trusted-source", File.ReadAllText(Path.Combine(Pending(sale), "fiscal-capture-status.json")));
    }

    [Fact]
    public void TruncatedPdfIsRejectedEvenWhenHashMatches()
    {
        var sale = Sale("sale_truncated_pdf");
        var store = CreateStore();
        store.Complete(sale);
        PublishPdf(sale, artifact: Encoding.ASCII.GetBytes("%PDF-1.4\nmissing final marker and body"));

        var refresh = store.RefreshFiscalArtifacts();

        Assert.Equal(1, refresh.Rejected);
        Assert.Contains("final marker", File.ReadAllText(Path.Combine(Pending(sale), "fiscal-capture-status.json")));
    }

    [Fact]
    public void ExactCopyRequiresFiscalAckAndFullPostAckRetentionBeforeDeletion()
    {
        var completeSale = Sale("sale_stored_ack", sourceDocumentId: 91235);
        var store = CreateStore(completedRetentionHours: 24);
        store.Complete(completeSale);
        PublishPdf(completeSale);
        Assert.Equal(1, store.RefreshFiscalArtifacts().Captured);
        store.MarkSaleDelivered(completeSale.SaleId);
        Assert.True(Directory.Exists(Pending(completeSale)));

        _now = _now.AddDays(7);
        Assert.Equal(0, store.CleanupCompletedArtifacts());
        store.MarkFiscalMetadataDelivered(completeSale.SaleId, ReadPendingSale(completeSale).ArtifactSha256!);
        Assert.True(Directory.Exists(Pending(completeSale)));

        _now = _now.AddHours(23);
        Assert.Equal(0, store.CleanupCompletedArtifacts());
        _now = _now.AddHours(2);
        Assert.Equal(1, store.CleanupCompletedArtifacts());
        Assert.False(Directory.Exists(Pending(completeSale)));
    }

    [Fact]
    public void DeliveredSaleWithoutFiscalSourceExpiresAfterBoundedWaitWindow()
    {
        var sale = Sale("sale_waiting_expiry");
        var store = CreateStore();
        store.Complete(sale);
        store.MarkSaleDelivered(sale.SaleId);

        _now = _now.AddHours(47);
        Assert.Equal(0, store.CleanupCompletedArtifacts());
        Assert.True(Directory.Exists(Pending(sale)));

        _now = _now.AddHours(2);
        Assert.Equal(1, store.CleanupCompletedArtifacts());
        Assert.False(Directory.Exists(Pending(sale)));
    }

    [Fact]
    public void CorruptPendingReceiptIsQuarantinedWithoutBlockingOthers()
    {
        var receiptRoot = Path.Combine(_root, "receipts");
        var corrupt = Path.Combine(receiptRoot, "pending", "sale_corrupt");
        Directory.CreateDirectory(corrupt);
        File.WriteAllText(Path.Combine(corrupt, "sale.json"), "{broken");

        var store = CreateStore();
        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(0, store.RecoverPending(outbox));
        Assert.False(Directory.Exists(corrupt));
        Assert.Single(Directory.EnumerateDirectories(Path.Combine(receiptRoot, "quarantine")));
    }

    [Fact]
    public void DamagedStoredArtifactIsQuarantinedOnRecovery()
    {
        var sale = Sale("sale_damaged_stored");
        var store = CreateStore();
        store.Complete(sale);
        PublishPdf(sale);
        Assert.Equal(1, store.RefreshFiscalArtifacts().Captured);
        File.AppendAllText(Path.Combine(Pending(sale), "fiscal-receipt.pdf"), "tampered");

        var restarted = CreateStore();
        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(0, restarted.RecoverPending(outbox));
        Assert.False(Directory.Exists(Pending(sale)));
        Assert.Single(Directory.EnumerateDirectories(Path.Combine(_root, "receipts", "quarantine")));
    }

    [Fact]
    public void StoredFiscalMetadataIdentityMismatchIsQuarantinedOnRecovery()
    {
        var sale = Sale("sale_damaged_metadata");
        var store = CreateStore();
        store.Complete(sale);
        PublishPdf(sale);
        Assert.Equal(1, store.RefreshFiscalArtifacts().Captured);

        var metadataPath = Path.Combine(Pending(sale), "fiscal-receipt.json");
        var metadata = JsonSerializer.Deserialize<StoredFiscalReceiptMetadata>(
            File.ReadAllText(metadataPath), EpharmJson.Options)!;
        metadata.Manifest.TotalAmount++;
        File.WriteAllText(metadataPath, JsonSerializer.Serialize(metadata, EpharmJson.Options));

        var restarted = CreateStore();
        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(0, restarted.RecoverPending(outbox));
        Assert.False(Directory.Exists(Pending(sale)));
        Assert.Single(Directory.EnumerateDirectories(Path.Combine(_root, "receipts", "quarantine")));
    }

    [Fact]
    public void LegacyReconstructedPngIsRemovedAndNeverClaimedAsFiscal()
    {
        var sale = Sale("sale_legacy_png");
        sale.ArtifactFormat = "png";
        var pending = Pending(sale);
        Directory.CreateDirectory(pending);
        File.WriteAllText(Path.Combine(pending, "sale.json"), JsonSerializer.Serialize(sale, EpharmJson.Options));
        File.WriteAllBytes(Path.Combine(pending, "receipt.png"), new byte[] { 0x89, 0x50, 0x4e, 0x47 });

        var store = CreateStore();
        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(1, store.RecoverPending(outbox));

        Assert.False(File.Exists(Path.Combine(pending, "receipt.png")));
        Assert.True(File.Exists(Path.Combine(pending, "legacy-non-fiscal-removed.txt")));
        Assert.Null(ReadPendingSale(sale).ArtifactFormat);
    }

    [Fact]
    public void CrashStagingDirectoryIsPromotedAndRecovered()
    {
        var receiptRoot = Path.Combine(_root, "receipts");
        var store = CreateStore();
        var sale = Sale("sale_staging_test");
        var staging = Path.Combine(receiptRoot, "pending", ".sale_staging_test.tmp-crash");
        Directory.CreateDirectory(staging);
        File.WriteAllText(
            Path.Combine(staging, "sale.json"),
            JsonSerializer.Serialize(sale, EpharmJson.Options));

        var outbox = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        Assert.Equal(1, store.RecoverPending(outbox));
        Assert.False(Directory.Exists(staging));
        Assert.True(Directory.Exists(Pending(sale)));
        Assert.Equal(sale.SaleId, Assert.Single(outbox.DequeueReady()).Id);
    }

    private ReceiptArtifactStore CreateStore(int completedRetentionHours = 24)
    {
        var source = new FiscalReceiptInboxSource(
            Path.Combine(_root, "fiscal-inbox"),
            new[] { "standardn-kkm-sdk", "ofd-api" });
        return new ReceiptArtifactStore(
            Path.Combine(_root, "receipts"),
            source,
            activeRetentionDays: 2,
            completedRetentionHours: completedRetentionHours,
            utcNow: () => _now);
    }

    private byte[] PublishPdf(
        SaleReport sale,
        Action<FiscalReceiptManifest>? mutate = null,
        byte[]? artifact = null)
    {
        artifact ??= Encoding.ASCII.GetBytes(
            "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n");
        return PublishArtifact(sale, "pdf", artifact, mutate);
    }

    private byte[] PublishPng(
        SaleReport sale,
        Action<FiscalReceiptManifest>? mutate = null)
    {
        var artifact = Convert.FromBase64String(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        return PublishArtifact(sale, "png", artifact, mutate);
    }

    private byte[] PublishArtifact(
        SaleReport sale,
        string extension,
        byte[] artifact,
        Action<FiscalReceiptManifest>? mutate)
    {
        var fileName = $"doc-{sale.SourceDocumentId}.{extension}";
        var inbox = Path.Combine(_root, "fiscal-inbox");
        Directory.CreateDirectory(inbox);
        File.WriteAllBytes(Path.Combine(inbox, fileName), artifact);
        var manifest = new FiscalReceiptManifest
        {
            SourceSystem = "ofd-api",
            PharmacyId = sale.PharmacyId,
            SaleId = sale.SaleId,
            SourceDocumentId = sale.SourceDocumentId,
            FiscalDocumentNumber = "fiscal-777",
            FiscalSign = "sign-777",
            CashRegisterRegistrationNumber = "kkm-registration-1",
            OfdName = "OFD test",
            Shift = "42",
            Cashier = "Cashier 1",
            PrintedAt = sale.PrintedAt,
            TotalAmount = sale.TotalAmount,
            DocumentFile = fileName,
            Sha256 = Sha(artifact),
        };
        mutate?.Invoke(manifest);
        File.WriteAllText(
            Path.Combine(inbox, $"doc-{sale.SourceDocumentId}.fiscal.json"),
            JsonSerializer.Serialize(manifest, EpharmJson.Options));
        return artifact;
    }

    private string Pending(SaleReport sale) =>
        Path.Combine(_root, "receipts", "pending", sale.SaleId);

    private SaleReport ReadPendingSale(SaleReport sale) =>
        JsonSerializer.Deserialize<SaleReport>(
            File.ReadAllText(Path.Combine(Pending(sale), "sale.json")), EpharmJson.Options)!;

    private static string Sha(byte[] bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private SaleReport Sale(string id, long sourceDocumentId = 91234) => new()
    {
        SaleId = id,
        PharmacyId = "pharmacy-1",
        SessionId = "session-1",
        SourceDocumentId = sourceDocumentId,
        CaptureSource = "standardn-firebird-close",
        TotalAmount = 1500,
        PrintedAt = _now,
        Items =
        {
            new SaleReportItem
            {
                Sku = "42",
                Barcode = "4870000000001",
                Name = "Test product",
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
}
