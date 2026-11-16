using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNSellerSourceTests
{
    private static readonly string Source = new('a', 64);
    private static StandardNObservedReceiptGroup Group()
    {
        var group = ObservedReceiptGroupTests.Group(100);
        return group with { SourceNamespace = Source, Snapshot = group.Snapshot with {
            WorkstationId = "19", ObservedSourceNamespace = Source } };
    }

    [Fact]
    public void ExactFrozenNativeSourceIsIndependentOfRewardAndOwnLogin()
    {
        var group = Group(); var snapshot = group.Snapshot;
        var checkout = CheckoutSession.RestoreObserved(group.CheckoutSessionId, group.PharmacistId, group.PharmacistName, snapshot.SessionId);
        var source = StandardNSellerSource.FromObservedGroup(group, checkout, snapshot.Documents!.Single(), snapshot);
        Assert.Equal(new SaleSellerSource(Source, "cashier", 1, "19"), source);
        var sale = new SaleReport { PharmacistId = group.PharmacistId, SellerSource = source };
        Assert.Null(sale.RewardEvidence);
        var json = JsonSerializer.Serialize(sale, EpharmJson.Options);
        Assert.Contains("\"sellerSource\":", json);
        Assert.DoesNotContain("rewardEvidence", json);
        Assert.Equal(source, JsonSerializer.Deserialize<SaleReport>(json, EpharmJson.Options)!.SellerSource);
    }

    [Theory]
    [InlineData("legacy")]
    [InlineData("database")]
    [InlineData("legacy-snapshot")]
    [InlineData("legacy-completion")]
    [InlineData("observation-database")]
    [InlineData("malformed-namespace")]
    [InlineData("missing-workstation")]
    [InlineData("workstation")]
    [InlineData("cashier")]
    [InlineData("session")]
    [InlineData("document")]
    [InlineData("checkout")]
    [InlineData("control-user")]
    public void MissingOrConflictingProofNeverGetsCurrentOrGuessedIdentity(string mismatch)
    {
        var group = Group(); var completion = group.Snapshot;
        var checkout = CheckoutSession.RestoreObserved(group.CheckoutSessionId, group.PharmacistId, group.PharmacistName, group.Snapshot.SessionId);
        var document = completion.Documents!.Single();
        switch (mismatch) {
            case "legacy": group = group with { SourceNamespace = null }; break;
            case "database": completion = completion with { ObservedSourceNamespace = new('b', 64) }; break;
            case "legacy-snapshot": group = group with { Snapshot = group.Snapshot with { ObservedSourceNamespace = null } }; break;
            case "legacy-completion": completion = completion with { ObservedSourceNamespace = null }; break;
            case "observation-database": group = group with { Snapshot = group.Snapshot with { ObservedSourceNamespace = new('b', 64) } }; break;
            case "malformed-namespace": group = group with { SourceNamespace = new('z', 64) }; break;
            case "missing-workstation": group = group with { Snapshot = group.Snapshot with { WorkstationId = null } }; break;
            case "workstation": completion = completion with { WorkstationId = "other" }; break;
            case "cashier": completion = completion with { Pharmacist = completion.Pharmacist with { Id = "other" } }; break;
            case "session": document = document with { SessionId = 2 }; break;
            case "document": document = document with { DocumentId = 200 }; break;
            case "checkout": checkout = CheckoutSession.RestoreObserved("other", group.PharmacistId, group.PharmacistName, group.Snapshot.SessionId); break;
            case "control-user": group = group with { PharmacistId = "cashier\n" }; break;
        }
        Assert.Null(StandardNSellerSource.FromObservedGroup(group, checkout, document, completion));
    }
}
