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
    // Deliberately blocked ThreadPool probes must not compete with other blocking fixtures
    // for the two-second start budget. Each test still exercises real concurrent work.
    [CollectionDefinition(DisableParallelization = true)]
    public sealed class StandardNReceiptLifecycleCollection { }

    [Collection<StandardNReceiptLifecycleCollection>]
    public sealed class StandardNReceiptLifecycleTests
    {
        [Theory]
        [InlineData(true)]
        [InlineData(false)]
        public void IdleConnectivityGapAfterPaidCheckoutCannotPoisonTheNextUnobservedCheckout(bool capabilityChange)
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            var previous = RewardReceipt(100, 1); window.Observe(previous);
            window.PrintWithStates(Posted(previous, 100));
            Assert.Empty(window.ReceiptItems); Assert.Null(window.DocumentId);
            if (capabilityChange) { window.SetRewardCapability(null); window.SetRewardCapability(1); }
            else window.DatabaseFailed();
            window.Observe(RewardReceipt(200, 1));
            Assert.NotNull(window.ShowReward("new-after-idle-gap", "4601164003157"));
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(30));
            var added = RewardReceipt(200, 2); window.Observe(added);
            window.PrintWithStates(Posted(added, 200));
            Assert.True(window.Reports.Single(report => report.SourceDocumentId == 200).RewardEvidence!.JournalComplete);
        }

        [Fact]
        public async Task SeventyDisappearedUnpaidCheckoutsRestartAndFreshPaymentKeepCaptureAndRewardsWorking()
        {
            var root = Path.Combine(Path.GetTempPath(), "epharm-sequential-cancels-" + Guid.NewGuid().ToString("N"));
            using (var window = new MainWindow(root, cleanup: false))
            {
                window.EnableRewardEvidence();
                long transaction = 0;
                window.SetUnpaidAbsenceReader((group, ns) => new(ns, StandardNUnpaidGroupAbsenceProof.Fingerprint(group),
                    Interlocked.Increment(ref transaction), window.ReceiptNow.UtcDateTime));
                for (var id = 1000; id < 1070; id++)
                {
                    window.Observe(RewardReceipt(id, 1));
                    Assert.NotNull(window.ShowReward("cancelled-show-" + id, "4601164003157"));
                    window.Observe(null); window.Observe(null);
                    await window.WaitForUnpaidProbeAsync();
                    window.ProbeUnpaidGroups(); await window.WaitForUnpaidProbeAsync();
                    window.AdvanceReceiptTime(TimeSpan.FromSeconds(2)); window.ProbeUnpaidGroups();
                    await window.WaitForUnpaidProbeAsync();
                    Assert.Empty(window.Artifacts.ReadObservedGroups());
                    Assert.Empty(window.Reports);
                }
                Assert.Equal(70, Directory.GetFiles(Path.Combine(root, "receipts", "reward-evidence", "unresolved"), "*.json").Length);
            }
            using var restarted = new MainWindow(root);
            restarted.EnableRewardEvidence();
            restarted.Observe(RewardReceipt(2000, 1));
            var shown = restarted.ShowReward("fresh-after-seventy", "4601164003157");
            Assert.True(shown!.CartComplete);
            restarted.AdvanceReceiptTime(TimeSpan.FromSeconds(30));
            var increased = RewardReceipt(2000, 2); restarted.Observe(increased);
            restarted.AdvanceReceiptTime(TimeSpan.FromMinutes(5));
            restarted.PrintWithStates(Posted(increased, 2000));
            var sale = Assert.Single(restarted.Reports);
            Assert.True(sale.RewardEvidence!.JournalComplete);
            Assert.Equal("fresh-after-seventy", Assert.Single(sale.RewardEvidence.Changes).EventId);
        }

        [Fact]
        public async Task CurrentOrSuspendedOrUnknownGroupsNeverRetireMerelyBecauseUiCartDisappears()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.Observe(RewardReceipt(100, 1));
            var calls = 0;
            window.SetUnpaidAbsenceReader((group, ns) => { Interlocked.Increment(ref calls); return null; });
            window.ProbeUnpaidGroups(); await window.WaitForUnpaidProbeAsync();
            Assert.Equal(0, calls); // Current source cannot be archived.
            window.Observe(null); window.Observe(null); await window.WaitForUnpaidProbeAsync();
            window.AdvanceReceiptTime(TimeSpan.FromHours(24)); window.ProbeUnpaidGroups(); await window.WaitForUnpaidProbeAsync();
            Assert.Single(window.Artifacts.ReadObservedGroups());
            Assert.Empty(window.Reports);
            Assert.False(Directory.Exists(Path.Combine(window.StorageRoot, "receipts", "reward-evidence", "unresolved")));
        }

        [Fact]
        public async Task FailedNativeReadCannotArchiveAGroupEvenIfItsOutValueWasAssignedBeforeFailure()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.Observe(RewardReceipt(100, 1));
            long transaction = 0;
            window.SetUnpaidAbsenceReader((group, ns) => new(ns, StandardNUnpaidGroupAbsenceProof.Fingerprint(group),
                Interlocked.Increment(ref transaction), window.ReceiptNow.UtcDateTime));
            window.SetUnpaidAbsenceFailure();
            window.Observe(null); window.Observe(null); await window.WaitForUnpaidProbeAsync();
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(2)); window.ProbeUnpaidGroups(); await window.WaitForUnpaidProbeAsync();
            Assert.Single(window.Artifacts.ReadObservedGroups()); Assert.Empty(window.Reports);
        }

        [Fact]
        public async Task LegacyGroupUsesOnlyItsRecordedJournalNamespaceWithoutRewritingOriginalMetadata()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            var receipt = RewardReceipt(100, 1);
            var legacy = new StandardNObservedReceiptGroup(1, "synthetic-pharmacy", receipt, "sess_legacy",
                receipt.Pharmacist.Id, receipt.Pharmacist.Name, false, []) { ObservedActiveDocumentIds = [100] };
            Assert.True(window.Artifacts.SaveObservedGroup(legacy));
            window.Observe(receipt); window.ShowReward("legacy-show", "4601164003157");
            Assert.Null(Assert.Single(window.Artifacts.ReadObservedGroups()).SourceNamespace);
            long transaction = 0;
            window.SetUnpaidAbsenceReader((group, ns) =>
            {
                Assert.Null(group.SourceNamespace); Assert.Equal(new string('a', 64), ns);
                return new(ns, StandardNUnpaidGroupAbsenceProof.Fingerprint(group), Interlocked.Increment(ref transaction), window.ReceiptNow.UtcDateTime);
            });
            window.Observe(null); window.Observe(null); await window.WaitForUnpaidProbeAsync();
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(2)); window.ProbeUnpaidGroups(); await window.WaitForUnpaidProbeAsync();
            Assert.Empty(window.Artifacts.ReadObservedGroups());
            Assert.Single(Directory.GetFiles(Path.Combine(window.StorageRoot, "receipts", "reward-evidence", "unresolved"), "*.json"));
            Assert.Empty(window.Reports);
        }

        [Theory]
        [InlineData(true)]
        [InlineData(false)]
        public async Task SameNativeTransactionOrClockCannotTurnOneAbsenceReadIntoTwo(bool repeatTransaction)
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.Observe(RewardReceipt(100, 1));
            var fixedTime = window.ReceiptNow.UtcDateTime; long transaction = 0;
            window.SetUnpaidAbsenceReader((group, ns) => new(ns, StandardNUnpaidGroupAbsenceProof.Fingerprint(group),
                repeatTransaction ? 1 : Interlocked.Increment(ref transaction), repeatTransaction ? window.ReceiptNow.UtcDateTime : fixedTime));
            window.Observe(null); window.Observe(null); await window.WaitForUnpaidProbeAsync();
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(3)); window.ProbeUnpaidGroups(); await window.WaitForUnpaidProbeAsync();
            Assert.Single(window.Artifacts.ReadObservedGroups()); Assert.Empty(window.Reports);
        }

        [Fact]
        public void OrdinarySaleCarriesExactNativeSourceWithoutEnablingRewards()
        {
            using var window = new MainWindow();
            var receipt = RewardReceipt(100, 1) with { ObservedSourceNamespace = new string('a', 64) };
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100));
            var sale = Assert.Single(window.Reports);
            Assert.Null(sale.RewardEvidence);
            Assert.Equal(new SaleSellerSource(new string('a', 64), receipt.Pharmacist.Id,
                receipt.SessionId, receipt.WorkstationId!), sale.SellerSource);
        }

        [Fact]
        public void NewGroupUsesObservedConnectionInsteadOfMutableGlobalNamespace()
        {
            using var window = new MainWindow();
            var receipt = RewardReceipt(100, 1) with { ObservedSourceNamespace = new string('b', 64) };
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100));
            var sale = Assert.Single(window.Reports);
            Assert.Equal(new string('b', 64), sale.SellerSource?.SourceNamespace);
            Assert.Null(sale.RewardEvidence);
        }

        [Fact]
        public void ChangedDatabaseCannotRelabelAnOrdinarySaleWithOldNativeIdentity()
        {
            using var window = new MainWindow();
            var receipt = RewardReceipt(100, 1) with { ObservedSourceNamespace = new string('a', 64) };
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100) with { ObservedSourceNamespace = new string('b', 64) });
            var sale = Assert.Single(window.Reports);
            Assert.Null(sale.SellerSource);
            Assert.Null(sale.RewardEvidence);
        }

        [Fact]
        public void LegacyGroupDoesNotBorrowCurrentDatabaseNamespaceForOrdinarySale()
        {
            using var window = new MainWindow();
            var receipt = RewardReceipt(100, 1);
            var legacy = new StandardNObservedReceiptGroup(1, "synthetic-pharmacy", receipt, "sess_legacy",
                receipt.Pharmacist.Id, receipt.Pharmacist.Name, false, []) { ObservedActiveDocumentIds = [100] };
            Assert.True(window.Artifacts.SaveObservedGroup(legacy));
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Null(Assert.Single(window.Reports).SellerSource);
        }

        [Fact]
        public void FreshExactCartSendsItsNamespaceWithoutEnablingMoneyCapture()
        {
            using var window = new MainWindow();
            window.Observe(RewardReceipt(100, 1));
            var request = window.RecommendRequestWithSource();
            Assert.Equal(new string('a', 64), request.SourceNamespace);
            Assert.Equal("cashier-1", request.PharmacistId);
            Assert.Single(request.Cart);
            Assert.Null(window.ShowReward("still-disabled", "4601164003157"));
        }

        [Theory]
        [InlineData("stale")]
        [InlineData("failed-read")]
        [InlineData("changed-quantity")]
        [InlineData("different-checkout")]
        [InlineData("different-user")]
        public void UnknownOrDifferentNativeContextNeverAddsACachedNamespaceToRecommendation(string change)
        {
            using var window = new MainWindow(); window.Observe(RewardReceipt(100, 1));
            var request = window.RecommendRequestWithSource();
            Assert.NotNull(request.SourceNamespace);
            switch (change) {
                case "stale": window.AdvanceReceiptTime(TimeSpan.FromSeconds(2).Add(TimeSpan.FromTicks(1))); break;
                case "failed-read": window.DatabaseFailed(); break;
                case "changed-quantity": window.ReceiptItems.Single().Qty = 2; break;
                case "different-checkout": request.SessionId = "different-checkout"; break;
                default: request.PharmacistId = "different-user"; break;
            }
            Assert.Null(window.NamespaceForRecommend(request));
        }

        [Fact]
        public void LogOnlyCartStillBuildsAnOrdinaryRecommendationWithoutNamespace()
        {
            using var window = new MainWindow(); window.ObserveLogLine(Item(10, "Synthetic", "4600000000012", 100, 1));
            var request = window.RecommendRequestWithSource();
            Assert.Null(request.SourceNamespace); Assert.Single(request.Cart);
        }

        [Fact]
        public async Task DelayedExactPrintBatchUsesOnlyRetainedDocumentAndDoesNotReplay()
        {
            using var window = new MainWindow(); var old = Receipt(100, 10); var current = Receipt(200, 20);
            window.Observe(old); window.Observe(current);
            window.EnableNativeRefundGuard(null);
            window.SetExactNativePrintObservation(new(false, [200, 999]));
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(30)); // Human paper dialog after DOCS posting.
            window.PrintExactWithStates(new HashSet<long> { 200, 999 }, Posted(old, 100), Posted(current, 200));
            await window.WaitForNativePrintGuardAsync();
            Assert.Equal(new long[] { 200, 999 }, window.LastExactPrintDocuments!.Order());
            Assert.Equal(200, Assert.Single(window.Reports).SourceDocumentId);
            window.PrintExactWithStates(new HashSet<long> { 200 }, Posted(old, 100), Posted(current, 200));
            await window.WaitForNativePrintGuardAsync();
            Assert.Single(window.Reports);
        }

        [Fact]
        public async Task UnknownOrRefundExactPrintBatchCannotFinalizeAnOldSale()
        {
            using var window = new MainWindow(); var receipt = Receipt(100, 10); window.Observe(receipt);
            window.EnableNativeRefundGuard(null); window.SetExactNativePrintObservation(new(false, [100]));
            window.PrintExactWithStates(new HashSet<long> { 999 }, Posted(receipt, 100));
            await window.WaitForNativePrintGuardAsync(); Assert.Empty(window.Reports);
            window.SetExactNativePrintObservation(new(true, [100]));
            window.PrintExactWithStates(new HashSet<long> { 100 }, Posted(receipt, 100));
            await window.WaitForNativePrintGuardAsync(); Assert.Empty(window.Reports);
        }

        [Fact]
        public async Task UnobservedReturnInExactBatchRemainsAVetoInsteadOfCreditingAnOldSale()
        {
            using var window = new MainWindow(); var receipt = Receipt(100, 10); window.Observe(receipt);
            window.EnableNativeRefundGuard(null); window.SetExactNativePrintObservation(new(true, [100]));
            window.PrintExactWithStates(new HashSet<long> { 100, 200 }, Posted(receipt, 100));
            await window.WaitForNativePrintGuardAsync();
            Assert.Equal(new long[] { 100, 200 }, window.LastExactPrintDocuments!.Order()); Assert.Empty(window.Reports);
        }

        [Fact]
        public async Task InvalidBatchNeverEnablesTheExactDocumentAgeException()
        {
            using var window = new MainWindow(); var receipt = Receipt(100, 10); window.Observe(receipt);
            window.EnableNativeRefundGuard(null); window.SetExactNativePrintObservation(new(false, [100]));
            var batch = new StandardNPrintLogBatch();
            batch.Observe("RunScriptByIndex: Перед печатью очереди чеков");
            batch.Observe("Destroy Cheque ID=100"); batch.Observe("Destroy Cheque ID=-2");
            var documents = batch.Observe("RunScriptByIndex: После печати очереди чеков");
            Assert.Null(documents);
            window.PrintExactWithStates(documents, Posted(receipt, 100));
            await window.WaitForNativePrintGuardAsync();
            Assert.Null(window.LastExactPrintDocuments); Assert.Empty(window.Reports);
        }

        [Fact]
        public async Task RefundPrintCueCannotFinalizeAnOldHeldSale()
        {
            using var window = new MainWindow(); var old = Receipt(100, 10); var current = Receipt(200, 20);
            window.Observe(old); window.Observe(current);
            window.EnableNativeRefundGuard(new(true, []));
            window.PrintWithStates(Posted(old, 100), current);
            await window.WaitForNativePrintGuardAsync();
            Assert.Empty(window.Reports);
            Assert.All(window.Artifacts.ReadObservedGroups(), group => Assert.Null(group.PendingPrint));
            Assert.Equal(200, window.DocumentId);
        }

        [Fact]
        public async Task FreshNativeSaleClassificationRestrictsCueToItsRealDocument()
        {
            using var window = new MainWindow(); var old = Receipt(100, 10); var current = Receipt(200, 20);
            window.Observe(old); window.Observe(current);
            window.EnableNativeRefundGuard(new(false, [200]));
            window.PrintWithStates(Posted(old, 100), Posted(current, 200));
            await window.WaitForNativePrintGuardAsync();
            Assert.Equal(200, Assert.Single(window.Reports).SourceDocumentId);
        }

        [Fact]
        public async Task UnavailableNativePrintClassificationCannotInventPayment()
        {
            using var window = new MainWindow(); var receipt = Receipt(100, 10); window.Observe(receipt);
            window.EnableNativeRefundGuard(null);
            window.PrintWithStates(Posted(receipt, 100));
            await window.WaitForNativePrintGuardAsync();
            Assert.Empty(window.Reports);
        }

        [Fact]
        public void MissingLocalSettingsRemainClosedUntilServerAdmissionAndFreshCart()
        {
            using var window = new MainWindow();
            var receipt = RewardReceipt(100, 1); window.Observe(receipt);
            Assert.Null(window.ShowReward("disabled", "4601164003157"));
            window.SetRewardCapability(1);
            Assert.False(window.ShowReward("before-fresh-poll", "4601164003157")!.CartComplete);
            window.Observe(receipt);
            Assert.True(window.ShowReward("fresh-poll", "4601164003157")!.CartComplete);
        }

        [Theory]
        [InlineData(null)]
        [InlineData(0)]
        [InlineData(2)]
        public void MissingOrUnknownCapabilityClosesEvidenceAndInvalidatesOldBaseline(int? protocol)
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.Observe(RewardReceipt(100, 1));
            Assert.True(window.ShowReward("first", "4601164003157")!.CartComplete);
            window.SetRewardCapability(protocol);
            Assert.Null(window.ShowReward("off", "4601164003157"));
            Assert.False(window.NativeRefundObservationActive);
            window.SetRewardCapability(1);
            Assert.Null(window.ShowReward("resumed-no-poll", "4601164003157")); // Never revive this checkout after an observation gap.
        }

        [Fact]
        public void ExplicitLocalOptOutWinsOverServerAndTrueDoesNotBypassServer()
        {
            using var window = new MainWindow();
            window.SetRewardOptions(true, true);
            window.Observe(RewardReceipt(100, 1));
            Assert.Null(window.ShowReward("no-server", "4601164003157"));
            window.SetRewardOptions(false, false); window.SetRewardCapability(1);
            window.Observe(RewardReceipt(100, 1));
            Assert.Null(window.ShowReward("opt-out", "4601164003157"));
            Assert.False(window.NativeRefundObservationActive);
        }

        [Fact]
        public void LocalRefundPollingOptOutCannotWeakenReturnBatchVetoForRewards()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.SetRewardOptions(true, false);
            var receipt = Receipt(100, 10); window.Observe(receipt);
            window.SetNativePrintObservation(new(true, []));
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Empty(window.Reports);
            Assert.False(window.NativeRefundObservationActive);
        }

        [Fact]
        public async Task LostRewardCapabilityKeepsOrdinaryNativePaymentAndItsStricterPrintGuard()
        {
            using var window = new MainWindow(); var receipt = Receipt(100, 10); window.Observe(receipt);
            window.EnableNativeRefundGuard(new(false, [100]));
            window.SetRewardCapability(null);
            window.PrintWithStates(Posted(receipt, 100));
            await window.WaitForNativePrintGuardAsync();
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Null(Assert.Single(window.Reports).RewardEvidence);
        }

        [Fact]
        public void RewardOptInCapturesRealSnapshotShowIncreaseAndLaterVerifiedPhysicalSale()
        {
            using var window = new MainWindow();
            window.EnableRewardEvidence();
            var receipt = RewardReceipt(100, 1);
            window.Observe(receipt);
            var shown = window.ShowReward("event-synthetic", "4601164003157");
            Assert.True(shown!.CartComplete);
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(119));
            var increased = RewardReceipt(100, 2);
            window.Observe(increased);
            window.AdvanceReceiptTime(TimeSpan.FromMinutes(5));
            window.PrintWithStates(Posted(increased, 100));
            var report = Assert.Single(window.Reports);
            Assert.True(report.RewardEvidence!.JournalComplete);
            Assert.Equal(shown.CheckoutSessionId, report.RewardEvidence.CheckoutSessionId);
            var change = Assert.Single(report.RewardEvidence.Changes);
            Assert.Equal((1m, 2m, "event-synthetic"), (change.BeforeQty, change.AfterQty, change.EventId));
            Assert.Equal(100, report.RewardEvidence.Completion.SourceDocumentId);
            Assert.Equal(-200m, report.RewardEvidence.Completion.SignedTotal);
            Assert.Empty(Directory.GetFiles(Path.Combine(window.StorageRoot, "receipts", "reward-evidence"), "*.json"));
        }

        [Fact]
        public void FailedDatabaseReadRetainsOrdinarySaleButRewardEvidenceRequiresReview()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            var receipt = RewardReceipt(100, 1); window.Observe(receipt);
            window.ShowReward("event-synthetic", "4601164003157");
            window.DatabaseFailed();
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(10));
            var increased = RewardReceipt(100, 2); window.Observe(increased);
            window.PrintWithStates(Posted(increased, 100));
            var report = Assert.Single(window.Reports);
            Assert.False(report.RewardEvidence!.JournalComplete);
            Assert.Single(report.RewardEvidence.Changes);
        }

        [Theory]
        [InlineData(false)]
        [InlineData(true)]
        public void PartialPrintingHoldPreservesCartWithoutHealingAnActualObservationGap(bool failedRead)
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.Observe(RewardReceipt(100, 1));
            var shown = window.ShowReward("event-synthetic", "4601164003157")!;
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(40));
            var increased = RewardReceipt(100, 2); window.Observe(increased);
            if (failedRead) window.DatabaseFailed();
            window.AdvanceReceiptTime(TimeSpan.FromMinutes(3));
            window.ObservePosting(); window.ObservePosting();
            Assert.Equal(2m, Assert.Single(window.ReceiptItems).Qty);
            Assert.Equal(100, window.DocumentId); Assert.Empty(window.Reports);
            var repeated = window.ShowReward("event-synthetic", "4601164003157");
            if (failedRead) Assert.Null(repeated);
            else Assert.Equal(JsonSerializer.Serialize(shown, EpharmJson.Options),
                JsonSerializer.Serialize(repeated, EpharmJson.Options));
            window.PrintWithStates(Posted(increased, 100));
            var evidence = Assert.Single(window.Reports).RewardEvidence!;
            Assert.Equal(!failedRead, evidence.JournalComplete);
            var change = Assert.Single(evidence.Changes);
            Assert.Equal((1m, 2m, "event-synthetic"), (change.BeforeQty, change.AfterQty, change.EventId));
            Assert.Empty(window.ReceiptItems);
        }

        [Fact]
        public void LogDeleteReaddBetweenEqualDatabaseSnapshotsCannotReviveEarlierEligibleUnits()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence();
            window.Observe(RewardReceipt(100, 1)); window.ShowReward("event-synthetic", "4601164003157");
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(10));
            var increased = RewardReceipt(100, 2); window.Observe(increased);
            window.AdvanceReceiptTime(TimeSpan.FromMinutes(3));
            window.ObserveLogLine(Item(10, "Item", "4601164003157", 100, 0));
            window.ObserveLogLine(Item(10, "Item", "4601164003157", 100, 2));
            window.Observe(increased); // Same qty as the last DB read does not prove continuity.
            window.PrintWithStates(Posted(increased, 100));
            var evidence = Assert.Single(window.Reports).RewardEvidence!;
            Assert.False(evidence.JournalComplete);
            Assert.Single(evidence.Changes);
        }

        [Fact]
        public void RewardEvidenceRequiresAdmissionAndRetainsUnknownUnitsWithoutInventingPackages()
        {
            using var disabled = new MainWindow(); var receipt = Receipt(100, 10);
            disabled.Observe(receipt); disabled.PrintWithStates(Posted(receipt, 100));
            Assert.Null(Assert.Single(disabled.Reports).RewardEvidence);
            using var enabled = new MainWindow(); enabled.EnableRewardEvidence();
            enabled.Observe(receipt with { WorkstationId = "9" });
            var shown = enabled.ShowReward("event-synthetic", "4601164003157")!;
            Assert.True(shown.CartComplete);
            Assert.All(shown.Lines, line => Assert.Equal("unknown", line.UnitSemantics));
            enabled.PrintWithStates(Posted(receipt with { WorkstationId = "9" }, 100));
            var evidence = Assert.Single(enabled.Reports).RewardEvidence!;
            Assert.True(evidence.JournalComplete);
            Assert.All(evidence.Completion.Lines, line => Assert.Equal("unknown", line.UnitSemantics));
        }

        private static StandardNReceiptSnapshot RewardReceipt(long document, decimal qty)
        {
            var source = Receipt(document, 10);
            var line = source.Lines.Single() with { Qty = qty, SourceTotal = qty * 100, SignedQuantity = -qty,
                SignedAmount = -qty * 100, UnitSemantics = "full_package" };
            return source with { WorkstationId = "9", Lines = [line], Documents = [source.Documents!.Single() with {
                Lines = [line], SignedTotal = -100 * qty }] };
        }

        [Fact]
        public void FractionalKztProofIsPreservedWithoutRoundingQueueRetirementOrAck()
        {
            using var window = new MainWindow();
            var receipt = FractionalReceipt(100);
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Empty(window.Reports);
            var group = Assert.Single(window.Artifacts.ReadObservedGroups());
            Assert.True(group.PrintObserved);
            Assert.Empty(group.QueuedDocumentIds);
            Assert.Equal(-131m, Assert.Single(group.Snapshot.Documents!).SignedTotal);
            Assert.All(group.Snapshot.Lines, line => { Assert.Equal(65.5m, line.Price); Assert.Equal(65.5m, line.SourceTotal); });
            Assert.Contains(window.Messages, message => message.Contains("целочисленным KZT") && message.Contains("исходные наблюдения сохранены"));
            Assert.Throws<InvalidDataException>(() => window.Artifacts.RetireObservedGroup(group with { QueuedDocumentIds = new long[] { 100 } }));
            var journalPath = Path.Combine(window.StorageRoot, "receipts", "observed-groups", "groups.json");
            var original = File.ReadAllBytes(journalPath);
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(31));
            window.RetryPrintWithStates(Posted(receipt, 100));
            Assert.Empty(window.Reports);
            Assert.Equal(original, File.ReadAllBytes(journalPath));
            Assert.Equal(100, window.DocumentId);
        }

        [Fact]
        public void UnsupportedAmountInOldGroupCannotDelayOtherGroupsCommitRetry()
        {
            using var window = new MainWindow();
            var unsupported = FractionalReceipt(100);
            var current = Receipt(200, 30);
            window.Observe(unsupported);
            window.PrintWithStates(Posted(unsupported, 100));
            Assert.Empty(window.Reports);
            window.Observe(current);
            window.PrintWithStates(Posted(unsupported, 100), current);
            window.AdvanceReceiptTime(TimeSpan.FromMilliseconds(200));
            window.RetryPrintWithStates(Posted(unsupported, 100), Posted(current, 200));
            Assert.Equal(200, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Empty(window.ReceiptItems);
            Assert.Equal(100, Assert.Single(window.Artifacts.ReadObservedGroups()).Snapshot.DocumentId);
        }

        [Fact]
        public void WholeKztAmountWithFractionalQuantityRemainsRepresentable()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            var line = receipt.Lines[0] with { Price = 3500m, SignedPrice = 3500m, Qty = 0.5m,
                SignedQuantity = -0.5m, SourceTotal = 1750m, SignedAmount = -1750m };
            receipt = receipt with { Lines = new[] { line }, Documents = new[] { receipt.Documents![0] with
                { SignedTotal = -1750m, Lines = new[] { line } } } };
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Equal(1750, Assert.Single(window.Reports).TotalAmount);
            Assert.Equal(0.5, Assert.Single(Assert.Single(window.Reports).Items).Qty);
        }

        [Fact]
        public void AmountOutsideLongRangeNeverReachesSaleReporterCast()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            var amount = (decimal)long.MaxValue + 1;
            var line = receipt.Lines[0] with { Price = amount, SignedPrice = amount, SourceTotal = amount, SignedAmount = -amount };
            receipt = receipt with { Lines = new[] { line }, Documents = new[] { receipt.Documents![0] with
                { SignedTotal = -amount, Lines = new[] { line } } } };
            window.Observe(receipt);
            Assert.Null(Record.Exception(() => window.PrintWithStates(Posted(receipt, 100))));
            Assert.Empty(window.Reports);
            Assert.Equal(amount, Assert.Single(window.Artifacts.ReadObservedGroups()).Snapshot.Lines[0].SourceTotal);
        }

        private static StandardNReceiptSnapshot FractionalReceipt(long document)
        {
            var receipt = Receipt(document, 10, 20);
            var lines = receipt.Lines.Select(line => line with
                { Price = 65.5m, SignedPrice = 65.5m, SourceTotal = 65.5m, SignedAmount = -65.5m }).ToArray();
            return receipt with { Lines = lines, Documents = new[] { receipt.Documents![0] with
                { SignedTotal = -131m, Lines = lines } } };
        }

        [Fact]
        public async Task FirstUnanchoredProbeCompletingAfterWindowCannotCreditLaterPostedState()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            using var blocked = window.BlockNextPhysicalProbe();
            window.PrintWithoutWaiting(Posted(receipt, 100));
            await blocked.Started.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(6));
            blocked.Release();
            await window.WaitForReceiptProbeAsync();
            Assert.Empty(window.Reports);
            Assert.False(Assert.Single(window.Artifacts.ReadObservedGroups()).PrintObserved);
            Assert.Equal(100, window.DocumentId);
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Equal(window.ReceiptNow, Assert.Single(window.Reports).PrintedAt);
        }

        [Fact]
        public void OldUnsafeAcknowledgementCannotSuppressFreshProvenPhysicalSale()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            var legacy = LegacySale(window, 100);
            window.Artifacts.Complete(legacy);
            window.Artifacts.MarkSaleDelivered(legacy.SaleId);
            var originalAck = File.ReadAllBytes(Path.Combine(window.StorageRoot, "receipts", "pending", legacy.SaleId, "sale-delivered.json"));
            window.Queue.Enqueue(legacy.SaleId, "sale", JsonSerializer.Serialize(legacy, EpharmJson.Options));
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Equal("standardn-print-log", Assert.Single(window.Reports).CaptureSource);
            Assert.Equal(originalAck, File.ReadAllBytes(Path.Combine(window.StorageRoot, "receipts", "legacy-unconfirmed", legacy.SaleId, "sale-delivered.json")));
            Assert.False(File.Exists(Path.Combine(window.StorageRoot, "receipts", "pending", legacy.SaleId, "sale-delivered.json")));
        }

        [Fact]
        public void RestartAfterAnchoredPrintQueueFailureKeepsOriginalTimeAndDoesNotCreditNewCart()
        {
            var path = Path.Combine(Path.GetTempPath(), "epharm-anchored-restart-" + Guid.NewGuid().ToString("N"));
            var previous = Receipt(100, 10);
            var current = Receipt(200, 20);
            DateTimeOffset marker;
            using (var first = new MainWindow(path, cleanup: false))
            {
                first.Observe(previous);
                using (var connection = new SqliteConnection("Data Source=" + Path.Combine(path, "outbox.db")))
                {
                    connection.Open();
                    using var command = connection.CreateCommand();
                    command.CommandText = "CREATE TRIGGER synthetic_queue_failure BEFORE INSERT ON outbox BEGIN SELECT RAISE(FAIL,'synthetic queue unavailable'); END;";
                    command.ExecuteNonQuery();
                }
                marker = first.ReceiptNow;
                first.PrintWithStates(Posted(previous, 100));
                Assert.Empty(first.Reports);
                var group = Assert.Single(first.Artifacts.ReadObservedGroups());
                Assert.True(group.PrintObserved);
                Assert.Empty(group.QueuedDocumentIds);
                Assert.Equal(marker, group.PrintDocumentTimes[100]);
            }
            // Remove only the test-injected I/O fault, never evidence or historical rows.
            using (var connection = new SqliteConnection("Data Source=" + Path.Combine(path, "outbox.db")))
            {
                connection.Open();
                using var command = connection.CreateCommand();
                command.CommandText = "DROP TRIGGER synthetic_queue_failure";
                command.ExecuteNonQuery();
            }
            using var restarted = new MainWindow(path);
            restarted.Observe(current);
            restarted.RetryPrintWithStates(Posted(previous, 100));
            var report = Assert.Single(restarted.Reports);
            Assert.Equal(100, report.SourceDocumentId);
            Assert.Equal(marker, report.PrintedAt);
            Assert.Equal(200, restarted.DocumentId);
            Assert.Equal(20, Assert.Single(restarted.ReceiptItems).PartId);
        }

        [Fact]
        public void QueueFailureAndFinalReadRetryPreserveGeneratedPostedIdAndOriginalActiveAlias()
        {
            using var window = new MainWindow(); window.EnableRewardEvidence(); window.EnableFinalLineRebinding();
            window.Observe(Receipt(100) with { WorkstationId = "9" });
            Assert.True(window.ShowReward("event-synthetic", "4601164003157")!.CartComplete);
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(10));
            var source = RewardReceipt(100, 1);
            var active = source.Lines.Single() with { LineId = 61043, HumanQuantity = "1" };
            source = source with { Lines = [active], Documents = [source.Documents!.Single() with { Lines = [active] }] };
            window.Observe(source);
            var paid = Posted(source, 100);
            var posted = paid.Documents!.Single().Lines.Single() with { LineId = 42375 };
            paid = paid with { Lines = [posted], Documents = [paid.Documents!.Single() with { Lines = [posted] }] };
            using (var connection = new SqliteConnection("Data Source=" + Path.Combine(window.StorageRoot, "outbox.db"))) {
                connection.Open(); using var command = connection.CreateCommand();
                command.CommandText = "CREATE TRIGGER synthetic_queue_failure BEFORE INSERT ON outbox BEGIN SELECT RAISE(FAIL,'synthetic queue unavailable'); END;";
                command.ExecuteNonQuery();
            }
            window.PrintWithStates(paid); Assert.Empty(window.Reports);
            var retained = Assert.Single(window.Artifacts.ReadObservedGroups()).Snapshot.Documents!.Single().Lines.Single();
            Assert.Equal(42375, retained.LineId); Assert.Equal(61043, retained.ObservedSourceLineId);
            using (var connection = new SqliteConnection("Data Source=" + Path.Combine(window.StorageRoot, "outbox.db"))) {
                connection.Open(); using var command = connection.CreateCommand(); command.CommandText = "DROP TRIGGER synthetic_queue_failure";
                command.ExecuteNonQuery();
            }
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(31)); window.RetryPrintWithStates(paid);
            var evidence = Assert.Single(window.Reports).RewardEvidence!;
            Assert.True(evidence.JournalComplete);
            Assert.Equal(61043, Assert.Single(evidence.Changes).SourceLineId);
            Assert.Equal(42375, Assert.Single(evidence.Completion.Lines).SourceLineId);
            Assert.Equal(61043, evidence.Completion.Lines[0].ObservedSourceLineId);
            Assert.Empty(window.Artifacts.ReadObservedGroups());
        }

        [Fact]
        public void ProvenPostedEmptyDocumentResolvesLocallyWithoutManufacturingSaleOrAck()
        {
            using var window = new MainWindow();
            var empty = Receipt(100);
            window.Observe(empty);
            window.PrintWithStates(Posted(empty, 100));
            Assert.Empty(window.Reports);
            Assert.Empty(window.Artifacts.ReadObservedGroups());
            Assert.Null(window.DocumentId);
            Assert.Equal(100, window.FinalizedDocumentId);
            Assert.Contains(window.Messages, message => message.Contains("денежный отчёт и финансовый ACK не создавались"));
        }

        [Fact]
        public void NonemptyProvenZeroAmountReceiptStillUsesNormalPhysicalSalePath()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            var line = receipt.Lines[0] with { Price = 0m, SignedPrice = 0m, SourceTotal = 0m, SignedAmount = 0m };
            receipt = receipt with { Lines = new[] { line }, Documents = new[] { receipt.Documents![0] with
                { SignedTotal = 0m, Lines = new[] { line } } } };
            window.Observe(receipt);
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Equal(0, Assert.Single(window.Reports).TotalAmount);
            Assert.Single(Assert.Single(window.Reports).Items);
            Assert.Empty(window.Artifacts.ReadObservedGroups());
        }

        [Fact]
        public void FirstObservedMixedGroupDisplaysPostedRowsButNeverCreatesNewHistoricalSale()
        {
            using var window = new MainWindow();
            var first = Receipt(100, 10);
            var second = Receipt(101, 20);
            var group = Linked(100, first, second);
            var oldPosted = Posted(group, 100).Documents!.Single(document => document.DocumentId == 100);
            group = group with { Documents = group.Documents!.Select(document => document.DocumentId == 100 ? oldPosted : document).ToArray() };
            window.Observe(group);
            Assert.Equal(200m, window.DisplayTotal);
            Assert.Equal(2, window.ReceiptItems.Count);
            var journal = Assert.Single(window.Artifacts.ReadObservedGroups());
            Assert.Equal(new long[] { 100 }, journal.InitiallyPostedDocumentIds);
            Assert.Equal(new long[] { 101 }, journal.ObservedActiveDocumentIds);
            window.PrintWithStates(Posted(group, 100, 101));
            Assert.Equal(101, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Empty(window.ReceiptItems);
            Assert.Empty(window.Artifacts.ReadObservedGroups());
        }

        [Fact]
        public async Task BlockedPhysicalDatabaseProbeDoesNotBlockAnotherCartObservationOrCreditIt()
        {
            using var window = new MainWindow();
            var previous = Receipt(100, 10);
            var current = Receipt(200, 20);
            window.Observe(previous);
            using var blocked = window.BlockNextPhysicalProbe();
            await Task.Run(() => window.PrintWithoutWaiting(Posted(previous, 100)), TestContext.Current.CancellationToken)
                .WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            await blocked.Started.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            await Task.Run(() => window.Observe(current), TestContext.Current.CancellationToken).WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            Assert.Equal(200, window.DocumentId);
            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Empty(window.Reports);
            blocked.Release();
            await window.WaitForReceiptProbeAsync();
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Equal(200, window.DocumentId);
            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
        }

        [Fact]
        public async Task StaleProbeCannotOverwriteChangedPhysicalObservationAndRechecksFreshInput()
        {
            using var window = new MainWindow();
            var first = Receipt(100, 10);
            window.Observe(first);
            using var blocked = window.BlockNextPhysicalProbe();
            window.PrintWithoutWaiting(Posted(first, 100));
            await blocked.Started.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            var line = first.Lines[0] with { Qty = 2m, SourceTotal = 200m, SignedQuantity = -2m, SignedAmount = -200m };
            var changed = first with { Lines = new[] { line }, Documents = new[] { first.Documents![0] with
                { SignedTotal = -200m, Lines = new[] { line } } } };
            window.Observe(changed);
            window.SetFreshProbeStates(Posted(changed, 100));
            blocked.Release();
            await window.WaitForReceiptProbeAsync();
            Assert.Equal(200, Assert.Single(window.Reports).TotalAmount);
            Assert.Equal(2, Assert.Single(Assert.Single(window.Reports).Items).Qty);
        }

        [Fact]
        public async Task ClosingObserverWhileProbeIsBlockedNeverCommitsItsResult()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            using var blocked = window.BlockNextPhysicalProbe();
            window.PrintWithoutWaiting(Posted(receipt, 100));
            await blocked.Started.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            window.CloseReceiptObservers();
            blocked.Release();
            await window.WaitForReceiptProbeAsync();
            Assert.Empty(window.Reports);
            Assert.False(Assert.Single(window.Artifacts.ReadObservedGroups()).PrintObserved);
            Assert.NotNull(Assert.Single(window.Artifacts.ReadObservedGroups()).PendingPrint);
        }

        [Fact]
        public void GlobalLogDiscountCannotChangeFrozenPostedChildOfMixedCart()
        {
            using var window = new MainWindow();
            var first = Receipt(100, 10);
            var second = Receipt(101, 20);
            var line = second.Lines[0] with { Price = 200m, SourceTotal = 200m, SignedAmount = -200m };
            second = second with { Lines = new[] { line }, Documents = new[] { second.Documents![0] with
                { SignedTotal = -200m, Lines = new[] { line } } } };
            var group = Linked(100, first, second);
            window.Observe(group);
            window.PrintWithStates(Posted(group, 100));
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            window.Discount(20m);
            Assert.Equal(260m, window.DisplayTotal);
            Assert.Equal(100m, window.ReceiptItems.Single(item => item.SourceDocumentId == 100).Total);
            Assert.False(window.ObserveLogLine(Item(10, "Item", "4601164003157", 100m, 2m)));
            Assert.False(window.DeleteLogPart(10));
            Assert.Equal(260m, window.DisplayTotal);
            Assert.Single(window.Reports);
        }

        [Fact]
        public void LinkedCartKeepsOneSessionAndQueuesSeparatePhysicalSalesWithExactPartitions()
        {
            using var window = new MainWindow();
            var first = Receipt(100, 10);
            var linked = Linked(100, first, Receipt(101, 20));
            window.Observe(first);
            var originalSession = Assert.Single(window.Artifacts.ReadObservedGroups()).CheckoutSessionId;
            window.Observe(linked);
            Assert.Equal(0, window.SessionRestarts);
            Assert.Equal(200m, window.DisplayTotal);
            window.PrintWithStates(Posted(linked, 100, 101));
            Assert.Equal(new long?[] { 100, 101 }, window.Reports.Select(report => report.SourceDocumentId).Order());
            Assert.All(window.Reports, report => Assert.Equal(originalSession, report.SessionId));
            Assert.Equal("10", Assert.Single(window.Reports.Single(report => report.SourceDocumentId == 100).Items).Sku);
            Assert.Equal("20", Assert.Single(window.Reports.Single(report => report.SourceDocumentId == 101).Items).Sku);
            Assert.All(window.Reports, report => Assert.Equal(100, report.TotalAmount));
            Assert.Equal(2, window.Reports.Select(report => report.SaleId).Distinct().Count());
            Assert.Empty(window.ReceiptItems);
            Assert.Equal(1, window.SessionRestarts);
            Assert.Empty(window.Artifacts.ReadObservedGroups());
        }

        [Fact]
        public void LatePrintOfPreviousGroupNeverCreditsOrClearsCurrentCart()
        {
            using var window = new MainWindow();
            var previous = Receipt(100, 10);
            var current = Receipt(200, 20);
            window.Observe(previous);
            var session = Assert.Single(window.Artifacts.ReadObservedGroups()).CheckoutSessionId;
            window.Observe(current);
            var restarts = window.SessionRestarts;
            window.PrintWithStates(Posted(previous, 100), current);
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Equal(session, Assert.Single(window.Reports).SessionId);
            Assert.Equal(200, window.DocumentId);
            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Equal(restarts, window.SessionRestarts);
            window.RetryPrintWithStates(Posted(current, 200));
            Assert.Single(window.Reports); // B never received A's print cue, even if it later posts.
            Assert.Null(Assert.Single(window.Artifacts.ReadObservedGroups()).PendingPrint);
            window.PrintWithStates(Posted(current, 200));
            Assert.Equal(2, window.Reports.Count);
            Assert.Empty(window.ReceiptItems);
        }

        [Fact]
        public void OldPartialGroupCannotConsumeNewGroupsPrintBeforeDatabaseCommit()
        {
            using var window = new MainWindow();
            var partial = Linked(100, Receipt(100, 10), Receipt(101, 20));
            var current = Receipt(200, 30);
            window.Observe(partial);
            window.PrintWithStates(Posted(partial, 100));
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            window.Observe(current);
            window.PrintWithStates(Posted(partial, 100), current);
            Assert.Single(window.Reports);
            Assert.NotNull(window.Artifacts.ReadObservedGroups().Single(group => group.Snapshot.DocumentId == 200).PendingPrint);
            window.AdvanceReceiptTime(TimeSpan.FromMilliseconds(200));
            window.RetryPrintWithStates(Posted(partial, 100), Posted(current, 200));
            Assert.Equal(new long?[] { 100, 200 }, window.Reports.Select(report => report.SourceDocumentId).Order());
            Assert.DoesNotContain(window.Reports, report => report.SourceDocumentId == 101);
            Assert.Empty(window.ReceiptItems);
            Assert.Equal(100, Assert.Single(window.Artifacts.ReadObservedGroups()).Snapshot.DocumentId);
        }

        [Fact]
        public void PartialPhysicalPaymentKeepsUnpaidCartAndUsesActualMarkerTimesForEachReport()
        {
            using var window = new MainWindow();
            var linked = Linked(100, Receipt(100, 10), Receipt(101, 20));
            window.Observe(linked);
            var firstMarker = window.ReceiptNow;
            window.PrintWithStates(Posted(linked, 100));
            Assert.Equal(100, Assert.Single(window.Reports).SourceDocumentId);
            Assert.Equal(2, window.ReceiptItems.Count);
            Assert.Equal(0, window.SessionRestarts);
            window.AdvanceReceiptTime(TimeSpan.FromMilliseconds(200));
            var secondMarker = window.ReceiptNow;
            window.PrintWithStates(Posted(linked, 100, 101));
            Assert.Equal(2, window.Reports.Count);
            Assert.Equal(firstMarker, window.Reports.Single(report => report.SourceDocumentId == 100).PrintedAt);
            Assert.Equal(secondMarker, window.Reports.Single(report => report.SourceDocumentId == 101).PrintedAt);
            Assert.Empty(window.ReceiptItems);
            Assert.Equal(1, window.SessionRestarts);
        }

        [Fact]
        public void PrintBeforeCommitRetriesOnlyCapturedGroupAndKeepsOriginalMarkerTime()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            var marker = window.ReceiptNow;
            window.PrintWithStates(receipt);
            Assert.Empty(window.Reports);
            Assert.False(Assert.Single(window.Artifacts.ReadObservedGroups()).PrintObserved);
            window.AdvanceReceiptTime(TimeSpan.FromMilliseconds(200));
            window.RetryPrintWithStates(Posted(receipt, 100));
            Assert.Equal(marker, Assert.Single(window.Reports).PrintedAt);
            Assert.Empty(window.ReceiptItems);
        }

        [Fact]
        public void ExpiredUnanchoredPrintDoesNotUseLaterPaidStateWithoutAnotherExplicitMarker()
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            window.PrintWithStates(receipt);
            window.AdvanceReceiptTime(TimeSpan.FromSeconds(6));
            window.RetryPrintWithStates(Posted(receipt, 100));
            Assert.Empty(window.Reports);
            Assert.Equal(100, window.DocumentId);
            Assert.False(Assert.Single(window.Artifacts.ReadObservedGroups()).PrintObserved);
            window.PrintWithStates(Posted(receipt, 100));
            Assert.Single(window.Reports);
        }

        [Fact]
        public void RestartPreservesPreviousGroupsSellerAndCannotRelabelLatePrintAsCurrentCart()
        {
            var path = Path.Combine(Path.GetTempPath(), "epharm-late-restart-" + Guid.NewGuid().ToString("N"));
            var previous = Receipt(100, 10);
            var current = Receipt(200, 20);
            string session;
            using (var first = new MainWindow(path, cleanup: false))
            {
                first.Observe(previous);
                session = Assert.Single(first.Artifacts.ReadObservedGroups()).CheckoutSessionId;
                first.Observe(current);
            }
            using var restarted = new MainWindow(path);
            restarted.Observe(current);
            restarted.PrintWithStates(Posted(previous, 100), current);
            var report = Assert.Single(restarted.Reports);
            Assert.Equal(100, report.SourceDocumentId);
            Assert.Equal("cashier-1", report.PharmacistId);
            Assert.Equal(session, report.SessionId);
            Assert.Equal(200, restarted.DocumentId);
            Assert.Equal(20, Assert.Single(restarted.ReceiptItems).PartId);
        }

        private static StandardNReceiptSnapshot Linked(long root, params StandardNReceiptSnapshot[] parts)
        {
            var documents = parts.SelectMany(part => part.Documents!).Select(document => document with { LinkId = root }).ToArray();
            return parts[0] with { DocumentId = root, LinkId = root, Documents = documents,
                Lines = documents.SelectMany(document => document.Lines).ToArray() };
        }

        private static StandardNReceiptSnapshot Posted(StandardNReceiptSnapshot snapshot, params long[] ids) => snapshot with
        {
            Documents = snapshot.Documents!.Select(document => ids.Contains(document.DocumentId)
                ? document with { Status = 1, DeviceNumber = "synthetic-register", FiscalNumber = document.DocumentId,
                    FiscalShift = 1, FinalDetailsRead = true }
                : document with { Lines = Array.Empty<StandardNReceiptLine>(), FinalDetailsRead = false }).ToArray(),
        };

        [Fact]
        public void LinkedPhysicalRowsWithSameProductKeepTheirOwnPriceDiscountAndTotals()
        {
            using var window = new MainWindow();
            var first = new StandardNReceiptLine(501, 10, "4600000000012", "Same product", 100m, 1m, 0m)
                { SourceDocumentId = 100, SourceTotal = 100m };
            var second = new StandardNReceiptLine(502, 10, "4600000000012", "Same product", 200m, 2m, 10m)
                { SourceDocumentId = 101, SourceTotal = 360m };
            var snapshot = new StandardNReceiptSnapshot(100, 1, "CASH-1", null,
                new("cashier-1", "Pharmacist", 1), [first, second])
            {
                LinkId = 100,
                Documents = [new(100, 1, 100, 0, -100m, null, 0, 0, [first]),
                    new(101, 1, 100, 0, -360m, null, 0, 0, [second])],
            };

            window.Observe(snapshot);

            Assert.Equal(2, window.ReceiptItems.Count);
            Assert.Equal(460m, window.DisplayTotal);
            Assert.Equal(100m, window.ReceiptItems.Single(row => row.SourceDocumentId == 100).Total);
            Assert.Equal(360m, window.ReceiptItems.Single(row => row.SourceDocumentId == 101).Total);
            Assert.Empty(window.Sales);
            var rendered = window.ReceiptItems.ToArray();
            Assert.False(window.ObserveLogLine(Item(10, "Same product", "4600000000012", 999m, 9m)));
            Assert.False(window.DeleteLogPart(10));
            Assert.Equal(460m, window.DisplayTotal);
            Assert.Equal(2, window.ReceiptItems.Count);
            window.Discount(20m); // Explicit all-cart discount applies to both physical rows.
            Assert.Equal(400m, window.DisplayTotal);
            Assert.All(window.ReceiptItems, row => Assert.Null(row.SourceTotal));
            Assert.Equal(460m, rendered.Sum(row => row.Total));
            window.Observe(snapshot);
            Assert.Equal(460m, window.DisplayTotal); // Fresh authoritative DB amounts win again.
            Assert.Empty(window.Sales);
        }

        [Fact]
        public void LogQuantityAndWholeCartDiscountAfterDbSnapshotCannotKeepStaleObservedTotal()
        {
            using var window = new MainWindow();
            var line = new StandardNReceiptLine(501, 10, "4600000000012", "Item", 100m, 3m, 10m)
                { SourceDocumentId = 100, SourceTotal = 270m };
            window.Observe(new(100, 1, "CASH-1", null, new("cashier", null, 1), [line])
            { LinkId = 100, Documents = [new(100, 1, 100, 0, -270m, null, 0, 0, [line])] });
            var original = Assert.Single(window.ReceiptItems);

            Assert.True(window.ObserveLogLine(Item(10, "Item", "4600000000012", 100m, 4m, 10m)));
            Assert.Equal(360m, window.DisplayTotal);
            Assert.Null(Assert.Single(window.ReceiptItems).SourceTotal);
            Assert.Equal(100, Assert.Single(window.ReceiptItems).SourceDocumentId);
            Assert.Equal(270m, original.Total);
            window.Discount(20m);
            Assert.Equal(320m, window.DisplayTotal);
            Assert.Empty(window.Sales);
        }

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
            Assert.Empty(window.Sales); // A doc-less print is display-only, never payment proof.
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
            Assert.Empty(window.Sales); // A doc-less print is display-only, never payment proof.
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
            Assert.Empty(window.Sales); // A doc-less print is display-only, never payment proof.

            window.Observe(null);
            window.Observe(null);
            window.Observe(Receipt(101, 20));

            Assert.Equal(20, Assert.Single(window.ReceiptItems).PartId);
            Assert.Equal(101, window.DocumentId);
            Assert.Empty(window.Sales); // A doc-less print is display-only, never payment proof.
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
        public void ReusedNativeLineIdUpdatesProductAndNeverInheritsAnotherProductsIdentity(bool missingIdentity)
        {
            using var window = new MainWindow();
            var receipt = Receipt(100, 10);
            window.Observe(receipt);
            var rendered = Assert.Single(window.ReceiptItems);
            var replacement = receipt.Lines.Single() with
            {
                PartId = 20,
                Barcode = missingIdentity ? null : receipt.Lines.Single().Barcode,
                Name = missingIdentity ? "" : receipt.Lines.Single().Name,
            };
            var updated = receipt with { Lines = [replacement], Documents = [receipt.Documents!.Single() with { Lines = [replacement] }] };

            window.Observe(updated);

            var current = Assert.Single(window.ReceiptItems);
            Assert.NotSame(rendered, current);
            Assert.Equal(10, rendered.PartId);
            Assert.Equal(20, current.PartId);
            Assert.Equal(replacement.Barcode, current.Barcode);
            Assert.Equal(replacement.Name, current.Name);
            Assert.Equal(100m, window.DisplayTotal);
            Assert.Equal(new[] { 10, 20 }, window.ScannedProducts.Select(item => item.PartId));
            Assert.Empty(window.Sales);
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

        private static StandardNReceiptSnapshot Receipt(long document, params int[] partIds)
        {
            var lines = partIds.Select(part => new StandardNReceiptLine(part, part, "4601164003157", "Item", 100m, 1m, 0m)
            { SourceDocumentId = document, SourceTotal = 100m, SignedQuantity = -1m, SignedAmount = -100m, SignedPrice = 100m }).ToArray();
            return new(document, 1, "CASH-1", null, new("cashier-1", "Pharmacist", 1), lines)
            { LinkId = document, Documents = [new(document, 1, document, 0, -100m * partIds.Length, null, 0, 0, lines)] };
        }
    }
}

