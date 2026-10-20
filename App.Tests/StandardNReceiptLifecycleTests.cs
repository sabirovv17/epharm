using CustomerDisplay.Config;
using CustomerDisplay.Models;
using CustomerDisplay.Services;
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
        internal List<ReceiptItem> ReceiptItems { get; } = new();
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
        private bool UpsertItemSetQty(ReceiptItem incoming)
        {
            var existing = ReceiptItems.FirstOrDefault(item => item.PartId == incoming.PartId);
            var increased = existing == null || incoming.Qty > existing.Qty;
            if (existing != null) ReceiptItems.Remove(existing);
            ReceiptItems.Add(incoming);
            return increased;
        }
        private sealed class ImmediateDispatcher { public void Invoke(Action action) => action(); }
        private sealed class UnavailableReceiptDatabase
        {
            public bool TryGetCurrentReceipt(out StandardNReceiptSnapshot? receipt) { receipt = null; return false; }
        }
    }
}
