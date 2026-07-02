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
        /// Строит запрос рекомендаций из живой корзины. Матчинг на backend:
        /// Sku/iPartID Стандарт-Н → Barcode (EAN-13) → Name.
        /// </summary>
        public RecommendRequest BuildRequest(
            EpharmConfig cfg,
            IEnumerable<ReceiptItem> items,
            ReceiptItem? scannedItem = null,
            string? pharmacistId = null)
        {
            var cart = items
                // PartId < 0 is a POSM-only visual item added after accepting a recommendation.
                // It is not a real Standard-N cart line and must not affect backend matching.
                .Where(i => i.PartId > 0)
                .Select(i => new CartItem
                {
                    Sku = i.PartId.ToString(),    // iPartID Стандарт-Н — точный ключ матчинга
                    Barcode = i.Barcode,          // EAN-13 — точный ключ матчинга (может быть null)
                    Name = i.Name,                // fallback-ключ матчинга
                    Qty = (double)i.Qty,
                })
                .ToList();

            return new RecommendRequest
            {
                // В боевом режиме фармацевт берётся строго из БД Стандарт-Н ACTIVEUSERS.USER_ID.
                // Если БД включена, но недоступна/пуста, отправляем пусто, а не устаревший fallback.
                PharmacistId = !string.IsNullOrWhiteSpace(pharmacistId)
                    ? pharmacistId
                    : (cfg.StandardNDbEnabled ? "" : cfg.PharmacistId),
                PharmacyId = cfg.PharmacyId,
                SessionId = SessionId,
                // Последний отсканированный EAN (информационно). Передаём явный snapshot,
                // потому что новые позиции в UI вставляются в начало списка.
                ScannedBarcode = !string.IsNullOrWhiteSpace(scannedItem?.Barcode)
                    ? scannedItem.Barcode
                    : cart.FirstOrDefault(c => !string.IsNullOrWhiteSpace(c.Barcode))?.Barcode,
                Cart = cart,
            };
        }
    }
}
