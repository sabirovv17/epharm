using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffSessionObservationTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-16T10:00:00Z");
    private static StaffCashSessionRow Row(string computer = "KASSA1", long session = 100, string user = "cashier-1") =>
        new(session, user, 7, computer, "zkassa", Now.AddSeconds(-30).UtcDateTime, Now.UtcDateTime, Now.UtcDateTime);
    private static StaffSessionObservation Observe(string process = "10:1000", long session = 100, string user = "cashier-1") =>
        StaffSessionResolver.Resolve([Row(session: session, user: user)], "KASSA1", process);

    [Fact]
    public void VendorCountersStringsAndOffsetsCannotBeGuessedAsSessionTimestamps()
    {
        object?[] unsupported = [null, DBNull.Value, 0, 1729500000L, 1729500000.5m,
            "2026-10-16T10:00:00Z", Now];
        foreach (var raw in unsupported)
        {
            var parsed = StaffSessionResolver.ReadDatabaseTimestamp(raw);
            Assert.Null(parsed);
            Assert.Equal("unsupported_session_time", StaffSessionResolver.Resolve(
                [Row() with { HeartbeatAt = parsed }], "KASSA1", "10:1000").Status);
        }
    }

    [Fact]
    public void TypedDatabaseTimestampPreservesDatabaseWallClockWithoutTimezoneGuessing()
    {
        var raw = new DateTime(2026, 10, 16, 10, 0, 0, DateTimeKind.Utc);
        var parsed = StaffSessionResolver.ReadDatabaseTimestamp(raw);
        Assert.NotNull(parsed);
        Assert.Equal(raw.Ticks, parsed.Value.Ticks);
        Assert.Equal(DateTimeKind.Unspecified, parsed.Value.Kind);
    }

    [Theory]
    [InlineData("KASSA1")]
    [InlineData("DOMAIN\\kassa1")]
    [InlineData("kassa1.example.local")]
    public void LocalSessionIsResolvedWithoutAnyReceiptData(string computer)
    {
        var result = StaffSessionResolver.Resolve([Row(computer)], "KASSA1", "10:1000");
        Assert.Equal("resolved", result.Status);
        Assert.Equal("cashier-1", result.Session!.ExternalUserId);
        Assert.Equal("100", result.Session.ExternalSessionId);
    }

    [Theory]
    [InlineData("KASSA10")]
    [InlineData("KASSA")]
    [InlineData("OTHER")]
    [InlineData("")]
    public void SoleForeignOrPartiallyMatchingComputerIsNeverUsed(string computer)
    {
        Assert.Equal("no_session", StaffSessionResolver.Resolve([Row(computer)], "KASSA1", "10:1000").Status);
    }

    [Fact]
    public void TwoLocalSessionsAreAmbiguousRatherThanSortedByLastActivity()
    {
        Assert.Equal("ambiguous_session", StaffSessionResolver.Resolve([Row(), Row(session: 101)], "KASSA1", "10:1000").Status);
    }

    [Fact]
    public void MissingProcessOrMissingWorkstationCannotAuthenticate()
    {
        Assert.Equal("no_process", StaffSessionResolver.Resolve([Row()], "KASSA1", "").Status);
        Assert.Equal("unknown_workstation", StaffSessionResolver.Resolve([Row()], "", "10:1000").Status);
        Assert.Equal("no_session", StaffSessionResolver.Resolve([Row() with { WorkstationId = null }], "KASSA1", "10:1000").Status);
    }

    [Fact]
    public void ManagerSessionCannotSubstituteForCashier()
    {
        Assert.Equal("no_session", StaffSessionResolver.Resolve([Row() with { Program = "manager" }], "KASSA1", "10:1000").Status);
    }

    [Theory]
    [InlineData("11:1001", 100, "cashier-1")]
    [InlineData("10:1001", 100, "cashier-1")]
    [InlineData("10:1000", 101, "cashier-1")]
    [InlineData("10:1000", 100, "cashier-2")]
    public void ProcessRestartPidReuseAndCashierSwitchInvalidateEarlierGrant(string process, long session, string user)
    {
        var state = new StaffSessionState();
        state.Observe(Observe());
        Assert.True(state.Accept(state.Generation, state.Observation.Session!.Key, "grant-1", Now.AddMinutes(5), Now));
        state.Observe(Observe(process, session, user));
        Assert.False(state.IsAuthenticated(Now));
    }

    [Fact]
    public void StablePollingDoesNotPromptRepeatedLogin()
    {
        var state = new StaffSessionState();
        Assert.True(state.Observe(Observe()));
        state.Accept(state.Generation, state.Observation.Session!.Key, "grant-1", Now.AddMinutes(5), Now);
        Assert.False(state.Observe(Observe()));
        Assert.True(state.IsAuthenticated(Now));
    }

    [Fact]
    public void LateLoginCannotAuthorizeNewCashierOrSwitchAwayAndBack()
    {
        var state = new StaffSessionState();
        state.Observe(Observe());
        var generation = state.Generation;
        var key = state.Observation.Session!.Key;
        state.Observe(Observe(user: "cashier-2"));
        state.Observe(Observe());
        Assert.False(state.Accept(generation, key, "stale-grant", Now.AddMinutes(5), Now));
    }

    [Theory]
    [InlineData("no_session")]
    [InlineData("database_unavailable")]
    [InlineData("ambiguous_session")]
    public void UnknownObservationClearsMemoryGrant(string status)
    {
        var state = new StaffSessionState();
        state.Observe(Observe());
        state.Accept(state.Generation, state.Observation.Session!.Key, "grant-1", Now.AddMinutes(5), Now);
        state.Observe(new(status));
        Assert.False(state.IsAuthenticated(Now));
        Assert.Null(state.Grant);
    }

    [Fact]
    public void ExpiredGrantIsClearedRatherThanReused()
    {
        var state = new StaffSessionState();
        state.Observe(Observe());
        state.Accept(state.Generation, state.Observation.Session!.Key, "grant-1", Now.AddSeconds(10), Now);
        Assert.False(state.IsAuthenticated(Now.AddSeconds(10)));
        Assert.Null(state.Grant);
    }

    [Fact]
    public void StaleFutureAndMissingHeartbeatDoNotResolvePersonalSession()
    {
        Assert.Equal("stale_session", StaffSessionResolver.Resolve([Row() with { HeartbeatAt = Now.AddSeconds(-301).UtcDateTime }], "KASSA1", "10:1000").Status);
        Assert.Equal("future_session_time", StaffSessionResolver.Resolve([Row() with { HeartbeatAt = Now.AddSeconds(1).UtcDateTime }], "KASSA1", "10:1000").Status);
        Assert.Equal("unsupported_session_time", StaffSessionResolver.Resolve([Row() with { HeartbeatAt = null }], "KASSA1", "10:1000").Status);
    }

    [Fact]
    public void PolicyIsConfigurableAndSessionCannotPrecedeRestartedProcess()
    {
        var row = Row() with { HeartbeatAt = Now.AddSeconds(-400).UtcDateTime, SessionStartedAt = Now.AddMinutes(-10).UtcDateTime };
        Assert.Equal("resolved", StaffSessionResolver.Resolve([row], "KASSA1", "10:1000", 600).Status);
        var process = "10:" + Now.AddSeconds(-10).Ticks;
        Assert.Equal("session_predates_process", StaffSessionResolver.Resolve([Row()], "KASSA1", process, observerUtcNow: Now).Status);
        Assert.Equal("resolved", StaffSessionResolver.Resolve([Row() with { SessionStartedAt = Now.AddSeconds(-5).UtcDateTime }], "KASSA1", process, observerUtcNow: Now).Status);
    }

    [Fact]
    public void ProcessAndDatabaseClockOffsetDoesNotRequireMatchingTimezones()
    {
        var dbNow = Now.AddHours(6).DateTime;
        var row = Row() with { DatabaseNow = dbNow, HeartbeatAt = dbNow.AddSeconds(-1), SessionStartedAt = dbNow.AddSeconds(-5) };
        var process = "10:" + Now.AddSeconds(-10).Ticks;
        Assert.Equal("resolved", StaffSessionResolver.Resolve([row], "KASSA1", process, observerUtcNow: Now).Status);
    }

    [Fact]
    public void ExplicitLogoutInvalidatesAnInFlightLoginEvenForTheSameCashier()
    {
        var state = new StaffSessionState();
        state.Observe(Observe());
        var generation = state.Generation;
        var key = state.Observation.Session!.Key;
        state.Invalidate();
        Assert.False(state.Accept(generation, key, "late-grant", Now.AddMinutes(5), Now));
        Assert.True(state.Accept(state.Generation, key, "new-grant", Now.AddMinutes(5), Now));
    }
}
