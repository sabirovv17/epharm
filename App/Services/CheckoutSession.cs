using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using CustomerDisplay.Config;
using CustomerDisplay.Models;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Сессия одного открытого чека: уникальный SessionId (для группировки рекомендаций на стороне
    /// backend) + сборка RecommendRequest из текущих позиций. Новый чек = новый CheckoutSession.
    /// </summary>
    public sealed class CheckoutSession
    {
        public string SessionId { get; } = "sess_" + Guid.NewGuid().ToString("N").Substring(0, 12);

        /// <summary>
        /// Строит запрос рекомендаций из живой корзины. Матчинг на backend: Barcode (EAN-13) → Name.
        /// Sku (iPartID) кладём только для диагностики. Позиции без Barcode НЕ отфильтровываем —
        /// их сматчит fallback по имени.
        /// </summary>
        public RecommendRequest BuildRequest(EpharmConfig cfg, IEnumerable<ReceiptItem> items)
        {
            var cart = items
                .Where(i => i.PartId != 0)
                .Select(i => new CartItem
                {
                    Sku = i.PartId.ToString(),   // диагностика, не ключ матчинга
                    Barcode = i.Barcode,          // первичный ключ матчинга (может быть null)
                    Name = i.Name,                // fallback-ключ матчинга
                    Qty = (double)i.Qty,
                })
                .ToList();

            return new RecommendRequest
            {
                PharmacistId = cfg.PharmacistId,
                PharmacyId = cfg.PharmacyId,
                SessionId = SessionId,
                // последний отсканированный EAN (информационно) — берём из последней позиции с штрих-кодом
                ScannedBarcode = cart.LastOrDefault(c => !string.IsNullOrWhiteSpace(c.Barcode))?.Barcode,
                Cart = cart,
            };
        }
    }
}
