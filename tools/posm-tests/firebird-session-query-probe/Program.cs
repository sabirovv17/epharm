using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using CustomerDisplay.Config;
using CustomerDisplay.Services;
using FirebirdSql.Data.FirebirdClient;

if (args is ["--self-test"])
{
    foreach (var invalid in new[] {
        "{\"host\":\"10.10.1.76\",\"port\":3050,\"user\":\"probe\",\"password\":\"unit-test\"}",
        "{\"host\":\"127.0.0.1\",\"port\":0,\"user\":\"probe\",\"password\":\"unit-test\"}",
        "{\"host\":\"127.0.0.1\",\"port\":3050,\"user\":\"probe\",\"password\":\"unit-test\",\"database\":\"vendor.fdb\"}",
        "{\"host\":\"127.0.0.1\",\"port\":3050,\"password\":\"unit-test\"}",
    })
    {
        try { Input.Parse(invalid); throw new Exception("Unsafe input was accepted"); }
        catch (ArgumentException) { }
        catch (JsonException) { }
    }
    Input.Parse("{\"host\":\"127.0.0.1\",\"port\":3050,\"user\":\"probe\",\"password\":\"unit-test\"}");
    foreach (var invalid in new[] { "ztrade.fdb", "Epharm-RpsSessionProbe-not-a-guid.fdb", "Epharm-RpsSessionProbe-" + new string('a', 31) + ".fdb" })
        if (OwnedDatabaseName(invalid)) throw new Exception("Unsafe database name was accepted");
    if (!OwnedDatabaseName("Epharm-RpsSessionProbe-" + Guid.NewGuid().ToString("N") + ".fdb")) throw new Exception("Owned name rejected");
    if (SafeDiagnostic("secret USER secret", "user", "SECRET") != "[redacted] [redacted] [redacted]") throw new Exception("Diagnostic redaction failed");
    if (SafeDiagnostic(new string('x', 5000), "unit-user", "unit-password").Length != 4096) throw new Exception("Diagnostic bound failed");
    if (SafeDiagnostic("a\0b\nc", "unit-user", "unit-password") != "ab\nc") throw new Exception("Diagnostic control filtering failed");
    Console.WriteLine("{\"selfTestsPassed\":12,\"databaseCalls\":0}");
    return 0;
}

if (args is not ["--connection-file", var connectionFile, "--output", var outputFile])
{
    Console.Error.WriteLine("Usage: FirebirdSessionQueryProbe --connection-file <private.json> --output <new-temp-proof.json>");
    return 2;
}
if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("The isolated probe runs only in the Windows VM.");
Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
var temp = PlainPath(@"C:\Windows\Temp");
var output = PlainPath(outputFile);
if (!string.Equals(Path.GetDirectoryName(output), temp, StringComparison.OrdinalIgnoreCase) || File.Exists(output))
    throw new ArgumentException("A new proof file directly in Windows Temp is required.");
var input = Input.Parse(File.ReadAllText(PlainPath(connectionFile)));
// No database path is accepted from input; this process owns a fresh, unguessable file only.
var database = PlainPath(Path.Combine(temp, "Epharm-RpsSessionProbe-" + Guid.NewGuid().ToString("N") + ".fdb"));
if (!OwnedDatabaseName(Path.GetFileName(database)) || File.Exists(database) || Directory.Exists(database))
    throw new ArgumentException("A fresh owned database is required.");
