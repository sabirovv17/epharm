using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Tests;

public sealed class StandardNUserNameCacheTests
{
    [Fact]
    public void SessionAuditNameQueryRequiresTheSameNativeUser()
    {
        Assert.Contains("SESSION_ID = @sessionId and USER_ID = @userId", StandardNDbLookup.ExactSessionUserNameSql);
    }

    private sealed class Clock : TimeProvider
    {
        public long Ticks;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override long GetTimestamp() => Ticks;
        public void Advance(TimeSpan interval) => Ticks += interval.Ticks;
    }

    [Fact]
    public void MissingNameIsRetriedWithoutRestartingPosm()
    {
        var clock = new Clock(); var cache = new StandardNUserNameCache(clock);
        cache.Store("source-a", "17", null);
        Assert.True(cache.TryGet("source-a", "17", out var missing)); Assert.Null(missing);
        clock.Advance(TimeSpan.FromSeconds(30));
        Assert.False(cache.TryGet("source-a", "17", out _));
        cache.Store("source-a", "17", "Synthetic Updated");
        Assert.True(cache.TryGet("source-a", "17", out var updated)); Assert.Equal("Synthetic Updated", updated);
    }

    [Fact]
    public void PositiveNameExpiresAndRawIdCannotCrossDatabases()
    {
        var clock = new Clock(); var cache = new StandardNUserNameCache(clock);
        cache.Store("source-a", "17", "Synthetic First");
        clock.Advance(TimeSpan.FromMinutes(4));
        Assert.True(cache.TryGet("source-a", "17", out _));
        clock.Advance(TimeSpan.FromMinutes(1));
        Assert.False(cache.TryGet("source-a", "17", out _));
        cache.Store("source-a", "17", "Synthetic First");
        Assert.False(cache.TryGet("source-b", "17", out _));
        cache.Store("source-b", "17", "Synthetic Other");
        Assert.True(cache.TryGet("source-b", "17", out var other)); Assert.Equal("Synthetic Other", other);
        Assert.False(cache.TryGet("source-a", "17", out _));
    }

    [Fact]
    public void UnknownSourceAndOtherUserCannotReuseAnAuditName()
    {
        var cache = new StandardNUserNameCache();
        cache.Store(null, "17", "Synthetic Unbound");
        Assert.False(cache.TryGet(null, "17", out _));
        cache.Store("source-a", "17", "Synthetic Bound");
        Assert.False(cache.TryGet("source-a", "18", out _));
        Assert.False(cache.TryGet(null, "17", out _));
        Assert.False(cache.TryGet("source-a", "17", out _));
    }
}
