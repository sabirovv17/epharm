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

        /// <summary>Строит запрос рекомендаций из живой корзины (PartId кассы → Sku).</summary>
        public RecommendRequest BuildRequest(EpharmConfig cfg, IEnumerable<ReceiptItem> items)
        {
            return new RecommendRequest
            {
                PharmacistId = cfg.PharmacistId,
                PharmacyId = cfg.PharmacyId,
                SessionId = SessionId,
                Cart = items
                    .Where(i => i.PartId != 0)
                    .Select(i => new CartItem { Sku = i.PartId.ToString(), Qty = (double)i.Qty })
                    .ToList(),
            };
        }
    }
}
