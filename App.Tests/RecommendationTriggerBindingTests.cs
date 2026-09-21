using System.Collections.Generic;
using CustomerDisplay.Models;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class RecommendationTriggerBindingTests
{
    [Fact]
    public void ResolvesPhotoNameToItsOwnLineInsteadOfNewestUnrelatedScan()
    {
        var liquidCoal = new ReceiptItem
        {
            PartId = 101,
            Barcode = "local-code",
            Name = "Жидкий уголь комплекс с пектином саше детс 7г №10",
        };
        var newest = new ReceiptItem
        {
            PartId = 202,
            Barcode = "4600000000002",
            Name = "Другой товар 20 мг №10",
        };

        var binding = RecommendationTriggerBinding.Resolve(
            new[] { liquidCoal, newest },
            lastScanned: newest,
            triggerIpartId: null,
            triggerBarcode: "4603423001072",
            triggerName: "Жидкий уголь Комплекс с пектином для детей саше 7г №10");

        Assert.NotNull(binding);
        Assert.Equal(liquidCoal.PartId, binding!.PartId);
        Assert.True(binding.IsPresent(new[] { liquidCoal, newest }));
        Assert.False(binding.IsPresent(new[] { newest }));
    }

    [Fact]
    public void UsesLastScanOnlyWhenLegacyResponseHasNoTriggerIdentity()
    {
        var scanned = new ReceiptItem { PartId = 303, Name = "Товар" };

        var binding = RecommendationTriggerBinding.Resolve(
            new List<ReceiptItem> { scanned }, scanned, null, null, null);

        Assert.NotNull(binding);
        Assert.Equal(scanned.PartId, binding!.PartId);
    }

    [Fact]
    public void ResolvesAnExactShortNameBeforeFuzzyTokenThreshold()
    {
        var item = new ReceiptItem { PartId = 404, Name = "Залаин свечи" };

        var binding = RecommendationTriggerBinding.Resolve(
            new[] { item },
            lastScanned: null,
            triggerIpartId: null,
            triggerBarcode: "4600000000404",
            triggerName: "Залаин, свечи");

        Assert.NotNull(binding);
        Assert.Equal(item.PartId, binding!.PartId);
        Assert.True(binding.IsPresent(new[] { item }));
    }
}