namespace CustomerDisplay
{
    // Compile the real reconciliation partial without WPF. Only its UI/IO boundaries are fakes.
    public partial class MainWindow : IDisposable
    {
        private readonly EpharmConfig _posmConfig = new() { PharmacyId = "synthetic-pharmacy", PharmacistId = "synthetic-person", NativeRefundCaptureEnabled = false };
        private readonly UnavailableReceiptDatabase _standardNDb = new();
        private readonly ImmediateDispatcher Dispatcher = new();
        private readonly string _storageRoot;
        private readonly bool _cleanupStorage;
        private readonly OfflineOutbox _testOutbox;
        private readonly SaleReporter _saleReporter;
        private ReceiptArtifactStore? _receiptArtifacts;
        private CheckoutSession _session = new();
        private DateTimeOffset _testReceiptNow = DateTimeOffset.UtcNow;
        private string _currentPharmacistId = "";
        private string _currentPharmacistName = "";
        private long? _currentStandardNSessionId;
        private bool _pharmacistFromDb;
        private string? _activeReceiptDraftId;
        internal ObservableCollection<ReceiptItem> ReceiptItems { get; } = new();
        internal List<ReceiptItem> ScannedProducts { get; } = new();
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
        internal List<SaleReport> Reports => _testOutbox.DequeueReady(100).Where(item => item.Kind == "sale")
            .Select(item => JsonSerializer.Deserialize<SaleReport>(item.Payload, EpharmJson.Options)!).ToList();
        internal DateTimeOffset ReceiptNow => _testReceiptNow;
        internal ReceiptArtifactStore Artifacts => _receiptArtifacts!;
        internal string StorageRoot => _storageRoot;
        internal string StableSaleId(long document) => ReceiptSaleId.Create(_posmConfig.PharmacyId, document, _session.SessionId);
        internal long? DocumentId => _standardNDocumentId;
        internal long ReadRevision => _receiptObservations.Revision;
        internal bool PharmacistFromDatabase => _pharmacistFromDb;

