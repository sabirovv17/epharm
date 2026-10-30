using System;
using System.Collections.Generic;
using System.Data;
using System.Globalization;
using System.IO;
using System.Linq;

namespace CustomerDisplay.Services;

internal sealed record StandardNReceiptHeader(long Id, long SessionId, int Type, int Status,
    long? LinkId, bool HasLinkColumn, decimal SignedTotal, string? Device, long? FiscalNumber,
    long? FiscalShift, DateTimeOffset? OpenedAt);
internal sealed record StandardNReceiptSessionRow(long SessionId, string UserId, long? WorkstationId,
    string? WorkstationName, string? Program);

/// <summary>Strict, shared projection of the actual Firebird query rows; never selects a newest cart.</summary>
internal static class StandardNReceiptProjection
{
    public const int MaxDocuments = 500;
    public const int MaxLines = 5000;
    public const string ActiveDocumentsSql = @"select first 501 * from DOCS
        where STATUS=0 and DOC_TYPE=3 and AUDIT_ID=@sessionId order by ID";
    public const string LinkedDocumentsSql = @"select first 501 * from DOCS
        where ID=@rootId or LINK_ID=@rootId order by ID";
    public const string SingleDocumentSql = "select first 2 * from DOCS where ID=@rootId";

    public static StandardNReceiptSessionRow Session(IDataRecord row) => new(
        RequiredLong(row, "SESSION_ID"), Text(row, "USER_ID") ?? "", OptionalLong(row, "WS_ID"),
        Text(row, "COMPNAME"), Text(row, "PROG"));

    public static StandardNReceiptSessionRow? ExactLocalSession(string machine, IReadOnlyList<StandardNReceiptSessionRow> rows)
    {
        if (rows.Count > 100) throw new InvalidDataException("Cashier session observation bound exceeded.");
        var canonical = StaffSessionResolver.CanonicalComputer(machine);
        if (canonical.Length == 0) return null;
        var exact = rows.Where(row => StaffSessionResolver.CanonicalComputer(row.WorkstationName) == canonical &&
            row.Program is { } program && (program.Contains("zkassa", StringComparison.OrdinalIgnoreCase) ||
                program.Contains("kassir", StringComparison.OrdinalIgnoreCase))).ToArray();
        return exact.Length == 1 && exact[0].SessionId > 0 && !string.IsNullOrWhiteSpace(exact[0].UserId) ? exact[0] : null;
    }

    public static string ActiveLinesSql(int documents)
    {
        if (documents is < 1 or > MaxDocuments) throw new InvalidDataException("Receipt document bound exceeded.");
        return "select first 5001 l.* from DOC_DETAIL_ACTIVE l join DOCS d on d.ID=l.DOC_ID " +
               "where d.AUDIT_ID=@sessionId and d.DOC_TYPE=3 and d.STATUS=0 and l.DOC_ID in (" +
               string.Join(",", Enumerable.Range(0, documents).Select(index => "@doc" + index)) + ") order by l.DOC_ID,l.ID";
    }

    public static string PostedLinesSql(int documents)
    {
        if (documents is < 1 or > MaxDocuments) throw new InvalidDataException("Receipt document bound exceeded.");
        // Proven Standard-N lifecycle: posted rows move to DOC_DETAIL; ACTIVE is empty after posting.
        // Read only monetary/product columns, never patient/doctor FIO references from this table.
        return "select first 5001 l.ID,l.DOC_ID,l.PART_ID,l.PRICE,l.QUANT,l.SUMMA,l.DISCOUNT " +
               "from DOC_DETAIL l join DOCS d on d.ID=l.DOC_ID where d.AUDIT_ID=@sessionId " +
               "and d.DOC_TYPE=3 and d.STATUS=1 and l.DOC_ID in (" +
               string.Join(",", Enumerable.Range(0, documents).Select(index => "@doc" + index)) + ") order by l.DOC_ID,l.ID";
    }

    public static StandardNReceiptHeader Header(IDataRecord row) => new(
        RequiredLong(row, "ID"), RequiredLong(row, "AUDIT_ID"), checked((int)RequiredLong(row, "DOC_TYPE")),
        checked((int)RequiredLong(row, "STATUS")), OptionalLong(row, "LINK_ID"), Has(row, "LINK_ID"),
        RequiredDecimal(row, "SUMMA"), Text(row, "DEVICE_NUM"), OptionalLong(row, "VNUM"),
        OptionalLong(row, "VSHIFT"), Timestamp(Value(row, "INSERTDT")));

