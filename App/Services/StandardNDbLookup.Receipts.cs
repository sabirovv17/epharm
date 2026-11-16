using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using FirebirdSql.Data.FirebirdClient;

namespace CustomerDisplay.Services;

public sealed partial class StandardNDbLookup
{
    private string? _postedUnitShapeSource;
    private bool _postedHumanQuantityAvailable;

    private bool HasPostedHumanQuantity(FbConnection connection, FbTransaction transaction)
    {
        var source = RewardSourceNamespace;
        if (source != null && source == _postedUnitShapeSource) return _postedHumanQuantityAvailable;
        try
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandTimeout = TimeoutSec();
            command.CommandText = @"select f.RDB$FIELD_TYPE from RDB$RELATION_FIELDS rf
                join RDB$FIELDS f on f.RDB$FIELD_NAME=rf.RDB$FIELD_SOURCE
                where rf.RDB$RELATION_NAME='DOC_DETAIL' and rf.RDB$FIELD_NAME='HUMAN_QUANT'";
            using var reader = command.ExecuteReader();
            var available = reader.Read() && !reader.IsDBNull(0) && Convert.ToInt32(reader.GetValue(0)) is 14 or 37;
            if (reader.Read()) available = false;
            _postedHumanQuantityAvailable = available;
            _postedUnitShapeSource = source;
            return available;
        }
        catch { return false; } // An optional unit field must never break legacy sale capture.
    }

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
            var humanQuantityAvailable = HasPostedHumanQuantity(connection, transaction);
            var result = new List<StandardNReceiptSnapshot>();
            foreach (var group in groups)
            {
                var observed = group.Snapshot;
                string? verifiedWorkstationId;
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
                    verifiedWorkstationId = session.WorkstationId?.ToString(System.Globalization.CultureInfo.InvariantCulture);
                    if (reader.Read() || session.SessionId != observed.SessionId || session.UserId != group.PharmacistId ||
                        observed.WorkstationId != null && observed.WorkstationId != verifiedWorkstationId ||
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
                    command.CommandText = StandardNReceiptProjection.PostedLinesSql(members.Count, humanQuantityAvailable);
                    command.Parameters.AddWithValue("@sessionId", observed.SessionId);
                    for (var index = 0; index < members.Count; index++) command.Parameters.AddWithValue("@doc" + index, members[index].Id);
                    using var reader = command.ExecuteReader();
                    while (reader.Read()) lines.Add(StandardNReceiptProjection.Line(reader));
                }
                if (lines.Count > StandardNReceiptProjection.MaxLines ||
                    lines.Any(line => members.All(header => header.Id != line.SourceDocumentId || header.Status != 1)))
                    throw new InvalidDataException("Posted physical detail membership is not proven.");
                var documents = members.Select(header => {
                    var observedLines = FinalLineObservations(observed.Documents.SingleOrDefault(document => document.DocumentId == header.Id));
                    var enriched = lines.Where(line => line.SourceDocumentId == header.Id).Select(line =>
                    {
                        // Metadata enrichment is exact local product identity only. Final prices,
                        // quantities and totals always come from DOC_DETAIL, never this observation.
                        var old = observedLines.Where(previous => previous.PartId == line.PartId).ToArray();
                        var barcodes = old.Select(previous => previous.Barcode).Where(value => !string.IsNullOrWhiteSpace(value))
                            .Distinct(StringComparer.Ordinal).ToArray();
                        var names = old.Select(previous => previous.Name).Where(value => !string.IsNullOrWhiteSpace(value))
                            .Distinct(StringComparer.Ordinal).ToArray();
                        return line with { Barcode = barcodes.Length == 1 ? barcodes[0] : null, Name = names.Length == 1 ? names[0] : "" };
                    }).ToArray();
                    return StandardNReceiptProjection.Document(header, StandardNFinalLineLinker.Link(header.Id, enriched, observedLines)) with {
                        FinalDetailsRead = header.Status == 1, FinalLineLinkRequired = humanQuantityAvailable,
                    };
                }).ToArray();
                var counters = documents.Where(document => StandardNReceiptProjection.IsPostedPhysicalSale(document) ||
                    StandardNReceiptProjection.IsPostedEmptyDocument(document)).Select(document =>
                    (document.DeviceNumber!.ToUpperInvariant(), document.FiscalShift, document.FiscalNumber)).ToArray();
                if (counters.Distinct().Count() != counters.Length)
                    throw new InvalidDataException("Physical fiscal counter identity is ambiguous.");
                result.Add(observed with { Documents = documents, Lines = lines, WorkstationId = verifiedWorkstationId,
                    ObservedSourceNamespace = ReceiptConnectionNamespace(connection) });
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

    internal static IReadOnlyList<StandardNReceiptLine> FinalLineObservations(StandardNReceiptDocument? document) =>
        document == null ? Array.Empty<StandardNReceiptLine>() :
        document.Status == 1 && document.FinalDetailsRead
            ? document.Lines.Where(line => line.ObservedSourceLineId is > 0 &&
                line.LineLinkSource == StandardNFinalLineLinker.Source).ToArray()
            : document.Lines;

    internal static bool IsCurrentReceiptPosting(long sessionId, IReadOnlyList<StandardNReceiptHeader> active,
        IReadOnlyList<StandardNReceiptHeader> members)
    {
        var key = StandardNReceiptProjection.CurrentGroup(sessionId, active)
            ?? throw new InvalidDataException("No active physical receipt group.");
        StandardNReceiptProjection.ValidateMembers(sessionId, key.Root, key.Linked, members);
        if (!active.Select(row => row.Id).OrderBy(id => id).SequenceEqual(
                members.Where(row => row.Status == 0).Select(row => row.Id).OrderBy(id => id)) ||
            members.Any(row => row.Status is not (0 or 1)))
            throw new InvalidDataException("Receipt membership changed or contains unknown documents.");
        return members.Any(row => row.Status == 1);
    }

    internal static bool KnownReceiptMatchesSession(StandardNReceiptSnapshot? known, long sessionId, string userId,
        string? workstationId, string? workstationName) =>
        known != null && sessionId > 0 && known.SessionId == sessionId && known.Pharmacist.SessionId == sessionId &&
        !string.IsNullOrWhiteSpace(userId) && known.Pharmacist.Id == userId &&
        !string.IsNullOrWhiteSpace(workstationId) && known.WorkstationId == workstationId &&
        StaffSessionResolver.CanonicalComputer(workstationName) is { Length: > 0 } machine &&
        StaffSessionResolver.CanonicalComputer(known.WorkstationName) == machine;

    internal static bool IsKnownReceiptPosting(StandardNReceiptSnapshot known, IReadOnlyList<StandardNReceiptHeader> members)
    {
        if (known.LinkId is > 0 && known.LinkId != known.DocumentId ||
            known.Documents is not { Count: > 0 and <= StandardNReceiptProjection.MaxDocuments } documents ||
            documents.Any(document => document.SessionId != known.SessionId || document.DocumentType != 3 ||
                document.Status is not (0 or 1)) || documents.Select(document => document.DocumentId).Distinct().Count() != documents.Count ||
            !documents.Select(document => document.DocumentId).OrderBy(id => id).SequenceEqual(
                members.Select(row => row.Id).OrderBy(id => id)) || members.Any(row => row.Status != 1)) return false;
        StandardNReceiptProjection.ValidateMembers(known.SessionId, known.DocumentId, known.LinkId is > 0, members);
        return true;
    }

    private string ReceiptConnectionNamespace(FbConnection connection)
    {
        var source = new FbConnectionStringBuilder(connection.ConnectionString);
        return RecommendationRewardJournal.SourceNamespace(source.DataSource, source.Port, source.Database, _machineName);
    }

    private bool TryReadCurrentReceiptGroup(out StandardNReceiptSnapshot? receipt, out bool posting,
        StandardNReceiptSnapshot? knownCurrent = null)
    {
        receipt = null;
        posting = false;
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
            if (!key.HasValue)
            {
                // Posting precedes the human print/save dialog; only the later real cue may finish this group.
                if (KnownReceiptMatchesSession(knownCurrent, session.SessionId, session.UserId,
                    session.WorkstationId?.ToString(System.Globalization.CultureInfo.InvariantCulture), session.WorkstationName))
                {
                    var posted = ReadReceiptHeaders(connection, transaction, knownCurrent!.LinkId is > 0
                        ? StandardNReceiptProjection.LinkedDocumentsSql : StandardNReceiptProjection.SingleDocumentSql,
                        ("@rootId", knownCurrent.DocumentId));
                    posting = IsKnownReceiptPosting(knownCurrent, posted);
                }
                return true;
            }
            var members = ReadReceiptHeaders(connection, transaction, key.Value.Linked
                ? StandardNReceiptProjection.LinkedDocumentsSql : StandardNReceiptProjection.SingleDocumentSql,
                ("@rootId", key.Value.Root));
            if (IsCurrentReceiptPosting(session.SessionId, active, members))
            {
                // Posted IDs and EANs are payment evidence, never additions to the live cart.
                posting = true;
                return true;
            }
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
            if (lines.Count > StandardNReceiptProjection.MaxLines) throw new InvalidDataException("Receipt detail bound exceeded.");
            EnrichReceiptProductIdentity(connection, transaction, lines);
            receipt = StandardNReceiptProjection.Current(session.SessionId, session.WorkstationName!,
                new(session.UserId, session.UserName, session.SessionId), active, members, lines) with
                { WorkstationId = session.WorkstationId?.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    ObservedSourceNamespace = ReceiptConnectionNamespace(connection) };
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
        var heartbeatTimestamp = ReadStaffSessionTimestampShape(connection, transaction).Heartbeat;
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandTimeout = TimeoutSec();
        // Vendor sessions can remain open after a crash; only a supported DB heartbeat proves liveness.
        command.CommandText = StandardNReceiptProjection.CurrentSessionSql(heartbeatTimestamp);
        command.Parameters.AddWithValue("@machine", machine);
        using var reader = command.ExecuteReader();
        var rows = new List<StandardNReceiptSessionRow>();
        while (reader.Read())
        {
            rows.Add(StandardNReceiptProjection.Session(reader));
        }
        var selected = StandardNReceiptProjection.ExactLocalSession(machine, rows, heartbeatTimestamp);
        if (selected == null) return null;
        _userNameCache.TryGet(ReceiptConnectionNamespace(connection), selected.UserId, out var name);
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
