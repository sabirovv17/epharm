using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Models;
using CustomerDisplay.Services;

namespace CustomerDisplay;

public partial class MainWindow
{
    private readonly Dictionary<string, StandardNObservedReceiptGroup> _observedReceiptGroups = new(StringComparer.Ordinal);
    private bool _observedReceiptGroupsLoaded;
    private bool _observedReceiptGroupsAvailable = true;
    private StandardNReceiptSnapshot? _standardNCurrentReceipt;
    private DateTimeOffset _nextPhysicalReceiptProbe;
    private bool _physicalReceiptProbeBusy;
    private readonly Func<DateTimeOffset> _receiptProofClock = () => DateTimeOffset.UtcNow;
    private string? _lastUnknownReceiptPrintCue;
    private long _physicalReceiptProbeGeneration;
    private bool _receiptObserversClosed;
    private Task _physicalReceiptProbeTask = Task.CompletedTask;
    private readonly Dictionary<string, DateTimeOffset> _physicalReceiptRetryAfter = new(StringComparer.Ordinal);

    private void StopPhysicalReceiptObservers()
    {
        _receiptObserversClosed = true;
        Interlocked.Increment(ref _physicalReceiptProbeGeneration);
    }

    private bool SaveObservedReceiptGroups(IReadOnlyList<StandardNObservedReceiptGroup> groups)
    {
        try
        {
            if (_receiptArtifacts == null || !_receiptArtifacts.SaveObservedGroups(groups)) return false;
            foreach (var group in groups) _observedReceiptGroups[ObservedGroupKey(group.Snapshot)] = group;
            return true;
        }
        catch (Exception error)
        {
            Log("Состояние печати не сохранено; физические чеки не подтверждены: " + error.GetBaseException().Message);
            return false;
        }
    }

    private void RecordPhysicalReceiptPrint()
    {
        Interlocked.Increment(ref _physicalReceiptProbeGeneration);
        if (!LoadObservedReceiptGroups() || _observedReceiptGroups.Count == 0)
        {
            Log("Печать без однозначно наблюдавшихся DOCS.ID: корзина сохранена, продажа не подтверждена.");
            if (_observedReceiptGroupsAvailable && _observedReceiptGroups.Count == 0 && !_standardNDocumentId.HasValue)
            {
                // Preserve the legacy display-only log boundary, never a financial report.
                _receiptObservations.AwaitPrintedReceiptBoundary();
                PreserveUnconfirmedReceiptDraft();
                ReceiptItems.Clear();
                RecalcTotal();
                ResetRecommendationUiState(closeWindows: true);
                StartNewCheckoutSession();
            }
            return;
        }
        var markerAt = _receiptProofClock();
        if (_standardNCurrentReceipt != null) _physicalReceiptRetryAfter.Remove(ObservedGroupKey(_standardNCurrentReceipt));
        var cueId = Guid.NewGuid().ToString("N");
        var changes = _observedReceiptGroups.Values.Select(group => group with
        {
            PendingPrint = group.PendingPrint != null && (!group.AnchoredPrintCueIds.Contains(group.PendingPrint.CueId) &&
                    markerAt - group.PendingPrint.ObservedAt <= TimeSpan.FromSeconds(5) ||
                    _standardNCurrentReceipt == null || ObservedGroupKey(group.Snapshot) != ObservedGroupKey(_standardNCurrentReceipt))
                ? group.PendingPrint
                : group.ObservedActiveDocumentIds.Except(group.PrintDocumentTimes.Keys)
                .Except(group.QueuedDocumentIds).Except(group.ResolvedEmptyDocumentIds).Any()
                ? new(markerAt, group.ObservedActiveDocumentIds.Except(group.PrintDocumentTimes.Keys)
                    .Except(group.QueuedDocumentIds).Except(group.ResolvedEmptyDocumentIds).ToArray(), cueId)
                : null,
        }).ToArray();
        // One bounded journal replacement records the entire immutable candidate set, or none.
        if (!SaveObservedReceiptGroups(changes)) return;
        _nextPhysicalReceiptProbe = DateTimeOffset.MinValue;
        ConfirmPhysicalReceiptPrints();
    }