    public static StandardNReceiptLine Line(IDataRecord row)
    {
        var id = RequiredLong(row, "ID");
        var document = RequiredLong(row, "DOC_ID");
        var part = checked((int)RequiredLong(row, "PART_ID"));
        var signedQty = RequiredDecimal(row, "QUANT");
        var signedPrice = RequiredDecimal(row, "PRICE");
        var price = Math.Abs(signedPrice);
        var signedAmount = RequiredDecimal(row, "SUMMA");
        if (id <= 0 || document <= 0 || part <= 0 || signedQty == 0)
            throw new InvalidDataException("Receipt detail has an invalid physical identity or quantity.");
        var qty = Math.Abs(signedQty);
        var amount = Math.Abs(signedAmount);
        var gross = price * qty;
        var discount = gross > 0 && amount < gross
            ? Math.Round((gross - amount) / gross * 100m, 2, MidpointRounding.AwayFromZero) : 0m;
        return new(id, part, ProductBarcode(Text(row, "BCODE_IZG"), Text(row, "ORIG_BCODE_IZG"),
            Text(row, "BARCODE1"), Text(row, "BARCODE")), Text(row, "SNAME") ?? "", price, qty, discount)
        {
            SourceDocumentId = document, SourceTotal = amount,
            SignedQuantity = signedQty, SignedAmount = signedAmount, SignedPrice = signedPrice,
        };
    }

    public static (long Root, bool Linked)? CurrentGroup(long sessionId, IReadOnlyList<StandardNReceiptHeader> active)
    {
        if (active.Count == 0) return null;
        if (active.Count > MaxDocuments || active.Select(row => row.Id).Distinct().Count() != active.Count ||
            active.Any(row => row.Id <= 0 || row.Type != 3 || row.Status != 0 || row.SessionId != sessionId))
            throw new InvalidDataException("Active receipt membership is invalid or exceeds its bound.");
        var groups = active.Select(row => (Root: row.LinkId is > 0 ? row.LinkId.Value : row.Id,
            Linked: row.HasLinkColumn && row.LinkId is > 0)).Distinct().ToArray();
        if (groups.Length != 1) throw new InvalidDataException("More than one current receipt group is open.");
        return groups[0];
    }

    public static StandardNReceiptSnapshot Current(long sessionId, string workstation, StandardNActivePharmacist pharmacist,
        IReadOnlyList<StandardNReceiptHeader> active, IReadOnlyList<StandardNReceiptHeader> members,
        IReadOnlyList<StandardNReceiptLine> lines)
    {
        var key = CurrentGroup(sessionId, active) ?? throw new InvalidDataException("No current receipt group.");
        ValidateMembers(sessionId, key.Root, key.Linked, members);
        if (!active.Select(row => row.Id).OrderBy(id => id).SequenceEqual(
                members.Where(row => row.Status == 0).Select(row => row.Id).OrderBy(id => id)) ||
            members.Any(row => row.Status is not (0 or 1)))
            throw new InvalidDataException("Receipt membership changed or contains suspended/unknown documents.");
        var ids = members.Select(row => row.Id).ToHashSet();
        if (lines.Count > MaxLines || lines.Any(line => !line.SourceDocumentId.HasValue || !ids.Contains(line.SourceDocumentId.Value)) ||
            lines.Select(line => (line.SourceDocumentId, line.LineId)).Distinct().Count() != lines.Count)
            throw new InvalidDataException("Receipt detail membership is invalid or exceeds its bound.");
        foreach (var header in members)
        {
            var physicalLines = lines.Where(line => line.SourceDocumentId == header.Id).ToArray();
            if (physicalLines.Sum(line => line.SourceTotal ?? line.Price * line.Qty) != Math.Abs(header.SignedTotal))
                throw new InvalidDataException("Active receipt header and details do not agree.");
        }
        var documents = members.Select(header => Document(header,
            lines.Where(line => line.SourceDocumentId == header.Id).ToArray()) with { FinalDetailsRead = header.Status == 1 }).ToArray();
        return new(key.Root, sessionId, workstation, members.Single(row => row.Id == key.Root).OpenedAt, pharmacist, lines)
        { LinkId = key.Linked ? key.Root : null, Documents = documents };
    }

    public static void ValidateMembers(long sessionId, long root, bool linked, IReadOnlyList<StandardNReceiptHeader> members)
    {
        if (members.Count is < 1 or > MaxDocuments || members.Select(row => row.Id).Distinct().Count() != members.Count ||
            members.Any(row => row.Id <= 0 || row.Type != 3 || row.SessionId != sessionId ||
                (linked ? row.LinkId != root : row.Id != root || row.LinkId is > 0)) ||
            members.Count(row => row.Id == root) != 1)
            throw new InvalidDataException("Receipt root or exact local physical membership is not proven.");
    }