        internal MainWindow(string? storageRoot = null, bool cleanup = true)
        {
            _storageRoot = storageRoot ?? Path.Combine(Path.GetTempPath(), "epharm-real-finalizer-" + Guid.NewGuid().ToString("N"));
            _cleanupStorage = cleanup;
            _posmConfig.ReceiptCaptureDir = Path.Combine(_storageRoot, "receipts");
            _receiptProofClock = () => _testReceiptNow;
            _testOutbox = new OfflineOutbox(Path.Combine(_storageRoot, "outbox.db"));
            _saleReporter = new SaleReporter(_posmConfig, _testOutbox);
            _receiptArtifacts = new ReceiptArtifactStore(Path.Combine(_storageRoot, "receipts"),
                new FiscalReceiptInboxSource(Path.Combine(_storageRoot, "fiscal-inbox"), ["ofd-api"]));
        }

        public void Dispose()
        {
            Dispatcher.Invoke(StopPhysicalReceiptObservers);
            _standardNDb.ReleaseBlockedRead();
            WaitForReceiptProbeAsync().GetAwaiter().GetResult();
            _unpaidGroupProbeTask.GetAwaiter().GetResult();
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            if (_cleanupStorage && Directory.Exists(_storageRoot)) Directory.Delete(_storageRoot, recursive: true);
        }

