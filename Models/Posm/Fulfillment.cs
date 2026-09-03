using System;
using System.Collections.Generic;
using System.Linq;

namespace CustomerDisplay.Models.Posm
{
    public sealed class RegisterFulfillmentDeviceRequest
    {
        public string DeviceId { get; set; } = "";
        public string PharmacyId { get; set; } = "";
    }

    public sealed class RegisterFulfillmentDeviceResponse
    {
        public string DeviceId { get; set; } = "";
        public string PharmacyId { get; set; } = "";
        public string Token { get; set; } = "";
    }

    public sealed class FulfillmentLine
    {
        public string ProductId { get; set; } = "";
        public string Sku { get; set; } = "";
        public string Title { get; set; } = "";
        public int Quantity { get; set; }
        public decimal? UnitPrice { get; set; }
    }

    public sealed class FulfillmentOrder
    {
        public string OrderId { get; set; } = "";
        public string Number { get; set; } = "";
        public string PharmacyExternalId { get; set; } = "";
        public string? PharmacyId { get; set; }
        public string? PharmacyName { get; set; }
        public string? PharmacyAddress { get; set; }
        public DateTimeOffset CreatedAt { get; set; }
        public decimal Total { get; set; }
        public string Currency { get; set; } = "KZT";
        public string Delivery { get; set; } = "pickup";
        public string PaymentMethod { get; set; } = "";
        public string PaymentStatus { get; set; } = "";
        public bool Demo { get; set; }
        public string Status { get; set; } = "submitted";
        public long Version { get; set; }
        public int PickupAttempts { get; set; }
        public DateTimeOffset? PickupLockedUntil { get; set; }
        public string? CancellationReason { get; set; }
        public DateTimeOffset? CompletedAt { get; set; }
        public DateTimeOffset UpdatedAt { get; set; }
        public List<FulfillmentLine> Lines { get; set; } = new();

        public bool IsActive => Status is "submitted" or "assembling" or "ready";
    }

    public sealed class FulfillmentOrderPage
    {
        public List<FulfillmentOrder> Items { get; set; } = new();
        public int Offset { get; set; }
        public int Limit { get; set; }
        public bool HasMore { get; set; }
    }

    public sealed class FulfillmentActionRequest
    {
        public string Action { get; set; } = "";
        public long ExpectedVersion { get; set; }
        public string? Code { get; set; }
        public string? Reason { get; set; }
        public bool CashCollected { get; set; }
    }

    public static class FulfillmentRules
    {
        public static bool CanIssue(FulfillmentOrder order, string? code, bool cashCollected)
        {
            if (order.Status != "ready" || code?.Length != 6 || !code.All(char.IsDigit)) return false;
            if (order.Demo) return order.PaymentStatus == "demo_no_charge";
            if (order.PaymentMethod.Equals("cash", StringComparison.OrdinalIgnoreCase))
                return order.PaymentStatus is "paid" or "cash_collected" || cashCollected;
            return order.PaymentStatus == "paid";
        }
    }
}
