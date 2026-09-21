using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ReceiptRecommendationChangeTests
{
    [Fact]
    public void DuplicateObservationDoesNotCancelRequestStartedByOtherReceiptSource()
    {
        var action = ReceiptRecommendationChange.ClassifyLine(
            existed: true,
            previousQty: 1,
            previousBarcode: "4603423001072",
            previousName: "Жидкий уголь",
            nextQty: 1,
            nextBarcode: "4603423001072",
            nextName: "Жидкий уголь");

        Assert.Equal(ReceiptRecommendationAction.None, action);
    }

    [Fact]
    public void ExplicitLogEventRefreshesEvenWhenPartQuantityAndIdentityAreUnchanged()
    {
        var action = ReceiptRecommendationChange.ClassifyExplicitAdd(
            existed: true,
            previousQty: 1,
            previousBarcode: "4601164003164",
            previousName: "Жидкий уголь комплекс с пектином саше детс 7г №10",
            nextQty: 1,
            nextBarcode: "4601164003164",
            nextName: "Жидкий уголь комплекс с пектином саше детс 7г №10");

        Assert.Equal(ReceiptRecommendationAction.Refresh, action);
    }

    [Theory]
    [InlineData(false, 0, 1)]
    [InlineData(true, 1, 2)]
    public void NewOrIncreasedLineRefreshesRecommendations(bool existed, double previousQty, double nextQty)
    {
        var action = ReceiptRecommendationChange.ClassifyLine(
            existed,
            (decimal)previousQty,
            null,
            "Жидкий уголь",
            (decimal)nextQty,
            null,
            "Жидкий уголь");

        Assert.Equal(ReceiptRecommendationAction.Refresh, action);
    }

    [Fact]
    public void BarcodeEnrichmentRefreshesRequestWithAuthoritativeIdentity()
    {
        var action = ReceiptRecommendationChange.ClassifyLine(
            existed: true,
            previousQty: 1,
            previousBarcode: null,
            previousName: "Жидкий уголь",
            nextQty: 1,
            nextBarcode: "4603423001072",
            nextName: "Жидкий уголь");

        Assert.Equal(ReceiptRecommendationAction.Refresh, action);
    }

    [Fact]
    public void MissingSecondaryIdentityDoesNotEraseOrRefreshKnownIdentity()
    {
        var action = ReceiptRecommendationChange.ClassifyLine(
            existed: true,
            previousQty: 1,
            previousBarcode: "4603423001072",
            previousName: "Жидкий уголь",
            nextQty: 1,
            nextBarcode: null,
            nextName: null);

        Assert.Equal(ReceiptRecommendationAction.None, action);
        Assert.Equal(
            "4603423001072",
            ReceiptRecommendationChange.MergeIdentity(null, "4603423001072"));
    }

    [Theory]
    [InlineData(2, 1)]
    [InlineData(1, 0)]
    public void DecreasedOrRemovedLineCancelsStaleRequest(double previousQty, double nextQty)
    {
        var action = ReceiptRecommendationChange.ClassifyLine(
            existed: true,
            previousQty: (decimal)previousQty,
            previousBarcode: "4603423001072",
            previousName: "Жидкий уголь",
            nextQty: (decimal)nextQty,
            nextBarcode: "4603423001072",
            nextName: "Жидкий уголь");

        Assert.Equal(ReceiptRecommendationAction.CancelPending, action);
    }

    [Fact]
    public void RefreshWinsWhenReceiptBatchAlsoContainsRemoval()
    {
        var action = ReceiptRecommendationChange.Combine(
            ReceiptRecommendationAction.CancelPending,
            ReceiptRecommendationAction.Refresh);

        Assert.Equal(ReceiptRecommendationAction.Refresh, action);
    }

    [Fact]
    public void DelayedDuplicateDeleteDoesNotCancelARequestForTheNextScan()
    {
        Assert.Equal(
            ReceiptRecommendationAction.None,
            ReceiptRecommendationChange.ClassifyRemoval(actuallyRemoved: false));
        Assert.Equal(
            ReceiptRecommendationAction.CancelPending,
            ReceiptRecommendationChange.ClassifyRemoval(actuallyRemoved: true));
    }

    [Fact]
    public void ExplicitLogDecreasePreservesExistingCancellationSemantics()
    {
        var action = ReceiptRecommendationChange.ClassifyExplicitAdd(
            existed: true,
            previousQty: 2,
            previousBarcode: "4601164003164",
            previousName: "Жидкий уголь",
            nextQty: 1,
            nextBarcode: "4601164003164",
            nextName: "Жидкий уголь");

        Assert.Equal(ReceiptRecommendationAction.CancelPending, action);
    }
}
