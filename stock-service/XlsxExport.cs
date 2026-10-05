using System.Globalization;
using System.IO.Compression;
using System.Text;
using System.Xml;

namespace Epharm.StockService;

public static class XlsxExport
{
    private static readonly string[] Headers =
    [
        "ID партии", "Наименование", "Штрихкод производителя", "Внутренний штрихкод",
        "Количество", "Цена", "Годен до", "Серия", "Ед. изм.", "ID аптеки"
    ];

    public static byte[] Create(PharmacyRow pharmacy, IReadOnlyList<StockRow> items)
    {
        if (items.Count > 1_048_570)
            throw new InvalidOperationException("Excel row limit exceeded");

        using var output = new MemoryStream();
        using (var archive = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true))
        {
            WriteText(archive, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
            WriteText(archive, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """);
            WriteText(archive, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Остатки" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """);
            WriteText(archive, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
                """);

            var sheet = archive.CreateEntry("xl/worksheets/sheet1.xml", CompressionLevel.Fastest);
            using var stream = sheet.Open();
            using var xml = XmlWriter.Create(stream, new XmlWriterSettings { Encoding = new UTF8Encoding(false), CloseOutput = false });
            xml.WriteStartDocument();
            xml.WriteStartElement("worksheet", "http://schemas.openxmlformats.org/spreadsheetml/2006/main");
            xml.WriteStartElement("sheetData");
            Row(xml, 1, [pharmacy.Name, $"Снимок: {pharmacy.LastUpdatedAt:O}"]);
            Row(xml, 2, Headers);
            for (var index = 0; index < items.Count; index++)
            {
                var item = items[index];
                xml.WriteStartElement("row");
                xml.WriteAttributeString("r", (index + 3).ToString(CultureInfo.InvariantCulture));
                TextCell(xml, "A", index + 3, item.PartId.ToString(CultureInfo.InvariantCulture));
                TextCell(xml, "B", index + 3, item.Name);
                TextCell(xml, "C", index + 3, item.ManufacturerBarcode);
                TextCell(xml, "D", index + 3, item.Barcode);
                NumberCell(xml, "E", index + 3, item.Quantity);
                if (item.Price is { } price) NumberCell(xml, "F", index + 3, price);
                TextCell(xml, "G", index + 3, item.ExpiryDate);
                TextCell(xml, "H", index + 3, item.Series);
                TextCell(xml, "I", index + 3, item.Unit);
                TextCell(xml, "J", index + 3, pharmacy.Id.ToString(CultureInfo.InvariantCulture));
                xml.WriteEndElement();
            }
            xml.WriteEndElement();
            xml.WriteEndElement();
            xml.WriteEndDocument();
        }
        return output.ToArray();
    }

    private static void Row(XmlWriter xml, int rowNumber, IReadOnlyList<string> values)
    {
        xml.WriteStartElement("row");
        xml.WriteAttributeString("r", rowNumber.ToString(CultureInfo.InvariantCulture));
        for (var index = 0; index < values.Count; index++)
            TextCell(xml, ((char)('A' + index)).ToString(), rowNumber, values[index]);
        xml.WriteEndElement();
    }

    private static void TextCell(XmlWriter xml, string column, int row, string? value)
    {
        if (string.IsNullOrEmpty(value)) return;
        xml.WriteStartElement("c");
        xml.WriteAttributeString("r", $"{column}{row}");
        xml.WriteAttributeString("t", "inlineStr");
        xml.WriteStartElement("is");
        xml.WriteStartElement("t");
        xml.WriteAttributeString("xml", "space", null, "preserve");
        xml.WriteString(value);
        xml.WriteEndElement();
        xml.WriteEndElement();
        xml.WriteEndElement();
    }

    private static void NumberCell(XmlWriter xml, string column, int row, decimal value)
    {
        xml.WriteStartElement("c");
        xml.WriteAttributeString("r", $"{column}{row}");
        xml.WriteStartElement("v");
        xml.WriteString(value.ToString(CultureInfo.InvariantCulture));
        xml.WriteEndElement();
        xml.WriteEndElement();
    }

    private static void WriteText(ZipArchive archive, string path, string body)
    {
        var entry = archive.CreateEntry(path, CompressionLevel.Fastest);
        using var writer = new StreamWriter(entry.Open(), new UTF8Encoding(false));
        writer.Write(body);
    }
}
