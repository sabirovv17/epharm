using CustomerDisplay.Config;
using CustomerDisplay.Models;
using CustomerDisplay.Services;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using Xunit;

namespace CustomerDisplay.Core.Tests
{
    public sealed class StandardNReceiptLifecycleTests
    {
        [Fact]
        public void LogOnlyCartWithoutDiscoveredDocumentDoesNotStayOnCustomerScreenOrBecomeSale()
        {
            var window = new MainWindow();
            window.AddLogItem(10);

            window.Observe(null);
            Assert.Single(window.ReceiptItems);
            window.Observe(null);

            Assert.Empty(window.ReceiptItems);
            Assert.Equal(0m, window.DisplayTotal);
            Assert.Empty(window.Sales);
            Assert.Equal(1, window.DiscardedDrafts);
        }

        [Fact]
        public void ConfirmedPrintImmediatelyClearsTotalAndCannotBeRepopulatedBySameDocument()
        {
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
            window.AddLogItem(20);

            window.ObserveMissingForSessions(new[] { "CASH-2" }, "CASH-2");
            window.ObserveMissingForSessions(new[] { "CASH-2" }, "CASH-2");

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void AmbiguousWorkstationBreaksPreviouslyConfirmedEmptySequence()
        {
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
            var window = new MainWindow();
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
    public partial class MainWindow
    {
        private readonly EpharmConfig? _posmConfig = null;
        private readonly UnavailableReceiptDatabase? _standardNDb = null;
        private readonly ImmediateDispatcher Dispatcher = new();
        private string _currentPharmacistId = "";
        private string _currentPharmacistName = "";
        private long? _currentStandardNSessionId;
        private bool _pharmacistFromDb;
        internal ObservableCollection<ReceiptItem> ReceiptItems { get; } = new();
        internal List<(long? DocumentId, int[] PartIds)> Sales { get; } = new();
        internal decimal DisplayTotal { get; private set; }
        internal int DiscardedDrafts { get; private set; }
        internal long? DocumentId => _standardNDocumentId;
        internal long ReadRevision => _receiptObservations.Revision;
        internal bool PharmacistFromDatabase => _pharmacistFromDb;

        internal void Observe(StandardNReceiptSnapshot? receipt) => CompleteRead(ReadRevision, receipt);
        internal void CompleteRead(long revision, StandardNReceiptSnapshot? receipt)
        {
            if (_receiptObservations.Admit(revision, receipt != null)) ApplyStandardNReceipt(receipt);
        }
        internal void Print() => HandleStandardNReceiptPrinted();
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

        private static void Log(string message) { }
        private void RecalcTotal() => DisplayTotal = ReceiptItems.Sum(item => item.Total);
        private void DiscardActiveReceiptDraft() => DiscardedDrafts++;
        private void ResetRecommendationUiState(bool closeWindows) { }
        private void StartNewCheckoutSession() { }
        private void OnProductScanned(ReceiptItem item) { }
        private void OnCartChangedLocalOnly(bool cancelPendingRecommendation) { }
        private void LogScannedItemContext(ReceiptItem item, string source) { }
        private bool OnReceiptFinalized(string source, long? documentId)
        {
            var parts = ReceiptItems.Where(item => item.PartId > 0).Select(item => item.PartId).ToArray();
            if (parts.Length == 0) return false;
            Sales.Add((documentId, parts));
            return true;
        }
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