    public static StandardNReceiptDocument Document(StandardNReceiptHeader header, IReadOnlyList<StandardNReceiptLine> lines) =>
        new(header.Id, header.SessionId, header.LinkId, header.Status, header.SignedTotal,
            header.Device, header.FiscalNumber, header.FiscalShift, lines);

    public static bool IsPostedPhysicalSale(StandardNReceiptDocument document) =>
        document.FinalDetailsRead && document.DocumentId > 0 && document.SessionId > 0 && document.Status == 1 && document.SignedTotal <= 0 &&
        !string.IsNullOrWhiteSpace(document.DeviceNumber) && document.FiscalNumber is > 0 && document.FiscalShift is > 0 &&
        document.Lines.Count is > 0 and <= MaxLines &&
        document.Lines.Select(line => line.LineId).Distinct().Count() == document.Lines.Count &&
        document.Lines.All(line => line.SourceDocumentId == document.DocumentId && line.LineId > 0 && line.PartId > 0 &&
            line.Qty > 0 && line.SignedPrice is >= 0 && line.Price == line.SignedPrice && line.SignedQuantity is < 0 && line.SignedAmount is <= 0 &&
            line.SourceTotal.HasValue && line.SourceTotal == Math.Abs(line.SignedAmount.Value) &&
            line.Qty == Math.Abs(line.SignedQuantity.Value)) &&
        document.Lines.Sum(line => line.SourceTotal!.Value) == Math.Abs(document.SignedTotal);

    public static bool HasPostedFiscalHeader(StandardNReceiptDocument document) => document.Status == 1 &&
        document.SignedTotal <= 0 && !string.IsNullOrWhiteSpace(document.DeviceNumber) &&
        document.FiscalNumber is > 0 && document.FiscalShift is > 0;

    public static bool IsPostedEmptyDocument(StandardNReceiptDocument document) => document.FinalDetailsRead &&
        document.DocumentId > 0 && document.SessionId > 0 && document.Status == 1 && document.SignedTotal == 0 &&
        !string.IsNullOrWhiteSpace(document.DeviceNumber) && document.FiscalNumber is > 0 && document.FiscalShift is > 0 &&
        document.Lines.Count == 0;

    public static bool IsResolvedPhysicalDocument(StandardNObservedReceiptGroup group, StandardNReceiptDocument document) =>
        CanRepresentLongKzt(document) && (group.QueuedDocumentIds.Contains(document.DocumentId) && IsPostedPhysicalSale(document) ||
        group.ResolvedEmptyDocumentIds.Contains(document.DocumentId) && IsPostedEmptyDocument(document) ||
        group.InitiallyPostedDocumentIds.Contains(document.DocumentId) && !group.ObservedActiveDocumentIds.Contains(document.DocumentId) &&
        (IsPostedPhysicalSale(document) || IsPostedEmptyDocument(document)));

    public static bool CanRepresentLongKzt(StandardNReceiptDocument document)
    {
        static bool Exact(decimal value) => value >= 0 && value <= long.MaxValue && decimal.Truncate(value) == value;
        return Exact(Math.Abs(document.SignedTotal)) && document.Lines.All(line =>
            Exact(line.Price) && line.SourceTotal.HasValue && Exact(line.SourceTotal.Value));
    }

    internal static string? ProductBarcode(params string?[] values)
    {
        foreach (var raw in values)
        {
            var value = raw?.Trim();
            if (value?.Length is 8 or 12 or 13 or 14 && value.All(ch => ch is >= '0' and <= '9')) return value;
        }
        return values.Select(value => value?.Trim()).FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));
    }

    private static bool Has(IDataRecord row, string column) =>
        Enumerable.Range(0, row.FieldCount).Any(index => string.Equals(row.GetName(index), column, StringComparison.OrdinalIgnoreCase));
    private static object? Value(IDataRecord row, string column) =>
        Has(row, column) && !row.IsDBNull(row.GetOrdinal(column)) ? row[column] : null;
    private static string? Text(IDataRecord row, string column) => Value(row, column)?.ToString()?.Trim();
    private static long? OptionalLong(IDataRecord row, string column) => Value(row, column) is { } value
        ? Convert.ToInt64(value, CultureInfo.InvariantCulture) : null;
    private static long RequiredLong(IDataRecord row, string column) => OptionalLong(row, column)
        ?? throw new InvalidDataException("Required receipt column is unavailable: " + column);
    private static decimal RequiredDecimal(IDataRecord row, string column) => Value(row, column) is { } value
        ? Convert.ToDecimal(value, CultureInfo.InvariantCulture) : throw new InvalidDataException("Required receipt column is unavailable: " + column);
    private static DateTimeOffset? Timestamp(object? value) => value switch
    {
        DateTimeOffset timestamp => timestamp,
        DateTime date => new DateTimeOffset(date),
        _ => null,
    };
}
