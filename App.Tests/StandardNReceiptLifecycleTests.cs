using CustomerDisplay.Config;
using CustomerDisplay.Models;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using Xunit;

namespace CustomerDisplay.Core.Tests
{
    public sealed class StandardNReceiptLifecycleTests
    {
        [Fact]
        public void RealPrintQueueConflictDoesNotEscapeOrClearCurrentCartAndKeepsDurablePending()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(100, 10));
            var legacy = LegacySale(window, 100);
            var originalPayload = JsonSerializer.Serialize(legacy, EpharmJson.Options);
            window.Queue.Enqueue(legacy.SaleId, "sale", originalPayload);
            window.Queue.Reschedule(legacy.SaleId, 0);
            using (var connection = new SqliteConnection("Data Source=" + Path.Combine(window.StorageRoot, "outbox.db")))
            {
                connection.Open();
                using var command = connection.CreateCommand();
                command.CommandText = @"INSERT INTO outbox_legacy_sale_evidence
                    SELECT id,kind,'conflicting-evidence',created_at,attempts,next_retry_at,'synthetic' FROM outbox;";
                command.ExecuteNonQuery();
            }
            var sessionRestarts = window.SessionRestarts;

            Assert.Null(Record.Exception(window.Print));

            Assert.Equal(100, window.DocumentId);
            Assert.Null(window.FinalizedDocumentId);
            Assert.Single(window.ReceiptItems);
            Assert.Empty(window.Sales);
            Assert.Equal(sessionRestarts, window.SessionRestarts);
            Assert.DoesNotContain(window.Messages, message => message.StartsWith("Чек поставлен в гарантированную очередь:"));
            Assert.Contains(window.Messages, message => message.Contains("не поставлен в очередь") && message.Contains("сохранён"));
            var pending = Path.Combine(window.StorageRoot, "receipts", "pending", legacy.SaleId, "sale.json");
            var durable = JsonSerializer.Deserialize<SaleReport>(File.ReadAllText(pending), EpharmJson.Options)!;
            Assert.Equal("standardn-print-log", durable.CaptureSource);
            Assert.Equal(100, durable.SourceDocumentId);
            Assert.Equal(0, window.Artifacts.RecoverPending(window.Queue));
            Assert.Equal(100, window.DocumentId); // A background retry cannot pretend the failed print was finalized.
        }

        [Fact]
        public void RealPrintHardPreservationFailureDoesNotQueueOrClaimDurableConfirmedCopy()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(100, 10));
            var legacy = LegacySale(window, 100);
            window.Artifacts.Complete(legacy);
            var pending = Path.Combine(window.StorageRoot, "receipts", "pending", legacy.SaleId, "sale.json");
            var original = File.ReadAllBytes(pending);
            Directory.CreateDirectory(Path.Combine(window.StorageRoot, "receipts", "legacy-unconfirmed", legacy.SaleId));
            legacy.CaptureSource = "standardn-active";
            window.Artifacts.SaveDraft(legacy);
            window.KeepDraft(legacy.SaleId);
            var active = Path.Combine(window.StorageRoot, "receipts", "active", legacy.SaleId, "sale.json");
            var draft = File.ReadAllBytes(active);

            Assert.Null(Record.Exception(window.Print));

            Assert.Empty(window.Sales);
            Assert.Equal(100, window.DocumentId);
            Assert.Null(window.FinalizedDocumentId);
            Assert.Equal(legacy.SaleId, window.ActiveDraftId);
            Assert.Single(window.ReceiptItems);
            Assert.Equal(original, File.ReadAllBytes(pending));
            Assert.Equal(draft, File.ReadAllBytes(active));
            Assert.DoesNotContain(window.Messages, message => message.StartsWith("Чек поставлен в гарантированную очередь:"));
            Assert.Contains(window.Messages, message => message.Contains("не сохранён") && message.Contains("корзина"));
        }

        [Fact]
        public void RealPrintWithNormallyMissingFiscalSourceStillQueuesStructuredSaleAndClearsDisplay()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(100, 10));

            window.Print();

            Assert.Equal(100, Assert.Single(window.Sales).DocumentId);
            Assert.Empty(window.ReceiptItems);
            Assert.Null(window.DocumentId);
            Assert.Equal(100, window.FinalizedDocumentId);
            Assert.Contains(window.Messages, message => message.Contains("Чек поставлен в гарантированную очередь:") &&
                message.Contains("ожидается оригинал ККМ/OFD"));
        }

        private static SaleReport LegacySale(MainWindow window, long document) => new()
        {
            SaleId = window.StableSaleId(document), PharmacyId = "synthetic-pharmacy", SourceDocumentId = document,
            CaptureSource = "standardn-document-switch", TotalAmount = 100,
            Items = [new SaleReportItem { Sku = "10", Qty = 1, Price = 100, Total = 100 }],
        };

        [Fact]
        public void UnpaidDocumentSwitchPreservesDraftWithoutManufacturingSale()
        {
            using var window = new MainWindow();
            window.Observe(new StandardNReceiptSnapshot(23639, 1713, "CASH-1", null,
                new("synthetic-cashier", null, 1713), new[] {
                    new StandardNReceiptLine(61030, 92715, "3838957010332", "Synthetic spray", 1690m, 2m, 0m) }));
            window.KeepDraft("draft-23639");

            window.Observe(new StandardNReceiptSnapshot(23500, 1713, "CASH-1", null,
                new("synthetic-cashier", null, 1713), new[] {
                    new StandardNReceiptLine(60000, 84993, "8809052586843", "Synthetic held item", 455m, 1m, 0m) }));

            Assert.Empty(window.Sales);
            Assert.Equal(0, window.FinalizationCalls);
            Assert.Equal(23500, window.DocumentId);
            Assert.Equal(455m, window.DisplayTotal);
            Assert.Equal(84993, Assert.Single(window.ReceiptItems).PartId);
            Assert.Contains("draft-23639", window.SavedDrafts);
            Assert.Null(window.ActiveDraftId);
            Assert.Null(window.FinalizedDocumentId);
            Assert.Equal(0, window.DiscardedDrafts);
            Assert.Equal(1, window.PopupClears);
            Assert.Equal(1, window.SessionRestarts);
        }

        [Fact]
        public void UnpaidDocumentDisappearancePreservesDraftWithoutManufacturingSale()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(23639, 92715));
            window.KeepDraft("draft-23639");

            window.Observe(null);
            window.Observe(null);

            Assert.Empty(window.Sales);
            Assert.Equal(0, window.FinalizationCalls);
            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Null(window.DocumentId);
            Assert.Null(window.FinalizedDocumentId);
            Assert.Contains("draft-23639", window.SavedDrafts);
            Assert.Null(window.ActiveDraftId);
            Assert.Equal(0, window.DiscardedDrafts);
            Assert.Equal(1, window.PopupClears);
            Assert.Equal(1, window.SessionRestarts);
        }

        [Fact]
        public void LogOnlyCartWithoutDiscoveredDocumentDoesNotStayOnCustomerScreenOrBecomeSale()
        {
            using var window = new MainWindow();
            window.AddLogItem(10);

            window.Observe(null);
            Assert.Single(window.ReceiptItems);
            window.Observe(null);

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Empty(window.Sales);
            Assert.Equal(0, window.DiscardedDrafts);
        }

        [Fact]
        public void ConfirmedPrintImmediatelyClearsTotalAndCannotBeRepopulatedBySameDocument()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);

            window.Print();

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Null(window.DocumentId);
            Assert.Equal(100, Assert.Single(window.Sales).DocumentId);

            window.Observe(receipt);
            window.Print();

            Assert.Empty(window.ReceiptItems);
            Assert.Single(window.Sales);
        }

        [Fact]
        public void DelayedReadBeforePrintCannotResurrectPrintedCart()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            var inFlightRevision = window.ReadRevision;
            window.Print();

            window.CompleteRead(inFlightRevision, receipt);

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Single(window.Sales);
        }

        [Fact]
        public void UnboundPrintCannotResurrectOldDocumentOrManufactureSecondSale()
        {
            using var window = new MainWindow();
            window.AddLogItem(10);
            window.Print();

            window.Observe(Receipt(100, 10));
            window.Observe(Receipt(100, 10));
            window.Observe(null);
            window.Observe(null);

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Single(window.Sales);
        }

        [Fact]
        public void PrintBeforeAnyObservedRowsCannotPopulateAlreadyPrintedDatabaseDocument()
        {
            using var window = new MainWindow();
            window.Print();

            window.Observe(Receipt(100, 10));
            window.Observe(null);
            window.Observe(null);

            Assert.Empty(window.ReceiptItems);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void NewLogScanDoesNotReleaseUnboundPrintDatabaseBarrier()
        {
            using var window = new MainWindow();
            window.AddLogItem(10);
            window.Print();
            window.AddLogItem(20);

            window.Observe(Receipt(100, 10));

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Null(window.DocumentId);
            Assert.Single(window.Sales);
        }

        [Fact]
        public void UnboundPrintBoundaryRequiresConsecutiveSuccessfulEmptyReads()
        {
            using var window = new MainWindow();
            window.AddLogItem(10);
            window.Print();
            window.Observe(null);
            window.DatabaseFailed();
            window.Observe(null);
            window.Observe(Receipt(100, 10));

            Assert.Empty(window.ReceiptItems);
            Assert.Single(window.Sales);

            window.Observe(null);
            window.Observe(null);
            window.Observe(Receipt(101, 20));

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Equal(101, window.DocumentId);
            Assert.Single(window.Sales);
        }

        [Fact]
        public void DelayedEmptyReadBeforeNewScanCannotClearNewCart()
        {
            using var window = new MainWindow();
            window.Observe(null);
            var inFlightRevision = window.ReadRevision;
            window.AddLogItem(20);

            window.CompleteRead(inFlightRevision, null);

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void UnresolvedWorkstationNeverClearsLogOnlyCart()
        {
            using var window = new MainWindow();
            window.AddLogItem(20);

            window.ObserveMissingForSessions(new[] { "CASH-2" }, "CASH-2");
            window.ObserveMissingForSessions(new[] { "CASH-2" }, "CASH-2");

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void AmbiguousWorkstationBreaksPreviouslyConfirmedEmptySequence()
        {
            using var window = new MainWindow();
            window.AddLogItem(20);
            window.ObserveMissingForSessions(new[] { "CASH-1" }, "CASH-1");

            window.ObserveMissingForSessions(new[] { "CASH-1", "DOMAIN\\CASH-1" }, "CASH-1");
            window.ObserveMissingForSessions(new[] { "CASH-1" }, "CASH-1");

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void DelayedReadBeforeDiscountCannotOverwriteNewDisplayedTotal()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            var inFlightRevision = window.ReadRevision;

            window.Discount(20m);
            window.CompleteRead(inFlightRevision, receipt);

            Assert.Equal(80m, window.DisplayTotal);
        }

        [Fact]
        public void RemovingAllRowsCancelsCartWithoutManufacturingSale()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(100, 10));

            window.Observe(Receipt(100));
            window.Observe(null);
            window.Observe(null);

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void DatabaseFailurePreservesLiveCartAndBreaksEmptyConfirmation()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(100, 10));
            window.Observe(null);

            window.DatabaseFailed();
            window.Observe(null);

            Assert.Equal(10, Assert.Single(window.ReceiptItems).PartId);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void SwitchingDocumentsDoesNotMixOldAndNewRowsOrDuplicatePrintedSale()
        {
            using var window = new MainWindow();
            window.Observe(Receipt(100, 10));
            window.Print();
            window.AddLogItem(20);

            window.Observe(Receipt(101, 20));
            window.Observe(Receipt(101, 20));

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Equal(101, window.DocumentId);
            Assert.Equal(100, Assert.Single(window.Sales).DocumentId);
        }

        [Theory]
        [InlineData(false)]
        [InlineData(true)]
        public void RealLogAndDatabaseUpdatesReplaceRenderedInstanceAndKeepRowsAndTotalInSync(bool database)
        {
            using var window = new MainWindow();
            var first = Item(10, "Resolved name", "4600000000012", 100m, 3m);
            var other = Item(20, "Other item", "4600000000029", 50m, 1m);
            Apply(window, database, first, other);
            var rendered = window.ReceiptItems.Single(item => item.PartId == 10);
            var order = window.ReceiptItems.Select(item => item.PartId).ToArray();
            Assert.Equal(350m, window.DisplayTotal);
            var replacements = new List<NotifyCollectionChangedEventArgs>();
            window.ReceiptItems.CollectionChanged += (_, change) =>
            {
                if (change.Action == NotifyCollectionChangedAction.Replace &&
                    change.NewItems!.Cast<ReceiptItem>().Any(item => item.PartId == 10)) replacements.Add(change);
            };

            Apply(window, database, Item(10, "Updated name", "4600000000036", 120m, 4m, 25m), other);

            var replacement = Assert.Single(replacements);
            var current = window.ReceiptItems.Single(item => item.PartId == 10);
            Assert.Same(rendered, Assert.Single(replacement.OldItems!.Cast<ReceiptItem>()));
            Assert.Same(current, Assert.Single(replacement.NewItems!.Cast<ReceiptItem>()));
            Assert.NotSame(rendered, current); // WPF must receive a different row to rebind plain properties.
            Assert.Equal((3m, 100m, 0m, "Resolved name", "4600000000012"),
                (rendered.Qty, rendered.Price, rendered.DiscountPercent, rendered.Name, rendered.Barcode));
            Assert.Equal((4m, 120m, 25m, "Updated name", "4600000000036"),
                (current.Qty, current.Price, current.DiscountPercent, current.Name, current.Barcode));
            Assert.Equal(480m, current.SubTotal);
            Assert.Equal(120m, current.DiscountAmount);
            Assert.Equal(360m, current.Total);
            Assert.Equal(360m.ToString("0.00"), current.TotalText);
            Assert.Equal(120m.ToString("0.00"), current.PriceText);
            Assert.Equal(410m, window.DisplayTotal);
            Assert.Equal(window.ReceiptItems.Sum(item => item.Total), window.DisplayTotal);
            Assert.Equal(order, window.ReceiptItems.Select(item => item.PartId).ToArray());

            replacements.Clear();
            Apply(window, database, Item(10, " ", null, 120m, 4m, 10m), other);
            var sparse = window.ReceiptItems.Single(item => item.PartId == 10);
            Assert.NotSame(current, sparse);
            Assert.Single(replacements);
            Assert.Equal("Updated name", sparse.Name);
            Assert.Equal("4600000000036", sparse.Barcode);
            Assert.Equal(25m, current.DiscountPercent); // Even price-only updates leave the rendered old row intact.
            Assert.Equal(432m, sparse.Total);
            Assert.Equal(482m, window.DisplayTotal);
        }

        [Theory]
        [InlineData(false)]
        [InlineData(true)]
        public void RealUpdateOfSecondExistingItemRebindsItsRowWithoutLosingOtherItems(bool database)
        {
            using var window = new MainWindow();
            var first = Item(10, "First", "4600000000012", 100m, 3m);
            var second = Item(20, "Second", "4600000000029", 50m, 1m);
            Apply(window, database, first, second);
            var rendered = window.ReceiptItems.Single(item => item.PartId == 20);

            Apply(window, database, first, Item(20, "Second", "4600000000029", 50m, 2m));

            var current = window.ReceiptItems.Single(item => item.PartId == 20);
            Assert.NotSame(rendered, current);
            Assert.Equal(1m, rendered.Qty);
            Assert.Equal(2m, current.Qty);
            Assert.Equal(100m, current.Total);
            Assert.Equal(400m, window.DisplayTotal);
            Assert.Equal(2, window.ReceiptItems.Count);
        }

        [Theory]
        [InlineData(false)]
        [InlineData(true)]
        public void RealQuantityDecreaseKeepsPreviousRowImmutableAndDoesNotManufactureASale(bool database)
        {
            using var window = new MainWindow();
            Apply(window, database, Item(10, "Item", "4600000000012", 100m, 4m, 10m));
            var rendered = Assert.Single(window.ReceiptItems);

            Apply(window, database, Item(10, "Item", "4600000000012", 100m, 2m, 10m));

            var current = Assert.Single(window.ReceiptItems);
            Assert.NotSame(rendered, current);
            Assert.Equal(4m, rendered.Qty);
            Assert.Equal(2m, current.Qty);
            Assert.Equal(180m, window.DisplayTotal);
            Assert.Empty(window.Sales);
        }

        [Theory]
        [InlineData(0)]
        [InlineData(-1)]
        public void RealLogZeroOrNegativeQuantityRemovesRowAndUnknownRemovalDoesNotInsert(int quantity)
        {
            using var window = new MainWindow();
            Assert.True(window.ObserveLogLine(Item(10, "Item", "4600000000012", 100m, 3m)));
            var rendered = Assert.Single(window.ReceiptItems);

            Assert.False(window.ObserveLogLine(Item(10, "", null, 100m, quantity)));
            Assert.False(window.ObserveLogLine(Item(99, "Missing", null, 100m, quantity)));

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(3m, rendered.Qty);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Empty(window.Sales);
        }

        private static ReceiptItem Item(int part, string name, string? barcode, decimal price, decimal qty, decimal discount = 0m) =>
            new() { PartId = part, Name = name, Barcode = barcode, Price = price, Qty = qty, DiscountPercent = discount };

        private static void Apply(MainWindow window, bool database, params ReceiptItem[] rows)
        {
            if (database)
                window.Observe(new StandardNReceiptSnapshot(100, 1, "CASH-1", null, new("cashier-1", "Pharmacist", 1),
                    rows.Select(item => new StandardNReceiptLine(item.PartId, item.PartId, item.Barcode, item.Name,
                        item.Price, item.Qty, item.DiscountPercent)).ToList()));
            else
                foreach (var row in rows) window.ObserveLogLine(row);
        }

        private static StandardNReceiptSnapshot Receipt(long document, params int[] partIds) => new(
            document, 1, "CASH-1", null, new("cashier-1", "Pharmacist", 1),
            partIds.Select(part => new StandardNReceiptLine(part, part, "4601164003157", "Item", 100m, 1m, 0m)).ToList());
    }
}

