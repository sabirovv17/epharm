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
        private int _standardNEmptyPolls;

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
                    if (_standardNDb?.TryGetCurrentReceipt(out var receipt) == true)
                    {
                        querySucceeded = true;
                        Dispatcher.Invoke(() => ApplyStandardNReceipt(receipt));
                    }
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception ex)
                {
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
            if (receipt == null)
            {
                _standardNEmptyPolls++;
                if (!_standardNReceiptInitialized)
                {
                    _standardNReceiptInitialized = true;
                    Log("Монитор активного чека Standard-N подключен; открытого чека пока нет");
                    return;
                }

                // Session/doc switches are not atomic from an observer's point of view. Requiring
                // two authoritative empty reads prevents a one-poll gap from flashing the receipt.
                if (_standardNDocumentId.HasValue && _standardNEmptyPolls >= 2)
                {
                    Log($"Активный чек Standard-N закрыт: doc={_standardNDocumentId.Value}");
                    _standardNDocumentId = null;
                    ReceiptItems.Clear();
                    RecalcTotal();
                    OnCartChangedLocalOnly();
                }
                return;
            }

            _standardNEmptyPolls = 0;
            ApplyStandardNPharmacist(receipt.Pharmacist);

            var wasInitialized = _standardNReceiptInitialized;
            var documentChanged = _standardNDocumentId != receipt.DocumentId;
            if (documentChanged)
            {
                if (_standardNDocumentId.HasValue)
                    Log($"Standard-N переключил активный чек: {_standardNDocumentId.Value} -> {receipt.DocumentId}");
                else
                    Log($"Standard-N открыл чек: doc={receipt.DocumentId}, session={receipt.SessionId}");

                ReceiptItems.Clear();
                ResetRecommendationUiState(closeWindows: true);
                StartNewCheckoutSession();
                _standardNDocumentId = receipt.DocumentId;
            }

            var targetPartIds = receipt.Lines.Select(line => line.PartId).ToHashSet();
            var changed = documentChanged;
            foreach (var stale in ReceiptItems
                         .Where(item => item.PartId > 0 && !targetPartIds.Contains(item.PartId))
                         .ToList())
            {
                ReceiptItems.Remove(stale);
                changed = true;
                Log($"БД Standard-N: позиция удалена из чека (PartId={stale.PartId})");
            }

            ReceiptItem? scanned = null;
            long scannedLineId = long.MinValue;
            foreach (var line in receipt.Lines)
            {
                var existing = ReceiptItems.FirstOrDefault(item => item.PartId == line.PartId);
                var incoming = new ReceiptItem
                {
                    PartId = line.PartId,
                    Barcode = line.Barcode,
                    Name = line.Name,
                    Price = line.Price,
                    Qty = line.Qty,
                    DiscountPercent = line.DiscountPercent,
                };

                var lineChanged = existing == null ||
                                  existing.Qty != incoming.Qty ||
                                  existing.Price != incoming.Price ||
                                  existing.DiscountPercent != incoming.DiscountPercent ||
                                  !string.Equals(existing.Name, incoming.Name, StringComparison.Ordinal) ||
                                  !string.Equals(existing.Barcode, incoming.Barcode, StringComparison.Ordinal);
                if (!lineChanged) continue;

                var increased = UpsertItemSetQty(incoming);
                changed = true;
                if (increased && line.LineId >= scannedLineId)
                {
                    scanned = incoming;
                    scannedLineId = line.LineId;
                }
            }

            _standardNReceiptInitialized = true;
            if (!changed) return;

            RecalcTotal();
            OnCartChangedLocalOnly();

            // Do not reopen popups for products that were already in the receipt when POSM itself
            // started. Every later new/increased database line is a real cashier cart change.
            if (wasInitialized && scanned != null)
            {
                LogScannedItemContext(scanned, "активный чек БД Standard-N");
                OnProductScanned(scanned);
            }
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