        internal void Observe(StandardNReceiptSnapshot? receipt) => CompleteRead(ReadRevision, receipt);
        internal void CompleteRead(long revision, StandardNReceiptSnapshot? receipt)
        {
            Dispatcher.Invoke(() => ApplyStandardNReceiptRead(revision, receipt, false));
        }
        internal void ObservePosting() => Dispatcher.Invoke(() => ApplyStandardNReceiptRead(ReadRevision, null, true));
        internal void Print()
        {
            Dispatcher.Invoke(() =>
            {
            _standardNDb.States = _observedReceiptGroups.Values.Select(group =>
            {
                var current = _standardNCurrentReceipt != null && ObservedGroupKey(_standardNCurrentReceipt) == ObservedGroupKey(group.Snapshot);
                return group.Snapshot with
                {
                    Documents = group.Snapshot.Documents!.Select(document => current ? document with
                    { Status = 1, DeviceNumber = "synthetic-register", FiscalNumber = document.DocumentId, FiscalShift = 1, FinalDetailsRead = true } : document).ToArray(),
                };
            }).ToArray();
            HandleStandardNReceiptPrinted();
            });
            WaitForReceiptProbeAsync().GetAwaiter().GetResult();
        }
        internal void PrintWithStates(params StandardNReceiptSnapshot[] states)
        {
            PrintWithoutWaiting(states);
            WaitForNativePrintGuardAsync().GetAwaiter().GetResult();
        }
        internal void PrintWithoutWaiting(params StandardNReceiptSnapshot[] states) =>
            Dispatcher.Invoke(() => { _standardNDb.States = states; HandleStandardNReceiptPrinted(); });
        internal void PrintExactWithStates(IReadOnlySet<long>? documents, params StandardNReceiptSnapshot[] states) =>
            Dispatcher.Invoke(() => { _standardNDb.States = states; HandleStandardNReceiptPrinted(documents); });
        internal void SetNativePrintObservation(NativeCashPrintObservation observation) { _standardNDb.ClassifyRewardStates = false; _standardNDb.PrintObservation = observation; }
        internal void SetExactNativePrintObservation(NativeCashPrintObservation observation) => _standardNDb.ExactPrintObservation = observation;
        internal IReadOnlySet<long>? LastExactPrintDocuments => _standardNDb.LastExactPrintDocuments;
        internal void RetryPrintWithStates(params StandardNReceiptSnapshot[] states)
        {
            WaitForReceiptProbeAsync().GetAwaiter().GetResult();
            Dispatcher.Invoke(() => { _standardNDb.States = states; _nextPhysicalReceiptProbe = DateTimeOffset.MinValue; ConfirmPhysicalReceiptPrints(); });
            WaitForReceiptProbeAsync().GetAwaiter().GetResult();
        }
        internal async Task WaitForReceiptProbeAsync()
        {
            for (var attempt = 0; attempt < 10; attempt++)
            {
                var probe = _physicalReceiptProbeTask;
                await probe.WaitAsync(TimeSpan.FromSeconds(5));
                if (ReferenceEquals(probe, _physicalReceiptProbeTask)) return;
            }
            throw new InvalidOperationException("Receipt probe did not settle.");
        }
        internal ReceiptProbeBlock BlockNextPhysicalProbe() => _standardNDb.BlockNext();
        internal void SetFreshProbeStates(params StandardNReceiptSnapshot[] states) => _standardNDb.States = states;
        internal void CloseReceiptObservers() => Dispatcher.Invoke(StopPhysicalReceiptObservers);
        internal void AdvanceReceiptTime(TimeSpan time) => _testReceiptNow += time;
        internal void SetUnpaidAbsenceReader(Func<StandardNObservedReceiptGroup, string, StandardNUnpaidGroupAbsenceProof?> reader) => _standardNDb.AbsenceReader = reader;
        internal void SetUnpaidAbsenceFailure() => _standardNDb.AbsenceReadSucceeded = false;
        internal Task WaitForUnpaidProbeAsync() => _unpaidGroupProbeTask.WaitAsync(TimeSpan.FromSeconds(5));
        internal void ProbeUnpaidGroups() => Dispatcher.Invoke(() => { _nextUnpaidGroupProbe = DateTimeOffset.MinValue; ReconcileUnresolvedReceiptGroups(); });
        internal void KeepDraft(string id) { SavedDrafts.Add(id); _activeReceiptDraftId = id; }
        internal void DatabaseFailed() { _receiptObservations.ReadFailed(); InvalidateRewardObservation(); }
        internal void EnableRewardEvidence() {
            _posmConfig.RecommendationRewardsEnabled = true;
            _standardNDb.ClassifyRewardStates = true;
            ApplyRewardCapability(1);
            _posmConfig.ReceiptCaptureDir = Path.Combine(_storageRoot, "receipts");
        }
        internal void SetRewardCapability(int? protocol) => ApplyRewardCapability(protocol);
        internal void SetRewardOptions(bool? rewards, bool? refunds) {
            _posmConfig.RecommendationRewardsEnabled = rewards; _posmConfig.NativeRefundCaptureEnabled = refunds;
            _posmConfig.ReceiptCaptureDir = Path.Combine(_storageRoot, "receipts");
        }
        internal bool NativeRefundObservationActive => NativeRefundObservationEnabled;
        internal void EnableFinalLineRebinding() => _standardNDb.RebindFinalLines = true;
        internal void EnableNativeRefundGuard(NativeCashPrintObservation? observation) {
            _posmConfig.NativeRefundCaptureEnabled = true;
            _standardNDb.ClassifyRewardStates = false;
            ApplyRewardCapability(1);
            _standardNDb.PrintObservation = observation;
        }
        internal async Task WaitForNativePrintGuardAsync() {
            await _nativePrintGuardTask.WaitAsync(TimeSpan.FromSeconds(5));
            await WaitForReceiptProbeAsync();
        }
        internal RewardShownEvidence? ShowReward(string eventId, string barcode) => CaptureRewardShown(new Recommendation {
            EventId = eventId, RecommendBarcode = barcode, RecommendSku = "catalogue-synthetic",
        }, _testReceiptNow);
        internal RecommendRequest RecommendRequestWithSource()
        {
            CaptureCurrentSellerForSession();
            var request = _session.BuildRequest(_posmConfig, ReceiptItems);
            request.SourceNamespace = RecommendSourceNamespace(request);
            return request;
        }
        internal string? NamespaceForRecommend(RecommendRequest request) => RecommendSourceNamespace(request);
        internal void ObserveMissingForSessions(string?[] workstations, string? selected)
        {
            if (ReceiptObservationGate.CanConfirmEmptyReceipt(workstations, selected, "CASH-1")) Observe(null);
            else DatabaseFailed();
        }
        internal void Discount(decimal percent)
        {
            _receiptObservations.LogChanged();
            ApplyDiscountToAllItems(percent);
            RecalcTotal();
        }
        internal bool DeleteLogPart(int partId) => RemoveItemByPartId(partId);
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
        private void StartNewCheckoutSession() { SessionRestarts++; _session = new CheckoutSession(); _rewardSnapshot = null; }
        private void RefreshCurrentPharmacistFromStandardNDb() { }
        private void CaptureCurrentSellerForSession() =>
            _session.CaptureSeller(_currentPharmacistId, _currentPharmacistName, _currentStandardNSessionId);
        private void OnProductScanned(ReceiptItem item) => ScannedProducts.Add(item);
        private void OnCartChangedLocalOnly(bool cancelPendingRecommendation) { }
        private void LogScannedItemContext(ReceiptItem item, string source) { }
        private sealed class ImmediateDispatcher
        {
            private readonly object _gate = new();
            public void Invoke(Action action) { lock (_gate) action(); }
            public Task InvokeAsync(Action action) { Invoke(action); return Task.CompletedTask; }
            public void BeginInvoke(Action action) => Invoke(action);
        }
        internal sealed class ReceiptProbeBlock : IDisposable
        {
            private readonly ManualResetEventSlim _release = new();
            internal TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
            internal void Wait() { Started.TrySetResult(); _release.Wait(TimeSpan.FromSeconds(10)); }
            internal void Release() => _release.Set();
            public void Dispose() => Release();
        }
        private sealed class UnavailableReceiptDatabase : INativeRefundSource
        {
            public NativeCashPrintObservation? PrintObservation;
            public NativeCashPrintObservation? ExactPrintObservation;
            public IReadOnlySet<long>? LastExactPrintDocuments;
            public bool RebindFinalLines;
            public bool ClassifyRewardStates;
            public bool TryObserveNativeCashPrint(out NativeCashPrintObservation? observation, IReadOnlySet<long>? exactDocuments = null) {
                LastExactPrintDocuments = exactDocuments;
                observation = exactDocuments == null ? PrintObservation : ExactPrintObservation;
                if (observation == null && ClassifyRewardStates && States != null)
                    observation = NativeCashPrintObservation.Classify(States.SelectMany(state => state.Documents ?? [])
                        .Select(document => (document.DocumentId, document.DocumentType, document.Status)).ToArray());
                return observation != null;
            }
            public bool TryGetRefundBaseline(out string sourceNamespace, out long maximumDocumentId, out DateTimeOffset databaseTime) { sourceNamespace = ""; maximumDocumentId = 0; databaseTime = default; return false; }
            public bool TryReadNativeRefunds(string sourceNamespace, NativeRefundScan scan, out NativeRefundBatch? batch) { batch = null; return false; }
            public string? RewardSourceNamespace => new('a', 64);
            public Func<StandardNObservedReceiptGroup, string, StandardNUnpaidGroupAbsenceProof?>? AbsenceReader { get; set; }
            public bool AbsenceReadSucceeded { get; set; } = true;
            public bool TryGetUnpaidGroupAbsenceProof(StandardNObservedReceiptGroup group, string sourceNamespace, out StandardNUnpaidGroupAbsenceProof? proof)
            { proof = AbsenceReader?.Invoke(group, sourceNamespace); return proof != null && AbsenceReadSucceeded; }
            public IReadOnlyList<StandardNReceiptSnapshot>? States { get; set; }
            private ReceiptProbeBlock? _block;
            private ReceiptProbeBlock? _active;
            public ReceiptProbeBlock BlockNext() => _block = new();
            public void ReleaseBlockedRead() { _block?.Release(); _active?.Release(); }
            public bool TryGetCurrentReceipt(out StandardNReceiptSnapshot? receipt) { receipt = null; return false; }
            public bool TryGetCurrentReceipt(out StandardNReceiptSnapshot? receipt, out bool posting,
                StandardNReceiptSnapshot? knownCurrent = null) { receipt = null; posting = false; return false; }
            public bool TryGetPhysicalReceiptStates(IReadOnlyList<StandardNObservedReceiptGroup> groups,
                out IReadOnlyList<StandardNReceiptSnapshot> states)
            {
                var response = States;
                var block = Interlocked.Exchange(ref _block, null);
                _active = block;
                block?.Wait();
                _active = null;
                // Emulate only the real DB adapter's requested-group response boundary.
                states = response?.Where(state => groups.Any(group => ObservedGroupKey(group.Snapshot) == ObservedGroupKey(state))).Select(state => {
                    if (!RebindFinalLines) return state;
                    var observed = groups.Single(group => ObservedGroupKey(group.Snapshot) == ObservedGroupKey(state)).Snapshot;
                    var documents = state.Documents!.Select(document => document with { FinalLineLinkRequired = true,
                        Lines = StandardNFinalLineLinker.Link(document.DocumentId, document.Lines,
                            observed.Documents!.Single(before => before.DocumentId == document.DocumentId).Lines),
                    }).ToArray();
                    return state with { Documents = documents, Lines = documents.SelectMany(document => document.Lines).ToArray() };
                }).ToArray() ?? [];
                return response != null;
            }
        }
    }
}