namespace CustomerDisplay
{
    // Compile the real reconciliation partial without WPF. Only its UI/IO boundaries are fakes.
    public partial class MainWindow : IDisposable
    {
        private readonly EpharmConfig _posmConfig = new() { PharmacyId = "synthetic-pharmacy", PharmacistId = "synthetic-person" };
        private readonly UnavailableReceiptDatabase? _standardNDb = null;
        private readonly ImmediateDispatcher Dispatcher = new();
        private readonly string _storageRoot = Path.Combine(Path.GetTempPath(), "epharm-real-finalizer-" + Guid.NewGuid().ToString("N"));
        private readonly OfflineOutbox _testOutbox;
        private readonly SaleReporter _saleReporter;
        private ReceiptArtifactStore? _receiptArtifacts;
        private CheckoutSession _session = new();
        private string _currentPharmacistId = "";
        private string _currentPharmacistName = "";
        private long? _currentStandardNSessionId;
        private bool _pharmacistFromDb;
        private string? _activeReceiptDraftId;
        internal ObservableCollection<ReceiptItem> ReceiptItems { get; } = new();
        internal List<(long? DocumentId, int[] PartIds)> Sales => _testOutbox.DequeueReady(100)
            .Where(item => item.Kind == "sale")
            .Select(item => JsonSerializer.Deserialize<SaleReport>(item.Payload, EpharmJson.Options)!)
            .Select(sale => (sale.SourceDocumentId, sale.Items.Select(item => int.Parse(item.Sku!)).ToArray())).ToList();
        internal decimal DisplayTotal { get; private set; }
        internal int DiscardedDrafts { get; private set; }
        internal HashSet<string> SavedDrafts { get; } = new();
        internal string? ActiveDraftId => _activeReceiptDraftId;
        internal long? FinalizedDocumentId => _lastFinalizedStandardNDocumentId;
        internal int PopupClears { get; private set; }
        internal int SessionRestarts { get; private set; }
        internal int FinalizationCalls { get; private set; }
        internal List<string> Messages { get; } = new();
        internal OfflineOutbox Queue => _testOutbox;
        internal ReceiptArtifactStore Artifacts => _receiptArtifacts!;
        internal string StorageRoot => _storageRoot;
        internal string StableSaleId(long document) => ReceiptSaleId.Create(_posmConfig.PharmacyId, document, _session.SessionId);
        internal long? DocumentId => _standardNDocumentId;
        internal long ReadRevision => _receiptObservations.Revision;
        internal bool PharmacistFromDatabase => _pharmacistFromDb;