    private void ConfirmPhysicalReceiptPrints()
    {
        var now = _receiptProofClock();
        if (_receiptObserversClosed || _physicalReceiptProbeBusy || now < _nextPhysicalReceiptProbe || !LoadObservedReceiptGroups()) return;
        bool PendingLive(StandardNObservedReceiptGroup group) => group.PendingPrint is { } cue &&
            now >= cue.ObservedAt && now - cue.ObservedAt <= TimeSpan.FromSeconds(5);
        var expired = _observedReceiptGroups.Values.Select(group => group.PendingPrint).Where(cue => cue != null &&
            now - cue.ObservedAt > TimeSpan.FromSeconds(5)).OrderByDescending(cue => cue!.ObservedAt).FirstOrDefault();
        if (expired != null && expired.CueId != _lastUnknownReceiptPrintCue)
        {
            _lastUnknownReceiptPrintCue = expired.CueId;
            Log("Печать не сопоставлена с проведённым физическим документом в окне повторной проверки; наблюдения сохранены без продажи.");
        }
        var candidates = _observedReceiptGroups.Values.Where(group =>
            (!_physicalReceiptRetryAfter.TryGetValue(ObservedGroupKey(group.Snapshot), out var retryAt) || now >= retryAt) &&
            (PendingLive(group) || group.PrintObserved &&
            (group.PrintDocumentTimes.Keys.Except(group.QueuedDocumentIds).Except(group.ResolvedEmptyDocumentIds).Any() ||
             group.Snapshot.Documents!.All(document => StandardNReceiptProjection.IsResolvedPhysicalDocument(group, document))))).ToArray();
        if (candidates.Length == 0) return;
        _physicalReceiptProbeBusy = true;
        _nextPhysicalReceiptProbe = now.AddSeconds(1);
        var generation = Volatile.Read(ref _physicalReceiptProbeGeneration);
        var fingerprints = candidates.ToDictionary(group => ObservedGroupKey(group.Snapshot),
            group => JsonSerializer.Serialize(group, EpharmJson.Options), StringComparer.Ordinal);
        var frozen = fingerprints.Values.Select(json => JsonSerializer.Deserialize<StandardNObservedReceiptGroup>(json, EpharmJson.Options)!).ToArray();
        _physicalReceiptProbeTask = Task.Run(() =>
        {
            IReadOnlyList<StandardNReceiptSnapshot> states = Array.Empty<StandardNReceiptSnapshot>();
            var available = false;
            try { available = _standardNDb?.TryGetPhysicalReceiptStates(frozen, out states) == true; }
            catch { /* A failed local read is UNKNOWN, never an empty or paid receipt. */ }
            try
            {
                Dispatcher.Invoke(() =>
                {
                    var stale = false;
                    try
                    {
                        if (_receiptObserversClosed) return;
                        stale = generation != Volatile.Read(ref _physicalReceiptProbeGeneration) ||
                            fingerprints.Any(entry => !_observedReceiptGroups.TryGetValue(entry.Key, out var current) ||
                                JsonSerializer.Serialize(current, EpharmJson.Options) != entry.Value);
                        if (stale || !available) return;
                        CommitPhysicalReceiptPrints(frozen, states);
                    }
                    catch (Exception error)
                    {
                        _nextPhysicalReceiptProbe = _receiptProofClock().AddSeconds(30);
                        Log("Подтверждение физического чека остановлено; локальные данные сохранены: " + error.GetBaseException().Message);
                    }
                    finally
                    {
                        _physicalReceiptProbeBusy = false;
                        if (stale && !_receiptObserversClosed)
                        {
                            _nextPhysicalReceiptProbe = DateTimeOffset.MinValue;
                            ConfirmPhysicalReceiptPrints();
                        }
                    }
                });
            }
            catch { /* Dispatcher shutdown never revives a receipt or writes a financial event. */ }
        });
    }