var connectionString = new FbConnectionStringBuilder {
    DataSource = input.Host, Port = input.Port, UserID = input.User, Password = input.Password,
    Database = database, Charset = "WIN1251", Dialect = 3, Pooling = false, ConnectionTimeout = 10,
}.ToString();
var cases = new List<object>();
var passed = true;
var created = false;
var dropped = false;
string? errorType = null;
int? databaseErrorCode = null;
string? sqlState = null;
string? diagnosticMessage = null;
var stage = "create-owned-database";
try
{
    FbConnection.CreateDatabase(connectionString, pageSize: 4096, forcedWrites: true, overwrite: false);
    created = true;
    PlainPath(database);
    using (var connection = Open())
    {
        Execute(connection, "create table WORKSTATIONS (ID bigint not null primary key, COMPNAME varchar(128))");
        Execute(connection, "create table USERS (ID integer not null primary key, INN varchar(12), USERNAME varchar(64))");
        Execute(connection, "insert into USERS(ID,USERNAME) values (1,'Probe Employee')");
        Execute(connection, "insert into USERS(ID,USERNAME) values (2,'Other Employee')");
    }
    stage = "fixture-matrix";
    Check("more-than-100-expired-before-active", "resolved", [.. Enumerable.Range(1, 160).Select(id => Row(id, heartbeatSeconds: -3600)), Row(1000)], expectedSession: "1000");
    Check("more-than-100-partial-hosts-before-active", "resolved", [.. Enumerable.Range(1, 160).Select(id => Row(id, host: "KASSA10")), Row(1000)], expectedSession: "1000");
    Check("more-than-100-noncashiers-before-active", "resolved", [.. Enumerable.Range(1, 160).Select(id => Row(id, program: "manager")), Row(1000)], expectedSession: "1000");
    Check("all-three-excluded-kinds-before-active", "resolved", [
        .. Enumerable.Range(1, 110).Select(id => Row(id, host: "KASSA10")),
        .. Enumerable.Range(201, 110).Select(id => Row(id, heartbeatSeconds: -3600)),
        .. Enumerable.Range(401, 110).Select(id => Row(id, program: "manager")), Row(1000)], expectedSession: "1000");
    Check("101-current-exact-candidates", "ambiguous_session", [.. Enumerable.Range(1, 101).Select(id => Row(id))]);
    Check("unknown-heartbeats-retained-before-limit", "ambiguous_session", [.. Enumerable.Range(1, 100).Select(id => Row(id, heartbeatSeconds: null)), Row(1000)]);
    Check("two-current-cashiers", "ambiguous_session", [Row(1), Row(2)]);
    Check("future-heartbeat-is-not-expired", "ambiguous_session", [Row(1), Row(2, heartbeatSeconds: 3600)]);
    Check("invalid-start-is-not-expired", "ambiguous_session", [Row(1), Row(2, startSeconds: 3600, heartbeatSeconds: -3600)]);
    Check("null-start-is-not-expired", "ambiguous_session", [Row(1), Row(2, startSeconds: null, heartbeatSeconds: -3600)]);
    Check("all-expired", "no_session", [Row(1, heartbeatSeconds: -3600)]);
    Check("foreign-host-is-not-local", "no_session", [Row(1, host: "KASSA10")]);
    Check("null-user-candidates-excluded-before-limit", "resolved", [.. Enumerable.Range(1, 160).Select(id => Row(id, user: null)), Row(1000)], expectedSession: "1000");
    foreach (var host in new[] { "KASSA1", "DOMAIN\\kassa1", "kassa1.example.local", "  DoMain\\kassa1.example.local  ", "\\\\KASSA1" })
        Check("canonical-host-" + cases.Count, "resolved", [Row(1, host: host, program: " KaSsIr.ExE  ")], expectedSession: "1");
    Check("numeric-heartbeat-is-not-a-timestamp", "unsupported_session_time", [Row(1)], heartbeatType: "bigint");
    Check("text-heartbeat-is-not-parsed", "unsupported_session_time", [Row(1)], heartbeatType: "varchar(40)");
    Check("date-heartbeat-is-not-full-time-evidence", "unsupported_session_time", [Row(1)], heartbeatType: "date");
    Check("missing-heartbeat-is-unknown", "unsupported_session_time", [Row(1)], heartbeatType: null);
    Check("missing-start-is-unknown", "unsupported_session_time", [Row(1)], startType: null);
    Check("101-unsupported-time-candidates-stay-ambiguous", "ambiguous_session", [.. Enumerable.Range(1, 101).Select(id => Row(id))], heartbeatType: "bigint");
    Check("expiry-clamp-lower-bound", "resolved", [Row(1, heartbeatSeconds: -11), Row(1000)], maxAge: 0, expectedSession: "1000");
    Check("expiry-clamp-upper-bound", "resolved", [Row(1, heartbeatSeconds: -3601), Row(1000)], maxAge: 7200, expectedSession: "1000");
}
catch (Exception error)
{
    errorType = error.GetType().Name;
    // Only this owned synthetic probe captures SQL diagnostics, never the ordinary POSM client.
    diagnosticMessage = SafeDiagnostic(error.Message, input.User, input.Password, connectionString);
    if (error is System.Data.Common.DbException databaseError)
    {
        databaseErrorCode = databaseError.ErrorCode;
        sqlState = databaseError.SqlState;
    }
    passed = false;
}
finally
{
    // All active commands/readers/connections have left their using scopes; clear only this process's pools.
    FbConnection.ClearAllPools();
    if (created)
    {
        try
        {
            PlainPath(database);
            if (!OwnedDatabaseName(Path.GetFileName(database)) || Path.GetDirectoryName(database) != temp)
                throw new InvalidOperationException("Owned database guard changed.");
            FbConnection.DropDatabase(connectionString);
            dropped = !File.Exists(database);
        }
        catch { dropped = false; }
    }
}
var proof = new { state = passed && dropped ? "passed" : "failed", cases, caseCount = cases.Count,
    databaseCreated = created, ownedDatabaseDropped = dropped, ownedDatabasePath = database,
    creationAttempted = true, ownedPoolCleared = true, vendorDatabaseTouched = false,
    productionQueriesReadOnly = true, errorType, databaseErrorCode, sqlState, stage, diagnosticMessage,
    providerVersion = typeof(FbConnection).Assembly.GetName().Version?.ToString() };
