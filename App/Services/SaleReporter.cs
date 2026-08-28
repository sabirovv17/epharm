using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Формирует неизменяемый снимок завершённого чека и ставит его в outbox. Построение отделено
    /// от постановки в очередь: ReceiptArtifactStore успевает атомарно сохранить локальную копию
    /// до того, как фоновый flusher сможет получить подтверждение backend.
    /// </summary>
    public sealed class SaleReporter
    {
        private readonly EpharmConfig _cfg;
        private readonly OfflineOutbox _outbox;

        public SaleReporter(EpharmConfig cfg, OfflineOutbox outbox)
        {
            _cfg = cfg;
            _outbox = outbox;
        }

        public SaleReport? Build(
            CheckoutSession session,
            IEnumerable<ReceiptItem> items,
            long? sourceDocumentId,
            string captureSource,
            DateTimeOffset completedAt)
        {
            var list = items
                // Report only real Standard-N rows. Accepted recommendations are mirrored in UI
                // with negative synthetic PartId until the cashier actually adds them.
                .Where(i => i.PartId > 0)
                .Select(i => new SaleReportItem
                {
                    Sku = i.PartId.ToString(),   // диагностика
                    Barcode = i.Barcode,          // EAN-13 — тот же ключ, что в /recommend (может быть null)
                    Name = i.Name,                // fallback-сверка по имени
                    Qty = (double)i.Qty,
                    Price = (long)Math.Round(i.Price),
                    Total = (long)Math.Round(i.Total),
                })
                .ToList();

            if (list.Count == 0) return null;

            var sale = new SaleReport
            {
                SaleId = ReceiptSaleId.Create(_cfg.PharmacyId, sourceDocumentId, session.SessionId),
                // В боевом режиме фармацевт берётся из активного пользователя/сессии Стандарт-Н.
                // Если БД включена, но недоступна/пуста, отправляем пусто, а не устаревший fallback.
                PharmacistId = !string.IsNullOrWhiteSpace(session.PharmacistId)
                    ? session.PharmacistId
                    : (_cfg.StandardNDbEnabled ? "" : _cfg.PharmacistId),
                PharmacistName = session.PharmacistName,
                PharmacyId = _cfg.PharmacyId,
                SessionId = session.SessionId,
                SourceDocumentId = sourceDocumentId,
                CaptureSource = captureSource,
                ArtifactFormat = "png",
                // FiscalId / Cashier — из лога Стандарт-Н (формат уточняется пилотом, missing data #1).
                TotalAmount = list.Sum(x => x.Total),
                Items = list,
                PrintedAt = completedAt,
            };
            return sale;
        }

        public void Enqueue(SaleReport sale)
        {
            _outbox.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(sale, EpharmJson.Options));
        }
    }
}