    private void CommitPhysicalReceiptPrints(IReadOnlyList<StandardNObservedReceiptGroup> candidates,
        IReadOnlyList<StandardNReceiptSnapshot> states)
    {
        var now = _receiptProofClock(); // A delayed first probe cannot anchor after the retry window.
        bool PendingLive(StandardNObservedReceiptGroup group) => group.PendingPrint is { } cue &&
            now >= cue.ObservedAt && now - cue.ObservedAt <= TimeSpan.FromSeconds(5);
        var fresh = states.ToDictionary(ObservedGroupKey, StringComparer.Ordinal);
        if (fresh.Count != candidates.Count || candidates.Any(group => !fresh.ContainsKey(ObservedGroupKey(group.Snapshot))))
            return;
        var changes = candidates.ToDictionary(group => ObservedGroupKey(group.Snapshot), StringComparer.Ordinal);
        foreach (var cueId in candidates.Where(PendingLive).Select(group => group.PendingPrint!.CueId).Distinct())
        {
            var batch = candidates.Where(group => group.PendingPrint?.CueId == cueId).ToArray();
            var marker = batch[0].PendingPrint!.ObservedAt;
            var alreadyAnchored = candidates.Where(group => group.AnchoredPrintCueIds.Contains(cueId)).ToArray();
            var eligible = batch.Where(group => fresh[ObservedGroupKey(group.Snapshot)].Documents!.Any(document =>
                group.PendingPrint!.CandidateDocumentIds.Contains(document.DocumentId) && !group.PrintDocumentTimes.ContainsKey(document.DocumentId) &&
                (StandardNReceiptProjection.IsPostedPhysicalSale(document) || StandardNReceiptProjection.IsPostedEmptyDocument(document)))).ToArray();
            // A doc-less marker cannot choose between several paid groups. It never binds to the current UI cart.
            var target = alreadyAnchored.Length == 1 ? alreadyAnchored[0] :
                alreadyAnchored.Length == 0 && eligible.Length == 1 ? eligible[0] : null;
            if (target == null) continue;
            foreach (var group in batch)
            {
                var key = ObservedGroupKey(group.Snapshot);
                if (key != ObservedGroupKey(target.Snapshot))
                {
                    changes[key] = changes[key] with { PendingPrint = null };
                    continue;
                }
                var times = new Dictionary<long, DateTimeOffset>(group.PrintDocumentTimes);
                foreach (var document in fresh[key].Documents!.Where(document =>
                             group.PendingPrint!.CandidateDocumentIds.Contains(document.DocumentId) &&
                             (StandardNReceiptProjection.IsPostedPhysicalSale(document) || StandardNReceiptProjection.IsPostedEmptyDocument(document))))
                    times.TryAdd(document.DocumentId, marker);
                changes[key] = changes[key] with { PrintObserved = times.Count > 0, PrintDocumentTimes = times,
                    AnchoredPrintCueIds = group.AnchoredPrintCueIds.Concat(new[] { cueId }).Distinct().ToArray() };
            }
        }
        // Anchoring A and clearing speculative B are committed together before any sale can leave.
        if (!SaveObservedReceiptGroups(changes.Values.ToArray())) return;
        foreach (var key in changes.Keys.ToArray())
        {
            var group = _observedReceiptGroups[key];
            if (!group.PrintObserved) continue;
            var state = fresh[key];
            var queued = group.QueuedDocumentIds.ToHashSet();
            var empty = group.ResolvedEmptyDocumentIds.ToHashSet();
            var session = CheckoutSession.RestoreObserved(group.CheckoutSessionId, group.PharmacistId,
                group.PharmacistName, group.Snapshot.SessionId);
            foreach (var document in state.Documents!)
            {
                if (!group.PrintDocumentTimes.TryGetValue(document.DocumentId, out var printedAt) ||
                    queued.Contains(document.DocumentId) || empty.Contains(document.DocumentId)) continue;
                if (StandardNReceiptProjection.IsPostedEmptyDocument(document))
                {
                    empty.Add(document.DocumentId);
                    Log("Пустой проведённый физический документ разрешён локально; денежный отчёт и финансовый ACK не создавались.");
                }
                else if (StandardNReceiptProjection.IsPostedPhysicalSale(document))
                {
                    if (!OnReceiptFinalized("standardn-print-log", document, session, printedAt))
                    {
                        _physicalReceiptRetryAfter[key] = now.AddSeconds(30);
                        continue;
                    }
                    queued.Add(document.DocumentId);
                }
            }
            // Keep unqueried active/held product metadata for later exact barcode/name
            // enrichment; its cached quantities are never payment proof.
            var observedDocuments = state.Documents!.Select(document => document.Status != 1 && document.Lines.Count == 0
                ? document with { Lines = group.Snapshot.Documents!.FirstOrDefault(old => old.DocumentId == document.DocumentId)?.Lines ?? document.Lines }
                : document).ToArray();
            group = group with { Snapshot = state with { Documents = observedDocuments,
                    Lines = observedDocuments.SelectMany(document => document.Lines).ToArray() },
                QueuedDocumentIds = queued.ToArray(), ResolvedEmptyDocumentIds = empty.ToArray() };
            if (!SaveObservedReceiptGroup(group)) continue;
            if (state.Documents!.Any(document => !StandardNReceiptProjection.IsResolvedPhysicalDocument(group, document))) continue;
            _receiptArtifacts!.RetireObservedGroup(group);
            _observedReceiptGroups.Remove(key);
            _physicalReceiptRetryAfter.Remove(key);
            _receiptObservations.LogChanged();
            _lastFinalizedStandardNDocumentId = state.DocumentId;
            if (_standardNCurrentReceipt == null || ObservedGroupKey(_standardNCurrentReceipt) != key) continue;
            _activeReceiptDraftId = null;
            _standardNDocumentId = null;
            _standardNCurrentReceipt = null;
            ReceiptItems.Clear();
            RecalcTotal();
            ResetRecommendationUiState(closeWindows: true);
            StartNewCheckoutSession();
        }
    }

