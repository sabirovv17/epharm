

using System;
namespace CustomerDisplay.Models
{
   public class ReceiptItem
{
    public int PartId { get; set; }
    public string Name { get; set; } = "";
    public decimal Price { get; set; }
    public decimal Qty { get; set; } = 1m;
    public decimal DiscountPercent { get; set; } = 0m;

    public decimal SubTotal => Price * Qty;
    public decimal DiscountAmount => SubTotal * (DiscountPercent / 100m);
    public decimal Total
{
    get
    {
        var sub = Price * Qty;
        var disc = sub * (DiscountPercent / 100m);
        return Math.Round(sub - disc, 2, MidpointRounding.AwayFromZero);
    }
}
}
}
