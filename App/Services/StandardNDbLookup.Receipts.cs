using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using FirebirdSql.Data.FirebirdClient;

namespace CustomerDisplay.Services;

public sealed partial class StandardNDbLookup
{
    public bool TryGetPhysicalReceiptStates(IReadOnlyList<StandardNObservedReceiptGroup> groups,
        out IReadOnlyList<StandardNReceiptSnapshot> states)
    {
        states = Array.Empty<StandardNReceiptSnapshot>();
        if (!_cfg.StandardNDbEnabled || groups.Count is < 1 or > 32 ||
            groups.Sum(group => group.Snapshot.Documents?.Count ?? 0) > StandardNReceiptProjection.MaxDocuments) return false;
        try
        {
            using var connection = OpenConnection();
            using var transaction = connection.BeginTransaction(new FbTransactionOptions
            { TransactionBehavior = FbTransactionBehavior.Read | FbTransactionBehavior.Concurrency | FbTransactionBehavior.Wait });
            var result = new List<StandardNReceiptSnapshot>();
            foreach (var group in groups)
            {
                var observed = group.Snapshot;
                using (var sessionCommand = connection.CreateCommand())
                {
                    sessionCommand.Transaction = transaction;
                    sessionCommand.CommandTimeout = TimeoutSec();
                    sessionCommand.CommandText = @"select first 2 s.ID as SESSION_ID,s.USER_ID,s.WS_ID,s.PROG,w.COMPNAME
                        from SESSIONS s join WORKSTATIONS w on w.ID=s.WS_ID where s.ID=@sessionId";
                    sessionCommand.Parameters.AddWithValue("@sessionId", observed.SessionId);
                    using var reader = sessionCommand.ExecuteReader();
                    if (!reader.Read()) throw new InvalidDataException("Original receipt session is unavailable.");
                    var session = StandardNReceiptProjection.Session(reader);
                    if (reader.Read() || session.SessionId != observed.SessionId || session.UserId != group.PharmacistId ||
                        StaffSessionResolver.CanonicalComputer(session.WorkstationName) != StaffSessionResolver.CanonicalComputer(_machineName) ||
                        StaffSessionResolver.CanonicalComputer(observed.WorkstationName) != StaffSessionResolver.CanonicalComputer(_machineName))
                        throw new InvalidDataException("Original physical receipt session/workstation is not proven.");
                }
                var members = ReadReceiptHeaders(connection, transaction, observed.LinkId is > 0
                    ? StandardNReceiptProjection.LinkedDocumentsSql : StandardNReceiptProjection.SingleDocumentSql,
                    ("@rootId", observed.DocumentId));
                StandardNReceiptProjection.ValidateMembers(observed.SessionId, observed.DocumentId, observed.LinkId is > 0, members);
                if (observed.Documents == null || observed.Documents.Any(document => members.All(header => header.Id != document.DocumentId)))
                    throw new InvalidDataException("Observed physical receipt membership disappeared or changed.");

                var lines = new List<StandardNReceiptLine>();
                using (var command = connection.CreateCommand())
                {
                    command.Transaction = transaction;
                    command.CommandTimeout = TimeoutSec();
                    command.CommandText = StandardNReceiptProjection.PostedLinesSql(members.Count);
                    command.Parameters.AddWithValue("@sessionId", observed.SessionId);
                    for (var index = 0; index < members.Count; index++) command.Parameters.AddWithValue("@doc" + index, members[index].Id);
                    using var reader = command.ExecuteReader();
                    while (reader.Read()) lines.Add(StandardNReceiptProjection.Line(reader));
                }
                if (lines.Count > StandardNReceiptProjection.MaxLines ||
                    lines.Any(line => members.All(header => header.Id != line.SourceDocumentId || header.Status != 1)))
                    throw new InvalidDataException("Posted physical detail membership is not proven.");
                var documents = members.Select(header => StandardNReceiptProjection.Document(header,
                    lines.Where(line => line.SourceDocumentId == header.Id).Select(line =>
                    {
                        // Metadata enrichment is exact local product identity only. Final prices,
                        // quantities and totals always come from DOC_DETAIL, never this observation.
                        var old = observed.Documents.Where(document => document.DocumentId == header.Id)
                            .SelectMany(document => document.Lines).Where(previous => previous.PartId == line.PartId).ToArray();
                        var barcodes = old.Select(previous => previous.Barcode).Where(value => !string.IsNullOrWhiteSpace(value))
                            .Distinct(StringComparer.Ordinal).ToArray();
                        var names = old.Select(previous => previous.Name).Where(value => !string.IsNullOrWhiteSpace(value))
                            .Distinct(StringComparer.Ordinal).ToArray();
                        return line with { Barcode = barcodes.Length == 1 ? barcodes[0] : null, Name = names.Length == 1 ? names[0] : "" };
                    }).ToArray()) with { FinalDetailsRead = header.Status == 1 }).ToArray();
                var counters = documents.Where(document => StandardNReceiptProjection.IsPostedPhysicalSale(document) ||
                    StandardNReceiptProjection.IsPostedEmptyDocument(document)).Select(document =>
                    (document.DeviceNumber!.ToUpperInvariant(), document.FiscalShift, document.FiscalNumber)).ToArray();
                if (counters.Distinct().Count() != counters.Length)
                    throw new InvalidDataException("Physical fiscal counter identity is ambiguous.");
                result.Add(observed with { Documents = documents, Lines = lines });
            }
            states = result;
            return true;
        }
        catch (Exception error)
        {
            LogErrorRateLimited("Физические оплаченные документы не подтверждены; продажи не отправлены: " + error.GetBaseException().Message);
            return false;
        }
    }

