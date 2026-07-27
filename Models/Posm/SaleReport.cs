using System;
using System.Collections.Generic;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>Позиция в отчёте о продаже (источник №1 сверки).</summary>
    public sealed class SaleReportItem
    {
        public string? Sku { get; set; }       // iPartID кассы — диагностика
        public string? Barcode { get; set; }    // EAN-13 — ключ сверки (тот же, что в /recommend)
        public string Name { get; set; } = "";  // sname — fallback-сверка
        public double Qty { get; set; } = 1.0;
        public long Price { get; set; }
        public long Total { get; set; }
    }

    /// <summary>
    /// Завершённый чек, подтверждённый логом кассы. Зеркалит kz.epharm.posm.dto.PosSaleRequest.
    /// SaleId — client-GUID для идемпотентности (повторная отправка из outbox не двоит сверку).
    /// </summary>
    public sealed class SaleReport
    {
        public string SaleId { get; set; } = Guid.NewGuid().ToString("N");
        public string PharmacistId { get; set; } = "";
        public string PharmacistName { get; set; } = "";
        public string PharmacyId { get; set; } = "";
        public string? SessionId { get; set; }
        public string? FiscalId { get; set; }
        public string? Cashier { get; set; }
        public string? Shift { get; set; }
        public long TotalAmount { get; set; }
        public List<SaleReportItem> Items { get; set; } = new();
        public DateTimeOffset PrintedAt { get; set; } = DateTimeOffset.UtcNow;
    }

    /// <summary>Полезная нагрузка для отложенной отправки результата рекомендации (в outbox).</summary>
    public sealed class OutboxOutcomePayload
    {
        public string EventId { get; set; } = "";
        public string Outcome { get; set; } = "";
        public string? FinalSku { get; set; }
    }

    /// <summary>
    /// Полезная нагрузка пинга «рекомендация показана» (в outbox, V032). Гарантированная доставка
    /// факта и времени показа фармацевту — даже при пропадании интернета/форс-мажоре доезжает,
    /// когда связь восстановится.
    /// </summary>
    public sealed class OutboxShownPayload
    {
        public string EventId { get; set; } = "";
        public DateTimeOffset ShownAt { get; set; } = DateTimeOffset.UtcNow;
    }
}
