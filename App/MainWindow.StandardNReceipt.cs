using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Models;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    public partial class MainWindow
    {
        private CancellationTokenSource? _standardNReceiptCts;
        private bool _standardNReceiptInitialized;
        private long? _standardNDocumentId;
        private long? _lastFinalizedStandardNDocumentId;
        private readonly ReceiptObservationGate _receiptObservations = new();

        private void StartStandardNReceiptPolling()
        {
            if (_posmConfig?.StandardNDbEnabled != true || _standardNDb == null) return;

            _standardNReceiptCts?.Cancel();
            _standardNReceiptCts = new CancellationTokenSource();
            var token = _standardNReceiptCts.Token;
            var pollMs = Math.Clamp(_posmConfig.StandardNReceiptPollMs, 200, 5000);
            Log($"Монитор активного чека Standard-N запущен: DOCS/DOC_DETAIL_ACTIVE каждые {pollMs}мс");
            _ = Task.Run(() => StandardNReceiptPollingLoop(pollMs, token), token);
        }

        private async Task StandardNReceiptPollingLoop(int pollMs, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                var querySucceeded = false;
                try
                {
                    var readRevision = _receiptObservations.Revision;
                    if (_standardNDb?.TryGetCurrentReceipt(out var receipt) == true)
                    {
                        querySucceeded = true;
                        Dispatcher.Invoke(() =>
                        {
                            if (_receiptObservations.Admit(readRevision, receipt != null))
                                ApplyStandardNReceipt(receipt);
                        });
                    }
                    else
                    {
                        Dispatcher.Invoke(() => _receiptObservations.ReadFailed());
                    }
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() => _receiptObservations.ReadFailed());
                    Log($"Монитор активного чека Standard-N временно недоступен: {ex.GetBaseException().Message}");
                }

                // A local healthy database is polled quickly for responsive recommendations. When
                // Firebird is unavailable or rejects authentication, back off instead of retrying
                // several times per second and adding noise/load to the pharmacy server.
                var nextDelayMs = querySucceeded ? pollMs : Math.Max(pollMs, 5000);
                try { await Task.Delay(nextDelayMs, token).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
            }
        }

        private void ApplyStandardNReceipt(StandardNReceiptSnapshot? receipt)
        {
            ConfirmPhysicalReceiptPrints();
            if (receipt == null)
            {
                if (!_standardNReceiptInitialized)
                {
                    _standardNReceiptInitialized = true;
                    Log("Монитор активного чека Standard-N подключен; открытого чека пока нет");
                }

                // Session/doc switches are not atomic from an observer's point of view. Requiring
                // two authoritative empty reads prevents a one-poll gap from flashing the receipt.
                if (_receiptObservations.ShouldClearMissingReceipt(
                        _standardNDocumentId.HasValue, ReceiptItems.Count > 0))
                {
                    var closingDocumentId = _standardNDocumentId;
                    Log($"Активный чек Standard-N закрыт: doc={closingDocumentId?.ToString() ?? "—"}");
                    var finalized = closingDocumentId.HasValue &&
                                    _lastFinalizedStandardNDocumentId == closingDocumentId;
                    if (!finalized)
                    {
                        // An absent receipt may be suspended/cancelled; it is not payment evidence.
                        PreserveUnconfirmedReceiptDraft();
                        ResetRecommendationUiState(closeWindows: true);
                        StartNewCheckoutSession();
                    }
                    _standardNDocumentId = null;
                    _standardNCurrentReceipt = null;
                    ReceiptItems.Clear();
                    RecalcTotal();
                }
                return;
            }

            ApplyStandardNPharmacist(receipt.Pharmacist);

            // Print-log marker can arrive slightly before DOCS stops exposing the active receipt.
            // Do not repopulate the just-finalized cart while Firebird still returns that document.
            if (_lastFinalizedStandardNDocumentId == receipt.DocumentId)
            {
                _standardNReceiptInitialized = true;
                return;
            }

            var documentChanged = _standardNDocumentId != receipt.DocumentId;
            if (documentChanged)
            {
                if (_standardNDocumentId.HasValue)
                {
                    Log($"Standard-N переключил активный чек: {_standardNDocumentId.Value} -> {receipt.DocumentId}");
                    var previousDocumentId = _standardNDocumentId.Value;
                    var previousFinalized = _lastFinalizedStandardNDocumentId == previousDocumentId;
                    if (!previousFinalized)
                    {
                        PreserveUnconfirmedReceiptDraft();
                        ResetRecommendationUiState(closeWindows: true);
                        StartNewCheckoutSession();
                    }
                    ReceiptItems.Clear();
                }
                else
                {
                    Log($"Standard-N открыл чек: doc={receipt.DocumentId}, session={receipt.SessionId}");
                    // Если лог успел увидеть первые товары раньше Firebird, это тот же чек. Меняем
                    // только стабильный ключ черновика, не сбрасывая seller/session и корзину.
                    if (ReceiptItems.Count > 0) DiscardActiveReceiptDraft();
                }
                _standardNDocumentId = receipt.DocumentId;
            }

            receipt = RememberObservedReceipt(receipt);

            var targetPartIds = receipt.Lines.Select(line => line.PartId).ToHashSet();
            var changed = documentChanged;
            var recommendationAction = ReceiptRecommendationAction.None;
            foreach (var stale in ReceiptItems
                         .Where(item => item.PartId > 0 && (item.SourceDocumentId.HasValue
                             ? !receipt.Lines.Any(line => line.SourceDocumentId == item.SourceDocumentId && line.LineId == item.SourceLineId)
                             : !targetPartIds.Contains(item.PartId)))
                         .ToList())
            {
                ReceiptItems.Remove(stale);
                changed = true;
                recommendationAction = ReceiptRecommendationChange.Combine(
                    recommendationAction,
                    ReceiptRecommendationAction.CancelPending);
                Log($"БД Standard-N: позиция удалена из чека (PartId={stale.PartId})");
            }

            ReceiptItem? scanned = null;
            long scannedLineId = long.MinValue;
            foreach (var line in receipt.Lines)
            {
                var existing = line.SourceDocumentId.HasValue
                    ? ReceiptItems.FirstOrDefault(item => item.SourceDocumentId == line.SourceDocumentId && item.SourceLineId == line.LineId)
                      ?? ReceiptItems.FirstOrDefault(item => !item.SourceDocumentId.HasValue && item.PartId == line.PartId)
                    : ReceiptItems.FirstOrDefault(item => item.PartId == line.PartId);
                var incoming = new ReceiptItem
                {
                    PartId = line.PartId,
                    // The log and Firebird projections do not always expose the same optional
                    // identity columns. A blank secondary observation must not create a synthetic
                    // change every 400 ms or erase data already used by an in-flight request.
                    Barcode = ReceiptRecommendationChange.MergeIdentity(line.Barcode, existing?.Barcode),
                    Name = ReceiptRecommendationChange.MergeIdentity(line.Name, existing?.Name) ?? "",
                    Price = line.Price,
                    Qty = line.Qty,
                    DiscountPercent = line.DiscountPercent,
                    SourceDocumentId = line.SourceDocumentId,
                    SourceLineId = line.SourceDocumentId.HasValue ? line.LineId : null,
                    SourceTotal = line.SourceTotal,
                };

                var lineAction = ReceiptRecommendationChange.ClassifyLine(
                    existed: existing != null,
                    previousQty: existing?.Qty ?? 0m,
                    previousBarcode: existing?.Barcode,
                    previousName: existing?.Name,
                    nextQty: incoming.Qty,
                    nextBarcode: incoming.Barcode,
                    nextName: incoming.Name);

                var lineChanged = existing == null ||
                                  existing.Qty != incoming.Qty ||
                                  existing.Price != incoming.Price ||
                                  existing.DiscountPercent != incoming.DiscountPercent ||
                                  existing.SourceDocumentId != incoming.SourceDocumentId ||
                                  existing.SourceLineId != incoming.SourceLineId ||
                                  existing.SourceTotal != incoming.SourceTotal ||
                                  !string.Equals(existing.Name, incoming.Name, StringComparison.Ordinal) ||
                                  !string.Equals(existing.Barcode, incoming.Barcode, StringComparison.Ordinal);
                if (!lineChanged) continue;

                var increased = UpsertItemSetQty(incoming);
                changed = true;
                recommendationAction = ReceiptRecommendationChange.Combine(recommendationAction, lineAction);
                if ((increased || lineAction == ReceiptRecommendationAction.Refresh) && line.LineId >= scannedLineId)
                {
                    scanned = incoming;
                    scannedLineId = line.LineId;
                }
            }

            _standardNReceiptInitialized = true;
            if (!changed) return;

            RecalcTotal();
            OnCartChangedLocalOnly(
                cancelPendingRecommendation: recommendationAction != ReceiptRecommendationAction.None);

            // Evaluate an already-open receipt once after a POSM restart as well. This recovers the
            // popup after a crash/update instead of permanently missing the scan that happened while
            // the client was down. Duplicate log+Firebird observations are classified as None above.
            if (scanned != null)
            {
                LogScannedItemContext(scanned, "активный чек БД Standard-N");
                OnProductScanned(scanned);
            }
        }

        private bool UpsertItemSetQty(ReceiptItem incoming)
        {
            if (!incoming.SourceDocumentId.HasValue && ReceiptItems.Any(item => item.PartId == incoming.PartId && !CanMutateLogReceiptItem(item)))
            {
                Log("Изменение лога не относится к подтверждённой активной физической строке; ожидается снимок БД.");
                return false;
            }
            if (!incoming.SourceDocumentId.HasValue && ReceiptItems.Count(item => item.PartId == incoming.PartId) > 1)
            {
                Log("Событие лога без DOCS.ID неоднозначно для нескольких физических строк; ожидается снимок БД.");
                return false;
            }
            var existing = incoming.SourceDocumentId.HasValue
                ? ReceiptItems.FirstOrDefault(item => item.SourceDocumentId == incoming.SourceDocumentId && item.SourceLineId == incoming.SourceLineId)
                  ?? ReceiptItems.FirstOrDefault(item => !item.SourceDocumentId.HasValue && item.PartId == incoming.PartId)
                : ReceiptItems.FirstOrDefault(item => item.PartId == incoming.PartId);

            if (incoming.Qty <= 0)
            {
                RemoveItemByPartId(incoming.PartId);
                return false;
            }

            if (existing == null)
            {
                ReceiptItems.Insert(0, incoming);
                Log($"Добавили новую позицию (PartId={incoming.PartId}): {incoming.Name}, qty={incoming.Qty}");
                return true;
            }

            // обновляем поля + ставим новое количество
            var idx = ReceiptItems.IndexOf(existing);
            var previousQty = existing.Qty;
            // Plain receipt rows do not notify WPF: replace the bound object without mutating it.
            existing = existing.Clone();
            // A duplicate observation from the other Standard-N source can omit presentation fields.
            // Preserve already resolved identity so it cannot cancel or degrade a valid backend request.
            if (!string.IsNullOrWhiteSpace(incoming.Name)) existing.Name = incoming.Name;
            // Не затираем уже пойманный штрих-код, если повторная строка лога (qty-bump/скидка) его не несёт.
            if (!string.IsNullOrWhiteSpace(incoming.Barcode)) existing.Barcode = incoming.Barcode;
            existing.Price = incoming.Price;
            existing.DiscountPercent = incoming.DiscountPercent;
            existing.Qty = incoming.Qty;
            if (incoming.SourceDocumentId.HasValue)
            {
                existing.SourceDocumentId = incoming.SourceDocumentId;
                existing.SourceLineId = incoming.SourceLineId;
            }
            existing.SourceTotal = incoming.SourceTotal;

            // чтобы UI точно обновился без INotifyPropertyChanged — пере-вставляем
            ReceiptItems[idx] = existing;

            Log($"Обновили позицию (PartId={incoming.PartId}): qty={existing.Qty}");
            return incoming.Qty > previousQty;
        }

        private bool RemoveItemByPartId(int partId)
        {
            var matches = ReceiptItems.Where(item => item.PartId == partId).ToArray();
            if (matches.Length > 1)
            {
                Log("Удаление из лога без DOCS.ID неоднозначно; ожидается точный снимок физических строк БД.");
                return false;
            }
            if (matches.Length == 0) return false;
            if (!CanMutateLogReceiptItem(matches[0])) return false;
            ReceiptItems.Remove(matches[0]);
            return true;
        }

        private void ApplyDiscountToAllItems(decimal percent)
        {
            percent = Math.Round(percent, 2, MidpointRounding.AwayFromZero);
            for (var index = 0; index < ReceiptItems.Count; index++)
            {
                if (!CanMutateLogReceiptItem(ReceiptItems[index])) continue;
                var item = ReceiptItems[index].Clone();
                item.DiscountPercent = percent;
                item.SourceTotal = null;
                ReceiptItems[index] = item;
            }
        }

        /// <summary>
        /// Вызывается при печати/завершении чека ДО очистки позиций. Фиксирует продажу (источник №1),
        /// закрывает popup, открывает новую сессию.
        /// </summary>
        private bool OnReceiptFinalized(string captureSource, StandardNReceiptDocument proof,
            CheckoutSession capturedSession, DateTimeOffset markerAt)
        {
            var sourceDocumentId = proof.DocumentId;
            if (!ReceiptFinalizationRules.IsConfirmedPrint(captureSource) ||
                !StandardNReceiptProjection.IsPostedPhysicalSale(proof) || capturedSession.StandardNSessionId != proof.SessionId)
            {
                Log("Завершение чека отклонено: сигнал печати и проведённый физический документ не подтверждены вместе.");
                return false;
            }
            if (!StandardNReceiptProjection.CanRepresentLongKzt(proof))
            {
                Log("Точность или размер суммы физического чека не поддерживаются целочисленным KZT-отчётом; исходные наблюдения сохранены, продажа и ACK не созданы.");
                return false;
            }
            var sale = _saleReporter?.Build(
                capturedSession,
                PhysicalReceiptItems(proof),
                sourceDocumentId,
                captureSource,
                markerAt);
            if (sale != null)
            {
                FiscalReceiptCaptureResult? fiscalCapture = null;
                try
                {
                    fiscalCapture = _receiptArtifacts?.Complete(sale);
                }
                catch (Exception ex)
                {
                    // Waiting/Rejected are normal results. An exception here instead means local
                    // persistence/identity preservation failed, so do not bypass that guard.
                    Log($"Чек не сохранён в подтверждённый pending; текущая корзина оставлена для проверки: {ex.GetBaseException().Message}");
                    return false;
                }

                try
                {
                    _saleReporter?.Enqueue(sale);
                }
                catch (Exception ex)
                {
                    var retained = fiscalCapture != null
                        ? "Локальный pending сохранён; текущая корзина оставлена для проверки."
                        : "Текущая корзина оставлена в окне для проверки; локальный pending недоступен.";
                    Log($"Подтверждённый чек не поставлен в очередь. {retained} {ex.GetBaseException().Message}");
                    return false;
                }
                var fiscalStatus = _receiptArtifacts == null
                    ? "захват недоступен"
                    : fiscalCapture?.Status switch
                    {
                        FiscalReceiptCaptureStatus.Stored => "оригинал сохранён",
                        FiscalReceiptCaptureStatus.Rejected => "источник отклонён",
                        _ => "ожидается оригинал ККМ/OFD",
                    };
                Log($"Чек поставлен в гарантированную очередь: id={sale.SaleId}, " +
                    $"doc={sourceDocumentId}, items={sale.Items.Count}, " +
                    $"total={sale.TotalAmount}, fiscal={fiscalStatus}");
            }
            else
            {
                Log($"Завершение чека пропущено: нет реальных позиций Standard-N (source={captureSource})");
            }

            return sale != null;
        }

        private void PreserveUnconfirmedReceiptDraft()
        {
            // Only active/ is retained. Recovery never promotes an unconfirmed draft to pending/.
            _activeReceiptDraftId = null;
            Log("Граница чека Standard-N не подтверждает оплату: незавершённый черновик сохранён без продажи.");
        }

        private void HandleStandardNReceiptPrinted()
        {
            _receiptObservations.LogChanged();
            RecordPhysicalReceiptPrint();
        }

        private void ApplyStandardNPharmacist(StandardNActivePharmacist pharmacist)
        {
            var previousId = _currentPharmacistId;
            var previousSession = _currentStandardNSessionId;
            _currentPharmacistId = pharmacist.Id;
            _currentPharmacistName = pharmacist.Name ?? "";
            _currentStandardNSessionId = pharmacist.SessionId;
            _pharmacistFromDb = true;

            if (!string.Equals(previousId, pharmacist.Id, StringComparison.OrdinalIgnoreCase) ||
                previousSession != pharmacist.SessionId)
            {
                Log($"Активный фармацевт из локальной кассовой сессии Standard-N: " +
                    $"id={pharmacist.Id}, name={pharmacist.Name ?? "—"}, " +
                    $"session={pharmacist.SessionId?.ToString() ?? "—"}");
            }
        }
    }
}