        internal MainWindow()
        {
            _testOutbox = new OfflineOutbox(Path.Combine(_storageRoot, "outbox.db"));
            _saleReporter = new SaleReporter(_posmConfig, _testOutbox);
            _receiptArtifacts = new ReceiptArtifactStore(Path.Combine(_storageRoot, "receipts"),
                new FiscalReceiptInboxSource(Path.Combine(_storageRoot, "fiscal-inbox"), ["ofd-api"]));
        }

        public void Dispose()
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            if (Directory.Exists(_storageRoot)) Directory.Delete(_storageRoot, recursive: true);
        }

        internal void Observe(StandardNReceiptSnapshot? receipt) => CompleteRead(ReadRevision, receipt);
        internal void CompleteRead(long revision, StandardNReceiptSnapshot? receipt)
        {
            if (_receiptObservations.Admit(revision, receipt != null)) ApplyStandardNReceipt(receipt);
        }
        internal void Print() => HandleStandardNReceiptPrinted();
        internal void KeepDraft(string id) { SavedDrafts.Add(id); _activeReceiptDraftId = id; }
        internal void DatabaseFailed() => _receiptObservations.ReadFailed();
        internal void ObserveMissingForSessions(string?[] workstations, string? selected)
        {
            if (ReceiptObservationGate.CanConfirmEmptyReceipt(workstations, selected, "CASH-1")) Observe(null);
            else DatabaseFailed();
        }
        internal void Discount(decimal percent)
        {
            _receiptObservations.LogChanged();
            foreach (var item in ReceiptItems) item.DiscountPercent = percent;
            RecalcTotal();
        }
        internal void AddLogItem(int partId)
        {
            _receiptObservations.LogChanged();
            UpsertItemSetQty(new ReceiptItem { PartId = partId, Name = "Item", Price = 100m, Qty = 1m });
            RecalcTotal();
        }
        internal bool ObserveLogLine(ReceiptItem incoming)
        {
            _receiptObservations.LogChanged();
            var increased = UpsertItemSetQty(incoming);
            RecalcTotal();
            return increased;
        }

