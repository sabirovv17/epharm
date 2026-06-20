using System.Collections.Generic;

namespace CustomerDisplay.Models.Posm
{
    // DTO для обмена с backend POSM Rules Engine (/api/posm/*).
    // Имена полей зеркалят kz.epharm.posm.dto.* (Kotlin). Сериализация — camelCase
    // (настроена в EpharmApiClient через JsonSerializerOptions).

    /// <summary>
    /// Позиция корзины. Матчинг на backend: сначала по Barcode (EAN-13), затем fallback по Name.
    /// Sku (iPartID Стандарт-Н) — только для диагностики, в матчинге НЕ участвует.
    /// </summary>
    public sealed class CartItem
    {
        public string? Sku { get; set; }       // legacy iPartID кассы — диагностика, НЕ ключ матчинга
        public string? Barcode { get; set; }    // EAN-13 — ПЕРВИЧНЫЙ ключ матчинга
        public string? Name { get; set; }       // sname из лога — FALLBACK ключ матчинга
        public double Qty { get; set; } = 1.0;
    }

    /// <summary>Запрос рекомендаций: вся текущая корзина чека + (опц.) отсканированный товар.</summary>
    public sealed class RecommendRequest
    {
        public string PharmacistId { get; set; } = "";
        public string PharmacyId { get; set; } = "";
        public string SessionId { get; set; } = "";
        public string? ScannedBarcode { get; set; }   // последний отсканированный EAN-13 (информационно)
        public List<CartItem> Cart { get; set; } = new();
    }

    /// <summary>Строка таблицы «Сравнение» (триггер-товар vs рекомендованный).</summary>
    public sealed class ComparisonRow
    {
        public string Label { get; set; } = "";            // «Состав», «Объём», ...
        public string TriggerValue { get; set; } = "";     // значение у исходного товара
        public string RecommendValue { get; set; } = "";   // значение у рекомендованного (можно с «✓ »)
        public bool RecommendHighlight { get; set; }       // выделить зелёным (преимущество)
    }

    /// <summary>
    /// Одна рекомендация для popup фармацевта. Зеркалит backend RecommendResponse — ВСЕ поля
    /// карточки задаются в админке (Rules Engine) и пуллятся сюда, без расхождений. Опциональные
    /// поля (вендор, сравнение, цель) при пустом значении в карточке скрываются — шаблон
    /// один, наполнение зависит от правила.
    /// </summary>
    public sealed class Recommendation
    {
        public string EventId { get; set; } = "";
        public string RuleId { get; set; } = "";
        public string Kind { get; set; } = "";             // substitution | crosssell

        // Что попросил покупатель (исходный товар)
        public string? TriggerSku { get; set; }
        public string? TriggerName { get; set; }
        public string? TriggerVolume { get; set; }         // «150 мл»
        public int? TriggerPrice { get; set; }             // 2390
        public string? TriggerBarcode { get; set; }        // EAN-13 исходного товара

        // Что предложить вместо / в дополнение
        public string RecommendSku { get; set; } = "";
        public string RecommendName { get; set; } = "";
        public string? RecommendBarcode { get; set; }      // EAN-13 рекомендованного (лог + позиция чека)
        public string? RecommendVendor { get; set; }       // «Jadran Galenski»
        public string? RecommendVolume { get; set; }       // «150 мл»
        public string? RecommendStock { get; set; }        // «в наличии 14 уп.»
        public int RecommendPrice { get; set; }
        public string? PartnerLabel { get; set; }          // «ПАРТНЁР EPHARM»

        // Доказательная база
        public string Script { get; set; } = "";
        public List<string> Advantages { get; set; } = new();         // запасной список (без сравнения)
        public List<ComparisonRow> Comparison { get; set; } = new();  // таблица сравнения

        // Мотивация фармацевта
        public int Bonus { get; set; }                     // «+520 ₸ вам»
        public string? GoalText { get; set; }              // «цель «7/10 замен Аквамарис в мае»»
        public int? GoalBonus { get; set; }                // «+2 000 ₸ за выполнение цели»

        public bool IsSubstitution => Kind == "substitution";
    }

    /// <summary>
    /// Конфликт правила кампании: backend нашёл подходящее правило замены/кросс-сейла, но применить
    /// его НЕЛЬЗЯ (напр. рекомендованного товара нет в наличии, кампания приостановлена, есть
    /// несовместимость). Фармацевту показываем причину (Reason) баннером — почему подмена/допродажа
    /// сейчас невозможна. Зеркалит backend Conflict; сериализация camelCase (kind/triggerName/...).
    /// </summary>
    public sealed class Conflict
    {
        public string Kind { get; set; } = "";             // substitution | crosssell
        public string? TriggerName { get; set; }           // товар, по которому сработало правило
        public string Reason { get; set; } = "";           // человекочитаемая причина (показываем фармацевту)
        public List<string> RuleIds { get; set; } = new();
    }

    /// <summary>Ответ Rules Engine: до 2 рекомендаций (замены раньше cross-sell, по бонусу DESC).</summary>
    public sealed class RecommendResponse
    {
        public string SessionId { get; set; } = "";
        public List<Recommendation> Recommendations { get; set; } = new();

        /// <summary>
        /// Конфликты: правило подошло, но применить нельзя (товара нет, кампания на паузе, …).
        /// Может прийти null от старого backend — обрабатывать как пустой список.
        /// </summary>
        public List<Conflict> Conflicts { get; set; } = new();
    }

    /// <summary>Результат рекомендации: accepted (принял) | rejected (пропустил).</summary>
    public sealed class OutcomeRequest
    {
        public string Outcome { get; set; } = "";
        public string? FinalSku { get; set; }
    }

    public sealed class OutcomeResponse
    {
        public string EventId { get; set; } = "";
        public string Outcome { get; set; } = "";
        public string? PendingBonusId { get; set; }
    }
}
