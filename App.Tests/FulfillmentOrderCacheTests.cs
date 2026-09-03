using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class FulfillmentOrderCacheTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-fulfillment-tests-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void QueueSurvivesRestartWithoutLosingVersionOrLines()
    {
        var path = Path.Combine(_root, "queue.json");
        var cache = new FulfillmentOrderCache(path);
        cache.Save(new[]
        {
            new FulfillmentOrder
            {
                OrderId = "order-1",
                Number = "1001",
                Status = "assembling",
                Version = 4,
                Total = 1590m,
                Lines = new List<FulfillmentLine>
                {
                    new() { ProductId = "product-1", Title = "Товар", Quantity = 2, UnitPrice = 795m },
                },
            },
        });

        var loaded = Assert.Single(cache.Load());
        Assert.Equal("order-1", loaded.OrderId);
        Assert.Equal(4, loaded.Version);
        Assert.Equal(1590m, loaded.Total);
        Assert.Equal(2, Assert.Single(loaded.Lines).Quantity);
        Assert.True(loaded.IsActive);
    }

    [Fact]
    public void CorruptCacheFailsClosedAsAnEmptyQueue()
    {
        Directory.CreateDirectory(_root);
        var path = Path.Combine(_root, "queue.json");
        File.WriteAllText(path, "{broken");

        Assert.Empty(new FulfillmentOrderCache(path).Load());
    }

    [Theory]
    [InlineData("submitted", true)]
    [InlineData("assembling", true)]
    [InlineData("ready", true)]
    [InlineData("completed", false)]
    [InlineData("cancelled", false)]
    public void OnlyNonTerminalStatusesRemainActive(string status, bool expected)
    {
        Assert.Equal(expected, new FulfillmentOrder { Status = status }.IsActive);
    }

    [Theory]
    [InlineData("cash", "pending", false, "123456", false, false)]
    [InlineData("cash", "pending", false, "123456", true, true)]
    [InlineData("cash", "paid", false, "123456", false, true)]
    [InlineData("card", "pending", false, "123456", true, false)]
    [InlineData("card", "paid", false, "123456", false, true)]
    [InlineData("cash", "demo_no_charge", true, "123456", false, true)]
    [InlineData("cash", "pending", false, "12345", true, false)]
    [InlineData("cash", "pending", false, "12A456", true, false)]
    public void IssueRequiresExactCodeAndTrustedPayment(
        string method,
        string paymentStatus,
        bool demo,
        string code,
        bool cashCollected,
        bool expected)
    {
        var order = new FulfillmentOrder
        {
            Status = "ready",
            PaymentMethod = method,
            PaymentStatus = paymentStatus,
            Demo = demo,
        };

        Assert.Equal(expected, FulfillmentRules.CanIssue(order, code, cashCollected));
    }

    public void Dispose()
    {
        try { if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true); } catch { }
    }
}