        private void Log(string message)
        {
            Messages.Add(message);
            if (message.StartsWith("Завершение чека отклонено:", StringComparison.Ordinal) ||
                message.StartsWith("Завершение чека пропущено:", StringComparison.Ordinal) ||
                message.StartsWith("Чек поставлен в гарантированную очередь:", StringComparison.Ordinal)) FinalizationCalls++;
        }
        private void RecalcTotal() => DisplayTotal = ReceiptItems.Sum(item => item.Total);
        private void DiscardActiveReceiptDraft()
        {
            DiscardedDrafts++;
            if (_activeReceiptDraftId != null) SavedDrafts.Remove(_activeReceiptDraftId);
            _activeReceiptDraftId = null;
        }
        private void ResetRecommendationUiState(bool closeWindows) { if (closeWindows) PopupClears++; }
        private void StartNewCheckoutSession() { SessionRestarts++; _session = new CheckoutSession(); }
        private void RefreshCurrentPharmacistFromStandardNDb() { }
        private void CaptureCurrentSellerForSession() =>
            _session.CaptureSeller(_currentPharmacistId, _currentPharmacistName, _currentStandardNSessionId);
        private void OnProductScanned(ReceiptItem item) { }
        private void OnCartChangedLocalOnly(bool cancelPendingRecommendation) { }
        private void LogScannedItemContext(ReceiptItem item, string source) { }
        // UI removal boundary; the production upsert itself is linked from the real partial.
        private bool RemoveItemByPartId(int partId)
        {
            var existing = ReceiptItems.FirstOrDefault(item => item.PartId == partId);
            return existing != null && ReceiptItems.Remove(existing);
        }
        private sealed class ImmediateDispatcher { public void Invoke(Action action) => action(); }
        private sealed class UnavailableReceiptDatabase
        {
            public bool TryGetCurrentReceipt(out StandardNReceiptSnapshot? receipt) { receipt = null; return false; }
        }
    }
}