    private bool TryReadCurrentReceiptGroup(out StandardNReceiptSnapshot? receipt)
    {
        receipt = null;
        if (!_cfg.StandardNDbEnabled) return false;
        try
        {
            using var connection = OpenConnection();
            using var transaction = connection.BeginTransaction(new FbTransactionOptions
            { TransactionBehavior = FbTransactionBehavior.Read | FbTransactionBehavior.Concurrency | FbTransactionBehavior.Wait });
            var session = QueryExactReceiptSession(connection, transaction);
            if (session == null) return false;
            var active = ReadReceiptHeaders(connection, transaction, StandardNReceiptProjection.ActiveDocumentsSql,
                ("@sessionId", session.SessionId));
            var key = StandardNReceiptProjection.CurrentGroup(session.SessionId, active);
            if (!key.HasValue) return true; // Exact unique local session + successful empty query only.
            var members = ReadReceiptHeaders(connection, transaction, key.Value.Linked
                ? StandardNReceiptProjection.LinkedDocumentsSql : StandardNReceiptProjection.SingleDocumentSql,
                ("@rootId", key.Value.Root));
            var lines = new List<StandardNReceiptLine>();
            using (var command = connection.CreateCommand())
            {
                command.Transaction = transaction;
                command.CommandTimeout = TimeoutSec();
                command.CommandText = StandardNReceiptProjection.ActiveLinesSql(active.Count);
                command.Parameters.AddWithValue("@sessionId", session.SessionId);
                for (var index = 0; index < active.Count; index++) command.Parameters.AddWithValue("@doc" + index, active[index].Id);
                using var reader = command.ExecuteReader();
                while (reader.Read()) lines.Add(StandardNReceiptProjection.Line(reader));
            }
            var posted = members.Where(header => header.Status == 1).ToArray();
            if (posted.Length > 0)
            {
                using var command = connection.CreateCommand();
                command.Transaction = transaction;
                command.CommandTimeout = TimeoutSec();
                command.CommandText = StandardNReceiptProjection.PostedLinesSql(posted.Length);
                command.Parameters.AddWithValue("@sessionId", session.SessionId);
                for (var index = 0; index < posted.Length; index++) command.Parameters.AddWithValue("@doc" + index, posted[index].Id);
                using var reader = command.ExecuteReader();
                while (reader.Read()) lines.Add(StandardNReceiptProjection.Line(reader));
            }
            if (lines.Count > StandardNReceiptProjection.MaxLines) throw new InvalidDataException("Receipt detail bound exceeded.");
            EnrichReceiptProductIdentity(connection, transaction, lines);
            receipt = StandardNReceiptProjection.Current(session.SessionId, session.WorkstationName!,
                new(session.UserId, session.UserName, session.SessionId), active, members, lines);
            return true;
        }
        catch (Exception error)
        {
            LogErrorRateLimited("БД Standard-N: группа чека не подтверждена; предыдущий экран сохранён: " + error.GetBaseException().Message);
            return false;
        }
    }

