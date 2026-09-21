using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNLogParserTests
{
    [Fact]
    public void ParsesExistingCompactAdd2ChequeFormat()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque iPartID=358546(4601164003164);sname=Жидкий уголь комплекс с пектином саше детс 7г №10;price=4455,00;quant=1");

        Assert.NotNull(item);
        Assert.Equal(358546, item.PartId);
        Assert.Equal("4601164003164", item.Barcode);
        Assert.Equal("Жидкий уголь комплекс с пектином саше детс 7г №10", item.Name);
        Assert.Equal(4455m, item.Price);
        Assert.Equal(1m, item.Qty);
    }

    [Fact]
    public void ParsesWhitespaceColonQuotesAndDifferentFieldOrder()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "ADD2CHEQUE QUANT : '1,000'; PRICE = \"4 455,00\"; SName : 'Жидкий уголь комплекс с пектином саше детс 7г №10'; iPartID = 358546 ( 4601164003164 )");

        Assert.NotNull(item);
        Assert.Equal(358546, item.PartId);
        Assert.Equal("4601164003164", item.Barcode);
        Assert.Equal("Жидкий уголь комплекс с пектином саше детс 7г №10", item.Name);
        Assert.Equal(4455m, item.Price);
        Assert.Equal(1m, item.Qty);
    }

    [Fact]
    public void ExplicitBarcodeFieldWinsAndMayUseManufacturerColumnName()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque iPartID:358546(358546);quant:1;sname:Жидкий уголь;barcode = \"4601164003164\";BCODE_IZG : 4603423001072");

        Assert.NotNull(item);
        Assert.Equal("4601164003164", item.Barcode);
    }

    [Fact]
    public void MissingOptionalNameAndPriceStillProducesScanIdentity()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque quant = 1; iPartID = 358546");

        Assert.NotNull(item);
        Assert.Equal(358546, item.PartId);
        Assert.Equal("", item.Name);
        Assert.Equal(0m, item.Price);
        Assert.Null(item.Barcode);
    }

    [Fact]
    public void DuplicatePartIdInParenthesesIsNotTreatedAsBarcode()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque iPartID=358546(358546);sname=Жидкий уголь;quant=1");

        Assert.NotNull(item);
        Assert.Null(item.Barcode);
    }

    [Fact]
    public void TwoIdenticalParsedRecordsRemainTwoExplicitScanEvents()
    {
        const string line =
            "Add2Cheque iPartID=358546(4601164003164);sname=Жидкий уголь;price=4455;quant=1";
        var first = StandardNLogParser.TryParseAdd2Cheque(line);
        var second = StandardNLogParser.TryParseAdd2Cheque(line);

        Assert.NotNull(first);
        Assert.NotNull(second);
        Assert.Equal(
            ReceiptRecommendationAction.Refresh,
            ReceiptRecommendationChange.ClassifyExplicitAdd(
                existed: false,
                previousQty: 0,
                previousBarcode: null,
                previousName: null,
                nextQty: first.Qty,
                nextBarcode: first.Barcode,
                nextName: first.Name));
        Assert.Equal(
            ReceiptRecommendationAction.Refresh,
            ReceiptRecommendationChange.ClassifyExplicitAdd(
                existed: true,
                previousQty: first.Qty,
                previousBarcode: first.Barcode,
                previousName: first.Name,
                nextQty: second.Qty,
                nextBarcode: second.Barcode,
                nextName: second.Name));
    }

    [Theory]
    [InlineData("Add2Cheque iPartID = 358546; sname=Жидкий уголь")]
    [InlineData("Add2Cheque quant = 1; sname=Жидкий уголь")]
    [InlineData("Add2Cheque iPartID = 358546; quant = 0")]
    [InlineData("Add2Cheque iPartID = 358546; quant = -1")]
    [InlineData("Add2Cheque iPartID = 358546; quant = 1 (delete)")]
    [InlineData("unrelated iPartID = 358546; quant = 1")]
    public void RejectsLinesWithoutRequiredAddEventFields(string line)
    {
        Assert.Null(StandardNLogParser.TryParseAdd2Cheque(line));
    }

    [Fact]
    public void DeletePathUsesSameTolerantPartIdSyntax()
    {
        Assert.Equal(358546, StandardNLogParser.TryExtractPartId("Add2Cheque iPartID : '358546' (delete)"));
    }
}
