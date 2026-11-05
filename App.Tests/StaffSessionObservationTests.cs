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

    [Theory]
    [InlineData("951212500015", "951212500015")]
    [InlineData(" 000101500011  ", "000101500011")]
    public void ExactTextIinIsTrimmedWithoutLosingLeadingZeroes(string raw, string expected)
    {
        Assert.Equal(new("available", expected), StaffSessionResolver.ReadIin([raw]));
    }

    [Theory]
    [InlineData("95121250001")]
    [InlineData("9512125000150")]
    [InlineData("95121250001x")]
    [InlineData("951212 500015")]
    [InlineData("９５１２１２５０００１５")]
    public void InvalidSourceIinIsNotReportedAsAnAvailableIdentity(string raw)
    {
        Assert.Equal(new("invalid"), StaffSessionResolver.ReadIin([raw]));
    }

    [Fact]
    public void NumericIinIsNeverPaddedOrConvertedAndMissingIinIsNotMismatch()
    {
        Assert.Equal(new("invalid"), StaffSessionResolver.ReadIin([951212500015L]));
        Assert.Equal(new("missing"), StaffSessionResolver.ReadIin([]));
        Assert.Equal(new("missing"), StaffSessionResolver.ReadIin([null]));
        Assert.Equal(new("missing"), StaffSessionResolver.ReadIin([DBNull.Value]));
        Assert.Equal(new("missing"), StaffSessionResolver.ReadIin(["  "]));
    }

    [Fact]
    public void MultipleSourceRowsRemainAmbiguousEvenWhenIinValuesAreEqual()
    {
        Assert.Equal(new("ambiguous"), StaffSessionResolver.ReadIin(["951212500015", "951212500015"]));
        Assert.Equal(new("ambiguous"), StaffSessionResolver.ReadIin([null, "000101500011"]));
    }

    [Theory]
    [InlineData("available", "000101500011")]
    [InlineData("missing", null)]
    [InlineData("invalid", null)]
    [InlineData("ambiguous", null)]
    [InlineData("unavailable", null)]
    public void IinOrAvailabilityChangeInvalidatesGrantAndAnEarlierLogin(string status, string? iin)
    {
        var original = Observe();
        original = original with { Session = original.Session! with { StandardNIin = "951212500015", StandardNIinStatus = "available" } };
        var state = new StaffSessionState();
        state.Observe(original);
        var generation = state.Generation;
        var oldKey = state.Observation.Session!.Key;
        Assert.True(state.Accept(generation, oldKey, "grant", Now.AddMinutes(5), Now));
        Assert.False(state.Observe(original));
        Assert.True(state.IsAuthenticated(Now));
        var changed = original with { Session = original.Session! with { StandardNIin = iin, StandardNIinStatus = status } };
        Assert.True(state.Observe(changed));
        Assert.False(state.IsAuthenticated(Now));
        Assert.False(state.Accept(generation, oldKey, "late-grant", Now.AddMinutes(5), Now));
    }

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

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void AnExpiredOrphanDoesNotHideTheSoleFreshLocalSession(bool reverse)
    {
        var fresh = Row();
        var expired = Row(session: 101, user: "previous-cashier") with {
            SessionStartedAt = Now.AddHours(-9).UtcDateTime,
            HeartbeatAt = Now.AddHours(-8).UtcDateTime,
        };
        var result = StaffSessionResolver.Resolve(reverse ? [expired, fresh] : [fresh, expired], "KASSA1", "10:1000");
        Assert.Equal("resolved", result.Status);
        Assert.Equal("100", result.Session!.ExternalSessionId);
        Assert.Equal("cashier-1", result.Session.ExternalUserId);
    }

    [Fact]
    public void AllExpiredLocalCandidatesRemainStaleWithoutAnIdentity()
    {
        var stale = Row() with { SessionStartedAt = Now.AddHours(-2).UtcDateTime, HeartbeatAt = Now.AddSeconds(-301).UtcDateTime };
        var result = StaffSessionResolver.Resolve([stale, stale with { SessionId = 101 }], "KASSA1", "10:1000");
        Assert.Equal("stale_session", result.Status);
        Assert.Null(result.Session);
    }

    [Fact]
    public void UnknownFutureOrInvalidTimesCannotBeDiscardedToPickAnotherSession()
    {
        var expired = Row(session: 101) with { SessionStartedAt = Now.AddHours(-2).UtcDateTime, HeartbeatAt = Now.AddHours(-1).UtcDateTime };
        foreach (var uncertain in new[] {
            expired with { HeartbeatAt = null }, expired with { DatabaseNow = null },
            expired with { SessionStartedAt = null }, expired with { HeartbeatAt = Now.AddSeconds(1).UtcDateTime },
            expired with { SessionStartedAt = Now.AddHours(1).UtcDateTime },
        }) {
            var result = StaffSessionResolver.Resolve([Row(), uncertain], "KASSA1", "10:1000");
            Assert.Equal("ambiguous_session", result.Status);
            Assert.Null(result.Session);
        }
    }

    [Theory]
    [InlineData(0, 10)]
    [InlineData(10, 10)]
    [InlineData(300, 300)]
    [InlineData(600, 600)]
    [InlineData(3600, 3600)]
    [InlineData(7200, 3600)]
    public void OrphanExpiryUsesTheSameInclusiveBoundaryAndClampedPolicy(int configured, int effective)
    {
        var boundary = Row(session: 101) with { SessionStartedAt = Now.AddHours(-2).UtcDateTime, HeartbeatAt = Now.AddSeconds(-effective).UtcDateTime };
        Assert.Equal("ambiguous_session", StaffSessionResolver.Resolve([Row(), boundary], "KASSA1", "10:1000", configured).Status);
        Assert.Equal("resolved", StaffSessionResolver.Resolve([Row(), boundary with { HeartbeatAt = boundary.HeartbeatAt!.Value.AddSeconds(-1) }], "KASSA1", "10:1000", configured).Status);
    }

    [Fact]
    public void IgnoringAnExpiredOrphanDoesNotRelaxTheCurrentProcessBoundaryOrGrantGeneration()
    {
        var orphan = Row(session: 101) with { SessionStartedAt = Now.AddHours(-2).UtcDateTime, HeartbeatAt = Now.AddHours(-1).UtcDateTime };
        var restartedProcess = "10:" + Now.AddSeconds(-10).Ticks;
        Assert.Equal("session_predates_process", StaffSessionResolver.Resolve([Row(), orphan], "KASSA1", restartedProcess, observerUtcNow: Now).Status);

        var state = new StaffSessionState();
        state.Observe(StaffSessionResolver.Resolve([Row(), orphan], "KASSA1", "10:1000"));
        var generation = state.Generation;
        var key = state.Observation.Session!.Key;
        Assert.True(state.Accept(generation, key, "grant", Now.AddMinutes(5), Now));
        state.Observe(StaffSessionResolver.Resolve([Row(user: "cashier-2"), orphan], "KASSA1", "10:1000"));
        Assert.False(state.IsAuthenticated(Now));
        Assert.False(state.Accept(generation, key, "late-grant", Now.AddMinutes(5), Now));
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