    private static string ObservedGroupKey(StandardNReceiptSnapshot snapshot) =>
        snapshot.SessionId + ":" + snapshot.DocumentId + ":" + snapshot.LinkId + ":" +
        StaffSessionResolver.CanonicalComputer(snapshot.WorkstationName);

    private bool CanMutateLogReceiptItem(ReceiptItem item)
    {
        if (!item.SourceDocumentId.HasValue) return true;
        if (_standardNCurrentReceipt == null || !_observedReceiptGroups.TryGetValue(
                ObservedGroupKey(_standardNCurrentReceipt), out var group)) return false;
        return !group.QueuedDocumentIds.Contains(item.SourceDocumentId.Value) &&
               group.Snapshot.Documents!.SingleOrDefault(document => document.DocumentId == item.SourceDocumentId)?.Status == 0;
    }

    private bool LoadObservedReceiptGroups()
    {
        if (_observedReceiptGroupsLoaded) return _observedReceiptGroupsAvailable;
        _observedReceiptGroupsLoaded = true;
        try
        {
            if (_receiptArtifacts == null) throw new IOException("Локальное хранилище наблюдений недоступно.");
            foreach (var group in _receiptArtifacts.ReadObservedGroups())
            {
                if (!string.Equals(group.PharmacyId, _posmConfig?.PharmacyId, StringComparison.Ordinal))
                    throw new InvalidDataException("Сохранённые наблюдения относятся к другой аптеке; автоматическая перепривязка запрещена.");
                _observedReceiptGroups.Add(ObservedGroupKey(group.Snapshot), group);
            }
        }
        catch (Exception error)
        {
            _observedReceiptGroupsAvailable = false;
            Log("Наблюдения физических чеков сохранены, но не подтверждены: " + error.GetBaseException().Message);
        }
        return _observedReceiptGroupsAvailable;
    }

    private bool SaveObservedReceiptGroup(StandardNObservedReceiptGroup group)
    {
        try
        {
            if (_receiptArtifacts == null || !_receiptArtifacts.SaveObservedGroup(group))
            {
                Log("Достигнут предел незавершённых наблюдений или хранилище недоступно: старые данные не удалены, новая группа не подтверждена.");
                return false;
            }
            _observedReceiptGroups[ObservedGroupKey(group.Snapshot)] = group;
            return true;
        }
        catch (Exception error)
        {
            Log("Наблюдение группы не сохранено; автоматическое подтверждение остановлено: " + error.GetBaseException().Message);
            return false;
        }
    }

