using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace CustomerDisplay.Services;

public sealed record StaffCashSessionRow(long SessionId, string UserId, long? WorkstationId,
    string? ComputerName, string? Program, DateTime? SessionStartedAt = null,
    DateTime? HeartbeatAt = null, DateTime? DatabaseNow = null);
public sealed record StaffObservedSession(string ProcessIdentity, string ExternalUserId,
    string ExternalSessionId, string WorkstationId, string? StandardNIin = null,
    string StandardNIinStatus = "unavailable", string? StandardNFullName = null,
    string StandardNFullNameStatus = "unavailable")
{
    public string Key => ProcessIdentity + ":" + WorkstationId + ":" + ExternalSessionId + ":" + ExternalUserId +
        ":" + StandardNIinStatus + ":" + StandardNIin + ":" + StandardNFullNameStatus + ":" + StandardNFullName;
}
public sealed record StaffSessionObservation(string Status, StaffObservedSession? Session = null);
public sealed record StaffIinObservation(string Status, string? Iin = null);
public sealed record StaffNameObservation(string Status, string? FullName = null);

/// <summary>Authoritative staff observations never inherit the receipt-reader's heuristic fallbacks.</summary>
public static class StaffSessionResolver
{
    public const int MaximumObservationRows = 100;
    private static readonly HashSet<string> GenericNames = new(StringComparer.OrdinalIgnoreCase) {
        "админ", "администратор", "менеджер", "кассир", "касса", "оператор", "пользователь", "сотрудник",
        "фармацевт", "аптека", "тест", "демо", "әкімші", "пайдаланушы", "дәріхана", "сынақ",
        "cashier", "admin", "administrator", "user", "operator", "employee", "demo", "test", "manager",
    };
    private static readonly Regex FullNameWord = new(@"\A\p{L}+(?:[-'’]\p{L}+)*\z", RegexOptions.CultureInvariant);