using (var outputStream = new FileStream(output, FileMode.CreateNew, FileAccess.Write, FileShare.None))
    JsonSerializer.Serialize(outputStream, proof, new JsonSerializerOptions { WriteIndented = true });
Console.WriteLine(JsonSerializer.Serialize(new { proof.state, proof.caseCount, proof.ownedDatabaseDropped, proof.errorType,
    proof.databaseErrorCode, proof.sqlState, proof.stage }));
return passed && dropped ? 0 : 1;

FbConnection Open()
{
    var connection = new FbConnection(connectionString);
    try { connection.Open(); return connection; }
    catch { connection.Dispose(); throw; }
}
void Execute(FbConnection connection, string sql)
{
    using var command = connection.CreateCommand();
    command.CommandTimeout = 15;
    command.CommandText = sql;
    command.ExecuteNonQuery();
}
ProbeRow Row(int id, string host = "KASSA1", string program = "zkassa", int? user = 1,
    int? startSeconds = -7200, int? heartbeatSeconds = 0) => new(id, host, program, user, startSeconds, heartbeatSeconds);
void Check(string name, string expected, ProbeRow[] rows, string? startType = "timestamp",
    string? heartbeatType = "timestamp", int maxAge = 300, string? expectedSession = null)
{
    stage = name + ":reset-fixture";
    FbConnection.ClearAllPools();
    using (var connection = Open())
    {
        if (cases.Count > 0) Execute(connection, "drop table SESSIONS");
        Execute(connection, "delete from WORKSTATIONS");
        stage = name + ":create-session-table";
        Execute(connection, "create table SESSIONS (ID bigint not null primary key, USER_ID integer, WS_ID bigint, PROG varchar(40), ENDDT timestamp" +
            (startType == null ? "" : ", STARTDT " + startType) + (heartbeatType == null ? "" : ", HEARTBEATS " + heartbeatType) + ")");
        var hosts = rows.Select(row => row.Host).Distinct().Select((host, index) => (host, id: index + 1)).ToDictionary(row => row.host, row => row.id);
        stage = name + ":insert-workstations";
        foreach (var host in hosts)
        {
            using var workstation = connection.CreateCommand();
            workstation.CommandText = "insert into WORKSTATIONS(ID,COMPNAME) values (@id,@host)";
            workstation.Parameters.AddWithValue("@id", host.Value);
            workstation.Parameters.AddWithValue("@host", host.Key);
            workstation.ExecuteNonQuery();
        }
        using var nowCommand = connection.CreateCommand();
        stage = name + ":read-source-clock";
        nowCommand.CommandText = "select cast(CURRENT_TIMESTAMP as timestamp) from RDB$DATABASE";
        var now = (DateTime)nowCommand.ExecuteScalar()!;
        using var transaction = connection.BeginTransaction();
        stage = name + ":insert-session-rows";
        foreach (var row in rows)
        {
            using var insert = connection.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText = "insert into SESSIONS(ID,USER_ID,WS_ID,PROG" +
                (startType == null ? "" : ",STARTDT") + (heartbeatType == null ? "" : ",HEARTBEATS") + ") values (@id,@user,@ws,@program" +
                (startType == null ? "" : ",@started") + (heartbeatType == null ? "" : ",@heartbeat") + ")";
            insert.Parameters.AddWithValue("@id", row.Id);
            insert.Parameters.AddWithValue("@user", row.User is { } user ? user : DBNull.Value);
            insert.Parameters.AddWithValue("@ws", hosts[row.Host]);
            insert.Parameters.AddWithValue("@program", row.Program);
            if (startType != null) AddTime(insert, "@started", startType, row.StartSeconds, now);
            if (heartbeatType != null) AddTime(insert, "@heartbeat", heartbeatType, row.HeartbeatSeconds, now);
            insert.ExecuteNonQuery();
        }
        transaction.Commit();
    }
    var config = new EpharmConfig { StandardNDbEnabled = true, StandardNDbHost = input.Host, StandardNDbPort = input.Port,
        StandardNDbUser = input.User, StandardNDbPassword = input.Password, StandardNDbPath = database,
        StandardNDbTimeoutMs = 10000, StaffSessionMaxAgeSec = maxAge };
    using var lookup = new StandardNDbLookup(config, _ => { }, "KASSA1");
    var queryRows = 0;
    stage = name + ":execute-actual-session-query";
    using (var connection = Open())
    using (var transaction = connection.BeginTransaction(new FbTransactionOptions {
        TransactionBehavior = FbTransactionBehavior.Read | FbTransactionBehavior.Concurrency | FbTransactionBehavior.Wait,
    }))
    using (var command = connection.CreateCommand())
    {
        command.Transaction = transaction;
        // The harness executes the actual internal SQL builder, not a separately maintained query.
        var query = typeof(StaffSessionResolver).Assembly.GetType("CustomerDisplay.Services.StaffSessionQuery")!
            .GetMethod("Build", System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)!;
        command.CommandText = (string)query.Invoke(null, [startType == "timestamp", heartbeatType == "timestamp"])!;
        command.Parameters.AddWithValue("@machine", "KASSA1");
        if (startType == "timestamp" && heartbeatType == "timestamp")
            command.Parameters.Add("@maxAge", FbDbType.Integer).Value = Math.Clamp(maxAge, 10, 3600);
        using var reader = command.ExecuteReader();
        while (reader.Read()) queryRows++;
    }
    stage = name + ":observe-session";
    var observation = lookup.ObserveStaffSession("42:" + DateTimeOffset.UtcNow.AddHours(-4).Ticks);
    var match = observation.Status == expected && (expectedSession == null || observation.Session?.ExternalSessionId == expectedSession);
    match &= queryRows <= StaffSessionResolver.MaximumObservationRows + 1;
    if (expected == "resolved") match &= queryRows == 1;
    cases.Add(new { name, expected, actual = observation.Status, sessionId = observation.Session?.ExternalSessionId, queryRows, passed = match });
    passed &= match;
    stage = name + ":complete";
}
void AddTime(FbCommand command, string parameter, string type, int? seconds, DateTime now)
{
    var fieldType = type == "bigint" ? FbDbType.BigInt : type.StartsWith("varchar") ? FbDbType.VarChar : type == "date" ? FbDbType.Date : FbDbType.TimeStamp;
    object value = seconds is not { } count ? DBNull.Value : type == "bigint" ? 1729500000L : type.StartsWith("varchar") ? "2026-11-06T10:00:00Z" : now.AddSeconds(count);
    command.Parameters.Add(parameter, fieldType).Value = value;
}
static string PlainPath(string path)
{
    if (!Path.IsPathRooted(path) || path.StartsWith(@"\\", StringComparison.Ordinal)) throw new ArgumentException("Local absolute path required.");
    var full = Path.GetFullPath(path);
    for (var current = full; !string.IsNullOrEmpty(current); current = Path.GetDirectoryName(current))
        if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
            throw new ArgumentException("Reparse paths are refused.");
    return full;
}
static bool OwnedDatabaseName(string name) => name.StartsWith("Epharm-RpsSessionProbe-", StringComparison.Ordinal) &&
    name.EndsWith(".fdb", StringComparison.Ordinal) && Guid.TryParseExact(name[23..^4], "N", out _);
