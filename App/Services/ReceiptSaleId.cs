using System;
using System.Security.Cryptography;
using System.Text;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Стабильный client id продажи. Для Firebird-чека один и тот же DOCS.ID в одной аптеке
    /// всегда даёт один id, поэтому два сигнала завершения и повтор после краша не создают дубль.
    /// </summary>
    public static class ReceiptSaleId
    {
        public static string Create(string pharmacyId, long? sourceDocumentId, string checkoutSessionId)
        {
            var identity = sourceDocumentId.HasValue
                ? $"{pharmacyId.Trim()}|standardn-doc|{sourceDocumentId.Value}"
                : $"{pharmacyId.Trim()}|checkout-session|{checkoutSessionId.Trim()}";
            var digest = SHA256.HashData(Encoding.UTF8.GetBytes(identity));
            // 24 bytes = 48 hex chars; с префиксом укладываемся в backend VARCHAR(64).
            return "sale_" + Convert.ToHexString(digest.AsSpan(0, 24)).ToLowerInvariant();
        }
    }
}