    public static StaffNameObservation ReadFullName(IEnumerable<object?> values)
    {
        var rows = values.Take(2).ToArray();
        if (rows.Length > 1) return new("ambiguous");
        if (rows.Length == 0 || rows[0] == null || rows[0] == DBNull.Value) return new("missing");
        if (rows[0] is not string value || value.Length > 255) return new("invalid");
        var parts = value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length == 0) return new("missing");
        if (parts.Length < 2 || parts.Any(part => GenericNames.Contains(part) || part.Count(char.IsLetter) < 2 ||
            !FullNameWord.IsMatch(part))) return new("invalid");
        return new("available", string.Join(" ", parts));
    }

    // The vendor's USERS.INN is text. Never invent leading zeroes or infer IIN from a name/login.
    public static StaffIinObservation ReadIin(IEnumerable<object?> values)
    {
        var rows = values.Take(2).ToArray();
        if (rows.Length > 1) return new("ambiguous");
        if (rows.Length == 0 || rows[0] == null || rows[0] == DBNull.Value) return new("missing");
        if (rows[0] is not string value) return new("invalid");
        var iin = value.Trim();
        if (iin.Length == 0) return new("missing");
        return iin.Length == 12 && iin.All(character => character is >= '0' and <= '9')
            ? new("available", iin) : new("invalid");
    }

    // A vendor counter/string is not a timestamp; never guess a unit or parse it as personal-session proof.
    public static DateTime? ReadDatabaseTimestamp(object? value) => value is DateTime timestamp
        ? DateTime.SpecifyKind(timestamp, DateTimeKind.Unspecified)
        : null;

    public static string CanonicalComputer(string? value)
    {
        var name = (value ?? "").Trim();
        var slash = name.LastIndexOf('\\');
        if (slash >= 0) name = name[(slash + 1)..];
        var dot = name.IndexOf('.');
        if (dot > 0) name = name[..dot];
        return name.ToUpperInvariant();
    }

    public static StaffSessionObservation Resolve(IEnumerable<StaffCashSessionRow> rows,
        string machineName, string processIdentity, int maxAgeSec = 300, DateTimeOffset? observerUtcNow = null)
    {
        if (string.IsNullOrWhiteSpace(processIdentity)) return new("no_process");
        var machine = CanonicalComputer(machineName);
        if (machine.Length == 0) return new("unknown_workstation");
        // SQL narrows exact, possibly current candidates before this limit; overflow still never proves one identity.
        var snapshot = rows.Take(MaximumObservationRows + 1).ToArray();
        if (snapshot.Length > MaximumObservationRows) return new("ambiguous_session");
        var candidates = snapshot.Where(row => CanonicalComputer(row.ComputerName) == machine &&
            row.SessionId > 0 && row.WorkstationId > 0 && !string.IsNullOrWhiteSpace(row.UserId) &&
            (string.Equals(row.Program?.Trim(), "zkassa", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(row.Program?.Trim(), "kassir", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(row.Program?.Trim(), "zkassa.exe", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(row.Program?.Trim(), "kassir.exe", StringComparison.OrdinalIgnoreCase))).ToArray();
        if (candidates.Length == 0) return new("no_session");
        var maxAge = TimeSpan.FromSeconds(Math.Clamp(maxAgeSec, 10, 3600));
        // A crashed cashier can leave an unclosed row; discard only provably expired observations.
        candidates = candidates.Where(row => row.SessionStartedAt == null || row.HeartbeatAt == null ||
            row.DatabaseNow == null || row.SessionStartedAt > row.HeartbeatAt ||
            row.DatabaseNow.Value - row.HeartbeatAt.Value <= maxAge).ToArray();
        if (candidates.Length == 0) return new("stale_session");
        if (candidates.Length != 1) return new("ambiguous_session");
        var item = candidates[0];
        if (item.HeartbeatAt == null || item.DatabaseNow == null || item.SessionStartedAt == null)
            return new("unsupported_session_time");
        var age = item.DatabaseNow.Value - item.HeartbeatAt.Value;
        if (age < TimeSpan.Zero) return new("future_session_time");
        if (age > maxAge) return new("stale_session");
        if (item.SessionStartedAt > item.HeartbeatAt) return new("invalid_session_time");
        var colon = processIdentity.LastIndexOf(':');
        if (colon < 0 || !long.TryParse(processIdentity[(colon + 1)..], out var ticks) || ticks < 0 || ticks > DateTime.MaxValue.Ticks)
            return new("unsupported_process_time");
        var processAge = (observerUtcNow ?? DateTimeOffset.UtcNow) - new DateTimeOffset(new DateTime(ticks, DateTimeKind.Utc));
        if (processAge < TimeSpan.Zero) return new("unsupported_process_time");
        // Compare ages, not Windows/DB wall-clock timestamps (their zones/clocks can differ).
        // Two seconds cover timestamp precision and a bounded observer query. This is an
        // observation consistency check, not proof of process-to-DB-session ownership.
        if (item.DatabaseNow.Value - item.SessionStartedAt.Value > processAge.Add(TimeSpan.FromSeconds(2)))
            return new("session_predates_process");
        return new("resolved", new(processIdentity, item.UserId.Trim(),
            item.SessionId.ToString(System.Globalization.CultureInfo.InvariantCulture),
            item.WorkstationId!.Value.ToString(System.Globalization.CultureInfo.InvariantCulture)));
    }
}

internal static class StaffSessionQuery
{
    public static string Build(bool startedAtTimestamp, bool heartbeatTimestamp)
    {
        var startedAt = startedAtTimestamp ? "s.STARTDT" : "cast(null as timestamp)";
        var heartbeat = heartbeatTimestamp ? "s.HEARTBEATS" : "cast(null as timestamp)";
        // These predicates run before FIRST: unrelated and provably expired rows cannot exhaust its sentinel.
        var expiry = startedAtTimestamp && heartbeatTimestamp ? @"
            and (s.STARTDT is null or s.HEARTBEATS is null or s.STARTDT > s.HEARTBEATS
                or s.HEARTBEATS >= dateadd(second, -cast(@maxAge as integer), cast(CURRENT_TIMESTAMP as timestamp)))" : "";
        return $@"select first {StaffSessionResolver.MaximumObservationRows + 1}
            s.ID as SESSION_ID, s.USER_ID, s.WS_ID, s.PROG, w.COMPNAME,
            {startedAt} as STARTDT, {heartbeat} as HEARTBEATS,
            cast(CURRENT_TIMESTAMP as timestamp) as DB_NOW
            from SESSIONS s join (
                select ws_host.ID, ws_host.COMPNAME,
                    case when position('.', ws_host.LOCAL_NAME) > 1
                        then substring(ws_host.LOCAL_NAME from 1 for position('.', ws_host.LOCAL_NAME) - 1)
                        else ws_host.LOCAL_NAME end as CANONICAL_NAME
                from (
                    select ws.ID, ws.COMPNAME,
                        case when position('\', reverse(trim(ws.COMPNAME))) > 0
                            then substring(trim(ws.COMPNAME) from char_length(trim(ws.COMPNAME))
                                - position('\', reverse(trim(ws.COMPNAME))) + 2)
                            else trim(ws.COMPNAME) end as LOCAL_NAME
                    from WORKSTATIONS ws
                ) ws_host
            ) w on w.ID = s.WS_ID
            where s.ENDDT is null and upper(w.CANONICAL_NAME) = @machine
                and upper(trim(s.PROG)) in ('ZKASSA', 'KASSIR', 'ZKASSA.EXE', 'KASSIR.EXE')
                and s.ID > 0 and s.WS_ID > 0 and s.USER_ID is not null
                and trim(cast(s.USER_ID as varchar(128))) <> ''{expiry}";
    }
}

/// <summary>Generation rejects a response from an earlier user/process, including switch-away-and-back.</summary>
public sealed class StaffSessionState
{
    public StaffSessionObservation Observation { get; private set; } = new("initializing");
    public long Generation { get; private set; }
    public string? Grant { get; private set; }
    public DateTimeOffset? ExpiresAt { get; private set; }

    public bool Observe(StaffSessionObservation observation)
    {
        if (Observation.Status == observation.Status && Observation.Session?.Key == observation.Session?.Key) return false;
        Observation = observation;
        Generation++;
        ClearGrant();
        return true;
    }

    public bool Accept(long generation, string observedKey, string grant, DateTimeOffset expiresAt, DateTimeOffset now)
    {
        if (generation != Generation || Observation.Status != "resolved" ||
            Observation.Session?.Key != observedKey || string.IsNullOrWhiteSpace(grant) || expiresAt <= now) return false;
        Grant = grant;
        ExpiresAt = expiresAt;
        return true;
    }

    public bool IsAuthenticated(DateTimeOffset now)
    {
        if (Grant != null && ExpiresAt > now) return true;
        ClearGrant();
        return false;
    }

    public void ClearGrant() { Grant = null; ExpiresAt = null; }
    public void Invalidate() { Generation++; ClearGrant(); }
}
