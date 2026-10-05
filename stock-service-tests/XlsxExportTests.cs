using System.IO.Compression;
using System.Xml.Linq;
using Epharm.StockService;

namespace stock_service_tests;

public class XlsxExportTests
{
    [Fact]
    public void ExportKeepsTextAsTextAndQuantityNumeric()
    {
        var pharmacy = new PharmacyRow(7, "Аптека", "Алматы", "Адрес", "1000", 1,
            DateTimeOffset.UtcNow, null, "fresh");
        var bytes = XlsxExport.Create(pharmacy,
            [new StockRow(5, "=1+1", "123", "456", 2.5m, 99.25m, null, null, "шт")]);

        using var archive = new ZipArchive(new MemoryStream(bytes), ZipArchiveMode.Read);
        var sheet = archive.GetEntry("xl/worksheets/sheet1.xml");
        Assert.NotNull(sheet);
        using var stream = sheet.Open();
        var document = XDocument.Load(stream);
        XNamespace ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        var cells = document.Descendants(ns + "c").ToDictionary(c => c.Attribute("r")!.Value);
        Assert.Equal("inlineStr", cells["B3"].Attribute("t")?.Value);
        Assert.Equal("=1+1", cells["B3"].Descendants(ns + "t").Single().Value);
        Assert.Null(cells["B3"].Element(ns + "f"));
        Assert.Equal("2.5", cells["E3"].Element(ns + "v")?.Value);
    }
}
