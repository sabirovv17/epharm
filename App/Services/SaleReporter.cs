using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>Единые опции JSON для обмена с backend (camelCase).</summary>
    public static class EpharmJson
    {
        public static readonly JsonSerializerOptions Options = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
        };
    }

    /// <summary>
    /// Формирует отчёт о завершённом чеке (источник №1 сверки) из позиций кассы и кладёт в outbox
    /// для гарантированной доставки. Вызывается при печати чека, до сброса сессии.
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

        public void Report(CheckoutSession session, IEnumerable<ReceiptItem> items)
        {
            var list = items
                .Where(i => i.PartId != 0)
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

            if (list.Count == 0) return;

            var sale = new SaleReport
            {
                SaleId = "sale_" + Guid.NewGuid().ToString("N").Substring(0, 16),
                PharmacistId = _cfg.PharmacistId,
                PharmacyId = _cfg.PharmacyId,
                SessionId = session.SessionId,
                // FiscalId / Cashier — из лога Стандарт-Н (формат уточняется пилотом, missing data #1).
                TotalAmount = list.Sum(x => x.Total),
                Items = list,
                PrintedAt = DateTimeOffset.UtcNow,
            };

            _outbox.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(sale, EpharmJson.Options));
        }
    }
}