static string SafeDiagnostic(string message, params string[] secrets)
{
    foreach (var secret in secrets.OrderByDescending(value => value.Length))
        if (!string.IsNullOrEmpty(secret)) message = message.Replace(secret, "[redacted]", StringComparison.OrdinalIgnoreCase);
    message = new string(message.Where(character => !char.IsControl(character) || character is '\n' or '\t').ToArray());
    return message.Length <= 4096 ? message : message[..4096];
}

sealed record ProbeRow(int Id, string Host, string Program, int? User, int? StartSeconds, int? HeartbeatSeconds);
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
sealed record Input(string Host, int Port, string User, string Password)
{
    public static Input Parse(string json)
    {
        var value = JsonSerializer.Deserialize<Input>(json, new JsonSerializerOptions {
            PropertyNameCaseInsensitive = true, RespectRequiredConstructorParameters = true,
        }) ?? throw new ArgumentException("Connection input required.");
        if (value.Host != "127.0.0.1" || value.Port is < 1 or > 65535 || string.IsNullOrWhiteSpace(value.User) ||
            value.User.Length > 128 || string.IsNullOrEmpty(value.Password) || value.Password.Length > 1024)
            throw new ArgumentException("Only explicit loopback test-server credentials are accepted.");
        return value;
    }
}