    private StandardNCashSession? QueryExactReceiptSession(FbConnection connection, FbTransaction transaction)
    {
        var machine = StaffSessionResolver.CanonicalComputer(_machineName);
        if (machine.Length == 0) return null;
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandTimeout = TimeoutSec();
        command.CommandText = @"select first 101 s.ID as SESSION_ID,s.USER_ID,s.WS_ID,s.PROG,w.COMPNAME
            from SESSIONS s join WORKSTATIONS w on w.ID=s.WS_ID
            where s.ENDDT is null and upper(w.COMPNAME) containing @machine
              and (s.PROG containing 'zkassa' or s.PROG containing 'kassir') order by s.ID";
        command.Parameters.AddWithValue("@machine", machine);
        using var reader = command.ExecuteReader();
        var rows = new List<StandardNReceiptSessionRow>();
        while (reader.Read())
        {
            rows.Add(StandardNReceiptProjection.Session(reader));
        }
        var selected = StandardNReceiptProjection.ExactLocalSession(machine, rows);
        if (selected == null) return null;
        _userNameCache.TryGetValue(selected.UserId, out var name);
        return new(selected.SessionId, selected.UserId, name, selected.WorkstationId, selected.WorkstationName, true);
    }

    private List<StandardNReceiptHeader> ReadReceiptHeaders(FbConnection connection, FbTransaction transaction,
        string sql, params (string Name, long Value)[] parameters)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandTimeout = TimeoutSec();
        command.CommandText = sql;
        foreach (var parameter in parameters) command.Parameters.AddWithValue(parameter.Name, parameter.Value);
        using var reader = command.ExecuteReader();
        var rows = new List<StandardNReceiptHeader>();
        while (reader.Read()) rows.Add(StandardNReceiptProjection.Header(reader));
        return rows;
    }

    private void EnrichReceiptProductIdentity(FbConnection connection, FbTransaction transaction, List<StandardNReceiptLine> lines)
    {
        // DOC_DETAIL intentionally omits patient references and does not carry display names.
        // Resolve exact local PARTS.ID metadata only; never replace final monetary values.
        var ids = lines.Where(line => string.IsNullOrWhiteSpace(line.Name) || string.IsNullOrWhiteSpace(line.Barcode))
            .Select(line => line.PartId).Distinct().ToArray();
        var metadata = new Dictionary<int, (string? Barcode, string? Name)>();
        foreach (var batch in ids.Chunk(500))
        {
            try
            {
                using var command = connection.CreateCommand();
                command.Transaction = transaction;
                command.CommandTimeout = TimeoutSec();
                command.CommandText = "select first 501 p.ID as PART_ID,p.ORIG_SNAME as SNAME,p.BARCODE,p.BARCODE1 from PARTS p where p.ID in (" +
                    string.Join(",", Enumerable.Range(0, batch.Length).Select(index => "@part" + index)) + ")";
                for (var index = 0; index < batch.Length; index++) command.Parameters.AddWithValue("@part" + index, batch[index]);
                using var reader = command.ExecuteReader();
                while (reader.Read())
                    metadata.Add(ReadInt32(reader, "PART_ID"), (SelectProductBarcode(ReadString(reader, "BARCODE1"),
                        ReadString(reader, "BARCODE")), ReadString(reader, "SNAME")));
            }
            catch { /* Optional directory columns vary; unknown metadata is never guessed. */ }
        }
        for (var index = 0; index < lines.Count; index++)
        {
            var line = lines[index];
            metadata.TryGetValue(line.PartId, out var item);
            lines[index] = line with
            {
                Barcode = !string.IsNullOrWhiteSpace(line.Barcode) ? line.Barcode : item.Barcode,
                Name = !string.IsNullOrWhiteSpace(line.Name) ? line.Name : item.Name ?? ("Товар #" + line.PartId),
            };
        }
    }
}