    private StandardNReceiptSnapshot RememberObservedReceipt(StandardNReceiptSnapshot receipt)
    {
        _standardNCurrentReceipt = receipt;
        if (receipt.Documents == null || !LoadObservedReceiptGroups()) return receipt;
        var key = ObservedGroupKey(receipt);
        _observedReceiptGroups.TryGetValue(key, out var previous);
        if (previous != null && previous.PharmacistId != receipt.Pharmacist.Id)
        {
            Log("Кассир сохранённой группы не совпадает с новой сессией: данные сохранены без автоматической перепривязки.");
            return receipt;
        }

        // A child can already be posted while the same logical cart still has active children.
        // Its last displayed rows remain visible, but are NEVER used as final payment details.
        var documents = receipt.Documents.Select(document =>
        {
            var observed = previous?.Snapshot.Documents?.FirstOrDefault(old => old.DocumentId == document.DocumentId);
            return document.Status == 1 && document.Lines.Count == 0 && observed != null
                ? document with { Lines = observed.Lines, FinalDetailsRead = false } : document;
        }).ToArray();
        var effective = receipt with
        {
            Documents = documents,
            Lines = documents.SelectMany(document => document.Lines).ToArray(),
            Pharmacist = previous?.Snapshot.Pharmacist ?? receipt.Pharmacist,
        };
        var group = previous == null
            ? new StandardNObservedReceiptGroup(1, _posmConfig?.PharmacyId ?? "", effective, _session.SessionId,
                receipt.Pharmacist.Id, receipt.Pharmacist.Name, false, Array.Empty<long>())
                { ObservedActiveDocumentIds = documents.Where(document => document.Status == 0).Select(document => document.DocumentId).ToArray(),
                    InitiallyPostedDocumentIds = documents.Where(StandardNReceiptProjection.HasPostedFiscalHeader).Select(document => document.DocumentId).ToArray() }
            : previous with { Snapshot = effective, ObservedActiveDocumentIds = previous.ObservedActiveDocumentIds.Concat(
                documents.Where(document => document.Status == 0).Select(document => document.DocumentId)).Distinct().ToArray() };
        if (SaveObservedReceiptGroup(group))
        {
            _session = CheckoutSession.RestoreObserved(group.CheckoutSessionId, group.PharmacistId,
                group.PharmacistName, group.Snapshot.SessionId);
            _standardNCurrentReceipt = effective;
            SavePhysicalReceiptDrafts();
        }
        return effective;
    }

    private void SavePhysicalReceiptDrafts()
    {
        if (_standardNCurrentReceipt?.Documents == null || _receiptArtifacts == null || _saleReporter == null ||
            !_observedReceiptGroups.TryGetValue(ObservedGroupKey(_standardNCurrentReceipt), out var group)) return;
        try
        {
            var session = CheckoutSession.RestoreObserved(group.CheckoutSessionId, group.PharmacistId,
                group.PharmacistName, group.Snapshot.SessionId);
            foreach (var document in group.Snapshot.Documents!.Where(document => document.Status == 0 &&
                         !group.QueuedDocumentIds.Contains(document.DocumentId)))
            {
                var draft = _saleReporter.Build(session, PhysicalReceiptItems(document), document.DocumentId,
                    "standardn-active", DateTimeOffset.UtcNow);
                if (draft != null) _receiptArtifacts.SaveDraft(draft);
            }
            _activeReceiptDraftId = null; // Each physical draft has its own stable sale id.
        }
        catch (Exception error)
        {
            Log("Физические черновики сохранены не полностью; наблюдение группы оставлено для проверки: " + error.GetBaseException().Message);
        }
    }

    private static IReadOnlyList<ReceiptItem> PhysicalReceiptItems(StandardNReceiptDocument document) =>
        document.Lines.Select(line => new ReceiptItem
        {
            PartId = line.PartId, Barcode = line.Barcode, Name = line.Name, Price = line.Price,
            Qty = line.Qty, DiscountPercent = line.DiscountPercent, SourceDocumentId = document.DocumentId,
            SourceLineId = line.LineId, SourceTotal = line.SourceTotal,
        }).ToArray();
}
