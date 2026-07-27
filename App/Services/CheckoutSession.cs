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

        // Продавец фиксируется один раз на первом товаре чека. Смена пользователя Standard-N
        // посередине чека применяется только к следующему чеку, чтобы показ и продажа всегда
        // относились к одному человеку.
        public bool SellerCaptured { get; private set; }
        public string PharmacistId { get; private set; } = "";
        public string PharmacistName { get; private set; } = "";
        public long? StandardNSessionId { get; private set; }

        public void CaptureSeller(string? pharmacistId, string? pharmacistName, long? standardNSessionId)
        {
            if (SellerCaptured) return;
            var id = pharmacistId?.Trim() ?? "";
            var name = pharmacistName?.Trim() ?? "";

            // A temporary Firebird/path failure on the first scanned item must not lock the
            // whole receipt to an empty seller. Keep the session unresolved and retry on the
            // next scan and immediately before the completed sale is queued.
            if (id.Length == 0 && name.Length == 0) return;

            SellerCaptured = true;
            PharmacistId = id;
            PharmacistName = name;
            StandardNSessionId = standardNSessionId;
        }

        /// <summary>
        /// Строит запрос рекомендаций из живой корзины. Матчинг на backend:
        /// Barcode (EAN/GTIN) → Sku/iPartID Стандарт-Н when EAN is unavailable → Name.
        /// </summary>
        public RecommendRequest BuildRequest(
            EpharmConfig cfg,
            IEnumerable<ReceiptItem> items,
            ReceiptItem? scannedItem = null)
        {
            var cart = items
                // PartId < 0 is a POSM-only visual item added after accepting a recommendation.
                // It is not a real Standard-N cart line and must not affect backend matching.
                .Where(i => i.PartId > 0)
                .Select(i => new CartItem
                {
                    // PARTS.ID is local to a Standard-N database and can collide with a catalog
                    // ipartId from another pharmacy. When POSM knows the retail barcode, do not
                    // send that conflicting local id to older backends that used sku first.
                    Sku = string.IsNullOrWhiteSpace(i.Barcode) ? i.PartId.ToString() : null,
                    Barcode = i.Barcode?.Trim(),  // EAN/GTIN — authoritative cross-pharmacy key
                    Name = i.Name,                // fallback-ключ матчинга
                    Qty = (double)i.Qty,
                })
                .ToList();

            return new RecommendRequest
            {
                // В боевом режиме фармацевт берётся из активного пользователя/сессии Стандарт-Н.
                // Если БД включена, но недоступна/пуста, отправляем пусто, а не устаревший fallback.
                PharmacistId = !string.IsNullOrWhiteSpace(this.PharmacistId)
                    ? this.PharmacistId
                    : (cfg.StandardNDbEnabled ? "" : cfg.PharmacistId),
                PharmacistName = !string.IsNullOrWhiteSpace(this.PharmacistName)
                    ? this.PharmacistName
                    : null,
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
