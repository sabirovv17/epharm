using System;
using System.Collections.Generic;
using System.Linq;

namespace CustomerDisplay.Services;

public sealed record StaffCashSessionRow(long SessionId, string UserId, long? WorkstationId,
    string? ComputerName, string? Program, DateTime? SessionStartedAt = null,
    DateTime? HeartbeatAt = null, DateTime? DatabaseNow = null);
public sealed record StaffObservedSession(string ProcessIdentity, string ExternalUserId,
    string ExternalSessionId, string WorkstationId)
{
    public string Key => ProcessIdentity + ":" + WorkstationId + ":" + ExternalSessionId + ":" + ExternalUserId;
}
public sealed record StaffSessionObservation(string Status, StaffObservedSession? Session = null);

/// <summary>Authoritative staff observations never inherit the receipt-reader's heuristic fallbacks.</summary>
public static class StaffSessionResolver
{
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
        var candidates = rows.Where(row => CanonicalComputer(row.ComputerName) == machine &&
            row.SessionId > 0 && row.WorkstationId > 0 && !string.IsNullOrWhiteSpace(row.UserId) &&
            (string.Equals(row.Program?.Trim(), "zkassa", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(row.Program?.Trim(), "kassir", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(row.Program?.Trim(), "zkassa.exe", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(row.Program?.Trim(), "kassir.exe", StringComparison.OrdinalIgnoreCase))).ToArray();
        if (candidates.Length == 0) return new("no_session");
        if (candidates.Length != 1) return new("ambiguous_session");
        var item = candidates[0];
        if (item.HeartbeatAt == null || item.DatabaseNow == null || item.SessionStartedAt == null)
            return new("unsupported_session_time");
        var age = item.DatabaseNow.Value - item.HeartbeatAt.Value;
        if (age < TimeSpan.Zero) return new("future_session_time");
        if (age > TimeSpan.FromSeconds(Math.Clamp(maxAgeSec, 10, 3600))) return new("stale_session");
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
