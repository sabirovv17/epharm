using System;
using System.Collections.Generic;
using System.Data;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using CustomerDisplay.Config;
using FirebirdSql.Data.FirebirdClient;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Read-only lookup into the local Standard-N Firebird database (ztrade).
    /// This service is strictly optional: every failure is swallowed and rate-limited in logs,
    /// because POSM must never block the cash desk when Firebird/path/credentials are unavailable.
    /// </summary>
    public sealed class StandardNDbLookup : IDisposable
    {
        private static readonly string[] DefaultDbPaths =
        {
            @"C:\Standart-N_DEMO\Apteka_KZ DEMO\db\ztrade",
            @"C:\Standart-N_DEMO\Apteka_KZ DEMO\db\ztrade.fdb",
            @"C:\Standart-N\db\ztrade",
            @"C:\Standart-N\db\ztrade.fdb",
        };

        // Standard-N installations in pharmacies are not schema-identical. In particular,
        // ACTIVEUSERS may omit columns used by another release. Read the actual row shape and
        // map semantic fields instead of issuing one brittle join that fails as a whole.
        private static readonly string[] ActiveUserTables = { "ACTIVEUSERS", "ACTIVE_USERS" };
        private static readonly string[] SessionTables = { "SESSIONS", "SP$SESSIONS" };
        private static readonly string[] UserTables =
        {
            "USERS", "USERLIST", "USER_LIST", "SP$USERS", "SP$USER",
        };
        private static readonly string[] UserIdColumns =
        {
            "USERID", "IDUSER", "USERSID", "IDUSERS", "IUSERID", "EMPLOYEEID",
            "PHARMACISTID", "KASSIRID", "CASHIERID", "USERCODE", "ID",
        };
        private static readonly string[] DisplayNameColumns =
        {
            "USERNAMEN", "USERFULLNAME", "FULLNAME", "DISPLAYNAME", "FIO",
            "EMPLOYEENAME", "PHARMACISTNAME", "KASSIRNAME", "CASHIERNAME",
            "OPERATORNAME", "USERNAME", "USER_SNAME", "USERLOGIN", "LOGIN",
            "USERCODE", "SNAME", "CAPTION", "NAME",
        };
        private static readonly string[] SessionColumns =
        {
            "SESSIONID", "IDSESSION", "ACTIVESESSIONID", "CONNECTID", "CONNECTIONID",
        };
        private static readonly string[] MachineColumns =
        {
            "COMPUTERNAME", "MACHINENAME", "HOSTNAME", "WORKSTATION", "WORKSTATIONNAME",
            "CLIENTNAME", "TERMINAL", "TERMINALNAME", "COMPUTER", "HOST", "KASSA",
            "WS_ID", "WSID",
        };
        private static readonly string[] ActivityTimeColumns =
        {
            "LASTACTIVE", "LASTACTIVITY", "LOGINTIME", "CONNECTTIME", "STARTTIME",
            "STARTEDAT", "CREATEDAT", "OPENDATE", "DATETIME", "EVENTTIME",
        };
        private static readonly string[] EndTimeColumns = { "ENDDT", "ENDTIME", "CLOSEDAT", "CLOSEDT" };
        private static readonly string[] EndFlagColumns = { "ENDFLAG", "CLOSEFLAG", "CLOSED", "ISENDED" };
        private static readonly string[] ProgramColumns = { "PROG", "PROGRAM", "APP", "APPLICATION" };

        private readonly EpharmConfig _cfg;
        private readonly Action<string> _log;
        private readonly string _machineName;
        private DateTimeOffset _lastErrorLog = DateTimeOffset.MinValue;
        private string? _resolvedDbPath;
        private bool _resolvedFromOptionsIni;
        private string? _optionsDbHost;
        private string? _optionsDbUser;
        private string? _optionsDbPassword;
        private string? _successfulConnectionKey;
        private string? _loggedConnectionSource;
        private string? _loggedActiveUserShape;
        private readonly Dictionary<string, string?> _userNameCache = new(StringComparer.OrdinalIgnoreCase);
        private bool _disposed;

        public StandardNDbLookup(EpharmConfig cfg, Action<string> log, string? machineName = null)
        {
            _cfg = cfg;
            _log = log;
            _machineName = string.IsNullOrWhiteSpace(machineName) ? Environment.MachineName : machineName.Trim();
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        }

        public string Describe()
        {
            if (!_cfg.StandardNDbEnabled) return "выключена";
            var path = ResolveDbPath(logIfMissing: false) ?? "не найден";
            var preferred = BuildConnectionCandidates(path).First();
            return $"{preferred.Host}:{preferred.Port}, db={path}, connection={preferred.Source}";
        }

        public StandardNActivePharmacist? GetActivePharmacist()
        {
            if (!_cfg.StandardNDbEnabled) return null;
            TryGetActivePharmacist(out var active);
            return active;
        }

        /// <summary>
        /// Reads the live Standard-N receipt owned by the local zkassa session. Production
        /// Standard-N builds do not consistently write Add2Cheque details to zkassa.log, while
        /// DOCS + DOC_DETAIL_ACTIVE are the authoritative state used by the cashier itself.
        /// A false result means a database/schema failure and must not clear the last known cart;
        /// true + null means the query succeeded and this workstation has no open receipt.
        /// </summary>
        public bool TryGetCurrentReceipt(out StandardNReceiptSnapshot? receipt)
        {
            receipt = null;
            if (!_cfg.StandardNDbEnabled) return false;

            try
            {
                using var conn = OpenConnection();
                var cashSession = QueryLocalCashSession(conn);
                if (cashSession == null) return true;

                using var docCmd = conn.CreateCommand();
                docCmd.CommandTimeout = TimeoutSec();
                docCmd.CommandText = @"
                    select first 1 *
                    from DOCS
                    where STATUS = 0
                      and DOC_TYPE = 3
                      and AUDIT_ID = @sessionId
                    order by ID desc";
                docCmd.Parameters.AddWithValue("@sessionId", cashSession.SessionId);

                long documentId;
                DateTimeOffset? openedAt = null;
                using (var reader = docCmd.ExecuteReader())
                {
                    if (!reader.Read()) return true;
                    documentId = ReadInt64(reader, "ID") ?? 0L;
                    if (documentId <= 0) return true;
                    openedAt = ToDateTimeOffset(ReadValue(reader, "INSERTDT"));
                }

                using var linesCmd = conn.CreateCommand();
                linesCmd.CommandTimeout = TimeoutSec();
                linesCmd.CommandText = @"
                    select *
                    from DOC_DETAIL_ACTIVE
                    where DOC_ID = @documentId
                    order by ID";
                linesCmd.Parameters.AddWithValue("@documentId", documentId);

                var rawLines = new List<StandardNReceiptLine>();
                using (var reader = linesCmd.ExecuteReader())
                {
                    while (reader.Read())
                    {
                        var partId = ReadInt32(reader, "PART_ID");
                        var qty = Math.Abs(ReadDecimal(reader, "QUANT"));
                        if (partId <= 0 || qty <= 0m) continue;

                        var price = Math.Abs(ReadDecimal(reader, "PRICE"));
                        var lineTotal = Math.Abs(ReadDecimal(reader, "SUMMA"));
                        var baseTotal = price * qty;
                        var discountPercent = baseTotal > 0m && lineTotal >= 0m && lineTotal < baseTotal
                            ? Math.Round((baseTotal - lineTotal) / baseTotal * 100m, 2, MidpointRounding.AwayFromZero)
                            : 0m;

                        rawLines.Add(new StandardNReceiptLine(
                            ReadInt64(reader, "ID") ?? partId,
                            partId,
                            SelectProductBarcode(
                                ReadString(reader, "BCODE_IZG"),
                                ReadString(reader, "ORIG_BCODE_IZG"),
                                ReadString(reader, "BARCODE1"),
                                ReadString(reader, "BARCODE")),
                            ReadString(reader, "SNAME") ?? "",
                            price,
                            qty,
                            discountPercent));
                    }
                }

                // ReceiptItem is keyed by PARTS.ID. A cashier build may expose the same batch in
                // more than one active detail row; aggregate it before handing state to the UI.
                var lines = rawLines
                    .GroupBy(line => line.PartId)
                    .Select(group =>
                    {
                        var newest = group.OrderByDescending(line => line.LineId).First();
                        return newest with { Qty = group.Sum(line => line.Qty) };
                    })
                    .OrderBy(line => line.LineId)
                    .ToList();

                receipt = new StandardNReceiptSnapshot(
                    documentId,
                    cashSession.SessionId,
                    cashSession.WorkstationName,
                    openedAt,
                    new StandardNActivePharmacist(
                        cashSession.UserId,
                        cashSession.UserName,
                        cashSession.SessionId),
                    lines);
                return true;
            }
            catch (Exception ex)
            {
                LogErrorRateLimited($"БД Стандарт-Н недоступна (активный чек): {ex.GetBaseException().Message}");
                return false;
            }
        }

        /// <summary>
        /// Read-only report for a pharmacy workstation. It intentionally prints table/column
        /// shapes and active-user rows, but never prints database credentials.
        /// </summary>
        public string BuildIdentityDiagnostics()
        {
            var report = new StringBuilder();
            report.AppendLine("Epharm POSM / Standard-N identity diagnostics");
            report.AppendLine($"Collected: {DateTimeOffset.Now:O}");
            report.AppendLine($"Computer: {Environment.MachineName}");
            report.AppendLine($"Database: {Describe()}");
            report.AppendLine();

            try
            {
                using var conn = OpenConnection();
                report.AppendLine("Firebird connection: OK");
                report.AppendLine();
                AppendIdentitySchema(conn, report);
                AppendActiveUserRows(conn, report);
                AppendSessionRows(conn, report);
                AppendRecentUserActions(conn, report);
            }
            catch (Exception ex)
            {
                report.AppendLine($"Firebird connection/schema error: {ex}");
            }

            report.AppendLine();
            try
            {
                var active = QueryActivePharmacist();
                if (active == null)
                {
                    report.AppendLine("RESOLVED ACTIVE PHARMACIST: not found");
                }
                else
                {
                    report.AppendLine($"RESOLVED ACTIVE PHARMACIST: id={active.Id}; name={active.Name ?? "—"}; session={active.SessionId?.ToString() ?? "—"}");
                }
            }
            catch (Exception ex)
            {
                report.AppendLine($"RESOLVED ACTIVE PHARMACIST ERROR: {ex}");
            }

            report.AppendLine();
            if (TryGetCurrentReceipt(out var receipt))
            {
                if (receipt == null)
                {
                    report.AppendLine("CURRENT CASH RECEIPT: no open receipt for this workstation");
                }
                else
                {
                    report.AppendLine(
                        $"CURRENT CASH RECEIPT: doc={receipt.DocumentId}; session={receipt.SessionId}; " +
                        $"workstation={receipt.WorkstationName ?? "—"}; lines={receipt.Lines.Count}");
                    report.AppendLine(
                        $"CURRENT CASHIER: id={receipt.Pharmacist.Id}; " +
                        $"name={receipt.Pharmacist.Name ?? "—"}");
                    foreach (var line in receipt.Lines.Take(50))
                    {
                        report.AppendLine(
                            $"  line={line.LineId}; part={line.PartId}; barcode={line.Barcode ?? "—"}; " +
                            $"qty={line.Qty:0.###}; price={line.Price:0.##}; name={line.Name}");
                    }
                }
            }
            else
            {
                report.AppendLine("CURRENT CASH RECEIPT: query failed; see application log");
            }

            return report.ToString();
        }

        private static void AppendIdentitySchema(FbConnection conn, StringBuilder report)
        {
            var relations = new List<string>();
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = @"
                    select trim(r.rdb$relation_name)
                    from rdb$relations r
                    where coalesce(r.rdb$system_flag, 0) = 0
                    order by r.rdb$relation_name";
                using var reader = cmd.ExecuteReader();
                while (reader.Read())
                {
                    var relation = reader.IsDBNull(0) ? "" : reader.GetString(0).Trim();
                    if (relation.Length > 0) relations.Add(relation);
                }
            }

            var interesting = new List<(string Table, string Columns)>();
            foreach (var relation in relations)
            {
                var columns = new List<string>();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"
                    select trim(rf.rdb$field_name)
                    from rdb$relation_fields rf
                    where trim(rf.rdb$relation_name) = @relation
                    order by rf.rdb$field_position";
                cmd.Parameters.AddWithValue("@relation", relation);
                using var reader = cmd.ExecuteReader();
                while (reader.Read())
                {
                    if (!reader.IsDBNull(0)) columns.Add(reader.GetString(0).Trim());
                }

                var signature = relation + " " + string.Join(" ", columns);
                if (IdentitySchemaLooksRelevant(signature))
                    interesting.Add((relation, string.Join(", ", columns)));
            }

            report.AppendLine("IDENTITY-RELATED TABLES/COLUMNS:");
            if (interesting.Count == 0) report.AppendLine("  none detected");
            foreach (var row in interesting.Take(100))
                report.AppendLine($"  {row.Table}: {row.Columns}");
            report.AppendLine();
        }

        private static bool IdentitySchemaLooksRelevant(string value)
        {
            var normalized = NormalizeIdentifier(value);
            return new[] { "ACTIVEUSER", "USER", "KASSIR", "CASHIER", "OPERATOR", "EMPLOYEE", "SESSION", "WORKSTATION" }
                .Any(normalized.Contains);
        }

        private static void AppendActiveUserRows(FbConnection conn, StringBuilder report)
        {
            report.AppendLine("ACTIVE USER ROWS (read-only, first 20):");
            foreach (var table in ActiveUserTables)
            {
                try
                {
                    using var cmd = conn.CreateCommand();
                    cmd.CommandText = $"select first 20 * from {table}";
                    using var reader = cmd.ExecuteReader();
                    report.AppendLine($"  TABLE {table}: {reader.FieldCount} columns");
                    var rowNumber = 0;
                    while (reader.Read())
                    {
                        rowNumber++;
                        var values = new List<string>();
                        for (var i = 0; i < reader.FieldCount; i++)
                        {
                            var column = reader.GetName(i);
                            var normalizedColumn = NormalizeIdentifier(column);
                            var secret = normalizedColumn.Contains("PASSWORD") || normalizedColumn.Contains("PASSWD") ||
                                         normalizedColumn.Contains("PWD") || normalizedColumn.Contains("TOKEN") ||
                                         normalizedColumn.Contains("SECRET") || normalizedColumn.Contains("HASH");
                            var value = secret ? "***REDACTED***" : DiagnosticValue(ReadValue(reader, i));
                            values.Add($"{column}={value}");
                        }
                        report.AppendLine($"    row {rowNumber}: {string.Join("; ", values)}");
                    }
                    if (rowNumber == 0) report.AppendLine("    no active rows");
                }
                catch (Exception ex)
                {
                    report.AppendLine($"  TABLE {table}: {ex.Message}");
                }
            }
            report.AppendLine();
        }

        private static void AppendSessionRows(FbConnection conn, StringBuilder report)
        {
            report.AppendLine("RECENT STANDARD-N SESSIONS (read-only, first 30 per table):");
            foreach (var table in SessionTables)
            {
                try
                {
                    using var cmd = conn.CreateCommand();
                    cmd.CommandText = $"select first 30 * from {table} order by STARTDT desc";
                    using var reader = cmd.ExecuteReader();
                    report.AppendLine($"  TABLE {table}: {reader.FieldCount} columns");
                    var rowNumber = 0;
                    while (reader.Read())
                    {
                        rowNumber++;
                        var values = new List<string>();
                        for (var i = 0; i < reader.FieldCount; i++)
                        {
                            var column = reader.GetName(i);
                            var normalizedColumn = NormalizeIdentifier(column);
                            if (normalizedColumn is "ID" or "USERID" or "WSID" or "STARTDT" or
                                "ENDDT" or "ENDFLAG" or "ENDSESSIONID" or "PROG" or "HEARTBEATS")
                                values.Add($"{column}={DiagnosticValue(ReadValue(reader, i))}");
                        }
                        report.AppendLine($"    row {rowNumber}: {string.Join("; ", values)}");
                    }
                    if (rowNumber == 0) report.AppendLine("    no session rows");
                }
                catch (Exception ex)
                {
                    report.AppendLine($"  TABLE {table}: {ex.Message}");
                }
            }
            report.AppendLine();
        }

        private static void AppendRecentUserActions(FbConnection conn, StringBuilder report)
        {
            report.AppendLine("RECENT USER ACTIONS (read-only, first 30):");
            try
            {
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"
                    select first 30 USER_ID, USER_SNAME, SESSION_ID, INSERTDT, ACT_TYPE
                    from HUMAN_ACTION_LOGS
                    order by INSERTDT desc";
                using var reader = cmd.ExecuteReader();
                var rowNumber = 0;
                while (reader.Read())
                {
                    rowNumber++;
                    report.AppendLine(
                        $"  row {rowNumber}: USER_ID={DiagnosticValue(ReadValue(reader, 0))}; " +
                        $"USER_SNAME={DiagnosticValue(ReadValue(reader, 1))}; " +
                        $"SESSION_ID={DiagnosticValue(ReadValue(reader, 2))}; " +
                        $"INSERTDT={DiagnosticValue(ReadValue(reader, 3))}; " +
                        $"ACT_TYPE={DiagnosticValue(ReadValue(reader, 4))}");
                }
                if (rowNumber == 0) report.AppendLine("  no action rows");
            }
            catch (Exception ex)
            {
                report.AppendLine($"  HUMAN_ACTION_LOGS: {ex.Message}");
            }
            report.AppendLine();
        }

        private static string DiagnosticValue(object? value)
        {
            var text = ValueToString(value) ?? "<null>";
            text = text.Replace('\r', ' ').Replace('\n', ' ');
            return text.Length <= 200 ? text : text.Substring(0, 200) + "…";
        }

        /// <summary>
        /// Активный фармацевт из ACTIVEUSERS или постоянной таблицы сессий с различением исходов:
        /// true  — запрос выполнился (active == null означает «никто не залогинен»);
        /// false — БД недоступна/ошибка (active == null НИЧЕГО не значит — прежнее значение
        ///         фармацевта затирать нельзя, иначе временный сбой БД стирает известного кассира).
        /// </summary>
        public bool TryGetActivePharmacist(out StandardNActivePharmacist? active)
        {
            active = null;
            if (!_cfg.StandardNDbEnabled) return false;

            try
            {
                active = QueryActivePharmacist();
                return true;
            }
            catch (Exception ex)
            {
                LogErrorRateLimited($"БД Стандарт-Н недоступна (активный фармацевт): {ex.Message}");
                return false;
            }
        }

        private StandardNActivePharmacist? QueryActivePharmacist()
        {
            using var conn = OpenConnection();
            Exception? firstError = null;
            var activeSourceReadable = false;

            // The workstation-bound zkassa session is more precise than ACTIVEUSERS on a shared
            // pharmacy database: it cannot attribute a neighbouring cash desk's pharmacist.
            try
            {
                var localCash = QueryLocalCashSession(conn);
                if (localCash != null)
                {
                    return new StandardNActivePharmacist(
                        localCash.UserId,
                        localCash.UserName,
                        localCash.SessionId);
                }
                activeSourceReadable = true;
            }
            catch (Exception ex)
            {
                firstError ??= ex;
            }

            foreach (var table in ActiveUserTables)
            {
                try
                {
                    using var cmd = conn.CreateCommand();
                    cmd.CommandTimeout = TimeoutSec();
                    cmd.CommandText = $"select first 100 * from {table}";
                    using var reader = cmd.ExecuteReader();
                    activeSourceReadable = true;

                    var columns = BuildColumnMap(reader);
                    var idOrdinal = FindColumn(columns, UserIdColumns);
                    var nameOrdinal = FindColumn(columns, DisplayNameColumns);
                    var sessionOrdinal = FindColumn(columns, SessionColumns);
                    var machineOrdinal = FindColumn(columns, MachineColumns);
                    var timeOrdinal = FindColumn(columns, ActivityTimeColumns);

                    if (idOrdinal == null && nameOrdinal == null)
                    {
                        firstError ??= new InvalidOperationException(
                            $"{table} не содержит распознаваемых колонок пользователя; поля: " +
                            string.Join(",", Enumerable.Range(0, reader.FieldCount).Select(reader.GetName)));
                        continue;
                    }

                    var candidates = new List<ActiveUserCandidate>();
                    var rowIndex = 0;
                    while (reader.Read())
                    {
                        rowIndex++;
                        var rawId = ReadValue(reader, idOrdinal);
                        var id = ValueToString(rawId);
                        var name = ReadDisplayName(reader, columns);
                        if (string.IsNullOrWhiteSpace(id) && string.IsNullOrWhiteSpace(name)) continue;

                        var machine = ValueToString(ReadValue(reader, machineOrdinal));
                        candidates.Add(new ActiveUserCandidate(
                            id ?? "",
                            name,
                            rawId,
                            TryConvertInt64(ReadValue(reader, sessionOrdinal)),
                            MachineRank(machine),
                            ActivityTicks(ReadValue(reader, timeOrdinal)),
                            rowIndex));
                    }

                    // On current Standard-N builds ACTIVEUSERS can be connection-local and empty
                    // for POSM even while a cashier is logged in. Continue with persistent sessions.
                    if (candidates.Count == 0) continue;

                    var selected = candidates
                        .OrderByDescending(x => x.MachineRank)
                        .ThenByDescending(x => x.SessionId ?? long.MinValue)
                        .ThenByDescending(x => x.ActivityTicks)
                        .ThenByDescending(x => x.RowIndex)
                        .First();

                    var shape = $"{table}:id={ColumnName(reader, idOrdinal)}," +
                                $"name={ColumnName(reader, nameOrdinal)}," +
                                $"session={ColumnName(reader, sessionOrdinal)}," +
                                $"machine={ColumnName(reader, machineOrdinal)}";

                    // Firebird does not reliably allow a second command while this reader is open.
                    // Finish ACTIVEUSERS first, then enrich the selected identity from USERS.
                    reader.Close();
                    var resolvedName = TryResolveUserName(
                        conn,
                        selected.RawId,
                        selected.Id,
                        selected.SessionId);
                    var finalName = FirstNotEmpty(resolvedName, selected.Name);
                    var finalId = FirstNotEmpty(selected.Id, finalName) ?? "";
                    if (finalId.Length == 0 && string.IsNullOrWhiteSpace(finalName)) return null;

                    if (!string.Equals(_loggedActiveUserShape, shape, StringComparison.Ordinal))
                    {
                        _loggedActiveUserShape = shape;
                        _log($"БД Стандарт-Н: схема активного продавца определена автоматически ({shape})");
                    }

                    return new StandardNActivePharmacist(finalId, finalName, selected.SessionId);
                }
                catch (Exception ex)
                {
                    firstError ??= ex;
                }
            }

            var sessionUser = QueryPersistentSession(
                conn,
                out var sessionSourceReadable,
                out var sessionError);
            firstError ??= sessionError;
            if (sessionUser != null) return sessionUser;

            // At least one authoritative source was read successfully and contained no active
            // cashier. This is a valid "nobody logged in" result, not a database failure.
            if (activeSourceReadable || sessionSourceReadable) return null;

            throw new InvalidOperationException(
                $"не удалось прочитать таблицу активных пользователей Стандарт-Н: " +
                (firstError?.Message ?? "причина не определена"),
                firstError);
        }

        private StandardNCashSession? QueryLocalCashSession(FbConnection conn)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandTimeout = TimeoutSec();
            cmd.CommandText = @"
                select first 100
                    s.ID as SESSION_ID,
                    s.USER_ID,
                    s.WS_ID,
                    s.PROG,
                    s.STARTDT,
                    s.HEARTBEATS,
                    w.COMPNAME
                from SESSIONS s
                left join WORKSTATIONS w on w.ID = s.WS_ID
                where s.ENDDT is null
                  and (s.PROG containing 'zkassa' or s.PROG containing 'kassir')
                order by s.HEARTBEATS desc, s.STARTDT desc, s.ID desc";

            using var reader = cmd.ExecuteReader();
            var candidates = new List<LocalCashSessionCandidate>();
            while (reader.Read())
            {
                var program = ReadString(reader, "PROG");
                if (ProgramRank(program) < 3) continue;

                var rawUserId = ReadValue(reader, "USER_ID");
                var userId = ValueToString(rawUserId);
                var sessionId = ReadInt64(reader, "SESSION_ID");
                if (!sessionId.HasValue || string.IsNullOrWhiteSpace(userId)) continue;

                var workstationName = ReadString(reader, "COMPNAME");
                candidates.Add(new LocalCashSessionCandidate(
                    sessionId.Value,
                    rawUserId,
                    userId,
                    ReadInt64(reader, "WS_ID"),
                    workstationName,
                    LocalMachineRank(workstationName),
                    ActivityTicks(ReadValue(reader, "HEARTBEATS")),
                    ActivityTicks(ReadValue(reader, "STARTDT"))));
            }

            if (candidates.Count == 0) return null;

            // Exact workstation name wins. A contains-match covers Standard-N values such as
            // DOMAIN\KASSA2. Falling back is allowed only when the entire database has one open
            // zkassa session; choosing an arbitrary terminal would show another cashier's cart.
            var local = candidates
                .Where(candidate => candidate.MachineRank >= 1)
                .OrderByDescending(candidate => candidate.MachineRank)
                .ThenByDescending(candidate => candidate.HeartbeatTicks)
                .ThenByDescending(candidate => candidate.StartTicks)
                .ThenByDescending(candidate => candidate.SessionId)
                .FirstOrDefault();
            local ??= candidates.Count == 1 ? candidates[0] : null;
            if (local == null) return null;

            reader.Close();
            var userName = TryResolveUserName(
                conn,
                local.RawUserId,
                local.UserId,
                local.SessionId);

            return new StandardNCashSession(
                local.SessionId,
                local.UserId,
                userName,
                local.WorkstationId,
                local.WorkstationName);
        }

        private StandardNActivePharmacist? QueryPersistentSession(
            FbConnection conn,
            out bool sourceReadable,
            out Exception? firstError)
        {
            sourceReadable = false;
            firstError = null;

            foreach (var table in SessionTables)
            {
                try
                {
                    using var cmd = conn.CreateCommand();
                    cmd.CommandTimeout = TimeoutSec();
                    cmd.CommandText = $"select first 500 * from {table} order by STARTDT desc";
                    using var reader = cmd.ExecuteReader();
                    sourceReadable = true;

                    var columns = BuildColumnMap(reader);
                    var userIdOrdinal = FindColumn(columns, UserIdColumns);
                    var nameOrdinal = FindColumn(columns, DisplayNameColumns);
                    var sessionOrdinal = FindColumn(columns, new[] { "ID", "SESSION_ID", "IDSESSION" });
                    var machineOrdinal = FindColumn(columns, MachineColumns);
                    var startOrdinal = FindColumn(columns, new[] { "STARTDT", "STARTTIME", "STARTEDAT" });
                    var endOrdinal = FindColumn(columns, EndTimeColumns);
                    var endFlagOrdinal = FindColumn(columns, EndFlagColumns);
                    var programOrdinal = FindColumn(columns, ProgramColumns);

                    if (userIdOrdinal == null)
                    {
                        firstError ??= new InvalidOperationException(
                            $"{table} не содержит USER_ID; поля: " +
                            string.Join(",", Enumerable.Range(0, reader.FieldCount).Select(reader.GetName)));
                        continue;
                    }

                    var candidates = new List<SessionUserCandidate>();
                    var rowIndex = 0;
                    while (reader.Read())
                    {
                        rowIndex++;
                        if (!SessionIsOpen(
                                ReadValue(reader, endFlagOrdinal),
                                ReadValue(reader, endOrdinal)))
                            continue;

                        var rawId = ReadValue(reader, userIdOrdinal);
                        var id = ValueToString(rawId);
                        if (string.IsNullOrWhiteSpace(id)) continue;

                        var machine = ValueToString(ReadValue(reader, machineOrdinal));
                        var program = ValueToString(ReadValue(reader, programOrdinal));
                        candidates.Add(new SessionUserCandidate(
                            id,
                            ReadDisplayName(reader, columns),
                            rawId,
                            TryConvertInt64(ReadValue(reader, sessionOrdinal)),
                            MachineRank(machine),
                            ProgramRank(program),
                            ActivityTicks(ReadValue(reader, startOrdinal)),
                            rowIndex));
                    }

                    if (candidates.Count == 0) continue;

                    var selected = candidates
                        .OrderByDescending(x => x.MachineRank)
                        .ThenByDescending(x => x.ProgramRank)
                        .ThenByDescending(x => x.StartTicks)
                        .ThenByDescending(x => x.SessionId ?? long.MinValue)
                        .ThenByDescending(x => x.RowIndex)
                        .First();

                    var shape = $"{table}:user={ColumnName(reader, userIdOrdinal)}," +
                                $"session={ColumnName(reader, sessionOrdinal)}," +
                                $"workstation={ColumnName(reader, machineOrdinal)}," +
                                $"program={ColumnName(reader, programOrdinal)}," +
                                $"start={ColumnName(reader, startOrdinal)}";

                    reader.Close();
                    var resolvedName = TryResolveUserName(
                        conn,
                        selected.RawId,
                        selected.Id,
                        selected.SessionId);
                    var finalName = FirstNotEmpty(resolvedName, selected.Name);

                    if (!string.Equals(_loggedActiveUserShape, shape, StringComparison.Ordinal))
                    {
                        _loggedActiveUserShape = shape;
                        _log($"БД Стандарт-Н: активный продавец читается из открытой сессии ({shape})");
                    }

                    return new StandardNActivePharmacist(selected.Id, finalName, selected.SessionId);
                }
                catch (Exception ex)
                {
                    firstError ??= ex;
                }
            }

            return null;
        }

        private string? TryResolveUserName(
            FbConnection conn,
            object? rawId,
            string id,
            long? sessionId)
        {
            if (string.IsNullOrWhiteSpace(id)) return null;

            var actionName = TryResolveUserNameFromActions(conn, rawId, id, sessionId);
            if (!string.IsNullOrWhiteSpace(actionName))
            {
                _userNameCache[id] = actionName;
                return actionName;
            }

            if (_userNameCache.TryGetValue(id, out var cached)) return cached;

            var queriedUserDirectory = false;
            foreach (var table in UserTables)
            {
                try
                {
                    using var cmd = conn.CreateCommand();
                    cmd.CommandTimeout = TimeoutSec();
                    cmd.CommandText = $"select first 5000 * from {table}";
                    using var reader = cmd.ExecuteReader();
                    var columns = BuildColumnMap(reader);
                    var idOrdinal = FindColumn(columns, UserIdColumns);
                    if (idOrdinal == null) continue;
                    queriedUserDirectory = true;

                    while (reader.Read())
                    {
                        var rowId = ValueToString(ReadValue(reader, idOrdinal));
                        if (!IdsEqual(rowId, id, rawId)) continue;
                        var name = ReadDisplayName(reader, columns);
                        _userNameCache[id] = name;
                        return name;
                    }
                }
                catch
                {
                    // User-name enrichment is optional; the ACTIVEUSERS identity remains usable.
                }
            }

            // Cache a real "not found", but retry after transient Firebird/schema errors.
            if (queriedUserDirectory) _userNameCache[id] = null;
            return null;
        }

        private string? TryResolveUserNameFromActions(
            FbConnection conn,
            object? rawId,
            string id,
            long? sessionId)
        {
            try
            {
                if (sessionId.HasValue)
                {
                    using var bySession = conn.CreateCommand();
                    bySession.CommandTimeout = TimeoutSec();
                    bySession.CommandText = @"
                        select first 1 USER_SNAME
                        from HUMAN_ACTION_LOGS
                        where SESSION_ID = @sessionId
                          and USER_SNAME is not null
                        order by INSERTDT desc";
                    bySession.Parameters.AddWithValue("@sessionId", sessionId.Value);
                    var value = ValueToString(bySession.ExecuteScalar());
                    if (!string.IsNullOrWhiteSpace(value)) return value;
                }

                using var byUser = conn.CreateCommand();
                byUser.CommandTimeout = TimeoutSec();
                byUser.CommandText = @"
                    select first 1 USER_SNAME
                    from HUMAN_ACTION_LOGS
                    where USER_ID = @userId
                      and USER_SNAME is not null
                    order by INSERTDT desc";
                byUser.Parameters.AddWithValue("@userId", rawId ?? id);
                return ValueToString(byUser.ExecuteScalar());
            }
            catch
            {
                // Audit-based name enrichment is optional; raw USER_ID is still authoritative.
                return null;
            }
        }

        private static bool IdsEqual(string? rowId, string expected, object? rawExpected)
        {
            if (string.Equals(rowId?.Trim(), expected.Trim(), StringComparison.OrdinalIgnoreCase)) return true;
            var left = TryConvertInt64(rowId);
            var right = TryConvertInt64(rawExpected);
            return left.HasValue && right.HasValue && left.Value == right.Value;
        }

        private static Dictionary<string, int> BuildColumnMap(IDataRecord reader)
        {
            var result = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            for (var i = 0; i < reader.FieldCount; i++)
            {
                var normalized = NormalizeIdentifier(reader.GetName(i));
                if (normalized.Length > 0 && !result.ContainsKey(normalized)) result[normalized] = i;
            }
            return result;
        }

        private static int? FindColumn(IReadOnlyDictionary<string, int> columns, IEnumerable<string> names)
        {
            foreach (var name in names)
                if (columns.TryGetValue(NormalizeIdentifier(name), out var ordinal))
                    return ordinal;
            return null;
        }

        private static string? ReadDisplayName(IDataRecord reader, IReadOnlyDictionary<string, int> columns)
        {
            var direct = ValueToString(ReadValue(reader, FindColumn(columns, DisplayNameColumns)));
            if (!string.IsNullOrWhiteSpace(direct)) return direct;

            var last = ValueToString(ReadValue(reader, FindColumn(columns,
                new[] { "LASTNAME", "SURNAME", "SNAME", "FAMILY", "FAMILIA" })));
            var first = ValueToString(ReadValue(reader, FindColumn(columns,
                new[] { "FIRSTNAME", "FNAME", "GIVENNAME", "NAME" })));
            var middle = ValueToString(ReadValue(reader, FindColumn(columns,
                new[] { "MIDDLENAME", "PATRONYMIC", "MNAME", "OTCHESTVO" })));
            var parts = new[] { last, first, middle }.Where(x => !string.IsNullOrWhiteSpace(x)).ToArray();
            return parts.Length == 0 ? null : string.Join(" ", parts!);
        }

        private static string NormalizeIdentifier(string? value)
            => new((value ?? "").Where(char.IsLetterOrDigit).Select(char.ToUpperInvariant).ToArray());

        private static object? ReadValue(IDataRecord reader, int? ordinal)
        {
            if (!ordinal.HasValue || ordinal.Value < 0 || ordinal.Value >= reader.FieldCount) return null;
            return reader.IsDBNull(ordinal.Value) ? null : reader.GetValue(ordinal.Value);
        }

        private static string? ValueToString(object? value)
        {
            if (value == null || value is DBNull) return null;
            var text = Convert.ToString(value, CultureInfo.InvariantCulture)?.Trim();
            return string.IsNullOrWhiteSpace(text) ? null : text;
        }

        private static long? TryConvertInt64(object? value)
        {
            if (value == null || value is DBNull) return null;
            try { return Convert.ToInt64(value, CultureInfo.InvariantCulture); }
            catch
            {
                return long.TryParse(ValueToString(value), NumberStyles.Any, CultureInfo.InvariantCulture, out var parsed)
                    ? parsed
                    : null;
            }
        }

        private static long ActivityTicks(object? value)
        {
            if (value is DateTime dt) return dt.Ticks;
            if (value is DateTimeOffset dto) return dto.UtcTicks;
            return DateTimeOffset.TryParse(ValueToString(value), CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeLocal, out var parsed)
                ? parsed.UtcTicks
                : 0L;
        }

        private static DateTimeOffset? ToDateTimeOffset(object? value)
        {
            if (value is DateTimeOffset dto) return dto;
            if (value is DateTime dt) return new DateTimeOffset(dt);
            return DateTimeOffset.TryParse(
                ValueToString(value),
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeLocal,
                out var parsed)
                ? parsed
                : null;
        }

        private static bool SessionIsOpen(object? endFlag, object? endTime)
        {
            if (endTime != null && endTime is not DBNull)
            {
                if (endTime is DateTime dt && dt.Year > 1900) return false;
                if (endTime is DateTimeOffset dto && dto.Year > 1900) return false;
                if (DateTimeOffset.TryParse(
                        ValueToString(endTime),
                        CultureInfo.InvariantCulture,
                        DateTimeStyles.AssumeLocal,
                        out var parsed) && parsed.Year > 1900)
                    return false;
            }

            if (endFlag == null || endFlag is DBNull) return true;
            if (endFlag is bool flag) return !flag;
            var numeric = TryConvertInt64(endFlag);
            if (numeric.HasValue) return numeric.Value == 0;

            var text = NormalizeIdentifier(ValueToString(endFlag));
            return text is "" or "FALSE" or "NO" or "OPEN" or "ACTIVE";
        }

        private static int ProgramRank(string? program)
        {
            var value = NormalizeIdentifier(program);
            if (value.Length == 0) return 0;
            if (new[] { "KASSIR", "KASSA", "CASH", "CHECKOUT", "SALE", "POS" }.Any(value.Contains))
                return 3;
            if (new[] { "MANAGER", "ADMIN", "SERVICE", "SERVER" }.Any(value.Contains))
                return -1;
            return 1;
        }

        private static int MachineRank(string? machine)
        {
            if (string.IsNullOrWhiteSpace(machine)) return 0;
            var actual = NormalizeIdentifier(machine);
            var local = NormalizeIdentifier(Environment.MachineName);
            if (actual == local) return 2;
            if (actual.Contains(local, StringComparison.OrdinalIgnoreCase) ||
                local.Contains(actual, StringComparison.OrdinalIgnoreCase)) return 1;
            return -1;
        }

        private int LocalMachineRank(string? machine)
        {
            if (string.IsNullOrWhiteSpace(machine)) return 0;
            var actual = NormalizeIdentifier(machine);
            var local = NormalizeIdentifier(_machineName);
            if (actual == local) return 2;
            if (actual.Contains(local, StringComparison.OrdinalIgnoreCase) ||
                local.Contains(actual, StringComparison.OrdinalIgnoreCase)) return 1;
            return -1;
        }

        private static string ColumnName(IDataRecord reader, int? ordinal)
            => ordinal.HasValue ? reader.GetName(ordinal.Value) : "—";

        private sealed record ActiveUserCandidate(
            string Id,
            string? Name,
            object? RawId,
            long? SessionId,
            int MachineRank,
            long ActivityTicks,
            int RowIndex);

        private sealed record SessionUserCandidate(
            string Id,
            string? Name,
            object? RawId,
            long? SessionId,
            int MachineRank,
            int ProgramRank,
            long StartTicks,
            int RowIndex);

        private sealed record LocalCashSessionCandidate(
            long SessionId,
            object? RawUserId,
            string UserId,
            long? WorkstationId,
            string? WorkstationName,
            int MachineRank,
            long HeartbeatTicks,
            long StartTicks);

        private sealed record StandardNCashSession(
            long SessionId,
            string UserId,
            string? UserName,
            long? WorkstationId,
            string? WorkstationName);

        private sealed record DbConnectionCandidate(
            string Source,
            string Host,
            int Port,
            string User,
            string Password,
            string Key);

        public StandardNProductInfo? GetProduct(int partId, string? barcode)
        {
            if (!_cfg.StandardNDbEnabled || partId <= 0) return null;
            return SafeQuery(() => QueryProduct(partId, barcode));
        }

        private StandardNProductInfo? QueryProduct(int partId, string? barcode)
        {
            using var conn = OpenConnection();
            var sourceErrors = new List<string>();

            StandardNProductInfo? TrySource(string source, Func<StandardNProductInfo?> query)
            {
                try
                {
                    return query();
                }
                catch (Exception ex)
                {
                    // Standard-N schemas differ between releases. One missing view/column must not
                    // abort the whole enrichment chain: remember it and continue with real tables.
                    sourceErrors.Add($"{source}: {ex.GetBaseException().Message}");
                    return null;
                }
            }

            var byPart = TrySource("VW_WAREBASE_KASSA", () => QueryProductFromWarebase(conn, partId, null))
                ?? TrySource("PARTS", () => QueryProductFromParts(conn, partId, null));
            if (byPart != null) return byPart;

            if (!string.IsNullOrWhiteSpace(barcode))
            {
                var byBarcode = TrySource("VW_WAREBASE_KASSA", () => QueryProductFromWarebase(conn, 0, barcode))
                    ?? TrySource("PARTS", () => QueryProductFromParts(conn, 0, barcode));
                if (byBarcode != null) return byBarcode;
            }

            var fromPrices = TrySource("PRICES", () => QueryProductFromPrices(conn, partId));
            if (fromPrices != null) return fromPrices;

            if (sourceErrors.Count > 0)
                LogErrorRateLimited("БД Стандарт-Н: товар не обогащён; " + string.Join(" | ", sourceErrors));
            return null;
        }

        private StandardNProductInfo? QueryProductFromPrices(FbConnection conn, int partId)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandTimeout = TimeoutSec();
            cmd.CommandText = @"
                select first 1
                    p.part_id,
                    p.price,
                    cast(null as varchar(250)) as barcode,
                    cast(null as varchar(1024)) as sname
                from prices p
                where p.part_id = @partId
                  and p.price_type = 0
                  and p.price > 0
                order by p.id desc";
            cmd.Parameters.AddWithValue("@partId", partId);
            return ReadProduct(cmd, "PRICES");
        }

        private StandardNProductInfo? QueryProductFromParts(FbConnection conn, int partId, string? barcode)
        {
            try
            {
                return QueryProductFromPartsCore(conn, partId, barcode, includeManufacturerBarcode: true);
            }
            catch
            {
                // ORIG_BCODE_IZG is present on the Auezova production schema but not guaranteed on
                // older Standard-N releases. Retry using only release-stable PARTS columns.
                return QueryProductFromPartsCore(conn, partId, barcode, includeManufacturerBarcode: false);
            }
        }

        private StandardNProductInfo? QueryProductFromPartsCore(
            FbConnection conn,
            int partId,
            string? barcode,
            bool includeManufacturerBarcode)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandTimeout = TimeoutSec();
            var barcode2 = includeManufacturerBarcode
                ? "p.orig_bcode_izg"
                : "cast(null as varchar(250))";
            cmd.CommandText = partId > 0
                ? $@"
                    select first 1
                        p.id as part_id,
                        p.price,
                        p.barcode,
                        p.barcode1,
                        {barcode2} as barcode2,
                        p.orig_sname as sname
                    from parts p
                    where p.id = @partId"
                : $@"
                    select first 1
                        p.id as part_id,
                        p.price,
                        p.barcode,
                        p.barcode1,
                        {barcode2} as barcode2,
                        p.orig_sname as sname
                    from parts p
                    where p.barcode = @barcode
                       or p.barcode1 = @barcode
                       or ({barcode2} = @barcode)
                    order by p.id desc";
            if (partId > 0)
                cmd.Parameters.AddWithValue("@partId", partId);
            else
                cmd.Parameters.AddWithValue("@barcode", barcode);
            return ReadProduct(cmd, "PARTS");
        }

        private StandardNProductInfo? QueryProductFromWarebase(FbConnection conn, int partId, string? barcode)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandTimeout = TimeoutSec();
            if (partId > 0)
            {
                cmd.CommandText = @"
                    select first 1
                        v.part_id,
                        v.price,
                        v.barcode,
                        v.barcode1,
                        v.sname
                    from vw_warebase_kassa v
                    where v.part_id = @partId
                      and v.price > 0
                    order by v.updatedt desc";
                cmd.Parameters.AddWithValue("@partId", partId);
            }
            else
            {
                cmd.CommandText = @"
                    select first 1
                        v.part_id,
                        v.price,
                        v.barcode,
                        v.barcode1,
                        v.sname
                    from vw_warebase_kassa v
                    where (v.barcode = @barcode or v.barcode1 = @barcode)
                      and v.price > 0
                    order by v.updatedt desc";
                cmd.Parameters.AddWithValue("@barcode", barcode);
            }

            return ReadProduct(cmd, "VW_WAREBASE_KASSA");
        }

        private StandardNProductInfo? ReadProduct(FbCommand cmd, string source)
        {
            using var reader = cmd.ExecuteReader();
            if (!reader.Read()) return null;

            var partId = ReadInt32(reader, "PART_ID");
            var price = ReadDecimal(reader, "PRICE");

            return new StandardNProductInfo(
                partId,
                SelectProductBarcode(
                    ReadString(reader, "BARCODE"),
                    ReadString(reader, "BARCODE1"),
                    ReadString(reader, "BARCODE2")),
                ReadString(reader, "SNAME"),
                price,
                source);
        }

        private static string? SelectProductBarcode(params string?[] values)
        {
            // Some Standard-N schemas keep an internal code in BARCODE and the retail EAN in
            // BARCODE1/ORIG_BCODE_IZG. Prefer a valid GTIN-like value across all known columns.
            foreach (var raw in values)
            {
                var value = raw?.Trim();
                if (value == null || value.Length is not (8 or 12 or 13 or 14)) continue;
                if (value.All(ch => ch is >= '0' and <= '9')) return value;
            }

            return FirstNotEmpty(values);
        }

        private FbConnection OpenConnection()
        {
            var dbPath = ResolveDbPath(logIfMissing: true);
            if (string.IsNullOrWhiteSpace(dbPath))
                throw new InvalidOperationException("ztrade не найден");

            var candidates = BuildConnectionCandidates(dbPath);
            if (!string.IsNullOrWhiteSpace(_successfulConnectionKey))
            {
                candidates = candidates
                    .OrderByDescending(candidate => candidate.Key == _successfulConnectionKey)
                    .ToList();
            }

            Exception? authoritativeError = null;
            foreach (var candidate in candidates)
            {
                var csb = new FbConnectionStringBuilder
                {
                    DataSource = candidate.Host,
                    Port = candidate.Port,
                    Database = dbPath,
                    UserID = candidate.User,
                    Password = candidate.Password,
                    Charset = "WIN1251",
                    Pooling = true,
                    ConnectionTimeout = TimeoutSec(),
                };

                var conn = new FbConnection(csb.ToString());
                try
                {
                    conn.Open();
                    _successfulConnectionKey = candidate.Key;
                    if (!string.Equals(_loggedConnectionSource, candidate.Source, StringComparison.Ordinal))
                    {
                        _loggedConnectionSource = candidate.Source;
                        _log($"БД Стандарт-Н: подключение подтверждено " +
                             $"(источник={candidate.Source}, сервер={candidate.Host}:{candidate.Port})");
                    }
                    return conn;
                }
                catch (Exception ex)
                {
                    conn.Dispose();
                    authoritativeError ??= ex;
                }
            }

            // The first candidate is authoritative (the running cashier's options.ini when the
            // database was discovered there). Preserve its error instead of masking an auth failure
            // with a less relevant fallback error. Connection strings/passwords are never logged.
            throw authoritativeError ?? new InvalidOperationException("нет вариантов подключения к ztrade");
        }

        private List<DbConnectionCandidate> BuildConnectionCandidates(string dbPath)
        {
            _ = dbPath;
            var candidates = new List<DbConnectionCandidate>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            void Add(string source, string? host, string? user, string? password)
            {
                var normalizedHost = string.IsNullOrWhiteSpace(host) ? "localhost" : host.Trim();
                var normalizedUser = string.IsNullOrWhiteSpace(user) ? "SYSDBA" : user.Trim();
                var normalizedPassword = password ?? "";
                var key = $"{normalizedHost}|{_cfg.StandardNDbPort}|{normalizedUser}|{normalizedPassword}";
                if (!seen.Add(key)) return;
                candidates.Add(new DbConnectionCandidate(
                    source,
                    normalizedHost,
                    _cfg.StandardNDbPort,
                    normalizedUser,
                    normalizedPassword,
                    key));
            }

            // When the database path came from the running cashier's options.ini, that same file is
            // the authoritative source for host and credentials. This deliberately outranks stale
            // machine/user EPHARM_STANDARDN_DB_* variables left by older POSM installations.
            if (_resolvedFromOptionsIni)
            {
                Add(
                    "running Standard-N options.ini",
                    _optionsDbHost,
                    _optionsDbUser,
                    _optionsDbPassword);
            }

            Add(
                "posm.json/environment fallback",
                _cfg.StandardNDbHost,
                _cfg.StandardNDbUser,
                _cfg.StandardNDbPassword);

            return candidates;
        }

        private string? ResolveDbPath(bool logIfMissing)
        {
            if (!string.IsNullOrWhiteSpace(_resolvedDbPath)) return _resolvedDbPath;

            // 1) Явный путь из конфига — высший приоритет.
            if (!string.IsNullOrWhiteSpace(_cfg.StandardNDbPath))
            {
                _resolvedDbPath = _cfg.StandardNDbPath.Trim();
                _resolvedFromOptionsIni = false;
                return _resolvedDbPath;
            }

            // 2) Авто-поиск из РОДНОГО options.ini кассы ([Connect] base=…\ztrade.fdb) — так путь
            //    находится на любой боевой установке Стандарт-Н, а не только по демо-путям.
            var fromIni = TryDiscoverFromOptionsIni();
            if (!string.IsNullOrWhiteSpace(fromIni))
            {
                _resolvedDbPath = fromIni;
                _resolvedFromOptionsIni = true;
                if (logIfMissing) _log($"БД Стандарт-Н найдена по options.ini: {fromIni}");
                return _resolvedDbPath;
            }

            // 3) Реальная установка может не прописать base в options.ini. Ищем только внутри
            //    каталогов Standard-N/Apteka, никогда не сканируем весь диск рекурсивно.
            var discoveredFile = TryDiscoverZtradeFile();
            if (!string.IsNullOrWhiteSpace(discoveredFile))
            {
                _resolvedDbPath = discoveredFile;
                _resolvedFromOptionsIni = false;
                if (logIfMissing) _log($"БД Стандарт-Н найдена по файлу ztrade: {discoveredFile}");
                return _resolvedDbPath;
            }

            // 4) Демо-пути (fallback для VM).
            foreach (var p in DefaultDbPaths)
            {
                try
                {
                    if (File.Exists(p))
                    {
                        _resolvedDbPath = p;
                        _resolvedFromOptionsIni = false;
                        return _resolvedDbPath;
                    }
                }
                catch { }
            }

            if (logIfMissing)
                LogErrorRateLimited("БД Стандарт-Н не найдена: укажи StandardNDbPath в posm.json");
            return null;
        }

        private static string? TryDiscoverZtradeFile()
        {
            var roots = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                @"C:\Standart-N", @"C:\Standart-N_DEMO", @"C:\StandartN", @"C:\Standart_N",
                @"C:\Program Files\Standart-N", @"C:\Program Files (x86)\Standart-N",
            };

            try
            {
                foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady))
                {
                    foreach (var directory in Directory.EnumerateDirectories(drive.RootDirectory.FullName))
                    {
                        var name = Path.GetFileName(directory);
                        if (name.IndexOf("standart", StringComparison.OrdinalIgnoreCase) >= 0 ||
                            name.IndexOf("apteka", StringComparison.OrdinalIgnoreCase) >= 0)
                            roots.Add(directory);
                    }
                }
            }
            catch { }

            foreach (var root in roots)
            {
                try
                {
                    if (!Directory.Exists(root)) continue;
                    var candidate = Directory.EnumerateFiles(root, "ztrade*", SearchOption.AllDirectories)
                        .FirstOrDefault(path =>
                        {
                            var file = Path.GetFileName(path);
                            return file.Equals("ztrade", StringComparison.OrdinalIgnoreCase) ||
                                   file.Equals("ztrade.fdb", StringComparison.OrdinalIgnoreCase) ||
                                   file.Equals("ztrade.gdb", StringComparison.OrdinalIgnoreCase);
                        });
                    if (!string.IsNullOrWhiteSpace(candidate)) return candidate;
                }
                catch { }
            }
            return null;
        }

        /// <summary>
        /// Finds the database in the native Standard-N options.ini. Production installations
        /// use different root folders and may specify an extensionless ztrade path or a Firebird
        /// server-side path that is not visible through File.Exists on the POSM process.
        /// </summary>
        private string? TryDiscoverFromOptionsIni()
        {
            try
            {
                var roots = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
                {
                    @"C:\Standart-N_DEMO", @"C:\Standart-N", @"C:\StandartN", @"C:\Standart_N",
                    @"C:\Program Files\Standart-N", @"C:\Program Files (x86)\Standart-N",
                };

                try
                {
                    foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady))
                    {
                        foreach (var known in new[] { "Standart-N", "StandartN", "Standart_N", "Standart-N_DEMO" })
                            roots.Add(Path.Combine(drive.RootDirectory.FullName, known));

                        foreach (var directory in Directory.EnumerateDirectories(drive.RootDirectory.FullName))
                        {
                            var name = Path.GetFileName(directory);
                            if (name.IndexOf("standart", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                name.IndexOf("apteka", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                name.IndexOf("kassir", StringComparison.OrdinalIgnoreCase) >= 0)
                                roots.Add(directory);
                        }
                    }
                }
                catch { }

                var iniCandidates = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
                {
                    @"C:\STANDART-N\Kassir\options.ini",
                    @"C:\Standart-N\Kassir\options.ini",
                };

                // The running cashier directory is the most authoritative source when several
                // demo/backup Standard-N trees coexist on one workstation.
                try
                {
                    foreach (var process in Process.GetProcesses())
                    {
                        try
                        {
                            if (process.ProcessName.IndexOf("zkassa", StringComparison.OrdinalIgnoreCase) < 0)
                                continue;
                            var executable = process.MainModule?.FileName;
                            var directory = string.IsNullOrWhiteSpace(executable)
                                ? null
                                : Path.GetDirectoryName(executable);
                            if (!string.IsNullOrWhiteSpace(directory))
                                iniCandidates.Add(Path.Combine(directory!, "options.ini"));
                        }
                        catch { }
                        finally { process.Dispose(); }
                    }
                }
                catch { }

                foreach (var root in roots)
                {
                    try
                    {
                        if (!Directory.Exists(root)) continue;
                        foreach (var ini in Directory.EnumerateFiles(root, "options.ini", SearchOption.AllDirectories).Take(100))
                            iniCandidates.Add(ini);
                    }
                    catch { }
                }

                var cp1251 = Encoding.GetEncoding(1251);
                foreach (var ini in iniCandidates
                             .Where(File.Exists)
                             .OrderByDescending(path => IsRunningCashierOptions(path))
                             .ThenByDescending(path => SafeLastWriteTimeUtc(path)))
                {
                    try
                    {
                        var connect = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                        var section = "";
                        foreach (var raw in File.ReadAllLines(ini, cp1251))
                        {
                            var line = raw.Trim();
                            if (line.Length == 0 || line.StartsWith(";") || line.StartsWith("#"))
                                continue;
                            if (line.StartsWith("[") && line.EndsWith("]"))
                            {
                                section = line.Substring(1, line.Length - 2).Trim();
                                continue;
                            }
                            if (!string.Equals(section, "Connect", StringComparison.OrdinalIgnoreCase)) continue;
                            var eq = line.IndexOf('=');
                            if (eq < 0) continue;
                            connect[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim().Trim('"', '\'');
                        }

                        var databaseRaw = FirstDictionaryValue(
                            connect,
                            "base", "basename", "database", "databasename", "db", "dbpath", "dbname");
                        var database = NormalizeDatabaseValue(databaseRaw ?? "");
                        if (string.IsNullOrWhiteSpace(database)) continue;

                        _optionsDbHost = FirstDictionaryValue(connect, "server", "host", "datasource");
                        _optionsDbUser = FirstDictionaryValue(connect, "login", "user", "username");
                        _optionsDbPassword = FirstDictionaryValue(connect, "password", "pass", "pwd");
                        return database;
                    }
                    catch { }
                }
            }
            catch { }
            return null;
        }

        private static string? FirstDictionaryValue(
            IReadOnlyDictionary<string, string> values,
            params string[] keys)
        {
            foreach (var key in keys)
                if (values.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value))
                    return value.Trim();
            return null;
        }

        private static bool IsRunningCashierOptions(string optionsPath)
        {
            try
            {
                var directory = Path.GetDirectoryName(optionsPath);
                if (string.IsNullOrWhiteSpace(directory)) return false;
                return Process.GetProcesses().Any(process =>
                {
                    try
                    {
                        return process.ProcessName.IndexOf("zkassa", StringComparison.OrdinalIgnoreCase) >= 0 &&
                               string.Equals(
                                   Path.GetDirectoryName(process.MainModule?.FileName),
                                   directory,
                                   StringComparison.OrdinalIgnoreCase);
                    }
                    catch { return false; }
                    finally { process.Dispose(); }
                });
            }
            catch { return false; }
        }

        private static DateTime SafeLastWriteTimeUtc(string path)
        {
            try { return File.GetLastWriteTimeUtc(path); }
            catch { return DateTime.MinValue; }
        }

        private static string? NormalizeDatabaseValue(string raw)
        {
            var value = raw.Trim().Trim('"', '\'');
            if (value.Length == 0) return null;

            // localhost:C:\path\ztrade, host/3050:C:\path\ztrade.fdb -> C:\path\...
            var drivePath = value.IndexOf(":\\", StringComparison.Ordinal);
            if (drivePath > 0 && char.IsLetter(value[drivePath - 1]))
                value = value.Substring(drivePath - 1);

            // Accept local/UNC paths and Firebird aliases. Do not require File.Exists: the path
            // can be server-side and extensionless, while Firebird on localhost can still open it.
            if (Path.IsPathRooted(value) ||
                value.IndexOf("ztrade", StringComparison.OrdinalIgnoreCase) >= 0 ||
                value.EndsWith(".fdb", StringComparison.OrdinalIgnoreCase))
                return value;

            return null;
        }

        private T? SafeQuery<T>(Func<T?> query) where T : class
        {
            try
            {
                return query();
            }
            catch (Exception ex)
            {
                LogErrorRateLimited($"БД Стандарт-Н недоступна: {ex.Message}");
                return null;
            }
        }

        private void LogErrorRateLimited(string message)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - _lastErrorLog < TimeSpan.FromSeconds(60)) return;
            _lastErrorLog = now;
            _log(message);
        }

        private int TimeoutSec() => Math.Max(1, (int)Math.Ceiling(_cfg.StandardNDbTimeoutMs / 1000.0));

        private static string? FirstNotEmpty(params string?[] values)
        {
            foreach (var v in values)
                if (!string.IsNullOrWhiteSpace(v))
                    return v.Trim();
            return null;
        }

        private static string? ReadString(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? null : Convert.ToString(value, CultureInfo.InvariantCulture)?.Trim();
        }

        private static long? ReadInt64(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? null : Convert.ToInt64(value, CultureInfo.InvariantCulture);
        }

        private static int ReadInt32(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? 0 : Convert.ToInt32(value, CultureInfo.InvariantCulture);
        }

        private static decimal ReadDecimal(IDataRecord reader, string name)
        {
            var value = ReadValue(reader, name);
            return value == null ? 0m : Convert.ToDecimal(value, CultureInfo.InvariantCulture);
        }

        private static object? ReadValue(IDataRecord reader, string name)
        {
            try
            {
                var ordinal = reader.GetOrdinal(name);
                return reader.IsDBNull(ordinal) ? null : reader.GetValue(ordinal);
            }
            catch (IndexOutOfRangeException)
            {
                return null;
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            try { FbConnection.ClearAllPools(); } catch { }
        }
    }

    public sealed record StandardNActivePharmacist(string Id, string? Name, long? SessionId);

    public sealed record StandardNProductInfo(
        int PartId,
        string? Barcode,
        string? Name,
        decimal Price,
        string Source);

    public sealed record StandardNReceiptLine(
        long LineId,
        int PartId,
        string? Barcode,
        string Name,
        decimal Price,
        decimal Qty,
        decimal DiscountPercent);

    public sealed record StandardNReceiptSnapshot(
        long DocumentId,
        long SessionId,
        string? WorkstationName,
        DateTimeOffset? OpenedAt,
        StandardNActivePharmacist Pharmacist,
        IReadOnlyList<StandardNReceiptLine> Lines);
}
